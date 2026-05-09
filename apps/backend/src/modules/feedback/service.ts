import { createHmac } from "node:crypto"
import type { Database } from "../../infra/db/database.js"
import { HttpError } from "../../infra/http/HttpError.js"
import { getProfileFrom } from "../_shared/profiles.js"
import { requireSessionContext } from "../_shared/session.js"

export interface CannySsoTokenResponse {
  token: string
}

export class FeedbackService {
  constructor(
    private readonly db: Database,
    private readonly cannySsoPrivateKey?: string,
  ) {}

  async createCannySsoToken(accessToken: string): Promise<CannySsoTokenResponse> {
    if (!this.cannySsoPrivateKey?.trim()) {
      throw new HttpError(503, "Feedback is not configured yet.")
    }

    const context = await requireSessionContext(this.db, accessToken)
    const profile = await getProfileFrom(this.db, context.user.id)
    return {
      token: signHs256Jwt(
        {
          avatarURL: context.user.google_picture_url ?? undefined,
          email: context.user.email,
          exp: Math.floor(Date.now() / 1000) + 60 * 60,
          id: context.user.id,
          name: profile?.display_name?.trim() || context.user.email,
        },
        this.cannySsoPrivateKey,
      ),
    }
  }
}

function signHs256Jwt(payload: Record<string, unknown>, privateKey: string): string {
  const header = { alg: "HS256", typ: "JWT" }
  const encodedHeader = base64UrlJson(header)
  const encodedPayload = base64UrlJson(payload)
  const unsignedToken = `${encodedHeader}.${encodedPayload}`
  const signature = createHmac("sha256", privateKey)
    .update(unsignedToken)
    .digest("base64url")
  return `${unsignedToken}.${signature}`
}

function base64UrlJson(value: Record<string, unknown>): string {
  return Buffer.from(JSON.stringify(value)).toString("base64url")
}
