import { OAuth2Client } from "google-auth-library"
import type { AppConfig } from "../config/config.js"
import { HttpError } from "../http/HttpError.js"

export interface GoogleIdentity {
  subject: string
  email: string
  name: string | null
  pictureUrl: string | null
}

export interface GoogleTokenVerifier {
  verify(idToken: string): Promise<GoogleIdentity>
}

interface GoogleIdTokenClient {
  verifyIdToken(options: { idToken: string; audience: string | string[] }): Promise<{
    getPayload(): {
      sub?: string
      email?: string
      name?: string
      picture?: string
    } | undefined
  }>
}

export class DefaultGoogleTokenVerifier implements GoogleTokenVerifier {
  private readonly client: GoogleIdTokenClient

  constructor(
    private readonly config: AppConfig,
    client: GoogleIdTokenClient = new OAuth2Client(),
  ) {
    this.client = client
  }

  async verify(idToken: string): Promise<GoogleIdentity> {
    if (this.config.allowDevGoogleTokens && idToken.startsWith("dev-google:")) {
      const [, email, name, ...pictureParts] = idToken.split(":")
      const pictureUrl = pictureParts.join(":")
      if (!email) {
        throw new HttpError(401, "Invalid development Google token")
      }
      return {
        subject: email,
        email,
        name: name ?? null,
        pictureUrl: pictureUrl.length > 0 ? pictureUrl : null,
      }
    }

    const audiences = this.allowedAudiences()
    if (audiences.length === 0) {
      throw new HttpError(500, "Google client id is not configured")
    }

    const ticket = await this.verifyGoogleIDToken(idToken, audiences)
    const payload = ticket.getPayload()

    if (!payload?.sub || !payload.email) {
      throw new HttpError(401, "Google token is missing required claims")
    }

    return {
      subject: payload.sub,
      email: payload.email,
      name: payload.name ?? null,
      pictureUrl: payload.picture ?? null,
    }
  }

  private allowedAudiences(): string[] {
    return Array.from(
      new Set(
        [this.config.googleClientId, ...this.config.googleAllowedClientIds]
          .map((audience) => audience.trim())
          .filter((audience) => audience.length > 0),
      ),
    )
  }

  private async verifyGoogleIDToken(idToken: string, audiences: string[]) {
    try {
      return await this.client.verifyIdToken({
        idToken,
        audience: audiences,
      })
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      throw new HttpError(401, `Invalid Google token: ${message}`)
    }
  }
}
