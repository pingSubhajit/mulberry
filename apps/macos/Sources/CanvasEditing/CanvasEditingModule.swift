import CanvasCore
import CoreGraphics
import Foundation
import Persistence

public enum CanvasEditingModule {
    public static let name = "CanvasEditing"
}

public enum CanvasEditingTool: String, Codable, CaseIterable, Sendable {
    case none = "NONE"
    case draw = "DRAW"
    case erase = "ERASE"
    case text = "TEXT"
    case sticker = "STICKER"
    case eyedropper = "EYEDROPPER"

    public var updatesLastNonNoneTool: Bool {
        switch self {
        case .draw, .erase, .text, .sticker:
            true
        case .none, .eyedropper:
            false
        }
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

public struct CanvasEditingToolState: Equatable, Sendable {
    public var activeTool: CanvasEditingTool
    public var lastNonNoneTool: CanvasEditingTool
    public var brushColorArgb: UInt32
    public var textColorArgb: UInt32
    public var selectedBrushWidthPx: Float

    public init(
        activeTool: CanvasEditingTool = .none,
        lastNonNoneTool: CanvasEditingTool = .draw,
        brushColorArgb: UInt32 = CanvasEditingDefaults.defaultColorArgb,
        textColorArgb: UInt32 = CanvasEditingDefaults.defaultColorArgb,
        selectedBrushWidthPx: Float = CanvasEditingDefaults.defaultBrushWidthPx
    ) {
        self.activeTool = activeTool
        self.lastNonNoneTool = lastNonNoneTool.updatesLastNonNoneTool ? lastNonNoneTool : .draw
        self.brushColorArgb = brushColorArgb
        self.textColorArgb = textColorArgb
        self.selectedBrushWidthPx = CanvasEditingDefaults.clampedBrushWidth(selectedBrushWidthPx)
        if activeTool.updatesLastNonNoneTool {
            self.lastNonNoneTool = activeTool
        }
    }

    public var selectedColorArgb: UInt32 {
        activeTool == .text ? textColorArgb : brushColorArgb
    }

    public mutating func setActiveTool(_ tool: CanvasEditingTool) {
        activeTool = tool
        if tool.updatesLastNonNoneTool {
            lastNonNoneTool = tool
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
        activeTool = lastNonNoneTool
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

    public func normalizedPoint(fromRenderedPoint point: CGPoint, surfaceSize: CGSize) -> CanvasPoint {
        contentPoint(fromRenderedPoint: point).normalized(in: surfaceSize)
    }

    public func renderedPoint(fromNormalizedPoint point: CanvasPoint, surfaceSize: CGSize) -> CGPoint {
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

public struct CanvasEditingMetadata: Equatable, Sendable {
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

    public init(persisted: PersistedCanvasEditingMetadata) {
        self.init(
            toolState: CanvasEditingToolState(
                activeTool: CanvasEditingTool(rawValue: persisted.selectedTool) ?? .none,
                lastNonNoneTool: CanvasEditingTool(rawValue: persisted.lastNonNoneTool) ?? .draw,
                brushColorArgb: persisted.selectedBrushColorArgb,
                textColorArgb: persisted.selectedTextColorArgb,
                selectedBrushWidthPx: persisted.selectedBrushWidthPx
            ),
            lastViewportWidthPx: persisted.lastViewportWidthPx,
            lastViewportHeightPx: persisted.lastViewportHeightPx
        )
    }

    public var persisted: PersistedCanvasEditingMetadata {
        PersistedCanvasEditingMetadata(
            selectedBrushColorArgb: toolState.brushColorArgb,
            selectedTextColorArgb: toolState.textColorArgb,
            selectedBrushWidthPx: toolState.selectedBrushWidthPx,
            selectedTool: toolState.activeTool.rawValue,
            lastNonNoneTool: toolState.lastNonNoneTool.rawValue,
            lastViewportWidthPx: lastViewportWidthPx,
            lastViewportHeightPx: lastViewportHeightPx
        )
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

    public func makeOperation(
        type: CanvasOperationType,
        strokeId: String?,
        payload: CanvasOperationPayload,
        actorUserId: String? = nil,
        pairSessionId: String? = nil,
        at date: Date? = nil
    ) -> CanvasOperation {
        let timestampDate = date ?? nowProvider()
        return CanvasOperation(
            clientOperationId: uuidProvider().uuidString.lowercased(),
            actorUserId: actorUserId,
            pairSessionId: pairSessionId,
            type: type,
            strokeId: strokeId,
            payload: payload,
            clientCreatedAt: DateFormatter.canvasEditingISO8601.string(from: timestampDate),
            clientLocalDate: DateFormatter.canvasEditingLocalDate.string(from: timestampDate)
        )
    }

    public func makeAddStroke(
        strokeId: String,
        colorArgb: UInt32,
        width: Float,
        firstPoint: CanvasPoint,
        createdAt: Int64,
        actorUserId: String? = nil,
        pairSessionId: String? = nil,
        at date: Date? = nil
    ) -> CanvasOperation {
        makeOperation(
            type: .addStroke,
            strokeId: strokeId,
            payload: .addStroke(AddStrokePayload(
                id: strokeId,
                colorArgb: colorArgb,
                width: width,
                createdAt: createdAt,
                firstPoint: firstPoint
            )),
            actorUserId: actorUserId,
            pairSessionId: pairSessionId,
            at: date
        )
    }

    public func makeAppendPoints(
        strokeId: String,
        points: [CanvasPoint],
        actorUserId: String? = nil,
        pairSessionId: String? = nil,
        at date: Date? = nil
    ) -> CanvasOperation {
        makeOperation(
            type: .appendPoints,
            strokeId: strokeId,
            payload: .appendPoints(AppendPointsPayload(points: points)),
            actorUserId: actorUserId,
            pairSessionId: pairSessionId,
            at: date
        )
    }

    public func makeFinishStroke(
        strokeId: String,
        actorUserId: String? = nil,
        pairSessionId: String? = nil,
        at date: Date? = nil
    ) -> CanvasOperation {
        makeOperation(
            type: .finishStroke,
            strokeId: strokeId,
            payload: .finishStroke,
            actorUserId: actorUserId,
            pairSessionId: pairSessionId,
            at: date
        )
    }
}

public extension CGSize {
    var safeCanvasWidth: CGFloat { max(width, 1) }
    var safeCanvasHeight: CGFloat { max(height, 1) }
    var safeCanvasReferenceDimension: CGFloat { min(safeCanvasWidth, safeCanvasHeight) }
}

public extension CGPoint {
    func normalized(in surfaceSize: CGSize) -> CanvasPoint {
        CanvasPoint(
            x: Float((x / surfaceSize.safeCanvasWidth).clamped(to: 0...1)),
            y: Float((y / surfaceSize.safeCanvasHeight).clamped(to: 0...1))
        )
    }
}

public extension CanvasPoint {
    func denormalized(in surfaceSize: CGSize) -> CGPoint {
        CGPoint(
            x: CGFloat(x) * surfaceSize.safeCanvasWidth,
            y: CGFloat(y) * surfaceSize.safeCanvasHeight
        )
    }
}

public func normalizeBrushWidth(widthPx: Float, surfaceSize: CGSize) -> Float {
    let reference = Float(surfaceSize.safeCanvasReferenceDimension)
    return max(0, widthPx / max(reference, 1))
}

public func denormalizeBrushWidth(_ normalizedWidth: Float, surfaceSize: CGSize) -> Float {
    let reference = Float(surfaceSize.safeCanvasReferenceDimension)
    return max(0, normalizedWidth) * max(reference, 1)
}

public extension MulberryDatabase {
    func canvasEditingMetadata() throws -> CanvasEditingMetadata {
        try CanvasEditingMetadata(persisted: persistedCanvasEditingMetadata())
    }

    func saveCanvasEditingMetadata(_ metadata: CanvasEditingMetadata) throws {
        try savePersistedCanvasEditingMetadata(metadata.persisted)
    }
}

private extension DateFormatter {
    static let canvasEditingISO8601: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        return formatter
    }()

    static let canvasEditingLocalDate: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }()
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
