"use client"

import { LoaderCircle, Trash2, X } from "lucide-react"
import Link from "next/link"
import { FormEvent, useEffect, useMemo, useRef, useState, useTransition } from "react"

import { Button } from "@/components/ui/button"
import { API_BASE_URL } from "@/lib/constants"

const PRIMARY_ADMIN_PASSWORD_STORAGE_KEY = "mulberry.wallpaperAdminPassword"
const LEGACY_ADMIN_PASSWORD_STORAGE_KEY = "mulberry.stickerAdminPassword"
const ACCEPTED_IMAGE_MIME_TYPES = new Set(["image/png"])

type StickerPackAdminSummary = {
  packKey: string
  packVersion: number
  title: string
  description: string
  coverThumbnailUrl: string
  coverFullUrl: string
  sortOrder: number
  featured: boolean
  publishedAt: string | null
  createdAt: string
  updatedAt: string
  published: boolean
  stickerCount: number
}

type StickerSummary = {
  stickerId: string
  thumbnailUrl: string
  fullUrl: string
  width: number
  height: number
  sortOrder: number
}

type StickerPackAdminDetail = StickerPackAdminSummary & {
  stickers: StickerSummary[]
}

type AdminListResponse = {
  items: StickerPackAdminSummary[]
}

export default function StickerAdminPage() {
  const [passwordInput, setPasswordInput] = useState("")
  const [adminPassword, setAdminPassword] = useState("")
  const [packs, setPacks] = useState<StickerPackAdminSummary[]>([])
  const [openPack, setOpenPack] = useState<{ packKey: string; packVersion: number } | null>(null)
  const [openPackDetail, setOpenPackDetail] = useState<StickerPackAdminDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [status, setStatus] = useState<string | null>(null)
  const [isPending, startTransition] = useTransition()

  const [isCreatingPack, setIsCreatingPack] = useState(false)
  const [creatingCoverFileName, setCreatingCoverFileName] = useState<string | null>(null)
  const createCoverInputRef = useRef<HTMLInputElement | null>(null)
  const [creatingStickerFileNames, setCreatingStickerFileNames] = useState<string | null>(null)
  const [isDraggingStickers, setIsDraggingStickers] = useState(false)
  const createStickersInputRef = useRef<HTMLInputElement | null>(null)
  const createStickerDragDepthRef = useRef(0)

  const [isUploadingStickers, setIsUploadingStickers] = useState(false)
  const [stickerUploadProgress, setStickerUploadProgress] = useState<string | null>(null)
  const stickerUploadInputRef = useRef<HTMLInputElement | null>(null)
  const [isUpdatingCover, setIsUpdatingCover] = useState(false)
  const [coverUpdateFileName, setCoverUpdateFileName] = useState<string | null>(null)
  const coverUpdateInputRef = useRef<HTMLInputElement | null>(null)
  const [isDeletingPack, setIsDeletingPack] = useState(false)

  const isUnlocked = adminPassword.trim().length > 0
  const adminHeaders = useMemo(
    () => ({
      "x-sticker-admin-password": adminPassword.trim(),
    }),
    [adminPassword],
  )

  const fetchAdminPacks = async (password: string): Promise<StickerPackAdminSummary[]> => {
    const response = await fetch(`${API_BASE_URL}/admin/stickers/packs`, {
      cache: "no-store",
      headers: {
        "x-sticker-admin-password": password,
      },
    })
    if (!response.ok) {
      const body = await response.json().catch(() => null)
      throw new Error(body?.message ?? "Unable to unlock sticker admin")
    }
    const body = (await response.json()) as AdminListResponse
    return body.items
}

  const fetchAdminPackDetail = async (packKey: string, packVersion: number): Promise<StickerPackAdminDetail> => {
    const response = await fetch(`${API_BASE_URL}/admin/stickers/packs/${packKey}/${packVersion}`, {
      cache: "no-store",
      headers: adminHeaders,
    })
    if (!response.ok) {
      const body = await response.json().catch(() => null)
      throw new Error(body?.message ?? "Unable to load sticker pack")
    }
    return (await response.json()) as StickerPackAdminDetail
  }

  const loadAdminCatalog = (password = adminPassword) => {
    const credential = password.trim()
    if (!credential) return

    startTransition(() => {
      void (async () => {
        setError(null)
        try {
          const next = await fetchAdminPacks(credential)
          setPacks(next)

          // Close dialog if the open pack disappears.
          if (openPack) {
            const stillExists = next.some(
              (pack) => pack.packKey === openPack.packKey && pack.packVersion === openPack.packVersion,
            )
            if (!stillExists) {
              setOpenPack(null)
              setOpenPackDetail(null)
            }
          }
        } catch (err) {
          setError(err instanceof Error ? err.message : "Unable to load sticker packs")
        }
      })()
    })
  }

  const openPackDialog = (packKey: string, packVersion: number) => {
    setOpenPack({ packKey, packVersion })
    setCoverUpdateFileName(null)
    setIsUpdatingCover(false)
    if (coverUpdateInputRef.current) coverUpdateInputRef.current.value = ""
    startTransition(() => {
      void (async () => {
        setError(null)
        try {
          const detail = await fetchAdminPackDetail(packKey, packVersion)
          setOpenPackDetail(detail)
        } catch (err) {
          setOpenPackDetail(null)
          setError(err instanceof Error ? err.message : "Unable to load sticker pack")
        }
      })()
    })
  }

  const closePackDialog = () => {
    setOpenPack(null)
    setOpenPackDetail(null)
    setStickerUploadProgress(null)
    setIsUploadingStickers(false)
    setCoverUpdateFileName(null)
    setIsUpdatingCover(false)
    setIsDeletingPack(false)
    if (stickerUploadInputRef.current) stickerUploadInputRef.current.value = ""
    if (coverUpdateInputRef.current) coverUpdateInputRef.current.value = ""
  }

  useEffect(() => {
    const storedPassword =
      window.localStorage.getItem(PRIMARY_ADMIN_PASSWORD_STORAGE_KEY) ??
      window.localStorage.getItem(LEGACY_ADMIN_PASSWORD_STORAGE_KEY)
    if (!storedPassword) return

    setAdminPassword(storedPassword)
    loadAdminCatalog(storedPassword)
  }, [])

  useEffect(() => {
    if (!openPack) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return
      event.preventDefault()
      closePackDialog()
    }
    window.addEventListener("keydown", onKeyDown)
    return () => window.removeEventListener("keydown", onKeyDown)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openPack])

  const unlockAdmin = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const credential = passwordInput.trim()
    if (!credential) return

    startTransition(() => {
      void (async () => {
        setError(null)
        setStatus(null)
        try {
          const next = await fetchAdminPacks(credential)
          window.localStorage.setItem(PRIMARY_ADMIN_PASSWORD_STORAGE_KEY, credential)
          window.localStorage.removeItem(LEGACY_ADMIN_PASSWORD_STORAGE_KEY)
          setAdminPassword(credential)
          setPasswordInput("")
          setPacks(next)
        } catch (err) {
          window.localStorage.removeItem(PRIMARY_ADMIN_PASSWORD_STORAGE_KEY)
          window.localStorage.removeItem(LEGACY_ADMIN_PASSWORD_STORAGE_KEY)
          setAdminPassword("")
          setPacks([])
          setOpenPack(null)
          setOpenPackDetail(null)
          setError(err instanceof Error ? err.message : "Unable to unlock sticker admin")
        }
      })()
    })
  }

  const lockAdmin = () => {
    window.localStorage.removeItem(PRIMARY_ADMIN_PASSWORD_STORAGE_KEY)
    window.localStorage.removeItem(LEGACY_ADMIN_PASSWORD_STORAGE_KEY)
    setAdminPassword("")
    setPasswordInput("")
    setPacks([])
    setOpenPack(null)
    setOpenPackDetail(null)
    setError(null)
    setStatus(null)
    setCreatingCoverFileName(null)
    setStickerUploadProgress(null)
    setIsUploadingStickers(false)
    setIsUpdatingCover(false)
    setCoverUpdateFileName(null)
    setIsCreatingPack(false)
    setCreatingStickerFileNames(null)
    setIsDraggingStickers(false)
    createStickerDragDepthRef.current = 0
    if (createCoverInputRef.current) createCoverInputRef.current.value = ""
    if (createStickersInputRef.current) createStickersInputRef.current.value = ""
    if (stickerUploadInputRef.current) stickerUploadInputRef.current.value = ""
    if (coverUpdateInputRef.current) coverUpdateInputRef.current.value = ""
  }

  const setFileInputValue = (input: HTMLInputElement | null, file: File | null) => {
    if (!input) return
    if (typeof DataTransfer === "undefined") return
    const dataTransfer = new DataTransfer()
    if (file) dataTransfer.items.add(file)
    input.files = dataTransfer.files
  }

  const setMultiFileInputValue = (input: HTMLInputElement | null, files: File[]) => {
    if (!input) return
    if (typeof DataTransfer === "undefined") return
    const dataTransfer = new DataTransfer()
    for (const file of files) dataTransfer.items.add(file)
    input.files = dataTransfer.files
  }

  const applyCoverFile = (file: File | null) => {
    setError(null)
    setStatus(null)
    setFileInputValue(createCoverInputRef.current, file)
    setCreatingCoverFileName(file?.name ?? null)
  }

  const applyCoverUpdateFile = (file: File | null) => {
    setError(null)
    setStatus(null)
    setFileInputValue(coverUpdateInputRef.current, file)
    setCoverUpdateFileName(file?.name ?? null)
  }

  const applyCreateStickerFiles = (files: FileList | null) => {
    setError(null)
    setStatus(null)
    const list = Array.from(files ?? [])
    if (list.length === 0) {
      setCreatingStickerFileNames(null)
      return
    }
    setCreatingStickerFileNames(`${list.length} sticker${list.length === 1 ? "" : "s"} selected`)
  }

  const onDropCreateStickers = (event: React.DragEvent<HTMLLabelElement>) => {
    event.preventDefault()
    event.stopPropagation()
    setIsDraggingStickers(false)
    createStickerDragDepthRef.current = 0

    const files = Array.from(event.dataTransfer.files ?? []).filter((file) =>
      file.name.toLowerCase().endsWith(".png"),
    )
    setMultiFileInputValue(createStickersInputRef.current, files)
    applyCreateStickerFiles(createStickersInputRef.current?.files ?? null)
  }

  const onCreatePack = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!isUnlocked) return

    const form = event.currentTarget
    const cover = createCoverInputRef.current?.files?.[0] ?? null
    const stickerFiles = Array.from(createStickersInputRef.current?.files ?? [])
    if (stickerFiles.length === 0) {
      setError("Select at least one sticker PNG.")
      return
    }

    const filesOk = stickerFiles.every((file) => !file.type || ACCEPTED_IMAGE_MIME_TYPES.has(file.type))
    if (!filesOk) {
      setError("All stickers must be PNG files.")
      return
    }

    const coverOk = cover ? (!cover.type || ACCEPTED_IMAGE_MIME_TYPES.has(cover.type)) : true
    if (!coverOk) {
      setError("Cover must be a PNG file.")
      return
    }

    const sortedStickerFiles = stickerFiles.slice().sort((a, b) => a.name.localeCompare(b.name))
    const resolvedCover = cover ?? sortedStickerFiles[0]
    if (!resolvedCover) {
      setError("Select at least one sticker PNG.")
      return
    }

    setIsCreatingPack(true)
    setError(null)
    setStatus(null)
    startTransition(() => {
      void (async () => {
        try {
          const fields = Object.fromEntries(new FormData(form).entries())
          const formData = new FormData()
          formData.set("packKey", String(fields.packKey ?? ""))
          formData.set("packVersion", String(fields.packVersion ?? "1"))
          formData.set("title", String(fields.title ?? ""))
          formData.set("description", String(fields.description ?? ""))
          formData.set("sortOrder", String(fields.sortOrder ?? "0"))
          formData.set("featured", String(Boolean(fields.featured)))
          formData.set("published", String(Boolean(fields.published)))
          formData.set("cover", resolvedCover, resolvedCover.name)

          const response = await fetch(`${API_BASE_URL}/admin/stickers/packs`, { method: "POST", headers: adminHeaders, body: formData })
          if (!response.ok) {
            const body = await response.json().catch(() => null)
            throw new Error(body?.message ?? "Unable to create sticker pack")
          }
          const created = (await response.json()) as StickerPackAdminSummary
          setStatus(`Created ${created.title} (${created.packKey} v${created.packVersion}). Uploading stickers...`)

          for (let i = 0; i < sortedStickerFiles.length; i += 1) {
            const file = sortedStickerFiles[i]
            const stickerId = file.name.replace(/\.[^.]+$/, "").trim()
            if (!stickerId) continue
            setStatus(`Uploading ${stickerId} (${i + 1}/${sortedStickerFiles.length})...`)
            const stickerForm = new FormData()
            stickerForm.set("stickerId", stickerId)
            stickerForm.set("sortOrder", String(i))
            stickerForm.set("image", file, file.name)
            const uploadResponse = await fetch(
              `${API_BASE_URL}/admin/stickers/packs/${created.packKey}/${created.packVersion}/stickers`,
              { method: "POST", headers: adminHeaders, body: stickerForm },
            )
            if (!uploadResponse.ok) {
              const body = await uploadResponse.json().catch(() => null)
              throw new Error(body?.message ?? `Unable to upload ${stickerId}`)
            }
          }

          setStatus(`Uploaded ${sortedStickerFiles.length} sticker${sortedStickerFiles.length === 1 ? "" : "s"} to ${created.title}.`)
          if (createCoverInputRef.current) createCoverInputRef.current.value = ""
          setCreatingCoverFileName(null)
          if (createStickersInputRef.current) createStickersInputRef.current.value = ""
          setCreatingStickerFileNames(null)
          form.reset()
          loadAdminCatalog()
          openPackDialog(created.packKey, created.packVersion)
        } catch (err) {
          setError(err instanceof Error ? err.message : "Unable to create sticker pack")
        } finally {
          setIsCreatingPack(false)
        }
      })()
    })
  }

  const onUpdatePack = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!isUnlocked) return
    if (!openPack) return

    const form = event.currentTarget
    const body = Object.fromEntries(new FormData(form).entries())
    const payload = {
      title: String(body.title ?? ""),
      description: String(body.description ?? ""),
      sortOrder: Number(body.sortOrder ?? 0),
      featured: Boolean(body.featured),
      published: Boolean(body.published),
    }

    startTransition(() => {
      void (async () => {
        setError(null)
        setStatus(null)
        try {
          const response = await fetch(
            `${API_BASE_URL}/admin/stickers/packs/${openPack.packKey}/${openPack.packVersion}`,
            {
              method: "PATCH",
              headers: {
                ...adminHeaders,
                "content-type": "application/json",
              },
              body: JSON.stringify(payload),
            },
          )
          if (!response.ok) {
            const body = await response.json().catch(() => null)
            throw new Error(body?.message ?? "Unable to update sticker pack")
          }
          setStatus("Pack updated.")
          loadAdminCatalog()
          openPackDialog(openPack.packKey, openPack.packVersion)
        } catch (err) {
          setError(err instanceof Error ? err.message : "Unable to update sticker pack")
        }
      })()
    })
  }

  const onUploadStickers = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!isUnlocked) return
    if (!openPack) return

    const input = stickerUploadInputRef.current
    const files = Array.from(input?.files ?? [])
    if (files.length === 0) return

    const hasInvalid = files.some((file) => Boolean(file.type) && !ACCEPTED_IMAGE_MIME_TYPES.has(file.type))
    if (hasInvalid) {
      setError("All stickers must be PNG files.")
      return
    }

    const startSortOrder = Number(new FormData(event.currentTarget).get("startSortOrder") ?? "0")
    const baseSortOrder = Number.isFinite(startSortOrder) ? startSortOrder : 0

    setIsUploadingStickers(true)
    setStickerUploadProgress(null)
    setError(null)
    setStatus(null)
    startTransition(() => {
      void (async () => {
        try {
          for (let i = 0; i < files.length; i += 1) {
            const file = files[i]
            const stickerId = file.name.replace(/\.[^.]+$/, "").trim()
            if (!stickerId) continue
            setStickerUploadProgress(`Uploading ${stickerId} (${i + 1}/${files.length})...`)
            const formData = new FormData()
            formData.set("stickerId", stickerId)
            formData.set("sortOrder", String(baseSortOrder + i))
            formData.set("image", file, file.name)

            const response = await fetch(
              `${API_BASE_URL}/admin/stickers/packs/${openPack.packKey}/${openPack.packVersion}/stickers`,
              {
                method: "POST",
                headers: adminHeaders,
                body: formData,
              },
            )
            if (!response.ok) {
              const body = await response.json().catch(() => null)
              throw new Error(body?.message ?? `Unable to upload ${stickerId}`)
            }
          }

          if (input) input.value = ""
          setStickerUploadProgress(null)
          setStatus(`Uploaded ${files.length} sticker${files.length === 1 ? "" : "s"}.`)
          openPackDialog(openPack.packKey, openPack.packVersion)
          loadAdminCatalog()
        } catch (err) {
          setError(err instanceof Error ? err.message : "Unable to upload stickers")
        } finally {
          setIsUploadingStickers(false)
          setStickerUploadProgress(null)
        }
      })()
    })
  }

  const onUpdateCover = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!isUnlocked) return
    if (!openPack) return
    if (isUpdatingCover || isPending) return

    const file = coverUpdateInputRef.current?.files?.[0] ?? null
    if (!file) {
      setError("Choose a PNG cover image.")
      return
    }

    const ok = !file.type || ACCEPTED_IMAGE_MIME_TYPES.has(file.type)
    if (!ok) {
      setError("Cover must be a PNG file.")
      return
    }

    setIsUpdatingCover(true)
    setError(null)
    setStatus(null)
    startTransition(() => {
      void (async () => {
        try {
          const formData = new FormData()
          formData.set("image", file, file.name)
          const response = await fetch(
            `${API_BASE_URL}/admin/stickers/packs/${openPack.packKey}/${openPack.packVersion}/cover`,
            {
              method: "PUT",
              headers: adminHeaders,
              body: formData,
            },
          )
          if (!response.ok) {
            const body = await response.json().catch(() => null)
            throw new Error(body?.message ?? "Unable to update cover image")
          }
          if (coverUpdateInputRef.current) coverUpdateInputRef.current.value = ""
          setCoverUpdateFileName(null)
          setStatus("Cover image updated.")
          openPackDialog(openPack.packKey, openPack.packVersion)
          loadAdminCatalog()
        } catch (err) {
          setError(err instanceof Error ? err.message : "Unable to update cover image")
        } finally {
          setIsUpdatingCover(false)
        }
      })()
    })
  }

  const deleteOpenPack = () => {
    if (!isUnlocked || !openPack || isDeletingPack) return
    const label = openPackDetail?.title?.trim() ? `"${openPackDetail.title}"` : `${openPack.packKey} v${openPack.packVersion}`
    const ok = window.confirm(`Delete sticker pack ${label}? This will remove the pack and all its stickers.`)
    if (!ok) return

    setIsDeletingPack(true)
    setError(null)
    setStatus(null)
    void (async () => {
      try {
        const response = await fetch(
          `${API_BASE_URL}/admin/stickers/packs/${openPack.packKey}/${openPack.packVersion}`,
          { method: "DELETE", headers: adminHeaders },
        )
        if (!response.ok) {
          const body = await response.json().catch(() => null)
          throw new Error(body?.message ?? "Unable to delete sticker pack")
        }
        closePackDialog()
        setStatus("Sticker pack deleted.")
        loadAdminCatalog()
      } catch (err) {
        setError(err instanceof Error ? err.message : "Unable to delete sticker pack")
      } finally {
        setIsDeletingPack(false)
      }
    })()
  }

  if (!isUnlocked) {
    return (
      <main className="min-h-screen bg-background text-foreground">
        <header className="mx-auto flex w-full max-w-[94rem] items-center justify-between px-5 py-6 sm:px-8 lg:px-10">
          <Link href="/" className="text-sm font-semibold tracking-[-0.03em] text-brand">
            mulberry
          </Link>
          <Link href="/" className="text-sm font-medium text-muted-foreground transition hover:text-foreground">
            Home
          </Link>
        </header>

        <section className="mx-auto flex min-h-[70vh] max-w-xl flex-col items-center justify-center px-5 pb-14 text-center sm:px-8">
          <p className="text-xs font-semibold uppercase tracking-[0.42em] text-brand/80">Sticker admin</p>
          <h1 className="mt-5 text-4xl font-semibold tracking-[-0.065em] sm:text-5xl">Unlock the catalog.</h1>
          <p className="mt-4 max-w-md text-sm leading-6 text-muted-foreground">
            Enter the admin password once. This browser will remember it for future uploads and catalog edits.
          </p>

          <form onSubmit={unlockAdmin} className="mt-9 w-full space-y-4">
            <label className="block w-full text-left">
              <span className="sr-only">Admin password</span>
              <input
                type="password"
                value={passwordInput}
                onChange={(event) => setPasswordInput(event.target.value)}
                placeholder="Admin password"
                autoFocus
                className="h-14 w-full rounded-2xl border border-soft-border bg-elevated px-5 text-base font-medium text-elevated-foreground outline-none transition placeholder:text-muted-foreground focus:border-brand focus:ring-4 focus:ring-brand/10"
              />
            </label>
            <Button type="submit" disabled={passwordInput.trim().length === 0 || isPending} className="h-16 w-full rounded-2xl text-base">
              {isPending ? "Checking..." : "Continue"}
            </Button>
            {error ? <p className="text-sm font-semibold text-red-700">{error}</p> : null}
          </form>
        </section>
      </main>
    )
  }

  const selected = openPackDetail
  const defaultStartSortOrder = selected?.stickers?.length ?? 0

  return (
    <main className="min-h-screen bg-background text-foreground">
      <header className="mx-auto flex w-full max-w-[94rem] items-center justify-between px-5 py-6 sm:px-8 lg:px-10">
        <Link href="/" className="text-sm font-semibold tracking-[-0.03em] text-brand">
          mulberry
        </Link>
        <div className="flex items-center gap-6 text-sm font-medium text-muted-foreground">
          <button
            type="button"
            onClick={() => loadAdminCatalog()}
            className="inline-flex transition-all duration-200 ease-out hover:text-foreground active:scale-[0.97]"
          >
            Refresh
          </button>
          <button
            type="button"
            onClick={lockAdmin}
            className="inline-flex transition-all duration-200 ease-out hover:text-foreground active:scale-[0.97]"
          >
            Lock
          </button>
          <Link href="/admin/wallpapers" className="transition hover:text-foreground">
            Wallpapers
          </Link>
          <Link href="/" className="transition hover:text-foreground">
            Home
          </Link>
        </div>
      </header>

      <section className="mx-auto flex max-w-xl flex-col items-center px-5 pb-14 pt-6 text-center sm:px-8">
        <p className="text-xs font-semibold uppercase tracking-[0.42em] text-brand/80">Sticker admin</p>
        <h1 className="mt-5 text-4xl font-semibold tracking-[-0.065em] sm:text-5xl">Curate the sticker shelf.</h1>
        <p className="mt-4 max-w-md text-sm leading-6 text-muted-foreground">
          Create a pack version, then open it to upload sticker PNGs.
        </p>

        <form onSubmit={onCreatePack} className="mt-9 w-full space-y-4 text-left">
          <fieldset disabled={isCreatingPack || isPending} className="space-y-4">
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="space-y-2">
                <span className="text-xs font-semibold text-muted-foreground">Pack key</span>
                <input
                  name="packKey"
                  required
                  placeholder="kawaii-cats"
                  className="h-12 w-full rounded-2xl border border-soft-border bg-elevated px-4 text-sm font-medium outline-none transition placeholder:text-muted-foreground focus:border-brand focus:ring-4 focus:ring-brand/10"
                />
              </label>
              <label className="space-y-2">
                <span className="text-xs font-semibold text-muted-foreground">Version</span>
                <input
                  name="packVersion"
                  required
                  defaultValue={1}
                  type="number"
                  min={1}
                  className="h-12 w-full rounded-2xl border border-soft-border bg-elevated px-4 text-sm font-medium outline-none transition placeholder:text-muted-foreground focus:border-brand focus:ring-4 focus:ring-brand/10"
                />
              </label>
            </div>

            <label className="space-y-2">
              <span className="text-xs font-semibold text-muted-foreground">Title</span>
              <input
                name="title"
                required
                placeholder="Kawaii Cats"
                className="h-12 w-full rounded-2xl border border-soft-border bg-elevated px-4 text-sm font-medium outline-none transition placeholder:text-muted-foreground focus:border-brand focus:ring-4 focus:ring-brand/10"
              />
            </label>

            <label className="space-y-2">
              <span className="text-xs font-semibold text-muted-foreground">Description</span>
              <textarea
                name="description"
                rows={3}
                placeholder="Optional"
                className="w-full resize-none rounded-2xl border border-soft-border bg-elevated px-4 py-3 text-sm outline-none transition placeholder:text-muted-foreground focus:border-brand focus:ring-4 focus:ring-brand/10"
              />
            </label>

            <div className="grid gap-3 sm:grid-cols-2">
              <label className="space-y-2">
                <span className="text-xs font-semibold text-muted-foreground">Sort order</span>
                <input
                  name="sortOrder"
                  defaultValue={0}
                  type="number"
                  className="h-12 w-full rounded-2xl border border-soft-border bg-elevated px-4 text-sm font-medium outline-none transition placeholder:text-muted-foreground focus:border-brand focus:ring-4 focus:ring-brand/10"
                />
              </label>
              <div className="flex items-end justify-between gap-3">
                <label className="inline-flex items-center gap-2 rounded-2xl border border-soft-border bg-elevated px-4 py-3 text-sm font-medium">
                  <input name="featured" type="checkbox" defaultChecked className="h-4 w-4 accent-brand" />
                  Featured
                </label>
                <label className="inline-flex items-center gap-2 rounded-2xl border border-soft-border bg-elevated px-4 py-3 text-sm font-medium">
                  <input name="published" type="checkbox" defaultChecked className="h-4 w-4 accent-brand" />
                  Published
                </label>
              </div>
            </div>

            <label className="group block cursor-pointer rounded-[2rem] border-2 border-dashed bg-soft-surface px-6 py-10 text-center transition hover:border-brand hover:bg-soft-surface-hover focus-within:border-brand focus-within:ring-4 focus-within:ring-brand/10">
              <input
                ref={createCoverInputRef}
                type="file"
                name="cover"
                accept="image/png"
                className="sr-only"
                onChange={(event) => applyCoverFile(event.target.files?.[0] ?? null)}
              />
              <span className="block text-lg font-semibold tracking-[-0.04em]">
                {creatingCoverFileName ?? "Upload cover PNG (optional)"}
              </span>
              <span className="mt-2 block text-xs font-medium text-muted-foreground">
                If omitted, the first sticker becomes the cover.
              </span>
            </label>

            <label
              className={[
                "group block cursor-pointer rounded-[2rem] border-2 border-dashed bg-soft-surface px-6 py-10 text-center transition focus-within:ring-4 focus-within:ring-brand/10",
                isDraggingStickers
                  ? "border-brand bg-soft-surface-hover"
                  : "border-soft-border hover:border-brand hover:bg-soft-surface-hover focus-within:border-brand",
              ].join(" ")}
              onDragEnter={(event) => {
                event.preventDefault()
                event.stopPropagation()
                createStickerDragDepthRef.current += 1
                setIsDraggingStickers(true)
              }}
              onDragOver={(event) => {
                event.preventDefault()
                event.stopPropagation()
              }}
              onDragLeave={(event) => {
                event.preventDefault()
                event.stopPropagation()
                createStickerDragDepthRef.current = Math.max(0, createStickerDragDepthRef.current - 1)
                if (createStickerDragDepthRef.current === 0) setIsDraggingStickers(false)
              }}
              onDrop={onDropCreateStickers}
            >
              <input
                ref={createStickersInputRef}
                type="file"
                name="stickers"
                accept="image/png"
                multiple
                required
                className="sr-only"
                onChange={(event) => applyCreateStickerFiles(event.target.files)}
              />
              <span className="block text-lg font-semibold tracking-[-0.04em]">
                {creatingStickerFileNames ?? "Upload stickers (PNG, multiple)"}
              </span>
              <span className="mt-2 block text-xs font-medium text-muted-foreground">
                Drag and drop all sticker PNGs here.
              </span>
            </label>

            <Button type="submit" className="h-16 w-full rounded-2xl text-base" disabled={isCreatingPack}>
              {isCreatingPack ? (
                <>
                  <LoaderCircle className="h-4 w-4 animate-spin" />
                  Creating...
                </>
              ) : (
                "Create pack version"
              )}
            </Button>
          </fieldset>

          {status ? <p className="text-sm font-semibold text-brand">{status}</p> : null}
          {error ? <p className="text-sm font-semibold text-red-700">{error}</p> : null}
        </form>
      </section>

      <section className="mx-auto w-full max-w-[98rem] px-5 pb-24 sm:px-8 lg:px-10">
        {packs.length === 0 ? (
          <div className="rounded-[2rem] border border-dashed border-soft-border bg-soft-surface px-6 py-16 text-center text-sm font-medium text-muted-foreground">
            No sticker packs yet.
          </div>
        ) : (
          <div className="grid gap-x-6 gap-y-10 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {packs.map((pack) => (
              <article key={`${pack.packKey}:${pack.packVersion}`} className="min-w-0">
                <button type="button" onClick={() => openPackDialog(pack.packKey, pack.packVersion)} className="w-full text-left">
                  <div className="relative aspect-[171/103] overflow-hidden rounded-[1.65rem] bg-brand-soft">
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img src={pack.coverThumbnailUrl} alt={pack.title} className="h-full w-full object-cover" loading="lazy" />
                    {pack.featured ? (
                      <div className="absolute left-4 top-4 rounded-full bg-elevated px-3 py-1 text-[11px] font-semibold text-brand shadow-sm">
                        Featured
                      </div>
                    ) : null}
                  </div>

                  <div className="mt-4 space-y-2">
                    <h3 className="text-lg font-semibold tracking-[-0.04em] text-foreground">{pack.title}</h3>
                    {pack.description ? (
                      <p className="text-sm leading-6 text-muted-foreground">{pack.description}</p>
                    ) : (
                      <p className="text-sm leading-6 text-muted-foreground">
                        {pack.packKey} · v{pack.packVersion}
                      </p>
                    )}
                    <p className="text-xs font-semibold text-muted-foreground">
                      {pack.stickerCount} sticker{pack.stickerCount === 1 ? "" : "s"} ·{" "}
                      {pack.published ? "Published" : "Draft"}
                    </p>
                  </div>
                </button>
              </article>
            ))}
          </div>
        )}
      </section>

      {openPack ? (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 px-4 py-6"
          role="presentation"
          onMouseDown={(event) => {
            if (event.currentTarget !== event.target) return
            closePackDialog()
          }}
        >
          <div
            role="dialog"
            aria-modal="true"
            aria-label="Sticker pack"
            className="relative w-full max-w-4xl overflow-hidden rounded-[2rem] border border-soft-border bg-background shadow-xl"
          >
            <header className="flex items-start justify-between gap-4 border-b border-soft-border bg-soft-surface px-6 py-5">
              <div className="min-w-0">
                <p className="text-xs font-semibold uppercase tracking-[0.42em] text-brand/80">Sticker pack</p>
                <h2 className="mt-2 truncate text-2xl font-semibold tracking-[-0.05em]">
                  {selected?.title ?? `${openPack.packKey} v${openPack.packVersion}`}
                </h2>
                <p className="mt-1 text-sm text-muted-foreground">
                  {openPack.packKey} · v{openPack.packVersion}
                </p>
              </div>
              <div className="flex items-center gap-3">
                {isPending ? <LoaderCircle className="h-5 w-5 animate-spin text-muted-foreground" /> : null}
                <button
                  type="button"
                  onClick={deleteOpenPack}
                  disabled={isDeletingPack || isPending}
                  className="inline-flex h-10 w-10 items-center justify-center rounded-full border border-soft-border bg-elevated text-muted-foreground transition hover:text-red-600 active:scale-[0.97] disabled:opacity-40"
                  aria-label="Delete pack"
                >
                  {isDeletingPack ? <LoaderCircle className="h-4 w-4 animate-spin" /> : <Trash2 className="h-4 w-4" />}
                </button>
                <button
                  type="button"
                  onClick={closePackDialog}
                  disabled={isDeletingPack}
                  className="inline-flex h-10 w-10 items-center justify-center rounded-full border border-soft-border bg-elevated text-muted-foreground transition hover:text-foreground active:scale-[0.97] disabled:opacity-40"
                  aria-label="Close"
                >
                  <X className="h-4 w-4" />
                </button>
              </div>
            </header>

            <div className="max-h-[calc(100vh-12rem)] overflow-y-auto px-6 py-6">
              {!selected ? (
                <div className="rounded-[2rem] border border-dashed border-soft-border bg-soft-surface px-6 py-16 text-center text-sm font-medium text-muted-foreground">
                  Loading…
                </div>
              ) : (
                <>
                  <form onSubmit={onUpdatePack} className="space-y-4">
                    <fieldset disabled={isPending || isUploadingStickers || isCreatingPack} className="space-y-4">
                      <div className="grid gap-3 sm:grid-cols-2">
                        <label className="space-y-2">
                          <span className="text-xs font-semibold text-muted-foreground">Title</span>
                          <input
                            name="title"
                            required
                            defaultValue={selected.title}
                            className="h-12 w-full rounded-2xl border border-soft-border bg-elevated px-4 text-sm font-medium outline-none focus:border-brand focus:ring-4 focus:ring-brand/10"
                          />
                        </label>
                        <label className="space-y-2">
                          <span className="text-xs font-semibold text-muted-foreground">Sort order</span>
                          <input
                            name="sortOrder"
                            type="number"
                            defaultValue={selected.sortOrder ?? 0}
                            className="h-12 w-full rounded-2xl border border-soft-border bg-elevated px-4 text-sm font-medium outline-none focus:border-brand focus:ring-4 focus:ring-brand/10"
                          />
                        </label>
                      </div>

                      <label className="space-y-2">
                        <span className="text-xs font-semibold text-muted-foreground">Description</span>
                        <textarea
                          name="description"
                          rows={3}
                          defaultValue={selected.description ?? ""}
                          className="w-full resize-none rounded-2xl border border-soft-border bg-elevated px-4 py-3 text-sm outline-none focus:border-brand focus:ring-4 focus:ring-brand/10"
                        />
                      </label>

                      <div className="flex flex-wrap items-center gap-3">
                        <label className="inline-flex items-center gap-2 rounded-2xl border border-soft-border bg-elevated px-4 py-3 text-sm font-medium">
                          <input name="featured" type="checkbox" defaultChecked={Boolean(selected.featured)} className="h-4 w-4 accent-brand" />
                          Featured
                        </label>
                        <label className="inline-flex items-center gap-2 rounded-2xl border border-soft-border bg-elevated px-4 py-3 text-sm font-medium">
                          <input name="published" type="checkbox" defaultChecked={Boolean(selected.published)} className="h-4 w-4 accent-brand" />
                          Published
                        </label>
                        <Button type="submit" className="h-11 rounded-2xl px-6">
                          Save
                        </Button>
                      </div>
                    </fieldset>
                  </form>

                  <div className="mt-8 grid gap-6 lg:grid-cols-[minmax(0,340px)_minmax(0,1fr)]">
                    <div className="space-y-6">
                      <section className="rounded-[1.6rem] border border-soft-border bg-soft-surface p-4">
                        <h3 className="text-sm font-semibold tracking-[-0.03em]">Cover image</h3>
                        <p className="mt-1 text-xs leading-5 text-muted-foreground">Upload a new PNG to replace the pack cover.</p>

                        <div className="mt-4 overflow-hidden rounded-2xl border border-soft-border bg-elevated">
                          <div className="aspect-square w-full bg-background">
                            {/* eslint-disable-next-line @next/next/no-img-element */}
                            <img src={selected.coverThumbnailUrl} alt={`${selected.title} cover`} className="h-full w-full object-cover" loading="lazy" />
                          </div>
                        </div>

                        <form onSubmit={onUpdateCover} className="mt-4 space-y-3">
                          <fieldset disabled={isUpdatingCover || isPending} className="space-y-3">
                            <label className="block cursor-pointer rounded-[1.4rem] border-2 border-dashed border-soft-border bg-elevated px-4 py-5 text-center transition hover:border-brand hover:bg-soft-surface-hover focus-within:ring-4 focus-within:ring-brand/10">
                              <input
                                ref={coverUpdateInputRef}
                                type="file"
                                accept="image/png"
                                className="sr-only"
                                onChange={(event) => applyCoverUpdateFile(event.target.files?.[0] ?? null)}
                              />
                              <span className="block text-sm font-semibold">
                                {coverUpdateFileName ? coverUpdateFileName : isUpdatingCover ? "Updating..." : "Choose PNG cover"}
                              </span>
                              <span className="mt-1 block text-xs font-medium text-muted-foreground">PNG only</span>
                            </label>

                            <Button type="submit" disabled={!coverUpdateFileName} className="h-11 w-full rounded-2xl">
                              {isUpdatingCover ? "Updating..." : "Update cover"}
                            </Button>
                          </fieldset>
                        </form>
                      </section>

                      <section className="rounded-[1.6rem] border border-soft-border bg-soft-surface p-4">
                        <h3 className="text-sm font-semibold tracking-[-0.03em]">Upload stickers</h3>
                        <p className="mt-1 text-xs leading-5 text-muted-foreground">
                          Select multiple PNG files. Sticker IDs are derived from filenames.
                        </p>

                        <form onSubmit={onUploadStickers} className="mt-4 space-y-3">
                          <fieldset disabled={isUploadingStickers || isPending} className="space-y-3">
                            <label className="space-y-2">
                              <span className="text-[11px] font-semibold text-muted-foreground">Start sort order</span>
                              <input
                                name="startSortOrder"
                                type="number"
                                defaultValue={defaultStartSortOrder}
                                className="h-11 w-full rounded-2xl border border-soft-border bg-elevated px-4 text-sm font-medium outline-none focus:border-brand focus:ring-4 focus:ring-brand/10"
                              />
                            </label>

                            <label className="block cursor-pointer rounded-[1.4rem] border-2 border-dashed border-soft-border bg-elevated px-4 py-6 text-center transition hover:border-brand hover:bg-soft-surface-hover focus-within:ring-4 focus-within:ring-brand/10">
                              <input ref={stickerUploadInputRef} type="file" accept="image/png" multiple className="sr-only" />
                              <span className="block text-sm font-semibold">
                                {isUploadingStickers ? "Uploading..." : "Choose PNG stickers"}
                              </span>
                              <span className="mt-1 block text-xs font-medium text-muted-foreground">PNG only</span>
                            </label>

                            <Button type="submit" className="h-11 w-full rounded-2xl">
                              {isUploadingStickers ? "Uploading..." : "Upload"}
                            </Button>
                          </fieldset>

                          {stickerUploadProgress ? (
                            <p className="text-xs font-semibold text-muted-foreground">{stickerUploadProgress}</p>
                          ) : null}
                        </form>
                      </section>
                    </div>

                    <section className="rounded-[1.6rem] border border-soft-border bg-soft-surface p-4">
                      <h3 className="text-sm font-semibold tracking-[-0.03em]">Stickers</h3>
                      {selected.stickers?.length ? (
                        <div className="mt-4 grid grid-cols-3 gap-3 sm:grid-cols-4 lg:grid-cols-5">
                          {selected.stickers.map((sticker) => (
                            <div
                              key={sticker.stickerId}
                              className="rounded-2xl border border-soft-border bg-elevated p-2"
                              title={sticker.stickerId}
                            >
                              <div className="aspect-square overflow-hidden rounded-xl bg-background">
                                {/* eslint-disable-next-line @next/next/no-img-element */}
                                <img src={sticker.thumbnailUrl} alt={sticker.stickerId} className="h-full w-full object-contain" loading="lazy" />
                              </div>
                              <p className="mt-2 truncate text-[11px] font-semibold text-muted-foreground">
                                {sticker.stickerId}
                              </p>
                            </div>
                          ))}
                        </div>
                      ) : (
                        <p className="mt-4 text-sm text-muted-foreground">No stickers uploaded yet.</p>
                      )}
                    </section>
                  </div>
                </>
              )}

              {status ? <p className="mt-6 text-sm font-semibold text-brand">{status}</p> : null}
              {error ? <p className="mt-2 text-sm font-semibold text-red-700">{error}</p> : null}
            </div>
          </div>
        </div>
      ) : null}
    </main>
  )
}
