import type { JsonObject, JsonValue } from "../../src/infra/analytics/events.js"

/**
 * Sets up Mulberry's PostHog project to match docs/analytics/tracking-plan.md: the internal
 * account filter, event descriptions, and the "Mulberry — Core" dashboard's saved insights.
 * Running it again updates everything in place.
 */

type Env = {
  apiHost: string
  personalApiKey: string
  projectId: number
}

interface Insight {
  name: string
  description: string
  query: JsonObject
}

interface NamedResource {
  id: number | string
  name?: string
  deleted?: boolean
}

interface Paginated<T> {
  results?: T[]
}

/** Only events recorded under the tracking plan; older events have no `source`. */
const TRACKED_ONLY: JsonObject[] = [{ key: "source", type: "event", operator: "is_set", value: "is_set" }]
const LAST_30_DAYS: JsonObject = { date_from: "-30d" }
const LAST_90_DAYS: JsonObject = { date_from: "-90d" }
/** PostHog numbers group types in the order they first appear; `pair` is Mulberry's only one. */
const PAIR_GROUP_INDEX = 0

const EVENT_DESCRIPTIONS: Record<string, string> = {
  mulberry_user_signed_up: "An account was created.",
  mulberry_app_opened: "The person opened an app; at most once per UTC day.",
  mulberry_onboarding_completed: "Profile onboarding finished for the first time.",
  mulberry_invite_created: "A new invite code was made.",
  mulberry_invite_redeem_failed: "A code was refused; see reason.",
  mulberry_invite_redeemed: "A partner entered a valid code.",
  mulberry_invite_declined: "The partner declined the invite.",
  mulberry_invite_expired: "An unused invite passed its expiry; timestamped at the expiry.",
  mulberry_invite_accepted: "The partner accepted the invite.",
  mulberry_pair_created: "A pair formed; sent by the accepting partner.",
  mulberry_pair_ended: "A pair ended; reason is unpaired or account_deleted.",
  mulberry_account_deleted: "An account was deleted. Personless.",
  mulberry_canvas_batch_ingested: "Canvas changes were saved.",
  mulberry_canvas_first_action: "A pair's first canvas change.",
  mulberry_reaction_sent: "A reaction was sent.",
  mulberry_pair_active_day: "A pair's first activity in a UTC day. Count = daily active pairs.",
  mulberry_pair_active_week: "A pair's first activity in an ISO week. Count = weekly active pairs.",
  mulberry_streak_milestone: "A pair's streak reached a milestone.",
  mulberry_wallpaper_first_visible: "A surface showed doodles for the first time.",
  mulberry_wallpaper_visibility_changed: "A surface started or stopped showing doodles.",
  mulberry_push_sent: "A visible notification was sent; one per recipient.",
  mulberry_onboarding_step_viewed: "An onboarding or pairing screen appeared (from the apps).",
  mulberry_wallpaper_setup_step: "A wallpaper, Shortcut or overlay setup step (from the apps).",
  mulberry_push_opened: "The person opened a notification (from the apps).",
}

function requiredEnv(): Env {
  const apiHost = (process.env.POSTHOG_API_HOST ?? process.env.POSTHOG_CLI_HOST ?? "https://us.posthog.com").replace(/\/$/, "")
  const personalApiKey = process.env.POSTHOG_PERSONAL_API_KEY ?? process.env.POSTHOG_CLI_API_KEY ?? process.env.POSTHOG_API_KEY ?? ""
  const projectId = Number(process.env.POSTHOG_PROJECT_ID ?? process.env.POSTHOG_CLI_PROJECT_ID ?? "")
  if (!personalApiKey.trim()) {
    throw new Error("Missing POSTHOG_PERSONAL_API_KEY (or POSTHOG_CLI_API_KEY / POSTHOG_API_KEY).")
  }
  if (!Number.isFinite(projectId) || projectId <= 0) {
    throw new Error("Missing/invalid POSTHOG_PROJECT_ID (or POSTHOG_CLI_PROJECT_ID).")
  }
  return { apiHost, personalApiKey, projectId }
}

async function posthogRequest<T>(env: Env, path: string, init: { method?: string; body?: JsonValue } = {}): Promise<T> {
  const response = await fetch(`${env.apiHost}/api/projects/${env.projectId}${path}`, {
    method: init.method ?? "GET",
    headers: { Authorization: `Bearer ${env.personalApiKey}`, "Content-Type": "application/json" },
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
  })
  if (!response.ok) {
    throw new Error(`PostHog API ${response.status} ${response.statusText}: ${await response.text()}`)
  }
  return (await response.json()) as T
}

const INTERNAL_FILTER: JsonObject = { key: "is_internal", type: "person", operator: "is_not", value: ["true"] }

/**
 * Leaves internal and test accounts out of every insight by default. Filters already on the
 * project, such as an internal-users cohort, are kept.
 */
async function configureInternalFilter(env: Env): Promise<void> {
  const project = await posthogRequest<{ test_account_filters?: JsonObject[] }>(env, "/")
  const filters = project.test_account_filters ?? []
  const hasInternal = filters.some((filter) => filter.key === INTERNAL_FILTER.key && filter.type === INTERNAL_FILTER.type)
  await posthogRequest<JsonObject>(env, "/", {
    method: "PATCH",
    body: {
      test_account_filters: hasInternal ? filters : [...filters, INTERNAL_FILTER],
      test_account_filters_default_checked: true,
    },
  })
  console.log(`Internal account filters: ${filters.length + (hasInternal ? 0 : 1)} (is_internal ${hasInternal ? "already there" : "added"}).`)
}

async function describeEvents(env: Env): Promise<void> {
  for (const [name, description] of Object.entries(EVENT_DESCRIPTIONS)) {
    try {
      const list = await posthogRequest<Paginated<NamedResource>>(env, `/event_definitions/?search=${encodeURIComponent(name)}`)
      const definition = list.results?.find((item) => item.name === name)
      if (!definition) {
        console.log(`Event not seen yet, skipped: ${name}`)
        continue
      }
      await posthogRequest<JsonObject>(env, `/event_definitions/${definition.id}/`, { method: "PATCH", body: { description } })
    } catch (error) {
      // Event descriptions need PostHog's data management feature; insights still work without it.
      console.warn(`Couldn't describe ${name}: ${error instanceof Error ? error.message : String(error)}`)
      return
    }
  }
  console.log("Event descriptions updated.")
}

async function dashboardId(env: Env, name: string, description: string): Promise<number> {
  const list = await posthogRequest<Paginated<NamedResource>>(env, "/dashboards/?limit=200")
  const existing = list.results?.find((item) => item.name === name && !item.deleted)
  if (existing) return Number(existing.id)
  const created = await posthogRequest<NamedResource>(env, "/dashboards/", { method: "POST", body: { name, description } })
  return Number(created.id)
}

function trends(series: JsonObject[], options: JsonObject = {}): JsonObject {
  return {
    kind: "InsightVizNode",
    source: { kind: "TrendsQuery", series, dateRange: LAST_30_DAYS, interval: "day", properties: TRACKED_ONLY, filterTestAccounts: true, ...options },
  }
}

function funnel(series: JsonObject[], options: JsonObject = {}): JsonObject {
  return {
    kind: "InsightVizNode",
    source: { kind: "FunnelsQuery", series, dateRange: LAST_90_DAYS, properties: TRACKED_ONLY, filterTestAccounts: true, ...options },
  }
}

function event(name: string, label: string, extra: JsonObject = {}): JsonObject {
  return { kind: "EventsNode", event: name, name: label, ...extra }
}

function table(query: string): JsonObject {
  return { kind: "DataTableNode", source: { kind: "HogQLQuery", query } }
}

const INSIGHTS: Insight[] = [
  {
    name: "Sign-up to doodles visible (people)",
    description: "Each person's path from sign-up to seeing doodles on a wallpaper, Shortcut or Mac overlay.",
    query: funnel([
      event("mulberry_user_signed_up", "Signed up"),
      event("mulberry_onboarding_completed", "Finished onboarding"),
      event("mulberry_wallpaper_first_visible", "Doodles visible"),
    ]),
  },
  {
    name: "Pairing Funnel (Invite → Redeem → Pair)",
    description: "Each invite from creation to acceptance, counted per invite across both partners.",
    query: funnel(
      [
        event("mulberry_invite_created", "Invite created"),
        event("mulberry_invite_redeemed", "Invite redeemed"),
        event("mulberry_invite_accepted", "Invite accepted"),
      ],
      { funnelsFilter: { funnelAggregateByHogQL: "properties.invite_id" } },
    ),
  },
  {
    name: "Pair activation (pairs)",
    description: "Each pair from forming to its first canvas change and first active day.",
    query: funnel(
      [
        event("mulberry_pair_created", "Pair created"),
        event("mulberry_canvas_first_action", "First canvas change"),
        event("mulberry_pair_active_day", "Active day"),
      ],
      { aggregation_group_type_index: PAIR_GROUP_INDEX },
    ),
  },
  {
    name: "Daily Active Pairs",
    description: "Pairs that drew, wrote, placed a sticker or sent a reaction each UTC day.",
    query: trends([event("mulberry_pair_active_day", "Active pairs")]),
  },
  {
    name: "Weekly Active Pairs",
    description: "Pairs active each ISO week.",
    query: trends([event("mulberry_pair_active_week", "Active pairs")], { dateRange: LAST_90_DAYS, interval: "week" }),
  },
  {
    name: "Retention (Active Pairs)",
    description: "Share of pairs active again in the weeks after their first active week.",
    query: {
      kind: "InsightVizNode",
      source: {
        kind: "RetentionQuery",
        aggregation_group_type_index: PAIR_GROUP_INDEX,
        filterTestAccounts: true,
        properties: TRACKED_ONLY,
        retentionFilter: {
          period: "Week",
          totalIntervals: 8,
          retentionType: "retention_first_time",
          targetEntity: { id: "mulberry_pair_active_week", name: "mulberry_pair_active_week", type: "events" },
          returningEntity: { id: "mulberry_pair_active_week", name: "mulberry_pair_active_week", type: "events" },
        },
      },
    },
  },
  {
    name: "Daily Active Pairs by platform",
    description: "Active pair-days by the platform of the partner who acted first that day.",
    query: trends([event("mulberry_pair_active_day", "Active pairs")], { breakdownFilter: { breakdown: "platform", breakdown_type: "event" } }),
  },
  {
    name: "Time To First Canvas Action (Pair → First Action)",
    description: "How long pairs take from forming to their first canvas change.",
    query: funnel(
      [event("mulberry_pair_created", "Pair created"), event("mulberry_canvas_first_action", "First canvas change")],
      { aggregation_group_type_index: PAIR_GROUP_INDEX, funnelsFilter: { funnelVizType: "time_to_convert" } },
    ),
  },
  {
    name: "Daily Active Pairs by Brush Mode",
    description: "Active pairs split by dry and round stroke mode.",
    query: trends([event("mulberry_pair_active_day", "Active pairs")], {
      breakdownFilter: { breakdown: "canvas_stroke_render_mode", breakdown_type: "event" },
    }),
  },
  {
    name: "Canvas Feature Usage (Strokes vs Text vs Stickers)",
    description: "Strokes, text and stickers added per day.",
    query: trends([
      event("mulberry_canvas_batch_ingested", "Strokes (finished)", { math: "sum", math_property: "strokes_finished_count" }),
      event("mulberry_canvas_batch_ingested", "Text (added)", { math: "sum", math_property: "text_added_count" }),
      event("mulberry_canvas_batch_ingested", "Stickers (added)", { math: "sum", math_property: "sticker_added_count" }),
    ]),
  },
  {
    name: "Wallpaper setup steps",
    description: "App-reported setup steps by surface, step and outcome.",
    query: trends([event("mulberry_wallpaper_setup_step", "Setup steps")], {
      breakdownFilter: { breakdowns: [{ property: "surface_type", type: "event" }, { property: "step", type: "event" }, { property: "outcome", type: "event" }] },
    }),
  },
  {
    name: "Notifications sent vs opened",
    description: "Visible notifications sent and opened, by type.",
    query: trends([event("mulberry_push_sent", "Sent"), event("mulberry_push_opened", "Opened")], {
      breakdownFilter: { breakdown: "push_type", breakdown_type: "event" },
    }),
  },
  {
    name: "Pairs ended by reason",
    description: "Unpairs and account deletions that ended a pair.",
    query: trends([event("mulberry_pair_ended", "Pairs ended")], {
      dateRange: LAST_90_DAYS,
      interval: "week",
      breakdownFilter: { breakdown: "reason", breakdown_type: "event" },
    }),
  },
  {
    name: "Unpaired people",
    description: "Signed-up people without a partner, newest first. Internal and deleted accounts left out.",
    query: table(`
      SELECT properties.email AS email, properties.name AS name, properties.signed_up_at AS signed_up_at,
        properties.platforms AS platforms, properties.onboarding_completed AS onboarding_completed
      FROM persons
      WHERE toString(properties.is_paired) = 'false'
        AND toString(properties.is_internal) != 'true'
        AND toString(properties.account_deleted) != 'true'
        AND properties.email IS NOT NULL
      ORDER BY signed_up_at DESC
      LIMIT 500
    `),
  },
  {
    name: "Most active pairs (30 days)",
    description: "Pairs by active days in the last 30 days, shown as Partner 1 x Partner 2.",
    query: table(`
      SELECT groups.properties.name AS pair, count() AS active_days, max(events.timestamp) AS last_active,
        groups.properties.partner_one_email AS partner_one_email, groups.properties.partner_two_email AS partner_two_email
      FROM events
      LEFT JOIN groups ON groups.key = events.$group_${PAIR_GROUP_INDEX} AND groups.index = ${PAIR_GROUP_INDEX}
      WHERE events.event = 'mulberry_pair_active_day'
        AND events.timestamp > now() - INTERVAL 30 DAY
        AND events.$group_${PAIR_GROUP_INDEX} != ''
        AND toString(groups.properties.is_internal) != 'true'
      GROUP BY pair, partner_one_email, partner_two_email
      ORDER BY active_days DESC, last_active DESC
      LIMIT 50
    `),
  },
]

async function saveInsight(env: Env, dashboard: number, insight: Insight): Promise<void> {
  const list = await posthogRequest<Paginated<NamedResource>>(env, `/insights/?limit=100&search=${encodeURIComponent(insight.name)}`)
  const existing = list.results?.find((item) => item.name === insight.name && !item.deleted)
  const body: JsonObject = {
    name: insight.name,
    description: insight.description,
    query: insight.query,
    dashboards: [dashboard],
    saved: true,
    tags: ["mulberry", "autogenerated"],
  }
  if (existing) {
    await posthogRequest<NamedResource>(env, `/insights/${existing.id}/`, { method: "PATCH", body })
    console.log(`Updated insight: ${insight.name}`)
  } else {
    const created = await posthogRequest<NamedResource>(env, "/insights/", { method: "POST", body })
    console.log(`Created insight: id=${created.id} ${insight.name}`)
  }
}

async function main(): Promise<void> {
  const env = requiredEnv()
  await configureInternalFilter(env)
  await describeEvents(env)
  const dashboard = await dashboardId(env, "Mulberry — Core", "Mulberry sign-up, pairing, activation, retention and canvas usage.")
  console.log(`Dashboard ready: id=${dashboard}`)
  for (const insight of INSIGHTS) {
    try {
      await saveInsight(env, dashboard, insight)
    } catch (error) {
      console.error(`Failed saving insight: ${insight.name}`, error)
    }
  }
}

await main()
