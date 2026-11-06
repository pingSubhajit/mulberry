import CoreText
import Foundation

/// The share image's three typefaces, read straight from their files, so drawing never depends on
/// which fonts a process has registered: the app, the Mac app and the tests all draw alike.
public struct ShareFrameFonts: @unchecked Sendable {
    /// Captions: Permanent Marker.
    public let caption: CTFontDescriptor
    /// The date line and the brand line: Poppins Medium.
    public let medium: CTFontDescriptor
    /// The clock: Poppins SemiBold.
    public let semiBold: CTFontDescriptor

    public static let captionFile = "permanent_marker_regular.ttf"
    public static let mediumFile = "poppins_medium.ttf"
    public static let semiBoldFile = "poppins_semibold.ttf"

    public enum LoadError: Error, Equatable {
        case missing(String)
    }

    public init(caption: URL, medium: URL, semiBold: URL) throws {
        self.caption = try Self.descriptor(caption)
        self.medium = try Self.descriptor(medium)
        self.semiBold = try Self.descriptor(semiBold)
    }

    /// The fonts in `directory`, under their file names.
    public init(directory: URL) throws {
        try self.init(caption: directory.appendingPathComponent(Self.captionFile),
                      medium: directory.appendingPathComponent(Self.mediumFile),
                      semiBold: directory.appendingPathComponent(Self.semiBoldFile))
    }

    /// The fonts in an app bundle: in its `Fonts` folder, or at its top level.
    public init(bundle: Bundle) throws {
        func url(_ file: String) throws -> URL {
            let name = (file as NSString).deletingPathExtension
            guard let url = bundle.url(forResource: name, withExtension: "ttf", subdirectory: "Fonts")
                    ?? bundle.url(forResource: name, withExtension: "ttf") else { throw LoadError.missing(file) }
            return url
        }
        try self.init(caption: url(Self.captionFile), medium: url(Self.mediumFile), semiBold: url(Self.semiBoldFile))
    }

    func font(_ descriptor: CTFontDescriptor, size: CGFloat) -> CTFont {
        CTFontCreateWithFontDescriptor(descriptor, size, nil)
    }

    private static func descriptor(_ url: URL) throws -> CTFontDescriptor {
        guard let data = try? Data(contentsOf: url),
              let descriptors = CTFontManagerCreateFontDescriptorsFromData(data as CFData) as? [CTFontDescriptor],
              let first = descriptors.first else { throw LoadError.missing(url.lastPathComponent) }
        return first
    }
}
