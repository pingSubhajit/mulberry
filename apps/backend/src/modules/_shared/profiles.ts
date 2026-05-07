import type { Database } from "../../infra/db/database.js"
import type { ProfileRecord } from "../../contracts/dbRecords.js"
import { normalizeDateString, normalizeTimestampString } from "./dates.js"

export async function ensureProfileRow(
  db: Pick<Database, "query">,
  userId: string,
  displayName: string | null,
): Promise<void> {
  await db.query(
    `
      INSERT INTO user_profiles (user_id, display_name)
      VALUES ($1, $2)
      ON CONFLICT (user_id) DO NOTHING
      `,
    [userId, displayName],
  )
}

export async function getProfileFrom(
  db: Pick<Database, "query">,
  userId: string,
): Promise<ProfileRecord | null> {
  const rows = await db.query<{
    user_id: string
    display_name: string | null
    partner_display_name: string | null
    anniversary_date: string | Date | null
    onboarding_completed_at: string | Date | null
    partner_profile_updated_at: string | Date | null
    profile_photo_path: string | null
    partner_profile_photo_path: string | null
  }>(
    `
      SELECT user_id, display_name, partner_display_name, anniversary_date,
        onboarding_completed_at, partner_profile_updated_at,
        profile_photo_path, partner_profile_photo_path
      FROM user_profiles
      WHERE user_id = $1
      `,
    [userId],
  )
  const row = rows.rows[0]
  if (!row) {
    return null
  }
  return {
    user_id: row.user_id,
    display_name: row.display_name,
    partner_display_name: row.partner_display_name,
    anniversary_date: normalizeDateString(row.anniversary_date),
    onboarding_completed_at: normalizeTimestampString(row.onboarding_completed_at),
    partner_profile_updated_at: normalizeTimestampString(row.partner_profile_updated_at),
    profile_photo_path: row.profile_photo_path,
    partner_profile_photo_path: row.partner_profile_photo_path,
  }
}

