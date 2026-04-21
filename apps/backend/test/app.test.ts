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
})

function bearer(accessToken: string) {
  return {
    authorization: `Bearer ${accessToken}`,
  }
}
