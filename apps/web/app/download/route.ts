import { NextResponse, type NextRequest } from "next/server"

import { APP_STORE_DOWNLOAD_URL, GOOGLE_PLAY_DOWNLOAD_URL } from "@/lib/constants"
import { platformFromUserAgent } from "@/lib/invite"

export function GET(request: NextRequest) {
  const platform = platformFromUserAgent(request.headers.get("user-agent"))
  return NextResponse.redirect(platform === "ios" ? APP_STORE_DOWNLOAD_URL : GOOGLE_PLAY_DOWNLOAD_URL)
}
