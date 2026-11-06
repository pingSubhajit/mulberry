"use client"

import { Copy, Eye, EyeOff, FileJson, Frame, ImagePlus, Lock, Pencil, Trash2 } from "lucide-react"
import { FormEvent, useEffect, useRef, useState } from "react"

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
  PublishState,
  Spinner,
  StatusLine,
  Switch,
  TopBar
} from "../ui"
import { FramePreview, SAMPLE_CAPTION } from "./FramePreview"
import type { ShareFrameItem, ShareFrameLayout } from "./types"

type FrameRef = Pick<ShareFrameItem, "key" | "version" | "title">

type MetadataDraft = {
  title: string
  sortOrder: string
  availableFrom: string
  availableUntil: string
  published: boolean
}

type MetadataChanges = Partial<{
  title: string
  sortOrder: number
  availableFrom: string | null
  availableUntil: string | null
  published: boolean
}>

/** A layout file the admin picked, read and parsed for the preview; the backend validates it fully. */
type LayoutSelection = { fileName: string; text: string; layout: ShareFrameLayout | null; error: string | null }

const frameId = (frame: Pick<ShareFrameItem, "key" | "version">) => `${frame.key}:${frame.version}`
const framePath = (frame: Pick<ShareFrameItem, "key" | "version">) =>
  `/admin/share-frames/${encodeURIComponent(frame.key)}/${frame.version}`
const frameLabel = (frame: FrameRef) => `"${frame.title}" v${frame.version}`

const toDraft = (item: ShareFrameItem): MetadataDraft => ({
  title: item.title,
  sortOrder: String(item.sortOrder),
  availableFrom: item.availableFrom ?? "",
  availableUntil: item.availableUntil ?? "",
  published: item.published
})

function metadataChanges(draft: MetadataDraft, item: ShareFrameItem): MetadataChanges {
  const changes: MetadataChanges = {}
  if (draft.title.trim() !== item.title) changes.title = draft.title.trim()
  if (draft.sortOrder.trim() !== String(item.sortOrder)) changes.sortOrder = Number(draft.sortOrder)
  if (draft.availableFrom !== (item.availableFrom ?? "")) changes.availableFrom = draft.availableFrom || null
  if (draft.availableUntil !== (item.availableUntil ?? "")) changes.availableUntil = draft.availableUntil || null
  if (draft.published !== item.published) changes.published = draft.published
  return changes
}

const DATE_FORMAT = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" })
const formatDate = (day: string) => DATE_FORMAT.format(new Date(`${day}T00:00:00Z`))

function windowLabel(from: string | null, until: string | null): string {
  if (from && until) return `${formatDate(from)} – ${formatDate(until)}`
  if (from) return `From ${formatDate(from)}`
  if (until) return `Until ${formatDate(until)}`
  return "Always"
}

async function readLayoutFile(file: File): Promise<LayoutSelection> {
  const text = await file.text()
  try {
    const layout = JSON.parse(text) as ShareFrameLayout
    if (!layout?.content?.quad || !layout.caption || !layout.brand) {
      return { fileName: file.name, text, layout: null, error: "Not a share frame layout" }
    }
    return { fileName: file.name, text, layout, error: null }
  } catch {
    return { fileName: file.name, text, layout: null, error: "The layout isn't valid JSON" }
  }
}

/** An object URL for a picked file, revoked when the file changes or the page closes. */
function useObjectUrl(file: File | null): string | null {
  const [url, setUrl] = useState<string | null>(null)
  useEffect(() => {
    if (!file) {
      setUrl(null)
      return
    }
    const next = URL.createObjectURL(file)
    setUrl(next)
    return () => URL.revokeObjectURL(next)
  }, [file])
  return url
}

function PreviewControls({
  id,
  showSafeZones,
  onShowSafeZones,
  caption,
  onCaption
}: Readonly<{ id: string; showSafeZones: boolean; onShowSafeZones: (value: boolean) => void; caption: string; onCaption: (value: string) => void }>) {
  return (
    <div className="flex flex-col gap-3">
      <input
        aria-label="Caption"
        value={caption}
        maxLength={60}
        onChange={(event) => onCaption(event.target.value)}
        className={fieldClass}
      />
      <label htmlFor={id} className="flex items-center justify-between gap-3 text-[13px] text-admin-fg-2">
        Safe zones
        <Switch id={id} checked={showSafeZones} onChange={(event) => onShowSafeZones(event.target.checked)} />
      </label>
    </div>
  )
}

export default function ShareFrameAdminClient() {
  const { adminFetch } = useAdmin()
  const [items, setItems] = useState<ShareFrameItem[]>([])
  const [isLoaded, setIsLoaded] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [status, setStatus] = useState<string | null>(null)
  const [pendingId, setPendingId] = useState<string | null>(null)

  // New frame form.
  const [isUploading, setIsUploading] = useState(false)
  const [newImage, setNewImage] = useState<File | null>(null)
  const [newLayout, setNewLayout] = useState<LayoutSelection | null>(null)
  const [newKey, setNewKey] = useState("")
  const [newShowSafeZones, setNewShowSafeZones] = useState(false)
  const [newCaption, setNewCaption] = useState(SAMPLE_CAPTION)
  const newImageUrl = useObjectUrl(newImage)
  const newImageInputRef = useRef<HTMLInputElement | null>(null)
  const newLayoutInputRef = useRef<HTMLInputElement | null>(null)

  // Edit sheet.
  const [sheetRef, setSheetRef] = useState<Pick<ShareFrameItem, "key" | "version"> | null>(null)
  const [sheetSnapshot, setSheetSnapshot] = useState<ShareFrameItem | null>(null)
  const [isSheetOpen, setIsSheetOpen] = useState(false)
  const [draft, setDraft] = useState<MetadataDraft | null>(null)
  const [replaceImage, setReplaceImage] = useState<File | null>(null)
  const [replaceLayout, setReplaceLayout] = useState<LayoutSelection | null>(null)
  const [showSafeZones, setShowSafeZones] = useState(false)
  const [caption, setCaption] = useState(SAMPLE_CAPTION)
  const [isSaving, setIsSaving] = useState(false)
  const [sheetError, setSheetError] = useState<string | null>(null)
  const [sheetStatus, setSheetStatus] = useState<string | null>(null)
  const replaceImageUrl = useObjectUrl(replaceImage)
  const replaceImageInputRef = useRef<HTMLInputElement | null>(null)
  const replaceLayoutInputRef = useRef<HTMLInputElement | null>(null)

  const fetchFrames = async (): Promise<ShareFrameItem[]> => {
    const response = await adminFetch("/admin/share-frames")
    return ((await response.json()) as { items: ShareFrameItem[] }).items
  }

  const reload = async () => {
    try {
      setItems(await fetchFrames())
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load share frames")
    } finally {
      setIsLoaded(true)
    }
  }

  useEffect(() => {
    void reload()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const resetSheetContent = () => {
    setReplaceImage(null)
    setReplaceLayout(null)
    if (replaceImageInputRef.current) replaceImageInputRef.current.value = ""
    if (replaceLayoutInputRef.current) replaceLayoutInputRef.current.value = ""
  }

  const openSheet = (item: ShareFrameItem) => {
    setSheetRef({ key: item.key, version: item.version })
    setSheetSnapshot(item)
    setDraft(toDraft(item))
    resetSheetContent()
    setSheetError(null)
    setSheetStatus(null)
    setIsSheetOpen(true)
  }

  // Keep showing the opened frame while the list refetches.
  const shownItem =
    (sheetRef && items.find((item) => item.key === sheetRef.key && item.version === sheetRef.version)) ?? sheetSnapshot

  const setPublished = (item: ShareFrameItem, published: boolean) => {
    if (pendingId) return
    if (published && !window.confirm(`Publish ${frameLabel(item)}? Apps will show it, and its image and layout can't change after this.`)) return
    setPendingId(frameId(item))
    setError(null)
    setStatus(null)
    void (async () => {
      try {
        await adminFetch(framePath(item), {
          method: "PATCH",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ published })
        })
        setStatus(published ? "Frame published" : "Frame hidden")
        await reload()
      } catch (err) {
        setError(err instanceof Error ? err.message : "Unable to update the frame")
      } finally {
        setPendingId(null)
      }
    })()
  }

  const copyToNewVersion = (item: ShareFrameItem) => {
    if (pendingId) return
    setPendingId(frameId(item))
    setError(null)
    setStatus(null)
    setSheetError(null)
    void (async () => {
      try {
        const latest = await fetchFrames()
        const newVersion = Math.max(item.version, ...latest.filter((frame) => frame.key === item.key).map((frame) => frame.version)) + 1
        const response = await adminFetch(`${framePath(item)}/copy`, {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ newVersion })
        })
        const copy = (await response.json()) as ShareFrameItem
        await reload()
        openSheet(copy)
        setSheetStatus(`Copied to draft v${copy.version}`)
      } catch (err) {
        const message = err instanceof Error ? err.message : "Unable to copy the frame"
        if (isSheetOpen) setSheetError(message)
        else setError(message)
      } finally {
        setPendingId(null)
      }
    })()
  }

  const deleteFrame = (item: ShareFrameItem) => {
    if (pendingId) return
    if (!window.confirm(`Delete ${frameLabel(item)}? Apps stop showing it. Hide it instead to keep it in the list.`)) return
    setPendingId(frameId(item))
    setError(null)
    setStatus(null)
    setSheetError(null)
    void (async () => {
      try {
        await adminFetch(framePath(item), {
          method: "DELETE",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ confirm: true })
        })
        if (sheetRef && frameId(sheetRef) === frameId(item)) setIsSheetOpen(false)
        setStatus("Frame deleted")
        await reload()
      } catch (err) {
        const message = err instanceof Error ? err.message : "Unable to delete the frame"
        if (isSheetOpen) setSheetError(message)
        else setError(message)
      } finally {
        setPendingId(null)
      }
    })()
  }

  const frameActions = (item: ShareFrameItem): MenuAction[] => [
    { label: "Edit", icon: Pencil, onSelect: () => openSheet(item) },
    item.published
      ? { label: "Hide", icon: EyeOff, disabled: Boolean(pendingId), onSelect: () => setPublished(item, false) }
      : { label: "Publish", icon: Eye, disabled: Boolean(pendingId), onSelect: () => setPublished(item, true) },
    { label: "Copy to new version", icon: Copy, disabled: Boolean(pendingId), onSelect: () => copyToNewVersion(item) },
    { label: "Delete", icon: Trash2, tone: "danger", disabled: Boolean(pendingId), onSelect: () => deleteFrame(item) }
  ]

  const pickNewLayout = async (file: File | null) => {
    setError(null)
    if (!file) {
      setNewLayout(null)
      return
    }
    const selection = await readLayoutFile(file)
    setNewLayout(selection)
    if (selection.layout && typeof selection.layout.key === "string" && !newKey.trim()) setNewKey(selection.layout.key)
  }

  const onCreate = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (isUploading) return
    const form = event.currentTarget
    const values = new FormData(form)
    if (!newImage) return setError("Choose the frame PNG")
    if (!newLayout) return setError("Choose the layout JSON")
    const from = String(values.get("availableFrom") ?? "")
    const until = String(values.get("availableUntil") ?? "")
    if (from && until && from > until) return setError("The window must start on or before its last day")

    const body = new FormData()
    body.set("frameKey", newKey.trim())
    body.set("frameVersion", String(values.get("frameVersion") ?? "1"))
    body.set("title", String(values.get("title") ?? "").trim())
    body.set("sortOrder", String(values.get("sortOrder") ?? "0"))
    if (from) body.set("availableFrom", from)
    if (until) body.set("availableUntil", until)
    body.set("published", "false")
    body.set("layout", newLayout.text)
    body.set("image", newImage, newImage.name)

    setIsUploading(true)
    setError(null)
    setStatus(null)
    void (async () => {
      try {
        const response = await adminFetch("/admin/share-frames", { method: "POST", body })
        const created = (await response.json()) as ShareFrameItem
        form.reset()
        setNewImage(null)
        setNewLayout(null)
        setNewKey("")
        if (newImageInputRef.current) newImageInputRef.current.value = ""
        if (newLayoutInputRef.current) newLayoutInputRef.current.value = ""
        setStatus(`Draft ${created.key} v${created.version} created`)
        await reload()
      } catch (err) {
        setError(err instanceof Error ? err.message : "Unable to create the frame")
      } finally {
        setIsUploading(false)
      }
    })()
  }

  const isSheetDirty = Boolean(
    shownItem &&
      draft &&
      (replaceImage || replaceLayout || Object.keys(metadataChanges(draft, shownItem)).length > 0)
  )

  const onSave = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!shownItem || !draft || isSaving) return
    const item = shownItem
    if (!draft.title.trim()) return setSheetError("Give the frame a title")
    if (!draft.sortOrder.trim() || !Number.isSafeInteger(Number(draft.sortOrder))) {
      return setSheetError("Sort order must be a whole number")
    }
    if (draft.availableFrom && draft.availableUntil && draft.availableFrom > draft.availableUntil) {
      return setSheetError("The window must start on or before its last day")
    }
    if (replaceLayout?.error) return setSheetError(replaceLayout.error)
    const changes = metadataChanges(draft, item)
    if (changes.published && !window.confirm(`Publish ${frameLabel(item)}? Apps will show it, and its image and layout can't change after this.`)) return

    setIsSaving(true)
    setSheetError(null)
    setSheetStatus(null)
    void (async () => {
      try {
        // Content first, so publishing in the same save locks the new art.
        if (replaceImage || replaceLayout) {
          const body = new FormData()
          if (replaceLayout) body.set("layout", replaceLayout.text)
          if (replaceImage) body.set("image", replaceImage, replaceImage.name)
          await adminFetch(`${framePath(item)}/content`, { method: "PUT", body })
          resetSheetContent()
        }
        if (Object.keys(changes).length > 0) {
          await adminFetch(framePath(item), {
            method: "PATCH",
            headers: { "content-type": "application/json" },
            body: JSON.stringify(changes)
          })
        }
        setSheetStatus("Saved")
        const next = await fetchFrames()
        setItems(next)
        const saved = next.find((frame) => frame.key === item.key && frame.version === item.version)
        if (saved) {
          setSheetSnapshot(saved)
          setDraft(toDraft(saved))
        }
      } catch (err) {
        setSheetError(err instanceof Error ? err.message : "Unable to save the frame")
      } finally {
        setIsSaving(false)
      }
    })()
  }

  const sheetPreviewLayout = replaceLayout?.layout ?? shownItem?.layout ?? null
  const sheetPreviewSrc = replaceImageUrl ?? shownItem?.frameUrl ?? null

  return (
    <>
      <TopBar icon={Frame} title="Share frames" meta={items.length > 0 ? items.length : null} />

      <div className="flex min-h-0 flex-1 flex-col-reverse lg:flex-row">
        <section aria-label="Share frames" className="min-h-0 min-w-0 flex-1 overflow-y-auto p-6">
          {items.length === 0 ? (
            isLoaded ? <EmptyState icon={Frame} title="No share frames yet" /> : null
          ) : (
            <ul className="grid grid-cols-[repeat(auto-fill,minmax(168px,1fr))] gap-x-4 gap-y-6">
              {items.map((item) => (
                <li key={frameId(item)} className="group relative min-w-0">
                  <button
                    type="button"
                    onClick={() => openSheet(item)}
                    aria-label={`${item.title} v${item.version}`}
                    className="block w-full min-w-0 rounded-2xl text-left outline-none focus-visible:ring-2 focus-visible:ring-admin-fg/40 focus-visible:ring-offset-2 focus-visible:ring-offset-admin-panel"
                  >
                    <span className="relative block aspect-[9/16] overflow-hidden rounded-2xl bg-neutral-900">
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img src={item.thumbnailUrl} alt="" className="h-full w-full object-cover" loading="lazy" />
                      <span aria-hidden className="pointer-events-none absolute inset-0 rounded-2xl ring-1 ring-inset ring-black/5" />
                      {pendingId === frameId(item) ? (
                        <span className="absolute inset-0 flex items-center justify-center bg-black/40 text-white">
                          <Spinner />
                        </span>
                      ) : null}
                    </span>
                    <span className="mt-2.5 flex flex-col gap-1 px-0.5">
                      <span className="flex min-w-0 items-center gap-1.5">
                        <span className="truncate text-sm font-medium text-admin-fg">{item.title}</span>
                        {item.contentLocked ? (
                          <Lock aria-label="Content locked" className="size-3.5 shrink-0 text-admin-muted" strokeWidth={1.5} />
                        ) : null}
                      </span>
                      <span className="truncate font-mono text-xs tabular-nums text-admin-muted">
                        {item.key} · v{item.version} · {item.sortOrder}
                      </span>
                      <span className="truncate text-xs text-admin-fg-2">{windowLabel(item.availableFrom, item.availableUntil)}</span>
                      <PublishState published={item.published} />
                    </span>
                  </button>
                  <ActionMenu
                    label={`${item.title} v${item.version} actions`}
                    actions={frameActions(item)}
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

        <aside className="shrink-0 border-b border-admin-line lg:w-96 lg:overflow-y-auto lg:border-b-0 lg:border-l">
          <form onSubmit={onCreate} className="flex flex-col gap-4 p-5">
            <h2 className="text-sm font-medium text-admin-fg">New frame</h2>

            <fieldset disabled={isUploading} className="flex flex-col gap-4">
              <Dropzone icon={ImagePlus} title={newImage?.name ?? "Choose frame"} hint="1080×1920 PNG with alpha">
                <input
                  ref={newImageInputRef}
                  type="file"
                  accept="image/png"
                  className="sr-only"
                  onChange={(event) => {
                    setError(null)
                    setNewImage(event.target.files?.[0] ?? null)
                  }}
                />
              </Dropzone>
              <Dropzone icon={FileJson} title={newLayout?.fileName ?? "Choose layout"} hint="layout.json">
                <input
                  ref={newLayoutInputRef}
                  type="file"
                  accept="application/json,.json"
                  className="sr-only"
                  onChange={(event) => void pickNewLayout(event.target.files?.[0] ?? null)}
                />
              </Dropzone>
              {newLayout?.error ? <StatusLine tone="error">{newLayout.error}</StatusLine> : null}

              {newImageUrl && newLayout?.layout ? (
                <div className="flex flex-col gap-3">
                  <FramePreview frameSrc={newImageUrl} layout={newLayout.layout} caption={newCaption} showSafeZones={newShowSafeZones} />
                  <PreviewControls
                    id="new-frame-safe-zones"
                    showSafeZones={newShowSafeZones}
                    onShowSafeZones={setNewShowSafeZones}
                    caption={newCaption}
                    onCaption={setNewCaption}
                  />
                </div>
              ) : null}

              <div className="grid grid-cols-[minmax(0,1fr)_88px] gap-3">
                <Field label="Key">
                  <input
                    name="frameKey"
                    required
                    value={newKey}
                    onChange={(event) => setNewKey(event.target.value)}
                    placeholder="valentines"
                    className={cn(fieldClass, "font-mono")}
                  />
                </Field>
                <Field label="Version">
                  <input name="frameVersion" type="number" min={1} required defaultValue={1} className={numberFieldClass} />
                </Field>
              </div>
              <div className="grid grid-cols-[minmax(0,1fr)_88px] gap-3">
                <Field label="Title">
                  <input name="title" required maxLength={80} placeholder="Valentine's" className={fieldClass} />
                </Field>
                <Field label="Sort order">
                  <input name="sortOrder" type="number" required defaultValue={0} className={numberFieldClass} />
                </Field>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <Field label="Available from">
                  <input name="availableFrom" type="date" className={cn(fieldClass, "font-mono")} />
                </Field>
                <Field label="Until">
                  <input name="availableUntil" type="date" className={cn(fieldClass, "font-mono")} />
                </Field>
              </div>

              <AdminButton type="submit" disabled={isUploading} className="h-9 w-full">
                {isUploading ? <Spinner /> : "Create draft"}
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
        title={shownItem ? `${shownItem.title} · v${shownItem.version}` : null}
        footer={
          shownItem ? (
            <>
              <AdminButton variant="danger" onClick={() => deleteFrame(shownItem)} disabled={isSaving || Boolean(pendingId)}>
                {pendingId === frameId(shownItem) ? <Spinner /> : "Delete"}
              </AdminButton>
              <AdminButton
                type="submit"
                form="share-frame-sheet-form"
                className="min-w-20"
                disabled={!isSheetDirty || isSaving || Boolean(pendingId)}
              >
                {isSaving ? <Spinner /> : "Save"}
              </AdminButton>
            </>
          ) : null
        }
      >
        {shownItem && draft ? (
          <div className="flex flex-col gap-6 p-5">
            <div className="flex flex-col gap-3">
              {sheetPreviewSrc && sheetPreviewLayout ? (
                <FramePreview
                  frameSrc={sheetPreviewSrc}
                  layout={sheetPreviewLayout}
                  caption={caption}
                  showSafeZones={showSafeZones}
                  className="mx-auto w-full max-w-[300px]"
                />
              ) : null}
              <PreviewControls
                id="share-frame-safe-zones"
                showSafeZones={showSafeZones}
                onShowSafeZones={setShowSafeZones}
                caption={caption}
                onCaption={setCaption}
              />
              <p className="font-mono text-xs tabular-nums text-admin-muted">
                {shownItem.key} · v{shownItem.version} · {shownItem.layout.content.kind}
              </p>
            </div>

            <form id="share-frame-sheet-form" onSubmit={onSave} className="flex flex-col gap-3">
              <fieldset disabled={isSaving} className="flex flex-col gap-3">
                <div className="divide-y divide-admin-line rounded-xl bg-admin-fill px-4 py-1">
                  <PropertyRow label="Title" htmlFor="share-frame-title">
                    <input
                      id="share-frame-title"
                      required
                      maxLength={80}
                      value={draft.title}
                      onChange={(event) => setDraft({ ...draft, title: event.target.value })}
                      className={fieldClass}
                    />
                  </PropertyRow>
                  <PropertyRow label="Sort order" htmlFor="share-frame-sort">
                    <input
                      id="share-frame-sort"
                      type="number"
                      value={draft.sortOrder}
                      onChange={(event) => setDraft({ ...draft, sortOrder: event.target.value })}
                      className={cn(numberFieldClass, "w-28")}
                    />
                  </PropertyRow>
                  <PropertyRow label="Available from" htmlFor="share-frame-from">
                    <input
                      id="share-frame-from"
                      type="date"
                      value={draft.availableFrom}
                      onChange={(event) => setDraft({ ...draft, availableFrom: event.target.value })}
                      className={cn(fieldClass, "font-mono")}
                    />
                  </PropertyRow>
                  <PropertyRow label="Until" htmlFor="share-frame-until">
                    <input
                      id="share-frame-until"
                      type="date"
                      value={draft.availableUntil}
                      onChange={(event) => setDraft({ ...draft, availableUntil: event.target.value })}
                      className={cn(fieldClass, "font-mono")}
                    />
                  </PropertyRow>
                  <PropertyRow label="Published" htmlFor="share-frame-published">
                    <Switch
                      id="share-frame-published"
                      checked={draft.published}
                      onChange={(event) => setDraft({ ...draft, published: event.target.checked })}
                    />
                  </PropertyRow>
                </div>

                {shownItem.contentLocked ? (
                  <div className="flex items-center justify-between gap-4 rounded-xl border border-admin-line px-4 py-3">
                    <p className="flex items-center gap-2 text-[13px] text-admin-fg">
                      <Lock className="size-3.5 shrink-0 text-admin-muted" strokeWidth={1.5} />
                      Image and layout are locked
                    </p>
                    <AdminButton variant="secondary" onClick={() => copyToNewVersion(shownItem)} disabled={Boolean(pendingId) || isSaving}>
                      Copy to new version
                    </AdminButton>
                  </div>
                ) : (
                  <div className="flex flex-col gap-2">
                    <Dropzone icon={ImagePlus} title={replaceImage?.name ?? "Replace frame"} hint="1080×1920 PNG with alpha">
                      <input
                        ref={replaceImageInputRef}
                        type="file"
                        accept="image/png"
                        className="sr-only"
                        onChange={(event) => setReplaceImage(event.target.files?.[0] ?? null)}
                      />
                    </Dropzone>
                    <Dropzone icon={FileJson} title={replaceLayout?.fileName ?? "Replace layout"} hint="layout.json">
                      <input
                        ref={replaceLayoutInputRef}
                        type="file"
                        accept="application/json,.json"
                        className="sr-only"
                        onChange={(event) => {
                          const file = event.target.files?.[0] ?? null
                          if (!file) setReplaceLayout(null)
                          else void readLayoutFile(file).then(setReplaceLayout)
                        }}
                      />
                    </Dropzone>
                  </div>
                )}
              </fieldset>
            </form>

            {sheetStatus || sheetError || replaceLayout?.error ? (
              <div className="flex flex-col gap-1">
                {sheetStatus ? <StatusLine tone="neutral">{sheetStatus}</StatusLine> : null}
                {replaceLayout?.error ? <StatusLine tone="error">{replaceLayout.error}</StatusLine> : null}
                {sheetError ? <StatusLine tone="error">{sheetError}</StatusLine> : null}
              </div>
            ) : null}
          </div>
        ) : null}
      </Sheet>
    </>
  )
}
