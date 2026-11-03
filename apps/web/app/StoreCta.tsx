import * as React from "react"
import Image from "next/image"
import { ArrowUpRight } from "lucide-react"

import { AppleLogo } from "@/components/apple-logo"
import { Button } from "@/components/ui/button"
import { APP_STORE_DOWNLOAD_URL, GOOGLE_PLAY_DOWNLOAD_URL } from "@/lib/constants"
import type { AppStoreName } from "@/lib/store"

type StoreLink = {
  href: string
  name: string
  alsoLabel: string
  icon: React.ReactNode
}

const appStore: StoreLink = {
  href: APP_STORE_DOWNLOAD_URL,
  name: "App Store",
  alsoLabel: "Also on the App Store",
  icon: <AppleLogo className="size-5" />
}

const googlePlay: StoreLink = {
  href: GOOGLE_PLAY_DOWNLOAD_URL,
  name: "Google Play",
  alsoLabel: "Also on Google Play",
  icon: <Image src="/brand/google-play.svg" alt="" width={26} height={26} className="size-6" />
}

function StoreButton({ store }: { store: StoreLink }) {
  return (
    <Button asChild size="lg" className="bg-white text-[#090d18] shadow-none hover:bg-white/90 hover:shadow-none">
      <a href={store.href}>
        {store.icon}
        {store.name}
      </a>
    </Button>
  )
}

/** One store button for the visitor's phone, or both when the device is unknown. */
export default function StoreCta({ store }: { store: AppStoreName | null }) {
  if (store === null) {
    return (
      <div className="flex flex-wrap items-center justify-center gap-3">
        <StoreButton store={appStore} />
        <StoreButton store={googlePlay} />
      </div>
    )
  }

  const [primary, other] = store === "appStore" ? [appStore, googlePlay] : [googlePlay, appStore]

  return (
    <>
      <StoreButton store={primary} />
      <a
        href={other.href}
        target="_blank"
        rel="noopener noreferrer"
        className="mt-3 inline-flex items-center gap-0.5 text-sm text-white/80 transition hover:text-white"
      >
        {other.alsoLabel}
        <ArrowUpRight className="size-4" aria-hidden="true" />
      </a>
    </>
  )
}
