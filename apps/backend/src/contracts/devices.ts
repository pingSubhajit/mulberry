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
