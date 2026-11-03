export const APP_PLATFORMS = ["ios", "android"] as const
export type AppPlatform = (typeof APP_PLATFORMS)[number]

/** The build numbers that decide whether an app prompts for, or requires, an update. */
export interface AppVersionPolicy {
  /** The newest build live in the store. Neither threshold may go above it. */
  liveBuild: number
  /** Builds below this see a prompt to update that they can dismiss. */
  recommendedBuild: number
  /** Builds below this are blocked until they update. */
  minimumBuild: number
}

/** One change made from the admin page, read back from Remote Config's version history. */
export interface AppVersionsChange {
  version: string
  updatedAt: string | null
  description: string
}

export interface AppVersionsResponse {
  /** Every platform's values, in a sentence. */
  message: string
  policies: Record<AppPlatform, AppVersionPolicy>
  history: AppVersionsChange[]
}

/** A `PATCH /admin/app-versions/:platform` body. Any of the three values; the rest stay. */
export interface AppVersionsPatch extends Partial<AppVersionPolicy> {
  /** Required to raise `minimumBuild`, which blocks every older build. */
  confirm?: boolean
}

export interface AppVersionsUpdate extends AppVersionsResponse {
  platform: AppPlatform
  previous: AppVersionPolicy
  policy: AppVersionPolicy
  changed: (keyof AppVersionPolicy)[]
}
