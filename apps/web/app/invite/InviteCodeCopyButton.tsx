"use client"

import { useState } from "react"

import { Button } from "@/components/ui/button"

export default function InviteCodeCopyButton({ code }: { code: string }) {
  const [status, setStatus] = useState<"idle" | "copied" | "error">("idle")

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(code)
      setStatus("copied")
      window.setTimeout(() => setStatus("idle"), 1200)
    } catch {
      setStatus("error")
      window.setTimeout(() => setStatus("idle"), 1600)
    }
  }

  const label =
    status === "copied" ? "Copied" : status === "error" ? "Copy failed" : "Copy code"

  return (
    <Button type="button" onClick={copy} className="bg-white text-[#090d18] hover:bg-white/90">
      {label}
    </Button>
  )
}

