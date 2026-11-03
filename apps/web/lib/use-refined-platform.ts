import * as React from "react"

import type { InvitePlatform } from "@/lib/invite"

/** Safari on iPad reports itself as a Mac; a touch screen gives it away. */
export function useRefinedPlatform(initial: InvitePlatform): InvitePlatform {
  const [platform, setPlatform] = React.useState(initial)
  React.useEffect(() => {
    if (initial === "other" && navigator.userAgent.includes("Macintosh") && navigator.maxTouchPoints > 1) {
      setPlatform("ios")
    }
  }, [initial])
  return platform
}
