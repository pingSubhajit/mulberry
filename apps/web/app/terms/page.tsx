import type { Metadata } from "next"
import Image from "next/image"
import Link from "next/link"

export const metadata: Metadata = {
  title: "Terms of Use | Mulberry",
  description: "Read the Mulberry Terms of Use.",
  alternates: {
    canonical: "/terms"
  },
  robots: {
    index: false,
    follow: false
  }
}

type TermsBlock =
  | { type: "paragraph"; text: string }
  | { type: "list"; items: string[] }

type TermsSection = {
  id: string
  title: string
  blocks: TermsBlock[]
}

const terms = {
  title: "Mulberry Terms of Use",
  effectiveDate: "April 23, 2026",
  lastUpdated: "April 23, 2026",
  operator: "Subhajit Kundu",
  address: "ITI Crossing, Kalyani, West Bengal, India, 741235",
  contact: "subha60kundu@gmail.com",
  intro:
    "These Terms of Use explain the rules for using Mulberry, a private Android app for two people to share a drawing canvas that can appear on each other's lock screen.",
  sections: [
    {
      id: "acceptance",
      title: "1. Acceptance of these terms",
      blocks: [
        {
          type: "paragraph",
          text:
            "By installing, accessing, signing in to, pairing through, or using Mulberry, you agree to these Terms of Use and to the Mulberry Privacy Policy. If you do not agree, do not use Mulberry."
        },
        {
          type: "paragraph",
          text:
            "If you use Mulberry on behalf of another person or organization, you confirm that you have authority to accept these terms for them. Mulberry is primarily intended for personal use by individual users."
        }
      ]
    },
    {
      id: "eligibility",
      title: "2. Eligibility and India-only launch",
      blocks: [
        {
          type: "paragraph",
          text:
            "Mulberry is intended for users in India and is not intended for users under 18 years old. By using Mulberry, you confirm that you are at least 18 years old and that you are legally able to agree to these terms."
        },
        {
          type: "paragraph",
          text:
            "If Mulberry becomes available outside India, these terms may be updated before that expansion."
        }
      ]
    },
    {
      id: "service",
      title: "3. The Mulberry service",
      blocks: [
        {
          type: "paragraph",
          text:
            "Mulberry provides a paired-device experience where two users can share a private drawing canvas. Shared drawings may sync between paired users and may appear locally through the Android live wallpaper experience."
        },
        {
          type: "paragraph",
          text:
            "Mulberry is not a public social network, publishing platform, marketplace, payment service, emergency communication tool, or backup service. Background sync, push delivery, wallpapers, and device behavior may depend on Android, Google services, network availability, and device settings."
        }
      ]
    },
    {
      id: "accounts",
      title: "4. Accounts, sign-in, and security",
      blocks: [
        {
          type: "paragraph",
          text:
            "Mulberry uses Google Sign-In and Mulberry-created session tokens to authenticate users. You are responsible for keeping your Google account, device, passcode, and Mulberry session secure."
        },
        {
          type: "list",
          items: [
            "Do not share your account or authentication tokens with anyone.",
            "Do not try to access another person's account, device, pair session, invite, or canvas without permission.",
            "Tell Mulberry promptly if you believe your account, invite code, or pair session has been misused."
          ]
        }
      ]
    },
    {
      id: "pairing",
      title: "5. Pairing, invite codes, and shared canvas content",
      blocks: [
        {
          type: "paragraph",
          text:
            "Mulberry invite codes are used to connect exactly two users into a private pair session. You should share invite codes only with the person you intend to pair with. Anyone with a valid invite code may be able to start the pairing flow."
        },
        {
          type: "paragraph",
          text:
            "The shared canvas is visible to both paired users. Either paired user may draw, erase, delete strokes, clear the canvas, or otherwise change the shared canvas where the app allows those actions."
        },
        {
          type: "paragraph",
          text:
            "Mulberry cannot control what your paired partner does after viewing shared content, including screenshots, screen recordings, photos of a device, or sharing outside Mulberry. Do not put anything on the shared canvas that you do not want your paired partner to see or save."
        }
      ]
    },
    {
      id: "user-content",
      title: "6. Your content and Mulberry's operating license",
      blocks: [
        {
          type: "paragraph",
          text:
            "You keep ownership of drawings, messages, names, dates, images, and other content that you provide to Mulberry, subject to any rights held by others."
        },
        {
          type: "paragraph",
          text:
            "You give Mulberry a limited, worldwide, non-exclusive, royalty-free license to host, store, process, transmit, display, reproduce, modify for technical formatting, and otherwise use your content only as needed to operate, secure, sync, support, and improve Mulberry."
        },
        {
          type: "paragraph",
          text:
            "You confirm that you have the rights needed to provide any content you add to Mulberry and that your content does not violate these terms or applicable law."
        }
      ]
    },
    {
      id: "acceptable-use",
      title: "7. Acceptable use",
      blocks: [
        {
          type: "paragraph",
          text:
            "You agree to use Mulberry respectfully, lawfully, and only for its intended paired personal communication purpose."
        },
        {
          type: "list",
          items: [
            "Do not harass, threaten, exploit, impersonate, or harm another person.",
            "Do not upload, draw, send, or display illegal, non-consensual, abusive, hateful, sexually exploitative, or infringing content.",
            "Do not use Mulberry to stalk, monitor, pressure, or control another person.",
            "Do not reverse engineer, scrape, interfere with, overload, bypass, or attack Mulberry systems.",
            "Do not use automation, fake accounts, stolen credentials, or unauthorized tokens.",
            "Do not remove, obscure, or misuse Mulberry branding, notices, or security features."
          ]
        }
      ]
    },
    {
      id: "wallpaper",
      title: "8. Device storage, wallpapers, and local data",
      blocks: [
        {
          type: "paragraph",
          text:
            "Mulberry may store account state, pairing state, canvas state, tokens, cached files, wallpaper backgrounds, and app preferences locally on your device. Your device-local wallpaper background is intended to remain on your device unless you share it outside Mulberry or add it to shared content yourself."
        },
        {
          type: "paragraph",
          text:
            "You are responsible for your device settings, lock screen visibility, screenshots, backups outside Mulberry, and any manual sharing or exporting you perform."
        }
      ]
    },
    {
      id: "third-party-services",
      title: "9. Third-party services and app store terms",
      blocks: [
        {
          type: "paragraph",
          text:
            "Mulberry depends on third-party services, including Google Sign-In, Android platform services, Firebase Cloud Messaging, Firebase Crashlytics, Google Analytics, Sentry, Better Stack Logs, PostHog, Supabase, Railway, and Google Play distribution. Those services may have their own terms and policies."
        },
        {
          type: "paragraph",
          text:
            "Your use of Google Play, Android, Google accounts, or other third-party services is governed by the applicable third-party terms. Mulberry is not responsible for third-party service outages, account decisions, platform restrictions, or policy changes."
        }
      ]
    },
    {
      id: "fees",
      title: "10. Fees and paid features",
      blocks: [
        {
          type: "paragraph",
          text:
            "Mulberry does not currently describe paid features, subscriptions, or in-app purchases in these terms. If paid features are introduced, applicable prices, billing terms, renewal rules, cancellation rules, and refund rules will be presented before purchase or in updated terms."
        }
      ]
    },
    {
      id: "intellectual-property",
      title: "11. Mulberry intellectual property",
      blocks: [
        {
          type: "paragraph",
          text:
            "Mulberry, including the app, website, backend services, logos, wordmarks, visual design, software, source code, documentation, and related materials, is owned by Subhajit Kundu or licensed to Mulberry."
        },
        {
          type: "paragraph",
          text:
            "These terms do not give you ownership of Mulberry or permission to copy, modify, distribute, sell, sublicense, or create derivative works from Mulberry except as allowed by law or by written permission."
        },
        {
          type: "paragraph",
          text:
            "If you send feedback, ideas, bug reports, or suggestions, Mulberry may use them without restriction or compensation to you."
        }
      ]
    },
    {
      id: "privacy",
      title: "12. Privacy",
      blocks: [
        {
          type: "paragraph",
          text:
            "The Mulberry Privacy Policy explains how information is collected, used, stored, shared, and protected. By using Mulberry, you also acknowledge the Privacy Policy."
        }
      ]
    },
    {
      id: "availability",
      title: "13. Service changes, availability, and updates",
      blocks: [
        {
          type: "paragraph",
          text:
            "Mulberry may change, suspend, discontinue, limit, or update parts of the service at any time. Features may be experimental, may not work on all devices, and may depend on app version, backend availability, network access, and platform permissions."
        },
        {
          type: "paragraph",
          text:
            "You are responsible for installing updates needed for security, compatibility, bug fixes, and continued access. Older app versions may stop working."
        }
      ]
    },
    {
      id: "termination",
      title: "14. Suspension and termination",
      blocks: [
        {
          type: "paragraph",
          text:
            "You may stop using Mulberry at any time. You may also use available account deletion flows or contact Mulberry to request account deletion."
        },
        {
          type: "paragraph",
          text:
            "Mulberry may suspend, limit, or terminate access if you violate these terms, create legal or security risk, misuse the service, infringe rights, threaten safety, or if continued operation is no longer commercially, technically, or legally practical."
        },
        {
          type: "paragraph",
          text:
            "After termination or deletion, some provisions will continue where reasonably necessary, including intellectual property, privacy, disclaimers, liability limits, dispute terms, and provisions needed to enforce these terms."
        }
      ]
    },
    {
      id: "disclaimers",
      title: "15. Disclaimers",
      blocks: [
        {
          type: "paragraph",
          text:
            "Mulberry is provided on an as-is and as-available basis. To the maximum extent permitted by law, Mulberry disclaims all warranties, whether express, implied, statutory, or otherwise, including warranties of merchantability, fitness for a particular purpose, title, non-infringement, availability, security, reliability, and error-free operation."
        },
        {
          type: "paragraph",
          text:
            "Mulberry does not guarantee that drawings, sync events, push signals, wallpaper updates, notifications, accounts, or local storage will always be accurate, uninterrupted, timely, secure, or recoverable."
        }
      ]
    },
    {
      id: "liability",
      title: "16. Limitation of liability",
      blocks: [
        {
          type: "paragraph",
          text:
            "To the maximum extent permitted by law, Mulberry and its operator will not be liable for indirect, incidental, special, consequential, exemplary, or punitive damages, or for loss of data, content, profits, goodwill, device functionality, or emotional distress arising from or related to your use of Mulberry."
        },
        {
          type: "paragraph",
          text:
            "To the maximum extent permitted by law, Mulberry's total liability for claims arising from or related to the service or these terms will be limited to the greater of the amount you paid to Mulberry for the service in the three months before the claim or INR 1,000."
        },
        {
          type: "paragraph",
          text:
            "Nothing in these terms limits liability where the law does not allow that limitation."
        }
      ]
    },
    {
      id: "indemnity",
      title: "17. Your responsibility for claims",
      blocks: [
        {
          type: "paragraph",
          text:
            "To the extent permitted by law, you agree to defend, indemnify, and hold harmless Mulberry and its operator from claims, losses, liabilities, damages, costs, and expenses arising from your content, your misuse of Mulberry, your violation of these terms, your violation of law, or your violation of another person's rights."
        }
      ]
    },
    {
      id: "law",
      title: "18. Governing law and disputes",
      blocks: [
        {
          type: "paragraph",
          text:
            "These terms are governed by the laws of India, without regard to conflict-of-law rules. Subject to applicable law, courts in West Bengal, India will have jurisdiction over disputes arising from or related to these terms or Mulberry."
        },
        {
          type: "paragraph",
          text:
            "Before filing a formal claim, you agree to contact Mulberry and try to resolve the dispute informally. This does not prevent either party from seeking urgent legal relief where necessary."
        }
      ]
    },
    {
      id: "changes",
      title: "19. Changes to these terms",
      blocks: [
        {
          type: "paragraph",
          text:
            "Mulberry may update these terms from time to time. If changes are material, Mulberry will provide notice by email at least 30 days before the material changes take effect, unless a shorter period is required for legal, security, or operational reasons."
        },
        {
          type: "paragraph",
          text:
            "The Last updated date at the top of this page shows when these terms were last changed. Continued use after updated terms take effect means you accept the updated terms."
        }
      ]
    },
    {
      id: "contact",
      title: "20. Contact",
      blocks: [
        {
          type: "paragraph",
          text:
            "For questions about these terms, account deletion, support, complaints, or legal notices, contact Subhajit Kundu at subha60kundu@gmail.com or by mail at ITI Crossing, Kalyani, West Bengal, India, 741235."
        }
      ]
    }
  ] satisfies TermsSection[]
}

export default function TermsPage() {
  return (
    <main className="min-h-screen bg-background text-foreground">
      <SiteHeader />

      <section className="mx-auto max-w-6xl px-5 pb-16 pt-10 sm:px-8 lg:px-10 lg:pt-16">
        <div className="rounded-[2.5rem] bg-brand px-6 py-10 text-white sm:px-10 lg:px-14 lg:py-14">
          <p className="text-sm font-semibold uppercase tracking-[0.26em] text-white/68">
            Legal
          </p>
          <h1 className="mt-5 max-w-3xl text-5xl font-semibold leading-tight text-white sm:text-6xl">
            {terms.title}
          </h1>
          <p className="mt-6 max-w-3xl text-base leading-8 text-white/76 sm:text-lg">
            {terms.intro}
          </p>

          <dl className="mt-10 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <TermsMeta label="Effective date" value={terms.effectiveDate} />
            <TermsMeta label="Last updated" value={terms.lastUpdated} />
            <TermsMeta label="Operator" value={terms.operator} />
            <TermsMeta label="Terms contact" value={terms.contact} />
          </dl>
        </div>
      </section>

      <section className="mx-auto grid max-w-6xl gap-10 px-5 pb-24 sm:px-8 lg:grid-cols-[16rem_1fr] lg:px-10">
        <aside className="hidden lg:block">
          <nav className="sticky top-8 text-sm">
            <p className="mb-4 font-semibold text-foreground">Contents</p>
            <ol className="grid gap-2 text-muted-foreground">
              {terms.sections.map((section) => (
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
            <h2 className="text-2xl font-semibold text-foreground">
              Overview
            </h2>
            <div className="mt-5 grid gap-4 text-base leading-8 text-muted-foreground">
              <p>
                These terms are written for Mulberry's current India-only,
                two-person Android app launch and should be read with the{" "}
                <Link href="/privacy" className="font-medium text-brand">
                  Privacy Policy
                </Link>
                .
              </p>
            </div>
            <address className="mt-6 not-italic text-sm leading-7 text-muted-foreground">
              <span className="font-medium text-foreground">Address:</span>{" "}
              {terms.address}
            </address>
          </section>

          {terms.sections.map((section) => (
            <section
              key={section.id}
              id={section.id}
              className="scroll-mt-8"
            >
              <h2 className="text-3xl font-semibold leading-tight text-foreground sm:text-4xl">
                {section.title}
              </h2>
              <div className="mt-6 grid gap-5">
                {section.blocks.map((block, index) => {
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

function TermsMeta({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-[1.35rem] bg-white/12 p-4">
      <dt className="text-xs font-semibold uppercase tracking-[0.18em] text-white/52">
        {label}
      </dt>
      <dd className="mt-2 text-sm font-medium leading-6 text-white">{value}</dd>
    </div>
  )
}
