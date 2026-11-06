import type { Metadata } from "next"

import ShareFrameAdminClient from "./ShareFrameAdminClient"

export const metadata: Metadata = {
  title: "Share frames"
}

export default function ShareFrameAdminPage() {
  return <ShareFrameAdminClient />
}
