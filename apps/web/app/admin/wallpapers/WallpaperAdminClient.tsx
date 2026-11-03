"use client"

import { ImageIcon, ImagePlus, Pencil, Trash2 } from "lucide-react"
import { FormEvent, useEffect, useRef, useState, useTransition } from "react"

import { API_BASE_URL } from "@/lib/constants"
import { cn } from "@/lib/utils"

import { useAdmin } from "../AdminShell"
import { ActionMenu, REVEAL_IN_GROUP, Sheet, type MenuAction } from "../overlays"
import {
  AdminButton,
  Dropzone,
  EmptyState,
  Field,
  fieldClass,
  numberFieldClass,
  PropertyRow,
  Spinner,
  StatusLine,
  Switch,
  textareaClass,
  TopBar
} from "../ui"

const ACCEPTED_IMAGE_MIME_TYPES = new Set(["image/jpeg", "image/png", "image/webp"])

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
  sortOrder: number
  published: boolean
}

type WallpaperDraft = Pick<WallpaperItem, "title" | "description" | "published"> & { sortOrder: string }

const toDraft = (item: WallpaperItem): WallpaperDraft => ({
  title: item.title,
  description: item.description ?? "",
  sortOrder: String(item.sortOrder),
  published: item.published,
})

const isDraftChanged = (draft: WallpaperDraft, item: WallpaperItem) =>
  draft.title.trim() !== item.title ||
  draft.description.trim() !== (item.description ?? "") ||
  draft.sortOrder.trim() !== String(item.sortOrder) ||
  draft.published !== item.published

// The wallpaper's caption rises into place with the overlay.
const CAPTION_RISE =
  "group-hover:translate-y-0 group-has-[:focus-visible]:translate-y-0 group-has-[[aria-expanded=true]]:translate-y-0 [@media(hover:none)]:translate-y-0"

const wallpaperUrl = (id: string) => `${API_BASE_URL}/admin/wallpapers/${encodeURIComponent(id)}`

async function readErrorMessage(response: Response, fallback: string): Promise<string> {
  const body: unknown = await response.json().catch(() => null)
  if (body && typeof body === "object" && "message" in body && typeof body.message === "string") return body.message
  return fallback
}

type CatalogResponse = {
  items: WallpaperItem[]
  nextCursor?: string | null
}

export default function WallpaperAdminPage() {
  const { adminHeaders } = useAdmin()
  const [items, setItems] = useState<WallpaperItem[]>([])
  const [error, setError] = useState<string | null>(null)
  const [status, setStatus] = useState<string | null>(null)
  const [selectedFileName, setSelectedFileName] = useState<string | null>(null)
  const [isDraggingImage, setIsDraggingImage] = useState(false)
  const [isUploading, setIsUploading] = useState(false)
  const [deletingId, setDeletingId] = useState<string | null>(null)
  const [, startTransition] = useTransition()
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  const dragDepthRef = useRef(0)

  const [sheetItem, setSheetItem] = useState<WallpaperItem | null>(null)
  const [isSheetOpen, setIsSheetOpen] = useState(false)
  const [draft, setDraft] = useState<WallpaperDraft | null>(null)
  const [replaceFileName, setReplaceFileName] = useState<string | null>(null)
  const [isSaving, setIsSaving] = useState(false)
  const [sheetError, setSheetError] = useState<string | null>(null)
  const [sheetStatus, setSheetStatus] = useState<string | null>(null)
  const replaceInputRef = useRef<HTMLInputElement | null>(null)

  const fetchAdminCatalog = async (): Promise<WallpaperItem[]> => {
    const response = await fetch(`${API_BASE_URL}/admin/wallpapers`, {
      cache: "no-store",
      headers: adminHeaders,
    })
    if (!response.ok) {
      const body = await response.json().catch(() => null)
      throw new Error(body?.message ?? "Unable to load wallpaper catalog")
    }
    const body = (await response.json()) as CatalogResponse
    return body.items
  }

  const loadAdminCatalog = () => {
    startTransition(() => {
      void (async () => {
        setError(null)
        try {
          const nextItems = await fetchAdminCatalog()
          setItems(nextItems)
        } catch (err) {
          setError(err instanceof Error ? err.message : "Unable to load wallpaper catalog")
        }
      })()
    })
  }

  useEffect(() => {
    loadAdminCatalog()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const setFileInputValue = (file: File | null) => {
    const input = fileInputRef.current
    if (!input) return

    if (typeof DataTransfer === "undefined") return
    const dataTransfer = new DataTransfer()
    if (file) dataTransfer.items.add(file)
    input.files = dataTransfer.files
  }

  const applySelectedFile = (file: File | null) => {
    setError(null)
    setStatus(null)
    setFileInputValue(file)
    setSelectedFileName(file?.name ?? null)
  }

  const onDropImage = (event: React.DragEvent<HTMLLabelElement>) => {
    event.preventDefault()
    event.stopPropagation()
    setIsDraggingImage(false)
    dragDepthRef.current = 0

    const file = event.dataTransfer.files?.[0]
    if (!file) return

    const hasMimeType = Boolean(file.type)
    const isAcceptedMimeType = hasMimeType ? ACCEPTED_IMAGE_MIME_TYPES.has(file.type) : true
    const extension = file.name.split(".").pop()?.toLowerCase()
    const isAcceptedExtension = extension ? ["jpg", "jpeg", "png", "webp"].includes(extension) : true

    if (!isAcceptedMimeType || !isAcceptedExtension) {
      applySelectedFile(null)
      setError("Use a JPG, PNG or WebP image")
      return
    }

    applySelectedFile(file)
  }

  const onUpload = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (isUploading || deletingId) return

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
        applySelectedFile(null)
        setStatus("Wallpaper uploaded")
        loadAdminCatalog()
      } catch (err) {
        setError(err instanceof Error ? err.message : "Wallpaper upload failed")
      } finally {
        setIsUploading(false)
      }
    })()
  }

  const deleteWallpaper = (item: WallpaperItem) => {
    if (isUploading || deletingId) return
    if (!window.confirm(`Delete "${item.title}"? Saved links can stop working. Unpublish it instead to keep them working.`)) return

    setDeletingId(item.id)
    setError(null)
    setStatus(null)
    setSheetError(null)
    void (async () => {
      try {
        const response = await fetch(wallpaperUrl(item.id), {
          method: "DELETE",
          headers: { ...adminHeaders, "content-type": "application/json" },
          body: JSON.stringify({ confirm: true }),
        })
        if (!response.ok) throw new Error(await readErrorMessage(response, "Unable to delete wallpaper"))
        if (sheetItem?.id === item.id) setIsSheetOpen(false)
        setStatus("Wallpaper deleted")
        loadAdminCatalog()
      } catch (err) {
        const message = err instanceof Error ? err.message : "Unable to delete wallpaper"
        if (isSheetOpen) setSheetError(message)
        else setError(message)
      } finally {
        setDeletingId(null)
      }
    })()
  }

  const openSheet = (item: WallpaperItem) => {
    setSheetItem(item)
    setDraft(toDraft(item))
    setReplaceFileName(null)
    setSheetError(null)
    setSheetStatus(null)
    if (replaceInputRef.current) replaceInputRef.current.value = ""
    setIsSheetOpen(true)
  }

  // Keep showing the opened wallpaper while the catalog refetches.
  const shownItem = (sheetItem && items.find((item) => item.id === sheetItem.id)) ?? sheetItem
  const isSheetDirty = Boolean(shownItem && draft && (replaceFileName !== null || isDraftChanged(draft, shownItem)))

  const onSaveWallpaper = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!shownItem || !draft || isSaving) return
    const item = shownItem

    const title = draft.title.trim()
    if (!title) {
      setSheetError("Give the wallpaper a name")
      return
    }
    const sortOrder = Number(draft.sortOrder)
    if (!draft.sortOrder.trim() || !Number.isSafeInteger(sortOrder)) {
      setSheetError("Sort order must be a whole number")
      return
    }
    const file = replaceInputRef.current?.files?.[0] ?? null
    if (file && file.type && !ACCEPTED_IMAGE_MIME_TYPES.has(file.type)) {
      setSheetError("Use a JPG, PNG or WebP image")
      return
    }

    setIsSaving(true)
    setSheetError(null)
    setSheetStatus(null)
    void (async () => {
      try {
        if (isDraftChanged(draft, item)) {
          const response = await fetch(wallpaperUrl(item.id), {
            method: "PATCH",
            headers: { ...adminHeaders, "content-type": "application/json" },
            body: JSON.stringify({ title, description: draft.description.trim(), sortOrder, published: draft.published }),
          })
          if (!response.ok) throw new Error(await readErrorMessage(response, "Unable to save wallpaper"))
        }
        if (file) {
          const formData = new FormData()
          formData.set("image", file, file.name)
          const response = await fetch(`${wallpaperUrl(item.id)}/image`, {
            method: "PUT",
            headers: adminHeaders,
            body: formData,
          })
          if (!response.ok) throw new Error(await readErrorMessage(response, "Unable to replace the image"))
          if (replaceInputRef.current) replaceInputRef.current.value = ""
          setReplaceFileName(null)
        }
        setSheetStatus("Saved")
        loadAdminCatalog()
      } catch (err) {
        setSheetError(err instanceof Error ? err.message : "Unable to save wallpaper")
      } finally {
        setIsSaving(false)
      }
    })()
  }

  const wallpaperActions = (item: WallpaperItem): MenuAction[] => [
    { label: "Edit", icon: Pencil, onSelect: () => openSheet(item) },
    { label: "Delete", icon: Trash2, tone: "danger", disabled: Boolean(deletingId), onSelect: () => deleteWallpaper(item) },
  ]

  return (
    <>
      <TopBar
        icon={ImageIcon}
        title="Wallpapers"
        meta={items.length > 0 ? items.length : null}
      />

      <div className="flex min-h-0 flex-1 flex-col-reverse lg:flex-row">
        <section aria-label="Wallpapers" className="min-h-0 min-w-0 flex-1 overflow-y-auto p-6">
          {items.length === 0 ? (
            <EmptyState icon={ImageIcon} title="No wallpapers yet" />
          ) : (
            <ul className="grid grid-cols-[repeat(auto-fill,minmax(168px,1fr))] gap-4">
              {items.map((item) => (
                <li key={item.id} className="group relative min-w-0">
                  <button
                    type="button"
                    onClick={() => openSheet(item)}
                    aria-label={item.title}
                    className={cn(
                      "relative block aspect-[9/19.5] w-full overflow-hidden rounded-2xl text-left outline-none",
                      "focus-visible:ring-2 focus-visible:ring-admin-fg/40 focus-visible:ring-offset-2 focus-visible:ring-offset-admin-panel"
                    )}
                    style={{ backgroundColor: item.dominantColor }}
                  >
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img src={item.thumbnailUrl} alt="" className="h-full w-full object-cover" loading="lazy" />
                    <span
                      aria-hidden
                      className={cn(
                        "pointer-events-none absolute inset-x-0 bottom-0 h-3/5 bg-linear-to-t from-black/85 via-black/40 to-transparent",
                        "opacity-0 transition-opacity duration-200 ease-admin",
                        REVEAL_IN_GROUP
                      )}
                    />
                    <span
                      className={cn(
                        "pointer-events-none absolute inset-x-0 bottom-0 flex flex-col gap-1 p-4",
                        "translate-y-2 opacity-0 transition-[opacity,translate] duration-200 ease-admin motion-reduce:translate-y-0",
                        REVEAL_IN_GROUP,
                        CAPTION_RISE
                      )}
                    >
                      <span className="truncate text-sm font-medium text-white">{item.title}</span>
                      {item.description ? (
                        <span className="line-clamp-3 text-xs leading-[18px] text-white/75">{item.description}</span>
                      ) : null}
                      <span className="font-mono text-[11px] tabular-nums text-white/55">
                        {item.width} × {item.height}
                      </span>
                    </span>
                    <span aria-hidden className="pointer-events-none absolute inset-0 rounded-2xl ring-1 ring-inset ring-black/5" />
                    {item.published ? null : (
                      <span className="absolute left-2 top-2 rounded-tag bg-black/50 px-2 py-0.5 text-[11px] text-white backdrop-blur-sm">
                        Draft
                      </span>
                    )}
                    {deletingId === item.id ? (
                      <span className="absolute inset-0 flex items-center justify-center bg-black/40 text-white">
                        <Spinner />
                      </span>
                    ) : null}
                  </button>
                  <ActionMenu
                    label={`${item.title} actions`}
                    actions={wallpaperActions(item)}
                    className={cn(
                      "absolute right-2 top-2 size-7 rounded-full bg-black/45 text-white opacity-0 backdrop-blur-sm",
                      "transition-[opacity,background-color,scale] hover:bg-black/60 hover:text-white",
                      REVEAL_IN_GROUP
                    )}
                  />
                </li>
              ))}
            </ul>
          )}
        </section>

        <aside className="shrink-0 border-b border-admin-line lg:w-80 lg:overflow-y-auto lg:border-b-0 lg:border-l">
          <form onSubmit={onUpload} className="flex flex-col gap-4 p-5">
            <h2 className="text-sm font-medium text-admin-fg">New wallpaper</h2>

            <fieldset disabled={isUploading} className="flex flex-col gap-4">
              <Dropzone
                icon={ImagePlus}
                title={selectedFileName ?? "Choose image"}
                hint="JPG, PNG or WebP"
                isActive={isDraggingImage}
                onDragEnter={(event) => {
                  event.preventDefault()
                  event.stopPropagation()
                  dragDepthRef.current += 1
                  setIsDraggingImage(true)
                }}
                onDragOver={(event) => {
                  event.preventDefault()
                  event.stopPropagation()
                  event.dataTransfer.dropEffect = "copy"
                  setIsDraggingImage(true)
                }}
                onDragLeave={(event) => {
                  event.preventDefault()
                  event.stopPropagation()
                  dragDepthRef.current = Math.max(0, dragDepthRef.current - 1)
                  if (dragDepthRef.current === 0) setIsDraggingImage(false)
                }}
                onDrop={onDropImage}
              >
                <input
                  ref={fileInputRef}
                  name="image"
                  type="file"
                  accept="image/png,image/jpeg,image/webp"
                  required
                  className="sr-only"
                  onChange={(event) => applySelectedFile(event.target.files?.[0] ?? null)}
                />
              </Dropzone>

              <Field label="Name">
                <input name="title" required className={fieldClass} />
              </Field>

              <Field label="Description">
                <textarea name="description" rows={3} placeholder="Optional" className={textareaClass} />
              </Field>

              <input name="sortOrder" type="hidden" value="0" />
              <input name="published" type="hidden" value="true" />

              <AdminButton type="submit" disabled={isUploading || Boolean(deletingId)} className="h-9 w-full">
                {isUploading ? <Spinner /> : "Upload"}
              </AdminButton>
            </fieldset>

            {status ? <StatusLine tone="neutral">{status}</StatusLine> : null}
            {error ? <StatusLine tone="error">{error}</StatusLine> : null}
          </form>
        </aside>
      </div>

      <Sheet
        open={isSheetOpen}
        onClose={() => setIsSheetOpen(false)}
        title={shownItem?.title}
        footer={
          shownItem ? (
            <>
              <AdminButton
                variant="danger"
                onClick={() => deleteWallpaper(shownItem)}
                disabled={isSaving || Boolean(deletingId)}
              >
                {deletingId === shownItem.id ? <Spinner /> : "Delete"}
              </AdminButton>
              <AdminButton
                type="submit"
                form="wallpaper-sheet-form"
                className="min-w-20"
                disabled={!isSheetDirty || isSaving || Boolean(deletingId)}
              >
                {isSaving ? <Spinner /> : "Save"}
              </AdminButton>
            </>
          ) : null
        }
      >
        {shownItem && draft ? (
          <div className="flex flex-col gap-6 p-5">
            <figure className="flex flex-col items-center gap-2">
              <div
                className="relative aspect-[9/19.5] h-[min(56vh,520px)] overflow-hidden rounded-[28px]"
                style={{ backgroundColor: shownItem.dominantColor }}
              >
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={shownItem.previewUrl} alt={shownItem.title} className="h-full w-full object-cover" />
                <span aria-hidden className="pointer-events-none absolute inset-0 rounded-[28px] ring-1 ring-inset ring-black/5" />
              </div>
              <figcaption className="font-mono text-xs tabular-nums text-admin-muted">
                {shownItem.width} × {shownItem.height}
              </figcaption>
            </figure>

            <form id="wallpaper-sheet-form" onSubmit={onSaveWallpaper}>
              <fieldset disabled={isSaving} className="flex flex-col gap-3">
                <div className="divide-y divide-admin-line rounded-xl bg-admin-fill px-4 py-1">
                  <PropertyRow label="Name" htmlFor="wallpaper-title">
                    <input
                      id="wallpaper-title"
                      required
                      value={draft.title}
                      onChange={(event) => setDraft({ ...draft, title: event.target.value })}
                      className={fieldClass}
                    />
                  </PropertyRow>
                  <PropertyRow label="Description" htmlFor="wallpaper-description" align="start">
                    <textarea
                      id="wallpaper-description"
                      rows={3}
                      placeholder="Optional"
                      value={draft.description}
                      onChange={(event) => setDraft({ ...draft, description: event.target.value })}
                      className={textareaClass}
                    />
                  </PropertyRow>
                  <PropertyRow label="Sort order" htmlFor="wallpaper-sort">
                    <input
                      id="wallpaper-sort"
                      type="number"
                      value={draft.sortOrder}
                      onChange={(event) => setDraft({ ...draft, sortOrder: event.target.value })}
                      className={cn(numberFieldClass, "w-28")}
                    />
                  </PropertyRow>
                  <PropertyRow label="Published" htmlFor="wallpaper-published">
                    <Switch
                      id="wallpaper-published"
                      checked={draft.published}
                      onChange={(event) => setDraft({ ...draft, published: event.target.checked })}
                    />
                  </PropertyRow>
                </div>
                <Dropzone icon={ImagePlus} title={replaceFileName ?? "Replace image"} hint="JPG, PNG or WebP">
                  <input
                    ref={replaceInputRef}
                    type="file"
                    accept="image/png,image/jpeg,image/webp"
                    className="sr-only"
                    onChange={(event) => setReplaceFileName(event.target.files?.[0]?.name ?? null)}
                  />
                </Dropzone>
              </fieldset>
            </form>

            {sheetStatus || sheetError ? (
              <div className="flex flex-col gap-1">
                {sheetStatus ? <StatusLine tone="neutral">{sheetStatus}</StatusLine> : null}
                {sheetError ? <StatusLine tone="error">{sheetError}</StatusLine> : null}
              </div>
            ) : null}
          </div>
        ) : null}
      </Sheet>
    </>
  )
}
