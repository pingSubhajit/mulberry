import { describe, expect, it } from "vitest"
import {
  createPushSender,
  NoopPushSender,
  parseServiceAccountJson,
} from "../src/push.js"

describe("createPushSender", () => {
  it("returns a noop sender when firebase credentials are absent", () => {
    expect(createPushSender({})).toBeInstanceOf(NoopPushSender)
  })

  it("accepts a raw service-account JSON string", () => {
    const parsed = parseServiceAccountJson(
      JSON.stringify({
        type: "service_account",
        project_id: "mulberry-test",
        private_key_id: "key-id",
        private_key: "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----\n",
        client_email: "firebase-adminsdk@example.com",
      }),
    )

    expect(parsed.type).toBe("service_account")
    expect(parsed.project_id).toBe("mulberry-test")
  })

  it("accepts an over-escaped service-account JSON string", () => {
    const escaped = JSON.stringify({
      type: "service_account",
      project_id: "mulberry-test",
      private_key_id: "key-id",
      private_key: "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----\n",
      client_email: "firebase-adminsdk@example.com",
      client_id: "1234567890",
      auth_uri: "https://accounts.google.com/o/oauth2/auth",
      token_uri: "https://oauth2.googleapis.com/token",
      auth_provider_x509_cert_url: "https://www.googleapis.com/oauth2/v1/certs",
      client_x509_cert_url: "https://example.com/cert",
      universe_domain: "googleapis.com",
    }).replace(/"/g, "\\\"")

    const parsed = parseServiceAccountJson(escaped)

    expect(parsed.type).toBe("service_account")
    expect(parsed.project_id).toBe("mulberry-test")
  })
})
