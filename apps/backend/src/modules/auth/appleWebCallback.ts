import type { FastifyInstance } from "fastify"
import { HttpError } from "../../infra/http/HttpError.js"

/** The Android apps that may receive a Sign in with Apple result: production and development. */
export const APPLE_CALLBACK_ANDROID_PACKAGES = ["com.subhajit.mulberry", "com.subhajit.mulberry.dev"] as const

/** The scheme the Android app's callback activity listens on. */
export const APPLE_CALLBACK_ANDROID_SCHEME = "mulberry-apple"

/** What Apple posts to the return URL with `response_mode=form_post`. */
interface AppleFormPost {
  code?: string
  id_token?: string
  state?: string
  /** JSON with the person's name and email, sent only the first time they sign in to Mulberry. */
  user?: string
  /** As `user_cancelled_authorize`, when the person closed Apple's page. */
  error?: string
}

/** The part of Apple's `user` field the app needs. */
interface AppleFormUser {
  name?: { firstName?: string; lastName?: string }
}

/**
 * Android has no native Sign in with Apple, so the app opens Apple's web page in a Custom Tab.
 * Apple posts the result here, and this hands it to the app that asked, named in `state` as
 * `package:random`, through an `intent://` redirect. The app checks the state and the nonce, then
 * signs in through `/auth/apple` like the Apple apps do.
 */
export function registerAppleWebCallbackRoute(app: FastifyInstance): void {
  app.register(async (scope) => {
    scope.addContentTypeParser("application/x-www-form-urlencoded", { parseAs: "string" }, (_request, body, done) => {
      const text = typeof body === "string" ? body : body.toString("utf8")
      done(null, Object.fromEntries(new URLSearchParams(text)))
    })

    scope.post("/auth/apple/callback", async (request, reply) => {
      const location = appleCallbackRedirect(formFields(request.body))
      return reply.header("cache-control", "no-store").redirect(location, 303)
    })
  })
}

/** The `intent://` URL that opens the Android app named in the state with Apple's result. */
export function appleCallbackRedirect(form: AppleFormPost): string {
  const androidPackage = packageFromState(form.state)
  const query = new URLSearchParams()
  query.set("state", form.state ?? "")
  if (form.error) {
    query.set("error", form.error)
  } else {
    if (!form.id_token) throw new HttpError(400, "id_token is required")
    query.set("id_token", form.id_token)
    if (form.code) query.set("code", form.code)
    const name = displayName(form.user)
    if (name) query.set("name", name)
  }
  return `intent://callback?${query.toString()}#Intent;scheme=${APPLE_CALLBACK_ANDROID_SCHEME};package=${androidPackage};end`
}

function packageFromState(state: string | undefined): string {
  const separator = state?.indexOf(":") ?? -1
  const candidate = state && separator > 0 ? state.slice(0, separator) : ""
  const known = APPLE_CALLBACK_ANDROID_PACKAGES.find((name) => name === candidate)
  if (!known) throw new HttpError(400, "Unknown app in state")
  return known
}

function formFields(body: unknown): AppleFormPost {
  const fields = (body ?? {}) as Partial<Record<keyof AppleFormPost, unknown>>
  const text = (value: unknown): string | undefined => (typeof value === "string" && value.length > 0 ? value : undefined)
  return {
    code: text(fields.code),
    id_token: text(fields.id_token),
    state: text(fields.state),
    user: text(fields.user),
    error: text(fields.error),
  }
}

function displayName(user: string | undefined): string | null {
  if (!user) return null
  try {
    const parsed = JSON.parse(user) as AppleFormUser
    const name = [parsed.name?.firstName, parsed.name?.lastName]
      .map((part) => part?.trim() ?? "")
      .filter((part) => part.length > 0)
      .join(" ")
    return name.length > 0 ? name : null
  } catch {
    return null
  }
}
