export const DevicePlatforms = ["ANDROID", "MACOS", "IOS"] as const
export type DevicePlatform = (typeof DevicePlatforms)[number]

export interface RegisterFcmTokenRequest {
  token: string
  platform: DevicePlatform
  appEnvironment: string
}

export interface DeviceTokenRecord {
  id: string
  userId: string
  token: string
  platform: DevicePlatform
  appEnvironment: string
  lastSeenAt: string
  revokedAt: string | null
}

/** An iPhone's reactions Live Activity tokens, sent as they change. `null` clears one; an absent one
 * stays as it was. */
export interface UpdateLiveActivityTokensRequest {
  /** The FCM registration token the device registered. */
  token: string
  pushToStartToken?: string | null
  activityPushToken?: string | null
}

/** Where WidgetKit takes reloads for this device's widgets. `null` forgets the widget token. */
export interface UpdateWidgetPushTokenRequest {
  /** The FCM registration token the device registered. */
  token: string
  /** WidgetKit's push token for the app's widgets, as hex. */
  widgetPushToken: string | null
  /** The app's bundle ID, which names the APNs topic. */
  bundleId: string
  /** Which APNs environment the build receives through. */
  apnsEnvironment: "sandbox" | "production"
  /** A random secret, as hex, the widget proves itself with when it asks for what to show. */
  widgetSecret?: string
}

/** What the Time together widget shows, for the device whose widget asks. */
export interface WidgetTimeTogetherResponse {
  anniversaryDate: string | null
  pairSessionId: string | null
}
