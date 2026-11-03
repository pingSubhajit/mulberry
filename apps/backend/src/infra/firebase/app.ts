import { readFileSync } from "node:fs"
import { cert, getApps, initializeApp, type App, type ServiceAccount } from "firebase-admin/app"

export interface FirebaseCredentials {
  serviceAccountPath?: string
  serviceAccountJson?: string
}

/** The one Firebase app push and Remote Config share, or null when no service account is set. */
export function firebaseApp(credentials: FirebaseCredentials): App | null {
  const existing = getApps()[0]
  if (existing) return existing
  if (!credentials.serviceAccountPath && !credentials.serviceAccountJson) return null
  const serviceAccount: ServiceAccount = JSON.parse(
    credentials.serviceAccountJson ?? readFileSync(credentials.serviceAccountPath ?? "", "utf8"),
  )
  return initializeApp({ credential: cert(serviceAccount) })
}
