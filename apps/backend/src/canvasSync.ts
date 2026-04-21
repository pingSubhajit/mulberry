import type { WebSocket } from "@fastify/websocket"
import type { CanvasOperationEnvelope, ClientCanvasOperation } from "./domain.js"
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

    const accepted = await this.service.acceptCanvasOperationForSession(
      context.accessToken,
      context.pairSessionId,
      operation,
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

  private broadcast(pairSessionId: string, payload: unknown): void {
    const connections = this.connectionsByPairSession.get(pairSessionId)
    if (!connections) return
    connections.forEach((socket) => this.send(socket, payload))
  }

  private send(socket: WebSocket, payload: unknown): void {
    if (socket.readyState === 1) {
      socket.send(JSON.stringify(payload))
    }
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

export function isRemoteOperationFromOtherUser(
  operation: CanvasOperationEnvelope,
  userId: string,
): boolean {
  return operation.actorUserId !== userId
}
