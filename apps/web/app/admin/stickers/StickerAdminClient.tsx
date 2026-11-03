"use client"

import { ImagePlus, Plus, Sticker, Upload } from "lucide-react"
import { FormEvent, useEffect, useRef, useState, useTransition } from "react"

import { API_BASE_URL } from "@/lib/constants"
import { cn } from "@/lib/utils"

import { useAdmin } from "../AdminShell"
import {
  AdminButton,
  Dropzone,
  EmptyState,
  fieldClass,
  LiveDot,
  numberFieldClass,
  PropertyRow,
  PublishState,
  SectionLabel,
  Segmented,
  Spinner,
  StatusLine,
  Switch,
  textareaClass,
  TopBar
} from "../ui"

type DetailTab = "stickers" | "details"

const DETAIL_TABS: readonly { value: DetailTab; label: string }[] = [
  { value: "stickers", label: "Stickers" },
  { value: "details", label: "Details" }
]

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
  contentLocked: boolean
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
  const { adminHeaders } = useAdmin()
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
  const [stickerUploadSelection, setStickerUploadSelection] = useState<string | null>(null)
  const stickerUploadInputRef = useRef<HTMLInputElement | null>(null)
  const [isUpdatingCover, setIsUpdatingCover] = useState(false)
  const [coverUpdateFileName, setCoverUpdateFileName] = useState<string | null>(null)
  const coverUpdateInputRef = useRef<HTMLInputElement | null>(null)
  const [isDeletingPack, setIsDeletingPack] = useState(false)
  const [isCreatingView, setIsCreatingView] = useState(false)
  const [detailTab, setDetailTab] = useState<DetailTab>("stickers")
  const hasAutoOpenedRef = useRef(false)

  const fetchAdminPacks = async (): Promise<StickerPackAdminSummary[]> => {
    const response = await fetch(`${API_BASE_URL}/admin/stickers/packs`, {
      cache: "no-store",
      headers: adminHeaders,
    })
    if (!response.ok) {
      const body = await response.json().catch(() => null)
      throw new Error(body?.message ?? "Unable to load sticker packs")
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

  const loadAdminCatalog = () => {
    startTransition(() => {
      void (async () => {
        setError(null)
        try {
          const next = await fetchAdminPacks()
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
    setIsCreatingView(false)
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
    setStickerUploadSelection(null)
    setIsUploadingStickers(false)
    setCoverUpdateFileName(null)
    setIsUpdatingCover(false)
    setIsDeletingPack(false)
    if (stickerUploadInputRef.current) stickerUploadInputRef.current.value = ""
    if (coverUpdateInputRef.current) coverUpdateInputRef.current.value = ""
  }

  useEffect(() => {
    loadAdminCatalog()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Open the first pack once the list arrives, so the detail pane never starts empty.
  useEffect(() => {
    if (hasAutoOpenedRef.current || openPack || isCreatingView) return
    const first = packs[0]
    if (!first) return
    hasAutoOpenedRef.current = true
    openPackDialog(first.packKey, first.packVersion)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [packs])

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
    setCreatingStickerFileNames(stickerCountLabel(list.length, "selected"))
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

    const form = event.currentTarget
    const cover = createCoverInputRef.current?.files?.[0] ?? null
    const stickerFiles = Array.from(createStickersInputRef.current?.files ?? [])
    if (stickerFiles.length === 0) {
      setError("Choose at least one sticker PNG")
      return
    }

    const filesOk = stickerFiles.every((file) => !file.type || ACCEPTED_IMAGE_MIME_TYPES.has(file.type))
    if (!filesOk) {
      setError("Stickers must be PNG files")
      return
    }

    const coverOk = cover ? (!cover.type || ACCEPTED_IMAGE_MIME_TYPES.has(cover.type)) : true
    if (!coverOk) {
      setError("The cover must be a PNG file")
      return
    }

    const sortedStickerFiles = stickerFiles.slice().sort((a, b) => a.name.localeCompare(b.name))
    const resolvedCover = cover ?? sortedStickerFiles[0]
    if (!resolvedCover) {
      setError("Choose at least one sticker PNG")
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
          formData.set("published", "false")
          formData.set("cover", resolvedCover, resolvedCover.name)

          const response = await fetch(`${API_BASE_URL}/admin/stickers/packs`, { method: "POST", headers: adminHeaders, body: formData })
          if (!response.ok) {
            const body = await response.json().catch(() => null)
            throw new Error(body?.message ?? "Unable to create sticker pack")
          }
          const created = (await response.json()) as StickerPackAdminSummary
          setStatus(`Created ${created.title}, ${created.packKey} v${created.packVersion}`)

          for (let i = 0; i < sortedStickerFiles.length; i += 1) {
            const file = sortedStickerFiles[i]
            const stickerId = file.name.replace(/\.[^.]+$/, "").trim()
            if (!stickerId) continue
            setStatus(`Uploading ${i + 1} of ${sortedStickerFiles.length} · ${stickerId}`)
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

          if (fields.published) {
            const publish = await fetch(`${API_BASE_URL}/admin/stickers/packs/${created.packKey}/${created.packVersion}`, {
              method: "PATCH", headers: { ...adminHeaders, "content-type": "application/json" },
              body: JSON.stringify({ published: true }),
            })
            if (!publish.ok) {
              const body = await publish.json().catch(() => null)
              throw new Error(body?.message ?? "Pack uploaded as a draft, but could not be published")
            }
          }

          setStatus(`Uploaded ${stickerCountLabel(sortedStickerFiles.length)} to ${created.title}`)
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
          setStatus("Pack saved")
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
    if (!openPack) return

    const input = stickerUploadInputRef.current
    const files = Array.from(input?.files ?? [])
    if (files.length === 0) return

    const hasInvalid = files.some((file) => Boolean(file.type) && !ACCEPTED_IMAGE_MIME_TYPES.has(file.type))
    if (hasInvalid) {
      setError("Stickers must be PNG files")
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
            setStickerUploadProgress(`Uploading ${i + 1} of ${files.length} · ${stickerId}`)
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
          setStickerUploadSelection(null)
          setStickerUploadProgress(null)
          setStatus(`Uploaded ${stickerCountLabel(files.length)}`)
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
    if (!openPack) return
    if (isUpdatingCover || isPending) return

    const file = coverUpdateInputRef.current?.files?.[0] ?? null
    if (!file) {
      setError("Choose a PNG cover")
      return
    }

    const ok = !file.type || ACCEPTED_IMAGE_MIME_TYPES.has(file.type)
    if (!ok) {
      setError("The cover must be a PNG file")
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
          setStatus("Cover updated")
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

  const copyOpenPack = () => {
    if (!openPack || isPending) return
    startTransition(async () => {
      setError(null)
      try {
        const latest = await fetchAdminPacks()
        const newVersion = Math.max(openPack.packVersion, ...latest.filter((pack) => pack.packKey === openPack.packKey).map((pack) => pack.packVersion)) + 1
        const response = await fetch(`${API_BASE_URL}/admin/stickers/packs/${openPack.packKey}/${openPack.packVersion}/copy`, {
          method: "POST", headers: { ...adminHeaders, "content-type": "application/json" },
          body: JSON.stringify({ newVersion }),
        })
        const body = await response.json()
        if (!response.ok) throw new Error(body.message ?? "Unable to copy pack")
        loadAdminCatalog()
        openPackDialog(body.packKey, body.packVersion)
        setStatus(`Copied to draft version ${body.packVersion}`)
      } catch (err) {
        setError(err instanceof Error ? err.message : "Unable to copy pack")
      }
    })
  }

  const deleteOpenPack = () => {
    if (!openPack || isDeletingPack) return
    const label = openPackDetail?.title?.trim() ? `"${openPackDetail.title}"` : `${openPack.packKey} v${openPack.packVersion}`
    const ok = window.confirm(`Delete sticker pack ${label}? Saved canvases can lose these stickers. Hide the pack instead to keep them working.`)
    if (!ok) return

    setIsDeletingPack(true)
    setError(null)
    setStatus(null)
    void (async () => {
      try {
        const response = await fetch(
          `${API_BASE_URL}/admin/stickers/packs/${openPack.packKey}/${openPack.packVersion}`,
          { method: "DELETE", headers: { ...adminHeaders, "content-type": "application/json" }, body: JSON.stringify({ confirm: true }) },
        )
        if (!response.ok) {
          const body = await response.json().catch(() => null)
          throw new Error(body?.message ?? "Unable to delete sticker pack")
        }
        closePackDialog()
        setStatus("Pack deleted")
        loadAdminCatalog()
      } catch (err) {
        setError(err instanceof Error ? err.message : "Unable to delete sticker pack")
      } finally {
        setIsDeletingPack(false)
      }
    })()
  }

  const selected =
    openPackDetail &&
    openPack &&
    openPackDetail.packKey === openPack.packKey &&
    openPackDetail.packVersion === openPack.packVersion
      ? openPackDetail
      : null
  const defaultStartSortOrder = selected?.stickers?.length ?? 0
  const totalStickers = packs.reduce((sum, pack) => sum + pack.stickerCount, 0)

  const startCreating = () => {
    setStatus(null)
    setError(null)
    setIsCreatingView(true)
  }

  const messages =
    status || error ? (
      <div className="flex flex-col gap-1">
        {status ? <StatusLine tone="neutral">{status}</StatusLine> : null}
        {error ? <StatusLine tone="error">{error}</StatusLine> : null}
      </div>
    ) : null

  const createView = (
    <div className="mx-auto flex w-full max-w-[720px] flex-col gap-6 px-6 py-6">
      <header className="flex items-center justify-between gap-4">
        <h2 className="text-lg font-medium leading-7 text-admin-fg">New pack version</h2>
        <AdminButton variant="ghost" onClick={() => setIsCreatingView(false)} disabled={isCreatingPack}>
          Cancel
        </AdminButton>
      </header>

      {messages}

      <form onSubmit={onCreatePack}>
        <fieldset disabled={isCreatingPack || isPending} className="flex flex-col gap-6">
          <div className="divide-y divide-admin-line rounded-xl bg-admin-fill px-5 py-1">
            <PropertyRow label="Pack key" htmlFor="new-pack-key">
              <input
                id="new-pack-key"
                name="packKey"
                required
                placeholder="kawaii-cats"
                className={cn(fieldClass, "font-mono")}
              />
            </PropertyRow>
            <PropertyRow label="Version" htmlFor="new-pack-version">
              <input
                id="new-pack-version"
                name="packVersion"
                required
                defaultValue={1}
                type="number"
                min={1}
                className={cn(numberFieldClass, "w-28")}
              />
            </PropertyRow>
            <PropertyRow label="Title" htmlFor="new-pack-title">
              <input id="new-pack-title" name="title" required placeholder="Kawaii Cats" className={fieldClass} />
            </PropertyRow>
            <PropertyRow label="Description" htmlFor="new-pack-description" align="start">
              <textarea
                id="new-pack-description"
                name="description"
                rows={3}
                placeholder="Optional"
                className={textareaClass}
              />
            </PropertyRow>
            <PropertyRow label="Sort order" htmlFor="new-pack-sort">
              <input
                id="new-pack-sort"
                name="sortOrder"
                defaultValue={0}
                type="number"
                className={cn(numberFieldClass, "w-28")}
              />
            </PropertyRow>
            <PropertyRow label="Featured" htmlFor="new-pack-featured">
              <Switch id="new-pack-featured" name="featured" defaultChecked />
            </PropertyRow>
            <PropertyRow label="Published" htmlFor="new-pack-published">
              <Switch id="new-pack-published" name="published" defaultChecked />
            </PropertyRow>
          </div>

          <section className="flex flex-col gap-3">
            <SectionLabel>Artwork</SectionLabel>
            <div className="flex flex-col gap-2 rounded-2xl bg-admin-fill p-2">
              <Dropzone
                icon={Upload}
                title={creatingStickerFileNames ?? "Choose stickers"}
                hint="PNG files, or drop them here"
                isActive={isDraggingStickers}
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
              </Dropzone>
              <Dropzone
                icon={ImagePlus}
                title={creatingCoverFileName ?? "Choose cover"}
                hint="Optional, the first sticker otherwise"
              >
                <input
                  ref={createCoverInputRef}
                  type="file"
                  name="cover"
                  accept="image/png"
                  className="sr-only"
                  onChange={(event) => applyCoverFile(event.target.files?.[0] ?? null)}
                />
              </Dropzone>
            </div>
          </section>

          <div className="flex justify-end">
            <AdminButton type="submit" className="h-9" disabled={isCreatingPack}>
              {isCreatingPack ? <Spinner /> : "Create pack version"}
            </AdminButton>
          </div>
        </fieldset>
      </form>
    </div>
  )

  const detailView = selected ? (
    <div
      key={`${selected.packKey}:${selected.packVersion}:${selected.updatedAt}`}
      className="mx-auto flex w-full max-w-[960px] flex-col gap-6 px-6 py-6"
    >
      <header className="flex items-center gap-4">
        <div className="size-14 shrink-0 rounded-xl bg-admin-fill p-1.5">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={selected.coverThumbnailUrl} alt="" className="h-full w-full object-contain" />
        </div>
        <div className="min-w-0 flex-1">
          <h2 className="truncate text-lg font-medium leading-7 text-admin-fg">{selected.title}</h2>
          <p className="truncate font-mono text-xs tabular-nums text-admin-muted">
            {selected.packKey} · v{selected.packVersion} · {stickerCountLabel(selected.stickers?.length ?? 0)}
          </p>
        </div>
        <div className="flex items-center gap-4">
          {isPending ? <Spinner className="text-admin-muted" /> : null}
          {selected.featured ? <span className="text-xs text-admin-fg-2">Featured</span> : null}
          <PublishState published={selected.published} />
        </div>
      </header>

      <Segmented value={detailTab} options={DETAIL_TABS} onChange={setDetailTab} />

      {messages}

      {selected.contentLocked ? (
        <div className="flex items-center justify-between gap-4 rounded-xl border border-admin-line px-5 py-4">
          <p className="text-sm text-admin-fg">This version has been published. Copy it to a draft to edit its art.</p>
          <AdminButton onClick={copyOpenPack} disabled={isPending}>Copy to draft</AdminButton>
        </div>
      ) : null}

      {detailTab === "stickers" ? (
        <div className="flex flex-col gap-6">
          <form onSubmit={onUploadStickers} className="flex flex-col gap-2">
            <fieldset
              disabled={selected.contentLocked || isUploadingStickers || isPending}
              className="flex flex-col gap-2 rounded-2xl bg-admin-fill p-2 sm:flex-row sm:items-center"
            >
              <Dropzone
                icon={Upload}
                title={stickerUploadSelection ?? "Add stickers"}
                hint="PNG files, named by sticker ID"
                className="flex-1"
              >
                <input
                  ref={stickerUploadInputRef}
                  type="file"
                  accept="image/png"
                  multiple
                  className="sr-only"
                  onChange={(event) => {
                    const count = event.target.files?.length ?? 0
                    setStickerUploadSelection(count > 0 ? stickerCountLabel(count, "selected") : null)
                  }}
                />
              </Dropzone>
              <label className="flex h-14 items-center gap-3 rounded-ctl border border-admin-line-strong bg-admin-panel px-3">
                <span className="text-xs text-admin-muted">Start at</span>
                <input
                  name="startSortOrder"
                  type="number"
                  defaultValue={defaultStartSortOrder}
                  className="w-12 bg-transparent font-mono text-sm tabular-nums text-admin-fg outline-none [appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none"
                />
              </label>
              <AdminButton type="submit" className="h-9 sm:mx-2" disabled={!stickerUploadSelection}>
                {isUploadingStickers ? <Spinner /> : "Upload"}
              </AdminButton>
            </fieldset>

            {stickerUploadProgress ? (
              <p className="px-2 font-mono text-xs tabular-nums text-admin-muted">{stickerUploadProgress}</p>
            ) : null}
          </form>

          {selected.stickers?.length ? (
            <ul className="grid grid-cols-[repeat(auto-fill,minmax(112px,1fr))] gap-x-3 gap-y-4">
              {selected.stickers.map((sticker) => (
                <li key={sticker.stickerId} className="min-w-0" title={sticker.stickerId}>
                  <div className="aspect-square rounded-xl bg-admin-fill p-3 transition-[background-color] duration-150 ease-admin hover:bg-admin-fill-2">
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img
                      src={sticker.thumbnailUrl}
                      alt={sticker.stickerId}
                      className="h-full w-full object-contain"
                      loading="lazy"
                    />
                  </div>
                  <p className="mt-2 truncate px-0.5 font-mono text-xs text-admin-muted">{sticker.stickerId}</p>
                </li>
              ))}
            </ul>
          ) : (
            <EmptyState icon={Sticker} title="No stickers yet" />
          )}
        </div>
      ) : (
        <div className="flex flex-col gap-8">
          <form onSubmit={onUpdatePack}>
            <fieldset disabled={isPending || isUploadingStickers || isCreatingPack} className="flex flex-col gap-3">
              <div className="divide-y divide-admin-line rounded-xl bg-admin-fill px-5 py-1">
                <PropertyRow label="Title" htmlFor="pack-title">
                  <input id="pack-title" name="title" required defaultValue={selected.title} className={fieldClass} />
                </PropertyRow>
                <PropertyRow label="Description" htmlFor="pack-description" align="start">
                  <textarea
                    id="pack-description"
                    name="description"
                    rows={2}
                    defaultValue={selected.description ?? ""}
                    placeholder="Optional"
                    className={textareaClass}
                  />
                </PropertyRow>
                <PropertyRow label="Sort order" htmlFor="pack-sort">
                  <input
                    id="pack-sort"
                    name="sortOrder"
                    type="number"
                    defaultValue={selected.sortOrder ?? 0}
                    className={cn(numberFieldClass, "w-28")}
                  />
                </PropertyRow>
                <PropertyRow label="Featured" htmlFor="pack-featured">
                  <Switch id="pack-featured" name="featured" defaultChecked={Boolean(selected.featured)} />
                </PropertyRow>
                <PropertyRow label="Published" htmlFor="pack-published">
                  <Switch id="pack-published" name="published" defaultChecked={Boolean(selected.published)} />
                </PropertyRow>
              </div>
              <div className="flex justify-end">
                <AdminButton type="submit" className="h-9 min-w-20">
                  Save
                </AdminButton>
              </div>
            </fieldset>
          </form>

          <section className="flex flex-col gap-3">
            <SectionLabel>Cover</SectionLabel>
            <form onSubmit={onUpdateCover}>
              <fieldset
                disabled={selected.contentLocked || isUpdatingCover || isPending}
                className="flex flex-col gap-2 rounded-2xl bg-admin-fill p-2 sm:flex-row sm:items-center"
              >
                <div className="size-14 shrink-0 rounded-ctl bg-admin-panel p-1.5">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img
                    src={selected.coverThumbnailUrl}
                    alt={`${selected.title} cover`}
                    className="h-full w-full object-contain"
                    loading="lazy"
                  />
                </div>
                <Dropzone icon={ImagePlus} title={coverUpdateFileName ?? "Choose a new cover"} hint="PNG" className="flex-1">
                  <input
                    ref={coverUpdateInputRef}
                    type="file"
                    accept="image/png"
                    className="sr-only"
                    onChange={(event) => applyCoverUpdateFile(event.target.files?.[0] ?? null)}
                  />
                </Dropzone>
                <AdminButton type="submit" variant="secondary" disabled={!coverUpdateFileName} className="h-9 sm:mx-2">
                  {isUpdatingCover ? <Spinner /> : "Replace"}
                </AdminButton>
              </fieldset>
            </form>
          </section>

          <section className="flex items-center justify-between gap-4 rounded-xl border border-admin-line px-5 py-4">
            <p className="text-sm text-admin-fg">Delete this pack and its stickers</p>
            <AdminButton variant="danger" onClick={deleteOpenPack} disabled={isDeletingPack || isPending}>
              {isDeletingPack ? <Spinner /> : "Delete"}
            </AdminButton>
          </section>
        </div>
      )}
    </div>
  ) : null

  return (
    <>
      <TopBar
        icon={Sticker}
        title="Stickers"
        meta={
          packs.length > 0
            ? `${packs.length} pack${packs.length === 1 ? "" : "s"} · ${stickerCountLabel(totalStickers)}`
            : null
        }
      >
        <AdminButton onClick={startCreating} disabled={isCreatingView}>
          <Plus />
          New pack
        </AdminButton>
      </TopBar>

      <div className="flex min-h-0 flex-1 flex-col lg:flex-row">
        <aside
          aria-label="Sticker packs"
          className="shrink-0 border-b border-admin-line lg:w-80 lg:overflow-y-auto lg:border-b-0 lg:border-r"
        >
          {packs.length === 0 ? (
            <EmptyState icon={Sticker} title="No sticker packs yet" />
          ) : (
            <ul className="flex flex-col gap-0.5 p-2">
              {packs.map((pack) => {
                const isActive =
                  !isCreatingView &&
                  openPack?.packKey === pack.packKey &&
                  openPack.packVersion === pack.packVersion
                return (
                  <li key={`${pack.packKey}:${pack.packVersion}`}>
                    <button
                      type="button"
                      aria-current={isActive ? "true" : undefined}
                      onClick={() => {
                        setDetailTab("stickers")
                        setStickerUploadSelection(null)
                        openPackDialog(pack.packKey, pack.packVersion)
                      }}
                      className={cn(
                        "flex w-full items-center gap-3 rounded-xl p-1.5 pr-3 text-left outline-none",
                        "transition-[background-color] duration-150 ease-admin focus-visible:ring-2 focus-visible:ring-admin-fg/20",
                        isActive ? "bg-admin-fill-2" : "hover:bg-admin-fill"
                      )}
                    >
                      <span className="size-11 shrink-0 rounded-tag bg-admin-fill p-1">
                        {/* eslint-disable-next-line @next/next/no-img-element */}
                        <img src={pack.coverThumbnailUrl} alt="" className="h-full w-full object-contain" loading="lazy" />
                      </span>
                      <span className="flex min-w-0 flex-1 flex-col">
                        <span className="truncate text-sm text-admin-fg">{pack.title}</span>
                        <span className="truncate font-mono text-xs text-admin-muted">
                          {pack.packKey} · v{pack.packVersion}
                        </span>
                      </span>
                      <span className="flex shrink-0 items-center gap-2 font-mono text-xs tabular-nums text-admin-muted">
                        {pack.stickerCount}
                        {pack.published ? <LiveDot /> : null}
                      </span>
                    </button>
                  </li>
                )
              })}
            </ul>
          )}
        </aside>

        <section className="min-h-0 min-w-0 flex-1 overflow-y-auto">
          {isCreatingView ? (
            createView
          ) : detailView ? (
            detailView
          ) : openPack ? (
            <div className="flex h-full min-h-64 items-center justify-center text-admin-muted">
              <Spinner />
            </div>
          ) : (
            <div className="flex h-full flex-col">
              {messages ? <div className="px-6 pt-6">{messages}</div> : null}
              <EmptyState icon={Sticker} title="Choose a pack, or start a new one" />
            </div>
          )}
        </section>
      </div>
    </>
  )
}

function stickerCountLabel(count: number, suffix?: string): string {
  const base = `${count} sticker${count === 1 ? "" : "s"}`
  return suffix ? `${base} ${suffix}` : base
}
