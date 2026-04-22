import Image from "next/image"
import Link from "next/link"
import {
  ArrowRight,
  BellRing,
  Brush,
  HeartHandshake,
  LockKeyhole,
  RefreshCcw,
  ShieldCheck,
  Sparkles,
  Wallpaper
} from "lucide-react"

import { Button } from "@/components/ui/button"

const features = [
  {
    icon: Brush,
    title: "Draw tiny moments",
    description: "Sketch a note, heart, inside joke, or doodle in a canvas built for quick gestures."
  },
  {
    icon: Wallpaper,
    title: "Appears on the lock screen",
    description: "Finished strokes sync into a live wallpaper so your partner sees the latest canvas."
  },
  {
    icon: RefreshCcw,
    title: "Near-realtime when open",
    description: "Foreground sessions stream through WebSockets, with snapshot catch-up when the app wakes."
  },
  {
    icon: ShieldCheck,
    title: "Private by default",
    description: "A one-to-one paired space, minimal payloads, and snapshot-first recovery for reliability."
  }
]

const steps = [
  "Sign in with Google",
  "Invite your partner with a short code",
  "Set Mulberry as the lock-screen wallpaper",
  "Draw. Finish. Let it appear."
]

export default function LandingPage() {
  return (
    <main className="overflow-hidden">
      <SiteHeader />
      <section className="relative mx-auto grid min-h-[calc(100vh-6rem)] w-full max-w-7xl grid-cols-1 items-center gap-12 px-5 pb-20 pt-12 sm:px-8 lg:grid-cols-[1.02fr_0.98fr] lg:px-10 lg:pt-20">
        <div className="absolute inset-x-0 top-0 -z-10 h-[44rem] bg-[radial-gradient(circle_at_55%_30%,rgba(179,19,41,0.12),transparent_25rem)]" />
        <div className="max-w-3xl">
          <div className="mb-7 inline-flex items-center gap-2 rounded-full border border-primary/15 bg-white/70 px-4 py-2 text-sm font-medium text-primary shadow-sm backdrop-blur">
            <Sparkles className="size-4" />
            A shared canvas that lives on your lock screen
          </div>
          <h1 className="brush-text text-5xl font-bold leading-[0.98] tracking-[-0.06em] text-foreground sm:text-7xl lg:text-[5.8rem]">
            Send small drawings that feel surprisingly close.
          </h1>
          <p className="mt-7 max-w-2xl text-lg leading-8 text-muted-foreground sm:text-xl">
            Mulberry is a private lock-screen canvas for two people. Draw something cute, finish the stroke, and let it quietly appear on your partner&apos;s phone.
          </p>
          <div className="mt-9 flex flex-col gap-3 sm:flex-row">
            <Button asChild size="lg">
              <Link href="#waitlist">
                Join the waitlist
                <ArrowRight className="size-4" />
              </Link>
            </Button>
            <Button asChild variant="outline" size="lg" className="bg-white/75">
              <Link href="#how-it-works">See how it works</Link>
            </Button>
          </div>
        </div>

        <HeroPhone />
      </section>

      <section id="how-it-works" className="mx-auto max-w-7xl px-5 py-20 sm:px-8 lg:px-10">
        <div className="grid gap-10 lg:grid-cols-[0.85fr_1.15fr]">
          <div>
            <p className="text-lg font-semibold text-primary">How it works</p>
            <h2 className="mt-3 text-4xl font-bold tracking-[-0.05em] sm:text-5xl">
              Built around one simple ritual.
            </h2>
            <p className="mt-5 text-lg leading-8 text-muted-foreground">
              Mulberry avoids social feeds, reactions, and noise. The product is intentionally small: one person, one canvas, one lock screen.
            </p>
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            {steps.map((step, index) => (
              <div key={step} className="rounded-[2rem] border bg-white/75 p-6 shadow-sm backdrop-blur">
                <div className="mb-7 flex size-11 items-center justify-center rounded-full bg-secondary text-lg font-bold text-primary">
                  {index + 1}
                </div>
                <p className="text-xl font-semibold tracking-[-0.03em]">{step}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      <section className="mx-auto max-w-7xl px-5 py-12 sm:px-8 lg:px-10">
        <div className="grid gap-5 md:grid-cols-2 lg:grid-cols-4">
          {features.map((feature) => (
            <article key={feature.title} className="group rounded-[2rem] border bg-white p-6 shadow-sm transition hover:-translate-y-1 hover:shadow-xl hover:shadow-primary/10">
              <feature.icon className="mb-8 size-8 text-primary" />
              <h3 className="text-xl font-semibold tracking-[-0.04em]">{feature.title}</h3>
              <p className="mt-3 text-sm leading-6 text-muted-foreground">{feature.description}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="mx-auto max-w-7xl px-5 py-20 sm:px-8 lg:px-10">
        <div className="overflow-hidden rounded-[2.5rem] bg-[#0b0207] text-white shadow-2xl shadow-primary/20">
          <div className="grid gap-0 lg:grid-cols-[0.9fr_1.1fr]">
            <div className="p-8 sm:p-12 lg:p-16">
              <p className="font-semibold text-[#ffb1ba]">Designed for background sync</p>
              <h2 className="mt-4 text-4xl font-bold tracking-[-0.05em] sm:text-5xl">
                Foreground realtime. Background snapshots.
              </h2>
              <p className="mt-5 text-lg leading-8 text-white/70">
                The app streams strokes when both people are active and uses snapshot-first recovery when one phone is asleep. That keeps the wallpaper current without pretending background delivery is realtime.
              </p>
              <div className="mt-8 grid gap-3 text-sm text-white/78">
                <div className="flex items-center gap-3">
                  <BellRing className="size-4 text-[#ffbd66]" />
                  FCM wakes the app only for durable canvas updates.
                </div>
                <div className="flex items-center gap-3">
                  <LockKeyhole className="size-4 text-[#ffbd66]" />
                  Wallpaper reads cached local state, not active sockets.
                </div>
                <div className="flex items-center gap-3">
                  <HeartHandshake className="size-4 text-[#ffbd66]" />
                  Pairing stays one-to-one and intentionally personal.
                </div>
              </div>
            </div>
            <div className="relative min-h-[28rem]">
              <Image
                src="/brand/auth-login-bg.png"
                alt="Mulberry red brush background"
                fill
                className="object-cover"
                sizes="(min-width: 1024px) 50vw, 100vw"
              />
              <div className="absolute inset-0 bg-gradient-to-r from-[#0b0207] via-[#0b0207]/45 to-transparent" />
              <div className="absolute bottom-8 left-8 right-8 rounded-[2rem] border border-white/10 bg-white/10 p-6 backdrop-blur-md">
                <p className="text-sm uppercase tracking-[0.24em] text-white/60">Latest canvas</p>
                <p className="mt-3 text-3xl font-semibold tracking-[-0.04em]">Draw them something cute</p>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section id="waitlist" className="mx-auto max-w-4xl px-5 py-24 text-center sm:px-8">
        <Image
          src="/brand/icon-color.svg"
          alt=""
          width={54}
          height={88}
          className="mx-auto mb-7 h-16 w-auto"
        />
        <h2 className="text-4xl font-bold tracking-[-0.05em] sm:text-6xl">
          A softer way to stay close.
        </h2>
        <p className="mx-auto mt-5 max-w-2xl text-lg leading-8 text-muted-foreground">
          Mulberry is currently focused on Android. The landing site is ready for product updates, waitlist capture, and app-store links when those are added.
        </p>
        <div className="mt-9 flex flex-col justify-center gap-3 sm:flex-row">
          <Button size="lg">Android coming soon</Button>
          <Button asChild variant="ghost" size="lg">
            <Link href="mailto:hello@mulberry.my">Contact the team</Link>
          </Button>
        </div>
      </section>
    </main>
  )
}

function SiteHeader() {
  return (
    <header className="mx-auto flex w-full max-w-7xl items-center justify-between px-5 py-6 sm:px-8 lg:px-10">
      <Link href="/" className="flex items-center gap-3 font-semibold tracking-[-0.03em]">
        <span className="flex size-11 items-center justify-center rounded-2xl bg-primary">
          <Image src="/brand/icon-white.svg" alt="" width={18} height={30} />
        </span>
        Mulberry
      </Link>
      <nav className="hidden items-center gap-8 text-sm font-medium text-muted-foreground sm:flex">
        <Link href="#how-it-works" className="hover:text-foreground">How it works</Link>
        <Link href="#waitlist" className="hover:text-foreground">Waitlist</Link>
      </nav>
    </header>
  )
}

function HeroPhone() {
  return (
    <div className="relative mx-auto h-[43rem] w-full max-w-[24rem]">
      <div className="absolute inset-x-8 bottom-5 h-12 rounded-full bg-primary/20 blur-3xl" />
      <div className="relative h-full rounded-[3rem] border-[10px] border-[#111116] bg-[#111116] shadow-2xl shadow-primary/25">
        <div className="absolute left-1/2 top-4 z-20 h-6 w-24 -translate-x-1/2 rounded-full bg-[#111116]" />
        <div className="relative h-full overflow-hidden rounded-[2.25rem] bg-white">
          <Image
            src="/wallpapers/default-preview.jpg"
            alt="Mulberry lock screen wallpaper preview"
            fill
            priority
            className="object-cover"
            sizes="384px"
          />
          <div className="absolute inset-0 bg-gradient-to-b from-black/30 via-transparent to-black/30" />
          <div className="absolute left-7 top-14 text-white">
            <p className="text-5xl font-bold tracking-[-0.08em]">9:41</p>
            <p className="mt-1 text-sm font-medium">Wednesday, April 22</p>
          </div>
          <div className="absolute inset-x-8 top-[48%] rounded-[2rem] border border-white/25 bg-white/10 p-6 text-center text-white backdrop-blur-[2px]">
            <p className="text-2xl font-semibold tracking-[-0.05em] text-[#ffe6ea]">hello :)</p>
            <p className="mt-3 text-sm text-white/72">Whatever you draw appears here.</p>
          </div>
          <div className="absolute bottom-8 left-1/2 h-1.5 w-28 -translate-x-1/2 rounded-full bg-white/85" />
        </div>
      </div>
    </div>
  )
}
