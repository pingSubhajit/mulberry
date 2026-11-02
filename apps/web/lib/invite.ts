import { GOOGLE_PLAY_DOWNLOAD_URL, SITE_URL } from "@/lib/constants"

/** The phone a visitor is on, as far as the server can tell from its user agent. */
export type InvitePlatform = "ios" | "android" | "other"

/** Six digits, or nothing: invite links carry the code the apps show. */
export function normalizeInviteCode(raw: string | null | undefined): string | null {
  if (!raw) return null
  const digits = raw.replaceAll(/\D/g, "").slice(0, 6)
  return digits.length === 6 ? digits : null
}

export function inviteUrl(code: string): string {
  return `${SITE_URL}/invite?code=${code}`
}

/** Google Play keeps the referrer through the install, so the app picks the code up on first launch. */
export function playStoreUrlForInvite(code: string): string {
  const referrer = new URLSearchParams({
    invite_code: code,
    src: "invite_link"
  }).toString()
  return `${GOOGLE_PLAY_DOWNLOAD_URL}&referrer=${encodeURIComponent(referrer)}`
}

/**
 * iPads ask for the desktop site by default and send a Mac user agent, so the server reports
 * them as "other"; the page corrects that in the browser.
 */
export function platformFromUserAgent(userAgent: string | null): InvitePlatform {
  if (!userAgent) return "other"
  if (/iPhone|iPad|iPod/.test(userAgent)) return "ios"
  if (userAgent.includes("Android")) return "android"
  return "other"
}
