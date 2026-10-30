import Foundation

public struct DrawingPoint: Codable, Equatable, Sendable {
    public var x: Double
    public var y: Double
    public init(x: Double, y: Double) { self.x = x; self.y = y }
    public var isValid: Bool { x.isFinite && y.isFinite && (-0.001...1.001).contains(x) && (-0.001...1.001).contains(y) }
}

public struct DrawingStroke: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var colorArgb: UInt32
    public var width: Double
    public var createdAt: Int64
    public var points: [DrawingPoint]
    public var finished: Bool?

    public init(id: String = UUID().uuidString, colorArgb: UInt32, width: Double, createdAt: Int64 = Int64(Date().timeIntervalSince1970 * 1_000), points: [DrawingPoint], finished: Bool = false) {
        self.id = id; self.colorArgb = colorArgb; self.width = width
        self.createdAt = createdAt; self.points = points; self.finished = finished
    }
}

public struct StrokeStart: Codable, Equatable, Sendable {
    public let id: String
    public let colorArgb: UInt32
    public let width: Double
    public let createdAt: Int64
    public let firstPoint: DrawingPoint

    public init(id: String, colorArgb: UInt32, width: Double, createdAt: Int64, firstPoint: DrawingPoint) {
        self.id = id; self.colorArgb = colorArgb; self.width = width; self.createdAt = createdAt; self.firstPoint = firstPoint
    }
}

public struct StrokePoints: Codable, Equatable, Sendable {
    public let points: [DrawingPoint]
    public init(points: [DrawingPoint]) { self.points = points }
}

public struct DrawingOperation: Codable, Equatable, Identifiable, Sendable {
    public let clientOperationId: String
    public let actorUserId: String?
    public let pairSessionId: String?
    public let type: String
    public let strokeId: String?
    public let payload: JSONValue
    public let clientCreatedAt: String
    public let clientLocalDate: String?
    public let serverRevision: Int64?
    public let createdAt: String?
    public var id: String { clientOperationId }

    public init(clientOperationId: String = UUID().uuidString, actorUserId: String? = nil, pairSessionId: String? = nil, type: String, strokeId: String? = nil, payload: JSONValue = .object([:]), clientCreatedAt: String = ServerDate.string(), clientLocalDate: String? = ServerDate.localDay(), serverRevision: Int64? = nil, createdAt: String? = nil) {
        self.clientOperationId = clientOperationId; self.actorUserId = actorUserId; self.pairSessionId = pairSessionId
        self.type = type; self.strokeId = strokeId; self.payload = payload
        self.clientCreatedAt = clientCreatedAt; self.clientLocalDate = clientLocalDate
        self.serverRevision = serverRevision; self.createdAt = createdAt
    }

    /// Starts a stroke at its first point; later points follow as `appendPoints`.
    public static func startStroke(_ stroke: DrawingStroke) throws -> DrawingOperation {
        guard let first = stroke.points.first else { throw DrawingError.invalidStroke }
        return DrawingOperation(type: "ADD_STROKE", strokeId: stroke.id, payload: try .encoded(StrokeStart(id: stroke.id, colorArgb: stroke.colorArgb, width: stroke.width, createdAt: stroke.createdAt, firstPoint: first)))
    }

    public static func appendPoints(_ points: [DrawingPoint], to strokeId: String) throws -> DrawingOperation {
        DrawingOperation(type: "APPEND_POINTS", strokeId: strokeId, payload: try .encoded(StrokePoints(points: points)))
    }

    /// Restoring a stroke (redo or undo-delete) uses fresh operation IDs, so the server
    /// accepts the restoration instead of treating it as a retry of the original draw.
    public static func restore(_ stroke: DrawingStroke) throws -> [DrawingOperation] {
        guard let first = stroke.points.first else { return [] }
        var operations = [DrawingOperation(type: "ADD_STROKE", strokeId: stroke.id, payload: try .encoded(StrokeStart(id: stroke.id, colorArgb: stroke.colorArgb, width: stroke.width, createdAt: stroke.createdAt, firstPoint: first)))]
        let remaining = Array(stroke.points.dropFirst())
        for offset in stride(from: 0, to: remaining.count, by: 64) {
            operations.append(DrawingOperation(type: "APPEND_POINTS", strokeId: stroke.id, payload: try .encoded(StrokePoints(points: Array(remaining[offset..<min(offset + 64, remaining.count)])))))
        }
        operations.append(DrawingOperation(type: "FINISH_STROKE", strokeId: stroke.id))
        return operations
    }
}

public struct DrawingOperations: Codable, Sendable { public let operations: [DrawingOperation] }

/// Encodes only client fields. Server identity/revision must never be supplied by a client.
public struct DrawingBatch: Encodable, Sendable {
    public let batchId: String
    public let operations: [DrawingOperation]
    public let clientCreatedAt: String

    public init(operations: [DrawingOperation], batchId: String = UUID().uuidString, clientCreatedAt: String = ServerDate.string()) {
        self.batchId = batchId; self.operations = operations; self.clientCreatedAt = clientCreatedAt
    }

    private struct ClientOperation: Encodable {
        let clientOperationId: String
        let type: String
        let strokeId: String?
        let payload: JSONValue
        let clientCreatedAt: String
        let clientLocalDate: String?
    }
    private enum CodingKeys: CodingKey { case batchId, operations, clientCreatedAt }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(batchId, forKey: .batchId)
        try c.encode(clientCreatedAt, forKey: .clientCreatedAt)
        try c.encode(operations.map { ClientOperation(clientOperationId: $0.id, type: $0.type, strokeId: $0.strokeId, payload: $0.payload, clientCreatedAt: $0.clientCreatedAt, clientLocalDate: $0.clientLocalDate) }, forKey: .operations)
    }
}

public struct DrawingSnapshot: Decodable, Sendable {
    public let pairSessionId: String
    public let snapshotRevision: Int64
    public let latestRevision: Int64
    public let revision: Int64
    public let snapshot: DrawingSnapshotPayload
    public let updatedAt: String?
}

public struct DrawingSnapshotPayload: Decodable, Sendable {
    public let strokes: [DrawingStroke]
    public let elements: [JSONValue]
    private enum CodingKeys: CodingKey { case strokes, elements, textElements }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        strokes = try c.decodeIfPresent([DrawingStroke].self, forKey: .strokes) ?? []
        if let elements = try c.decodeIfPresent([JSONValue].self, forKey: .elements), !elements.isEmpty {
            self.elements = elements
        } else {
            self.elements = try (c.decodeIfPresent([JSONValue].self, forKey: .textElements) ?? []).map {
                guard case .object(var fields) = $0 else { return $0 }
                fields["kind"] = .string("TEXT")
                return .object(fields)
            }
        }
    }
}

public enum DrawingError: Error, Equatable, LocalizedError {
    case wrongSession, invalidStroke, missingStroke(String), revisionGap(expected: Int64, actual: Int64), unsupportedOperation(String)
    public var errorDescription: String? {
        switch self {
        case .wrongSession: "This drawing belongs to a different pairing."
        case .invalidStroke: "This stroke could not be read."
        case .missingStroke, .revisionGap: "The canvas needs to sync again."
        case .unsupportedOperation: "This drawing needs a newer version of Mulberry."
        }
    }
}

public struct DrawingState: Codable, Equatable, Sendable {
    public var strokes: [DrawingStroke] = []
    public var elements: [JSONValue] = []
    public var revision: Int64 = 0
    private var activeStrokes: [String: DrawingStroke] = [:]
    public init() {}

    public var finishedStrokes: [DrawingStroke] { strokes.filter { $0.finished == true } }

    public mutating func apply(_ operation: DrawingOperation) throws {
        switch operation.type {
        case "ADD_STROKE":
            let payload = try operation.payload.decode(StrokeStart.self)
            guard operation.strokeId == payload.id, payload.firstPoint.isValid, payload.width.isFinite, payload.width > 0 else { throw DrawingError.invalidStroke }
            strokes.removeAll { $0.id == payload.id }
            let stroke = DrawingStroke(id: payload.id, colorArgb: payload.colorArgb, width: payload.width, createdAt: payload.createdAt, points: [payload.firstPoint])
            strokes.append(stroke)
            activeStrokes[payload.id] = stroke
        case "APPEND_POINTS":
            let payload = try operation.payload.decode(StrokePoints.self)
            guard payload.points.allSatisfy(\.isValid) else { throw DrawingError.invalidStroke }
            guard let id = operation.strokeId, var active = activeStrokes[id] else { throw DrawingError.missingStroke(operation.strokeId ?? "") }
            active.points.append(contentsOf: payload.points)
            activeStrokes[id] = active
            if let index = strokes.firstIndex(where: { $0.id == id }) { strokes[index] = active }
        case "FINISH_STROKE":
            guard let id = operation.strokeId else { throw DrawingError.invalidStroke }
            if var active = activeStrokes.removeValue(forKey: id) {
                // The backend reconstructs FINISH from stroke history, including when
                // the partner cleared the canvas while this stroke was in flight.
                active.finished = true
                strokes.removeAll { $0.id == id }
                strokes.append(active)
            } else if !strokes.contains(where: { $0.id == id && $0.finished == true }) {
                throw DrawingError.missingStroke(id)
            }
        case "DELETE_STROKE": strokes.removeAll { $0.id == operation.strokeId }
        case "CLEAR_CANVAS": strokes = []; elements = []
        case "ADD_TEXT_ELEMENT", "UPDATE_TEXT_ELEMENT", "ADD_STICKER_ELEMENT", "UPDATE_STICKER_ELEMENT":
            guard case .object(var fields) = operation.payload, let id = fields["id"]?.string else { throw DrawingError.invalidStroke }
            fields["kind"] = .string(operation.type.contains("TEXT") ? "TEXT" : "STICKER")
            elements.removeAll { $0["id"]?.string == id }
            elements.append(.object(fields))
        case "DELETE_TEXT_ELEMENT", "DELETE_STICKER_ELEMENT": elements.removeAll { $0["id"]?.string == operation.strokeId }
        default: throw DrawingError.unsupportedOperation(operation.type)
        }
        if let revision = operation.serverRevision { self.revision = revision }
    }
}

/// Durable state is scoped to one user AND one pair. Confirmed operations are applied
/// in contiguous revision order; optimistic operations are replayed separately.
public struct DrawingDocument: Codable, Equatable, Sendable {
    public let userId: String
    public let pairSessionId: String
    public private(set) var confirmed = DrawingState()
    public private(set) var pending: [DrawingOperation] = []
    /// Advances with every change, so a newer document can be told from an older one.
    public private(set) var version: UInt64 = 0
    /// The confirmed drawing with the pending edits applied: what this device shows. It is kept
    /// current as the document changes, rather than replayed on every read, and is never saved.
    public private(set) var visible = DrawingState()
    // Retained after server acknowledgement until this phone finishes the stroke.
    // Optional keeps documents written by earlier builds readable.
    private var unfinishedLocalStrokes: Set<String>?

    private enum CodingKeys: String, CodingKey { case userId, pairSessionId, confirmed, pending, version, unfinishedLocalStrokes }

    public init(userId: String, pairSessionId: String) { self.userId = userId; self.pairSessionId = pairSessionId }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        userId = try c.decode(String.self, forKey: .userId)
        pairSessionId = try c.decode(String.self, forKey: .pairSessionId)
        confirmed = try c.decode(DrawingState.self, forKey: .confirmed)
        pending = try c.decode([DrawingOperation].self, forKey: .pending)
        version = try c.decode(UInt64.self, forKey: .version)
        unfinishedLocalStrokes = try c.decodeIfPresent(Set<String>.self, forKey: .unfinishedLocalStrokes)
        visible = Self.replay(pending, onto: confirmed)
    }

    public mutating func enqueue(_ operations: [DrawingOperation]) throws {
        var preview = visible
        var additions: [DrawingOperation] = []
        var ids = Set(pending.map(\.id))
        for operation in operations where ids.insert(operation.id).inserted {
            guard operation.serverRevision == nil, operation.pairSessionId == nil || operation.pairSessionId == pairSessionId else { throw DrawingError.wrongSession }
            try preview.apply(operation)
            additions.append(operation)
        }
        guard !additions.isEmpty else { return }
        pending.append(contentsOf: additions)
        visible = preview
        var unfinished = unfinishedLocalStrokes ?? []
        for operation in additions {
            if operation.type == "ADD_STROKE", let id = operation.strokeId { unfinished.insert(id) }
            if ["FINISH_STROKE", "DELETE_STROKE"].contains(operation.type), let id = operation.strokeId { unfinished.remove(id) }
            if operation.type == "CLEAR_CANVAS" { unfinished.removeAll() }
        }
        unfinishedLocalStrokes = unfinished
        version += 1
    }

    /// Called when reopening this pair's editor, never on a socket reconnect.
    /// Recover only strokes started on this installation, not a partner's stroke.
    public mutating func finishInterruptedStrokes() throws {
        try finishInterruptedStrokes(with: interruptedStrokeFinishes)
    }

    /// A FINISH_STROKE for each stroke this installation began and never finished.
    var interruptedStrokeFinishes: [DrawingOperation] {
        let ids = (unfinishedLocalStrokes ?? []).filter { id in visible.strokes.contains { $0.id == id && $0.finished != true } }
        return ids.sorted().map { .init(type: "FINISH_STROKE", strokeId: $0) }
    }

    /// Takes the finishing operations as made, so replaying this change gives the same document.
    mutating func finishInterruptedStrokes(with finishes: [DrawingOperation]) throws {
        let before = version
        try enqueue(finishes)
        guard unfinishedLocalStrokes != [] else { return }
        unfinishedLocalStrokes = []
        if version == before { version += 1 }
    }

    /// All-or-nothing. A partial batch must not leave the persisted cursor beyond a gap.
    public mutating func receive(_ operations: [DrawingOperation]) throws {
        var next = confirmed
        var acknowledged = Set<String>()
        for operation in operations.sorted(by: { ($0.serverRevision ?? 0) < ($1.serverRevision ?? 0) }) {
            guard operation.pairSessionId == pairSessionId, let revision = operation.serverRevision else { throw DrawingError.wrongSession }
            if operation.actorUserId == userId { acknowledged.insert(operation.id) }
            if revision <= next.revision { continue }
            guard revision == next.revision + 1 else { throw DrawingError.revisionGap(expected: next.revision + 1, actual: revision) }
            try next.apply(operation)
        }
        confirmed = next
        pending.removeAll { acknowledged.contains($0.id) }
        visible = Self.replay(pending, onto: confirmed)
        version += 1
    }

    public mutating func rebuild(from operations: [DrawingOperation]) throws {
        var rebuilt = DrawingDocument(userId: userId, pairSessionId: pairSessionId)
        rebuilt.pending = pending
        rebuilt.unfinishedLocalStrokes = unfinishedLocalStrokes
        try rebuilt.receive(operations)
        rebuilt.version = version + 1
        self = rebuilt
    }

    /// Pending edits were each checked as they were made. One the confirmed drawing no longer
    /// takes only goes unshown here; it is still sent, and the server decides.
    private static func replay(_ pending: [DrawingOperation], onto confirmed: DrawingState) -> DrawingState {
        var state = confirmed
        for operation in pending { try? state.apply(operation) }
        return state
    }
}

extension DrawingDocument {
    /// One change to a document, as `DrawingStore` journals it. Applying the same changes in the
    /// same order always gives the same document, so the journal can be replayed after a restart.
    enum Change: Codable, Sendable {
        case enqueue([DrawingOperation])
        case receive([DrawingOperation])
        case rebuild([DrawingOperation])
        case finishInterruptedStrokes([DrawingOperation])
    }

    mutating func apply(_ change: Change) throws {
        switch change {
        case .enqueue(let operations): try enqueue(operations)
        case .receive(let operations): try receive(operations)
        case .rebuild(let operations): try rebuild(from: operations)
        case .finishInterruptedStrokes(let finishes): try finishInterruptedStrokes(with: finishes)
        }
    }
}
