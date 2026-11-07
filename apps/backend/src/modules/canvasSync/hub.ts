import type { WebSocket } from "@fastify/websocket"
import type { CanvasOperationEnvelope, ClientCanvasOperation, ClientCanvasOperationBatch } from "../../contracts/canvas.js"
import { HttpError } from "../../infra/http/HttpError.js"
import type { CanvasService } from "../canvas/service.js"
import type { CanvasSessionContext } from "../_shared/session.js"
import { operationForClient, operationsForClient } from "../canvas/featureCompat.js"

type ClientMessage =
  | {
      type: "HELLO"
      accessToken?: string
      pairSessionId?: string
      lastAppliedServerRevision?: number
    }
  | {
      type: "CLIENT_OP"
      operation?: ClientCanvasOperation
    }
  | ({
      type: "CLIENT_OP_BATCH"
    } & Partial<ClientCanvasOperationBatch>)
  | {
      type: "PING"
    }

/** A message the server sends; `type` names it. */
type ServerMessage = { type: string } & Record<string, unknown>

interface ConnectionContext {
  accessToken: string
  pairSessionId: string
  userId: string
  /** The session, user and pair from HELLO, so each operation skips looking them up again. */
  canvas: CanvasSessionContext
  /** When `canvas` was last confirmed against the database. */
  checkedAt: number
}

/**
 * How long a socket trusts its session before checking it again. A signed-out or unpaired session
 * keeps syncing for at most this long; ending a pair closes its sockets at once.
 */
const SESSION_RECHECK_MS = 30_000

export class CanvasSyncHub {
  private readonly connectionsByPairSession = new Map<string, Set<WebSocket>>()
  private readonly contexts = new WeakMap<WebSocket, ConnectionContext>()
  /** From each socket's upgrade request: legacy sockets get photo content translated. */
  private readonly photoSupport = new WeakMap<WebSocket, boolean>()
  private readonly pairQueues = new Map<string, Promise<void>>()

  constructor(private readonly service: CanvasService) {}

  attach(socket: WebSocket, supportsPhotos = false): void {
    this.photoSupport.set(socket, supportsPhotos)
    socket.on("message", (data) => {
      void this.handleMessage(socket, data.toString())
    })
    socket.on("close", () => {
      this.remove(socket)
    })
    socket.on("error", () => {
      this.remove(socket)
    })
  }

  /**
   * Closes every socket on a pair that no longer exists. Clients reconnect, find they are no longer
   * paired in bootstrap, and stop syncing.
   */
  closePairSession(pairSessionId: string): void {
    const connections = this.connectionsByPairSession.get(pairSessionId)
    if (!connections) return
    this.connectionsByPairSession.delete(pairSessionId)
    for (const socket of connections) {
      this.contexts.delete(socket)
      socket.close(1000, "pair_session_ended")
    }
  }

  private async handleMessage(socket: WebSocket, raw: string): Promise<void> {
    let message: ClientMessage
    try {
      message = JSON.parse(raw) as ClientMessage
    } catch {
      this.send(socket, { type: "ERROR", message: "Invalid JSON message" })
      return
    }

    try {
      switch (message.type) {
        case "HELLO":
          await this.handleHello(socket, message)
          break
        case "CLIENT_OP":
          await this.handleClientOperation(socket, message.operation)
          break
        case "CLIENT_OP_BATCH":
          await this.handleClientOperationBatch(socket, message)
          break
        case "PING":
          this.send(socket, { type: "PONG" })
          break
        default:
          this.send(socket, { type: "ERROR", message: "Unsupported message type" })
      }
    } catch (error) {
      const message = error instanceof HttpError ? error.message : "Unable to process sync message"
      this.send(socket, { type: "ERROR", message })
    }
  }

  private async handleHello(
    socket: WebSocket,
    message: Extract<ClientMessage, { type: "HELLO" }>,
  ): Promise<void> {
    if (!message.accessToken || !message.pairSessionId) {
      throw new HttpError(400, "HELLO requires accessToken and pairSessionId")
    }
    const canvas = await this.service.resolveCanvasSessionContext(message.accessToken, message.pairSessionId)
    const bootstrap = await this.service.bootstrapCanvasSyncForContext(
      canvas,
      Number(message.lastAppliedServerRevision ?? 0),
    )
    this.contexts.set(socket, {
      accessToken: message.accessToken,
      pairSessionId: bootstrap.pairSessionId,
      userId: bootstrap.userId,
      canvas,
      checkedAt: Date.now(),
    })
    const connections = this.connectionsByPairSession.get(bootstrap.pairSessionId) ?? new Set()
    connections.add(socket)
    this.connectionsByPairSession.set(bootstrap.pairSessionId, connections)

    this.send(socket, {
      type: "READY",
      pairSessionId: bootstrap.pairSessionId,
      userId: bootstrap.userId,
      latestRevision: bootstrap.latestRevision,
      missedOperations: operationsForClient(bootstrap.missedOperations, this.supportsPhotos(socket)),
    })
  }

  private async handleClientOperation(
    socket: WebSocket,
    operation: ClientCanvasOperation | undefined,
  ): Promise<void> {
    const context = this.contexts.get(socket)
    if (!context) {
      throw new HttpError(401, "Send HELLO before CLIENT_OP")
    }
    if (!operation) {
      throw new HttpError(400, "CLIENT_OP requires operation")
    }

    const startedAt = Date.now()
    const accepted = await this.enqueueForPair(context.pairSessionId, async () =>
      this.service.acceptCanvasOperation(await this.currentCanvasContext(context), operation),
    )
    this.send(socket, {
      type: "ACK",
      clientOperationId: accepted.clientOperationId,
      serverRevision: accepted.serverRevision,
      operation: operationForClient(accepted, this.supportsPhotos(socket)),
    })
    this.broadcast(context.pairSessionId, (supportsPhotos) => ({
      type: "SERVER_OP",
      operation: operationForClient(accepted, supportsPhotos),
    }), socket, undefined, startedAt)
  }

  private async handleClientOperationBatch(
    socket: WebSocket,
    batch: Partial<ClientCanvasOperationBatch> | undefined,
  ): Promise<void> {
    const context = this.contexts.get(socket)
    if (!context) {
      throw new HttpError(401, "Send HELLO before CLIENT_OP_BATCH")
    }
    if (!batch?.batchId || !Array.isArray(batch.operations)) {
      throw new HttpError(400, "CLIENT_OP_BATCH requires batchId and operations")
    }
    const batchId = batch.batchId
    const operations = batch.operations

    const startedAt = Date.now()
    const accepted = await this.enqueueForPair(context.pairSessionId, async () =>
      this.service.acceptCanvasOperationBatchForContext(await this.currentCanvasContext(context), {
        batchId,
        operations,
        clientCreatedAt: batch.clientCreatedAt ?? new Date().toISOString(),
      }),
    )
    const ackedClientOperationIds = accepted.map((operation) => operation.clientOperationId)
    const ackedThroughRevision = accepted.at(-1)?.serverRevision ?? 0
    this.send(socket, {
      type: "ACK_BATCH",
      batchId,
      ackedClientOperationIds,
      ackedThroughRevision,
      operations: operationsForClient(accepted, this.supportsPhotos(socket)),
    })
    this.broadcast(context.pairSessionId, (supportsPhotos) => ({
      type: "SERVER_OP_BATCH",
      operations: operationsForClient(accepted, supportsPhotos),
    }), socket, batchId, startedAt)
    this.send(socket, {
      type: "FLOW_CONTROL",
      mode: operations.length >= SLOW_DOWN_OPERATION_THRESHOLD ? "SLOW_DOWN" : "NORMAL",
      maxAppendHz: operations.length >= SLOW_DOWN_OPERATION_THRESHOLD ? 15 : 30,
      reason: operations.length >= SLOW_DOWN_OPERATION_THRESHOLD ? "large_batch" : null,
    })
  }

  /** The socket's session context, checked against the database again once it is old enough. */
  private async currentCanvasContext(context: ConnectionContext): Promise<CanvasSessionContext> {
    if (Date.now() - context.checkedAt < SESSION_RECHECK_MS) {
      return context.canvas
    }
    context.canvas = await this.service.resolveCanvasSessionContext(context.accessToken, context.pairSessionId)
    context.checkedAt = Date.now()
    return context.canvas
  }

  private async enqueueForPair<T>(
    pairSessionId: string,
    task: () => Promise<T>,
  ): Promise<T> {
    const previous = this.pairQueues.get(pairSessionId) ?? Promise.resolve()
    const next = previous.catch(() => undefined).then(task)
    const cleanup = next.then(
      () => undefined,
      () => undefined,
    )
    this.pairQueues.set(pairSessionId, cleanup)
    cleanup.finally(() => {
      if (this.pairQueues.get(pairSessionId) === cleanup) {
        this.pairQueues.delete(pairSessionId)
      }
    })
    return next
  }

  /** Builds the message per recipient, as legacy sockets get photo content translated. */
  private broadcast(
    pairSessionId: string,
    payloadFor: (supportsPhotos: boolean) => ServerMessage,
    excludedSocket?: WebSocket,
    batchId?: string,
    startedAt?: number,
  ): void {
    const connections = this.connectionsByPairSession.get(pairSessionId)
    if (!connections) return
    const payloads = new Map<boolean, ServerMessage>()
    const payloadForSocket = (socket: WebSocket): ServerMessage => {
      const supportsPhotos = this.supportsPhotos(socket)
      const payload = payloads.get(supportsPhotos) ?? payloadFor(supportsPhotos)
      payloads.set(supportsPhotos, payload)
      return payload
    }
    let recipientCount = 0
    connections.forEach((socket) => {
      if (socket === excludedSocket) {
        return
      }
      if (this.send(socket, payloadForSocket(socket))) {
        recipientCount += 1
      }
    })
    const payloadType = (payloads.values().next().value ?? payloadFor(true)).type
    console.info("[sync] broadcast", {
      pairSessionId,
      payloadType,
      batchId: batchId ?? null,
      recipientCount,
      excludedOrigin: excludedSocket != null,
      // From the message's arrival, including any wait behind the pair's earlier messages.
      elapsedMs: startedAt === undefined ? null : Date.now() - startedAt,
    })
  }

  private supportsPhotos(socket: WebSocket): boolean {
    return this.photoSupport.get(socket) ?? false
  }

  private send(socket: WebSocket, payload: unknown): boolean {
    if (socket.readyState !== 1) {
      return false
    }
    if (socket.bufferedAmount > MAX_SOCKET_BUFFERED_BYTES) {
      socket.send(JSON.stringify({
        type: "FLOW_CONTROL",
        mode: "SLOW_DOWN",
        maxAppendHz: 10,
        reason: "socket_backpressure",
      }))
      socket.close(1013, "socket_backpressure")
      return false
    }
    socket.send(JSON.stringify(payload))
    return true
  }

  private remove(socket: WebSocket): void {
    const context = this.contexts.get(socket)
    if (!context) return
    this.contexts.delete(socket)
    const connections = this.connectionsByPairSession.get(context.pairSessionId)
    connections?.delete(socket)
    if (connections?.size === 0) {
      this.connectionsByPairSession.delete(context.pairSessionId)
    }
  }
}

const SLOW_DOWN_OPERATION_THRESHOLD = 48
const MAX_SOCKET_BUFFERED_BYTES = 1_048_576

export function isRemoteOperationFromOtherUser(operation: CanvasOperationEnvelope, userId: string): boolean {
  return operation.actorUserId !== userId
}

