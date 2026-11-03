import { createPublicKey, generateKeyPairSync, verify } from "node:crypto"
import { describe, expect, it } from "vitest"
import { loadConfig } from "../src/infra/config/config.js"
import { apnsProviderToken, createWidgetPushSender, NoopWidgetPushSender } from "../src/infra/push/widgetSender.js"

describe("APNs widget pushes", () => {
  const { privateKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" })
  const pem = privateKey.export({ type: "pkcs8", format: "pem" }).toString()

  it("signs provider tokens that verify with the key", () => {
    const token = apnsProviderToken("TEAMID1234", { keyId: "KEY1234567", privateKey: pem }, 1_700_000_000_000)
    const [header, claims, signature] = token.split(".")
    expect(JSON.parse(Buffer.from(header, "base64url").toString())).toEqual({ alg: "ES256", kid: "KEY1234567" })
    expect(JSON.parse(Buffer.from(claims, "base64url").toString())).toEqual({ iss: "TEAMID1234", iat: 1_700_000_000 })
    const valid = verify(
      "sha256",
      Buffer.from(`${header}.${claims}`),
      { key: createPublicKey(pem), dsaEncoding: "ieee-p1363" },
      Buffer.from(signature, "base64url"),
    )
    expect(valid).toBe(true)
  })

  it("reads the keys from the environment, with escaped line breaks", () => {
    // The loader also reads the repository's .env; set every key so this test sees only its own.
    const none = { APNS_PRODUCTION_KEY_ID: "", APNS_PRODUCTION_PRIVATE_KEY: "", APNS_SANDBOX_KEY_ID: "", APNS_SANDBOX_PRIVATE_KEY: "" }
    const config = loadConfig({
      ...none,
      APNS_TEAM_ID: "TEAMID1234",
      APNS_SANDBOX_KEY_ID: "SANDBOX123",
      APNS_SANDBOX_PRIVATE_KEY: pem.replaceAll("\n", "\\n"),
    })
    expect(config.apnsKeys).toEqual({ teamId: "TEAMID1234", sandbox: { keyId: "SANDBOX123", privateKey: pem.trim() }, production: undefined })
    expect(loadConfig({ ...none, APNS_TEAM_ID: "TEAMID1234" }).apnsKeys).toBeUndefined()
  })

  it("sends nothing without keys", () => {
    expect(createWidgetPushSender(undefined)).toBeInstanceOf(NoopWidgetPushSender)
  })
})
