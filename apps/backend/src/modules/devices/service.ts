import { randomUUID } from "node:crypto"
import type { Database } from "../../infra/db/database.js"
import { HttpError } from "../../infra/http/HttpError.js"
import type { DeviceTokenRow } from "../../contracts/dbRecords.js"
import type { DeviceTokenRecord, RegisterFcmTokenRequest } from "../../contracts/devices.js"
import { requireSessionContext } from "../_shared/session.js"

export class DevicesService {
  constructor(private readonly db: Database) {}

  async registerFcmToken(accessToken: string, request: RegisterFcmTokenRequest): Promise<DeviceTokenRecord> {
    const context = await requireSessionContext(this.db, accessToken)
    if (!request.token?.trim()) {
      throw new HttpError(400, "token is required")
    }
    if (request.platform !== "ANDROID") {
      throw new HttpError(400, "Unsupported device platform")
    }
    if (!request.appEnvironment?.trim()) {
      throw new HttpError(400, "appEnvironment is required")
    }

    const tokenId = randomUUID()
    const rows = await this.db.query<DeviceTokenRow>(
      `
      INSERT INTO device_tokens (
        id,
        user_id,
        token,
        platform,
        app_environment,
        last_seen_at,
        revoked_at
      ) VALUES ($1, $2, $3, $4, $5, NOW(), NULL)
      ON CONFLICT (token) DO UPDATE SET
        user_id = EXCLUDED.user_id,
        platform = EXCLUDED.platform,
        app_environment = EXCLUDED.app_environment,
        last_seen_at = NOW(),
        revoked_at = NULL
      RETURNING id, user_id, token, platform, app_environment, last_seen_at, revoked_at
      `,
      [
        tokenId,
        context.user.id,
        request.token.trim(),
        request.platform,
        request.appEnvironment.trim(),
      ],
    )
    return deviceTokenToRecord(rows.rows[0])
  }

  async unregisterFcmToken(accessToken: string, token: string): Promise<void> {
    const context = await requireSessionContext(this.db, accessToken)
    if (!token.trim()) {
      throw new HttpError(400, "token is required")
    }

    await this.db.query(
      `
      UPDATE device_tokens
      SET revoked_at = NOW()
      WHERE user_id = $1 AND token = $2 AND revoked_at IS NULL
      `,
      [context.user.id, token.trim()],
    )
  }
}

function deviceTokenToRecord(row: DeviceTokenRow): DeviceTokenRecord {
  return {
    id: row.id,
    userId: row.user_id,
    token: row.token,
    platform: row.platform,
    appEnvironment: row.app_environment,
    lastSeenAt: new Date(row.last_seen_at).toISOString(),
    revokedAt: row.revoked_at ? new Date(row.revoked_at).toISOString() : null,
  }
}

