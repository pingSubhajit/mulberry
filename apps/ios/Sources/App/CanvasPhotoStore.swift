import Foundation
import ImageIO
import UIKit
import UniformTypeIdentifiers

/// Canvas photos, kept on disk per user once downloaded or taken here, and decoded for the canvas
/// at screen size. A photo is never changed once uploaded, so its id names its file for good.
actor CanvasPhotoStore {
    static let shared = CanvasPhotoStore()
    /// The canvas's decoded size: enough for a photo filling an iPad's canvas.
    static let canvasPixels = 1600

    private let root = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("Mulberry/CanvasPhotos", isDirectory: true)
    private var downloads: [String: Task<URL?, Never>] = [:]
    /// Advances when an account's photos are removed, so a download or upload that started before
    /// never writes a photo back afterwards.
    private var generation = 0
    /// NSCache is thread-safe, though not marked Sendable, so the canvas can read it synchronously.
    nonisolated(unsafe) private let images = NSCache<NSString, UIImage>()

    private init() { images.totalCostLimit = 160 * 1024 * 1024 }

    /// The decoded photo if it is already in memory, so the canvas can draw it in its first frame.
    nonisolated func cachedImage(_ photoId: String, user: String) -> UIImage? {
        images.object(forKey: Self.key(photoId, user: user))
    }

    /// The decoded photo, downloaded first if this device has never had it. Nil while offline.
    func image(_ photoId: String, user: String) async -> UIImage? {
        if let cached = cachedImage(photoId, user: user) { return cached }
        let started = generation
        guard let file = await file(photoId, user: user) else { return nil }
        let key = Self.key(photoId, user: user)
        let decoded = await Task.detached(priority: .userInitiated) { Self.decode(file) }.value
        guard let (image, cost) = decoded, generation == started else { return nil }
        images.setObject(image, forKey: key, cost: cost)
        return image
    }

    /// Uploads a photo taken or picked here and keeps it, so the canvas shows it without a download.
    func upload(_ data: Data, contentType: String, user: String) async throws -> CanvasPhotoUpload {
        let started = generation
        let api = try await MainActor.run { try AppRuntime.api() }
        let result = try await api.uploadCanvasPhoto(data, contentType: contentType)
        if generation == started, let destination = destination(result.photoId, user: user) {
            try? FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? data.write(to: destination, options: .atomic)
        }
        return result
    }

    /// Removes everything kept for the account, on disk and in memory, on sign-out or deletion.
    func removeAll(user: String) {
        generation += 1
        downloads.values.forEach { $0.cancel() }
        downloads = [:]
        images.removeAllObjects()
        guard let folder = folder(user) else { return }
        try? FileManager.default.removeItem(at: folder)
    }

    private func file(_ photoId: String, user: String) async -> URL? {
        guard let destination = destination(photoId, user: user) else { return nil }
        if FileManager.default.fileExists(atPath: destination.path) { return destination }
        if let pending = downloads[photoId] { return await pending.value }
        let started = generation
        let task = Task<URL?, Never> {
            guard let api = try? await MainActor.run(body: { try AppRuntime.api() }),
                  let data = try? await api.canvasPhoto(photoId),
                  !Task.isCancelled, self.generation == started else { return nil }
            try? FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
            guard (try? data.write(to: destination, options: .atomic)) != nil else { return nil }
            return destination
        }
        downloads[photoId] = task
        let result = await task.value
        if generation == started { downloads[photoId] = nil }
        return result
    }

    private func folder(_ user: String) -> URL? {
        guard !user.isEmpty, !user.contains("/"), user != ".." else { return nil }
        return root.appendingPathComponent(user, isDirectory: true)
    }

    private func destination(_ photoId: String, user: String) -> URL? {
        guard UUID(uuidString: photoId) != nil else { return nil }
        return folder(user)?.appendingPathComponent(photoId.lowercased())
    }

    private nonisolated static func key(_ photoId: String, user: String) -> NSString { "\(user)|\(photoId.lowercased())" as NSString }

    private nonisolated static func decode(_ file: URL) -> (UIImage, Int)? {
        guard let source = CGImageSourceCreateWithURL(file as CFURL, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                  kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
                  kCGImageSourceShouldCacheImmediately: true, kCGImageSourceThumbnailMaxPixelSize: canvasPixels
              ] as CFDictionary) else { return nil }
        return (UIImage(cgImage: image), image.bytesPerRow * image.height)
    }

    /// Puts a photo taken here in memory under its new id, so the canvas never waits to decode it.
    nonisolated func remember(_ image: UIImage, photoId: String, user: String) {
        let cost = Int(image.size.width * image.scale * image.size.height * image.scale * 4)
        images.setObject(image, forKey: Self.key(photoId, user: user), cost: cost)
    }
}
