type Env = {
  apiHost: string
  personalApiKey: string
  projectId: number
}

function requiredEnv(): Env {
  const apiHost = (process.env.POSTHOG_API_HOST ?? process.env.POSTHOG_CLI_HOST ?? "https://us.posthog.com").replace(/\/$/, "")
  const personalApiKey =
    process.env.POSTHOG_PERSONAL_API_KEY ??
    process.env.POSTHOG_CLI_API_KEY ??
    process.env.POSTHOG_API_KEY ??
    ""
  const projectIdRaw = process.env.POSTHOG_PROJECT_ID ?? process.env.POSTHOG_CLI_PROJECT_ID ?? ""
  const projectId = Number(projectIdRaw)

  if (!personalApiKey.trim()) {
    throw new Error("Missing POSTHOG_PERSONAL_API_KEY (or POSTHOG_CLI_API_KEY / POSTHOG_API_KEY).")
  }
  if (!Number.isFinite(projectId) || projectId <= 0) {
    throw new Error("Missing/invalid POSTHOG_PROJECT_ID (or POSTHOG_CLI_PROJECT_ID).")
  }
  return { apiHost, personalApiKey, projectId }
}

async function posthogRequest(env: Env, path: string, init: RequestInit = {}): Promise<any> {
  const url = `${env.apiHost}/api${path.startsWith("/") ? path : `/${path}`}`
  const res = await fetch(url, {
    ...init,
    headers: {
      Authorization: `Bearer ${env.personalApiKey}`,
      "Content-Type": "application/json",
      ...(init.headers ?? {}),
    },
  })
  if (!res.ok) {
    const body = await res.text()
    throw new Error(`PostHog API ${res.status} ${res.statusText}: ${body}`)
  }
  const contentType = res.headers.get("content-type") ?? ""
  if (contentType.includes("application/json")) {
    return res.json()
  }
  return res.text()
}

async function getOrCreateDashboard(env: Env, name: string, description: string): Promise<number | null> {
  // Dashboards API isn't documented in the Mintlify spec as of 2026-05, but it exists on PostHog Cloud/self-host.
  // We try the common endpoints and gracefully fall back to "insights only" if the host doesn't support it.
  const candidates = [
    `/projects/${env.projectId}/dashboards/`,
    `/projects/${env.projectId}/dashboards`,
  ]

  for (const basePath of candidates) {
    try {
      const list = await posthogRequest(env, `${basePath}?limit=100`)
      const existing = Array.isArray(list?.results) ? list.results.find((d: any) => d?.name === name) : null
      if (existing?.id) return Number(existing.id)

      const created = await posthogRequest(env, basePath, {
        method: "POST",
        body: JSON.stringify({ name, description }),
      })
      if (created?.id) return Number(created.id)
    } catch {
      // try next candidate
    }
  }

  return null
}

async function findExistingInsight(env: Env, name: string): Promise<any | null> {
  const list = await posthogRequest(env, `/projects/${env.projectId}/insights/?limit=100&search=${encodeURIComponent(name)}`)
  const candidates = Array.isArray(list?.results) ? list.results : []
  return candidates.find((item: any) => item?.name === name && item?.deleted === false) ?? null
}

function insightPayloads(dashboardId: number | null): Array<{ name: string; description: string; query: any; dashboards?: number[] }> {
  const dashboards = dashboardId ? [dashboardId] : undefined
  const since30d = { date_from: "-30d" }

  return [
    {
      name: "Pairing Funnel (Invite → Redeem → Pair)",
      description: "Invite conversion funnel, keyed by inviteId.",
      dashboards,
      query: {
        kind: "InsightVizNode",
        source: {
          kind: "FunnelsQuery",
          series: [
            { kind: "EventsNode", event: "mulberry_invite_created", name: "Invite created" },
            { kind: "EventsNode", event: "mulberry_invite_redeemed", name: "Invite redeemed" },
            { kind: "EventsNode", event: "mulberry_invite_accepted", name: "Invite accepted" },
          ],
          dateRange: since30d,
        },
      },
    },
    {
      name: "Time To First Canvas Action (Pair → First Action)",
      description: "Funnel + conversion time from pair creation to first canvas action.",
      dashboards,
      query: {
        kind: "InsightVizNode",
        source: {
          kind: "FunnelsQuery",
          series: [
            { kind: "EventsNode", event: "mulberry_pair_created", name: "Pair created" },
            { kind: "EventsNode", event: "mulberry_canvas_first_action", name: "First canvas action" },
          ],
          dateRange: since30d,
        },
      },
    },
    {
      name: "Daily Active Pairs",
      description: "Pairs with at least one canvas change or reaction per day.",
      dashboards,
      query: {
        kind: "InsightVizNode",
        source: {
          kind: "TrendsQuery",
          series: [{ kind: "EventsNode", event: "mulberry_pair_active_day", name: "Active pair-day" }],
          dateRange: since30d,
          interval: "day",
        },
      },
    },
    {
      name: "Weekly Active Pairs",
      description: "Pairs with at least one canvas change or reaction per week.",
      dashboards,
      query: {
        kind: "InsightVizNode",
        source: {
          kind: "TrendsQuery",
          series: [{ kind: "EventsNode", event: "mulberry_pair_active_week", name: "Active pair-week" }],
          dateRange: since30d,
          interval: "week",
        },
      },
    },
    {
      name: "Retention (Active Pairs)",
      description: "Return rate for active pairs (day-level).",
      dashboards,
      query: {
        kind: "InsightVizNode",
        source: {
          kind: "RetentionQuery",
          retentionFilter: {
            period: "Day",
            totalIntervals: 14,
            retentionType: "retention_first_time",
            targetEntity: {
              id: "mulberry_pair_active_day",
              name: "mulberry_pair_active_day",
              type: "events",
            },
            returningEntity: {
              id: "mulberry_pair_active_day",
              name: "mulberry_pair_active_day",
              type: "events",
            },
          },
        },
      },
    },
    {
      name: "Canvas Feature Usage (Strokes vs Text vs Stickers)",
      description: "Counts derived from per-batch canvas ingestion properties.",
      dashboards,
      query: {
        kind: "InsightVizNode",
        source: {
          kind: "TrendsQuery",
          series: [
            {
              kind: "EventsNode",
              event: "mulberry_canvas_batch_ingested",
              name: "Strokes (finished)",
              math: "sum",
              math_property: "strokes_finished_count",
            },
            {
              kind: "EventsNode",
              event: "mulberry_canvas_batch_ingested",
              name: "Text (added)",
              math: "sum",
              math_property: "text_added_count",
            },
            {
              kind: "EventsNode",
              event: "mulberry_canvas_batch_ingested",
              name: "Stickers (added)",
              math: "sum",
              math_property: "sticker_added_count",
            },
          ],
          dateRange: since30d,
          interval: "day",
        },
      },
    },
    {
      name: "Daily Active Pairs by Brush Mode",
      description: "Active pairs split by dry vs round stroke mode.",
      dashboards,
      query: {
        kind: "InsightVizNode",
        source: {
          kind: "TrendsQuery",
          series: [
            {
              kind: "EventsNode",
              event: "mulberry_pair_active_day",
              name: "Dry brush (active pairs)",
              properties: [
                { key: "canvas_stroke_render_mode", type: "event", operator: "exact", value: "dry" },
              ],
            },
            {
              kind: "EventsNode",
              event: "mulberry_pair_active_day",
              name: "Round stroke (active pairs)",
              properties: [
                { key: "canvas_stroke_render_mode", type: "event", operator: "exact", value: "round" },
              ],
            },
          ],
          dateRange: since30d,
          interval: "day",
        },
      },
    },
  ]
}

async function main(): Promise<void> {
  const env = requiredEnv()
  const dashboardName = "Mulberry — Core"
  const dashboardDescription = "Mulberry pairing, activation, retention, and canvas usage."
  const dashboardId = await getOrCreateDashboard(env, dashboardName, dashboardDescription)

  if (dashboardId) {
    // eslint-disable-next-line no-console
    console.log(`Dashboard ready: id=${dashboardId}`)
  } else {
    // eslint-disable-next-line no-console
    console.warn("Could not create/find dashboard via API. Creating insights only (not attached to a dashboard).")
  }

  for (const payload of insightPayloads(dashboardId)) {
    try {
      const existing = await findExistingInsight(env, payload.name)
      if (existing?.id) {
        // eslint-disable-next-line no-console
        console.log(`Insight exists: id=${existing.id} name=${payload.name}`)
        continue
      }

      const created = await posthogRequest(env, `/projects/${env.projectId}/insights/`, {
        method: "POST",
        body: JSON.stringify({
          name: payload.name,
          description: payload.description,
          query: payload.query,
          dashboards: payload.dashboards,
          saved: true,
          tags: ["mulberry", "autogenerated"],
        }),
      })
      // eslint-disable-next-line no-console
      console.log(`Created insight: id=${created?.id} name=${payload.name}`)
    } catch (error) {
      // eslint-disable-next-line no-console
      console.error(`Failed creating insight: name=${payload.name}`)
      // eslint-disable-next-line no-console
      console.error(error)
    }
  }
}

await main()
