import Image from "next/image"
import Link from "next/link"

import { Button } from "@/components/ui/button"
import { CONTACT_MAILTO, GOOGLE_PLAY_DOWNLOAD_URL } from "@/lib/constants"

const notes = [
  {
    title: "Send little notes, right on their lock screen",
    description: "Finished drawings appear quietly over your wallpaper. It is the quietest way to stay connected to each other.",
    image: "/bento/card-1.png"
  },
  {
    title: "Private by design, just for the two of you",
    description: "Mulberry creates a one-to-one space made only for you and your partner. No feeds, no followers, no ads.",
    image: "/bento/card-2.png"
  },
  {
    title: "Draw it fast, send with a heart",
    description: "Let them know you are missing them, or check in if they had their lunch. All without calling or texting.",
    image: "/bento/card-3.png"
  }
]

export default function LandingPage() {
  return (
    <main className="min-h-screen overflow-hidden text-foreground bg-brand">
      <SiteHeader />

      <section className="mx-auto flex min-h-[calc(100vh-6rem)] w-full max-w-7xl flex-col items-center px-5 pt-8 text-center sm:px-8 lg:px-10 lg:pt-10">
        <div className="mx-auto max-w-4xl">
          <h1 className="mx-auto max-w-4xl text-5xl font-semibold leading-[1.03] tracking-[-0.07em] text-white sm:text-6xl lg:text-[5.4rem]">
            Draw something. Let it arrive quietly.
          </h1>
          <p className="mx-auto mt-5 max-w-xl text-base leading-7 text-white/70 sm:text-lg">
            Mulberry is a minimal Android app for couples to share small drawings
            on each other&apos;s lock screen. No feed. No noise. Just one shared
            canvas.
          </p>

          <div className="mt-7 flex justify-center">
            <Button asChild size="lg" className="bg-white text-[#090d18] shadow-none hover:bg-white/90 hover:shadow-none">
              <Link href={GOOGLE_PLAY_DOWNLOAD_URL}>
                <Image
                  src="/brand/google-play.svg"
                  alt=""
                  width={26}
                  height={26}
                  className="size-6"
                />
                Google Play
              </Link>
            </Button>
          </div>
        </div>

        <div className="relative mt-8 flex w-full flex-1 items-end justify-center overflow-hidden sm:mt-10">
          <div className="absolute bottom-0 h-28 w-[38rem] rounded-full bg-brand/12 blur-3xl" />
          <Image
            src="/hero.png"
            alt="Mulberry app showing a shared canvas and lock-screen wallpaper setup"
            width={1374}
            height={2734}
            priority
            className="relative mb-[-46%] h-auto w-[min(94vw,36rem)] sm:mb-[-38%] lg:mb-[-35%] lg:w-[42rem]"
            sizes="(min-width: 1024px) 42rem, 94vw"
          />
        </div>
      </section>

      <section id="about" className="pb-20 bg-background">
        <div className="mx-auto w-full p-3 md:p-4">
          <div className="mx-auto grid max-w-[92rem] gap-6 p-5 md:grid-cols-3 md:p-6 lg:p-8">
            {notes.map((note) => (
              <article key={note.title} className="text-foreground">
                <div className="relative mb-7 aspect-[4/3] overflow-hidden rounded-[1.5rem] bg-white/10">
                  <Image
                    src={note.image}
                    alt=""
                    fill
                    className="object-cover"
                    sizes="(min-width: 768px) 30vw, 90vw"
                  />
                </div>
                <h2 className="text-2xl font-semibold tracking-[-0.045em] lg:text-3xl">
                  {note.title}
                </h2>
                <p className="mt-3 max-w-sm text-sm leading-6 text-muted-foreground lg:text-base">
                  {note.description}
                </p>
              </article>
            ))}
          </div>
        </div>
      </section>

      <footer className="w-full bg-background flex-col gap-4 border-t border-brand-muted/45">
        <div className="mx-auto flex max-w-6xl flex-col gap-4 px-5 py-8 text-sm text-muted-foreground sm:px-8 md:flex-row md:items-center md:justify-between lg:px-10">
          <p>© 2026 Mulberry. All rights reserved.</p>
          <div className="flex gap-5">
            <Link href="/privacy" className="transition hover:text-brand">
              Privacy
            </Link>
            <Link href="/delete-account" className="transition hover:text-brand">
              Delete account
            </Link>
            <Link href="/terms" className="transition hover:text-brand">
              Terms
            </Link>
          <Link href={CONTACT_MAILTO} className="transition hover:text-brand">
            Contact
          </Link>
          </div>
        </div>
      </footer>
    </main>
  )
}

function SiteHeader() {
  return (
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
      <Link
        href={GOOGLE_PLAY_DOWNLOAD_URL}
        className="inline-flex min-h-11 items-center rounded-full px-2 text-sm font-medium text-white/65 transition hover:text-white"
      >
        Download
      </Link>
    </header>
  )
}
