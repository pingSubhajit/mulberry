import Foundation

// Share frames: the art the share image puts around the lock screen. See docs/share-frames.md.
// This file is the catalog and its rules; the drawing lives in ShareFrames.

/// Where a frame's content, caption and brand line go. Only layoutVersion 1 exists.
public struct ShareFrameLayout: Codable, Equatable, Sendable {
    public static let supportedVersion = 1

    public struct Point: Codable, Equatable, Sendable {
        public var x: Double
        public var y: Double
        public init(x: Double, y: Double) { self.x = x; self.y = y }
    }

    public struct Size: Codable, Equatable, Sendable {
        public var width: Double
        public var height: Double
        public init(width: Double, height: Double) { self.width = width; self.height = height }
    }

    /// Where the content image's corners go, in frame pixels: a parallelogram.
    public struct Quad: Codable, Equatable, Sendable {
        public var topLeft: Point
        public var topRight: Point
        public var bottomRight: Point
        public var bottomLeft: Point
        public init(topLeft: Point, topRight: Point, bottomRight: Point, bottomLeft: Point) {
            self.topLeft = topLeft; self.topRight = topRight; self.bottomRight = bottomRight; self.bottomLeft = bottomLeft
        }
    }

    public struct Content: Codable, Equatable, Sendable {
        /// `phone` or `polaroid`; it doesn't change how the frame is drawn.
        public var kind: String
        public var aspectRatio: Double
        public var quad: Quad
    }

    public enum Anchor: String, Codable, Sendable {
        case topLeft, topRight, bottomLeft, bottomRight, center
    }

    public enum Alignment: String, Codable, Sendable {
        case left, right
    }

    public struct Backing: Codable, Equatable, Sendable {
        public var color: String
        public var paddingX: Double
        public var paddingY: Double
        /// The brand's backing only; caption strips always round by 7.
        public var cornerRadius: Double?
    }

    public struct Caption: Codable, Equatable, Sendable {
        public var position: Point
        public var anchor: Anchor
        /// Clockwise for positive values.
        public var rotationDegrees: Double
        public var maxWidth: Double
        public var maxLines: Int
        public var maxFontSize: Int
        public var minFontSize: Int
        public var lineHeight: Double
        public var align: Alignment
        public var inkColor: String
        public var backing: Backing?

        public init(position: Point, anchor: Anchor, rotationDegrees: Double = 0, maxWidth: Double, maxLines: Int,
                    maxFontSize: Int, minFontSize: Int, lineHeight: Double, align: Alignment, inkColor: String, backing: Backing?) {
            self.position = position; self.anchor = anchor; self.rotationDegrees = rotationDegrees
            self.maxWidth = maxWidth; self.maxLines = maxLines; self.maxFontSize = maxFontSize; self.minFontSize = minFontSize
            self.lineHeight = lineHeight; self.align = align; self.inkColor = inkColor; self.backing = backing
        }

        public init(from decoder: any Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            position = try container.decode(Point.self, forKey: .position)
            anchor = try container.decode(Anchor.self, forKey: .anchor)
            rotationDegrees = try container.decodeIfPresent(Double.self, forKey: .rotationDegrees) ?? 0
            maxWidth = try container.decode(Double.self, forKey: .maxWidth)
            maxLines = try container.decode(Int.self, forKey: .maxLines)
            maxFontSize = try container.decode(Int.self, forKey: .maxFontSize)
            minFontSize = try container.decode(Int.self, forKey: .minFontSize)
            lineHeight = try container.decode(Double.self, forKey: .lineHeight)
            align = try container.decode(Alignment.self, forKey: .align)
            inkColor = try container.decode(String.self, forKey: .inkColor)
            backing = try container.decodeIfPresent(Backing.self, forKey: .backing)
        }
    }

    public struct Brand: Codable, Equatable, Sendable {
        public var position: Point
        public var anchor: Anchor
        public var rotationDegrees: Double
        public var markHeight: Double
        public var fontSize: Double
        public var color: String
        public var backing: Backing?

        public init(from decoder: any Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            position = try container.decode(Point.self, forKey: .position)
            anchor = try container.decode(Anchor.self, forKey: .anchor)
            rotationDegrees = try container.decodeIfPresent(Double.self, forKey: .rotationDegrees) ?? 0
            markHeight = try container.decode(Double.self, forKey: .markHeight)
            fontSize = try container.decode(Double.self, forKey: .fontSize)
            color = try container.decode(String.self, forKey: .color)
            backing = try container.decodeIfPresent(Backing.self, forKey: .backing)
        }
    }

    public var layoutVersion: Int
    public var key: String
    public var canvas: Size
    public var content: Content
    public var caption: Caption
    public var brand: Brand

    /// The layout in `data`, or nil when its version isn't one this build draws or it doesn't
    /// match the schema.
    public static func decodeIfSupported(_ data: Data) -> ShareFrameLayout? {
        guard let probe = try? JSONDecoder().decode(VersionProbe.self, from: data),
              probe.layoutVersion == supportedVersion,
              let layout = try? JSONDecoder().decode(ShareFrameLayout.self, from: data), layout.isDrawable else { return nil }
        return layout
    }

    /// Everything the renderer relies on holds: colours parse, sizes are positive.
    public var isDrawable: Bool {
        layoutVersion == Self.supportedVersion && canvas.width > 0 && canvas.height > 0
            && caption.maxLines >= 1 && caption.minFontSize >= 1 && caption.minFontSize <= caption.maxFontSize
            && caption.maxWidth > 0 && caption.lineHeight > 0 && brand.markHeight > 0 && brand.fontSize > 0
            && ShareFrameColor(hex: caption.inkColor) != nil && ShareFrameColor(hex: brand.color) != nil
            && [caption.backing, brand.backing].allSatisfy { $0.map { ShareFrameColor(hex: $0.color) != nil } ?? true }
    }

    struct VersionProbe: Decodable { let layoutVersion: Int }
}

/// A `#RRGGBB` colour.
public struct ShareFrameColor: Equatable, Sendable {
    public let red: Double
    public let green: Double
    public let blue: Double

    public init?(hex: String) {
        guard hex.count == 7, hex.first == "#", let value = UInt32(hex.dropFirst(), radix: 16) else { return nil }
        red = Double((value >> 16) & 255) / 255
        green = Double((value >> 8) & 255) / 255
        blue = Double(value & 255) / 255
    }
}

/// One published frame version from `GET /share-frames`.
public struct ShareFrameCatalogItem: Decodable, Equatable, Sendable {
    public let key: String
    public let version: Int
    public let title: String
    public let sortOrder: Int
    public let availableFrom: String?
    public let availableUntil: String?
    public let frameUrl: String
    public let thumbnailUrl: String?
    /// Nil when the layout's version isn't one this build draws, or it doesn't decode.
    public let layout: ShareFrameLayout?

    enum CodingKeys: String, CodingKey {
        case key, version, title, sortOrder, availableFrom, availableUntil, frameUrl, thumbnailUrl, layout
    }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        key = try container.decode(String.self, forKey: .key)
        version = try container.decode(Int.self, forKey: .version)
        title = try container.decodeIfPresent(String.self, forKey: .title) ?? key
        sortOrder = try container.decodeIfPresent(Int.self, forKey: .sortOrder) ?? 0
        availableFrom = try container.decodeIfPresent(String.self, forKey: .availableFrom)
        availableUntil = try container.decodeIfPresent(String.self, forKey: .availableUntil)
        frameUrl = try container.decode(String.self, forKey: .frameUrl)
        thumbnailUrl = try container.decodeIfPresent(String.self, forKey: .thumbnailUrl)
        let probe = try? container.decode(ShareFrameLayout.VersionProbe.self, forKey: .layout)
        if probe?.layoutVersion == ShareFrameLayout.supportedVersion,
           let layout = try? container.decode(ShareFrameLayout.self, forKey: .layout), layout.isDrawable {
            self.layout = layout
        } else {
            layout = nil
        }
    }
}

/// The catalog's reply. An item that doesn't decode is left out rather than failing the rest.
public struct ShareFrameCatalogList: Decodable, Sendable {
    public let items: [ShareFrameCatalogItem]

    public init(from decoder: any Decoder) throws {
        struct Lossy: Decodable {
            let item: ShareFrameCatalogItem?
            init(from decoder: any Decoder) throws { item = try? ShareFrameCatalogItem(from: decoder) }
        }
        let container = try decoder.container(keyedBy: CodingKeys.self)
        items = try container.decode([Lossy].self, forKey: .items).compactMap(\.item)
    }

    enum CodingKeys: String, CodingKey { case items }
}

/// A frame the share screen can offer: from the catalog or bundled with the app.
public struct ShareFrame: Equatable, Sendable, Identifiable {
    public let key: String
    public let version: Int
    public let title: String
    public let sortOrder: Int
    /// Local calendar dates, `YYYY-MM-DD`, both inclusive.
    public let availableFrom: String?
    public let availableUntil: String?
    public let layout: ShareFrameLayout
    /// The full frame image: a file in the app for bundled frames, a download otherwise.
    public let imageURL: URL
    public let thumbnailURL: URL?
    public let bundled: Bool

    public var id: String { key }

    public init(key: String, version: Int, title: String, sortOrder: Int, availableFrom: String? = nil, availableUntil: String? = nil,
                layout: ShareFrameLayout, imageURL: URL, thumbnailURL: URL? = nil, bundled: Bool = false) {
        self.key = key; self.version = version; self.title = title; self.sortOrder = sortOrder
        self.availableFrom = availableFrom; self.availableUntil = availableUntil; self.layout = layout
        self.imageURL = imageURL; self.thumbnailURL = thumbnailURL; self.bundled = bundled
    }

    /// The catalog item as a frame, with its URLs resolved against the API, or nil when this
    /// build can't draw it.
    public init?(_ item: ShareFrameCatalogItem, baseURL: URL? = nil) {
        guard let layout = item.layout, ShareFrameRules.isValidKey(item.key), item.version > 0,
              let image = URL(string: item.frameUrl, relativeTo: baseURL)?.absoluteURL else { return nil }
        self.init(key: item.key, version: item.version, title: item.title, sortOrder: item.sortOrder,
                  availableFrom: item.availableFrom, availableUntil: item.availableUntil, layout: layout, imageURL: image,
                  thumbnailURL: item.thumbnailUrl.flatMap { URL(string: $0, relativeTo: baseURL)?.absoluteURL })
    }

    /// Has a date window, whether or not today is in it.
    public var isSeasonal: Bool { availableFrom != nil || availableUntil != nil }

    /// Shown on `day` (`YYYY-MM-DD`, local): inside its window, or it has none. A window date that
    /// isn't a calendar date hides the frame rather than guessing.
    public func isAvailable(on day: String) -> Bool {
        if let from = availableFrom {
            guard ShareFrameRules.isLocalDay(from), from <= day else { return false }
        }
        if let until = availableUntil {
            guard ShareFrameRules.isLocalDay(until), day <= until else { return false }
        }
        return true
    }
}

/// The frames to show, in order, and the one to start on.
public struct ShareFrameLineup: Equatable, Sendable {
    public let frames: [ShareFrame]
    public let preselected: ShareFrame?

    public init(frames: [ShareFrame], preselected: ShareFrame?) {
        self.frames = frames; self.preselected = preselected
    }
}

public enum ShareFrameRules {
    /// Selected when no seasonal frame is in its window.
    public static let defaultKey = "polaroid"

    /// The frames to show on `day`, in order, with the one to preselect:
    /// - one version per key, the highest;
    /// - only frames whose window contains `day`, or that have none;
    /// - seasonal frames first, then the rest, each by `sortOrder`;
    /// - preselect the first seasonal frame, else Polaroid, else the first.
    public static func lineup(_ frames: [ShareFrame], on day: String) -> ShareFrameLineup {
        var newest: [String: ShareFrame] = [:]
        for frame in frames where frame.version > newest[frame.key]?.version ?? 0 { newest[frame.key] = frame }
        let shown = newest.values.filter { $0.isAvailable(on: day) }
        let ordered = shown.sorted { a, b in
            if a.isSeasonal != b.isSeasonal { return a.isSeasonal }
            if a.sortOrder != b.sortOrder { return a.sortOrder < b.sortOrder }
            return a.key < b.key
        }
        let preselected = ordered.first(where: \.isSeasonal) ?? ordered.first { $0.key == defaultKey } ?? ordered.first
        return ShareFrameLineup(frames: ordered, preselected: preselected)
    }

    /// Lowercase and hyphenated, as `valentines` or `spring-kittens`. Also keeps a key safe as a
    /// folder name in the cache.
    public static func isValidKey(_ key: String) -> Bool {
        guard !key.isEmpty, key.count <= 64, key.first != "-", key.last != "-", !key.contains("--") else { return false }
        return key.allSatisfy { ("a"..."z").contains($0) || ("0"..."9").contains($0) || $0 == "-" }
    }

    static func isLocalDay(_ value: String) -> Bool {
        let parts = value.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0].count == 4, parts[1].count == 2, parts[2].count == 2,
              parts.allSatisfy({ $0.allSatisfy(\.isASCII) && $0.allSatisfy(\.isNumber) }),
              let month = Int(parts[1]), let date = Int(parts[2]) else { return false }
        return (1...12).contains(month) && (1...31).contains(date)
    }
}

/// The caption the share screen starts with.
public enum ShareCaption {
    public static let maxLength = 60
    public static let fallbackName = "my love"

    /// With the partner's name, or "my love" when there is none or it would run past 60 characters.
    public static func defaultText(partnerName: String?) -> String {
        let name = partnerName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let named = text(name: name)
        return name.isEmpty || named.count > maxLength ? text(name: fallbackName) : named
    }

    private static func text(name: String) -> String { "Look what \(name) drew on my lock screen" }

    /// At most 60 characters.
    public static func limited(_ text: String) -> String {
        text.count > maxLength ? String(text.prefix(maxLength)) : text
    }
}
