import { describe, expect, it, vi } from "vitest"
import type { Messaging } from "firebase-admin/messaging"
import { FirebaseAdminPushSender } from "../src/infra/push/sender.js"
import type { MulberryPushMessage } from "../src/infra/push/types.js"

describe("Apple wallpaper delivery", () => {
  it("sends a passive Shortcut trigger without changing Android data delivery", async () => {
    const send = vi.fn().mockResolvedValue({ responses: [{ success: true }] })
    const sender = new FirebaseAdminPushSender({ sendEachForMulticast: send } as unknown as Messaging)
    const message: MulberryPushMessage = {
      tokens: ["fixture-token"],
      data: { type: "CANVAS_UPDATED", pairSessionId: "pair-one", latestRevision: "14", snapshotRevision: "12", actorUserId: "partner" },
      android: { priority: "high", collapseKey: "canvas-pair-one", ttlMs: 60_000 },
    }
    await sender.send(message)
    const payload = send.mock.calls[0][0]
    expect(payload.data).toEqual(message.data)
    expect(payload.android).toEqual({ priority: "high", collapseKey: "canvas-pair-one", ttl: 60_000 })
    expect(payload.notification).toBeUndefined()
    expect(payload.apns.headers).toMatchObject({ "apns-push-type": "alert", "apns-priority": "10", "apns-collapse-id": "canvas-pair-one" })
    expect(payload.apns.payload.aps).toEqual({
      alert: { title: "Mulberry canvas updated", body: "Your wallpaper update is ready." },
      threadId: "mulberry-wallpaper", "interruption-level": "passive",
    })
    expect(Number(payload.apns.headers["apns-expiration"])).toBeGreaterThan(Date.now() / 1_000)
  })

  it("shows pairing changes as banners and keeps reminders passive", async () => {
    const send = vi.fn().mockResolvedValue({ responses: [{ success: true }, { success: true }] })
    const sender = new FirebaseAdminPushSender({ sendEachForMulticast: send } as unknown as Messaging)
    const levels: Record<string, string> = {}
    for (const type of ["PAIRING_CONFIRMED", "PAIRING_DISCONNECTED", "CANVAS_NUDGE", "DRAW_REMINDER"] as const) {
      await sender.send({
        tokens: ["fixture-token"],
        data: { type, pairSessionId: "pair-one" },
        android: { priority: "high", collapseKey: `${type}-pair-one`, ttlMs: 60_000 },
      } as MulberryPushMessage)
      const call = send.mock.calls[send.mock.calls.length - 1]
      levels[type] = call[0].apns.payload.aps["interruption-level"]
    }
    expect(levels).toEqual({
      PAIRING_CONFIRMED: "active",
      PAIRING_DISCONNECTED: "active",
      CANVAS_NUDGE: "passive",
      DRAW_REMINDER: "passive",
    })
  })
})
