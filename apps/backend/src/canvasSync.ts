import type { WebSocket } from "@fastify/websocket"
import type {
  CanvasOperationEnvelope,
  ClientCanvasOperation,
  ClientCanvasOperationBatch,
} from "./domain.js"
import { HttpError, MulberryService } from "./service.js"

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

interface ConnectionContext {
  accessToken: string
  pairSessionId: string
  userId: string
}

export class CanvasSyncHub {
  private readonly connectionsByPairSession = new Map<string, Set<WebSocket>>()
  private readonly contexts = new WeakMap<WebSocket, ConnectionContext>()
  private readonly pairQueues = new Map<string, Promise<void>>()

  constructor(private readonly service: MulberryService) {}

  attach(socket: WebSocket): void {
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
    const bootstrap = await this.service.bootstrapCanvasSync(
      message.accessToken,
      message.pairSessionId,
      Number(message.lastAppliedServerRevision ?? 0),
    )
    this.contexts.set(socket, {
      accessToken: message.accessToken,
      pairSessionId: bootstrap.pairSessionId,
      userId: bootstrap.userId,
    })
    const connections = this.connectionsByPairSession.get(bootstrap.pairSessionId) ?? new Set()
    connections.add(socket)
    this.connectionsByPairSession.set(bootstrap.pairSessionId, connections)

    this.send(socket, {
      type: "READY",
      pairSessionId: bootstrap.pairSessionId,
      userId: bootstrap.userId,
      latestRevision: bootstrap.latestRevision,
      missedOperations: bootstrap.missedOperations,
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

    const accepted = await this.enqueueForPair(context.pairSessionId, () =>
      this.service.acceptCanvasOperationForSession(
        context.accessToken,
        context.pairSessionId,
        operation,
      ),
    )
    this.send(socket, {
      type: "ACK",
      clientOperationId: accepted.clientOperationId,
      serverRevision: accepted.serverRevision,
      operation: accepted,
    })
    this.broadcast(context.pairSessionId, {
      type: "SERVER_OP",
      operation: accepted,
    })
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

    const accepted = await this.enqueueForPair(context.pairSessionId, () =>
      this.service.acceptCanvasOperationBatchForSession(
        context.accessToken,
        context.pairSessionId,
        {
          batchId,
          operations,
          clientCreatedAt: batch.clientCreatedAt ?? new Date().toISOString(),
        },
      ),
    )
    const ackedClientOperationIds = accepted.map((operation) => operation.clientOperationId)
    const ackedThroughRevision = accepted.at(-1)?.serverRevision ?? 0
    this.send(socket, {
      type: "ACK_BATCH",
      batchId,
      ackedClientOperationIds,
      ackedThroughRevision,
      operations: accepted,
    })
    this.broadcast(context.pairSessionId, {
      type: "SERVER_OP_BATCH",
      operations: accepted,
    })
    this.send(socket, {
      type: "FLOW_CONTROL",
      mode: operations.length >= SLOW_DOWN_OPERATION_THRESHOLD ? "SLOW_DOWN" : "NORMAL",
      maxAppendHz: operations.length >= SLOW_DOWN_OPERATION_THRESHOLD ? 15 : 30,
      reason: operations.length >= SLOW_DOWN_OPERATION_THRESHOLD ? "large_batch" : null,
    })
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

  private broadcast(pairSessionId: string, payload: unknown): void {
    const connections = this.connectionsByPairSession.get(pairSessionId)
    if (!connections) return
    connections.forEach((socket) => this.send(socket, payload))
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

export function isRemoteOperationFromOtherUser(
  operation: CanvasOperationEnvelope,
  userId: string,
): boolean {
  return operation.actorUserId !== userId
}
