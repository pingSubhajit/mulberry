import Image from "next/image"
import Link from "next/link"

import { Button } from "@/components/ui/button"
import { GOOGLE_PLAY_DOWNLOAD_URL } from "@/lib/constants"

import InviteCodeCopyButton from "./InviteCodeCopyButton"

function normalizeInviteCode(raw?: string): string | null {
  if (!raw) return null
  const digits = raw.replaceAll(/\D/g, "").slice(0, 6)
  return digits.length === 6 ? digits : null
}

function playStoreUrlForInvite(code: string): string {
  const referrer = new URLSearchParams({
    invite_code: code,
    src: "invite_link"
  }).toString()
  return `${GOOGLE_PLAY_DOWNLOAD_URL}&referrer=${encodeURIComponent(referrer)}`
}

export default function InvitePage({
  searchParams
}: {
  searchParams: { code?: string }
}) {
  const code = normalizeInviteCode(searchParams.code)

  return (
    <main className="min-h-screen overflow-hidden text-foreground bg-brand">
      <header className="mx-auto flex w-full max-w-6xl items-center justify-between px-5 py-6 sm:px-8 lg:px-10">
        <Link href="/" aria-label="Mulberry home" className="inline-flex min-h-11 items-center">
          <Image
            src="/brand/wordmark-white.svg"
            alt="Mulberry"
            width={143}
            height={33}
            priority
            className="h-8 w-auto"
          />
        </Link>
        <a
          href={GOOGLE_PLAY_DOWNLOAD_URL}
          className="inline-flex min-h-11 items-center rounded-full px-2 text-sm font-medium text-white transition hover:text-white/70"
        >
          Download
        </a>
      </header>

      <section className="mx-auto w-full max-w-5xl px-5 pb-16 pt-6 sm:px-8 lg:px-10">
        <div className="mx-auto max-w-2xl text-center">
          <h1 className="text-4xl font-semibold leading-[1.05] tracking-[-0.06em] text-white sm:text-5xl">
            You&apos;ve been invited to Mulberry
          </h1>
          <p className="mt-4 text-base leading-7 text-white/80 sm:text-lg">
            Mulberry is a minimal Android app for couples to share small drawings on each
            other&apos;s lock screen.
          </p>
        </div>

        <div className="mx-auto mt-10 max-w-2xl rounded-[1.5rem] bg-white/10 p-6 text-center backdrop-blur sm:p-8">
          {code ? (
            <>
              <p className="text-sm font-medium tracking-wide text-white/70">Invite code</p>
              <div className="mt-3 flex flex-col items-center gap-4 sm:flex-row sm:justify-center">
                <div className="rounded-2xl bg-white/15 px-6 py-4">
                  <div className="text-4xl font-semibold tracking-[0.25em] text-white">
                    {code}
                  </div>
                </div>
                <InviteCodeCopyButton code={code} />
              </div>

              <p className="mx-auto mt-5 max-w-xl text-sm leading-6 text-white/75">
                On Android, install Mulberry from Google Play. After install, opening the app will
                continue setup automatically.
              </p>

              <div className="mt-7 flex flex-col items-center gap-3 sm:flex-row sm:justify-center">
                <Button
                  asChild
                  size="lg"
                  className="bg-white text-[#090d18] shadow-none hover:bg-white/90 hover:shadow-none"
                >
                  <a href={playStoreUrlForInvite(code)}>Get it on Google Play</a>
                </Button>
                <Link
                  href="/"
                  className="text-sm font-medium text-white/80 underline transition hover:text-white"
                >
                  Learn more
                </Link>
              </div>
            </>
          ) : (
            <>
              <p className="text-sm font-medium tracking-wide text-white/70">Invite link</p>
              <h2 className="mt-3 text-2xl font-semibold tracking-[-0.045em] text-white">
                That invite link looks incomplete
              </h2>
              <p className="mx-auto mt-3 max-w-xl text-sm leading-6 text-white/75">
                Ask your partner to share the invite link again, or copy the 6-digit code they sent
                you.
              </p>
              <div className="mt-7 flex flex-col items-center gap-3 sm:flex-row sm:justify-center">
                <Button
                  asChild
                  size="lg"
                  className="bg-white text-[#090d18] shadow-none hover:bg-white/90 hover:shadow-none"
                >
                  <a href={GOOGLE_PLAY_DOWNLOAD_URL}>Get it on Google Play</a>
                </Button>
                <Link
                  href="/"
                  className="text-sm font-medium text-white/80 underline transition hover:text-white"
                >
                  Learn more
                </Link>
              </div>
            </>
          )}
        </div>
      </section>
    </main>
  )
}
