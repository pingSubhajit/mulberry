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
] as const

export type CanvasOperationType = (typeof CanvasOperationTypes)[number]

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

