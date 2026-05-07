import { randomUUID } from "node:crypto"
import type { Database } from "../../infra/db/database.js"
import { HttpError } from "../../infra/http/HttpError.js"
import type {
  CanvasOperationEnvelope,
  CanvasOpsResponse,
  CanvasSnapshotResponse,
  CanvasSyncBootstrap,
  ClientCanvasOperation,
  ClientCanvasOperationBatch,
} from "../../contracts/canvas.js"
import type { CanvasOperationRecord } from "../../contracts/dbRecords.js"
import type { PushDispatchService } from "../../infra/push/dispatchService.js"
import type { AnalyticsClient } from "../../infra/analytics/analytics.js"
import { requireSessionContext, type CanvasSessionContext } from "../_shared/session.js"
import { getPairSession } from "../_shared/pairs.js"
import { dateOnly, validDateOrNow } from "../_shared/dates.js"
import type { StreakService } from "../streak/service.js"

interface MaterializedStroke {
  id: string
  colorArgb: number
  width: number
  createdAt: number
  points: Array<{ x: number; y: number }>
  finished: boolean
}

interface MaterializedTextElement {
  id: string
  text: string
  createdAt: number
  center: { x: number; y: number }
  rotationRad: number
  scale: number
  boxWidth: number
  colorArgb: number
  backgroundPillEnabled: boolean
  font: string
  alignment: string
}

interface MaterializedStickerElement {
  kind: "STICKER"
  id: string
  createdAt: number
  center: { x: number; y: number }
  rotationRad: number
  scale: number
  packKey: string
  packVersion: number
  stickerId: string
}

interface MaterializedCanvasTextElement extends MaterializedTextElement {
  kind: "TEXT"
}

type MaterializedCanvasElement = MaterializedCanvasTextElement | MaterializedStickerElement

export class CanvasService {
  constructor(
    private readonly db: Database,
    private readonly streakService: StreakService,
    private readonly pushDispatchService?: PushDispatchService,
    private readonly analytics?: AnalyticsClient,
  ) {}

  async bootstrapCanvasSync(
    accessToken: string,
    pairSessionId: string,
    lastAppliedRevision: number,
  ): Promise<CanvasSyncBootstrap> {
    const context = await this.requireCanvasSessionContext(accessToken, pairSessionId)
    const missedOperations = await this.listCanvasOperationsForPair(
      context.pairSession.id,
      lastAppliedRevision,
    )
    return {
      pairSessionId: context.pairSession.id,
      userId: context.user.id,
      latestRevision: missedOperations.at(-1)?.serverRevision ??
        (await this.getLatestCanvasRevision(context.pairSession.id)),
      missedOperations,
    }
  }

  async acceptCanvasOperationForSession(
    accessToken: string,
    pairSessionId: string,
    operation: ClientCanvasOperation,
  ): Promise<CanvasOperationEnvelope> {
    const context = await this.requireCanvasSessionContext(accessToken, pairSessionId)
    const accepted = await this.acceptCanvasOperationBatch(context, [operation])
    return accepted[0]
  }

  async acceptCanvasOperationBatchForSession(
    accessToken: string,
    pairSessionId: string,
    batch: ClientCanvasOperationBatch,
  ): Promise<CanvasOperationEnvelope[]> {
    const context = await this.requireCanvasSessionContext(accessToken, pairSessionId)
    if (!batch.batchId.trim()) {
      throw new HttpError(400, "batchId is required")
    }
    if (!Array.isArray(batch.operations) || batch.operations.length === 0) {
      throw new HttpError(400, "CLIENT_OP_BATCH requires operations")
    }
    if (batch.operations.length > 128) {
      throw new HttpError(413, "CLIENT_OP_BATCH is too large")
    }

    return this.acceptCanvasOperationBatch(context, batch.operations)
  }

  async acceptCanvasOperationBatchForAuthenticatedPair(
    accessToken: string,
    batch: ClientCanvasOperationBatch,
  ): Promise<CanvasOperationEnvelope[]> {
    const context = await this.requireDefaultCanvasSessionContext(accessToken)
    if (!batch.batchId.trim()) {
      throw new HttpError(400, "batchId is required")
    }
    if (!Array.isArray(batch.operations) || batch.operations.length === 0) {
      throw new HttpError(400, "CLIENT_OP_BATCH requires operations")
    }
    if (batch.operations.length > 128) {
      throw new HttpError(413, "CLIENT_OP_BATCH is too large")
    }

    return this.acceptCanvasOperationBatch(context, batch.operations)
  }

  async listCanvasOperations(accessToken: string, afterRevision: number): Promise<CanvasOpsResponse> {
    const context = await this.requireDefaultCanvasSessionContext(accessToken)
    return {
      operations: await this.listCanvasOperationsForPair(
        context.pairSession.id,
        afterRevision,
      ),
    }
  }

  async getCanvasSnapshot(accessToken: string): Promise<CanvasSnapshotResponse> {
    const context = await this.requireDefaultCanvasSessionContext(accessToken)
    const row = await this.getOrCreateCanvasSnapshot(context.pairSession.id)
    const snapshotRevision = Number(row.revision)
    return {
      pairSessionId: context.pairSession.id,
      snapshotRevision,
      latestRevision: Number(row.latest_revision),
      revision: snapshotRevision,
      snapshot: row.snapshot_json,
      updatedAt: row.updated_at ? new Date(row.updated_at).toISOString() : null,
    }
  }

  async acceptCanvasOperation(context: CanvasSessionContext, operation: ClientCanvasOperation): Promise<CanvasOperationEnvelope> {
    const accepted = await this.acceptCanvasOperationBatch(context, [operation])
    return accepted[0]
  }

  private async acceptCanvasOperationBatch(
    context: CanvasSessionContext,
    operations: ClientCanvasOperation[],
  ): Promise<CanvasOperationEnvelope[]> {
    operations.forEach((operation) => {
      if (!operation.clientOperationId.trim()) {
        throw new HttpError(400, "clientOperationId is required")
      }
      if (!operation.type) {
        throw new HttpError(400, "operation type is required")
      }
    })

    const {
      accepted,
      latestRevision,
      snapshotRevision,
      shouldPushCanvasUpdate,
      shouldEnqueueCanvasNudge,
      shouldRecordUserDrew,
      durableCounts,
      firstCanvasAction,
    } = await this.db.transaction(
      async (tx) => {
        const snapshotRow = await this.getOrCreateCanvasSnapshot(context.pairSession.id, tx, true)
        let latestRevision = Number(snapshotRow.latest_revision)
        let snapshotRevision = Number(snapshotRow.revision)
        const initialSnapshotRevision = snapshotRevision
        const snapshot = normalizeCanvasSnapshot(snapshotRow.snapshot_json)
        const acceptedRecords: CanvasOperationRecord[] = []
        let snapshotChanged = false
        let shouldPushCanvasUpdate = false
        let shouldEnqueueCanvasNudge = false
        let shouldRecordUserDrew = false
        const durableCounts: Record<string, number> = {}
        let durableTotal = 0
        let firstCanvasAction: { at: Date; type: string } | null = null

        for (const operation of operations) {
          const duplicate = await tx.query<CanvasOperationRecord>(
            `
            SELECT id, pair_session_id, server_revision, client_operation_id, actor_user_id,
              type, stroke_id, payload_json, client_created_at, created_at
            FROM canvas_operations
            WHERE pair_session_id = $1 AND actor_user_id = $2 AND client_operation_id = $3
            LIMIT 1
            `,
            [context.pairSession.id, context.user.id, operation.clientOperationId],
          )
          if (duplicate.rows[0]) {
            acceptedRecords.push(duplicate.rows[0])
            latestRevision = Math.max(latestRevision, Number(duplicate.rows[0].server_revision))
            continue
          }

          latestRevision += 1
          const id = randomUUID()
          const clientCreatedAt = validDateOrNow(operation.clientCreatedAt)
          const rows = await tx.query<CanvasOperationRecord>(
            `
            INSERT INTO canvas_operations (
              id,
              pair_session_id,
              server_revision,
              client_operation_id,
              actor_user_id,
              type,
              stroke_id,
              payload_json,
              client_created_at
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8::jsonb, $9)
            RETURNING id, pair_session_id, server_revision, client_operation_id, actor_user_id,
              type, stroke_id, payload_json, client_created_at, created_at
            `,
            [
              id,
              context.pairSession.id,
              latestRevision,
              operation.clientOperationId,
              context.user.id,
              operation.type,
              operation.strokeId ?? null,
              JSON.stringify(operation.payload ?? {}),
              clientCreatedAt.toISOString(),
            ],
          )
          const acceptedRecord = rows.rows[0]
          acceptedRecords.push(acceptedRecord)
          if (this.shouldRecordPairActivityForOperation(acceptedRecord.type)) {
            await this.streakService.recordPairActivityDay(tx, context.pairSession.id, operation.clientLocalDate)
            shouldEnqueueCanvasNudge = true
            shouldRecordUserDrew = true
          }
          const materialized = await this.materializeDurableOperation(
            tx,
            context.pairSession.id,
            acceptedRecord,
            snapshot,
          )
          if (materialized) {
            snapshotRevision = Number(acceptedRecord.server_revision)
            snapshotChanged = true
            shouldPushCanvasUpdate = true
            durableTotal += 1
            durableCounts[acceptedRecord.type] = (durableCounts[acceptedRecord.type] ?? 0) + 1
            if (initialSnapshotRevision === 0 && !firstCanvasAction) {
              firstCanvasAction = {
                at: new Date(acceptedRecord.created_at),
                type: acceptedRecord.type,
              }
            }
          }
        }

        await tx.query(
          `
          UPDATE canvas_snapshots
          SET latest_revision = $2,
            revision = $3,
            snapshot_json = CASE WHEN $4 THEN $5::jsonb ELSE snapshot_json END,
            updated_at = CASE WHEN $4 THEN NOW() ELSE updated_at END
          WHERE pair_session_id = $1
          `,
          [
            context.pairSession.id,
            latestRevision,
            snapshotRevision,
            snapshotChanged,
            JSON.stringify(snapshot),
          ],
        )

        return {
          accepted: acceptedRecords.map((row) => this.canvasOperationToEnvelope(row)),
          latestRevision,
          snapshotRevision,
          shouldPushCanvasUpdate,
          shouldEnqueueCanvasNudge,
          shouldRecordUserDrew,
          durableCounts: { ...durableCounts, _TOTAL: durableTotal },
          firstCanvasAction,
        }
      },
    )

    const durableCountsByType = durableCounts as unknown as Record<string, number>

    if (this.analytics?.enabled && (durableCountsByType._TOTAL ?? 0) > 0) {
      const strokeMode = context.pairSession.canvas_stroke_render_mode
      const activityRecordedAt = firstCanvasAction?.at ?? new Date()
      const activity = await this.recordPairProductActivity({
        pairSessionId: context.pairSession.id,
        occurredAt: activityRecordedAt,
      })

      this.analytics.capture({
        distinctId: context.pairSession.id,
        event: "mulberry_canvas_batch_ingested",
        properties: {
          latest_revision: latestRevision,
          snapshot_revision: snapshotRevision,
          durable_ops_total: durableCountsByType._TOTAL ?? 0,
          strokes_finished_count: durableCountsByType["FINISH_STROKE"] ?? 0,
          strokes_deleted_count: durableCountsByType["DELETE_STROKE"] ?? 0,
          canvas_cleared_count: durableCountsByType["CLEAR_CANVAS"] ?? 0,
          text_added_count: durableCountsByType["ADD_TEXT_ELEMENT"] ?? 0,
          text_updated_count: durableCountsByType["UPDATE_TEXT_ELEMENT"] ?? 0,
          text_deleted_count: durableCountsByType["DELETE_TEXT_ELEMENT"] ?? 0,
          sticker_added_count: durableCountsByType["ADD_STICKER_ELEMENT"] ?? 0,
          sticker_updated_count: durableCountsByType["UPDATE_STICKER_ELEMENT"] ?? 0,
          sticker_deleted_count: durableCountsByType["DELETE_STICKER_ELEMENT"] ?? 0,
          canvas_stroke_render_mode: strokeMode,
        },
      })
      if (activity.insertedDay) {
        this.analytics.capture({
          distinctId: context.pairSession.id,
          event: "mulberry_pair_active_day",
          timestamp: activityRecordedAt,
          properties: {
            source: "canvas",
            activity_day: activity.activityDay,
            canvas_stroke_render_mode: strokeMode,
          },
        })
      }
      if (activity.insertedWeek) {
        this.analytics.capture({
          distinctId: context.pairSession.id,
          event: "mulberry_pair_active_week",
          timestamp: activityRecordedAt,
          properties: {
            source: "canvas",
            week_start_day: activity.weekStartDay,
            canvas_stroke_render_mode: strokeMode,
          },
        })
      }
      if (firstCanvasAction) {
        this.analytics.capture({
          distinctId: context.pairSession.id,
          event: "mulberry_canvas_first_action",
          timestamp: firstCanvasAction.at,
          properties: {
            first_action_type: firstCanvasAction.type,
            canvas_stroke_render_mode: strokeMode,
          },
        })
      }
    }

    if (shouldPushCanvasUpdate) {
      this.pushDispatchService?.enqueueCanvasUpdated(
        context.pairSession.id,
        context.user.id,
        latestRevision,
        snapshotRevision,
      )
    }

    if (shouldEnqueueCanvasNudge) {
      this.pushDispatchService?.enqueueCanvasNudge(
        context.pairSession.id,
        context.user.id,
        latestRevision,
      )
    }

    if (shouldRecordUserDrew) {
      this.pushDispatchService?.recordUserDrew(context.pairSession.id, context.user.id)
    }

    return accepted
  }

  private async recordPairProductActivity(input: {
    pairSessionId: string
    occurredAt: Date
  }): Promise<{ insertedDay: boolean; insertedWeek: boolean; activityDay: string; weekStartDay: string }> {
    const activityDay = dateOnly(input.occurredAt)
    const weekStartDay = weekStartFromDay(activityDay)

    const dayRows = await this.db.query<{ inserted: boolean }>(
      `
      INSERT INTO pair_product_activity_days (pair_session_id, activity_day, last_seen_at)
      VALUES ($1, $2::date, NOW())
      ON CONFLICT (pair_session_id, activity_day) DO UPDATE SET
        last_seen_at = NOW()
      RETURNING (xmax = 0) AS inserted
      `,
      [input.pairSessionId, activityDay],
    )

    const weekRows = await this.db.query<{ inserted: boolean }>(
      `
      INSERT INTO pair_product_activity_weeks (pair_session_id, week_start_day, last_seen_at)
      VALUES ($1, $2::date, NOW())
      ON CONFLICT (pair_session_id, week_start_day) DO UPDATE SET
        last_seen_at = NOW()
      RETURNING (xmax = 0) AS inserted
      `,
      [input.pairSessionId, weekStartDay],
    )

    return {
      insertedDay: Boolean(dayRows.rows[0]?.inserted),
      insertedWeek: Boolean(weekRows.rows[0]?.inserted),
      activityDay,
      weekStartDay,
    }
  }

  private async requireDefaultCanvasSessionContext(accessToken: string): Promise<CanvasSessionContext> {
    const context = await requireSessionContext(this.db, accessToken)
    const pairSession = await getPairSession(this.db, context.user.id)
    if (!pairSession) {
      throw new HttpError(403, "User is not paired")
    }
    return { ...context, pairSession }
  }

  private async requireCanvasSessionContext(accessToken: string, pairSessionId: string): Promise<CanvasSessionContext> {
    const context = await requireSessionContext(this.db, accessToken)
    const pairSession = await getPairSession(this.db, context.user.id)
    if (!pairSession || pairSession.id !== pairSessionId) {
      throw new HttpError(403, "User is not in this pair session")
    }
    return { ...context, pairSession }
  }

  private shouldRecordPairActivityForOperation(type: string): boolean {
    return type === "FINISH_STROKE" ||
      type === "ADD_TEXT_ELEMENT" ||
      type === "UPDATE_TEXT_ELEMENT" ||
      type === "ADD_STICKER_ELEMENT" ||
      type === "UPDATE_STICKER_ELEMENT"
  }

  private async getLatestCanvasRevision(pairSessionId: string): Promise<number> {
    const rows = await this.db.query<{ latest_revision: string | number }>(
      `
      SELECT latest_revision
      FROM canvas_snapshots
      WHERE pair_session_id = $1
      `,
      [pairSessionId],
    )
    if (rows.rows[0]) {
      return Number(rows.rows[0].latest_revision)
    }
    const fallback = await this.db.query<{ revision: string | number }>(
      `
      SELECT COALESCE(MAX(server_revision), 0) AS revision
      FROM canvas_operations
      WHERE pair_session_id = $1
      `,
      [pairSessionId],
    )
    return Number(fallback.rows[0]?.revision ?? 0)
  }

  private async listCanvasOperationsForPair(pairSessionId: string, afterRevision: number): Promise<CanvasOperationEnvelope[]> {
    const rows = await this.db.query<CanvasOperationRecord>(
      `
      SELECT id, pair_session_id, server_revision, client_operation_id, actor_user_id,
        type, stroke_id, payload_json, client_created_at, created_at
      FROM canvas_operations
      WHERE pair_session_id = $1 AND server_revision > $2
      ORDER BY server_revision ASC
      `,
      [pairSessionId, Math.max(0, Math.floor(afterRevision))],
    )
    return rows.rows.map((row) => this.canvasOperationToEnvelope(row))
  }

  private async getOrCreateCanvasSnapshot(pairSessionId: string): Promise<{
    revision: string | number
    latest_revision: string | number
    snapshot_json: unknown
    updated_at: Date | string
  }>
  private async getOrCreateCanvasSnapshot(
    pairSessionId: string,
    db: Pick<Database, "query">,
    lockForUpdate: true,
  ): Promise<{
    revision: string | number
    latest_revision: string | number
    snapshot_json: unknown
    updated_at: Date | string
  }>
  private async getOrCreateCanvasSnapshot(
    pairSessionId: string,
    db: Pick<Database, "query"> = this.db,
    lockForUpdate = false,
  ): Promise<{
    revision: string | number
    latest_revision: string | number
    snapshot_json: unknown
    updated_at: Date | string
  }> {
    await db.query(
      `
      INSERT INTO canvas_snapshots (pair_session_id)
      VALUES ($1)
      ON CONFLICT (pair_session_id) DO NOTHING
      `,
      [pairSessionId],
    )
    const rows = await db.query<{
      revision: string | number
      latest_revision: string | number
      snapshot_json: unknown
      updated_at: Date | string
    }>(
      `
      SELECT revision, latest_revision, snapshot_json, updated_at
      FROM canvas_snapshots
      WHERE pair_session_id = $1
      ${lockForUpdate ? "FOR UPDATE" : ""}
      `,
      [pairSessionId],
    )
    return rows.rows[0]
  }

  private async materializeDurableOperation(
    db: Pick<Database, "query">,
    pairSessionId: string,
    operation: CanvasOperationRecord,
    snapshot: { strokes: MaterializedStroke[]; elements: MaterializedCanvasElement[]; textElements: MaterializedTextElement[] },
  ): Promise<boolean> {
    switch (operation.type) {
      case "FINISH_STROKE": {
        const strokeId = operation.stroke_id
        if (!strokeId) return false
        const stroke = await this.reconstructFinishedStroke(db, pairSessionId, strokeId, Number(operation.server_revision))
        if (!stroke) return false
        snapshot.strokes = snapshot.strokes.filter((stroke) => stroke.id !== strokeId)
        snapshot.strokes.push(stroke)
        return true
      }
      case "DELETE_STROKE":
        snapshot.strokes = snapshot.strokes.filter((stroke) => stroke.id !== operation.stroke_id)
        return true
      case "CLEAR_CANVAS": {
        snapshot.strokes = []
        snapshot.textElements = []
        snapshot.elements = []
        return true
      }
      case "ADD_TEXT_ELEMENT":
      case "UPDATE_TEXT_ELEMENT": {
        const payload = normalizeTextElement(operation.payload_json)
        if (!payload) return false
        snapshot.textElements = snapshot.textElements.filter((element) => element.id !== payload.id)
        snapshot.textElements.push(payload)
        snapshot.elements = snapshot.elements.filter((element) => element.id !== payload.id)
        snapshot.elements.push({ kind: "TEXT", ...payload })
        return true
      }
      case "DELETE_TEXT_ELEMENT": {
        if (!operation.stroke_id) return false
        snapshot.textElements = snapshot.textElements.filter((element) => element.id !== operation.stroke_id)
        snapshot.elements = snapshot.elements.filter((element) => element.id !== operation.stroke_id)
        return true
      }
      case "ADD_STICKER_ELEMENT":
      case "UPDATE_STICKER_ELEMENT": {
        const payload = normalizeStickerElement(operation.payload_json)
        if (!payload) return false
        snapshot.elements = snapshot.elements.filter((element) => element.id !== payload.id)
        snapshot.elements.push(payload)
        return true
      }
      case "DELETE_STICKER_ELEMENT": {
        if (!operation.stroke_id) return false
        snapshot.elements = snapshot.elements.filter((element) => element.id !== operation.stroke_id)
        return true
      }
      default:
        return false
    }
  }

  private async reconstructFinishedStroke(
    db: Pick<Database, "query">,
    pairSessionId: string,
    strokeId: string,
    throughRevision: number,
  ): Promise<MaterializedStroke | null> {
    const rows = await db.query<CanvasOperationRecord>(
      `
      SELECT id, pair_session_id, server_revision, client_operation_id, actor_user_id,
        type, stroke_id, payload_json, client_created_at, created_at
      FROM canvas_operations
      WHERE pair_session_id = $1
        AND stroke_id = $2
        AND server_revision <= $3
        AND type IN ('ADD_STROKE', 'APPEND_POINTS')
      ORDER BY server_revision ASC
      `,
      [pairSessionId, strokeId, throughRevision],
    )
    let stroke: MaterializedStroke | null = null
    for (const operation of rows.rows) {
      if (operation.type === "ADD_STROKE") {
        const payload = operation.payload_json as Partial<MaterializedStroke> & {
          firstPoint?: { x: number; y: number }
        }
        const firstPoint = normalizeCanvasPoint(payload.firstPoint)
        if (!firstPoint) continue
        stroke = {
          id: strokeId,
          colorArgb: Number(payload.colorArgb ?? 0xff111111),
          width: Number(payload.width ?? 8),
          createdAt: Number(payload.createdAt ?? Date.now()),
          points: [firstPoint],
          finished: true,
        }
      } else if (operation.type === "APPEND_POINTS" && stroke) {
        const payload = operation.payload_json as { points?: Array<{ x: number; y: number }> }
        if (Array.isArray(payload.points)) {
          stroke.points.push(
            ...payload.points.map(normalizeCanvasPoint).filter((point): point is { x: number; y: number } => point !== null),
          )
        }
      }
    }
    return stroke
  }

  private canvasOperationToEnvelope(row: CanvasOperationRecord): CanvasOperationEnvelope {
    return {
      clientOperationId: row.client_operation_id,
      actorUserId: row.actor_user_id,
      pairSessionId: row.pair_session_id,
      type: row.type,
      strokeId: row.stroke_id,
      payload: row.payload_json,
      clientCreatedAt: new Date(row.client_created_at).toISOString(),
      serverRevision: Number(row.server_revision),
      createdAt: new Date(row.created_at).toISOString(),
    }
  }
}

function normalizeCanvasSnapshot(raw: unknown): {
  strokes: MaterializedStroke[]
  elements: MaterializedCanvasElement[]
  textElements: MaterializedTextElement[]
} {
  if (
    typeof raw === "object" &&
    raw !== null &&
    "strokes" in raw &&
    Array.isArray((raw as { strokes: unknown }).strokes)
  ) {
    const candidate = raw as { strokes: unknown[]; textElements?: unknown; elements?: unknown }
    const textElements = Array.isArray(candidate.textElements)
      ? candidate.textElements
        .map(normalizeTextElement)
        .filter((element): element is MaterializedTextElement => element !== null)
      : []
    const elements: MaterializedCanvasElement[] = Array.isArray(candidate.elements)
      ? candidate.elements
        .map(normalizeCanvasElement)
        .filter((element): element is MaterializedCanvasElement => element !== null)
      : textElements.map((element): MaterializedCanvasTextElement => ({ kind: "TEXT", ...element }))

    return {
      strokes: candidate.strokes
        .map(normalizeStroke)
        .filter((stroke): stroke is MaterializedStroke => stroke !== null),
      elements,
      textElements: textElements.length > 0
        ? textElements
        : elements
          .filter((element): element is MaterializedCanvasTextElement => element.kind === "TEXT")
          .map(({ kind: _kind, ...rest }) => rest),
    }
  }
  return { strokes: [], elements: [], textElements: [] }
}

function weekStartFromDay(day: string): string {
  const date = new Date(`${day}T00:00:00.000Z`)
  const dayOfWeek = date.getUTCDay() // Sun=0..Sat=6
  const delta = (dayOfWeek + 6) % 7 // shift to Monday=0..Sunday=6
  date.setUTCDate(date.getUTCDate() - delta)
  return dateOnly(date)
}

function normalizeCanvasPoint(raw: unknown): { x: number; y: number } | null {
  if (typeof raw !== "object" || raw === null) return null
  const item = raw as { x?: unknown; y?: unknown }
  return { x: Number(item.x ?? 0), y: Number(item.y ?? 0) }
}

function normalizeStroke(raw: unknown): MaterializedStroke | null {
  if (typeof raw !== "object" || raw === null || !("id" in raw)) return null
  const candidate = raw as {
    id?: unknown
    colorArgb?: unknown
    width?: unknown
    createdAt?: unknown
    points?: unknown
    finished?: unknown
  }
  if (typeof candidate.id !== "string" || !Array.isArray(candidate.points)) return null
  return {
    id: candidate.id,
    colorArgb: Number(candidate.colorArgb ?? 0xff111111),
    width: Number(candidate.width ?? 8),
    createdAt: Number(candidate.createdAt ?? Date.now()),
    points: candidate.points
      .map((point) => {
        if (typeof point !== "object" || point === null) return null
        const item = point as { x?: unknown; y?: unknown }
        return { x: Number(item.x ?? 0), y: Number(item.y ?? 0) }
      })
      .filter((point): point is { x: number; y: number } => point !== null),
    finished: Boolean(candidate.finished),
  }
}

function normalizeTextElement(raw: unknown): MaterializedTextElement | null {
  if (typeof raw !== "object" || raw === null || !("id" in raw)) return null
  const candidate = raw as {
    id?: unknown
    text?: unknown
    createdAt?: unknown
    center?: unknown
    rotationRad?: unknown
    scale?: unknown
    boxWidth?: unknown
    colorArgb?: unknown
    backgroundPillEnabled?: unknown
    font?: unknown
    alignment?: unknown
  }
  if (typeof candidate.id !== "string") return null
  const center = normalizeCanvasPoint(candidate.center)
  if (!center) return null
  return {
    id: candidate.id,
    text: typeof candidate.text === "string" ? candidate.text : "",
    createdAt: Number(candidate.createdAt ?? Date.now()),
    center,
    rotationRad: Number(candidate.rotationRad ?? 0),
    scale: Number(candidate.scale ?? 1),
    boxWidth: Number(candidate.boxWidth ?? 0.7),
    colorArgb: Number(candidate.colorArgb ?? 0xff111111),
    backgroundPillEnabled: Boolean(candidate.backgroundPillEnabled),
    font: typeof candidate.font === "string" ? candidate.font : "POPPINS",
    alignment: typeof candidate.alignment === "string" ? candidate.alignment : "CENTER",
  }
}

function normalizeStickerElement(raw: unknown): MaterializedStickerElement | null {
  if (typeof raw !== "object" || raw === null || !("id" in raw)) return null
  const candidate = raw as {
    id?: unknown
    createdAt?: unknown
    center?: unknown
    rotationRad?: unknown
    scale?: unknown
    packKey?: unknown
    packVersion?: unknown
    stickerId?: unknown
  }
  if (typeof candidate.id !== "string") return null
  const center = normalizeCanvasPoint(candidate.center)
  if (!center) return null
  const packKey = typeof candidate.packKey === "string" ? candidate.packKey.trim() : ""
  const stickerId = typeof candidate.stickerId === "string" ? candidate.stickerId.trim() : ""
  const packVersion = Number(candidate.packVersion ?? 0)
  if (!packKey || !stickerId || !Number.isFinite(packVersion) || packVersion <= 0) return null

  return {
    kind: "STICKER",
    id: candidate.id,
    createdAt: Number(candidate.createdAt ?? Date.now()),
    center,
    rotationRad: Number(candidate.rotationRad ?? 0),
    scale: Number(candidate.scale ?? 0.22),
    packKey,
    packVersion,
    stickerId,
  }
}

function normalizeCanvasElement(raw: unknown): MaterializedCanvasElement | null {
  if (typeof raw !== "object" || raw === null || !("kind" in raw)) return null
  const candidate = raw as { kind?: unknown }
  if (candidate.kind === "TEXT") {
    const normalized = normalizeTextElement(raw)
    return normalized ? { kind: "TEXT", ...normalized } : null
  }
  if (candidate.kind === "STICKER") {
    return normalizeStickerElement(raw)
  }
  return null
}
