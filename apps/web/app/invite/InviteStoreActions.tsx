"use client"

import * as React from "react"
import Image from "next/image"

import { Button } from "@/components/ui/button"
import { APP_STORE_DOWNLOAD_URL, GOOGLE_PLAY_DOWNLOAD_URL } from "@/lib/constants"
import { inviteUrl, playStoreUrlForInvite, type InvitePlatform } from "@/lib/invite"

const storeButtonClassName = "bg-brand text-white shadow-none hover:bg-brand/90 hover:shadow-none"

function AppleLogo({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" className={className}>
      <path d="M12.152 6.896c-.948 0-2.415-1.078-3.96-1.04-2.04.027-3.91 1.183-4.961 3.014-2.117 3.675-.546 9.103 1.519 12.09 1.013 1.454 2.208 3.09 3.792 3.039 1.52-.065 2.09-.987 3.935-.987 1.831 0 2.35.987 3.96.948 1.637-.026 2.676-1.48 3.676-2.948 1.156-1.688 1.636-3.325 1.662-3.415-.039-.013-3.182-1.221-3.22-4.857-.026-3.04 2.48-4.494 2.597-4.559-1.429-2.09-3.623-2.324-4.39-2.376-2-.156-3.675 1.09-4.61 1.09zM15.53 3.83c.843-1.012 1.4-2.427 1.245-3.83-1.207.052-2.662.805-3.532 1.818-.78.896-1.454 2.338-1.273 3.714 1.338.104 2.715-.688 3.559-1.701" />
    </svg>
  )
}

/** Safari on iPad reports itself as a Mac; a touch screen gives it away. */
function useRefinedPlatform(initial: InvitePlatform): InvitePlatform {
  const [platform, setPlatform] = React.useState(initial)
  React.useEffect(() => {
    if (initial === "other" && navigator.userAgent.includes("Macintosh") && navigator.maxTouchPoints > 1) {
      setPlatform("ios")
    }
  }, [initial])
  return platform
}

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
