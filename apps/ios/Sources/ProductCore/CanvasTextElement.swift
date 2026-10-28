import Foundation

/// Raw values are Android's enum names, which the backend stores verbatim.
public enum CanvasTextFont: String, CaseIterable, Sendable {
    case poppins = "POPPINS", virgil = "VIRGIL", dmSans = "DM_SANS", spaceMono = "SPACE_MONO"
    case playfairDisplay = "PLAYFAIR_DISPLAY", bangers = "BANGERS", permanentMarker = "PERMANENT_MARKER"
    case kalam = "KALAM", oswald = "OSWALD"
}

public enum CanvasTextAlign: String, CaseIterable, Sendable {
    case left = "LEFT", center = "CENTER", right = "RIGHT"
}

/// Mirrors Android's CanvasTextElement and its sync payload. Geometry is
/// normalized: `center` and `boxWidth` are fractions of the canvas size.
public struct CanvasTextElement: Equatable, Identifiable, Sendable {
    public static let scaleRange: ClosedRange<Double> = 0.3...6
    public static let defaultBoxWidth = 0.7

    public var id: String
    public var text: String
    public var createdAt: Int64
    public var center: DrawingPoint
    public var rotationRad: Double
    public var scale: Double
    public var boxWidth: Double
    public var colorArgb: UInt32
    public var backgroundPillEnabled: Bool
    public var font: CanvasTextFont
    public var alignment: CanvasTextAlign

    public init(id: String = UUID().uuidString, text: String = "", createdAt: Int64 = Int64(Date().timeIntervalSince1970 * 1_000),
                center: DrawingPoint, rotationRad: Double = 0, scale: Double = 1, boxWidth: Double = defaultBoxWidth,
                colorArgb: UInt32, backgroundPillEnabled: Bool = false, font: CanvasTextFont = .poppins, alignment: CanvasTextAlign = .center) {
        self.id = id; self.text = text; self.createdAt = createdAt; self.center = center
        self.rotationRad = rotationRad; self.scale = scale; self.boxWidth = boxWidth; self.colorArgb = colorArgb
        self.backgroundPillEnabled = backgroundPillEnabled; self.font = font; self.alignment = alignment
    }

    /// Reads a materialized element or operation payload with Android's defaults.
    /// Unknown fonts and alignments fall back rather than hiding a partner's text.
    public init?(json: JSONValue) {
        guard let id = json["id"]?.string, json["kind"]?.string ?? "TEXT" == "TEXT",
              let x = json["center"]?["x"]?.number, let y = json["center"]?["y"]?.number else { return nil }
        self.id = id
        text = json["text"]?.string ?? ""
        createdAt = Int64(json["createdAt"]?.number ?? 0)
        center = DrawingPoint(x: x, y: y)
        rotationRad = json["rotationRad"]?.number ?? 0
        scale = json["scale"]?.number ?? 1
        boxWidth = json["boxWidth"]?.number ?? Self.defaultBoxWidth
        let color = json["colorArgb"]?.number ?? Double(0xFF111111)
        colorArgb = color.isFinite && color >= 0 && color <= Double(UInt32.max) ? UInt32(color) : 0xFF111111
        backgroundPillEnabled = json["backgroundPillEnabled"]?.bool ?? false
        font = json["font"]?.string.flatMap(CanvasTextFont.init(rawValue:)) ?? .poppins
        alignment = json["alignment"]?.string.flatMap(CanvasTextAlign.init(rawValue:)) ?? .center
    }

    public var json: JSONValue {
        .object([
            "id": .string(id), "text": .string(text), "createdAt": .number(Double(createdAt)),
            "center": .object(["x": .number(center.x), "y": .number(center.y)]),
            "rotationRad": .number(rotationRad), "scale": .number(scale), "boxWidth": .number(boxWidth),
            "colorArgb": .number(Double(colorArgb)), "backgroundPillEnabled": .bool(backgroundPillEnabled),
            "font": .string(font.rawValue), "alignment": .string(alignment.rawValue)
        ])
    }
}

public extension DrawingOperation {
    static func addText(_ element: CanvasTextElement) -> DrawingOperation {
        .init(type: "ADD_TEXT_ELEMENT", strokeId: element.id, payload: element.json)
    }
    static func updateText(_ element: CanvasTextElement) -> DrawingOperation {
        .init(type: "UPDATE_TEXT_ELEMENT", strokeId: element.id, payload: element.json)
    }
    static func deleteText(_ id: String) -> DrawingOperation {
        .init(type: "DELETE_TEXT_ELEMENT", strokeId: id)
    }
}

public extension DrawingState {
    /// Text elements in paint order; stickers are skipped until iOS supports them.
    var textElements: [CanvasTextElement] { elements.compactMap(CanvasTextElement.init(json:)) }
}

// Foundation may surface JSON 0/1 as either a number or a Boolean, so each
// accessor accepts the other spelling.
extension JSONValue {
    var number: Double? {
        switch self {
        case .number(let value): value
        case .bool(let value): value ? 1 : 0
        default: nil
        }
    }
    var bool: Bool? {
        switch self {
        case .bool(let value): value
        case .number(let value): value != 0
        default: nil
        }
    }
}
