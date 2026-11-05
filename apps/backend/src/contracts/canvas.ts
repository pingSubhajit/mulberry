export const CanvasOperationTypes = [
  "ADD_STROKE",
  "APPEND_POINTS",
  "FINISH_STROKE",
  "DELETE_STROKE",
  "CLEAR_CANVAS",
  "ADD_TEXT_ELEMENT",
  "UPDATE_TEXT_ELEMENT",
  "DELETE_TEXT_ELEMENT",
  "ADD_STICKER_ELEMENT",
  "UPDATE_STICKER_ELEMENT",
  "DELETE_STICKER_ELEMENT",
  "ADD_PHOTO_ELEMENT",
  "UPDATE_PHOTO_ELEMENT",
  "DELETE_PHOTO_ELEMENT",
] as const

export type CanvasOperationType = (typeof CanvasOperationTypes)[number]

/** Operations that put something of the person's own on the canvas: they count as drawing. */
export const DRAWING_OPERATION_TYPES: readonly CanvasOperationType[] = [
  "FINISH_STROKE",
  "ADD_TEXT_ELEMENT",
  "UPDATE_TEXT_ELEMENT",
  "ADD_STICKER_ELEMENT",
  "UPDATE_STICKER_ELEMENT",
  "ADD_PHOTO_ELEMENT",
  "UPDATE_PHOTO_ELEMENT",
]

export const CanvasPhotoStyles = ["FRAME", "POLAROID", "STICKER"] as const

/** How a photo is framed on the canvas. Anything else reads as `FRAME`. */
export type CanvasPhotoStyle = (typeof CanvasPhotoStyles)[number]

/**
 * The payload of `ADD_PHOTO_ELEMENT` and `UPDATE_PHOTO_ELEMENT`, whose `strokeId` is the element id.
 * `DELETE_PHOTO_ELEMENT` carries only the `strokeId`.
 */
export interface CanvasPhotoElementPayload {
  id: string
  /** Milliseconds since the epoch. */
  createdAt: number
  /** Normalised canvas coordinates. */
  center: { x: number; y: number }
  rotationRad: number
  /** The framed photo's long edge as a fraction of the canvas width, 0.12...1.2. */
  scale: number
  /** From `POST /canvas/photos`. */
  photoId: string
  /** The photo's width ÷ height. */
  aspectRatio: number
  style: CanvasPhotoStyle
  /**
   * Optional. A second upload: the subject cut out as a PNG with transparency, which the
   * `STICKER` style draws instead of `photoId`. Left out when missing or invalid.
   */
  cutoutPhotoId?: string
}

/** A photo element as it sits in `snapshot.elements`. */
export interface CanvasPhotoElement extends CanvasPhotoElementPayload {
  kind: "PHOTO"
}

export const CanvasPhotoContentTypes = ["image/jpeg", "image/png"] as const

export type CanvasPhotoContentType = (typeof CanvasPhotoContentTypes)[number]

/** `POST /canvas/photos`. */
export interface CanvasPhotoUploadResponse {
  photoId: string
  width: number
  height: number
  contentType: CanvasPhotoContentType
}

export interface CanvasOperationEnvelope {
  clientOperationId: string
  actorUserId: string
  pairSessionId: string
  type: CanvasOperationType
  strokeId: string | null
  payload: unknown
  clientCreatedAt: string
  serverRevision: number
  createdAt: string
}

export interface ClientCanvasOperation {
  clientOperationId: string
  type: CanvasOperationType
  strokeId?: string | null
  payload: unknown
  clientCreatedAt: string
  clientLocalDate?: string | null
}

export interface ClientCanvasOperationBatch {
  batchId: string
  operations: ClientCanvasOperation[]
  clientCreatedAt: string
}

export interface CanvasOpsResponse {
  operations: CanvasOperationEnvelope[]
}

export interface CanvasSnapshotResponse {
  pairSessionId: string
  snapshotRevision: number
  latestRevision: number
  revision: number
  snapshot: unknown
  updatedAt: string | null
}

export interface CanvasSyncBootstrap {
  pairSessionId: string
  userId: string
  latestRevision: number
  missedOperations: CanvasOperationEnvelope[]
}

