import pg from "pg"

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

    CREATE TABLE IF NOT EXISTS user_profiles (
      user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
      display_name TEXT,
      partner_display_name TEXT,
      anniversary_date DATE,
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

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

    CREATE INDEX IF NOT EXISTS device_tokens_active_user_idx
      ON device_tokens(user_id)
      WHERE revoked_at IS NULL;

    CREATE TABLE IF NOT EXISTS pair_sessions (
      id TEXT PRIMARY KEY,
      user_one_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      user_two_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

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
  `)
}

export async function createDatabase(databaseUrl: string): Promise<Database> {
  const pool = new pg.Pool({ connectionString: databaseUrl })
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
