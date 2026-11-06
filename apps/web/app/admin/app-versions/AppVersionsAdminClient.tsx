"use client"

import { Smartphone } from "lucide-react"
import { FormEvent, useEffect, useState } from "react"

import { cn } from "@/lib/utils"

import { useAdmin } from "../AdminShell"
import { AdminButton, Field, LiveDot, numberFieldClass, SectionLabel, Spinner, StatusLine, TopBar } from "../ui"

type AppPlatform = "ios" | "android"

type AppVersionPolicy = {
  liveBuild: number
  recommendedBuild: number
  minimumBuild: number
}

type PolicyField = keyof AppVersionPolicy
type PolicyDraft = Record<PolicyField, string>

type AppVersionsChange = {
  version: string
  updatedAt: string | null
  description: string
}

type AppVersionsResponse = {
  policies: Record<AppPlatform, AppVersionPolicy>
  history: AppVersionsChange[]
}

const PLATFORMS: readonly { platform: AppPlatform; name: string; caveat: string }[] = [
  { platform: "ios", name: "iOS", caveat: "Only raise it once the build is live in the App Store." },
  { platform: "android", name: "Android", caveat: "Only raise it once the build is live on open testing." }
]

const FIELDS: readonly { field: PolicyField; label: string }[] = [
  { field: "liveBuild", label: "Live in store" },
  { field: "recommendedBuild", label: "Ask to update below" },
  { field: "minimumBuild", label: "Require update below" }
]

const historyTime = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" })

export default function AppVersionsAdminClient() {
  const { adminFetch } = useAdmin()
  const [data, setData] = useState<AppVersionsResponse | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    void (async () => {
      try {
        const response = await adminFetch("/admin/app-versions")
        setData((await response.json()) as AppVersionsResponse)
      } catch (err) {
        setError(err instanceof Error ? err.message : "Unable to load app versions")
      }
    })()
  }, [adminFetch])

  return (
    <>
      <TopBar icon={Smartphone} title="App versions" />

      <div className="min-h-0 flex-1 overflow-y-auto">
        <div className="mx-auto flex w-full max-w-[880px] flex-col gap-12 px-6 py-10">
          {data ? (
            <>
              <section className="flex flex-col gap-3">
                <SectionLabel>Update policy</SectionLabel>
                <div className="flex flex-col gap-3">
                  {PLATFORMS.map(({ platform, name, caveat }) => {
                    const policy = data.policies[platform]
                    return (
                      <PlatformCard
                        key={`${platform}:${policy.liveBuild}:${policy.recommendedBuild}:${policy.minimumBuild}`}
                        platform={platform}
                        name={name}
                        caveat={caveat}
                        policy={policy}
                        onSaved={setData}
                      />
                    )
                  })}
                </div>
              </section>

              <section className="flex flex-col gap-3">
                <SectionLabel>History</SectionLabel>
                {data.history.length === 0 ? (
                  <p className="rounded-xl bg-admin-fill px-5 py-4 text-sm text-admin-fg-2">No changes yet</p>
                ) : (
                  <ol className="divide-y divide-admin-line rounded-xl bg-admin-fill">
                    {data.history.map((change) => (
                      <li
                        key={change.version}
                        className="flex flex-col gap-1 px-5 py-3 text-sm sm:flex-row sm:items-baseline sm:justify-between sm:gap-6"
                      >
                        <span className="text-admin-fg">{change.description}</span>
                        {change.updatedAt ? (
                          <time
                            dateTime={change.updatedAt}
                            className="shrink-0 font-mono text-xs tabular-nums text-admin-muted"
                          >
                            {historyTime.format(new Date(change.updatedAt))}
                          </time>
                        ) : null}
                      </li>
                    ))}
                  </ol>
                )}
              </section>
            </>
          ) : error ? (
            <StatusLine tone="error">{error}</StatusLine>
          ) : (
            <div className="flex justify-center py-16 text-admin-muted">
              <Spinner />
            </div>
          )}
        </div>
      </div>
    </>
  )
}

function PlatformCard({
  platform,
  name,
  caveat,
  policy,
  onSaved
}: Readonly<{
  platform: AppPlatform
  name: string
  caveat: string
  policy: AppVersionPolicy
  onSaved: (data: AppVersionsResponse) => void
}>) {
  const { adminFetch } = useAdmin()
  const [draft, setDraft] = useState<PolicyDraft>(() => toDraft(policy))
  const [isSaving, setIsSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const isChanged = FIELDS.some(({ field }) => draft[field] !== String(policy[field]))

  const onSave = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const next = fromDraft(draft)
    if (!next) {
      setError("Builds are whole numbers")
      return
    }
    if (next.minimumBuild > policy.minimumBuild) {
      const confirmed = window.confirm(`Block ${name} builds below ${next.minimumBuild}?\n\n${caveat}`)
      if (!confirmed) return
    }

    setIsSaving(true)
    setError(null)
    void (async () => {
      try {
        const response = await adminFetch(`/admin/app-versions/${platform}`, {
          method: "PATCH",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ ...next, confirm: true })
        })
        onSaved((await response.json()) as AppVersionsResponse)
      } catch (err) {
        setError(err instanceof Error ? err.message : "Unable to save")
      } finally {
        setIsSaving(false)
      }
    })()
  }

  const preview = fromDraft(draft) ?? policy

  return (
    <form onSubmit={onSave} className="rounded-xl bg-admin-fill">
      <header className="flex items-center justify-between gap-4 px-5 pt-4">
        <h3 className="text-sm font-medium text-admin-fg">{name}</h3>
        <span className="font-mono text-xs tabular-nums text-admin-muted">Build {preview.liveBuild} live</span>
      </header>

      <div className="px-5 pb-5 pt-3">
        <BuildRuler policy={preview} />
      </div>

      <div className="flex flex-col gap-4 border-t border-admin-line px-5 py-4 sm:flex-row sm:items-end">
        <fieldset disabled={isSaving} className="grid flex-1 gap-3 sm:grid-cols-3">
          {FIELDS.map(({ field, label }) => (
            <Field key={field} label={label}>
              <input
                type="number"
                inputMode="numeric"
                min={0}
                step={1}
                required
                value={draft[field]}
                onChange={(event) => setDraft((current) => ({ ...current, [field]: event.target.value }))}
                className={numberFieldClass}
              />
            </Field>
          ))}
        </fieldset>
        <AdminButton type="submit" disabled={!isChanged || isSaving} className="h-9 min-w-20">
          {isSaving ? <Spinner /> : "Save"}
        </AdminButton>
      </div>

      {error ? (
        <div className="px-5 pb-4">
          <StatusLine tone="error">{error}</StatusLine>
        </div>
      ) : null}
    </form>
  )
}

const ZONES = [
  { key: "blocked", label: "Must update", className: "bg-admin-fg/70" },
  { key: "asked", label: "Asked to update", className: "bg-admin-fg/25" },
  { key: "current", label: "Up to date", className: "bg-admin-fg/[0.08]" }
] as const

/** Lays the three builds on one line, so it reads who gets blocked, who gets asked and who is fine. */
function BuildRuler({ policy }: Readonly<{ policy: AppVersionPolicy }>) {
  const { liveBuild, recommendedBuild, minimumBuild } = policy
  const askedUntil = Math.max(minimumBuild, recommendedBuild)
  const low = Math.min(liveBuild, minimumBuild, recommendedBuild)
  const high = Math.max(liveBuild, minimumBuild, recommendedBuild)
  const pad = Math.max(high - low, 4) * 0.4
  const start = Math.max(0, low - pad)
  const end = high + pad
  const at = (build: number) => ((build - start) / (end - start)) * 100

  const liveAt = at(liveBuild)
  const widths = {
    blocked: at(minimumBuild),
    asked: at(askedUntil) - at(minimumBuild),
    current: 100 - at(askedUntil)
  }

  return (
    <div>
      <div className="relative h-6">
        <span
          className={cn(
            "absolute bottom-1 flex items-baseline gap-1 whitespace-nowrap font-mono text-xs tabular-nums",
            liveAt < 8 ? "-translate-x-1.5" : liveAt > 92 ? "-translate-x-[calc(100%-6px)]" : "-translate-x-1/2"
          )}
          style={{ left: `${liveAt}%` }}
        >
          <span className="text-admin-fg">{liveBuild}</span>
          <span className="text-admin-muted">live</span>
        </span>
      </div>

      <div className="relative">
        <div className="flex h-1.5 overflow-hidden rounded-full">
          {ZONES.map((zone) => (
            <span key={zone.key} className={zone.className} style={{ width: `${widths[zone.key]}%` }} />
          ))}
        </div>
        <span
          className="absolute top-1/2 -translate-x-1/2 -translate-y-1/2 rounded-full ring-[3px] ring-admin-fill"
          style={{ left: `${liveAt}%` }}
        >
          <LiveDot className="block size-2.5" />
        </span>
      </div>

      <div className="relative mt-2 h-4 font-mono text-xs tabular-nums text-admin-fg-2">
        {minimumBuild > start ? (
          <span
            className={at(minimumBuild) < 8 ? "absolute pl-1.5" : "absolute -translate-x-full pr-1.5"}
            style={{ left: `${at(minimumBuild)}%` }}
          >
            {minimumBuild}
          </span>
        ) : null}
        {recommendedBuild > minimumBuild ? (
          <span className="absolute pl-1.5" style={{ left: `${at(recommendedBuild)}%` }}>
            {recommendedBuild}
          </span>
        ) : null}
      </div>

      <ul className="mt-3 flex flex-wrap gap-x-5 gap-y-1 text-xs text-admin-fg-2">
        {ZONES.map((zone) => (
          <li key={zone.key} className="inline-flex items-center gap-2">
            <span aria-hidden className={`size-2 rounded-full ${zone.className}`} />
            {zone.label}
          </li>
        ))}
      </ul>
    </div>
  )
}

function toDraft(policy: AppVersionPolicy): PolicyDraft {
  return {
    liveBuild: String(policy.liveBuild),
    recommendedBuild: String(policy.recommendedBuild),
    minimumBuild: String(policy.minimumBuild)
  }
}

function fromDraft(draft: PolicyDraft): AppVersionPolicy | null {
  const policy: AppVersionPolicy = {
    liveBuild: Number(draft.liveBuild),
    recommendedBuild: Number(draft.recommendedBuild),
    minimumBuild: Number(draft.minimumBuild)
  }
  const isValid = Object.values(draft).every((value) => value.trim() !== "") &&
    Object.values(policy).every((value) => Number.isInteger(value) && value >= 0)
  return isValid ? policy : null
}
