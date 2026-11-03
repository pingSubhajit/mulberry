import { createHash, randomUUID } from "node:crypto"
import type { Database } from "../../infra/db/database.js"
import { HttpError } from "../../infra/http/HttpError.js"
import type { DeviceTokenRow } from "../../contracts/dbRecords.js"
import type {
  DeviceTokenRecord,
  RegisterFcmTokenRequest,
  UpdateLiveActivityTokensRequest,
  UpdateWidgetPushTokenRequest,
  WidgetTimeTogetherResponse,
} from "../../contracts/devices.js"
import { DevicePlatforms } from "../../contracts/devices.js"
import { requireSessionContext } from "../_shared/session.js"
import { getPairSession } from "../_shared/pairs.js"
import { getProfileFrom } from "../_shared/profiles.js"

export class DevicesService {
  constructor(private readonly db: Database) {}

  async registerFcmToken(accessToken: string, request: RegisterFcmTokenRequest): Promise<DeviceTokenRecord> {
    const context = await requireSessionContext(this.db, accessToken)
    if (!request.token?.trim()) {
      throw new HttpError(400, "token is required")
    }
    if (!DevicePlatforms.includes(request.platform)) {
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
        sign_in_id,
        last_seen_at,
        revoked_at
      ) VALUES ($1, $2, $3, $4, $5, $6, NOW(), NULL)
      ON CONFLICT (token) DO UPDATE SET
        user_id = EXCLUDED.user_id,
        platform = EXCLUDED.platform,
        app_environment = EXCLUDED.app_environment,
        sign_in_id = EXCLUDED.sign_in_id,
        -- An activity on screen belongs to the account that was signed in.
        live_activity_token = CASE
          WHEN device_tokens.user_id = EXCLUDED.user_id THEN device_tokens.live_activity_token
        END,
        last_seen_at = NOW(),
        revoked_at = NULL
      RETURNING id, user_id, token, platform, app_environment, live_activity_start_token,
        live_activity_token, widget_push_token, widget_push_bundle_id, widget_push_environment,
        sign_in_id, last_seen_at, revoked_at
      `,
      [
        tokenId,
        context.user.id,
        request.token.trim(),
        request.platform,
        request.appEnvironment.trim(),
        context.session.sign_in_id,
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

  /** Where the reactions Live Activity of this signed-in iPhone can be started or updated by push. */
  async updateLiveActivityTokens(accessToken: string, request: UpdateLiveActivityTokensRequest): Promise<void> {
    const context = await requireSessionContext(this.db, accessToken)
    if (!request.token?.trim()) {
      throw new HttpError(400, "token is required")
    }
    const pushToStartToken = liveActivityTokenChange(request.pushToStartToken)
    const activityPushToken = liveActivityTokenChange(request.activityPushToken)

    const rows = await this.db.query<Pick<DeviceTokenRow, "id">>(
      `
      UPDATE device_tokens
      SET
        live_activity_start_token = CASE WHEN $3 THEN $4 ELSE live_activity_start_token END,
        live_activity_token = CASE WHEN $5 THEN $6 ELSE live_activity_token END
      WHERE user_id = $1 AND token = $2 AND platform = 'IOS' AND revoked_at IS NULL
      RETURNING id
      `,
      [
        context.user.id,
        request.token.trim(),
        pushToStartToken.changed,
        pushToStartToken.value,
        activityPushToken.changed,
        activityPushToken.value,
      ],
    )
    if (rows.rows.length === 0) {
      throw new HttpError(404, "Register this device for push first")
    }
  }

  /** What the Time together widget shows, for the signed-in device whose widget holds `secret`. The
   * widget asks each time WidgetKit reloads it, so it shows the change a WidgetKit push announces even
   * when the app never got the silent push. */
  async widgetTimeTogether(secret: string): Promise<WidgetTimeTogetherResponse> {
    const normalized = secret.trim().toLowerCase()
    if (!WIDGET_SECRET.test(normalized)) {
      throw new HttpError(401, "Unknown widget")
    }
    const rows = await this.db.query<Pick<DeviceTokenRow, "user_id">>(
      `SELECT user_id FROM device_tokens WHERE widget_secret_hash = $1 AND revoked_at IS NULL LIMIT 1`,
      [widgetSecretHash(normalized)],
    )
    const userId = rows.rows[0]?.user_id
    if (!userId) {
      throw new HttpError(401, "Unknown widget")
    }
    const [profile, pairSession] = await Promise.all([getProfileFrom(this.db, userId), getPairSession(this.db, userId)])
    return {
      anniversaryDate: profile?.anniversary_date ?? null,
      pairSessionId: pairSession?.id ?? null,
    }
  }

  /** Where this signed-in iPhone, iPad or Mac takes widget reloads. */
  async updateWidgetPushToken(accessToken: string, request: UpdateWidgetPushTokenRequest): Promise<void> {
    const context = await requireSessionContext(this.db, accessToken)
    if (!request.token?.trim()) {
      throw new HttpError(400, "token is required")
    }
    const widgetToken = request.widgetPushToken === null ? null : request.widgetPushToken?.trim().toLowerCase()
    if (widgetToken !== null && (!widgetToken || !HEX_TOKEN.test(widgetToken))) {
      throw new HttpError(400, "widgetPushToken must be a hex token or null")
    }
    if (!BUNDLE_ID.test(request.bundleId ?? "")) {
      throw new HttpError(400, "bundleId is invalid")
    }
    if (request.apnsEnvironment !== "sandbox" && request.apnsEnvironment !== "production") {
      throw new HttpError(400, "apnsEnvironment must be sandbox or production")
    }
    const secret = request.widgetSecret?.trim().toLowerCase()
    if (secret !== undefined && !WIDGET_SECRET.test(secret)) {
      throw new HttpError(400, "widgetSecret must be 64 hex characters")
    }

    const rows = await this.db.query<Pick<DeviceTokenRow, "id">>(
      `
      UPDATE device_tokens
      SET
        widget_push_token = $3,
        widget_push_bundle_id = CASE WHEN $3::text IS NULL THEN NULL ELSE $4 END,
        widget_push_environment = CASE WHEN $3::text IS NULL THEN NULL ELSE $5 END,
        widget_secret_hash = COALESCE($6, widget_secret_hash)
      WHERE user_id = $1 AND token = $2 AND platform IN ('IOS', 'MACOS') AND revoked_at IS NULL
      RETURNING id
      `,
      [
        context.user.id,
        request.token.trim(),
        widgetToken,
        request.bundleId,
        request.apnsEnvironment,
        secret ? widgetSecretHash(secret) : null,
      ],
    )
    if (rows.rows.length === 0) {
      throw new HttpError(404, "Register this device for push first")
    }
  }
}

const WIDGET_SECRET = /^[0-9a-f]{64}$/

function widgetSecretHash(secret: string): string {
  return createHash("sha256").update(secret).digest("hex")
}

/** A bundle ID as Apple allows it, which also keeps the APNs topic well formed. */
const BUNDLE_ID = /^[A-Za-z0-9.-]{1,155}$/
const HEX_TOKEN = /^[0-9a-f]{16,512}$/

/** A token given as a string sets it, `null` clears it, and an absent one leaves it as it was. */
function liveActivityTokenChange(value: unknown): { changed: boolean; value: string | null } {
  if (value === undefined) return { changed: false, value: null }
  if (value === null) return { changed: true, value: null }
  if (typeof value !== "string" || !value.trim()) {
    throw new HttpError(400, "Live Activity tokens must be non-empty strings or null")
  }
  return { changed: true, value: value.trim() }
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

