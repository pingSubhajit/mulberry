export const DevicePlatforms = ["ANDROID"] as const
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

