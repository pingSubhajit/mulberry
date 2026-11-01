import type { BootstrapResponse } from "./bootstrap.js"

export const AuthStatuses = ["SIGNED_OUT", "SIGNED_IN", "REFRESHING"] as const
export type AuthStatus = (typeof AuthStatuses)[number]

export interface AuthResponse {
  accessToken: string
  refreshToken: string
  userId: string
  bootstrapState: BootstrapResponse
}


/** The ways into an account. One account can have both. */
export const SignInMethods = ["google", "apple"] as const
export type SignInMethod = (typeof SignInMethods)[number]

/** What the app sends after Sign in with Apple. */
export interface AppleSignInRequest {
  identityToken: string
  /** Exchanged for Apple's refresh token, so it can be revoked if the account is deleted. */
  authorizationCode?: string
  /** The raw nonce whose SHA-256 the app put in its request. */
  nonce: string
  /** Apple shares the person's name only the first time they sign in, with the app, not the token. */
  displayName?: string
}
