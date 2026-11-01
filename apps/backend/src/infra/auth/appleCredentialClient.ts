import { importPKCS8, SignJWT } from "jose"
import type { AppleSignInKey } from "../config/config.js"
import { APPLE_ISSUER } from "./appleTokenVerifier.js"

/**
 * Apple's token endpoints. Apple requires apps that delete accounts to revoke the person's Apple
 * tokens too, and revoking takes a refresh token, which only the sign-in's authorization code can
 * be exchanged for.
 */
export interface AppleCredentialClient {
  /** Apple's refresh token for an authorization code, or null when this server has no key. */
  exchangeAuthorizationCode(code: string, clientId: string): Promise<string | null>
  revoke(refreshToken: string, clientId: string): Promise<void>
}

type Fetch = (input: string, init: RequestInit) => Promise<Response>

interface AppleTokenResponse {
  refresh_token?: string
}

export class DefaultAppleCredentialClient implements AppleCredentialClient {
  constructor(
    private readonly key: AppleSignInKey,
    private readonly fetcher: Fetch = fetch,
  ) {}

  async exchangeAuthorizationCode(code: string, clientId: string): Promise<string | null> {
    const response = await this.post("/auth/token", {
      client_id: clientId,
      client_secret: await this.clientSecret(clientId),
      code,
      grant_type: "authorization_code",
    })
    const body = (await response.json()) as AppleTokenResponse
    return body.refresh_token ?? null
  }

  async revoke(refreshToken: string, clientId: string): Promise<void> {
    await this.post("/auth/revoke", {
      client_id: clientId,
      client_secret: await this.clientSecret(clientId),
      token: refreshToken,
      token_type_hint: "refresh_token",
    })
  }

  private async post(path: string, fields: Record<string, string>): Promise<Response> {
    const response = await this.fetcher(`${APPLE_ISSUER}${path}`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams(fields).toString(),
    })
    if (!response.ok) {
      throw new Error(`Apple ${path} failed: HTTP ${response.status} ${await response.text()}`)
    }
    return response
  }

  /** A short-lived ES256 JWT signed with the Sign in with Apple key, as Apple's endpoints expect. */
  private async clientSecret(clientId: string): Promise<string> {
    const privateKey = await importPKCS8(this.key.privateKey, "ES256")
    return new SignJWT({})
      .setProtectedHeader({ alg: "ES256", kid: this.key.keyId })
      .setIssuer(this.key.teamId)
      .setSubject(clientId)
      .setAudience(APPLE_ISSUER)
      .setIssuedAt()
      .setExpirationTime("5m")
      .sign(privateKey)
  }
}

/** Without a key, nothing is stored, so there is nothing to revoke. */
export class UnconfiguredAppleCredentialClient implements AppleCredentialClient {
  async exchangeAuthorizationCode(): Promise<string | null> {
    return null
  }

  async revoke(): Promise<void> {}
}

export function createAppleCredentialClient(key: AppleSignInKey | undefined): AppleCredentialClient {
  return key ? new DefaultAppleCredentialClient(key) : new UnconfiguredAppleCredentialClient()
}
