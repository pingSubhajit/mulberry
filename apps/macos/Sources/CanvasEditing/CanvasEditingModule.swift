import CoreGraphics
import Foundation
import ProductCore

public enum CanvasEditingModule {
    public static let name = "CanvasEditing"
}

/// One tool is always selected, as on iPhone and iPad; the brush is the default.
public enum CanvasEditingTool: String, Codable, CaseIterable, Sendable {
    case draw = "DRAW"
    case erase = "ERASE"
    case text = "TEXT"
    case sticker = "STICKER"
    case eyedropper = "EYEDROPPER"

    /// The eyedropper is a moment's detour: picking a colour returns to the tool before it.
    public var isReturnedTo: Bool {
        self != .eyedropper
    }
}

public enum CanvasEditingDefaults {
    public static let defaultColorArgb: UInt32 = 0xFFB31329
    public static let defaultBrushWidthPx: Float = 10
    public static let minBrushWidthPx: Float = 4
    public static let maxBrushWidthPx: Float = 42
    public static let strokeHitTolerancePx: Float = 16
    public static let minViewportScale: CGFloat = 1
    public static let maxViewportScale: CGFloat = 4

    public static let palette: [UInt32] = [
        0xFFB31329,
        0xFFFF6A2A,
        0xFFFFE66D,
        0xFF2457D6,
        0xFF5BB7E8,
        0xFF006B4F,
        0xFF567A3A,
        0xFF80D8B0,
        0xFFE85072,
        0xFFA78BFA,
        0xFF3B2F8F,
        0xFFF5B83D,
        0xFF141414,
        0xFFF7F4EF,
        0xFF3A3A3A,
        0xFF7A4A32,
        0xFFB56A43,
        0xFFD8A0A8,
        0xFF00A53C
    ]

    public static func clampedBrushWidth(_ width: Float) -> Float {
        width.clamped(to: minBrushWidthPx...maxBrushWidthPx)
    }
}

public struct CanvasEditingToolState: Codable, Equatable, Sendable {
    public var activeTool: CanvasEditingTool
    public var previousTool: CanvasEditingTool
    public var brushColorArgb: UInt32
    public var textColorArgb: UInt32
    public var selectedBrushWidthPx: Float

    public init(
        activeTool: CanvasEditingTool = .draw,
        previousTool: CanvasEditingTool = .draw,
        brushColorArgb: UInt32 = CanvasEditingDefaults.defaultColorArgb,
        textColorArgb: UInt32 = CanvasEditingDefaults.defaultColorArgb,
        selectedBrushWidthPx: Float = CanvasEditingDefaults.defaultBrushWidthPx
    ) {
        self.activeTool = activeTool
        self.previousTool = previousTool.isReturnedTo ? previousTool : .draw
        self.brushColorArgb = brushColorArgb
        self.textColorArgb = textColorArgb
        self.selectedBrushWidthPx = CanvasEditingDefaults.clampedBrushWidth(selectedBrushWidthPx)
        if activeTool.isReturnedTo {
            self.previousTool = activeTool
        }
    }

    public var selectedColorArgb: UInt32 {
        activeTool == .text ? textColorArgb : brushColorArgb
    }

    public mutating func setActiveTool(_ tool: CanvasEditingTool) {
        activeTool = tool
        if tool.isReturnedTo {
            previousTool = tool
        }
    }

    public mutating func setSelectedColor(_ colorArgb: UInt32) {
        if activeTool == .text {
            textColorArgb = colorArgb
        } else {
            brushColorArgb = colorArgb
        }
    }

    public mutating func commitEyedropperColor(_ colorArgb: UInt32) {
        brushColorArgb = colorArgb
        textColorArgb = colorArgb
        activeTool = previousTool
    }

    public mutating func setBrushWidth(_ widthPx: Float) {
        selectedBrushWidthPx = CanvasEditingDefaults.clampedBrushWidth(widthPx)
    }
}

public struct CanvasViewportTransform: Equatable, Sendable {
    public var scale: CGFloat
    public var offset: CGPoint

    public init(scale: CGFloat = 1, offset: CGPoint = .zero) {
        self.scale = scale.clamped(
            to: CanvasEditingDefaults.minViewportScale...CanvasEditingDefaults.maxViewportScale
        )
        self.offset = offset
    }

    public func contentPoint(fromRenderedPoint point: CGPoint) -> CGPoint {
        let safeScale = max(scale, 0.0001)
        return CGPoint(
            x: (point.x - offset.x) / safeScale,
            y: (point.y - offset.y) / safeScale
        )
    }

    public func renderedPoint(fromContentPoint point: CGPoint) -> CGPoint {
        CGPoint(
            x: (point.x * scale) + offset.x,
            y: (point.y * scale) + offset.y
        )
    }

    public func normalizedPoint(fromRenderedPoint point: CGPoint, surfaceSize: CGSize) -> DrawingPoint {
        contentPoint(fromRenderedPoint: point).normalized(in: surfaceSize)
    }

    public func renderedPoint(fromNormalizedPoint point: DrawingPoint, surfaceSize: CGSize) -> CGPoint {
        renderedPoint(fromContentPoint: point.denormalized(in: surfaceSize))
    }

    public func zoomed(by factor: CGFloat, around anchor: CGPoint) -> CanvasViewportTransform {
        let oldScale = max(scale, 0.0001)
        let nextScale = (scale * factor).clamped(
            to: CanvasEditingDefaults.minViewportScale...CanvasEditingDefaults.maxViewportScale
        )
        let contentUnderAnchor = CGPoint(
            x: (anchor.x - offset.x) / oldScale,
            y: (anchor.y - offset.y) / oldScale
        )
        let nextOffset = CGPoint(
            x: anchor.x - (contentUnderAnchor.x * nextScale),
            y: anchor.y - (contentUnderAnchor.y * nextScale)
        )
        return CanvasViewportTransform(scale: nextScale, offset: nextOffset)
    }
}

/// Tool choices and the last canvas size, as Android keeps them between sessions.
public struct CanvasEditingMetadata: Codable, Equatable, Sendable {
    public var toolState: CanvasEditingToolState
    public var lastViewportWidthPx: Int
    public var lastViewportHeightPx: Int

    public init(
        toolState: CanvasEditingToolState = CanvasEditingToolState(),
        lastViewportWidthPx: Int = 0,
        lastViewportHeightPx: Int = 0
    ) {
        self.toolState = toolState
        self.lastViewportWidthPx = max(0, lastViewportWidthPx)
        self.lastViewportHeightPx = max(0, lastViewportHeightPx)
    }
}

public enum CanvasEditingDisabledReason: Equatable, Sendable {
    case signedOut
    case unpaired
    case missingPairSession
    case waitingForCanvasState
    case localStateError
}

public enum CanvasEditingAvailability: Equatable, Sendable {
    case enabled
    case disabled(CanvasEditingDisabledReason)

    public var isEnabled: Bool {
        self == .enabled
    }
}

public struct CanvasEditingAvailabilityInput: Equatable, Sendable {
    public var isAuthenticated: Bool
    public var isPaired: Bool
    public var pairSessionID: String?
    public var hasUsableCanvasState: Bool
    public var hasHardLocalStateError: Bool

    public init(
        isAuthenticated: Bool,
        isPaired: Bool,
        pairSessionID: String?,
        hasUsableCanvasState: Bool,
        hasHardLocalStateError: Bool = false
    ) {
        self.isAuthenticated = isAuthenticated
        self.isPaired = isPaired
        self.pairSessionID = pairSessionID
        self.hasUsableCanvasState = hasUsableCanvasState
        self.hasHardLocalStateError = hasHardLocalStateError
    }
}

public struct CanvasEditingAvailabilityPolicy: Sendable {
    public init() {}

    public func availability(for input: CanvasEditingAvailabilityInput) -> CanvasEditingAvailability {
        guard input.isAuthenticated else { return .disabled(.signedOut) }
        guard input.isPaired else { return .disabled(.unpaired) }
        guard input.pairSessionID?.isEmpty == false else { return .disabled(.missingPairSession) }
        guard input.hasHardLocalStateError == false else { return .disabled(.localStateError) }
        guard input.hasUsableCanvasState else { return .disabled(.waitingForCanvasState) }
        return .enabled
    }
}

/// Stamps local edits with their IDs and times. Injectable so tests are deterministic.
public struct CanvasEditingOperationFactory: Sendable {
    public var uuidProvider: @Sendable () -> UUID
    public var nowProvider: @Sendable () -> Date

    public init(
        uuidProvider: @escaping @Sendable () -> UUID = { UUID() },
        nowProvider: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.uuidProvider = uuidProvider
        self.nowProvider = nowProvider
    }

    public func makeOperation(type: String, strokeId: String?, payload: JSONValue = .object([:]), at date: Date? = nil) -> DrawingOperation {
        let timestamp = date ?? nowProvider()
        return DrawingOperation(
            clientOperationId: uuidProvider().uuidString.lowercased(),
            type: type,
            strokeId: strokeId,
            payload: payload,
            clientCreatedAt: ServerDate.string(timestamp),
            clientLocalDate: ServerDate.localDay(timestamp)
        )
    }

    public func makeAddStroke(_ stroke: DrawingStroke, at date: Date? = nil) throws -> DrawingOperation {
        let start = try DrawingOperation.startStroke(stroke)
        return makeOperation(type: start.type, strokeId: start.strokeId, payload: start.payload, at: date)
    }

    public func makeAppendPoints(strokeId: String, points: [DrawingPoint], at date: Date? = nil) throws -> DrawingOperation {
        let append = try DrawingOperation.appendPoints(points, to: strokeId)
        return makeOperation(type: append.type, strokeId: strokeId, payload: append.payload, at: date)
    }

    public func makeFinishStroke(strokeId: String, at date: Date? = nil) -> DrawingOperation {
        makeOperation(type: "FINISH_STROKE", strokeId: strokeId, at: date)
    }

    public func makeDeleteStroke(strokeId: String, at date: Date? = nil) -> DrawingOperation {
        makeOperation(type: "DELETE_STROKE", strokeId: strokeId, at: date)
    }

    public func makeClearCanvas(at date: Date? = nil) -> DrawingOperation {
        makeOperation(type: "CLEAR_CANVAS", strokeId: nil, at: date)
    }
}

/// Turns pointer input into canvas operations. Undo and redo use ProductCore's `DrawingHistory`,
/// the same history as the iPhone and iPad editor: they only touch this Mac's own strokes.
public struct CanvasStrokeEditingEngine: Sendable {
    public var toolState: CanvasEditingToolState
    public var viewportTransform: CanvasViewportTransform
    public var appendBatchSize: Int
    public private(set) var activeStroke: DrawingStroke?
    public private(set) var clearConfirmationRequested: Bool

    private var pendingAppendPoints: [DrawingPoint]
    private var history: DrawingHistory
    private var operationFactory: CanvasEditingOperationFactory
    private var strokeIDProvider: @Sendable () -> String

    public init(
        toolState: CanvasEditingToolState = CanvasEditingToolState(),
        viewportTransform: CanvasViewportTransform = CanvasViewportTransform(),
        appendBatchSize: Int = 4,
        operationFactory: CanvasEditingOperationFactory = CanvasEditingOperationFactory(),
        strokeIDProvider: @escaping @Sendable () -> String = { UUID().uuidString.lowercased() }
    ) {
        self.toolState = toolState
        self.viewportTransform = viewportTransform
        self.appendBatchSize = max(1, appendBatchSize)
        self.operationFactory = operationFactory
        self.strokeIDProvider = strokeIDProvider
        self.activeStroke = nil
        self.clearConfirmationRequested = false
        self.pendingAppendPoints = []
        self.history = DrawingHistory()
    }

    public var canUndo: Bool {
        activeStroke != nil || history.canUndo
    }

    public var canRedo: Bool {
        activeStroke == nil && history.canRedo
    }

    public mutating func startStroke(at renderedPoint: CGPoint, surfaceSize: CGSize, date: Date = Date()) -> [DrawingOperation] {
        guard toolState.activeTool == .draw else { return [] }
        let point = viewportTransform.normalizedPoint(fromRenderedPoint: renderedPoint, surfaceSize: surfaceSize)
        let stroke = DrawingStroke(
            id: strokeIDProvider(),
            colorArgb: toolState.brushColorArgb,
            width: normalizeBrushWidth(widthPx: toolState.selectedBrushWidthPx, surfaceSize: surfaceSize),
            createdAt: Int64((date.timeIntervalSince1970 * 1_000).rounded()),
            points: [point]
        )
        guard let operation = try? operationFactory.makeAddStroke(stroke, at: date) else { return [] }
        activeStroke = stroke
        pendingAppendPoints.removeAll()
        return [operation]
    }

    public mutating func appendStrokePoint(at renderedPoint: CGPoint, surfaceSize: CGSize, date: Date = Date()) -> [DrawingOperation] {
        guard var stroke = activeStroke else { return [] }
        let point = viewportTransform.normalizedPoint(fromRenderedPoint: renderedPoint, surfaceSize: surfaceSize)
        guard stroke.points.last != point else { return [] }
        stroke.points.append(point)
        activeStroke = stroke
        pendingAppendPoints.append(point)
        guard pendingAppendPoints.count >= appendBatchSize else { return [] }
        return flushAppendPoints(date: date)
    }

    public mutating func finishStroke(date: Date = Date()) -> [DrawingOperation] {
        guard var stroke = activeStroke else { return [] }
        var operations = flushAppendPoints(date: date)
        operations.append(operationFactory.makeFinishStroke(strokeId: stroke.id, at: date))
        stroke.finished = true
        history.recordDraw(stroke)
        activeStroke = nil
        pendingAppendPoints.removeAll()
        return operations
    }

    public mutating func eraseStroke(at renderedPoint: CGPoint, in state: DrawingState, surfaceSize: CGSize, date: Date = Date()) -> [DrawingOperation] {
        guard toolState.activeTool == .erase else { return [] }
        let point = viewportTransform.normalizedPoint(fromRenderedPoint: renderedPoint, surfaceSize: surfaceSize)
            .denormalized(in: surfaceSize)
        guard let stroke = Self.hitStroke(at: point, strokes: state.finishedStrokes, surfaceSize: surfaceSize) else {
            return []
        }
        history.recordErase(stroke)
        return [operationFactory.makeDeleteStroke(strokeId: stroke.id, at: date)]
    }

    public mutating func requestClearCanvas() {
        clearConfirmationRequested = true
    }

    public mutating func cancelClearCanvas() {
        clearConfirmationRequested = false
    }

    public mutating func confirmClearCanvas(date: Date = Date()) -> [DrawingOperation] {
        clearConfirmationRequested = false
        activeStroke = nil
        pendingAppendPoints.removeAll()
        history.reset()
        return [operationFactory.makeClearCanvas(at: date)]
    }

    /// Undoing mid-stroke drops the stroke being drawn.
    public mutating func undo(date: Date = Date()) -> [DrawingOperation] {
        if let activeStroke {
            self.activeStroke = nil
            pendingAppendPoints.removeAll()
            return [operationFactory.makeDeleteStroke(strokeId: activeStroke.id, at: date)]
        }
        return (try? history.undo()) ?? []
    }

    public mutating func redo() -> [DrawingOperation] {
        guard activeStroke == nil else { return [] }
        return (try? history.redo()) ?? []
    }

    // MARK: Text and stickers

    /// Saves an edited text or sticker. Blank text is never saved: a new one is discarded and an
    /// existing one deleted, as on Android and iOS. An unchanged edit saves nothing.
    public mutating func commit(_ element: CanvasElement, isNew: Bool, in state: DrawingState) -> [DrawingOperation] {
        let empty: Bool
        switch element {
        case let .text(text):
            empty = text.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case let .sticker(sticker):
            empty = sticker.sticker.stickerId.isEmpty
        }
        if isNew {
            guard !empty else { return [] }
            history.recordAdd(element)
            return [.add(element)]
        }
        if empty {
            return delete(elementID: element.id, in: state)
        }
        return update(element, in: state)
    }

    /// Moves, resizes or rotates an element already on the canvas, as one undo step.
    public mutating func update(_ element: CanvasElement, in state: DrawingState) -> [DrawingOperation] {
        guard let before = state.canvasElements.first(where: { $0.id == element.id }), before != element else { return [] }
        history.recordUpdate(from: before, to: element)
        return [.update(element)]
    }

    public mutating func delete(elementID: String, in state: DrawingState) -> [DrawingOperation] {
        guard let existing = state.canvasElements.first(where: { $0.id == elementID }) else { return [] }
        history.recordDelete(existing)
        return [.delete(existing)]
    }

    private mutating func flushAppendPoints(date: Date) -> [DrawingOperation] {
        guard let activeStroke, pendingAppendPoints.isEmpty == false else { return [] }
        let points = pendingAppendPoints
        pendingAppendPoints.removeAll()
        guard let operation = try? operationFactory.makeAppendPoints(strokeId: activeStroke.id, points: points, at: date) else {
            return []
        }
        return [operation]
    }

    private static func hitStroke(at point: CGPoint, strokes: [DrawingStroke], surfaceSize: CGSize) -> DrawingStroke? {
        strokes.reversed().first { stroke in
            let rendered = stroke.denormalizedStroke(in: surfaceSize)
            let threshold = (rendered.width / 2) + CGFloat(CanvasEditingDefaults.strokeHitTolerancePx)
            guard rendered.points.isEmpty == false else { return false }
            if rendered.points.count == 1 {
                return rendered.points[0].distance(to: point) <= threshold
            }
            return zip(rendered.points, rendered.points.dropFirst()).contains { start, end in
                point.distance(toSegmentStart: start, end: end) <= threshold
            }
        }
    }
}

public extension CGSize {
    var safeCanvasWidth: CGFloat { max(width, 1) }
    var safeCanvasHeight: CGFloat { max(height, 1) }
    var safeCanvasReferenceDimension: CGFloat { min(safeCanvasWidth, safeCanvasHeight) }
}

public extension CGPoint {
    func normalized(in surfaceSize: CGSize) -> DrawingPoint {
        DrawingPoint(
            x: Double((x / surfaceSize.safeCanvasWidth).clamped(to: 0...1)),
            y: Double((y / surfaceSize.safeCanvasHeight).clamped(to: 0...1))
        )
    }
}

public extension DrawingPoint {
    func denormalized(in surfaceSize: CGSize) -> CGPoint {
        CGPoint(
            x: CGFloat(x) * surfaceSize.safeCanvasWidth,
            y: CGFloat(y) * surfaceSize.safeCanvasHeight
        )
    }
}

/// Stroke widths travel as a fraction of the canvas's shorter side, as on Android and iOS.
public func normalizeBrushWidth(widthPx: Float, surfaceSize: CGSize) -> Double {
    let reference = Double(surfaceSize.safeCanvasReferenceDimension)
    return max(0, Double(widthPx) / max(reference, 1))
}

public func denormalizeBrushWidth(_ normalizedWidth: Double, surfaceSize: CGSize) -> Float {
    let reference = Double(surfaceSize.safeCanvasReferenceDimension)
    return Float(max(0, normalizedWidth) * max(reference, 1))
}

private struct RenderedStroke {
    var id: String
    var width: CGFloat
    var points: [CGPoint]
}

private extension DrawingStroke {
    func denormalizedStroke(in surfaceSize: CGSize) -> RenderedStroke {
        RenderedStroke(
            id: id,
            width: CGFloat(width) * surfaceSize.safeCanvasReferenceDimension,
            points: points.map { $0.denormalized(in: surfaceSize) }
        )
    }
}

private extension CGPoint {
    func distance(to other: CGPoint) -> CGFloat {
        let dx = x - other.x
        let dy = y - other.y
        return sqrt(dx * dx + dy * dy)
    }

    func distance(toSegmentStart start: CGPoint, end: CGPoint) -> CGFloat {
        let dx = end.x - start.x
        let dy = end.y - start.y
        guard dx != 0 || dy != 0 else {
            return distance(to: start)
        }
        let projection = (((x - start.x) * dx) + ((y - start.y) * dy)) / ((dx * dx) + (dy * dy))
        let t = projection.clamped(to: 0...1)
        return distance(to: CGPoint(x: start.x + (t * dx), y: start.y + (t * dy)))
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
