"use client"

import * as React from "react"
import { CheckIcon, CopyIcon } from "lucide-react"

import { cn } from "@/lib/utils"

export default function InviteCodeCopyButton({
  code,
  className
}: {
  code: string
  className?: string
}) {
  const [copied, setCopied] = React.useState(false)

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(code)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      // Ignore; if clipboard fails (permissions), leave the button as-is.
    }
  }

  return (
    <button
      type="button"
      onClick={copy}
      aria-label={copied ? "Copied" : "Copy code"}
      disabled={copied}
      className={cn(
        "relative inline-flex cursor-pointer items-center justify-center bg-white text-[#090d18] shadow-none transition-all duration-200 ease-out hover:bg-white/90 active:scale-[0.97] disabled:pointer-events-none disabled:opacity-100",
        className
      )}
    >
      <div
        className={cn(
          "transition-all duration-200",
          copied
            ? "scale-100 opacity-100 blur-none"
            : "scale-70 opacity-0 blur-[2px]"
        )}
      >
        <CheckIcon size={20} strokeWidth={2} aria-hidden="true" />
      </div>
      <div
        className={cn(
          "absolute transition-all duration-200",
          copied ? "scale-0 opacity-0 blur-[2px]" : "scale-100 opacity-100 blur-none"
        )}
      >
        <CopyIcon size={20} strokeWidth={2} aria-hidden="true" />
      </div>
    </button>
  )
}
