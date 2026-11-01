import { newDb } from "pg-mem"
import type { FastifyInstance } from "fastify"
import { exportPKCS8, generateKeyPair, decodeProtectedHeader, decodeJwt, createLocalJWKSet, exportJWK, SignJWT } from "jose"
import { afterEach, beforeEach, describe, expect, it } from "vitest"
import { createApp } from "../src/app/createApp.js"
import { runMigrations, type Database } from "../src/infra/db/database.js"
import type { AppleCredentialClient } from "../src/infra/auth/appleCredentialClient.js"
import { DefaultAppleCredentialClient } from "../src/infra/auth/appleCredentialClient.js"
import { DefaultAppleTokenVerifier, sha256Hex } from "../src/infra/auth/appleTokenVerifier.js"
import type { AppConfig } from "../src/infra/config/config.js"
import type { AuthResponse } from "../src/contracts/auth.js"
import type { BootstrapResponse } from "../src/contracts/bootstrap.js"

describe("Sign in with Apple", () => {
  let app: FastifyInstance
  let db: Database
  let appleCredentials: RecordingAppleCredentials

  beforeEach(async () => {
    const pool = new (newDb({ autoCreateForeignKeyIndices: true }).adapters.createPg().Pool)()
    await runMigrations(pool)
    db = {
      query: pool.query.bind(pool),
      transaction: async (fn) => {
        await pool.query("BEGIN")
        try {
          const result = await fn({ query: pool.query.bind(pool) })
          await pool.query("COMMIT")
          return result
        } catch (error) {
          await pool.query("ROLLBACK")
          throw error
        }
      },
      end: async () => {
        await pool.end()
      },
    }
    appleCredentials = new RecordingAppleCredentials()
    app = await createApp({ db, appleCredentials, config: testConfig() })
    await app.ready()
  })

  afterEach(async () => {
    await app.close()
  })

  async function google(email: string, name = "Google Person"): Promise<AuthResponse> {
    const response = await app.inject({ method: "POST", url: "/auth/google", payload: { idToken: `dev-google:${email}:${name}` } })
    expect(response.statusCode).toBe(200)
    return response.json()
  }

  async function apple(subject: string, email: string | null, extra: Record<string, string> = {}) {
    return app.inject({
      method: "POST",
      url: "/auth/apple",
      payload: { identityToken: `dev-apple:${subject}:${email ?? ""}`, nonce: "nonce", ...extra },
    })
  }

  it("creates an account with the name the app sends, and stores Apple's refresh token", async () => {
    const response = await apple("apple-1", "new@icloud.com", { displayName: "  Riya  ", authorizationCode: "code-1" })

    expect(response.statusCode).toBe(200)
    const body: AuthResponse = response.json()
    expect(body.bootstrapState.userEmail).toBe("new@icloud.com")
    expect(body.bootstrapState.userDisplayName).toBe("Riya")
    expect(body.bootstrapState.signInMethods).toEqual(["apple"])
    expect(appleCredentials.exchanged).toEqual([{ code: "code-1", clientId: "com.subhajit.mulberry.dev" }])

    const again: AuthResponse = (await apple("apple-1", "new@icloud.com")).json()
    expect(again.userId).toBe(body.userId)
  })

  it("signs Apple in to the Google account with the same verified email", async () => {
    const googleAuth = await google("Same@Gmail.com")

    const response = await apple("apple-2", "same@gmail.com")

    expect(response.statusCode).toBe(200)
    const body: AuthResponse = response.json()
    expect(body.userId).toBe(googleAuth.userId)
    expect(body.bootstrapState.signInMethods).toEqual(["google", "apple"])
  })

  it("signs Google in to the Apple account with the same email", async () => {
    const appleAuth: AuthResponse = (await apple("apple-3", "person@gmail.com")).json()

    const googleAuth = await google("person@gmail.com")

    expect(googleAuth.userId).toBe(appleAuth.userId)
    expect(googleAuth.bootstrapState.signInMethods).toEqual(["google", "apple"])
  })

  it("gives a Hide My Email address its own account", async () => {
    const googleAuth = await google("hidden@gmail.com")

    const body: AuthResponse = (await apple("apple-4", "x7k2@privaterelay.appleid.com")).json()

    expect(body.userId).not.toBe(googleAuth.userId)
    expect(body.bootstrapState.signInMethods).toEqual(["apple"])
  })

  it("refuses a second Apple ID for an email whose account already has one", async () => {
    await apple("apple-5", "taken@icloud.com")

    const response = await apple("apple-6", "taken@icloud.com")

    expect(response.statusCode).toBe(409)
  })

  it("links Apple from settings, whatever email Apple shares", async () => {
    const googleAuth = await google("linker@gmail.com")

    const link = await app.inject({
      method: "POST",
      url: "/me/sign-in-methods/apple",
      headers: bearer(googleAuth.accessToken),
      payload: { identityToken: "dev-apple:apple-7:q9z@privaterelay.appleid.com", nonce: "nonce", authorizationCode: "code-7" },
    })

    expect(link.statusCode).toBe(200)
    expect((link.json() as BootstrapResponse).signInMethods).toEqual(["google", "apple"])
    const signIn: AuthResponse = (await apple("apple-7", "q9z@privaterelay.appleid.com")).json()
    expect(signIn.userId).toBe(googleAuth.userId)
    expect(signIn.bootstrapState.userEmail).toBe("linker@gmail.com")
  })

  it("links Google from settings to an Apple account", async () => {
    const appleAuth: AuthResponse = (await apple("apple-8", "p4@privaterelay.appleid.com")).json()

    const link = await app.inject({
      method: "POST",
      url: "/me/sign-in-methods/google",
      headers: bearer(appleAuth.accessToken),
      payload: { idToken: "dev-google:real@gmail.com:Real" },
    })

    expect(link.statusCode).toBe(200)
    expect((link.json() as BootstrapResponse).signInMethods).toEqual(["google", "apple"])
    expect((await google("real@gmail.com")).userId).toBe(appleAuth.userId)
  })

  it("won't link an Apple ID that signs in to another account", async () => {
    await apple("apple-9", "first@icloud.com")
    const other = await google("second@gmail.com")

    const link = await app.inject({
      method: "POST",
      url: "/me/sign-in-methods/apple",
      headers: bearer(other.accessToken),
      payload: { identityToken: "dev-apple:apple-9:first@icloud.com", nonce: "nonce" },
    })

    expect(link.statusCode).toBe(409)
    expect(link.json().message).toBe("That Apple account already signs in to another Mulberry account.")
  })

  it("revokes Apple's token when the account is deleted", async () => {
    const body: AuthResponse = (await apple("apple-10", "leaving@icloud.com", { authorizationCode: "code-10" })).json()

    const deletion = await app.inject({ method: "DELETE", url: "/me", headers: bearer(body.accessToken) })

    expect(deletion.statusCode).toBe(204)
    expect(appleCredentials.revoked).toEqual([{ refreshToken: "refresh-for-code-10", clientId: "com.subhajit.mulberry.dev" }])
  })

  it("answers a refused token while linking as a bad request, keeping the session", async () => {
    const googleAuth = await google("keep@gmail.com")

    const link = await app.inject({
      method: "POST",
      url: "/me/sign-in-methods/apple",
      headers: bearer(googleAuth.accessToken),
      payload: { identityToken: "dev-apple:", nonce: "nonce" },
    })

    expect(link.statusCode).toBe(400)
    const bootstrap = await app.inject({ method: "GET", url: "/bootstrap", headers: bearer(googleAuth.accessToken) })
    expect(bootstrap.statusCode).toBe(200)
  })

  it("lists only Google for a Google account", async () => {
    expect((await google("only@gmail.com")).bootstrapState.signInMethods).toEqual(["google"])
  })
})

describe("DefaultAppleTokenVerifier", () => {
  async function signedToken(claims: Record<string, unknown>, audience = "com.subhajit.mulberry.beta") {
    const { privateKey, publicKey } = await generateKeyPair("RS256")
    const jwk = { ...(await exportJWK(publicKey)), kid: "test-key", alg: "RS256" }
    const token = await new SignJWT(claims)
      .setProtectedHeader({ alg: "RS256", kid: "test-key" })
      .setIssuer("https://appleid.apple.com")
      .setAudience(audience)
      .setSubject("apple-subject")
      .setIssuedAt()
      .setExpirationTime("5m")
      .sign(privateKey)
    return { token, keys: createLocalJWKSet({ keys: [jwk] }) }
  }

  it("reads the identity from a token carrying the nonce's hash", async () => {
    const { token, keys } = await signedToken({ email: "a@icloud.com", email_verified: "true", nonce: sha256Hex("raw-nonce") })

    const identity = await new DefaultAppleTokenVerifier(testConfig(), keys).verify(token, "raw-nonce")

    expect(identity).toEqual({
      subject: "apple-subject",
      email: "a@icloud.com",
      emailVerified: true,
      clientId: "com.subhajit.mulberry.beta",
    })
  })

  it("rejects a token issued for a different nonce", async () => {
    const { token, keys } = await signedToken({ nonce: sha256Hex("other") })

    await expect(new DefaultAppleTokenVerifier(testConfig(), keys).verify(token, "raw-nonce")).rejects.toMatchObject({
      statusCode: 401,
      message: "Invalid Apple token: nonce mismatch",
    })
  })

  it("rejects a token for another app", async () => {
    const { token, keys } = await signedToken({ nonce: sha256Hex("raw-nonce") }, "com.example.other")

    await expect(new DefaultAppleTokenVerifier(testConfig(), keys).verify(token, "raw-nonce")).rejects.toMatchObject({
      statusCode: 401,
    })
  })

  it("ignores development tokens unless they're allowed", async () => {
    const { keys } = await signedToken({})
    const config = { ...testConfig(), allowDevAppleTokens: false }

    await expect(new DefaultAppleTokenVerifier(config, keys).verify("dev-apple:x:y@z.com", "n")).rejects.toMatchObject({
      statusCode: 401,
    })
  })
})

describe("DefaultAppleCredentialClient", () => {
  it("exchanges a code and revokes with a client secret signed by the key", async () => {
    const { privateKey } = await generateKeyPair("ES256", { extractable: true })
    const requests: { url: string; fields: URLSearchParams }[] = []
    const client = new DefaultAppleCredentialClient(
      { teamId: "TEAM123456", keyId: "KEY1234567", privateKey: await exportPKCS8(privateKey) },
      async (url, init) => {
        requests.push({ url, fields: new URLSearchParams(String(init.body)) })
        return new Response(JSON.stringify({ refresh_token: "apple-refresh" }), { status: 200 })
      },
    )

    expect(await client.exchangeAuthorizationCode("the-code", "com.subhajit.mulberry.beta")).toBe("apple-refresh")
    await client.revoke("apple-refresh", "com.subhajit.mulberry.beta")

    expect(requests.map((request) => request.url)).toEqual([
      "https://appleid.apple.com/auth/token",
      "https://appleid.apple.com/auth/revoke",
    ])
    expect(requests[0].fields.get("code")).toBe("the-code")
    expect(requests[0].fields.get("grant_type")).toBe("authorization_code")
    expect(requests[1].fields.get("token")).toBe("apple-refresh")
    expect(requests[1].fields.get("token_type_hint")).toBe("refresh_token")
    const secret = requests[0].fields.get("client_secret") ?? ""
    expect(decodeProtectedHeader(secret)).toMatchObject({ alg: "ES256", kid: "KEY1234567" })
    expect(decodeJwt(secret)).toMatchObject({
      iss: "TEAM123456",
      sub: "com.subhajit.mulberry.beta",
      aud: "https://appleid.apple.com",
    })
  })

  it("throws when Apple refuses", async () => {
    const { privateKey } = await generateKeyPair("ES256", { extractable: true })
    const client = new DefaultAppleCredentialClient(
      { teamId: "TEAM123456", keyId: "KEY1234567", privateKey: await exportPKCS8(privateKey) },
      async () => new Response('{"error":"invalid_grant"}', { status: 400 }),
    )

    await expect(client.exchangeAuthorizationCode("bad", "com.subhajit.mulberry.beta")).rejects.toThrow(
      'Apple /auth/token failed: HTTP 400 {"error":"invalid_grant"}',
    )
  })
})

class RecordingAppleCredentials implements AppleCredentialClient {
  exchanged: { code: string; clientId: string }[] = []
  revoked: { refreshToken: string; clientId: string }[] = []

  async exchangeAuthorizationCode(code: string, clientId: string): Promise<string | null> {
    this.exchanged.push({ code, clientId })
    return `refresh-for-${code}`
  }

  async revoke(refreshToken: string, clientId: string): Promise<void> {
    this.revoked.push({ refreshToken, clientId })
  }
}

function testConfig(): AppConfig {
  return {
    port: 8080,
    databaseUrl: "postgres://unused",
    googleClientId: "",
    googleAllowedClientIds: [],
    allowDevGoogleTokens: true,
    allowDevAppleTokens: true,
    appleClientIds: ["com.subhajit.mulberry.beta", "com.subhajit.mulberry.dev"],
    enableDebugRoutes: false,
  }
}

function bearer(accessToken: string) {
  return { authorization: `Bearer ${accessToken}` }
}
