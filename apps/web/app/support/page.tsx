import type { Metadata } from "next"
import Image from "next/image"
import Link from "next/link"

import { CONTACT_EMAIL, CONTACT_MAILTO } from "@/lib/constants"

export const metadata: Metadata = {
  title: "Support | Mulberry",
  description:
    "Get help with pairing, wallpaper setup, and your Mulberry account.",
  alternates: {
    canonical: "/support"
  }
}

interface Topic {
  id: string
  title: string
  steps: string[]
}

const topics: Topic[] = [
  {
    id: "pairing",
    title: "Pair with your partner",
    steps: [
      "Sign in with Google and finish your profile.",
      "Tap Invite your partner and share the six-digit code. Codes expire, so send it soon.",
      "Your partner enters the code in Mulberry and accepts. You are paired straight away.",
      "Mulberry pairs exactly two people. Your partner can use an iPhone, iPad, Mac, or Android phone."
    ]
  },
  {
    id: "iphone-wallpaper",
    title: "Drawings on your iPhone or iPad wallpaper",
    steps: [
      "iOS lets apps change the wallpaper only through Shortcuts, so Mulberry sets up a Shortcut called Mulberry Sync once.",
      "Open Settings in Mulberry, tap Wallpaper setup, and allow notifications.",
      "Add the Shortcut, tap Edit, and choose your wallpaper in Set Wallpaper.",
      "Tap the arrow beside Mulberry in the Shortcut's trigger and turn Automation on.",
      "Run the test in Mulberry. New drawings then update your wallpaper on their own."
    ]
  },
  {
    id: "wallpaper-trouble",
    title: "Wallpaper not updating",
    steps: [
      "Check that Automation is still on for Mulberry Sync, and that only one copy of the Shortcut has it on.",
      "Focus and Scheduled Summary can hold notifications back. Allow Mulberry to notify you in Focus.",
      "In Set Wallpaper, choose a wallpaper with no linked Focus.",
      "If the wallpaper looks soft or zoomed, turn off Spatial Scene for that wallpaper in iOS Settings.",
      "Run the test again from Wallpaper setup in Mulberry."
    ]
  },
  {
    id: "mac",
    title: "Mulberry on Mac",
    steps: [
      "Mulberry lives in the menu bar and shows the shared canvas on your desktop.",
      "Press Control-Command-M for Quick Draw. You can change the shortcut in Settings.",
      "Choose the display and position of the desktop drawing in Settings."
    ]
  },
  {
    id: "unpair",
    title: "Unpair or report a problem",
    steps: [
      "Open Settings, tap Partner, then Unpair. Shared drawing stops straight away for both of you.",
      `To report misuse, email ${CONTACT_EMAIL}. Reports are reviewed promptly.`
    ]
  },
  {
    id: "delete",
    title: "Delete your account",
    steps: [
      'On iPhone or iPad, open Settings in Mulberry and tap "Delete your account", below Log Out.',
      'On Mac, open Settings, choose Account, and click "Delete your account".',
      "Deletion is immediate and ends your pairing. See the account deletion page for what is removed."
    ]
  }
]

export default function SupportPage() {
  return (
    <main className="min-h-screen bg-background text-foreground">
      <SiteHeader />

      <section className="mx-auto max-w-6xl px-5 pb-14 pt-10 sm:px-8 lg:px-10 lg:pt-16">
        <div className="bg-brand px-6 py-10 text-white sm:px-10 lg:px-14 lg:py-14">
          <p className="text-sm font-semibold uppercase text-white/68">
            Support
          </p>
          <h1 className="mt-5 max-w-3xl text-5xl font-semibold leading-tight text-white sm:text-6xl">
            How can we help?
          </h1>
          <div className="mt-8 flex flex-col gap-3 text-sm leading-7 text-white/78 sm:flex-row sm:items-center">
            <span className="font-medium text-white">Email:</span>
            <Link
              href={CONTACT_MAILTO}
              className="font-semibold text-white underline underline-offset-4"
            >
              {CONTACT_EMAIL}
            </Link>
          </div>
        </div>
      </section>

      <section className="mx-auto grid max-w-6xl gap-10 px-5 pb-24 sm:px-8 lg:grid-cols-[16rem_1fr] lg:px-10">
        <aside className="hidden lg:block">
          <nav className="sticky top-8 text-sm">
            <p className="mb-4 font-semibold text-foreground">Contents</p>
            <ol className="grid gap-2 text-muted-foreground">
              {topics.map((topic) => (
                <li key={topic.id}>
                  <Link
                    href={`#${topic.id}`}
                    className="block px-3 py-2 transition hover:bg-soft-surface hover:text-brand"
                  >
                    {topic.title}
                  </Link>
                </li>
              ))}
            </ol>
          </nav>
        </aside>

        <article className="grid gap-14">
          {topics.map((topic) => (
            <section key={topic.id} id={topic.id} className="scroll-mt-8">
              <h2 className="text-3xl font-semibold leading-tight text-foreground sm:text-4xl">
                {topic.title}
              </h2>
              <StepList steps={topic.steps} />
            </section>
          ))}
        </article>
      </section>
    </main>
  )
}

function SiteHeader() {
  return (
    <header className="mx-auto flex w-full max-w-6xl items-center justify-between px-5 py-6 sm:px-8 lg:px-10">
      <Link href="/" aria-label="Mulberry home" className="inline-flex min-h-11 items-center">
        <Image
          src="/brand/wordmark-color.svg"
          alt="Mulberry"
          width={143}
          height={33}
          priority
          className="h-8 w-auto"
        />
      </Link>
      <Link
        href="/privacy"
        className="inline-flex min-h-11 items-center px-2 text-sm font-medium text-muted-foreground transition hover:text-brand"
      >
        Privacy
      </Link>
    </header>
  )
}

function StepList({ steps }: { steps: string[] }) {
  return (
    <ol className="mt-6 grid gap-3 text-base leading-7 text-soft-surface-foreground">
      {steps.map((step, index) => (
        <li key={step} className="flex gap-4 bg-soft-surface p-4">
          <span className="flex size-8 shrink-0 items-center justify-center bg-brand text-sm font-semibold text-white">
            {index + 1}
          </span>
          <span>{step}</span>
        </li>
      ))}
    </ol>
  )
}
