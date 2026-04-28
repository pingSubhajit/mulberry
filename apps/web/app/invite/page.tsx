import Image from "next/image"
import Link from "next/link"
import coupleHugImage from "@/public/couple-hug.png"

import { Button } from "@/components/ui/button"
import { GOOGLE_PLAY_DOWNLOAD_URL } from "@/lib/constants"

import InviteCodeCopyButton from "./InviteCodeCopyButton"

export const dynamic = "force-dynamic"

const notes = [
  {
    title: "Send little notes, right on their lock screen",
    description:
      "Finished drawings appear quietly over your wallpaper. It is the quietest way to stay connected to each other.",
    image: "/bento/card-1.jpg"
  },
  {
    title: "Private by design, just for the two of you",
    description:
      "Mulberry creates a one-to-one space made only for you and your partner. No feeds, no followers, no ads.",
    image: "/bento/card-2.jpg"
  },
  {
    title: "Draw it fast, send with a heart",
    description:
      "Let them know you are missing them, or check in if they had their lunch. All without calling or texting.",
    image: "/bento/card-3.jpg"
  }
]

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

export default async function InvitePage({
  searchParams
}: {
  searchParams: Promise<{ code?: string | string[] }>
}) {
  // Next.js may pass `searchParams` as an async prop (Promise) in newer App Router versions.
  const resolved = await searchParams
  const raw = Array.isArray(resolved.code) ? resolved.code[0] : resolved.code
  const code = normalizeInviteCode(raw)

  return (
    <main className="h-[100svh] overflow-hidden text-white">
      <section className="grid h-full grid-cols-1 lg:grid-cols-12">
        <div className="relative h-full overflow-y-auto px-5 py-10 sm:px-8 sm:py-14 lg:col-span-5 lg:px-16 lg:py-16">
          <div className="mx-auto flex w-full max-w-xl flex-col items-center justify-center text-center lg:mx-0 lg:items-start lg:justify-start lg:text-left">
            <Link
              href="/"
              aria-label="Mulberry home"
              className="inline-flex min-h-11 items-center justify-center lg:justify-start"
            >
              <Image
                  src="/brand/wordmark-white.svg"
                  alt="Mulberry"
                  width={143}
                  height={33}
                  priority
                  className="h-8 w-auto"
              />
            </Link>

            <Image
                src={coupleHugImage}
                alt=""
                priority
                className="mt-8 h-auto w-full max-w-md select-none object-contain sm:mt-10"
                sizes="(min-width: 1024px) 24rem, (min-width: 640px) 22rem, 18rem"
            />

            <h1 className="text-3xl font-semibold leading-[1.05] tracking-[-0.06em] sm:text-4xl">
              You&apos;ve been invited to Mulberry
            </h1>
            <p className="mx-auto mt-4 max-w-md text-sm leading-7 text-white/80 sm:text-base lg:mx-0">
              Mulberry is a minimal Android app for couples to share small drawings on each
              other&apos;s lock screen.
            </p>
          </div>

          <div className="mt-10 w-full">
            {code ? (
                <>
                  <div className="mt-3 flex items-center justify-center gap-4 lg:justify-start">
                    <div className="flex items-center justify-center gap-2 overflow-x-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden sm:w-auto lg:w-auto lg:justify-start">
                      {code.split("").map((digit, index) => (
                        <div
                          key={`${digit}-${index}`}
                          className="inline-flex aspect-square h-12 shrink-0 items-center justify-center rounded-2xl bg-neutral-900 text-4xl font-semibold leading-none text-white sm:h-14 md:h-16"
                        >
                          <span className="font-mono">{digit}</span>
                        </div>
                      ))}
                    </div>
                    <InviteCodeCopyButton
                      code={code}
                      className="h-12 w-12 rounded-2xl bg-neutral-800 text-white hover:bg-neutral-700 sm:h-14 sm:w-14 md:h-16 md:w-16"
                    />
                  </div>

                  <p className="mx-auto mt-5 max-w-xl text-sm leading-6 text-white/75 lg:mx-0 text-center lg:text-left">
                    On Android, install Mulberry from Google Play. After install, opening the app will
                    continue setup automatically.
                  </p>

                  <div className="mt-7 flex flex-col items-center gap-3 sm:flex-row sm:justify-center lg:items-start lg:justify-start">
                    <Button
                        asChild
                        size="lg"
                        className="bg-white text-[#090d18] shadow-none hover:bg-white/90 hover:shadow-none"
                    >
                      <a
                        href={playStoreUrlForInvite(code)}
                        target="_blank"
                        rel="noopener noreferrer"
                      >
                        <Image
                            src="/brand/google-play.svg"
                            alt=""
                            width={26}
                            height={26}
                            className="size-6"
                        />
                        Get it on Google Play
                      </a>
                    </Button>
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
                  <div className="mt-7 flex flex-col items-center gap-3 sm:flex-row sm:justify-center lg:items-start lg:justify-start">
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
        </div>

        <div className="relative hidden h-full overflow-hidden bg-brand lg:col-span-7 lg:flex lg:items-center lg:justify-center">
          <div className="w-full max-w-lg px-8 py-12 xl:max-w-xl xl:px-12 xl:py-16 2xl:max-w-2xl">
            <div className="mx-auto flex max-h-[calc(100svh-8rem)] flex-col gap-10 overflow-y-auto pr-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
              {notes.map((note) => (
                <article key={note.title} className="text-white">
                  <div className="relative mb-5 aspect-[4/3] overflow-hidden rounded-[1.5rem] bg-white/10">
                    <Image
                      src={note.image}
                      alt=""
                      fill
                      className="object-cover"
                      sizes="(min-width: 1024px) 30vw, 90vw"
                    />
                  </div>
                  <h2 className="text-2xl font-semibold tracking-[-0.045em] lg:text-3xl">
                    {note.title}
                  </h2>
                  <p className="mt-3 max-w-sm text-sm leading-6 text-white/80 lg:text-base">
                    {note.description}
                  </p>
                </article>
              ))}
            </div>
          </div>
        </div>
      </section>
    </main>
  )
}
