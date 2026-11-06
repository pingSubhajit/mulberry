import { describe, expect, it } from "vitest"
import { validClientEvent } from "../src/modules/analytics/service.js"

const NOW = Date.parse("2026-11-06T12:00:00Z")
const ID = "1b4e28ba-2fa1-4d2e-883f-0016d3cca427"

function event(name: string, properties: Record<string, unknown>) {
  return { id: ID, event: name, occurredAt: "2026-11-06T11:59:00Z", properties }
}

describe("Share analytics events", () => {
  it("accepts the share sheet opening", () => {
    expect(validClientEvent(event("mulberry_share_opened", {}), NOW)?.properties).toEqual({})
  })

  it("accepts a frame key and rejects anything else", () => {
    expect(validClientEvent(event("mulberry_share_frame_selected", { frame_key: "lavender-black" }), NOW)?.properties).toEqual({
      frame_key: "lavender-black",
    })
    expect(validClientEvent(event("mulberry_share_frame_selected", { frame_key: "Lavender Black" }), NOW)).toBeNull()
    expect(validClientEvent(event("mulberry_share_frame_selected", {}), NOW)).toBeNull()
  })

  it("accepts flags as booleans or as the strings the apps send", () => {
    const fromStrings = validClientEvent(
      event("mulberry_share_completed", { frame_key: "diwali", used_real_wallpaper: "true", caption_edited: "false" }),
      NOW,
    )
    expect(fromStrings?.properties).toEqual({ frame_key: "diwali", used_real_wallpaper: true, caption_edited: false })

    const withActivity = validClientEvent(
      event("mulberry_share_completed", {
        frame_key: "polaroid",
        used_real_wallpaper: false,
        caption_edited: true,
        activity_type: "net.whatsapp.WhatsApp.ShareExtension",
      }),
      NOW,
    )
    expect(withActivity?.properties).toEqual({
      frame_key: "polaroid",
      used_real_wallpaper: false,
      caption_edited: true,
      activity_type: "net.whatsapp.WhatsApp.ShareExtension",
    })
  })

  it("rejects missing flags and unsafe activity types", () => {
    expect(validClientEvent(event("mulberry_share_completed", { frame_key: "plain", used_real_wallpaper: "yes", caption_edited: false }), NOW)).toBeNull()
    expect(
      validClientEvent(
        event("mulberry_share_completed", { frame_key: "plain", used_real_wallpaper: true, caption_edited: false, activity_type: "has spaces" }),
        NOW,
      ),
    ).toBeNull()
  })
})
