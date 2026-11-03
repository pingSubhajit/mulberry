"use client"

import * as React from "react"
import Image from "next/image"

import { AppleLogo } from "@/components/apple-logo"
import { Button } from "@/components/ui/button"
import { APP_STORE_DOWNLOAD_URL, GOOGLE_PLAY_DOWNLOAD_URL } from "@/lib/constants"
import { inviteUrl, playStoreUrlForInvite, type InvitePlatform } from "@/lib/invite"
import { useRefinedPlatform } from "@/lib/use-refined-platform"

const storeButtonClassName = "bg-brand text-white shadow-none hover:bg-brand/90 hover:shadow-none"

function noteFor(platform: InvitePlatform, hasCode: boolean): string | null {
  if (!hasCode) return null
  switch (platform) {
    case "ios":
      return "Get Mulberry from the App Store. After you sign in, tap Paste to join your partner."
    case "android":
      return "Install Mulberry from Google Play. When you open it, setup continues automatically."
    case "other":
      return "Open this link on your phone to get Mulberry and join your partner."
  }
}

export default function InviteStoreActions({
  code,
  initialPlatform
}: {
  code: string | null
  initialPlatform: InvitePlatform
}) {
  const platform = useRefinedPlatform(initialPlatform)
  const note = noteFor(platform, code !== null)

  // iOS can't carry the code through an App Store install, so the link goes on the clipboard
  // and the app offers to paste it once the partner has signed in.
  const openAppStore = async (event: React.MouseEvent<HTMLAnchorElement>) => {
    if (!code || platform !== "ios") return
    event.preventDefault()
    try {
      await navigator.clipboard.writeText(inviteUrl(code))
    } catch {
      // The code is still on the page to type in.
    }
    window.location.href = APP_STORE_DOWNLOAD_URL
  }

  const appStoreButton = (
    <Button key="ios" asChild size="lg" className={storeButtonClassName}>
      <a href={APP_STORE_DOWNLOAD_URL} onClick={openAppStore}>
        <AppleLogo className="size-5" />
        Download on the App Store
      </a>
    </Button>
  )

  const playStoreButton = (
    <Button key="android" asChild size="lg" className={storeButtonClassName}>
      <a
        href={code ? playStoreUrlForInvite(code) : GOOGLE_PLAY_DOWNLOAD_URL}
        target="_blank"
        rel="noopener noreferrer"
      >
        <Image
          src="/brand/google-play.svg"
          alt=""
          width={26}
          height={26}
          className="size-6 brightness-0 invert"
        />
        Get it on Google Play
      </a>
    </Button>
  )

  const buttons =
    platform === "ios" ? [appStoreButton] : platform === "android" ? [playStoreButton] : [appStoreButton, playStoreButton]

  return (
    <>
      {note ? (
        <p className="mx-auto mt-5 max-w-xl text-center text-sm leading-6 text-muted-foreground lg:mx-0 lg:text-left">
          {note}
        </p>
      ) : null}
      <div className="mt-7 flex flex-col items-center gap-3 sm:flex-row sm:justify-center lg:items-start lg:justify-start">
        {buttons}
      </div>
    </>
  )
}
