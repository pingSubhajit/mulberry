# Profile and Partner Settings Requirements

This note captures the functional requirements implied by the latest profile and partner settings designs. It intentionally avoids detailed UI styling; the first implementation can use the existing settings structure with bare-bones controls, followed by a separate UI pass.

## New Functionality To Build

- Editable user profile
  - Allow the signed-in user to update their display name after onboarding.
  - Continue showing email as account identity from Google; email should remain read-only.
  - Persist changes through the backend and refresh local bootstrap/session state after save.

- Custom user profile picture
  - Add support for a user-managed profile image instead of relying only on Google profile photos.
  - Requires image picker, upload/storage, backend field, bootstrap exposure, local caching, and fallback to Google photo when no custom image exists.

- Editable partner profile details
  - Allow a user to edit partner-facing relationship details: partner name, relationship anniversary, relationship label/details if we decide to store one, and partner profile picture.
  - These are local-to-this-user relationship metadata unless we explicitly decide the partner can see or approve them.
  - Persist through backend and refresh bootstrap state after save.

- Invite-time partner details
  - The invite sheet must provide a way to edit partner name and relationship anniversary after onboarding.
  - Selecting "change partner info" should open a simple in-sheet details step with partner name and relationship anniversary fields.
  - Invite creation must require current partner details. If partner name or anniversary is missing, the user must enter them before an invite code can be created or shared.
  - This prevents a new partner from inheriting stale partner details that were entered for an older relationship.

- Partner profile edit cooldown
  - Partner details must not be editable at any time.
  - A user can update partner name, relationship details, relationship anniversary, or partner profile picture only once every 72 hours.
  - Backend should enforce the cooldown, not just the app.
  - Backend should return the next allowed update time so the app can show a clear locked state such as "in 36 hours."
  - Need to decide whether the cooldown is shared across all partner fields or tracked separately per field. Current requirement implies one shared cooldown for any partner profile update.
  - The invite-time edit step is still a partner details edit and should use the same 72-hour cooldown once the user has active partner details.
  - If old partner details were cleared after disconnect, entering required details for a new invite should be allowed because there is no active partner metadata to edit.

- Paired duration
  - Expose and display how long the couple has been paired, based on `pair_sessions.created_at`.
  - Backend can return `pairedAt`; app can format "48 days and counting" locally.

- Daily streak
  - Define what counts as a streak day, most likely at least one completed drawing operation in the pair within the local day boundary.
  - Track durable daily activity and expose current streak in bootstrap or a settings summary endpoint.
  - Decide whether streak is pair-level or per-user. The design implies pair-level.

- Pending/unpaired relationship summary
  - Settings should support both paired and unpaired/pending states.
  - For unpaired users, expose enough state to show that the user is waiting for a partner and that sync is unpaired.

## Existing Functionality To Change

- Extend profile update API
  - Current `PUT /me/profile` is onboarding-oriented and requires display name, partner name, and anniversary together.
  - Split or relax this so profile edits can update only the relevant user profile fields.
  - Keep onboarding validation separate from post-onboarding edit validation.

- Extend bootstrap data
  - Current bootstrap already includes user email/photo/name, partner photo/name, anniversary, pairing status, and pair session ID.
  - Add fields needed by settings: custom user photo URL, custom partner photo URL if separate, paired-at timestamp, streak summary, partner edit cooldown status, and next partner edit time.

- Change partner settings from read-only to controlled-edit
  - Existing settings treat partner details as read-only except disconnect.
  - Update this to allow partner metadata edits, but only through the 72-hour cooldown gate.
  - Keep disconnect/unpair as an explicit destructive action with confirmation.

- Clear stale partner metadata after disconnect
  - Current disconnect deletes the pair session but preserves `partner_display_name` and `anniversary_date`.
  - Change disconnect so old partner details are detached from the user's account when the pair is removed.
  - After a user has been paired and then becomes unpaired, they should not be able to create/share a new invite until they re-enter partner name and relationship anniversary.
  - This also prevents invite redemption from hydrating a new partner/device with the old partner's name or anniversary.

- Tighten invite creation validation
  - Current invite creation only checks `onboardingCompleted`, which can remain true because old partner details still exist.
  - Invite creation should validate that the inviter has active, current partner details for the intended invite.
  - If details are missing, the backend should return a clear validation error and the app should route the user to the invite-sheet partner details step.

- Add backend storage for edit metadata
  - Store `partner_profile_updated_at` or equivalent to enforce the 72-hour cooldown.
  - If custom photos are introduced, store image object paths/URLs and cleanup rules for replaced images.

- Adjust sync status semantics
  - Current app has lower-level WebSocket sync states and FCM registration diagnostics.
  - User-facing settings should reduce this to relationship/sync availability states such as connected, connecting, recovering, error, or unpaired.

- Keep developer/support tools separate
  - Existing Developer Options include force sync, FCM status, reset state, feature overrides, and debug notification.
  - Keep those out of normal profile/partner settings while adding the new user-facing fields.

- Adjust pairing disconnect behavior
  - Preserve the user's own profile details and account identity.
  - Clear or detach relationship-specific partner metadata from the account when the pair is disconnected.
  - Decide separately whether streaks, notes, or relationship history should be retained, hidden, or deleted.

## Implementation Batches

### Batch 1: Fix Partner Metadata Lifecycle

1. Update the backend data model to distinguish user-owned profile fields from relationship-specific partner metadata.
2. Add storage for partner metadata edit tracking, including a timestamp such as `partner_profile_updated_at`.
3. Change disconnect behavior so unpairing clears or detaches old partner metadata while preserving the user's own profile and account identity.
4. Add backend tests proving that disconnect removes stale partner name and anniversary from the user's active invite/profile state.
5. Update bootstrap mapping so unpaired users who disconnected from a previous partner no longer receive old partner details as active partner metadata.

### Batch 2: Tighten Invite Creation And Redemption

1. Add backend validation that invite creation requires current partner name and relationship anniversary.
2. Return a clear validation error when invite creation is attempted without required partner details.
3. Ensure existing active invite reuse still respects the required partner metadata state.
4. Update invite redemption so a new recipient cannot inherit stale partner details from a previous relationship.
5. Add backend tests covering: fresh onboarding invite, disconnected user creating a new invite, missing partner details, and new recipient hydration.

### Batch 3: Add Partner Details Update API

1. Add a backend endpoint for updating partner details after onboarding.
2. Support partner name and relationship anniversary first.
3. Enforce the 72-hour cooldown on partner detail updates when active partner metadata already exists.
4. Allow required partner details to be entered after disconnect when no active partner metadata exists.
5. Return cooldown state and next allowed update time in the response.
6. Add backend tests for allowed update, blocked update inside 72 hours, and allowed update after the cooldown.

### Batch 4: Extend Bootstrap Contract

1. Add bootstrap fields needed by bare-bones UI: `pairedAt`, partner edit cooldown state, and next partner edit time.
2. Keep existing fields for `partnerDisplayName`, `anniversaryDate`, `partnerPhotoUrl`, and pairing status.
3. Decide whether streak summary belongs in bootstrap now or in a later dedicated summary endpoint.
4. Update Android API models and local bootstrap storage for the new fields.
5. Add mapping tests or focused app-side checks for nullable unpaired states.

### Batch 5: Bare-Bones Android Invite Flow

1. Add a "change partner info" action to the existing invite bottom sheet.
2. Add an in-sheet partner details step with partner name and relationship anniversary fields.
3. If invite creation fails because details are missing, route the user to the partner details step.
4. Save partner details through the new backend endpoint, refresh bootstrap, then create or reload the invite.
5. Show cooldown-blocked state using the backend next allowed update time.
6. Keep the UI minimal and consistent with the current bottom sheet; defer visual redesign.

### Batch 6: Bare-Bones Settings Profile And Partner Edits

1. Split profile editing from onboarding so the user's display name can be changed after signup.
2. Add bare-bones settings controls for partner name and anniversary edits using the same backend endpoint and cooldown rules.
3. Keep email read-only.
4. Keep disconnect behind confirmation and ensure the app refreshes bootstrap after disconnect.
5. Ensure unpaired users with missing partner metadata are guided to enter details before creating an invite.

### Batch 7: Custom Photos

1. Add backend storage fields for custom user profile photo and custom partner profile photo.
2. Add upload, replacement, and cleanup behavior for stored images.
3. Add Android image picker and upload flow.
4. Expose custom photo URLs in bootstrap with fallback to Google photo where appropriate.
5. Apply the 72-hour cooldown to partner profile photo changes.

### Batch 8: Relationship Metrics

1. Expose paired duration from `pair_sessions.created_at` as `pairedAt`.
2. Define daily streak rules, including timezone and whether the streak is pair-level or per-user.
3. Add backend tracking for daily drawing activity.
4. Add current streak to bootstrap or a dedicated settings summary endpoint.
5. Add tests for streak start, continuation, missed day reset, and disconnect behavior.

### Batch 9: UI Pass

1. Replace bare-bones invite, profile, and partner settings screens with the designed visual treatment.
2. Format paired duration, cooldown copy, dates, and sync status for the final product UI.
3. Add empty, pending, paired, cooldown-blocked, saving, and error states.
4. Verify the complete flow on fresh onboarding, paired user, disconnected user, and re-pairing scenarios.
