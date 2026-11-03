import type { Metadata } from "next"

import AppVersionsAdminClient from "./AppVersionsAdminClient"

export const metadata: Metadata = {
  title: "App versions"
}

export default function AppVersionsAdminPage() {
  return <AppVersionsAdminClient />
}
