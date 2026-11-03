import { describe, expect, it, vi } from "vitest"
import type { Messaging } from "firebase-admin/messaging"
import { applePushConfiguration, FirebaseAdminPushSender, reactionLiveActivityConfiguration } from "../src/infra/push/sender.js"
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
    for (const type of ["PAIRING_CONFIRMED", "PAIRING_DISCONNECTED", "DRAW_REMINDER"] as const) {
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
      DRAW_REMINDER: "passive",
    })
  })

  it("names the partner in pairing and reminder alerts", () => {
    const alert = (data: MulberryPushMessage["data"]) => applePushConfiguration({
      tokens: ["fixture-token"],
      data,
      android: { priority: "high", collapseKey: "pair-one", ttlMs: 60_000 },
    }).payload?.aps.alert
    const pair = { pairSessionId: "pair-one", actorUserId: "partner" }
    expect(alert({ ...pair, type: "PAIRING_CONFIRMED", actorDisplayName: "Sam" }))
      .toEqual({ title: "You’re paired with Sam", body: "Draw them something to say hello." })
    expect(alert({ ...pair, type: "PAIRING_DISCONNECTED", actorDisplayName: " " }))
      .toEqual({ title: "You’re no longer paired", body: "Your canvas is no longer shared." })
    expect(alert({ pairSessionId: "pair-one", type: "DRAW_REMINDER", partnerDisplayName: "Sam", reminderCount: "1" }))
      .toEqual({ title: "Draw Sam something cute", body: "They’ll see it next time they look at their phone." })
    expect(alert({ pairSessionId: "pair-one", type: "DRAW_REMINDER", partnerDisplayName: "Your partner", reminderCount: "1" }))
      .toMatchObject({ title: "Draw them something cute" })
  })

  it("catches Macs up silently instead of telling them about the wallpaper", async () => {
    const send = vi.fn().mockResolvedValue({ responses: [{ success: true }] })
    const sender = new FirebaseAdminPushSender({ sendEachForMulticast: send } as unknown as Messaging)
    const canvas = { pairSessionId: "pair-one", latestRevision: "14", actorUserId: "partner" }
    for (const data of [
      { ...canvas, type: "CANVAS_UPDATED", snapshotRevision: "12" },
      { ...canvas, type: "CANVAS_NUDGE", actorDisplayName: "Sam" },
    ] as const) {
      send.mockClear()
      await sender.send({
        tokens: ["phone", "mac"],
        macTokens: ["mac"],
        data,
        android: { priority: "high", collapseKey: "canvas-pair-one", ttlMs: 60_000 },
      })
      expect(send).toHaveBeenCalledTimes(2)
      const [phone, mac] = send.mock.calls.map((call) => call[0])
      expect(phone.tokens).toEqual(["phone"])
      // The nudge is Android's alert; one reaching an iPhone stays silent.
      expect(phone.apns.headers["apns-push-type"]).toBe(data.type === "CANVAS_UPDATED" ? "alert" : "background")
      expect(mac.tokens).toEqual(["mac"])
      expect(mac.data).toEqual(data)
      expect(mac.apns.headers).toMatchObject({ "apns-push-type": "background", "apns-priority": "5" })
      expect(mac.apns.payload.aps).toEqual({ contentAvailable: true })
    }
  })

  it("refreshes iPhone and Mac widgets silently when the anniversary changes", async () => {
    const send = vi.fn().mockResolvedValue({ responses: [{ success: true }] })
    const sender = new FirebaseAdminPushSender({ sendEachForMulticast: send } as unknown as Messaging)
    const data = {
      type: "ANNIVERSARY_CHANGED",
      pairSessionId: "pair-one",
      actorUserId: "partner",
      anniversaryDate: "2026-02-14",
    } as const
    await sender.send({
      tokens: ["phone", "mac"],
      macTokens: ["mac"],
      data,
      android: { priority: "high", collapseKey: "anniversary-pair-one", ttlMs: 60_000 },
    })
    expect(send).toHaveBeenCalledTimes(2)
    const [phone, mac] = send.mock.calls.map((call) => call[0])
    expect(phone.tokens).toEqual(["phone"])
    expect(mac.tokens).toEqual(["mac"])
    for (const payload of [phone, mac]) {
      expect(payload.data).toEqual(data)
      expect(payload.apns.headers).toMatchObject({
        "apns-push-type": "background",
        "apns-priority": "5",
        "apns-collapse-id": "anniversary-pair-one",
      })
      expect(payload.apns.payload.aps).toEqual({ contentAvailable: true })
    }
  })

  it("reports invalid tokens from each platform's send", async () => {
    const send = vi.fn()
      .mockResolvedValueOnce({ responses: [{ success: false, error: { code: "messaging/registration-token-not-registered" } }] })
      .mockResolvedValueOnce({ responses: [{ success: false, error: { code: "messaging/internal-error" } }, { success: false, error: { code: "messaging/invalid-registration-token" } }] })
    const sender = new FirebaseAdminPushSender({ sendEachForMulticast: send } as unknown as Messaging)
    const result = await sender.send({
      tokens: ["phone", "mac-one", "mac-two"],
      macTokens: ["mac-one", "mac-two"],
      data: { type: "PAIRING_CONFIRMED", pairSessionId: "pair-one", actorUserId: "partner", actorDisplayName: "Sam" },
      android: { priority: "high", collapseKey: "pairing-pair-one", ttlMs: 60_000 },
    })
    expect(result.invalidTokens).toEqual(["phone", "mac-two"])
  })

  it("starts the reactions Live Activity with its Swift type, and updates it with content alone", () => {
    const message = {
      token: "phone",
      liveActivityToken: "start-token",
      partnerName: "Sam",
      contentState: { counts: { HEART: 2 }, latest: "HEART" as const },
      alert: { title: "Sam", body: "Sent you love ❤️" },
    }
    const now = 1_800_000_000_000
    const start = reactionLiveActivityConfiguration({ ...message, event: "start" }, now)
    expect(start.liveActivityToken).toBe("start-token")
    expect(start.headers).toEqual({ "apns-priority": "10" })
    expect(start.payload?.aps).toEqual({
      timestamp: 1_800_000_000,
      event: "start",
      "content-state": { counts: { HEART: 2 }, latest: "HEART" },
      "stale-date": 1_800_000_300,
      alert: { title: "Sam", body: "Sent you love ❤️", sound: "default" },
      "attributes-type": "ReactionActivityAttributes",
      attributes: { partnerName: "Sam" },
    })
    const update = reactionLiveActivityConfiguration({ ...message, liveActivityToken: "activity-token", event: "update" }, now)
    expect(update.payload?.aps).not.toHaveProperty("attributes")
    expect(update.payload?.aps).not.toHaveProperty("attributes-type")
    expect(update.payload?.aps.event).toBe("update")
  })
})
