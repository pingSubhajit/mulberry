import { NextRequest, NextResponse } from "next/server"

const PLAY_STORE_PACKAGE = "com.subhajit.mulberry"

function normalizeInviteCode(raw: string | null): string | null {
  if (!raw) return null
  const digits = raw.replaceAll(/\D/g, "").slice(0, 6)
  return digits.length === 6 ? digits : null
}

function isAndroidMobile(userAgent: string | null): boolean {
  if (!userAgent) return false
  return userAgent.includes("Android")
}

function playStoreUrlForInvite(code: string): string {
  const referrer = new URLSearchParams({
    invite_code: code,
    src: "invite_link"
  }).toString()
  return `https://play.google.com/store/apps/details?id=${PLAY_STORE_PACKAGE}&referrer=${encodeURIComponent(
    referrer
  )}`
}

export function middleware(request: NextRequest) {
  if (request.nextUrl.pathname !== "/invite") {
    return NextResponse.next()
  }

  const code = normalizeInviteCode(request.nextUrl.searchParams.get("code"))
  if (!code) {
    return NextResponse.next()
  }

  const userAgent = request.headers.get("user-agent")
  if (!isAndroidMobile(userAgent)) {
    return NextResponse.next()
  }

  return NextResponse.redirect(playStoreUrlForInvite(code), 302)
}

export const config = {
  matcher: ["/invite"]
}

