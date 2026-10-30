import Foundation

/// One sticker inside a published pack. Pack versions are immutable, so the
/// triple identifies the same artwork on every device.
public struct StickerReference: Codable, Hashable, Sendable {
    public var packKey: String
    public var packVersion: Int
    public var stickerId: String
    public init(packKey: String, packVersion: Int, stickerId: String) {
        self.packKey = packKey; self.packVersion = packVersion; self.stickerId = stickerId
    }
}

/// Mirrors Android's CanvasStickerElement and its sync payload. `scale` is the
/// sticker's long edge as a fraction of the canvas width.
public struct CanvasStickerElement: Equatable, Identifiable, Sendable {
    public static let scaleRange: ClosedRange<Double> = 0.08...1.6
    public static let defaultScale = 0.22

    public var id: String
    public var createdAt: Int64
    public var center: DrawingPoint
    public var rotationRad: Double
    public var scale: Double
    public var sticker: StickerReference

    public init(id: String = UUID().uuidString, createdAt: Int64 = Int64(Date().timeIntervalSince1970 * 1_000), center: DrawingPoint,
                rotationRad: Double = 0, scale: Double = defaultScale, sticker: StickerReference) {
        self.id = id; self.createdAt = createdAt; self.center = center
        self.rotationRad = rotationRad; self.scale = scale; self.sticker = sticker
    }

    public init?(json: JSONValue) {
        guard let id = json["id"]?.string, json["kind"]?.string ?? "STICKER" == "STICKER",
              let x = json["center"]?["x"]?.number, let y = json["center"]?["y"]?.number,
              let packKey = json["packKey"]?.string, !packKey.isEmpty,
              let packVersion = json["packVersion"]?.number, packVersion > 0,
              let stickerId = json["stickerId"]?.string, !stickerId.isEmpty else { return nil }
        self.id = id
        createdAt = Int64(json["createdAt"]?.number ?? 0)
        center = DrawingPoint(x: x, y: y)
        rotationRad = json["rotationRad"]?.number ?? 0
        scale = json["scale"]?.number ?? Self.defaultScale
        sticker = StickerReference(packKey: packKey, packVersion: Int(packVersion), stickerId: stickerId)
    }

    public var json: JSONValue {
        .object([
            "id": .string(id), "createdAt": .number(Double(createdAt)),
            "center": .object(["x": .number(center.x), "y": .number(center.y)]),
            "rotationRad": .number(rotationRad), "scale": .number(scale),
            "packKey": .string(sticker.packKey), "packVersion": .number(Double(sticker.packVersion)),
            "stickerId": .string(sticker.stickerId)
        ])
    }

    /// Android clamps the drawn size even if a payload carries a wider scale.
    public var renderScale: Double { min(Self.scaleRange.upperBound, max(Self.scaleRange.lowerBound, scale)) }
}

/// A text or sticker element in the canvas's shared paint order.
public enum CanvasElement: Equatable, Identifiable, Sendable {
    case text(CanvasTextElement), sticker(CanvasStickerElement)

    public init?(json: JSONValue) {
        switch json["kind"]?.string {
        case "STICKER": guard let sticker = CanvasStickerElement(json: json) else { return nil }; self = .sticker(sticker)
        default: guard let text = CanvasTextElement(json: json) else { return nil }; self = .text(text)
        }
    }

    public var id: String {
        switch self {
        case .text(let element): element.id
        case .sticker(let element): element.id
        }
    }

    public var center: DrawingPoint {
        get {
            switch self {
            case .text(let element): element.center
            case .sticker(let element): element.center
            }
        }
        set {
            switch self {
            case .text(var element): element.center = newValue; self = .text(element)
            case .sticker(var element): element.center = newValue; self = .sticker(element)
            }
        }
    }

    public var rotationRad: Double {
        get {
            switch self {
            case .text(let element): element.rotationRad
            case .sticker(let element): element.rotationRad
            }
        }
        set {
            switch self {
            case .text(var element): element.rotationRad = newValue; self = .text(element)
            case .sticker(var element): element.rotationRad = newValue; self = .sticker(element)
            }
        }
    }

    /// Multiplies the scale within each kind's range, as pinching does on Android.
    public mutating func scale(by factor: Double) {
        switch self {
        case .text(var element):
            element.scale = min(CanvasTextElement.scaleRange.upperBound, max(CanvasTextElement.scaleRange.lowerBound, element.scale * factor))
            self = .text(element)
        case .sticker(var element):
            element.scale = min(CanvasStickerElement.scaleRange.upperBound, max(CanvasStickerElement.scaleRange.lowerBound, element.scale * factor))
            self = .sticker(element)
        }
    }

    public var text: CanvasTextElement? { if case .text(let element) = self { element } else { nil } }
    public var sticker: CanvasStickerElement? { if case .sticker(let element) = self { element } else { nil } }
}

public extension DrawingOperation {
    static func add(_ element: CanvasElement) -> DrawingOperation {
        switch element {
        case .text(let text): .addText(text)
        case .sticker(let sticker): .init(type: "ADD_STICKER_ELEMENT", strokeId: sticker.id, payload: sticker.json)
        }
    }
    static func update(_ element: CanvasElement) -> DrawingOperation {
        switch element {
        case .text(let text): .updateText(text)
        case .sticker(let sticker): .init(type: "UPDATE_STICKER_ELEMENT", strokeId: sticker.id, payload: sticker.json)
        }
    }
    static func delete(_ element: CanvasElement) -> DrawingOperation {
        switch element {
        case .text(let text): .deleteText(text.id)
        case .sticker(let sticker): .init(type: "DELETE_STICKER_ELEMENT", strokeId: sticker.id)
        }
    }
}

public extension DrawingState {
    /// Text and stickers in paint order. Unreadable elements are skipped.
    var canvasElements: [CanvasElement] { elements.compactMap(CanvasElement.init(json:)) }
}

// MARK: Catalog

public struct StickerPackSummary: Codable, Equatable, Identifiable, Sendable {
    public let packKey: String
    public let packVersion: Int
    public let title: String
    public let description: String
    public let coverThumbnailUrl: String
    public let coverFullUrl: String
    public let sortOrder: Int
    public let featured: Bool
    public var id: String { "\(packKey):\(packVersion)" }
}

public struct StickerSummary: Codable, Equatable, Identifiable, Sendable {
    public let stickerId: String
    public let thumbnailUrl: String
    public let fullUrl: String
    public let width: Int
    public let height: Int
    public let sortOrder: Int
    public var id: String { stickerId }
}

public struct StickerPackDetail: Codable, Equatable, Identifiable, Sendable {
    public let packKey: String
    public let packVersion: Int
    public let title: String
    public let description: String
    public let coverThumbnailUrl: String
    public let coverFullUrl: String
    public let sortOrder: Int
    public let featured: Bool
    public let stickers: [StickerSummary]
    public var id: String { "\(packKey):\(packVersion)" }
}

public struct StickerPackList: Codable, Sendable { public let items: [StickerPackSummary] }
public struct StickerAssetURL: Codable, Sendable { public let url: String; public let expiresInSeconds: Int }
public enum StickerAssetVariant: String, Sendable { case thumbnail, full }

extension StickerReference {
    private static let dragPrefix = "mulberry-sticker:"

    /// The sticker as a dragged string, carried from a sticker picker to the canvas.
    public var dragString: String { "\(Self.dragPrefix)\(packKey)|\(packVersion)|\(stickerId)" }

    /// The sticker a dragged string carries, if it is one.
    public init?(dragString string: String) {
        guard string.hasPrefix(Self.dragPrefix) else { return nil }
        let parts = string.dropFirst(Self.dragPrefix.count).split(separator: "|", omittingEmptySubsequences: false)
        guard parts.count == 3, let version = Int(parts[1]), !parts[0].isEmpty, !parts[2].isEmpty else { return nil }
        self.init(packKey: String(parts[0]), packVersion: version, stickerId: String(parts[2]))
    }
}
