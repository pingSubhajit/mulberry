import AppKit
import Foundation
import ImageIO

/// Canvas photos on disk, per account, as the iPhone and iPad keep them. A photo never changes once
/// uploaded, so its id names its file for good.
enum CanvasPhotoFiles {
    /// The canvas's decoded size: enough for a photo filling the window's canvas, as on iPad.
    static let canvasPixels = 1600

    private static var root: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Mulberry/CanvasPhotos", isDirectory: true)
    }

    /// Where a photo is kept, or nil for anything that isn't a real photo id or account.
    static func file(_ photoId: String, user: String) -> URL? {
        guard UUID(uuidString: photoId) != nil, !user.isEmpty, !user.contains("/"), user != ".." else { return nil }
        return root.appendingPathComponent(user, isDirectory: true).appendingPathComponent(photoId.lowercased())
    }

    /// Removes every photo kept for the account.
    static func removeAll(user: String) {
        guard !user.isEmpty, !user.contains("/"), user != ".." else { return }
        try? FileManager.default.removeItem(at: root.appendingPathComponent(user, isDirectory: true))
    }

    static func save(_ data: Data, to file: URL) {
        try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: file, options: .atomic)
    }

    /// Decoded upright, at most `canvasPixels` on its long edge.
    static func decode(_ file: URL) -> NSImage? {
        guard let source = CGImageSourceCreateWithURL(file as CFURL, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                  kCGImageSourceCreateThumbnailFromImageAlways: true,
                  kCGImageSourceCreateThumbnailWithTransform: true,
                  kCGImageSourceShouldCacheImmediately: true,
                  kCGImageSourceThumbnailMaxPixelSize: canvasPixels
              ] as CFDictionary) else { return nil }
        return NSImage(cgImage: image, size: NSSize(width: image.width, height: image.height))
    }
}
