import type { Metadata } from "next"
import Image from "next/image"
import Link from "next/link"

import { readLegalDocument } from "@/lib/legal"

export const metadata: Metadata = {
  title: "Privacy Policy | Mulberry",
  description: "Read the Mulberry Privacy Policy."
}

export default function PrivacyPolicyPage() {
  const policy = readLegalDocument("privacy_policy")

  return (
    <main className="min-h-screen bg-background text-foreground">
      <SiteHeader />

      <section className="mx-auto max-w-6xl px-5 pb-16 pt-10 sm:px-8 lg:px-10 lg:pt-16">
        <div className="rounded-[2.5rem] bg-brand px-6 py-10 text-white sm:px-10 lg:px-14 lg:py-14">
          <p className="text-sm font-semibold uppercase tracking-[0.26em] text-white/68">
            Legal
          </p>
          <h1 className="mt-5 max-w-3xl text-5xl font-semibold leading-tight tracking-[-0.06em] sm:text-6xl">
            {policy.title}
          </h1>
          <p className="mt-6 max-w-3xl text-base leading-8 text-white/76 sm:text-lg">
            {policy.intro[0]}
          </p>

          <dl className="mt-10 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <PolicyMeta label="Effective date" value={policy.details["Effective date"] ?? ""} />
            <PolicyMeta label="Last updated" value={policy.details["Last updated"] ?? ""} />
            <PolicyMeta label="Operator" value={policy.details.Operator ?? ""} />
            <PolicyMeta label="Privacy contact" value={policy.details["Privacy contact"] ?? ""} />
          </dl>
        </div>
      </section>

      <section className="mx-auto grid max-w-6xl gap-10 px-5 pb-24 sm:px-8 lg:grid-cols-[16rem_1fr] lg:px-10">
        <aside className="hidden lg:block">
          <nav className="sticky top-8 text-sm">
            <p className="mb-4 font-semibold text-foreground">Contents</p>
            <ol className="grid gap-2 text-muted-foreground">
              {policy.sections.map((section) => (
                <li key={section.id}>
                  <Link
                    href={`#${section.id}`}
                    className="block rounded-xl px-3 py-2 transition hover:bg-soft-surface hover:text-brand"
                  >
                    {section.title.replace(/^\d+\.\s*/, "")}
                  </Link>
                </li>
              ))}
            </ol>
          </nav>
        </aside>

        <article className="grid gap-14">
          <section>
            <h2 className="text-2xl font-semibold tracking-[-0.04em] text-foreground">
              Overview
            </h2>
            <div className="mt-5 grid gap-4 text-base leading-8 text-muted-foreground">
              {policy.intro.slice(1).map((paragraph) => (
                <p key={paragraph}>{paragraph}</p>
              ))}
            </div>
            <address className="mt-6 not-italic text-sm leading-7 text-muted-foreground">
              <span className="font-medium text-foreground">Address:</span>{" "}
              {policy.details.Address}
            </address>
          </section>

          {policy.sections.map((section) => (
            <section
              key={section.id}
              id={section.id}
              className="scroll-mt-8"
            >
              <h2 className="text-3xl font-semibold leading-tight tracking-[-0.055em] text-foreground sm:text-4xl">
                {section.title}
              </h2>
              <div className="mt-6 grid gap-5">
                {section.blocks.map((block, index) => {
                  if (block.type === "subheading") {
                    return (
                      <h3
                        key={`${block.text}-${index}`}
                        className="pt-3 text-xl font-semibold tracking-[-0.035em] text-foreground"
                      >
                        {block.text}
                      </h3>
                    )
                  }

                  if (block.type === "list") {
                    return (
                      <ul
                        key={`list-${index}`}
                        className="grid gap-2 rounded-[1.35rem] bg-soft-surface p-5 text-base leading-7 text-soft-surface-foreground"
                      >
                        {block.items.map((item) => (
                          <li key={item} className="flex gap-3">
                            <span className="mt-3 size-1.5 shrink-0 rounded-full bg-brand" />
                            <span>{item}</span>
                          </li>
                        ))}
                      </ul>
                    )
                  }

                  return (
                    <p
                      key={`${block.text}-${index}`}
                      className="text-base leading-8 text-muted-foreground"
                    >
                      {block.text}
                    </p>
                  )
                })}
              </div>
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
        href="/"
        className="inline-flex min-h-11 items-center rounded-full px-2 text-sm font-medium text-muted-foreground transition hover:text-brand"
      >
        Home
      </Link>
    </header>
  )
}

function PolicyMeta({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-[1.35rem] bg-white/12 p-4">
      <dt className="text-xs font-semibold uppercase tracking-[0.18em] text-white/52">
        {label}
      </dt>
      <dd className="mt-2 text-sm font-medium leading-6 text-white">{value}</dd>
    </div>
  )
}
