import { createHash } from "node:crypto"
import { createRemoteJWKSet, jwtVerify, type JWTPayload, type JWTVerifyGetKey } from "jose"
import type { AppConfig } from "../config/config.js"
import { HttpError } from "../http/HttpError.js"

export const APPLE_ISSUER = "https://appleid.apple.com"

export interface AppleIdentity {
  subject: string
  /** Missing only if the person never shared one with Mulberry. */
  email: string | null
  /** Whether Apple has checked the person owns `email`. Only then can it match another account's. */
  emailVerified: boolean
  /** The bundle ID the token was issued to. Apple's tokens can be exchanged and revoked only for it. */
  clientId: string
}

export interface AppleTokenVerifier {
  /** Checks an identity token, and that it carries the SHA-256 of `nonce`, as the app asked for. */
  verify(identityToken: string, nonce: string): Promise<AppleIdentity>
}

/** Apple sends booleans in its identity tokens either as JSON booleans or as "true" and "false". */
interface AppleIdTokenPayload extends JWTPayload {
  email?: string
  email_verified?: boolean | "true" | "false"
  nonce?: string
}

export class DefaultAppleTokenVerifier implements AppleTokenVerifier {
  private readonly keys: JWTVerifyGetKey

  constructor(
    private readonly config: AppConfig,
    keys: JWTVerifyGetKey = createRemoteJWKSet(new URL(`${APPLE_ISSUER}/auth/keys`)),
  ) {
    this.keys = keys
  }

  async verify(identityToken: string, nonce: string): Promise<AppleIdentity> {
    if (this.config.allowDevAppleTokens && identityToken.startsWith("dev-apple:")) {
      return developmentIdentity(identityToken)
    }

    const audiences = this.config.appleClientIds ?? []
    if (audiences.length === 0) {
      throw new HttpError(500, "Sign in with Apple is not configured")
    }

    const payload = await this.verifiedPayload(identityToken, audiences)
    if (!payload.sub) {
      throw new HttpError(401, "Apple token is missing required claims")
    }
    if (!nonce || payload.nonce !== sha256Hex(nonce)) {
      throw new HttpError(401, "Invalid Apple token: nonce mismatch")
    }
    const clientId = Array.isArray(payload.aud) ? payload.aud[0] : payload.aud
    if (!clientId) {
      throw new HttpError(401, "Apple token is missing required claims")
    }

    return {
      subject: payload.sub,
      email: payload.email ?? null,
      emailVerified: claimIsTrue(payload.email_verified),
      clientId,
    }
  }

  private async verifiedPayload(identityToken: string, audiences: string[]): Promise<AppleIdTokenPayload> {
    try {
      const { payload } = await jwtVerify<AppleIdTokenPayload>(identityToken, this.keys, {
        issuer: APPLE_ISSUER,
        audience: audiences,
        algorithms: ["RS256"],
      })
      return payload
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      throw new HttpError(401, `Invalid Apple token: ${message}`)
    }
  }
}

export function sha256Hex(value: string): string {
  return createHash("sha256").update(value).digest("hex")
}

function claimIsTrue(value: boolean | "true" | "false" | undefined): boolean {
  return value === true || value === "true"
}

/** `dev-apple:SUBJECT:EMAIL`, for local development and tests. */
function developmentIdentity(token: string): AppleIdentity {
  const [, subject, email] = token.split(":")
  if (!subject) {
    throw new HttpError(401, "Invalid development Apple token")
  }
  return {
    subject,
    email: email || null,
    emailVerified: Boolean(email),
    clientId: "com.subhajit.mulberry.beta.dev",
  }
}
