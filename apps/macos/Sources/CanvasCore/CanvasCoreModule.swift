import CoreGraphics
import Foundation

public enum CanvasCoreModule {
    public static let name = "CanvasCore"
}

public enum CanvasOperationType: String, Codable, CaseIterable, Sendable {
    case addStroke = "ADD_STROKE"
    case appendPoints = "APPEND_POINTS"
    case finishStroke = "FINISH_STROKE"
    case deleteStroke = "DELETE_STROKE"
    case clearCanvas = "CLEAR_CANVAS"
    case addTextElement = "ADD_TEXT_ELEMENT"
    case updateTextElement = "UPDATE_TEXT_ELEMENT"
    case deleteTextElement = "DELETE_TEXT_ELEMENT"
    case addStickerElement = "ADD_STICKER_ELEMENT"
    case updateStickerElement = "UPDATE_STICKER_ELEMENT"
    case deleteStickerElement = "DELETE_STICKER_ELEMENT"
}

public enum CanvasTextFont: String, Codable, CaseIterable, Sendable {
    case poppins = "POPPINS"
    case virgil = "VIRGIL"
    case dmSans = "DM_SANS"
    case spaceMono = "SPACE_MONO"
    case playfairDisplay = "PLAYFAIR_DISPLAY"
    case bangers = "BANGERS"
    case permanentMarker = "PERMANENT_MARKER"
    case kalam = "KALAM"
    case oswald = "OSWALD"
}

public enum CanvasTextAlign: String, Codable, CaseIterable, Sendable {
    case left = "LEFT"
    case center = "CENTER"
    case right = "RIGHT"
}

public enum CanvasStrokeRenderMode: String, Codable, Sendable {
    case dryBrush
    case roundStroke

    public init(rawBackendValue: String?) {
        switch rawBackendValue?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "round", "round_stroke", "round-stroke", "round_stroke_only", "round-stroke-only", "round_stroke_only_strokes":
            self = .roundStroke
        case "dry", "dry_brush", "dry-brush", "dry_brush_only", "dry-brush-only", "dry_brush_only_strokes",
             "hybrid":
            self = .dryBrush
        default:
            self = .dryBrush
        }
    }
}

public struct CanvasPoint: Codable, Equatable, Sendable {
    public var x: Float
    public var y: Float

    public init(x: Float, y: Float) {
        self.x = x
        self.y = y
    }

    public var hasLegacyGeometry: Bool {
        x < -Self.legacyTolerance ||
            x > 1 + Self.legacyTolerance ||
            y < -Self.legacyTolerance ||
            y > 1 + Self.legacyTolerance
    }

    private static let legacyTolerance: Float = 0.001
}

public struct CanvasStroke: Codable, Equatable, Sendable {
    public var id: String
    public var colorArgb: UInt32
    public var width: Float
    public var points: [CanvasPoint]
    public var createdAt: Int64

    public init(id: String, colorArgb: UInt32, width: Float, points: [CanvasPoint], createdAt: Int64) {
        self.id = id
        self.colorArgb = colorArgb
        self.width = width
        self.points = points
        self.createdAt = createdAt
    }

    public var hasLegacyGeometry: Bool {
        points.contains(where: \.hasLegacyGeometry)
    }
}

public struct CanvasTextElement: Codable, Equatable, Sendable {
    public var id: String
    public var text: String
    public var createdAt: Int64
    public var center: CanvasPoint
    public var rotationRad: Float
    public var scale: Float
    public var boxWidth: Float
    public var colorArgb: UInt32
    public var backgroundPillEnabled: Bool
    public var font: CanvasTextFont
    public var alignment: CanvasTextAlign

    public init(
        id: String,
        text: String,
        createdAt: Int64,
        center: CanvasPoint,
        rotationRad: Float = 0,
        scale: Float = 1,
        boxWidth: Float,
        colorArgb: UInt32,
        backgroundPillEnabled: Bool = false,
        font: CanvasTextFont = .poppins,
        alignment: CanvasTextAlign = .center
    ) {
        self.id = id
        self.text = text
        self.createdAt = createdAt
        self.center = center
        self.rotationRad = rotationRad
        self.scale = scale
        self.boxWidth = boxWidth
        self.colorArgb = colorArgb
        self.backgroundPillEnabled = backgroundPillEnabled
        self.font = font
        self.alignment = alignment
    }
}

public struct CanvasStickerElement: Codable, Equatable, Sendable {
    public var id: String
    public var createdAt: Int64
    public var center: CanvasPoint
    public var rotationRad: Float
    public var scale: Float
    public var packKey: String
    public var packVersion: Int
    public var stickerId: String

    public init(
        id: String,
        createdAt: Int64,
        center: CanvasPoint,
        rotationRad: Float = 0,
        scale: Float = 0.22,
        packKey: String,
        packVersion: Int,
        stickerId: String
    ) {
        self.id = id
        self.createdAt = createdAt
        self.center = center
        self.rotationRad = rotationRad
        self.scale = scale
        self.packKey = packKey
        self.packVersion = packVersion
        self.stickerId = stickerId
    }
}

public enum CanvasElement: Equatable, Sendable {
    case text(CanvasTextElement)
    case sticker(CanvasStickerElement)

    public var id: String {
        switch self {
        case let .text(element): element.id
        case let .sticker(element): element.id
        }
    }

    public var createdAt: Int64 {
        switch self {
        case let .text(element): element.createdAt
        case let .sticker(element): element.createdAt
        }
    }
}

extension CanvasElement: Codable {
    private enum CodingKeys: String, CodingKey {
        case kind
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let kind = try container.decode(String.self, forKey: .kind)
        switch kind {
        case "TEXT":
            self = .text(try CanvasTextElement(from: decoder))
        case "STICKER":
            self = .sticker(try CanvasStickerElement(from: decoder))
        default:
            throw DecodingError.dataCorruptedError(
                forKey: .kind,
                in: container,
                debugDescription: "Unknown canvas element kind \(kind)"
            )
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case let .text(element):
            try container.encode("TEXT", forKey: .kind)
            try element.encode(to: encoder)
        case let .sticker(element):
            try container.encode("STICKER", forKey: .kind)
            try element.encode(to: encoder)
        }
    }
}

public struct CanvasState: Equatable, Sendable {
    public var committedStrokes: [CanvasStroke]
    public var committedElements: [CanvasElement]
    public var localActiveStroke: CanvasStroke?
    public var remoteActiveStrokes: [String: CanvasStroke]
    public var revision: Int64

    public init(
        committedStrokes: [CanvasStroke] = [],
        committedElements: [CanvasElement] = [],
        localActiveStroke: CanvasStroke? = nil,
        remoteActiveStrokes: [String: CanvasStroke] = [:],
        revision: Int64 = 0
    ) {
        self.committedStrokes = committedStrokes
        self.committedElements = committedElements
        self.localActiveStroke = localActiveStroke
        self.remoteActiveStrokes = remoteActiveStrokes
        self.revision = revision
    }

    public var isEmpty: Bool {
        committedStrokes.isEmpty &&
            committedElements.isEmpty &&
            localActiveStroke == nil &&
            remoteActiveStrokes.isEmpty
    }
}

public enum CanvasOperationPayload: Equatable, Sendable {
    case addStroke(AddStrokePayload)
    case appendPoints(AppendPointsPayload)
    case finishStroke
    case deleteStroke
    case clearCanvas
    case addTextElement(CanvasTextElement)
    case updateTextElement(CanvasTextElement)
    case deleteTextElement
    case addStickerElement(CanvasStickerElement)
    case updateStickerElement(CanvasStickerElement)
    case deleteStickerElement

    public var hasLegacyGeometry: Bool {
        switch self {
        case let .addStroke(payload):
            payload.firstPoint.hasLegacyGeometry
        case let .appendPoints(payload):
            payload.points.contains(where: \.hasLegacyGeometry)
        case let .addTextElement(element), let .updateTextElement(element):
            element.center.hasLegacyGeometry
        case let .addStickerElement(element), let .updateStickerElement(element):
            element.center.hasLegacyGeometry
        case .finishStroke, .deleteStroke, .clearCanvas, .deleteTextElement, .deleteStickerElement:
            false
        }
    }
}

public struct AddStrokePayload: Codable, Equatable, Sendable {
    public var id: String
    public var colorArgb: UInt32
    public var width: Float
    public var createdAt: Int64
    public var firstPoint: CanvasPoint

    public init(id: String, colorArgb: UInt32, width: Float, createdAt: Int64, firstPoint: CanvasPoint) {
        self.id = id
        self.colorArgb = colorArgb
        self.width = width
        self.createdAt = createdAt
        self.firstPoint = firstPoint
    }
}

public struct AppendPointsPayload: Codable, Equatable, Sendable {
    public var points: [CanvasPoint]

    public init(points: [CanvasPoint]) {
        self.points = points
    }
}

public struct CanvasOperation: Equatable, Sendable {
    public var clientOperationId: String
    public var actorUserId: String?
    public var pairSessionId: String?
    public var type: CanvasOperationType
    public var strokeId: String?
    public var payload: CanvasOperationPayload
    public var clientCreatedAt: String
    public var clientLocalDate: String?
    public var serverRevision: Int64?
    public var createdAt: String?

    public init(
        clientOperationId: String,
        actorUserId: String? = nil,
        pairSessionId: String? = nil,
        type: CanvasOperationType,
        strokeId: String?,
        payload: CanvasOperationPayload,
        clientCreatedAt: String,
        clientLocalDate: String? = nil,
        serverRevision: Int64? = nil,
        createdAt: String? = nil
    ) {
        self.clientOperationId = clientOperationId
        self.actorUserId = actorUserId
        self.pairSessionId = pairSessionId
        self.type = type
        self.strokeId = strokeId
        self.payload = payload
        self.clientCreatedAt = clientCreatedAt
        self.clientLocalDate = clientLocalDate
        self.serverRevision = serverRevision
        self.createdAt = createdAt
    }
}

extension CanvasOperation: Codable {
    private enum CodingKeys: String, CodingKey {
        case clientOperationId
        case actorUserId
        case pairSessionId
        case type
        case strokeId
        case payload
        case clientCreatedAt
        case clientLocalDate
        case serverRevision
        case createdAt
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(CanvasOperationType.self, forKey: .type)
        let payloadDecoder = try container.superDecoder(forKey: .payload)
        let payload: CanvasOperationPayload
        switch type {
        case .addStroke:
            payload = .addStroke(try AddStrokePayload(from: payloadDecoder))
        case .appendPoints:
            payload = .appendPoints(try AppendPointsPayload(from: payloadDecoder))
        case .finishStroke:
            payload = .finishStroke
        case .deleteStroke:
            payload = .deleteStroke
        case .clearCanvas:
            payload = .clearCanvas
        case .addTextElement:
            payload = .addTextElement(try CanvasTextElement(from: payloadDecoder))
        case .updateTextElement:
            payload = .updateTextElement(try CanvasTextElement(from: payloadDecoder))
        case .deleteTextElement:
            payload = .deleteTextElement
        case .addStickerElement:
            payload = .addStickerElement(try CanvasStickerElement(from: payloadDecoder))
        case .updateStickerElement:
            payload = .updateStickerElement(try CanvasStickerElement(from: payloadDecoder))
        case .deleteStickerElement:
            payload = .deleteStickerElement
        }

        self.init(
            clientOperationId: try container.decode(String.self, forKey: .clientOperationId),
            actorUserId: try container.decodeIfPresent(String.self, forKey: .actorUserId),
            pairSessionId: try container.decodeIfPresent(String.self, forKey: .pairSessionId),
            type: type,
            strokeId: try container.decodeIfPresent(String.self, forKey: .strokeId),
            payload: payload,
            clientCreatedAt: try container.decode(String.self, forKey: .clientCreatedAt),
            clientLocalDate: try container.decodeIfPresent(String.self, forKey: .clientLocalDate),
            serverRevision: try container.decodeIfPresent(Int64.self, forKey: .serverRevision),
            createdAt: try container.decodeIfPresent(String.self, forKey: .createdAt)
        )
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(clientOperationId, forKey: .clientOperationId)
        try container.encodeIfPresent(actorUserId, forKey: .actorUserId)
        try container.encodeIfPresent(pairSessionId, forKey: .pairSessionId)
        try container.encode(type, forKey: .type)
        try container.encodeIfPresent(strokeId, forKey: .strokeId)
        try container.encode(clientCreatedAt, forKey: .clientCreatedAt)
        try container.encodeIfPresent(clientLocalDate, forKey: .clientLocalDate)
        try container.encodeIfPresent(serverRevision, forKey: .serverRevision)
        try container.encodeIfPresent(createdAt, forKey: .createdAt)

        switch payload {
        case let .addStroke(payload):
            try container.encode(payload, forKey: .payload)
        case let .appendPoints(payload):
            try container.encode(payload, forKey: .payload)
        case .finishStroke, .deleteStroke, .clearCanvas, .deleteTextElement, .deleteStickerElement:
            try container.encode([String: String](), forKey: .payload)
        case let .addTextElement(element), let .updateTextElement(element):
            try container.encode(element, forKey: .payload)
        case let .addStickerElement(element), let .updateStickerElement(element):
            try container.encode(element, forKey: .payload)
        }
    }
}

public enum CanvasDiagnosticSeverity: String, Codable, Equatable, Sendable {
    case info
    case warning
    case error
}

public struct CanvasDiagnostic: Codable, Equatable, Sendable {
    public var code: String
    public var message: String
    public var severity: CanvasDiagnosticSeverity
    public var operationId: String?

    public init(
        code: String,
        message: String,
        severity: CanvasDiagnosticSeverity = .warning,
        operationId: String? = nil
    ) {
        self.code = code
        self.message = message
        self.severity = severity
        self.operationId = operationId
    }
}

public struct CanvasReducerResult: Equatable, Sendable {
    public var state: CanvasState
    public var diagnostics: [CanvasDiagnostic]

    public init(state: CanvasState, diagnostics: [CanvasDiagnostic]) {
        self.state = state
        self.diagnostics = diagnostics
    }
}

public struct CanvasReducer: Sendable {
    public init() {}

    public func apply(_ operations: [CanvasOperation], to initialState: CanvasState = CanvasState()) -> CanvasReducerResult {
        operations.sorted(by: operationSort).reduce(CanvasReducerResult(state: initialState, diagnostics: [])) { partial, operation in
            var next = apply(operation, to: partial.state)
            next.diagnostics = partial.diagnostics + next.diagnostics
            return next
        }
    }

    public func apply(_ operation: CanvasOperation, to initialState: CanvasState) -> CanvasReducerResult {
        var state = initialState
        var diagnostics: [CanvasDiagnostic] = []

        if operation.payload.hasLegacyGeometry {
            diagnostics.append(CanvasDiagnostic(
                code: "legacy_geometry",
                message: "Operation contains pixel-space or out-of-range geometry.",
                severity: .error,
                operationId: operation.clientOperationId
            ))
            return CanvasReducerResult(state: state, diagnostics: diagnostics)
        }

        switch operation.payload {
        case let .addStroke(payload):
            let stroke = CanvasStroke(
                id: payload.id,
                colorArgb: payload.colorArgb,
                width: payload.width,
                points: [payload.firstPoint],
                createdAt: payload.createdAt
            )
            state.remoteActiveStrokes[stroke.id] = stroke
        case let .appendPoints(payload):
            guard let strokeId = operation.strokeId else {
                diagnostics.append(missingStrokeID(operation, action: "append points"))
                break
            }
            guard var active = state.remoteActiveStrokes[strokeId] else {
                diagnostics.append(noActiveStroke(operation, strokeId: strokeId, action: "append points"))
                break
            }
            active.points.append(contentsOf: payload.points)
            state.remoteActiveStrokes[strokeId] = active
        case .finishStroke:
            guard let strokeId = operation.strokeId else {
                diagnostics.append(missingStrokeID(operation, action: "finish stroke"))
                break
            }
            if let active = state.remoteActiveStrokes.removeValue(forKey: strokeId) {
                state.committedStrokes = upsertStroke(active, in: state.committedStrokes)
            } else if state.committedStrokes.contains(where: { $0.id == strokeId }) {
                diagnostics.append(CanvasDiagnostic(
                    code: "finish_already_committed_stroke",
                    message: "Finish stroke referenced an already committed stroke.",
                    severity: .info,
                    operationId: operation.clientOperationId
                ))
            } else {
                diagnostics.append(noActiveStroke(operation, strokeId: strokeId, action: "finish stroke"))
            }
        case .deleteStroke:
            guard let strokeId = operation.strokeId else {
                diagnostics.append(missingStrokeID(operation, action: "delete stroke"))
                break
            }
            let removedCommitted = state.committedStrokes.contains(where: { $0.id == strokeId })
            let removedActive = state.remoteActiveStrokes.removeValue(forKey: strokeId) != nil
            state.committedStrokes.removeAll { $0.id == strokeId }
            if !removedCommitted && !removedActive {
                diagnostics.append(CanvasDiagnostic(
                    code: "delete_missing_stroke",
                    message: "Delete stroke referenced a missing stroke id \(strokeId).",
                    severity: .info,
                    operationId: operation.clientOperationId
                ))
            }
        case .clearCanvas:
            state.committedStrokes = []
            state.committedElements = []
            state.localActiveStroke = nil
            state.remoteActiveStrokes = [:]
        case let .addTextElement(element), let .updateTextElement(element):
            state.committedElements = upsertElement(.text(element), in: state.committedElements)
        case .deleteTextElement:
            guard let elementId = operation.strokeId else {
                diagnostics.append(missingStrokeID(operation, action: "delete text element"))
                break
            }
            state.committedElements = deleteElement(elementId, in: state.committedElements)
        case let .addStickerElement(element), let .updateStickerElement(element):
            state.committedElements = upsertElement(.sticker(element), in: state.committedElements)
        case .deleteStickerElement:
            guard let elementId = operation.strokeId else {
                diagnostics.append(missingStrokeID(operation, action: "delete sticker element"))
                break
            }
            state.committedElements = deleteElement(elementId, in: state.committedElements)
        }

        if let revision = operation.serverRevision {
            state.revision = max(state.revision, revision)
        }

        return CanvasReducerResult(state: state, diagnostics: diagnostics)
    }

    private func operationSort(_ lhs: CanvasOperation, _ rhs: CanvasOperation) -> Bool {
        switch (lhs.serverRevision, rhs.serverRevision) {
        case let (.some(left), .some(right)):
            left < right
        case (.some, .none):
            true
        case (.none, .some):
            false
        case (.none, .none):
            lhs.clientCreatedAt < rhs.clientCreatedAt
        }
    }

    private func upsertStroke(_ stroke: CanvasStroke, in strokes: [CanvasStroke]) -> [CanvasStroke] {
        strokes.filter { $0.id != stroke.id } + [stroke]
    }

    private func upsertElement(_ element: CanvasElement, in elements: [CanvasElement]) -> [CanvasElement] {
        elements.filter { $0.id != element.id } + [element]
    }

    private func deleteElement(_ elementId: String, in elements: [CanvasElement]) -> [CanvasElement] {
        elements.filter { $0.id != elementId }
    }

    private func missingStrokeID(_ operation: CanvasOperation, action: String) -> CanvasDiagnostic {
        CanvasDiagnostic(
            code: "missing_target_id",
            message: "Cannot \(action) without a target id.",
            operationId: operation.clientOperationId
        )
    }

    private func noActiveStroke(_ operation: CanvasOperation, strokeId: String, action: String) -> CanvasDiagnostic {
        CanvasDiagnostic(
            code: "missing_active_stroke",
            message: "Cannot \(action) because active stroke \(strokeId) is missing.",
            operationId: operation.clientOperationId
        )
    }
}
