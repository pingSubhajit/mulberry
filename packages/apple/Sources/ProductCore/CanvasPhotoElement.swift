import Foundation

/// How a photo sits on the canvas.
public enum CanvasPhotoStyle: String, CaseIterable, Codable, Sendable {
    /// A thin white border.
    case frame = "FRAME"
    /// A white border with a deeper bottom edge.
    case polaroid = "POLAROID"
    /// The subject cut out, with a white outline.
    case sticker = "STICKER"
}

/// A photo placed on the canvas. `scale` is the framed photo's long edge as a fraction of the
/// canvas width. The sticker style draws `cutoutPhotoId` when there is one.
public struct CanvasPhotoElement: Equatable, Identifiable, Sendable {
    public static let scaleRange: ClosedRange<Double> = 0.12...1.2
    public static let defaultScale = 0.5

    public var id: String
    public var createdAt: Int64
    public var center: DrawingPoint
    public var rotationRad: Double
    public var scale: Double
    public var photoId: String
    public var cutoutPhotoId: String?
    /// The original photo's width over its height.
    public var aspectRatio: Double
    public var style: CanvasPhotoStyle

    public init(id: String = UUID().uuidString, createdAt: Int64 = Int64(Date().timeIntervalSince1970 * 1_000),
                center: DrawingPoint, rotationRad: Double = 0, scale: Double = defaultScale,
                photoId: String, cutoutPhotoId: String? = nil, aspectRatio: Double, style: CanvasPhotoStyle = .frame) {
        self.id = id; self.createdAt = createdAt; self.center = center
        self.rotationRad = rotationRad; self.scale = scale
        self.photoId = photoId; self.cutoutPhotoId = cutoutPhotoId
        self.aspectRatio = aspectRatio; self.style = style
    }

    public init?(json: JSONValue) {
        guard let id = json["id"]?.string, json["kind"]?.string ?? "PHOTO" == "PHOTO",
              let x = json["center"]?["x"]?.number, let y = json["center"]?["y"]?.number,
              let photoId = json["photoId"]?.string, !photoId.isEmpty,
              let aspectRatio = json["aspectRatio"]?.number, aspectRatio > 0, aspectRatio.isFinite else { return nil }
        self.id = id
        createdAt = Int64(json["createdAt"]?.number ?? 0)
        center = DrawingPoint(x: x, y: y)
        rotationRad = json["rotationRad"]?.number ?? 0
        scale = json["scale"]?.number ?? Self.defaultScale
        self.photoId = photoId
        cutoutPhotoId = json["cutoutPhotoId"]?.string.flatMap { $0.isEmpty ? nil : $0 }
        self.aspectRatio = aspectRatio
        style = json["style"]?.string.flatMap(CanvasPhotoStyle.init(rawValue:)) ?? .frame
    }

    public var json: JSONValue {
        var fields: [String: JSONValue] = [
            "id": .string(id), "createdAt": .number(Double(createdAt)),
            "center": .object(["x": .number(center.x), "y": .number(center.y)]),
            "rotationRad": .number(rotationRad), "scale": .number(scale),
            "photoId": .string(photoId), "aspectRatio": .number(aspectRatio), "style": .string(style.rawValue)
        ]
        if let cutoutPhotoId { fields["cutoutPhotoId"] = .string(cutoutPhotoId) }
        return .object(fields)
    }

    public var renderScale: Double { min(Self.scaleRange.upperBound, max(Self.scaleRange.lowerBound, scale)) }

    /// The image the style draws: the cut-out for a sticker, when there is one.
    public var shownPhotoId: String { style == .sticker ? cutoutPhotoId ?? photoId : photoId }
}

/// The server's record of an uploaded canvas photo.
public struct CanvasPhotoUpload: Codable, Equatable, Sendable {
    public let photoId: String
    public let width: Int
    public let height: Int
    public let contentType: String
}

/// A short-lived signed link to a canvas photo in storage.
public struct CanvasPhotoURL: Codable, Equatable, Sendable {
    public let url: String
    public let expiresInSeconds: Int
}

/// Tells the server which canvas content this app can show, so it translates the rest for apps
/// released before that content existed.
public enum CanvasFeatures {
    public static let header = "X-Canvas-Features"
    public static let supported = "photos"
}
