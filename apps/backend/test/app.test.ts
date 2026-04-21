import { newDb } from "pg-mem"
import type { FastifyInstance } from "fastify"
import { afterEach, beforeEach, describe, expect, it } from "vitest"
import { createApp } from "../src/app.js"
import { runMigrations, type Database } from "../src/db.js"

describe("Mulberry backend", () => {
  let app: FastifyInstance
  let db: Database

  beforeEach(async () => {
    const memoryDb = newDb({
      autoCreateForeignKeyIndices: true,
    })
    const adapter = memoryDb.adapters.createPg()
    const pool = new adapter.Pool()
    await runMigrations(pool)
    db = {
      query: pool.query.bind(pool),
      end: async () => {
        await pool.end()
      },
    }
    app = await createApp({
      db,
      config: {
        port: 8080,
        databaseUrl: "postgres://unused",
        googleClientId: "",
        allowDevGoogleTokens: true,
      },
    })
    await app.ready()
  })

  afterEach(async () => {
    await app.close()
  })

  it("issues an app session from a development Google token", async () => {
    const response = await app.inject({
      method: "POST",
      url: "/auth/google",
      payload: {
        idToken: "dev-google:subhajit@elaris.dev:Subhajit",
      },
    })

    expect(response.statusCode).toBe(200)
    const body = response.json()
    expect(body.accessToken).toBeTruthy()
    expect(body.refreshToken).toBeTruthy()
    expect(body.bootstrapState.authStatus).toBe("SIGNED_IN")
    expect(body.bootstrapState.onboardingCompleted).toBe(false)
  })

  it("rotates the session on refresh", async () => {
    const auth = await signIn("subhajit@elaris.dev", "Subhajit")

    const response = await app.inject({
      method: "POST",
      url: "/auth/refresh",
      payload: {
        refreshToken: auth.refreshToken,
      },
    })

    expect(response.statusCode).toBe(200)
    const body = response.json()
    expect(body.accessToken).not.toBe(auth.accessToken)
    expect(body.refreshToken).not.toBe(auth.refreshToken)
  })

  it("persists onboarding profile and requires all fields", async () => {
    const auth = await signIn("subhajit@elaris.dev", "Subhajit")

    const invalid = await app.inject({
      method: "PUT",
      url: "/me/profile",
      headers: bearer(auth.accessToken),
      payload: {
        displayName: "Subhajit",
        partnerDisplayName: "",
        anniversaryDate: "2026-01-01",
      },
    })

    expect(invalid.statusCode).toBe(400)

    const valid = await app.inject({
      method: "PUT",
      url: "/me/profile",
      headers: bearer(auth.accessToken),
      payload: {
        displayName: "Subhajit",
        partnerDisplayName: "Ankita",
        anniversaryDate: "2026-01-01",
      },
    })

    expect(valid.statusCode).toBe(200)
    const body = valid.json()
    expect(body.onboardingCompleted).toBe(true)
    expect(body.userDisplayName).toBe("Subhajit")
    expect(body.partnerDisplayName).toBe("Ankita")
  })

  it("creates a six digit invite code", async () => {
    const auth = await signIn("subhajit@elaris.dev", "Subhajit")
    await completeProfile(auth.accessToken, "Subhajit", "Ankita", "2026-01-01")

    const response = await app.inject({
      method: "POST",
      url: "/invites",
      headers: bearer(auth.accessToken),
    })

    expect(response.statusCode).toBe(200)
    const body = response.json()
    expect(body.code).toMatch(/^\d{6}$/)
    expect(body.expiresAt).toBeTruthy()
  })

  it("redeems and accepts an invite into a paired session", async () => {
    const inviter = await signIn("subhajit@elaris.dev", "Subhajit")
    await completeProfile(inviter.accessToken, "Subhajit", "Ankita", "2026-01-01")
    const createInvite = await app.inject({
      method: "POST",
      url: "/invites",
      headers: bearer(inviter.accessToken),
    })
    const invite = createInvite.json()

    const recipient = await signIn("ankita@elaris.dev", "Ankita")
    await completeProfile(recipient.accessToken, "Ankita", "Subhajit", "2026-01-01")
    const redeem = await app.inject({
      method: "POST",
      url: "/invites/redeem",
      headers: bearer(recipient.accessToken),
      payload: {
        code: invite.code,
      },
    })

    expect(redeem.statusCode).toBe(200)
    expect(redeem.json().status).toBe("REDEEMED")

    const accept = await app.inject({
      method: "POST",
      url: `/invites/${invite.inviteId}/accept`,
      headers: bearer(recipient.accessToken),
    })

    expect(accept.statusCode).toBe(200)
    expect(accept.json().pairSessionId).toBeTruthy()
    expect(accept.json().bootstrapState.pairingStatus).toBe("PAIRED")
  })

  it("hydrates invitee onboarding from inviter profile when code is redeemed during onboarding", async () => {
    const inviter = await signIn("subhajit@elaris.dev", "Subhajit")
    await completeProfile(inviter.accessToken, "Subhajit", "Ankita", "2026-01-01")
    const createInvite = await app.inject({
      method: "POST",
      url: "/invites",
      headers: bearer(inviter.accessToken),
    })
    const invite = createInvite.json()

    const recipient = await signIn("ankita@elaris.dev", "Ankita Google")
    const redeem = await app.inject({
      method: "POST",
      url: "/invites/redeem",
      headers: bearer(recipient.accessToken),
      payload: {
        code: invite.code,
      },
    })

    expect(redeem.statusCode).toBe(200)
    const body = redeem.json()
    expect(body.bootstrapState.onboardingCompleted).toBe(true)
    expect(body.bootstrapState.pairingStatus).toBe("INVITE_PENDING_ACCEPTANCE")
    expect(body.bootstrapState.userDisplayName).toBe("Ankita")
    expect(body.bootstrapState.partnerDisplayName).toBe("Subhajit")
    expect(body.bootstrapState.anniversaryDate).toBe("2026-01-01")
    expect(body.bootstrapState.invite.inviterDisplayName).toBe("Subhajit")
    expect(body.bootstrapState.invite.recipientDisplayName).toBe("Ankita")

    const retry = await app.inject({
      method: "POST",
      url: "/invites/redeem",
      headers: bearer(recipient.accessToken),
      payload: {
        code: invite.code,
      },
    })

    expect(retry.statusCode).toBe(200)
    expect(retry.json().bootstrapState.pairingStatus).toBe("INVITE_PENDING_ACCEPTANCE")
  })

  it("declining an invite clears invite-derived onboarding and returns to onboarding", async () => {
    const inviter = await signIn("subhajit@elaris.dev", "Subhajit")
    await completeProfile(inviter.accessToken, "Subhajit", "Ankita", "2026-01-01")
    const createInvite = await app.inject({
      method: "POST",
      url: "/invites",
      headers: bearer(inviter.accessToken),
    })
    const invite = createInvite.json()
    const recipient = await signIn("ankita@elaris.dev", "Ankita Google")
    await app.inject({
      method: "POST",
      url: "/invites/redeem",
      headers: bearer(recipient.accessToken),
      payload: {
        code: invite.code,
      },
    })

    const decline = await app.inject({
      method: "POST",
      url: `/invites/${invite.inviteId}/decline`,
      headers: bearer(recipient.accessToken),
    })

    expect(decline.statusCode).toBe(200)
    const body = decline.json()
    expect(body.onboardingCompleted).toBe(false)
    expect(body.pairingStatus).toBe("UNPAIRED")
    expect(body.userDisplayName).toBeNull()
    expect(body.partnerDisplayName).toBeNull()
    expect(body.anniversaryDate).toBeNull()
  })

  it("rejects expired and already consumed invite codes deterministically", async () => {
    const inviter = await signIn("subhajit@elaris.dev", "Subhajit")
    await completeProfile(inviter.accessToken, "Subhajit", "Ankita", "2026-01-01")
    const createInvite = await app.inject({
      method: "POST",
      url: "/invites",
      headers: bearer(inviter.accessToken),
    })
    const invite = createInvite.json()

    await db.query(
      `UPDATE invites SET expires_at = NOW() - INTERVAL '1 day' WHERE id = $1`,
      [invite.inviteId],
    )

    const recipient = await signIn("ankita@elaris.dev", "Ankita")
    const expiredRedeem = await app.inject({
      method: "POST",
      url: "/invites/redeem",
      headers: bearer(recipient.accessToken),
      payload: {
        code: invite.code,
      },
    })

    expect(expiredRedeem.statusCode).toBe(400)

    const secondInviteResponse = await app.inject({
      method: "POST",
      url: "/invites",
      headers: bearer(inviter.accessToken),
    })
    const secondInvite = secondInviteResponse.json()

    await completeProfile(recipient.accessToken, "Ankita", "Subhajit", "2026-01-01")
    await app.inject({
      method: "POST",
      url: "/invites/redeem",
      headers: bearer(recipient.accessToken),
      payload: {
        code: secondInvite.code,
      },
    })
    await app.inject({
      method: "POST",
      url: `/invites/${secondInvite.inviteId}/accept`,
      headers: bearer(recipient.accessToken),
    })

    const thirdUser = await signIn("third@elaris.dev", "Third")
    const consumedRedeem = await app.inject({
      method: "POST",
      url: "/invites/redeem",
      headers: bearer(thirdUser.accessToken),
      payload: {
        code: secondInvite.code,
      },
    })

    expect(consumedRedeem.statusCode).toBe(400)
  })

  it("persists canvas operations with monotonic revisions and deduplicates client retries", async () => {
    const { inviter } = await pairUsers()
    const operation = {
      clientOperationId: "client-op-1",
      type: "ADD_STROKE",
      strokeId: "stroke-1",
      payload: {
        id: "stroke-1",
        colorArgb: 4278190080,
        width: 8,
        createdAt: 123,
        firstPoint: { x: 10, y: 20 },
      },
      clientCreatedAt: new Date().toISOString(),
    }

    const first = await app.injectWS("/canvas/sync")
    first.send(
      JSON.stringify({
        type: "HELLO",
        accessToken: inviter.accessToken,
        pairSessionId: inviter.pairSessionId,
        lastAppliedServerRevision: 0,
      }),
    )
    await nextWsJson(first)
    first.send(JSON.stringify({ type: "CLIENT_OP", operation }))
    const ack = await nextWsJson(first)
    expect(ack.type).toBe("ACK")
    expect(ack.serverRevision).toBe(1)

    first.send(JSON.stringify({ type: "CLIENT_OP", operation }))
    const duplicateAck = await nextWsJson(first)
    expect(duplicateAck.serverRevision).toBe(1)

    const ops = await app.inject({
      method: "GET",
      url: "/canvas/ops?afterRevision=0",
      headers: bearer(inviter.accessToken),
    })
    expect(ops.statusCode).toBe(200)
    expect(ops.json().operations).toHaveLength(1)
    first.close()
  })

  it("broadcasts accepted canvas operations to the paired peer", async () => {
    const { inviter, recipient } = await pairUsers()
    const first = await app.injectWS("/canvas/sync")
    const second = await app.injectWS("/canvas/sync")

    first.send(
      JSON.stringify({
        type: "HELLO",
        accessToken: inviter.accessToken,
        pairSessionId: inviter.pairSessionId,
        lastAppliedServerRevision: 0,
      }),
    )
    second.send(
      JSON.stringify({
        type: "HELLO",
        accessToken: recipient.accessToken,
        pairSessionId: recipient.pairSessionId,
        lastAppliedServerRevision: 0,
      }),
    )
    await nextWsJson(first)
    await nextWsJson(second)

    const peerMessage = nextWsJson(second)
    first.send(
      JSON.stringify({
        type: "CLIENT_OP",
        operation: {
          clientOperationId: "client-op-broadcast",
          type: "CLEAR_CANVAS",
          strokeId: null,
          payload: {},
          clientCreatedAt: new Date().toISOString(),
        },
      }),
    )

    const ack = await nextWsJson(first)
    const serverOp = await peerMessage
    expect(ack.type).toBe("ACK")
    expect(serverOp.type).toBe("SERVER_OP")
    expect(serverOp.operation.serverRevision).toBe(1)
    first.close()
    second.close()
  })

  it("accepts canvas operation batches with contiguous revisions", async () => {
    const { inviter } = await pairUsers()
    const first = await app.injectWS("/canvas/sync")

    first.send(
      JSON.stringify({
        type: "HELLO",
        accessToken: inviter.accessToken,
        pairSessionId: inviter.pairSessionId,
        lastAppliedServerRevision: 0,
      }),
    )
    await nextWsJson(first)
    first.send(
      JSON.stringify({
        type: "CLIENT_OP_BATCH",
        batchId: "batch-1",
        clientCreatedAt: new Date().toISOString(),
        operations: [
          {
            clientOperationId: "batch-op-1",
            type: "ADD_STROKE",
            strokeId: "batch-stroke-1",
            payload: {
              id: "batch-stroke-1",
              colorArgb: 4278190080,
              width: 8,
              createdAt: 123,
              firstPoint: { x: 1, y: 2 },
            },
            clientCreatedAt: new Date().toISOString(),
          },
          {
            clientOperationId: "batch-op-2",
            type: "FINISH_STROKE",
            strokeId: "batch-stroke-1",
            payload: {},
            clientCreatedAt: new Date().toISOString(),
          },
        ],
      }),
    )

    const ack = await nextWsJson(first)
    expect(ack.type).toBe("ACK_BATCH")
    expect(ack.ackedClientOperationIds).toEqual(["batch-op-1", "batch-op-2"])
    expect(ack.ackedThroughRevision).toBe(2)
    expect(ack.operations.map((operation: { serverRevision: number }) => operation.serverRevision))
      .toEqual([1, 2])
    first.close()
  })

  it("keeps canvas snapshot materialized to the latest current state", async () => {
    const { inviter } = await pairUsers()
    const first = await app.injectWS("/canvas/sync")

    first.send(
      JSON.stringify({
        type: "HELLO",
        accessToken: inviter.accessToken,
        pairSessionId: inviter.pairSessionId,
        lastAppliedServerRevision: 0,
      }),
    )
    await nextWsJson(first)
    first.send(
      JSON.stringify({
        type: "CLIENT_OP_BATCH",
        batchId: "snapshot-batch-1",
        clientCreatedAt: new Date().toISOString(),
        operations: [
          addStrokeOperation("snapshot-op-1", "deleted-stroke", 1, 1),
          appendPointsOperation("snapshot-op-2", "deleted-stroke", [{ x: 2, y: 2 }]),
          finishStrokeOperation("snapshot-op-3", "deleted-stroke"),
          deleteStrokeOperation("snapshot-op-4", "deleted-stroke"),
          clearCanvasOperation("snapshot-op-5"),
          addStrokeOperation("snapshot-op-6", "current-stroke", 9, 9),
          finishStrokeOperation("snapshot-op-7", "current-stroke"),
        ],
      }),
    )

    const ack = await nextWsJson(first)
    expect(ack.type).toBe("ACK_BATCH")
    expect(ack.ackedThroughRevision).toBe(7)

    const snapshot = await app.inject({
      method: "GET",
      url: "/canvas/snapshot",
      headers: bearer(inviter.accessToken),
    })
    const tail = await app.inject({
      method: "GET",
      url: "/canvas/ops?afterRevision=5",
      headers: bearer(inviter.accessToken),
    })

    expect(snapshot.statusCode).toBe(200)
    expect(snapshot.json().revision).toBe(7)
    expect(snapshot.json().snapshot.strokes.map((stroke: { id: string }) => stroke.id))
      .toEqual(["current-stroke"])
    expect(tail.json().operations.map((operation: { serverRevision: number }) => operation.serverRevision))
      .toEqual([6, 7])
    first.close()
  })

  async function signIn(email: string, name: string) {
    const response = await app.inject({
      method: "POST",
      url: "/auth/google",
      payload: {
        idToken: `dev-google:${email}:${name}`,
      },
    })
    expect(response.statusCode).toBe(200)
    return response.json() as {
      accessToken: string
      refreshToken: string
      userId: string
    }
  }

  async function completeProfile(
    accessToken: string,
    displayName: string,
    partnerDisplayName: string,
    anniversaryDate: string,
  ) {
    const response = await app.inject({
      method: "PUT",
      url: "/me/profile",
      headers: bearer(accessToken),
      payload: {
        displayName,
        partnerDisplayName,
        anniversaryDate,
      },
    })
    expect(response.statusCode).toBe(200)
  }

  async function pairUsers() {
    const inviter = await signIn("subhajit@elaris.dev", "Subhajit")
    await completeProfile(inviter.accessToken, "Subhajit", "Ankita", "2026-01-01")
    const createInvite = await app.inject({
      method: "POST",
      url: "/invites",
      headers: bearer(inviter.accessToken),
    })
    const invite = createInvite.json()

    const recipient = await signIn("ankita@elaris.dev", "Ankita")
    await completeProfile(recipient.accessToken, "Ankita", "Subhajit", "2026-01-01")
    await app.inject({
      method: "POST",
      url: "/invites/redeem",
      headers: bearer(recipient.accessToken),
      payload: { code: invite.code },
    })
    const accept = await app.inject({
      method: "POST",
      url: `/invites/${invite.inviteId}/accept`,
      headers: bearer(recipient.accessToken),
    })
    expect(accept.statusCode).toBe(200)
    const pairSessionId = accept.json().pairSessionId as string
    return {
      inviter: { ...inviter, pairSessionId },
      recipient: { ...recipient, pairSessionId },
    }
  }
})

function bearer(accessToken: string) {
  return {
    authorization: `Bearer ${accessToken}`,
  }
}

function nextWsJson(socket: { once: (event: string, listener: (raw: unknown) => void) => void }) {
  return new Promise<Record<string, any>>((resolve) => {
    socket.once("message", (raw) => {
      resolve(JSON.parse(String(raw)))
    })
  })
}

function addStrokeOperation(
  clientOperationId: string,
  strokeId: string,
  x: number,
  y: number,
) {
  return {
    clientOperationId,
    type: "ADD_STROKE",
    strokeId,
    payload: {
      id: strokeId,
      colorArgb: 4278190080,
      width: 8,
      createdAt: 123,
      firstPoint: { x, y },
    },
    clientCreatedAt: new Date().toISOString(),
  }
}

function appendPointsOperation(
  clientOperationId: string,
  strokeId: string,
  points: Array<{ x: number; y: number }>,
) {
  return {
    clientOperationId,
    type: "APPEND_POINTS",
    strokeId,
    payload: { points },
    clientCreatedAt: new Date().toISOString(),
  }
}

function finishStrokeOperation(clientOperationId: string, strokeId: string) {
  return {
    clientOperationId,
    type: "FINISH_STROKE",
    strokeId,
    payload: {},
    clientCreatedAt: new Date().toISOString(),
  }
}

function deleteStrokeOperation(clientOperationId: string, strokeId: string) {
  return {
    clientOperationId,
    type: "DELETE_STROKE",
    strokeId,
    payload: {},
    clientCreatedAt: new Date().toISOString(),
  }
}

function clearCanvasOperation(clientOperationId: string) {
  return {
    clientOperationId,
    type: "CLEAR_CANVAS",
    strokeId: null,
    payload: {},
    clientCreatedAt: new Date().toISOString(),
  }
}
