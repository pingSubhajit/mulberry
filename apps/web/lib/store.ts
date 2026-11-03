import { platformFromUserAgent } from "@/lib/invite"

export type AppStoreName = "appStore" | "googlePlay"

/**
 * The store to lead with, or null when the device can't be told. Mulberry ships for macOS under the
 * same App Store listing, so Macs get the App Store; that also covers iPads, which send a Mac user agent.
 */
export function storeFromUserAgent(userAgent: string | null): AppStoreName | null {
  const platform = platformFromUserAgent(userAgent)
  if (platform === "ios" || userAgent?.includes("Macintosh")) return "appStore"
  if (platform === "android") return "googlePlay"
  return null
}
