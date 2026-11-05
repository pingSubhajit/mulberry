import type { IncomingHttpHeaders } from "node:http"
import type {
  CanvasOperationEnvelope,
  CanvasOperationType,
  CanvasSnapshotResponse,
} from "../../contracts/canvas.js"

/**
 * Canvas features a client can handle, sent on every HTTP request and on the `/canvas/sync`
 * upgrade request: a comma-separated, case-insensitive list such as `photos`.
 */
export const CANVAS_FEATURES_HEADER = "x-canvas-features"

const PHOTOS_FEATURE = "photos"

const PHOTO_OPERATION_TYPES: readonly CanvasOperationType[] = [
  "ADD_PHOTO_ELEMENT",
  "UPDATE_PHOTO_ELEMENT",
  "DELETE_PHOTO_ELEMENT",
]

/** What a legacy client receives instead of a photo operation. */
const LEGACY_PHOTO_OPERATION_TYPE: CanvasOperationType = "DELETE_STICKER_ELEMENT"

/** Whether the request declares `photos` in `X-Canvas-Features`. Without it the client is legacy. */
export function clientSupportsPhotos(headers: IncomingHttpHeaders): boolean {
  const raw = headers[CANVAS_FEATURES_HEADER]
  const values = Array.isArray(raw) ? raw : raw === undefined ? [] : [raw]
  return values
    .flatMap((value) => value.split(","))
    .some((feature) => feature.trim().toLowerCase() === PHOTOS_FEATURE)
}

/**
 * Released apps stop syncing at an operation type they don't know, and revisions must stay
 * contiguous, so a legacy client gets every photo operation as a `DELETE_STICKER_ELEMENT` for the
 * same id: a no-op for an element it never had that still advances its revision. The payload is
 * `{}`, as every client sends for a sticker delete.
 */
export function operationForClient(
  operation: CanvasOperationEnvelope,
  supportsPhotos: boolean,
): CanvasOperationEnvelope {
  if (supportsPhotos || !PHOTO_OPERATION_TYPES.includes(operation.type)) return operation
  return { ...operation, type: LEGACY_PHOTO_OPERATION_TYPE, payload: {} }
}

export function operationsForClient(
  operations: readonly CanvasOperationEnvelope[],
  supportsPhotos: boolean,
): CanvasOperationEnvelope[] {
  return operations.map((operation) => operationForClient(operation, supportsPhotos))
}

/** A legacy client gets the snapshot without its `PHOTO` elements. */
export function snapshotForClient(
  snapshot: CanvasSnapshotResponse,
  supportsPhotos: boolean,
): CanvasSnapshotResponse {
  if (supportsPhotos || !hasElements(snapshot.snapshot)) return snapshot
  const elements = snapshot.snapshot.elements.filter((element) => !isPhotoElement(element))
  if (elements.length === snapshot.snapshot.elements.length) return snapshot
  return { ...snapshot, snapshot: { ...snapshot.snapshot, elements } }
}

function hasElements(snapshot: unknown): snapshot is { elements: unknown[] } {
  return typeof snapshot === "object" &&
    snapshot !== null &&
    "elements" in snapshot &&
    Array.isArray(snapshot.elements)
}

function isPhotoElement(element: unknown): boolean {
  return typeof element === "object" && element !== null && "kind" in element && element.kind === "PHOTO"
}
