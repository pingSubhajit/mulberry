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

export class DefaultGoogleTokenVerifier implements GoogleTokenVerifier {
  private readonly client = new OAuth2Client()

  constructor(private readonly config: AppConfig) {}

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

    if (!this.config.googleClientId) {
      throw new HttpError(500, "Google client id is not configured")
    }

    const ticket = await this.client.verifyIdToken({
      idToken,
      audience: this.config.googleClientId,
    })
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
}

