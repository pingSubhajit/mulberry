import { describe, expect, it } from "vitest"
import { DefaultGoogleTokenVerifier } from "../src/infra/auth/googleTokenVerifier.js"
import type { AppConfig } from "../src/infra/config/config.js"

describe("DefaultGoogleTokenVerifier", () => {
  it("accepts the server client id plus additional configured audiences", async () => {
    let capturedAudience: string | string[] | undefined
    const verifier = new DefaultGoogleTokenVerifier(testConfig(), {
      async verifyIdToken(options) {
        capturedAudience = options.audience
        return {
          getPayload() {
            return {
              sub: "google-subject",
              email: "mac@example.test",
              name: "Mac User",
              picture: "https://example.test/mac.png",
            }
          },
        }
      },
    })

    const identity = await verifier.verify("real-google-id-token")

    expect(capturedAudience).toEqual(["android-server-client", "macos-native-client", "web-client"])
    expect(identity).toEqual({
      subject: "google-subject",
      email: "mac@example.test",
      name: "Mac User",
      pictureUrl: "https://example.test/mac.png",
    })
  })

  it("returns a client-safe auth error when Google rejects the token", async () => {
    const verifier = new DefaultGoogleTokenVerifier(testConfig(), {
      async verifyIdToken() {
        throw new Error("Wrong recipient, payload audience != requiredAudience")
      },
    })

    await expect(verifier.verify("real-google-id-token")).rejects.toMatchObject({
      statusCode: 401,
      message: "Invalid Google token: Wrong recipient, payload audience != requiredAudience",
    })
  })
})

function testConfig(): AppConfig {
  return {
    port: 8080,
    databaseUrl: "postgres://unused",
    googleClientId: "android-server-client",
    googleAllowedClientIds: ["macos-native-client", "web-client", "android-server-client"],
    allowDevGoogleTokens: false,
  }
}
