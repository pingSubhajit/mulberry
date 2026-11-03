"use client"

import { ImageIcon, ImagePlus, Trash2 } from "lucide-react"
import { FormEvent, useEffect, useRef, useState, useTransition } from "react"

import { API_BASE_URL } from "@/lib/constants"

import { useAdmin } from "../AdminShell"
import {
  AdminButton,
  Dropzone,
  EmptyState,
  Field,
  fieldClass,
  IconButton,
  PublishState,
  Spinner,
  StatusLine,
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
  sortOrder?: number
  published?: boolean
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

  const deleteWallpaper = (id: string) => {
    if (isUploading || deletingId) return

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
        setStatus("Wallpaper deleted")
        loadAdminCatalog()
      } catch (err) {
        setError(err instanceof Error ? err.message : "Unable to delete wallpaper")
      } finally {
        setDeletingId(null)
      }
    })()
  }

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
            <ul className="grid grid-cols-[repeat(auto-fill,minmax(240px,1fr))] gap-x-4 gap-y-6">
              {items.map((item) => (
                <li key={item.id} className="group min-w-0">
                  <div
                    className="relative aspect-[171/103] overflow-hidden rounded-xl"
                    style={{ backgroundColor: item.dominantColor }}
                  >
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img src={item.thumbnailUrl} alt={item.title} className="h-full w-full object-cover" loading="lazy" />
                    <span aria-hidden className="pointer-events-none absolute inset-0 rounded-xl ring-1 ring-inset ring-black/5" />
                    <IconButton
                      tone="danger"
                      aria-label={deletingId === item.id ? `Deleting ${item.title}` : `Delete ${item.title}`}
                      onClick={() => deleteWallpaper(item.id)}
                      disabled={Boolean(deletingId) || isUploading}
                      className="absolute right-2 top-2 bg-admin-panel text-admin-fg-2 shadow-[0_1px_2px_rgba(0,0,0,0.12)] hover:bg-admin-panel"
                    >
                      {deletingId === item.id ? <Spinner /> : <Trash2 />}
                    </IconButton>
                  </div>

                  <div className="flex flex-col gap-0.5 px-1 pt-3">
                    <div className="flex items-center justify-between gap-3">
                      <h3 className="truncate text-sm text-admin-fg">{item.title}</h3>
                      {item.published === undefined ? null : <PublishState published={item.published} />}
                    </div>
                    {item.description ? (
                      <p className="line-clamp-2 text-[13px] leading-5 text-admin-fg-2">{item.description}</p>
                    ) : null}
                    <p className="font-mono text-xs tabular-nums text-admin-muted">
                      {item.width} × {item.height}
                    </p>
                  </div>
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
    </>
  )
}
