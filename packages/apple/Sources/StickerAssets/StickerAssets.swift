import Combine
import Foundation
import ImageIO
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif
#if canImport(ProductCore)
import ProductCore
#endif
#if canImport(CanvasDrawing)
import CanvasDrawing
#endif

/// How the sticker store and catalog reach the backend. The apps pass a closure so the
/// store never holds a stale client after sign-out.
public typealias StickerBackend = @Sendable () async throws -> BackendClient

/// Android's StickerAssetStore: artwork is downloaded once per user and pack
/// version, refreshed through the API when a signed URL has expired, and kept
/// within a 250 MB budget by evicting the least recently used pack versions.
public actor StickerAssetStore {
    public static let coverID = "__cover"
    private static let budgetBytes: Int64 = 250 * 1024 * 1024

    private let root: URL
    private let session: URLSession
    private var inFlight: [URL: Task<URL?, Never>] = [:]
    private var touched: [URL: Date] = [:]
    private let images = NSCache<NSString, CanvasImage>()
    private let api: StickerBackend

    public init(api: @escaping StickerBackend,
                root: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                    .appendingPathComponent("Mulberry/Stickers", isDirectory: true),
                session: URLSession = .shared) {
        self.api = api; self.root = root; self.session = session
        images.totalCostLimit = 96 * 1024 * 1024
    }

    /// A decoded image, downsampled when `maxPixelSize` is set. Returns nil while offline
    /// if the artwork was never downloaded.
    public func image(_ sticker: StickerReference, variant: StickerAssetVariant, user: String, hint: String? = nil, maxPixelSize: Int? = nil) async -> CanvasImage? {
        let key = "\(user)|\(sticker.packKey)|\(sticker.packVersion)|\(sticker.stickerId)|\(variant.rawValue)|\(maxPixelSize ?? 0)" as NSString
        if let cached = images.object(forKey: key) { return cached }
        guard let file = await file(sticker, variant: variant, user: user, hint: hint),
              let source = CGImageSourceCreateWithURL(file as CFURL, nil) else { return nil }
        let cgImage: CGImage?
        if let maxPixelSize {
            cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceShouldCacheImmediately: true, kCGImageSourceThumbnailMaxPixelSize: maxPixelSize
            ] as CFDictionary)
        } else {
            cgImage = CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary)
        }
        guard let cgImage else { return nil }
        #if canImport(UIKit)
        let image = UIImage(cgImage: cgImage)
        #else
        let image = NSImage(cgImage: cgImage, size: CGSize(width: cgImage.width, height: cgImage.height))
        #endif
        images.setObject(image, forKey: key, cost: cgImage.bytesPerRow * cgImage.height)
        return image
    }

    public func file(_ sticker: StickerReference, variant: StickerAssetVariant, user: String, hint: String? = nil) async -> URL? {
        guard let destination = destination(sticker, variant: variant, user: user) else { return nil }
        if FileManager.default.fileExists(atPath: destination.path) {
            touch(destination.deletingLastPathComponent())
            return destination
        }
        if let pending = inFlight[destination] { return await pending.value }
        let task = Task { await self.download(sticker, variant: variant, user: user, hint: hint, to: destination) }
        inFlight[destination] = task
        let result = await task.value
        inFlight[destination] = nil
        return result
    }

    public func removeAll(user: String) {
        guard let folder = userFolder(user) else { return }
        try? FileManager.default.removeItem(at: folder)
        images.removeAllObjects()
    }

    private func download(_ sticker: StickerReference, variant: StickerAssetVariant, user: String, hint: String?, to destination: URL) async -> URL? {
        if let hint, let url = URL(string: hint), await fetch(url, to: destination) { return finish(destination, user: user) }
        // Catalog URLs are signed and expire; ask the API for a fresh one.
        guard let fresh = await freshURL(sticker, variant: variant), fresh.absoluteString != hint,
              await fetch(fresh, to: destination) else { return nil }
        return finish(destination, user: user)
    }

    private func finish(_ destination: URL, user: String) -> URL {
        touch(destination.deletingLastPathComponent(), force: true)
        enforceBudget(user: user)
        return destination
    }

    private func fetch(_ url: URL, to destination: URL) async -> Bool {
        do {
            let (temporary, response) = try await session.download(from: url)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                try? FileManager.default.removeItem(at: temporary)
                return false
            }
            let folder = destination.deletingLastPathComponent()
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            var excluded = URLResourceValues(); excluded.isExcludedFromBackup = true
            var mutableRoot = root; try? mutableRoot.setResourceValues(excluded)
            try? FileManager.default.removeItem(at: destination)
            try FileManager.default.moveItem(at: temporary, to: destination)
            return true
        } catch {
            return false
        }
    }

    private func freshURL(_ sticker: StickerReference, variant: StickerAssetVariant) async -> URL? {
        do {
            let api = try await self.api()
            let value: String
            if sticker.stickerId == Self.coverID {
                let pack = try await api.stickerPack(sticker.packKey, version: sticker.packVersion)
                value = variant == .thumbnail ? pack.coverThumbnailUrl : pack.coverFullUrl
            } else {
                value = try await api.stickerAssetURL(sticker, variant: variant).url
            }
            return URL(string: value)
        } catch {
            return nil
        }
    }

    private func userFolder(_ user: String) -> URL? {
        guard UUID(uuidString: user) != nil else { return nil }
        return root.appendingPathComponent(user.lowercased(), isDirectory: true)
    }

    private func destination(_ sticker: StickerReference, variant: StickerAssetVariant, user: String) -> URL? {
        let pack = sticker.packKey.trimmingCharacters(in: .whitespaces).lowercased()
        let id = sticker.stickerId.trimmingCharacters(in: .whitespaces)
        let safe = CharacterSet.alphanumerics.union(.init(charactersIn: "-_"))
        guard let folder = userFolder(user), sticker.packVersion > 0, !pack.isEmpty, !id.isEmpty,
              pack.unicodeScalars.allSatisfy(safe.contains),
              let encoded = id.addingPercentEncoding(withAllowedCharacters: safe) else { return nil }
        return folder.appendingPathComponent("\(pack)/\(sticker.packVersion)/\(encoded)-\(variant.rawValue).png")
    }

    /// Records use on the pack-version folder; eviction removes the oldest first.
    private func touch(_ folder: URL, force: Bool = false) {
        if !force, let last = touched[folder], Date().timeIntervalSince(last) < 60 { return }
        touched[folder] = .now
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: folder.path)
    }

    private func enforceBudget(user: String) {
        guard let folder = userFolder(user) else { return }
        let manager = FileManager.default
        let versions = ((try? manager.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? []).flatMap { pack in
            (try? manager.contentsOfDirectory(at: pack, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        }
        let sized = versions.map { version -> (url: URL, bytes: Int64, used: Date) in
            let files = (try? manager.contentsOfDirectory(at: version, includingPropertiesForKeys: [.fileSizeKey])) ?? []
            let bytes = files.reduce(Int64(0)) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
            let used = (try? version.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            return (version, bytes, used)
        }
        var total = sized.reduce(0) { $0 + $1.bytes }
        for version in sized.sorted(by: { $0.used < $1.used }) where total > Self.budgetBytes {
            try? manager.removeItem(at: version.url)
            total -= version.bytes
        }
    }
}

/// Android keeps the pack list for six hours and pack details indefinitely,
/// because a published pack version never changes.
@MainActor
public final class StickerCatalog: ObservableObject {
    @Published public private(set) var packs: [StickerPackSummary] = []
    @Published public private(set) var selected: StickerPackDetail?
    @Published public private(set) var loading = false
    @Published public private(set) var errorMessage: String?
    @Published public private(set) var lastUsed: StickerReference?
    public let user: String?
    private let api: StickerBackend
    private let store: StickerAssetStore
    private let directory: URL
    private var lastAttempt: Date?
    private var loadTask: Task<Void, Never>?
    private static let revalidateInterval: TimeInterval = 6 * 60 * 60
    private static let prefetchCount = 18

    private struct CachedPacks: Codable { let fetchedAt: Date; let packs: [StickerPackSummary] }

    /// `directory` holds each account's cached pack list and pack details.
    public init(user: String?, api: @escaping StickerBackend, store: StickerAssetStore, directory: URL) {
        self.user = user
        self.api = api
        self.store = store
        self.directory = directory
        if let user, let data = UserDefaults.standard.data(forKey: Self.lastUsedKey(user)) {
            lastUsed = try? JSONDecoder().decode(StickerReference.self, from: data)
        }
    }

    /// The sticker a new placement starts with: the last one used, else the
    /// first sticker of the open pack.
    public var defaultSticker: StickerReference? {
        if let lastUsed { return lastUsed }
        guard let selected, let first = selected.stickers.min(by: { $0.sortOrder < $1.sortOrder }) else { return nil }
        return .init(packKey: selected.packKey, packVersion: selected.packVersion, stickerId: first.stickerId)
    }

    public func load(force: Bool = false) {
        guard user != nil, loadTask == nil else { return }
        if !force, !packs.isEmpty, let lastAttempt, Date().timeIntervalSince(lastAttempt) < 10 { return }
        loadTask = Task { await refresh(force: force); loadTask = nil }
    }

    public func select(_ pack: StickerPackSummary) {
        Task { await open(packKey: pack.packKey, version: pack.packVersion) }
    }

    public func use(_ sticker: StickerReference) {
        lastUsed = sticker
        guard let user, let data = try? JSONEncoder().encode(sticker) else { return }
        UserDefaults.standard.set(data, forKey: Self.lastUsedKey(user))
    }

    public func thumbnailHint(for sticker: StickerReference) -> String? {
        guard let selected, selected.packKey == sticker.packKey, selected.packVersion == sticker.packVersion else { return nil }
        return selected.stickers.first { $0.stickerId == sticker.stickerId }?.thumbnailUrl
    }

    private func refresh(force: Bool) async {
        guard let user else { return }
        lastAttempt = .now
        let cached = read(CachedPacks.self, "packs.json")
        if packs.isEmpty, let cached { packs = cached.packs }
        let stale = force || cached.map { Date().timeIntervalSince($0.fetchedAt) > Self.revalidateInterval } ?? true
        if stale {
            if packs.isEmpty { loading = true; errorMessage = nil }
            do {
                let fresh = try await api().stickerPacks()
                guard user == self.user else { return }
                packs = fresh; errorMessage = nil
                write(CachedPacks(fetchedAt: .now, packs: fresh), "packs.json")
            } catch {
                if packs.isEmpty { errorMessage = "Stickers couldn't load. Check your connection." }
            }
            loading = false
        }
        if selected == nil {
            let preferred = lastUsed.flatMap { used in packs.first { $0.packKey == used.packKey && $0.packVersion == used.packVersion } }
            if let pack = preferred ?? packs.first { await open(packKey: pack.packKey, version: pack.packVersion) }
        }
    }

    private func open(packKey: String, version: Int) async {
        guard let user else { return }
        let name = "pack-\(packKey)-\(version).json"
        if let cached = read(StickerPackDetail.self, name) {
            selected = cached
            prefetch(cached, user: user)
            return
        }
        loading = true; errorMessage = nil
        defer { loading = false }
        do {
            let detail = try await api().stickerPack(packKey, version: version)
            selected = detail
            write(detail, name)
            prefetch(detail, user: user)
        } catch {
            errorMessage = "This pack couldn't load. Check your connection."
        }
    }

    private func prefetch(_ pack: StickerPackDetail, user: String) {
        let stickers = pack.stickers.sorted { $0.sortOrder < $1.sortOrder }.prefix(Self.prefetchCount)
        let store = store
        Task.detached(priority: .utility) {
            for sticker in stickers {
                _ = await store.file(.init(packKey: pack.packKey, packVersion: pack.packVersion, stickerId: sticker.stickerId),
                                                        variant: .thumbnail, user: user, hint: sticker.thumbnailUrl)
            }
        }
    }

    private var folder: URL? {
        guard let user, UUID(uuidString: user) != nil else { return nil }
        return directory.appendingPathComponent(user.lowercased(), isDirectory: true)
    }

    private func read<Value: Decodable>(_ type: Value.Type, _ name: String) -> Value? {
        guard let url = folder?.appendingPathComponent(name), let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    private func write<Value: Encodable>(_ value: Value, _ name: String) {
        guard let folder, let data = try? JSONEncoder().encode(value) else { return }
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try? data.write(to: folder.appendingPathComponent(name), options: .atomic)
    }

    private static func lastUsedKey(_ user: String) -> String { "stickerLastUsed.\(user)" }
}
