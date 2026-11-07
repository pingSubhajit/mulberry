import pg from "pg"
import { DRAWING_OPERATION_TYPES } from "../../contracts/canvas.js"

export interface Database {
  query<T = Record<string, unknown>>(text: string, params?: unknown[]): Promise<{ rows: T[] }>
  transaction<T>(fn: (db: Pick<Database, "query">) => Promise<T>): Promise<T>
  end(): Promise<void>
}

export async function runMigrations(db: Pick<Database, "query">): Promise<void> {
  await db.query(`
    CREATE TABLE IF NOT EXISTS users (
      id TEXT PRIMARY KEY,
      google_subject TEXT NOT NULL UNIQUE,
      email TEXT NOT NULL UNIQUE,
      google_picture_url TEXT,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    ALTER TABLE users
      ADD COLUMN IF NOT EXISTS google_picture_url TEXT;

    ALTER TABLE users
      ADD COLUMN IF NOT EXISTS first_drew_at TIMESTAMPTZ;

    -- The last activity day of the lost streak whose breaking bolt the account has watched.
    ALTER TABLE users
      ADD COLUMN IF NOT EXISTS streak_loss_seen_day DATE;

    CREATE TABLE IF NOT EXISTS user_profiles (
      user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
      display_name TEXT,
      partner_display_name TEXT,
      anniversary_date DATE,
      onboarding_completed_at TIMESTAMPTZ,
      partner_profile_updated_at TIMESTAMPTZ,
      profile_photo_path TEXT,
      partner_profile_photo_path TEXT,
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    ALTER TABLE user_profiles
      ADD COLUMN IF NOT EXISTS onboarding_completed_at TIMESTAMPTZ;

    ALTER TABLE user_profiles
      ADD COLUMN IF NOT EXISTS partner_profile_updated_at TIMESTAMPTZ;

    ALTER TABLE user_profiles
      ADD COLUMN IF NOT EXISTS profile_photo_path TEXT;

    ALTER TABLE user_profiles
      ADD COLUMN IF NOT EXISTS partner_profile_photo_path TEXT;

    UPDATE user_profiles
    SET onboarding_completed_at = updated_at
    WHERE onboarding_completed_at IS NULL
      AND display_name IS NOT NULL
      AND partner_display_name IS NOT NULL
      AND anniversary_date IS NOT NULL;

    CREATE TABLE IF NOT EXISTS sessions (
      id TEXT PRIMARY KEY,
      user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      access_token TEXT NOT NULL UNIQUE,
      refresh_token TEXT NOT NULL UNIQUE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      revoked_at TIMESTAMPTZ
    );

    CREATE TABLE IF NOT EXISTS device_tokens (
      id TEXT PRIMARY KEY,
      user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      token TEXT NOT NULL UNIQUE,
      platform TEXT NOT NULL,
      app_environment TEXT NOT NULL,
      last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      revoked_at TIMESTAMPTZ
    );

    -- An iPhone's reactions Live Activity: the app's push-to-start token, and the push token of the
    -- activity it shows, if any.
    ALTER TABLE device_tokens
      ADD COLUMN IF NOT EXISTS live_activity_start_token TEXT;

    ALTER TABLE device_tokens
      ADD COLUMN IF NOT EXISTS live_activity_token TEXT;

    -- An Apple device's WidgetKit push token, so a change its widgets show can reload them at once.
    ALTER TABLE device_tokens
      ADD COLUMN IF NOT EXISTS widget_push_token TEXT;

    ALTER TABLE device_tokens
      ADD COLUMN IF NOT EXISTS widget_push_bundle_id TEXT;

    ALTER TABLE device_tokens
      ADD COLUMN IF NOT EXISTS widget_push_environment TEXT;

    -- The widget fetches what to show itself, proving it belongs to the device with this secret.
    ALTER TABLE device_tokens
      ADD COLUMN IF NOT EXISTS widget_secret_hash TEXT;

    CREATE INDEX IF NOT EXISTS device_tokens_widget_secret_idx ON device_tokens (widget_secret_hash);

    -- One sign-in per device: refreshing a session keeps it, so an edit and a push token can be
    -- matched to the device they came from. Sessions from before keep their own id.
    ALTER TABLE sessions
      ADD COLUMN IF NOT EXISTS sign_in_id TEXT;

    UPDATE sessions
    SET sign_in_id = id
    WHERE sign_in_id IS NULL;

    ALTER TABLE device_tokens
      ADD COLUMN IF NOT EXISTS sign_in_id TEXT;

    CREATE INDEX IF NOT EXISTS device_tokens_active_user_idx
      ON device_tokens(user_id)
      WHERE revoked_at IS NULL;

    CREATE TABLE IF NOT EXISTS pair_sessions (
      id TEXT PRIMARY KEY,
      user_one_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      user_two_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    ALTER TABLE pair_sessions
      ADD COLUMN IF NOT EXISTS canvas_stroke_render_mode TEXT;

    UPDATE pair_sessions
    SET canvas_stroke_render_mode = 'dry'
    WHERE canvas_stroke_render_mode IS NULL;

    ALTER TABLE pair_sessions
      ALTER COLUMN canvas_stroke_render_mode SET DEFAULT 'dry';

    ALTER TABLE pair_sessions
      ALTER COLUMN canvas_stroke_render_mode SET NOT NULL;

    CREATE TABLE IF NOT EXISTS invites (
      id TEXT PRIMARY KEY,
      inviter_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      recipient_user_id TEXT REFERENCES users(id) ON DELETE CASCADE,
      code TEXT NOT NULL UNIQUE,
      status TEXT NOT NULL,
      expires_at TIMESTAMPTZ NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      consumed_at TIMESTAMPTZ
    );

    CREATE TABLE IF NOT EXISTS invite_redeem_failures (
      user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
      failure_count INTEGER NOT NULL,
      window_started_at TIMESTAMPTZ NOT NULL
    );

    CREATE TABLE IF NOT EXISTS canvas_operations (
      id TEXT PRIMARY KEY,
      pair_session_id TEXT NOT NULL REFERENCES pair_sessions(id) ON DELETE CASCADE,
      server_revision BIGINT NOT NULL,
      client_operation_id TEXT NOT NULL,
      actor_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      type TEXT NOT NULL,
      stroke_id TEXT,
      payload_json JSONB NOT NULL,
      client_created_at TIMESTAMPTZ NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE (pair_session_id, server_revision),
      UNIQUE (pair_session_id, actor_user_id, client_operation_id)
    );

    CREATE INDEX IF NOT EXISTS canvas_operations_pair_revision_idx
      ON canvas_operations(pair_session_id, server_revision);

    CREATE TABLE IF NOT EXISTS canvas_snapshots (
      pair_session_id TEXT PRIMARY KEY REFERENCES pair_sessions(id) ON DELETE CASCADE,
      revision BIGINT NOT NULL DEFAULT 0,
      latest_revision BIGINT NOT NULL DEFAULT 0,
      snapshot_json JSONB NOT NULL DEFAULT '{"strokes":[]}'::jsonb,
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    ALTER TABLE canvas_snapshots
      ADD COLUMN IF NOT EXISTS latest_revision BIGINT NOT NULL DEFAULT 0;

    UPDATE canvas_snapshots
    SET latest_revision = GREATEST(latest_revision, revision);

    CREATE TABLE IF NOT EXISTS canvas_nudges (
      pair_session_id TEXT PRIMARY KEY REFERENCES pair_sessions(id) ON DELETE CASCADE,
      actor_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      latest_revision BIGINT NOT NULL,
      due_at TIMESTAMPTZ NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    CREATE INDEX IF NOT EXISTS canvas_nudges_due_at_idx
      ON canvas_nudges(due_at);

    CREATE TABLE IF NOT EXISTS draw_reminders (
      pair_session_id TEXT NOT NULL REFERENCES pair_sessions(id) ON DELETE CASCADE,
      user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      last_draw_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      reminder_count INTEGER NOT NULL DEFAULT 0,
      due_at TIMESTAMPTZ NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (pair_session_id, user_id)
    );

    CREATE INDEX IF NOT EXISTS draw_reminders_due_at_idx
      ON draw_reminders(due_at);

    CREATE TABLE IF NOT EXISTS pair_activity_days (
      pair_session_id TEXT NOT NULL REFERENCES pair_sessions(id) ON DELETE CASCADE,
      activity_day DATE NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (pair_session_id, activity_day)
    );

    CREATE TABLE IF NOT EXISTS pair_product_activity_days (
      pair_session_id TEXT NOT NULL REFERENCES pair_sessions(id) ON DELETE CASCADE,
      activity_day DATE NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (pair_session_id, activity_day)
    );

    CREATE TABLE IF NOT EXISTS pair_product_activity_weeks (
      pair_session_id TEXT NOT NULL REFERENCES pair_sessions(id) ON DELETE CASCADE,
      week_start_day DATE NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (pair_session_id, week_start_day)
    );

    CREATE TABLE IF NOT EXISTS user_wallpaper_status (
      user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
      pair_session_id TEXT REFERENCES pair_sessions(id) ON DELETE CASCADE,
      wallpaper_sync_enabled BOOLEAN NOT NULL,
      wallpaper_selected_on_home BOOLEAN NOT NULL,
      wallpaper_selected_on_lock BOOLEAN NOT NULL,
      can_see_latest_drawings BOOLEAN NOT NULL,
      has_ever_been_able_to_see BOOLEAN NOT NULL DEFAULT FALSE,
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    CREATE TABLE IF NOT EXISTS user_presence_surfaces (
      user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      pair_session_id TEXT REFERENCES pair_sessions(id) ON DELETE CASCADE,
      device_instance_id TEXT NOT NULL,
      surface_type TEXT NOT NULL,
      configured BOOLEAN NOT NULL,
      enabled BOOLEAN NOT NULL,
      can_see_latest_drawings BOOLEAN NOT NULL,
      has_ever_been_able_to_see BOOLEAN NOT NULL DEFAULT FALSE,
      details_json JSONB NOT NULL DEFAULT '{}'::jsonb,
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (user_id, surface_type, device_instance_id)
    );

    CREATE INDEX IF NOT EXISTS user_presence_surfaces_pair_idx
      ON user_presence_surfaces(pair_session_id, user_id);

    CREATE TABLE IF NOT EXISTS wallpapers (
      id TEXT PRIMARY KEY,
      title TEXT NOT NULL,
      description TEXT NOT NULL DEFAULT '',
      storage_path TEXT NOT NULL,
      thumbnail_path TEXT NOT NULL,
      preview_path TEXT NOT NULL,
      full_path TEXT NOT NULL,
      width INTEGER NOT NULL,
      height INTEGER NOT NULL,
      dominant_color TEXT NOT NULL DEFAULT '#B31329',
      sort_order INTEGER NOT NULL DEFAULT 0,
      published_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    CREATE INDEX IF NOT EXISTS wallpapers_public_catalog_idx
      ON wallpapers(sort_order ASC, created_at DESC, id ASC)
      WHERE published_at IS NOT NULL;

    CREATE TABLE IF NOT EXISTS sticker_packs (
      pack_key TEXT NOT NULL,
      pack_version INTEGER NOT NULL,
      title TEXT NOT NULL,
      description TEXT NOT NULL DEFAULT '',
      cover_thumbnail_path TEXT NOT NULL,
      cover_full_path TEXT NOT NULL,
      sort_order INTEGER NOT NULL DEFAULT 0,
      featured BOOLEAN NOT NULL DEFAULT FALSE,
      published_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (pack_key, pack_version)
    );

    CREATE INDEX IF NOT EXISTS sticker_packs_public_catalog_idx
      ON sticker_packs(sort_order ASC, created_at DESC, pack_key ASC, pack_version DESC)
      WHERE published_at IS NOT NULL;

    CREATE TABLE IF NOT EXISTS stickers (
      pack_key TEXT NOT NULL,
      pack_version INTEGER NOT NULL,
      sticker_id TEXT NOT NULL,
      sort_order INTEGER NOT NULL DEFAULT 0,
      thumbnail_path TEXT NOT NULL,
      full_path TEXT NOT NULL,
      width INTEGER NOT NULL,
      height INTEGER NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (pack_key, pack_version, sticker_id),
      FOREIGN KEY (pack_key, pack_version)
        REFERENCES sticker_packs(pack_key, pack_version)
        ON DELETE CASCADE
    );

    CREATE INDEX IF NOT EXISTS stickers_pack_sort_idx
      ON stickers(pack_key, pack_version, sort_order ASC, created_at DESC, sticker_id ASC);

    ALTER TABLE sticker_packs ADD COLUMN IF NOT EXISTS content_locked BOOLEAN NOT NULL DEFAULT TRUE;
    ALTER TABLE sticker_packs ALTER COLUMN content_locked SET DEFAULT FALSE;
    UPDATE sticker_packs SET content_locked = TRUE WHERE published_at IS NOT NULL;

    CREATE SCHEMA IF NOT EXISTS catalog_admin;
    CREATE TABLE IF NOT EXISTS catalog_admin.catalog_item_locks (
      content_type TEXT NOT NULL,
      content_key TEXT NOT NULL,
      PRIMARY KEY (content_type, content_key)
    );
    CREATE TABLE IF NOT EXISTS catalog_admin.admin_catalog_changes (
      id TEXT PRIMARY KEY,
      content_type TEXT NOT NULL,
      content_key TEXT NOT NULL,
      action TEXT NOT NULL,
      actor TEXT NOT NULL DEFAULT 'shared-admin',
      previous_json JSONB,
      current_json JSONB,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
    CREATE INDEX IF NOT EXISTS admin_catalog_changes_history_idx
      ON catalog_admin.admin_catalog_changes(content_type, created_at DESC, id DESC);

    -- Share frames: one image and one layout per (frame_key, frame_version). Publishing locks the
    -- image and layout for good; title, order, date window and visibility stay editable.
    -- available_from and available_until are inclusive local calendar dates; NULL means open.
    CREATE TABLE IF NOT EXISTS share_frames (
      frame_key TEXT NOT NULL,
      frame_version INTEGER NOT NULL,
      title TEXT NOT NULL,
      sort_order INTEGER NOT NULL DEFAULT 0,
      available_from DATE,
      available_until DATE,
      original_path TEXT NOT NULL,
      full_path TEXT NOT NULL,
      thumbnail_path TEXT NOT NULL,
      width INTEGER NOT NULL,
      height INTEGER NOT NULL,
      layout JSONB NOT NULL,
      published_at TIMESTAMPTZ,
      content_locked BOOLEAN NOT NULL DEFAULT FALSE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (frame_key, frame_version)
    );

    CREATE INDEX IF NOT EXISTS share_frames_public_catalog_idx
      ON share_frames(sort_order ASC, frame_key ASC, frame_version DESC)
      WHERE published_at IS NOT NULL;

    CREATE TABLE IF NOT EXISTS reaction_inboxes (
      recipient_user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
      pair_session_id TEXT REFERENCES pair_sessions(id) ON DELETE CASCADE,
      generation BIGINT NOT NULL DEFAULT 1,
      heart_count INTEGER NOT NULL DEFAULT 0,
      hug_count INTEGER NOT NULL DEFAULT 0,
      kiss_count INTEGER NOT NULL DEFAULT 0,
      smile_count INTEGER NOT NULL DEFAULT 0,
      laugh_count INTEGER NOT NULL DEFAULT 0,
      sparkle_count INTEGER NOT NULL DEFAULT 0,
      leased_by_device_id TEXT,
      lease_expires_at TIMESTAMPTZ,
      lease_generation BIGINT,
      consumed_generation BIGINT NOT NULL DEFAULT 0,
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    ALTER TABLE reaction_inboxes
      ADD COLUMN IF NOT EXISTS hug_count INTEGER NOT NULL DEFAULT 0;
    ALTER TABLE reaction_inboxes
      ADD COLUMN IF NOT EXISTS smile_count INTEGER NOT NULL DEFAULT 0;

    CREATE INDEX IF NOT EXISTS reaction_inboxes_pair_idx
      ON reaction_inboxes(pair_session_id);

    CREATE TABLE IF NOT EXISTS reaction_send_rate_limits (
      actor_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      pair_session_id TEXT NOT NULL REFERENCES pair_sessions(id) ON DELETE CASCADE,
      available_tokens DOUBLE PRECISION NOT NULL,
      last_refill_at_ms BIGINT NOT NULL,
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (actor_user_id, pair_session_id)
    );

    CREATE INDEX IF NOT EXISTS reaction_send_rate_limits_updated_idx
      ON reaction_send_rate_limits(updated_at);

    CREATE TABLE IF NOT EXISTS schema_backfills (
      name TEXT PRIMARY KEY,
      ran_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    -- Sign in with Apple. An account signs in with Google, Apple or both, so neither subject is
    -- required. Apple's refresh token is kept only to revoke it when the account is deleted, with
    -- the app it was issued to. The Google subject's unique constraint is rebuilt as an index,
    -- which leaves NULLs to Apple-only accounts (pg-mem, in tests, keeps refusing them otherwise).
    ALTER TABLE users
      DROP CONSTRAINT IF EXISTS users_google_subject_key;

    ALTER TABLE users
      ALTER COLUMN google_subject DROP NOT NULL;

    CREATE UNIQUE INDEX IF NOT EXISTS users_google_subject_idx
      ON users(google_subject);

    ALTER TABLE users
      ADD COLUMN IF NOT EXISTS apple_subject TEXT;

    CREATE UNIQUE INDEX IF NOT EXISTS users_apple_subject_idx
      ON users(apple_subject);

    ALTER TABLE users
      ADD COLUMN IF NOT EXISTS apple_client_id TEXT;

    ALTER TABLE users
      ADD COLUMN IF NOT EXISTS apple_refresh_token TEXT;

    -- Every reaction a person sends, kept with the pair. reaction_inboxes holds only the batch
    -- waiting to play and resets once it has, so this is the lasting record of who sent what.
    CREATE TABLE IF NOT EXISTS reaction_events (
      id TEXT PRIMARY KEY,
      pair_session_id TEXT NOT NULL REFERENCES pair_sessions(id) ON DELETE CASCADE,
      sender_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      reaction_type TEXT NOT NULL,
      sent_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    CREATE INDEX IF NOT EXISTS reaction_events_pair_sent_idx
      ON reaction_events(pair_session_id, sent_at);

    -- The IANA time zone the apps last reported (X-Time-Zone), and each change to it, so a
    -- moment can be read in the local time the person was in when it happened.
    ALTER TABLE users
      ADD COLUMN IF NOT EXISTS time_zone TEXT;

    CREATE TABLE IF NOT EXISTS user_time_zone_changes (
      id TEXT PRIMARY KEY,
      user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      time_zone TEXT NOT NULL,
      changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    CREATE INDEX IF NOT EXISTS user_time_zone_changes_user_changed_idx
      ON user_time_zone_changes(user_id, changed_at);

    -- Analytics waiting to reach PostHog, written in the same transaction as the change each row
    -- describes and removed once sent. No foreign keys: a deleted account's last rows still go.
    CREATE TABLE IF NOT EXISTS analytics_outbox (
      seq BIGSERIAL,
      id TEXT PRIMARY KEY,
      kind TEXT NOT NULL,
      payload_json JSONB NOT NULL,
      occurred_at TIMESTAMPTZ NOT NULL,
      available_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      claimed_until TIMESTAMPTZ,
      attempts INTEGER NOT NULL DEFAULT 0,
      last_error TEXT,
      dead_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    CREATE INDEX IF NOT EXISTS analytics_outbox_due_idx
      ON analytics_outbox(available_at, seq)
      WHERE dead_at IS NULL;

    -- Each app an account has used, from the X-App-Platform, X-Device-Family and X-App-Build headers.
    CREATE TABLE IF NOT EXISTS user_clients (
      user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      platform TEXT NOT NULL,
      device_family TEXT NOT NULL,
      app_build INTEGER,
      first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (user_id, platform, device_family)
    );

    -- The UTC days each account opened an app, so mulberry_app_opened is sent once a day.
    CREATE TABLE IF NOT EXISTS user_app_open_days (
      user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      open_day DATE NOT NULL,
      PRIMARY KEY (user_id, open_day)
    );

    -- Streak milestones already recorded, per streak (identified by the day it started).
    CREATE TABLE IF NOT EXISTS pair_streak_milestones (
      pair_session_id TEXT NOT NULL REFERENCES pair_sessions(id) ON DELETE CASCADE,
      streak_start_day DATE NOT NULL,
      streak_days INTEGER NOT NULL,
      reached_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (pair_session_id, streak_start_day, streak_days)
    );

    -- Every pair each person has been in, kept after the pair ends so that deleting the account
    -- can remove their name and email from those pairs in PostHog. Identifiers only.
    CREATE TABLE IF NOT EXISTS analytics_pair_members (
      pair_session_id TEXT NOT NULL,
      user_id TEXT NOT NULL,
      position SMALLINT NOT NULL,
      PRIMARY KEY (pair_session_id, user_id)
    );

    CREATE INDEX IF NOT EXISTS analytics_pair_members_user_idx
      ON analytics_pair_members(user_id);

    -- When mulberry_invite_expired was recorded for the invite.
    ALTER TABLE invites
      ADD COLUMN IF NOT EXISTS analytics_expired_at TIMESTAMPTZ;

    -- Photos placed on a pair's canvas, normalised to JPEG or PNG. They go with the pair: ending
    -- it, or deleting either account, removes them.
    CREATE TABLE IF NOT EXISTS canvas_photos (
      id TEXT PRIMARY KEY,
      pair_session_id TEXT NOT NULL REFERENCES pair_sessions(id) ON DELETE CASCADE,
      uploader_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      content_type TEXT NOT NULL,
      width INTEGER NOT NULL,
      height INTEGER NOT NULL,
      bytes BYTEA NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    CREATE INDEX IF NOT EXISTS canvas_photos_pair_idx
      ON canvas_photos(pair_session_id);

    -- Photo files moved to storage. storage_key is the object's path; bytes is only left on rows
    -- from before, until their first read moves them across.
    ALTER TABLE canvas_photos
      ADD COLUMN IF NOT EXISTS storage_key TEXT;

    ALTER TABLE canvas_photos
      ALTER COLUMN bytes DROP NOT NULL;

    CREATE INDEX IF NOT EXISTS canvas_photos_created_idx
      ON canvas_photos(created_at, id);
  `)

  await runBackfills(db)
}

/** Data backfills that go with the schema above, each run once. */
export async function runBackfills(db: Pick<Database, "query">): Promise<void> {
  await runBackfillOnce(db, "users.first_drew_at", `
    UPDATE users
    SET first_drew_at = drew.first_at
    FROM (
      SELECT actor_user_id, MIN(created_at) AS first_at
      FROM canvas_operations
      WHERE type IN (${DRAWING_OPERATION_TYPES.map((type) => `'${type}'`).join(", ")})
      GROUP BY actor_user_id
    ) AS drew
    WHERE users.id = drew.actor_user_id
      AND users.first_drew_at IS NULL
  `)
}

/**
 * Runs a data backfill the first time the server starts with it, rather than on every start. The
 * statement is idempotent, so two servers starting together and both running it is harmless.
 */
async function runBackfillOnce(db: Pick<Database, "query">, name: string, statement: string): Promise<void> {
  const ran = await db.query(`SELECT name FROM schema_backfills WHERE name = $1`, [name])
  if (ran.rows.length > 0) return
  await db.query(statement)
  await db.query(`INSERT INTO schema_backfills (name) VALUES ($1) ON CONFLICT (name) DO NOTHING`, [name])
}

export async function createDatabase(databaseUrl: string): Promise<Database> {
  const pool = new pg.Pool({
    connectionString: databaseUrl,
    // Opening a connection to the database costs several round trips (~400 ms from Fly to
    // Supabase), so idle connections are kept for minutes rather than pg's default 10 seconds.
    idleTimeoutMillis: 10 * 60_000,
    keepAlive: true,
  })
  // An idle connection the database or pooler closes reports here; without a listener it would
  // crash the server. The pool drops it and opens a new one when needed.
  pool.on("error", (error) => {
    console.warn("[db] idle connection closed", { error: error.message })
  })
  await runMigrations(pool as unknown as Pick<Database, "query">)
  return {
    query: pool.query.bind(pool),
    transaction: async (fn) => {
      const client = await pool.connect()
      try {
        await client.query("BEGIN")
        const result = await fn({ query: client.query.bind(client) })
        await client.query("COMMIT")
        return result
      } catch (error) {
        await client.query("ROLLBACK")
        throw error
      } finally {
        client.release()
      }
    },
    end: async () => {
      await pool.end()
    },
  }
}
