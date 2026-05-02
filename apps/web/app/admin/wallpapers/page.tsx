import type { Metadata } from "next"

import WallpaperAdminClient from "./WallpaperAdminClient"

export const metadata: Metadata = {
  title: "Wallpapers"
}

export default function WallpaperAdminPage() {
  return <WallpaperAdminClient />
}

