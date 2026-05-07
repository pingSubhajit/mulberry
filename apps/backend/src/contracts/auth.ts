import type { BootstrapResponse } from "./bootstrap.js"

export const AuthStatuses = ["SIGNED_OUT", "SIGNED_IN", "REFRESHING"] as const
export type AuthStatus = (typeof AuthStatuses)[number]

export interface AuthResponse {
  accessToken: string
  refreshToken: string
  userId: string
  bootstrapState: BootstrapResponse
}

