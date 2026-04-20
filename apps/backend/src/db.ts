import pg from "pg"

export interface Database {
  query<T = Record<string, unknown>>(text: string, params?: unknown[]): Promise<{ rows: T[] }>
  end(): Promise<void>
}

export async function runMigrations(db: Pick<Database, "query">): Promise<void> {
  await db.query(`
    CREATE TABLE IF NOT EXISTS users (
      id TEXT PRIMARY KEY,
      google_subject TEXT NOT NULL UNIQUE,
      email TEXT NOT NULL UNIQUE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

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
  `)
}

export async function createDatabase(databaseUrl: string): Promise<Database> {
  const pool = new pg.Pool({ connectionString: databaseUrl })
  await runMigrations(pool as unknown as Pick<Database, "query">)
  return {
    query: pool.query.bind(pool),
    end: async () => {
      await pool.end()
    },
  }
}
