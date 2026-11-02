import { NextRequest, NextResponse } from "next/server"

import { normalizeInviteCode, platformFromUserAgent, playStoreUrlForInvite } from "@/lib/invite"

/**
 * Android visitors go straight to Google Play, which carries the code through the install.
 * Everyone else gets the invite page: iPhones and iPads need it to hand the code to the app.
 */
export function proxy(request: NextRequest) {
  if (request.nextUrl.pathname !== "/invite") {
    return NextResponse.next()
  }

  const code = normalizeInviteCode(request.nextUrl.searchParams.get("code"))
  if (!code) {
    return NextResponse.next()
  }

  if (platformFromUserAgent(request.headers.get("user-agent")) !== "android") {
    return NextResponse.next()
  }

  return NextResponse.redirect(playStoreUrlForInvite(code), 302)
}

export const config = {
  matcher: ["/invite"]
}
