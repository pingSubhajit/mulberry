import type { Metadata } from "next"
import Image from "next/image"
import Link from "next/link"

import { CONTACT_EMAIL, CONTACT_MAILTO } from "@/lib/constants"

export const metadata: Metadata = {
  title: "Delete Your Account | Mulberry",
  description:
    "How to delete your Mulberry account and related app data.",
  alternates: {
    canonical: "/delete-account"
  }
}

const inAppSteps = [
  'On iPhone or iPad, open Settings in Mulberry and tap "Delete your account", below Log Out.',
  'On Mac, open Mulberry\'s Settings, choose Account, and click "Delete your account", below the Google Account section.',
  "Confirm. Your account is deleted straight away, and the device signs you out."
]

const emailSteps = [
  `Email ${CONTACT_EMAIL} from the Google account email address you use with Mulberry.`,
  'Use the subject line "Mulberry account deletion request".',
  "Include the email address on your Mulberry account and, if available, your display name in the app.",
  "Do not send your Google password, device passcode, authentication tokens, or private content.",
  "Mulberry will review the request, may ask for reasonable verification, and will confirm when the deletion has been completed, within 30 days."
]

const deletedData = [
  "Your Mulberry account, profile, and sign-in sessions on every device.",
  "Profile photos you uploaded, unless your paired partner's own profile still uses that photo.",
  "Device and Firebase Cloud Messaging tokens linked to your account.",
  "Pending invites.",
  "Your pairing, including the shared canvas, its operations and server-side snapshots, your streak and activity history together, reminders, and reactions. Your paired partner keeps their own account and is told the pairing has ended.",
  "On the iPhone, iPad, or Mac you delete from: the cached canvas, rendered wallpaper images, and account settings stored by Mulberry. On iPhone and iPad, Mulberry also asks Google to revoke its access to your Google account."
]

const retainedData = [
  "Copies in backups or service provider records are deleted or anonymized within 30 days.",
  "Product analytics events are recorded against pairing and invite identifiers rather than your account, and are kept for up to 30 days.",
  "Crash reports are not linked to your account and are kept by Firebase Crashlytics for up to 90 days.",
  `Feedback you sent through Canny stays with Canny. Email ${CONTACT_EMAIL} to have it removed.`,
  "Support and privacy request emails may be retained for up to 30 days after the request is resolved, unless a longer period is required by law or needed to protect rights, safety, or the service.",
  "Limited records may be retained for longer where required by law or needed for security, fraud prevention, dispute resolution, or enforcement.",
  "Other devices where you were signed in are signed out when they next connect, but keep what they stored locally until you delete Mulberry from them.",
  "On Mac, you can remove Mulberry's access to your Google account in your Google Account's security settings.",
  "Mulberry cannot delete screenshots, recordings, exports, or other copies saved outside Mulberry by you, your paired partner, your device, or third-party services."
]

export default function DeleteAccountPage() {
  return (
    <main className="min-h-screen bg-background text-foreground">
      <SiteHeader />

      <section className="mx-auto max-w-6xl px-5 pb-14 pt-10 sm:px-8 lg:px-10 lg:pt-16">
        <div className="bg-brand px-6 py-10 text-white sm:px-10 lg:px-14 lg:py-14">
          <p className="text-sm font-semibold uppercase text-white/68">
            Account deletion
          </p>
          <h1 className="mt-5 max-w-3xl text-5xl font-semibold leading-tight text-white sm:text-6xl">
            Delete your Mulberry account
          </h1>
          <p className="mt-6 max-w-3xl text-base leading-8 text-white/76 sm:text-lg">
            This page explains how users of Mulberry, developed by Subhajit
            Kundu, can delete their account and related app data.
          </p>
          <div className="mt-8 flex flex-col gap-3 text-sm leading-7 text-white/78 sm:flex-row sm:items-center">
            <span className="font-medium text-white">Deletion request email:</span>
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
              <li>
                <Link
                  href="#request"
                  className="block px-3 py-2 transition hover:bg-soft-surface hover:text-brand"
                >
                  How to delete your account
                </Link>
              </li>
              <li>
                <Link
                  href="#deleted"
                  className="block px-3 py-2 transition hover:bg-soft-surface hover:text-brand"
                >
                  Data deleted
                </Link>
              </li>
              <li>
                <Link
                  href="#retained"
                  className="block px-3 py-2 transition hover:bg-soft-surface hover:text-brand"
                >
                  Data retained
                </Link>
              </li>
            </ol>
          </nav>
        </aside>

        <article className="grid gap-14">
          <section id="request" className="scroll-mt-8">
            <h2 className="text-3xl font-semibold leading-tight text-foreground sm:text-4xl">
              How to delete your account
            </h2>
            <h3 className="mt-8 text-xl font-semibold text-foreground">
              In the app on iPhone, iPad, and Mac
            </h3>
            <StepList steps={inAppSteps} />
            <h3 className="mt-10 text-xl font-semibold text-foreground">
              By email, from Android or without a signed-in device
            </h3>
            <p className="mt-4 max-w-3xl text-base leading-8 text-muted-foreground">
              Send an email to{" "}
              <Link href={CONTACT_MAILTO} className="font-medium text-brand">
                {CONTACT_EMAIL}
              </Link>
              . Email requests are handled manually so Mulberry can verify the
              account owner before deleting account data.
            </p>
            <StepList steps={emailSteps} />
          </section>

          <section id="deleted" className="scroll-mt-8">
            <h2 className="text-3xl font-semibold leading-tight text-foreground sm:text-4xl">
              Data deleted or anonymized
            </h2>
            <p className="mt-5 max-w-3xl text-base leading-8 text-muted-foreground">
              Deleting your account in the app removes the following from
              Mulberry straight away. Email requests are completed within 30
              days of verification. Deletion cannot be undone.
            </p>
            <DataList items={deletedData} />
          </section>

          <section id="retained" className="scroll-mt-8">
            <h2 className="text-3xl font-semibold leading-tight text-foreground sm:text-4xl">
              Data kept and retention periods
            </h2>
            <p className="mt-5 max-w-3xl text-base leading-8 text-muted-foreground">
              Some information may remain for a limited period or may remain
              outside Mulberry&apos;s control.
            </p>
            <DataList items={retainedData} />
          </section>
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

function DataList({ items }: { items: string[] }) {
  return (
    <ul className="mt-6 grid gap-2 bg-soft-surface p-5 text-base leading-7 text-soft-surface-foreground">
      {items.map((item) => (
        <li key={item} className="flex gap-3">
          <span className="mt-3 size-1.5 shrink-0 rounded-full bg-brand" />
          <span>{item}</span>
        </li>
      ))}
    </ul>
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
