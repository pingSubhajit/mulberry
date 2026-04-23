"use client"

import { LoaderCircle, Trash2 } from "lucide-react"
import Link from "next/link"
import { FormEvent, useEffect, useMemo, useState, useTransition } from "react"

import { Button } from "@/components/ui/button"
import { API_BASE_URL } from "@/lib/constants"

const ADMIN_PASSWORD_STORAGE_KEY = "mulberry.wallpaperAdminPassword"

type WallpaperItem = {
  id: string
  title: string
  description: string
  thumbnailUrl: string
  previewUrl: string
  fullImageUrl: string
  width: number
  height: number
  dominantColor: string
  sortOrder?: number
  published?: boolean
}

type CatalogResponse = {
  items: WallpaperItem[]
  nextCursor?: string | null
}

export default function WallpaperAdminPage() {
  const [passwordInput, setPasswordInput] = useState("")
  const [adminPassword, setAdminPassword] = useState("")
  const [items, setItems] = useState<WallpaperItem[]>([])
  const [error, setError] = useState<string | null>(null)
  const [status, setStatus] = useState<string | null>(null)
  const [selectedFileName, setSelectedFileName] = useState<string | null>(null)
  const [isUploading, setIsUploading] = useState(false)
  const [deletingId, setDeletingId] = useState<string | null>(null)
  const [isPending, startTransition] = useTransition()
  const isUnlocked = adminPassword.trim().length > 0

  const adminHeaders = useMemo(
    () => ({
      "x-wallpaper-admin-password": adminPassword.trim(),
    }),
    [adminPassword],
  )

  const fetchAdminCatalog = async (password: string): Promise<WallpaperItem[]> => {
    const response = await fetch(`${API_BASE_URL}/admin/wallpapers`, {
      cache: "no-store",
      headers: {
        "x-wallpaper-admin-password": password,
      },
    })
    if (!response.ok) {
      const body = await response.json().catch(() => null)
      throw new Error(body?.message ?? "Unable to unlock wallpaper admin")
    }
    const body = (await response.json()) as CatalogResponse
    return body.items
  }

  const loadAdminCatalog = (password = adminPassword) => {
    const credential = password.trim()
    if (!credential) return

    startTransition(() => {
      void (async () => {
        setError(null)
        try {
          const nextItems = await fetchAdminCatalog(credential)
          setItems(nextItems)
        } catch (err) {
          setError(err instanceof Error ? err.message : "Unable to load wallpaper catalog")
        }
      })()
    })
  }

  useEffect(() => {
    const storedPassword = window.localStorage.getItem(ADMIN_PASSWORD_STORAGE_KEY)
    if (!storedPassword) return

    setAdminPassword(storedPassword)
    loadAdminCatalog(storedPassword)
  }, [])

  const unlockAdmin = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const credential = passwordInput.trim()
    if (!credential) return

    startTransition(() => {
      void (async () => {
        setError(null)
        setStatus(null)
        try {
          const nextItems = await fetchAdminCatalog(credential)
          window.localStorage.setItem(ADMIN_PASSWORD_STORAGE_KEY, credential)
          setAdminPassword(credential)
          setPasswordInput("")
          setItems(nextItems)
        } catch (err) {
          window.localStorage.removeItem(ADMIN_PASSWORD_STORAGE_KEY)
          setAdminPassword("")
          setItems([])
          setError(err instanceof Error ? err.message : "Unable to unlock wallpaper admin")
        }
      })()
    })
  }

  const lockAdmin = () => {
    window.localStorage.removeItem(ADMIN_PASSWORD_STORAGE_KEY)
    setAdminPassword("")
    setPasswordInput("")
    setItems([])
    setError(null)
    setStatus(null)
    setSelectedFileName(null)
  }

  const onUpload = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!isUnlocked || isUploading || deletingId) return

    const form = event.currentTarget
    const data = new FormData(form)
    setIsUploading(true)
    void (async () => {
      setError(null)
      setStatus(null)
      try {
        const response = await fetch(`${API_BASE_URL}/admin/wallpapers`, {
          method: "POST",
          headers: adminHeaders,
          body: data,
        })
        if (!response.ok) {
          const body = await response.json().catch(() => null)
          throw new Error(body?.message ?? "Wallpaper upload failed")
        }
        form.reset()
        setSelectedFileName(null)
        setStatus("Wallpaper uploaded.")
        loadAdminCatalog()
      } catch (err) {
        setError(err instanceof Error ? err.message : "Wallpaper upload failed")
      } finally {
        setIsUploading(false)
      }
    })()
  }

  const deleteWallpaper = (id: string) => {
    if (!isUnlocked || isUploading || deletingId) return

    setDeletingId(id)
    void (async () => {
      setError(null)
      setStatus(null)
      try {
        const response = await fetch(`${API_BASE_URL}/admin/wallpapers/${id}`, {
          method: "DELETE",
          headers: adminHeaders,
        })
        if (!response.ok) {
          const body = await response.json().catch(() => null)
          throw new Error(body?.message ?? "Unable to delete wallpaper")
        }
        setStatus("Wallpaper deleted.")
        loadAdminCatalog()
      } catch (err) {
        setError(err instanceof Error ? err.message : "Unable to delete wallpaper")
      } finally {
        setDeletingId(null)
      }
    })()
  }

  if (!isUnlocked) {
    return (
      <main className="min-h-screen bg-[#fffaf7] text-brand-ink">
        <header className="mx-auto flex w-full max-w-[94rem] items-center justify-between px-5 py-6 sm:px-8 lg:px-10">
          <Link href="/" className="text-sm font-semibold tracking-[-0.03em] text-brand">
            mulberry
          </Link>
          <Link href="/" className="text-sm font-medium text-brand-ink/58 transition hover:text-brand-ink">
            Home
          </Link>
        </header>

        <section className="mx-auto flex min-h-[70vh] max-w-xl flex-col items-center justify-center px-5 pb-14 text-center sm:px-8">
          <p className="text-xs font-semibold uppercase tracking-[0.42em] text-brand/80">
            Wallpaper admin
          </p>
          <h1 className="mt-5 text-4xl font-semibold tracking-[-0.065em] sm:text-5xl">
            Unlock the catalog.
          </h1>
          <p className="mt-4 max-w-md text-sm leading-6 text-brand-ink/58">
            Enter the admin password once. This browser will remember it for future uploads and
            catalog edits.
          </p>

          <form onSubmit={unlockAdmin} className="mt-9 w-full space-y-4">
            <label className="block text-left">
              <span className="sr-only">Admin password</span>
              <input
                type="password"
                value={passwordInput}
                onChange={(event) => setPasswordInput(event.target.value)}
                placeholder="Admin password"
                autoFocus
                className="h-14 w-full rounded-2xl border border-brand-ink/16 bg-white/70 px-5 text-base font-medium outline-none transition placeholder:text-brand-ink/42 focus:border-brand focus:ring-4 focus:ring-brand/10"
              />
            </label>
            <Button
              type="submit"
              disabled={passwordInput.trim().length === 0 || isPending}
              className="h-16 w-full rounded-2xl text-base"
            >
              {isPending ? "Checking..." : "Continue"}
            </Button>
            {error ? <p className="text-sm font-semibold text-red-700">{error}</p> : null}
          </form>
        </section>
      </main>
    )
  }

  return (
    <main className="min-h-screen bg-[#fffaf7] text-brand-ink">
      <header className="mx-auto flex w-full max-w-[94rem] items-center justify-between px-5 py-6 sm:px-8 lg:px-10">
        <Link href="/" className="text-sm font-semibold tracking-[-0.03em] text-brand">
          mulberry
        </Link>
        <div className="flex items-center gap-6 text-sm font-medium text-brand-ink/58">
          <button type="button" onClick={() => loadAdminCatalog()} className="transition hover:text-brand-ink">
            Refresh
          </button>
          <button type="button" onClick={lockAdmin} className="transition hover:text-brand-ink">
            Lock
          </button>
          <Link href="/" className="transition hover:text-brand-ink">
            Home
          </Link>
        </div>
      </header>

      <section className="mx-auto flex max-w-xl flex-col items-center px-5 pb-14 pt-6 text-center sm:px-8">
        <p className="text-xs font-semibold uppercase tracking-[0.42em] text-brand/80">
          Wallpaper admin
        </p>
        <h1 className="mt-5 text-4xl font-semibold tracking-[-0.065em] sm:text-5xl">
          Curate the Mulberry background shelf.
        </h1>
        <p className="mt-4 max-w-md text-sm leading-6 text-brand-ink/58">
          Upload one image. The backend creates the thumbnail, preview, and optimized full
          wallpaper variants.
        </p>

        <form onSubmit={onUpload} className="mt-9 w-full space-y-4">
          <fieldset disabled={isUploading} className="space-y-4">
            <label className="group block cursor-pointer rounded-[2rem] border-2 border-dashed border-brand-ink/30 bg-white/45 px-6 py-10 text-center transition hover:border-brand hover:bg-white focus-within:border-brand focus-within:ring-4 focus-within:ring-brand/10 disabled:pointer-events-none disabled:opacity-70">
              <input
                name="image"
                type="file"
                accept="image/png,image/jpeg,image/webp"
                required
                className="sr-only"
                onChange={(event) => setSelectedFileName(event.target.files?.[0]?.name ?? null)}
              />
              <span className="block text-lg font-semibold tracking-[-0.04em]">
                {selectedFileName ?? "Upload image"}
              </span>
              <span className="mt-2 block text-xs font-medium text-brand-ink/48">
                Supported file types: jpeg, jpg, png, webp
              </span>
            </label>

            <label className="block text-left">
              <span className="sr-only">Name</span>
              <input
                name="title"
                required
                placeholder="Name"
                className="h-14 w-full rounded-2xl border border-brand-ink/16 bg-white/70 px-5 text-base font-medium outline-none transition placeholder:text-brand-ink/42 focus:border-brand focus:ring-4 focus:ring-brand/10"
              />
            </label>

            <label className="block text-left">
              <span className="sr-only">Description</span>
              <textarea
                name="description"
                rows={3}
                placeholder="Description"
                className="w-full resize-none rounded-2xl border border-brand-ink/16 bg-white/70 px-5 py-4 text-base font-medium outline-none transition placeholder:text-brand-ink/42 focus:border-brand focus:ring-4 focus:ring-brand/10"
              />
            </label>

            <input name="sortOrder" type="hidden" value="0" />
            <input name="published" type="hidden" value="true" />

            <Button
              type="submit"
              disabled={isUploading || Boolean(deletingId)}
              className="h-16 w-full rounded-2xl text-base"
            >
              {isUploading ? (
                <>
                  <LoaderCircle className="h-4 w-4 animate-spin" />
                  Uploading...
                </>
              ) : (
                "Upload"
              )}
            </Button>
          </fieldset>

          {status ? <p className="text-sm font-semibold text-brand">{status}</p> : null}
          {error ? <p className="text-sm font-semibold text-red-700">{error}</p> : null}
        </form>
      </section>

      <section className="mx-auto w-full max-w-[98rem] px-5 pb-24 sm:px-8 lg:px-10">
        {items.length === 0 ? (
          <div className="rounded-[2rem] border border-dashed border-brand-ink/16 bg-white/45 px-6 py-16 text-center text-sm font-medium text-brand-ink/50">
            No wallpapers yet.
          </div>
        ) : (
          <div className="grid gap-x-6 gap-y-10 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {items.map((item) => (
              <article key={item.id} className="min-w-0">
                <div className="relative aspect-[171/103] overflow-hidden rounded-[1.65rem] bg-brand-soft">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img
                    src={item.thumbnailUrl}
                    alt={item.title}
                    className="h-full w-full object-cover"
                    loading="lazy"
                  />
                  <button
                    type="button"
                    aria-label={
                      deletingId === item.id ? `Deleting ${item.title}` : `Delete ${item.title}`
                    }
                    onClick={() => deleteWallpaper(item.id)}
                    disabled={Boolean(deletingId) || isUploading}
                    className="absolute right-4 top-4 inline-flex h-10 w-10 items-center justify-center rounded-full bg-white/92 text-brand-ink shadow-sm transition hover:text-brand disabled:opacity-40"
                  >
                    {deletingId === item.id ? (
                      <LoaderCircle className="h-4 w-4 animate-spin" />
                    ) : (
                      <Trash2 className="h-4 w-4" />
                    )}
                  </button>
                </div>

                <div className="mt-4 space-y-2">
                  <h3 className="text-lg font-semibold tracking-[-0.04em] text-brand-ink">
                    {item.title}
                  </h3>
                  <p className="text-sm leading-6 text-brand-ink/58">{item.description}</p>
                </div>
              </article>
            ))}
          </div>
        )}
      </section>
    </main>
  )
}
