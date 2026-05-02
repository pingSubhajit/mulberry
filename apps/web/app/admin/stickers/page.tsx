import type { Metadata } from "next"

import StickerAdminClient from "./StickerAdminClient"

export const metadata: Metadata = {
  title: "Stickers"
}

export default function StickerAdminPage() {
  return <StickerAdminClient />
}

