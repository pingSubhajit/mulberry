"use client"

import { Frame, ImageIcon, Lock, Smartphone, Sticker, type LucideIcon } from "lucide-react"
import type { Route } from "next"
import Link from "next/link"
import { usePathname } from "next/navigation"
import { createContext, FormEvent, useCallback, useContext, useEffect, useMemo, useState } from "react"

import { API_BASE_URL } from "@/lib/constants"
import { cn } from "@/lib/utils"

import { AdminButton, fieldClass, IconButton, Spinner, StatusLine } from "./ui"

const PASSWORD_STORAGE_KEY = "mulberry.adminPassword"
/** Where the wallpaper and sticker pages kept the password before they shared one. */
const LEGACY_PASSWORD_STORAGE_KEYS = ["mulberry.wallpaperAdminPassword", "mulberry.stickerAdminPassword"]

type NavItem = { href: Route; label: string; icon: LucideIcon }

const NAV_GROUPS: readonly { label: string; items: readonly NavItem[] }[] = [
  { label: "Releases", items: [{ href: "/admin/app-versions", label: "App versions", icon: Smartphone }] },
  {
    label: "Catalog",
    items: [
      { href: "/admin/stickers", label: "Stickers", icon: Sticker },
      { href: "/admin/wallpapers", label: "Wallpapers", icon: ImageIcon },
      { href: "/admin/share-frames", label: "Share frames", icon: Frame }
    ]
  }
]

const NAV_ITEMS: readonly NavItem[] = NAV_GROUPS.flatMap((group) => group.items)

const navRowClass = cn(
  "flex h-8 shrink-0 items-center gap-2.5 rounded-ctl px-2 text-sm text-admin-fg-2",
  "transition-[background-color,color] duration-150 ease-admin hover:bg-admin-fg/[0.04] hover:text-admin-fg",
  "outline-none focus-visible:ring-2 focus-visible:ring-admin-fg/20"
)

type AdminHeaders = { "x-admin-password": string }

type AdminContextValue = {
  adminHeaders: AdminHeaders
  /** Fetches an admin route, throwing the backend's message on failure and locking on a rejected password. */
  adminFetch: (path: string, init?: RequestInit) => Promise<Response>
}

type ShellState = "checking" | "locked" | "unlocked"

const AdminContext = createContext<AdminContextValue | null>(null)

export function useAdmin(): AdminContextValue {
  const context = useContext(AdminContext)
  if (!context) throw new Error("useAdmin needs AdminShell")
  return context
}

export default function AdminShell({ children }: Readonly<{ children: React.ReactNode }>) {
  const pathname = usePathname()
  const [state, setState] = useState<ShellState>("checking")
  const [password, setPassword] = useState("")
  const [passwordInput, setPasswordInput] = useState("")
  const [isChecking, setIsChecking] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const forgetPassword = () => {
    window.localStorage.removeItem(PASSWORD_STORAGE_KEY)
    for (const key of LEGACY_PASSWORD_STORAGE_KEYS) window.localStorage.removeItem(key)
  }

  const lock = useCallback(() => {
    forgetPassword()
    setPassword("")
    setPasswordInput("")
    setState("locked")
  }, [])

  const unlockWith = async (candidate: string) => {
    setError(null)
    try {
      const response = await fetch(`${API_BASE_URL}/admin/session`, {
        cache: "no-store",
        headers: { "x-admin-password": candidate }
      })
      if (!response.ok) throw new Error(await errorMessage(response, "Unable to unlock admin"))
      forgetPassword()
      window.localStorage.setItem(PASSWORD_STORAGE_KEY, candidate)
      setPassword(candidate)
      setState("unlocked")
    } catch (err) {
      forgetPassword()
      setState("locked")
      setError(err instanceof Error ? err.message : "Unable to unlock admin")
    }
  }

  useEffect(() => {
    const stored = [PASSWORD_STORAGE_KEY, ...LEGACY_PASSWORD_STORAGE_KEYS]
      .map((key) => window.localStorage.getItem(key))
      .find((value): value is string => Boolean(value))
    if (stored) void unlockWith(stored)
    else setState("locked")
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const onUnlock = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const candidate = passwordInput.trim()
    if (!candidate) return
    setIsChecking(true)
    void unlockWith(candidate).finally(() => setIsChecking(false))
  }

  const context = useMemo<AdminContextValue>(() => {
    const adminHeaders: AdminHeaders = { "x-admin-password": password }
    return {
      adminHeaders,
      adminFetch: async (path, init) => {
        const response = await fetch(`${API_BASE_URL}${path}`, {
          cache: "no-store",
          ...init,
          headers: { ...init?.headers, ...adminHeaders }
        })
        if (response.status === 401) lock()
        if (!response.ok) throw new Error(await errorMessage(response, "Request failed"))
        return response
      }
    }
  }, [password, lock])

  if (state === "checking") {
    return <div className="min-h-screen bg-admin-canvas" />
  }

  if (state === "locked") {
    return (
      <main className="flex min-h-screen items-center justify-center bg-admin-canvas px-6 text-admin-fg">
        <form
          onSubmit={onUnlock}
          className="w-full max-w-[360px] rounded-xl border border-admin-line bg-admin-panel p-6 shadow-[0_1px_2px_rgba(0,0,0,0.04)]"
        >
          <Brand />

          <div className="mt-8 flex flex-col gap-3">
            <label>
              <span className="sr-only">Admin password</span>
              <input
                type="password"
                value={passwordInput}
                onChange={(event) => setPasswordInput(event.target.value)}
                placeholder="Password"
                autoFocus
                autoComplete="current-password"
                className={fieldClass}
              />
            </label>
            <AdminButton
              type="submit"
              disabled={passwordInput.trim().length === 0 || isChecking}
              className="h-9 w-full"
            >
              {isChecking ? <Spinner /> : "Continue"}
            </AdminButton>
            {error ? <StatusLine tone="error">{error}</StatusLine> : null}
          </div>
        </form>
      </main>
    )
  }

  return (
    <AdminContext.Provider value={context}>
      <div className="flex min-h-screen bg-admin-canvas text-admin-fg lg:h-screen">
        <aside className="hidden w-60 shrink-0 flex-col px-3 pb-3 lg:flex">
          <div className="flex h-16 items-center px-2">
            <Brand />
          </div>

          <nav aria-label="Admin" className="mt-2 flex flex-col gap-6">
            {NAV_GROUPS.map((group) => (
              <div key={group.label} className="flex flex-col gap-0.5">
                <p className="flex h-6 items-center px-2 text-xs text-admin-muted">{group.label}</p>
                {group.items.map((item) => (
                  <NavLink key={item.href} item={item} isActive={pathname === item.href} />
                ))}
              </div>
            ))}
          </nav>

          <button type="button" onClick={lock} className={cn(navRowClass, "mt-auto w-full")}>
            <Lock className="size-4 shrink-0 text-admin-muted" strokeWidth={1.5} />
            Lock
          </button>
        </aside>

        <div className="flex min-w-0 flex-1 flex-col lg:py-2 lg:pr-2">
          <header className="flex h-12 shrink-0 items-center gap-1 px-3 lg:hidden">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/icon.svg" alt="" className="mr-2 size-5 rounded-[5px]" />
            <nav aria-label="Admin" className="flex min-w-0 flex-1 gap-0.5 overflow-x-auto">
              {NAV_ITEMS.map((item) => (
                <Link
                  key={item.href}
                  href={item.href}
                  aria-current={pathname === item.href ? "page" : undefined}
                  className={cn(navRowClass, pathname === item.href && "bg-admin-fg/[0.06] text-admin-fg")}
                >
                  {item.label}
                </Link>
              ))}
            </nav>
            <IconButton onClick={lock} aria-label="Lock admin">
              <Lock />
            </IconButton>
          </header>

          <div className="flex min-h-0 flex-1 flex-col bg-admin-panel lg:overflow-hidden lg:rounded-xl lg:border lg:border-admin-line lg:shadow-[0_1px_2px_rgba(0,0,0,0.03)]">
            {children}
          </div>
        </div>
      </div>
    </AdminContext.Provider>
  )
}

function NavLink({ item, isActive }: Readonly<{ item: NavItem; isActive: boolean }>) {
  const Icon = item.icon
  return (
    <Link
      href={item.href}
      aria-current={isActive ? "page" : undefined}
      className={cn(navRowClass, isActive && "bg-admin-fg/[0.06] text-admin-fg hover:bg-admin-fg/[0.06]")}
    >
      <Icon className={cn("size-4 shrink-0", isActive ? "text-admin-fg" : "text-admin-muted")} strokeWidth={1.5} />
      {item.label}
    </Link>
  )
}

function Brand() {
  return (
    <Link
      href="/"
      className="flex min-w-0 items-center gap-2.5 rounded-ctl outline-none focus-visible:ring-2 focus-visible:ring-admin-fg/20"
    >
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src="/icon.svg" alt="" className="size-6 shrink-0 rounded-[6px]" />
      <span className="text-sm font-medium text-admin-fg">Mulberry</span>
    </Link>
  )
}

async function errorMessage(response: Response, fallback: string): Promise<string> {
  const body: unknown = await response.json().catch(() => null)
  if (body && typeof body === "object" && "message" in body && typeof body.message === "string") {
    return body.message
  }
  return fallback
}
