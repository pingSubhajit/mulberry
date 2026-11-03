import { NextResponse, type NextRequest } from "next/server"

import { APP_STORE_DOWNLOAD_URL, GOOGLE_PLAY_DOWNLOAD_URL } from "@/lib/constants"
import { storeFromUserAgent } from "@/lib/store"

export function GET(request: NextRequest) {
  const store = storeFromUserAgent(request.headers.get("user-agent"))
  return NextResponse.redirect(store === "appStore" ? APP_STORE_DOWNLOAD_URL : GOOGLE_PLAY_DOWNLOAD_URL)
}
