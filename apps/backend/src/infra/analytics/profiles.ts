import type { Database } from "../db/database.js"
import type { PairSessionRecord } from "../../contracts/dbRecords.js"
import { getPairSession } from "../../modules/_shared/pairs.js"
import type { JsonObject, SignInMethod } from "./events.js"

/** Who counts as internal: everyone outside production, and the listed accounts in it. */
export interface InternalAccounts {
  production: boolean
  emails: ReadonlySet<string>
}

export function isInternalEmail(email: string | null, accounts: InternalAccounts): boolean {
  if (!accounts.production) return true
  return email !== null && accounts.emails.has(email.trim().toLowerCase())
}

/** The person properties PostHog shows, read fresh from the database. */
export interface PersonSnapshot {
  set: JsonObject
  setOnce: JsonObject
  unset: string[]
}

/** Properties a person has only while paired. */
const PAIR_PERSON_PROPERTIES = ["pair_id", "pair_name", "partner_name", "partner_email", "paired_at"]

interface UserRow {
  id: string
  email: string
  created_at: string | Date
  google_subject: string | null
  apple_subject: string | null
}

interface MemberRow {
  id: string
  email: string
  display_name: string | null
}

export async function personSnapshot(
  db: Pick<Database, "query">,
  userId: string,
  accounts: InternalAccounts,
): Promise<PersonSnapshot | null> {
  const users = await db.query<UserRow & { display_name: string | null; onboarding_completed_at: string | Date | null }>(
    `
    SELECT u.id, u.email, u.created_at, u.google_subject, u.apple_subject,
      p.display_name, p.onboarding_completed_at
    FROM users u
    LEFT JOIN user_profiles p ON p.user_id = u.id
    WHERE u.id = $1
    `,
    [userId],
  )
  const user = users.rows[0]
  if (!user) return null

  const name = user.display_name?.trim() || null
  const set: JsonObject = {
    email: user.email,
    name,
    display_label: personLabel(name, user.email),
    sign_in_methods: signInMethods(user),
    is_internal: isInternalEmail(user.email, accounts),
    onboarding_completed: user.onboarding_completed_at !== null,
  }
  const unset: string[] = []

  const pair = await getPairSession(db, userId)
  if (pair) {
    const members = await pairMembers(db, pair)
    const partner = pair.user_one_id === userId ? members.two : members.one
    Object.assign(set, {
      is_paired: true,
      pair_id: pair.id,
      pair_name: pairName(members.one, members.two),
      partner_name: partner ? memberName(partner) : null,
      partner_email: partner?.email ?? null,
      paired_at: isoString(pair.created_at),
    })
  } else {
    set.is_paired = false
    unset.push(...PAIR_PERSON_PROPERTIES)
  }

  Object.assign(set, await clientProperties(db, userId))
  Object.assign(set, await visibilityProperties(db, userId, pair?.id ?? null))

  return { set, setOnce: { signed_up_at: isoString(user.created_at) }, unset }
}

/** What PostHog shows for a pair: "Partner 1 x Partner 2", inviter first. */
export async function pairSnapshot(
  db: Pick<Database, "query">,
  pair: PairSessionRecord,
  accounts: InternalAccounts,
): Promise<JsonObject> {
  const members = await pairMembers(db, pair)
  return {
    name: pairName(members.one, members.two),
    partner_one_id: pair.user_one_id,
    partner_one_name: members.one ? memberName(members.one) : null,
    partner_one_email: members.one?.email ?? null,
    partner_two_id: pair.user_two_id,
    partner_two_name: members.two ? memberName(members.two) : null,
    partner_two_email: members.two?.email ?? null,
    paired_at: isoString(pair.created_at),
    status: "active",
    is_internal: isInternalEmail(members.one?.email ?? null, accounts) || isInternalEmail(members.two?.email ?? null, accounts),
  }
}

export function signInMethodOf(user: Pick<UserRow, "google_subject" | "apple_subject">): SignInMethod {
  return user.apple_subject && !user.google_subject ? "apple" : "google"
}

/**
 * How PostHog lists a person: "Name - email", or the email alone when there's no other name. The
 * project shows `display_label` first wherever it names a person.
 */
export function personLabel(name: string | null, email: string): string {
  return name && name.toLowerCase() !== email.toLowerCase() ? `${name} - ${email}` : email
}

/** The name a person shows under: their display name, or their email before they've set one. */
export function memberName(member: MemberRow): string {
  return member.display_name?.trim() || member.email
}

export function pairName(one: MemberRow | null, two: MemberRow | null): string {
  return `${one ? memberName(one) : "Deleted account"} x ${two ? memberName(two) : "Deleted account"}`
}

export async function pairMembers(
  db: Pick<Database, "query">,
  pair: Pick<PairSessionRecord, "user_one_id" | "user_two_id">,
): Promise<{ one: MemberRow | null; two: MemberRow | null }> {
  const rows = await db.query<MemberRow>(
    `
    SELECT u.id, u.email, p.display_name
    FROM users u
    LEFT JOIN user_profiles p ON p.user_id = u.id
    WHERE u.id = $1 OR u.id = $2
    `,
    [pair.user_one_id, pair.user_two_id],
  )
  return {
    one: rows.rows.find((row) => row.id === pair.user_one_id) ?? null,
    two: rows.rows.find((row) => row.id === pair.user_two_id) ?? null,
  }
}

function signInMethods(user: Pick<UserRow, "google_subject" | "apple_subject">): string[] {
  const methods: SignInMethod[] = []
  if (user.google_subject) methods.push("google")
  if (user.apple_subject) methods.push("apple")
  return methods
}

async function clientProperties(db: Pick<Database, "query">, userId: string): Promise<JsonObject> {
  const rows = await db.query<{ platform: string; device_family: string; app_build: number | null; last_seen_at: string | Date }>(
    `SELECT platform, device_family, app_build, last_seen_at FROM user_clients WHERE user_id = $1`,
    [userId],
  )
  if (rows.rows.length === 0) return {}
  const clients = [...rows.rows].sort((a, b) => new Date(b.last_seen_at).getTime() - new Date(a.last_seen_at).getTime())
  const latest = clients[0]
  return {
    platforms: distinctSorted(clients.map((client) => client.platform)),
    device_families: distinctSorted(clients.map((client) => client.device_family).filter((family) => family !== "unknown")),
    last_platform: latest.platform,
    last_device_family: latest.device_family === "unknown" ? null : latest.device_family,
    last_app_build: latest.app_build,
  }
}

async function visibilityProperties(db: Pick<Database, "query">, userId: string, pairId: string | null): Promise<JsonObject> {
  const surfaces = await db.query<{
    surface_type: string
    pair_session_id: string | null
    can_see_latest_drawings: boolean
    has_ever_been_able_to_see: boolean
  }>(
    `
    SELECT surface_type, pair_session_id, can_see_latest_drawings, has_ever_been_able_to_see
    FROM user_presence_surfaces
    WHERE user_id = $1
    `,
    [userId],
  )
  // Android's wallpaper status predates presence surfaces, so older accounts may only have this.
  const legacy = await db.query<{ has_ever_been_able_to_see: boolean }>(
    `SELECT has_ever_been_able_to_see FROM user_wallpaper_status WHERE user_id = $1`,
    [userId],
  )
  const visible = surfaces.rows.filter((surface) => pairId !== null && surface.pair_session_id === pairId && surface.can_see_latest_drawings)
  return {
    wallpaper_visible: visible.length > 0,
    wallpaper_ever_visible:
      surfaces.rows.some((surface) => surface.has_ever_been_able_to_see) ||
      legacy.rows.some((row) => row.has_ever_been_able_to_see),
    visible_surfaces: distinctSorted(visible.map((surface) => surface.surface_type)),
  }
}

function distinctSorted(values: string[]): string[] {
  return [...new Set(values)].sort()
}

export function isoString(value: string | Date): string {
  return new Date(value).toISOString()
}
