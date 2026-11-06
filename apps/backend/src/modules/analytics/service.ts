import type { Database } from "../../infra/db/database.js"
import { HttpError } from "../../infra/http/HttpError.js"
import { anonymousDistinctId, isUuid } from "../../infra/analytics/context.js"
import {
  ClientEventNames,
  OnboardingSteps,
  PresenceSurfaceTypes,
  PushTypes,
  WallpaperSetupOutcomes,
  WallpaperSetupSteps,
  type ClientContext,
  type ClientEventName,
  type ClientEventProperties,
} from "../../infra/analytics/events.js"
import type { Analytics } from "../../infra/analytics/recorder.js"
import { requireSessionContext } from "../_shared/session.js"
import { getPairSession } from "../_shared/pairs.js"

export const MAX_EVENTS_PER_REQUEST = 50
/** Older events are dropped: the app's queue keeps them only while it can't send. */
const MAX_EVENT_AGE_MS = 30 * 24 * 60 * 60 * 1_000
const FAILURE_REASON_PATTERN = /^[a-z][a-z0-9_]{0,63}$/

/** One app event, validated against the tracking plan. */
type ValidClientEvent = {
  [E in ClientEventName]: { id: string; event: E; occurredAt: Date; properties: ClientEventProperties[E] }
}[ClientEventName]

export interface RecordClientEventsResult {
  accepted: number
  rejected: number
}

export class ClientAnalyticsService {
  constructor(
    private readonly db: Database,
    private readonly analytics: Analytics,
  ) {}

  /**
   * Records the app's events under the signed-in user, or under the install's anonymous ID before
   * sign-in. Invalid events are dropped one by one, so one bad event doesn't lose the batch.
   */
  async record(input: { accessToken: string | null; client: ClientContext; body: unknown }): Promise<RecordClientEventsResult> {
    const rawEvents = eventsOf(input.body)
    let distinctId: string
    let pairId: string | null = null
    if (input.accessToken) {
      const context = await requireSessionContext(this.db, input.accessToken)
      distinctId = context.user.id
      pairId = (await getPairSession(this.db, context.user.id))?.id ?? null
    } else if (input.client.anonymousId) {
      distinctId = anonymousDistinctId(input.client.anonymousId)
    } else {
      throw new HttpError(400, "Sign in, or send X-Analytics-Anonymous-Id")
    }

    const now = Date.now()
    const valid = rawEvents.map((raw) => validClientEvent(raw, now)).filter((event): event is ValidClientEvent => event !== null)
    if (this.analytics.enabled && valid.length > 0) {
      await this.db.transaction(async (tx) => {
        for (const event of valid) {
          await this.analytics.track(tx, {
            event: event.event,
            id: event.id,
            distinctId,
            pairId,
            occurredAt: event.occurredAt,
            properties: event.properties,
            source: "app",
            client: input.client,
          })
        }
      })
    }
    return { accepted: valid.length, rejected: rawEvents.length - valid.length }
  }
}

function eventsOf(body: unknown): unknown[] {
  const events = isRecord(body) ? body.events : undefined
  if (!Array.isArray(events) || events.length === 0 || events.length > MAX_EVENTS_PER_REQUEST) {
    throw new HttpError(400, `events must hold 1 to ${MAX_EVENTS_PER_REQUEST} events`)
  }
  return events
}

export function validClientEvent(raw: unknown, now: number): ValidClientEvent | null {
  if (!isRecord(raw)) return null
  const id = typeof raw.id === "string" ? raw.id.trim().toLowerCase() : ""
  const occurredAt = typeof raw.occurredAt === "string" ? new Date(raw.occurredAt) : new Date(Number.NaN)
  const properties = isRecord(raw.properties) ? raw.properties : {}
  if (!isUuid(id) || Number.isNaN(occurredAt.getTime()) || now - occurredAt.getTime() > MAX_EVENT_AGE_MS) return null
  // A clock running ahead can't put an event in the future.
  const at = occurredAt.getTime() > now ? new Date(now) : occurredAt

  const event = oneOf(ClientEventNames, raw.event)
  switch (event) {
    case "mulberry_onboarding_step_viewed": {
      const step = oneOf(OnboardingSteps, properties.step)
      return step ? { id, event, occurredAt: at, properties: { step } } : null
    }
    case "mulberry_wallpaper_setup_step": {
      const surfaceType = oneOf(PresenceSurfaceTypes, properties.surface_type)
      const step = oneOf(WallpaperSetupSteps, properties.step)
      const outcome = oneOf(WallpaperSetupOutcomes, properties.outcome)
      const failureReason = properties.failure_reason
      if (!surfaceType || !step || !outcome) return null
      if (failureReason !== undefined && (typeof failureReason !== "string" || !FAILURE_REASON_PATTERN.test(failureReason))) return null
      return {
        id,
        event,
        occurredAt: at,
        properties: { surface_type: surfaceType, step, outcome, ...(failureReason ? { failure_reason: failureReason } : {}) },
      }
    }
    case "mulberry_push_opened": {
      const pushType = oneOf(PushTypes, properties.push_type)
      return pushType ? { id, event, occurredAt: at, properties: { push_type: pushType } } : null
    }
    case "mulberry_share_opened":
      return { id, event, occurredAt: at, properties: {} }
    case "mulberry_share_frame_selected": {
      const frameKey = frameKeyOf(properties.frame_key)
      return frameKey ? { id, event, occurredAt: at, properties: { frame_key: frameKey } } : null
    }
    case "mulberry_share_completed": {
      const frameKey = frameKeyOf(properties.frame_key)
      const usedRealWallpaper = booleanOf(properties.used_real_wallpaper)
      const captionEdited = booleanOf(properties.caption_edited)
      const activityType = properties.activity_type
      if (!frameKey || usedRealWallpaper === null || captionEdited === null) return null
      if (activityType !== undefined && (typeof activityType !== "string" || !ACTIVITY_TYPE_PATTERN.test(activityType))) return null
      return {
        id,
        event,
        occurredAt: at,
        properties: {
          frame_key: frameKey,
          used_real_wallpaper: usedRealWallpaper,
          caption_edited: captionEdited,
          ...(activityType ? { activity_type: activityType } : {}),
        },
      }
    }
    case null:
      return null
  }
}

/** A share frame key, as the catalog writes them: lowercase words joined by hyphens. */
const FRAME_KEY_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/
/** Where the iOS share sheet sent the image, e.g. `com.apple.UIKit.activity.Message`. */
const ACTIVITY_TYPE_PATTERN = /^[A-Za-z0-9._-]{1,128}$/

function frameKeyOf(value: unknown): string | null {
  return typeof value === "string" && value.length <= 64 && FRAME_KEY_PATTERN.test(value) ? value : null
}

/** The apps send these flags as booleans or as the strings "true" and "false". */
function booleanOf(value: unknown): boolean | null {
  if (typeof value === "boolean") return value
  if (value === "true") return true
  if (value === "false") return false
  return null
}

function oneOf<const T extends readonly string[]>(allowed: T, value: unknown): T[number] | null {
  return allowed.find((candidate) => candidate === value) ?? null
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}
