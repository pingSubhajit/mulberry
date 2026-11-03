import type {
  RemoteConfig,
  RemoteConfigParameter,
  RemoteConfigParameterValue,
  RemoteConfigTemplate,
} from "firebase-admin/remote-config"
import {
  APP_PLATFORMS,
  type AppPlatform,
  type AppVersionPolicy,
  type AppVersionsChange,
  type AppVersionsPatch,
  type AppVersionsResponse,
  type AppVersionsUpdate,
} from "../../contracts/appVersions.js"
import { HttpError } from "../../infra/http/HttpError.js"
import { requireAdminPassword } from "../../infra/http/adminAuth.js"

/** The part of the Firebase Remote Config client this service uses. */
export type RemoteConfigClient = Pick<RemoteConfig, "getTemplate" | "publishTemplate" | "listVersions">

type PolicyField = keyof AppVersionPolicy

const POLICY_FIELDS: readonly PolicyField[] = ["liveBuild", "recommendedBuild", "minimumBuild"]

/** The Remote Config keys the apps read. */
export const APP_VERSION_PARAMETERS: Record<AppPlatform, Record<PolicyField, string>> = {
  ios: { liveBuild: "live_build_ios", recommendedBuild: "recommended_build_ios", minimumBuild: "min_build_ios" },
  android: {
    liveBuild: "live_build_android",
    recommendedBuild: "recommended_build_android",
    minimumBuild: "min_build_android",
  },
}

const PARAMETER_DESCRIPTIONS: Record<PolicyField, string> = {
  liveBuild: "Newest build live in the store. Set from the Mulberry admin.",
  recommendedBuild: "Builds below this are asked to update. Set from the Mulberry admin.",
  minimumBuild: "Builds below this must update. Set from the Mulberry admin.",
}

const PARAMETER_GROUP = "App versions"
/** Starts every version description this page publishes, so its history can be picked out. */
const CHANGE_PREFIX = "App versions: "
const HISTORY_SCAN_SIZE = 50
const HISTORY_LIMIT = 10
const MAX_BUILD = 2_100_000_000
/** How old the copy of the minimums that requests are checked against may grow before it's fetched again. */
const MINIMUMS_TTL_MS = 60_000

const PLATFORM_NAMES: Record<AppPlatform, string> = { ios: "iOS", android: "Android" }
/** When a build counts as live, for the messages that ask for it. */
const FULLY_LIVE: Record<AppPlatform, string> = {
  ios: "showing in the App Store in every region",
  android: "at 100% of the Play rollout",
}
const PATCH_FIELDS: readonly (keyof AppVersionsPatch)[] = [...POLICY_FIELDS, "confirm"]
const FIELD_NAMES: Record<PolicyField, string> = {
  liveBuild: "live",
  recommendedBuild: "recommended",
  minimumBuild: "minimum",
}

export class AppVersionsService {
  private minimums: Record<AppPlatform, number> | null = null
  private minimumsReadAt = Number.NEGATIVE_INFINITY
  private minimumsRefresh: Promise<void> | null = null

  constructor(
    private readonly remoteConfig: RemoteConfigClient | null,
    private readonly adminPassword?: string,
  ) {}

  async read(adminPassword: string | undefined): Promise<AppVersionsResponse> {
    requireAdminPassword(this.adminPassword, adminPassword)
    const remoteConfig = this.requireRemoteConfig()
    const template = await callRemoteConfig(() => remoteConfig.getTemplate())
    const policies = readPolicies(template)
    this.rememberMinimums(policies)
    const message = APP_PLATFORMS.map((platform) => `${PLATFORM_NAMES[platform]}: ${describe(policies[platform])}.`).join(" ")
    return { message, policies, history: await this.history(remoteConfig) }
  }

  /**
   * The build `platform` must be at least, from a copy at most a minute old. A stale copy is
   * refreshed in the background, so requests never wait on Remote Config. Null until the first read
   * succeeds; a failed read keeps the last copy.
   */
  minimumBuild(platform: AppPlatform): number | null {
    if (Date.now() - this.minimumsReadAt > MINIMUMS_TTL_MS) void this.refreshMinimums()
    return this.minimums?.[platform] ?? null
  }

  /** Reads the minimums now, as the server starts. */
  refreshMinimums(): Promise<void> {
    const remoteConfig = this.remoteConfig
    if (!remoteConfig) return Promise.resolve()
    this.minimumsRefresh ??= remoteConfig
      .getTemplate()
      .then((template) => this.rememberMinimums(readPolicies(template)))
      .catch((error: unknown) => {
        // Wait out the usual interval before trying again, rather than on every request.
        this.minimumsReadAt = Date.now()
        console.warn("[app-versions] could not read minimum builds", {
          error: error instanceof Error ? error.message : String(error),
        })
      })
      .finally(() => {
        this.minimumsRefresh = null
      })
    return this.minimumsRefresh
  }

  private rememberMinimums(policies: Record<AppPlatform, AppVersionPolicy>): void {
    this.minimums = { ios: policies.ios.minimumBuild, android: policies.android.minimumBuild }
    this.minimumsReadAt = Date.now()
  }

  /** Changes any of a platform's values, keeping the rest, and explains what the change does. */
  async update(input: {
    adminPassword: string | undefined
    platform: string | undefined
    body: unknown
  }): Promise<AppVersionsUpdate> {
    requireAdminPassword(this.adminPassword, input.adminPassword)
    const platform = parsePlatform(input.platform)
    const patch = parsePatch(input.body)
    const remoteConfig = this.requireRemoteConfig()

    const template = await callRemoteConfig(() => remoteConfig.getTemplate())
    const previous = readPolicies(template)[platform]
    const policy: AppVersionPolicy = {
      liveBuild: patch.liveBuild ?? previous.liveBuild,
      recommendedBuild: patch.recommendedBuild ?? previous.recommendedBuild,
      minimumBuild: patch.minimumBuild ?? previous.minimumBuild,
    }
    validate(platform, previous, policy)
    if (policy.minimumBuild > previous.minimumBuild && patch.confirm !== true) {
      const name = PLATFORM_NAMES[platform]
      throw new HttpError(
        409,
        `Raising the ${name} minimum from ${previous.minimumBuild} to ${policy.minimumBuild} blocks every ${name} build ` +
          `below ${policy.minimumBuild} until it updates. Check that build ${policy.minimumBuild} is ${FULLY_LIVE[platform]}, ` +
          `then resend with "confirm": true.`,
        { code: "CONFIRMATION_REQUIRED", platform, current: previous, proposed: policy },
      )
    }

    const changed = POLICY_FIELDS.filter((field) => previous[field] !== policy[field])
    if (changed.length > 0) {
      for (const field of changed) {
        writeParameter(template, APP_VERSION_PARAMETERS[platform][field], policy[field], PARAMETER_DESCRIPTIONS[field])
      }
      const summary = changed.map((field) => `${FIELD_NAMES[field]} ${previous[field]} → ${policy[field]}`).join(", ")
      template.version = { description: `${CHANGE_PREFIX}${PLATFORM_NAMES[platform]} ${summary}` }
      await callRemoteConfig(() => remoteConfig.publishTemplate(template))
    }

    const state = await this.read(input.adminPassword)
    return { ...state, message: updateMessage(platform, previous, policy, changed), platform, previous, policy, changed }
  }

  private requireRemoteConfig(): RemoteConfigClient {
    if (!this.remoteConfig) {
      throw new HttpError(503, "Remote Config needs a Firebase service account")
    }
    return this.remoteConfig
  }

  private async history(remoteConfig: RemoteConfigClient): Promise<AppVersionsChange[]> {
    const { versions } = await callRemoteConfig(() => remoteConfig.listVersions({ pageSize: HISTORY_SCAN_SIZE }))
    return versions
      .filter((version) => version.description?.startsWith(CHANGE_PREFIX))
      .slice(0, HISTORY_LIMIT)
      .map((version) => ({
        version: version.versionNumber ?? "",
        updatedAt: version.updateTime ? new Date(version.updateTime).toISOString() : null,
        description: (version.description ?? "").slice(CHANGE_PREFIX.length),
      }))
  }
}

function parsePlatform(value: string | undefined): AppPlatform {
  const platform = APP_PLATFORMS.find((candidate) => candidate === value)
  if (!platform) {
    throw new HttpError(404, `Unknown platform "${value ?? ""}". Use one of: ${APP_PLATFORMS.join(", ")}.`, {
      code: "UNKNOWN_PLATFORM",
    })
  }
  return platform
}

const EXAMPLE_PATCH = '{"recommendedBuild": 7}'

/** Takes whole numbers, or strings of digits, for the build fields, and rejects anything it doesn't know. */
function parsePatch(body: unknown): AppVersionsPatch {
  if (typeof body !== "object" || body === null || Array.isArray(body)) {
    throw invalid(`Send a JSON object with any of ${POLICY_FIELDS.join(", ")}, for example ${EXAMPLE_PATCH}.`)
  }
  const fields = Object.entries(body)
  const unknown = fields.map(([key]) => key).filter((key) => !PATCH_FIELDS.some((field) => field === key))
  if (unknown.length > 0) {
    throw invalid(`Unknown field ${unknown.map((key) => `"${key}"`).join(", ")}. Use ${PATCH_FIELDS.join(", ")}.`)
  }

  const patch: AppVersionsPatch = {}
  for (const [key, value] of fields) {
    if (key === "confirm") {
      if (typeof value !== "boolean") throw invalid(`"confirm" must be true or false, not ${JSON.stringify(value)}.`)
      patch.confirm = value
      continue
    }
    const field = POLICY_FIELDS.find((candidate) => candidate === key)
    if (field) patch[field] = parseBuild(field, value)
  }
  if (!POLICY_FIELDS.some((field) => patch[field] !== undefined)) {
    throw invalid(`Nothing to change. Send any of ${POLICY_FIELDS.join(", ")}, for example ${EXAMPLE_PATCH}.`)
  }
  return patch
}

function parseBuild(field: PolicyField, value: unknown): number {
  const parsed = typeof value === "string" && /^\d+$/.test(value) ? Number(value) : value
  if (typeof parsed !== "number" || !Number.isInteger(parsed) || parsed < 0 || parsed > MAX_BUILD) {
    throw invalid(`"${field}" must be a whole number from 0 to ${MAX_BUILD}, not ${JSON.stringify(value)}.`)
  }
  return parsed
}

/** The values must keep minimum ≤ recommended ≤ live; each message says how to get there. */
function validate(platform: AppPlatform, current: AppVersionPolicy, next: AppVersionPolicy): void {
  const details = { platform, current, proposed: next }
  if (next.recommendedBuild > next.liveBuild) {
    throw invalid(
      `The recommended build (${next.recommendedBuild}) can't be above the live build (${next.liveBuild}). ` +
        `Once build ${next.recommendedBuild} is ${FULLY_LIVE[platform]}, send "liveBuild": ${next.recommendedBuild} in the same request.`,
      details,
    )
  }
  if (next.minimumBuild > next.recommendedBuild) {
    throw invalid(
      `The minimum build (${next.minimumBuild}) can't be above the recommended build (${next.recommendedBuild}). ` +
        `Send "recommendedBuild": ${next.minimumBuild} or higher in the same request.`,
      details,
    )
  }
}

function invalid(message: string, details: Record<string, unknown> = {}): HttpError {
  return new HttpError(400, message, { code: "INVALID_APP_VERSIONS", ...details })
}

function describe(policy: AppVersionPolicy): string {
  return `live ${policy.liveBuild}, recommended ${policy.recommendedBuild}, minimum ${policy.minimumBuild}`
}

function updateMessage(platform: AppPlatform, previous: AppVersionPolicy, next: AppVersionPolicy, changed: PolicyField[]): string {
  const name = PLATFORM_NAMES[platform]
  if (changed.length === 0) return `Nothing changed: ${name} is already ${describe(next)}.`

  const summary = changed.map((field) => `${FIELD_NAMES[field]} ${previous[field]} → ${next[field]}`).join(", ")
  const effects: string[] = []
  if (changed.includes("liveBuild")) effects.push(`${name}'s live build is now ${next.liveBuild}.`)
  if (changed.includes("recommendedBuild")) {
    effects.push(next.recommendedBuild === 0
      ? `No ${name} build is asked to update.`
      : `${name} builds below ${next.recommendedBuild} are asked to update.`)
  }
  if (changed.includes("minimumBuild")) {
    effects.push(next.minimumBuild === 0
      ? `No ${name} build has to update.`
      : `${name} builds below ${next.minimumBuild} must update; the server turns them away within a minute.`)
  }
  return `Updated ${name}: ${summary}. ${effects.join(" ")} Open apps pick this up within seconds, others on their next launch.`
}

function readPolicies(template: RemoteConfigTemplate): Record<AppPlatform, AppVersionPolicy> {
  const policy = (platform: AppPlatform): AppVersionPolicy => {
    const keys = APP_VERSION_PARAMETERS[platform]
    return {
      liveBuild: readNumber(findParameter(template, keys.liveBuild)?.defaultValue),
      recommendedBuild: readNumber(findParameter(template, keys.recommendedBuild)?.defaultValue),
      minimumBuild: readNumber(findParameter(template, keys.minimumBuild)?.defaultValue),
    }
  }
  return { ios: policy("ios"), android: policy("android") }
}

/** A parameter sits either at the top level or inside one group, wherever the console last put it. */
function findParameter(template: RemoteConfigTemplate, key: string): RemoteConfigParameter | undefined {
  if (template.parameters[key]) return template.parameters[key]
  return Object.values(template.parameterGroups).find((group) => group.parameters[key])?.parameters[key]
}

function readNumber(value: RemoteConfigParameterValue | undefined): number {
  if (!value || !("value" in value)) return 0
  const parsed = Number(value.value)
  return Number.isInteger(parsed) && parsed >= 0 ? parsed : 0
}

function writeParameter(template: RemoteConfigTemplate, key: string, value: number, description: string): void {
  const existing = findParameter(template, key)
  if (existing) {
    existing.defaultValue = { value: String(value) }
    existing.valueType = "NUMBER"
    return
  }
  const group = (template.parameterGroups[PARAMETER_GROUP] ??= { parameters: {} })
  group.parameters[key] = { defaultValue: { value: String(value) }, valueType: "NUMBER", description }
}

async function callRemoteConfig<T>(call: () => Promise<T>): Promise<T> {
  try {
    return await call()
  } catch (error) {
    throw remoteConfigError(error)
  }
}

function remoteConfigError(error: unknown): HttpError {
  const code = typeof error === "object" && error !== null && "code" in error ? String(error.code) : ""
  if (code === "remote-config/failed-precondition" || code === "remote-config/aborted") {
    return new HttpError(409, "Remote Config changed while saving. Try again.")
  }
  if (code === "remote-config/permission-denied" || code === "remote-config/unauthenticated") {
    return new HttpError(502, "The Firebase service account can't edit Remote Config")
  }
  const message = error instanceof Error ? error.message : "Unknown error"
  return new HttpError(502, `Remote Config failed: ${message}`)
}
