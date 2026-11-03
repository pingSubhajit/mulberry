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
/// version and kept within a 250 MB budget by evicting the least recently used pack
/// versions. Signed links learned from pack lists and details are reused while they
/// last; an expired one is replaced by refreshing the whole pack once, shared by
/// every sticker in it, instead of asking the API sticker by sticker.
public actor StickerAssetStore {
    public static let coverID = "__cover"
    /// The pickers' grid artwork size, which the catalog decodes ahead of time.
    public static let thumbnailPixels = 256
    /// The pack strip's cover size.
    public static let coverPixels = 168
    /// How long a signed link from the catalog is trusted: under the server's lifetime,
    /// including an older server's ten minutes.
    public static let linkLifetime: TimeInterval = 9 * 60
    private static let budgetBytes: Int64 = 250 * 1024 * 1024
    /// A pack's links are refreshed at most this often, so a pack that can't be fetched
    /// isn't asked for again by each of its stickers.
    private static let refreshInterval: TimeInterval = 30

    private struct Link { let url: URL; let until: Date }

    private let root: URL
    private let session: URLSession
    private var inFlight: [URL: Task<URL?, Never>] = [:]
    private var touched: [URL: Date] = [:]
    private var links: [URL: Link] = [:]
    private var packLoads: [String: Task<StickerPackDetail?, Never>] = [:]
    private var listLoads: [String: Task<[StickerPackSummary]?, Never>] = [:]
    private var refreshed: [String: Date] = [:]
    private var budgetPending: Set<String> = []
    /// NSCache is thread-safe, though not marked Sendable, so the canvas can read it synchronously.
    nonisolated(unsafe) private let images = NSCache<NSString, CanvasImage>()
    private let api: StickerBackend

    public init(api: @escaping StickerBackend,
                root: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                    .appendingPathComponent("Mulberry/Stickers", isDirectory: true),
                session: URLSession = .shared) {
        self.api = api; self.root = root; self.session = session
        images.totalCostLimit = 96 * 1024 * 1024
    }

    // MARK: Images

    /// A decoded image, downsampled when `maxPixelSize` is set. Returns nil while offline
    /// if the artwork was never downloaded. Decoding runs off the store, in parallel, so
    /// one large image never holds up the thumbnails behind it.
    @concurrent
    public nonisolated func image(_ sticker: StickerReference, variant: StickerAssetVariant, user: String, hint: String? = nil, maxPixelSize: Int? = nil) async -> CanvasImage? {
        let key = Self.key(sticker, variant: variant, user: user, maxPixelSize: maxPixelSize)
        if let cached = images.object(forKey: key) { return cached }
        guard let file = await file(sticker, variant: variant, user: user, hint: hint) else { return nil }
        if let cached = images.object(forKey: key) { return cached }
        guard let (image, cost) = Self.decode(file, maxPixelSize: maxPixelSize) else { return nil }
        images.setObject(image, forKey: key, cost: cost)
        return image
    }

    /// The decoded image if it is already in memory, without waiting, so a canvas or picker
    /// can draw it in its first frame.
    public nonisolated func cachedImage(_ sticker: StickerReference, variant: StickerAssetVariant, user: String, maxPixelSize: Int? = nil) -> CanvasImage? {
        images.object(forKey: Self.key(sticker, variant: variant, user: user, maxPixelSize: maxPixelSize))
    }

    /// Decodes the drawing's stickers ahead of the canvas, so they are in memory when it opens.
    public func prewarm(_ stickers: Set<StickerReference>, variant: StickerAssetVariant, user: String, maxPixelSize: Int? = nil) async {
        for sticker in stickers where cachedImage(sticker, variant: variant, user: user, maxPixelSize: maxPixelSize) == nil {
            _ = await image(sticker, variant: variant, user: user, maxPixelSize: maxPixelSize)
        }
    }

    private nonisolated static func key(_ sticker: StickerReference, variant: StickerAssetVariant, user: String, maxPixelSize: Int?) -> NSString {
        "\(user)|\(sticker.packKey)|\(sticker.packVersion)|\(sticker.stickerId)|\(variant.rawValue)|\(maxPixelSize ?? 0)" as NSString
    }

    private nonisolated static func decode(_ file: URL, maxPixelSize: Int?) -> (CanvasImage, Int)? {
        guard let source = CGImageSourceCreateWithURL(file as CFURL, nil) else { return nil }
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
        return (image, cgImage.bytesPerRow * cgImage.height)
    }

    // MARK: Files

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
        links = links.filter { !$0.key.path.hasPrefix(folder.path) }
    }

    private func download(_ sticker: StickerReference, variant: StickerAssetVariant, user: String, hint: String?, to destination: URL) async -> URL? {
        // A refresh of this pack's links already under way answers before any older link is tried.
        await pendingRefresh(for: sticker, user: user)
        var tried: Set<URL> = []
        if await attempt(link(for: destination) ?? hint.flatMap(URL.init(string:)), to: destination, tried: &tried) {
            return finish(destination, user: user)
        }
        // The link expired or failed: refresh the pack's links once for all its stickers.
        await refreshLinks(for: sticker, user: user)
        if await attempt(link(for: destination), to: destination, tried: &tried) { return finish(destination, user: user) }
        // Packs no longer published still serve their artwork by sticker, for drawings that use them.
        if sticker.stickerId != Self.coverID, await attempt(await freshURL(sticker, variant: variant), to: destination, tried: &tried) {
            return finish(destination, user: user)
        }
        return nil
    }

    /// Downloads from `url` unless it is missing or was already tried.
    private func attempt(_ url: URL?, to destination: URL, tried: inout Set<URL>) async -> Bool {
        guard let url, tried.insert(url).inserted else { return false }
        return await fetch(url, to: destination)
    }

    private func finish(_ destination: URL, user: String) -> URL {
        touch(destination.deletingLastPathComponent(), force: true)
        scheduleBudget(user: user)
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
        guard let value = try? await api().stickerAssetURL(sticker, variant: variant).url else { return nil }
        return URL(string: value)
    }

    // MARK: Catalog

    /// The published packs. Callers asking at once share one request, and the covers'
    /// links are kept for downloads.
    public func packList(user: String) async -> [StickerPackSummary]? {
        if let pending = listLoads[user] { return await pending.value }
        let task = Task<[StickerPackSummary]?, Never> {
            guard let packs = try? await self.api().stickerPacks() else { return nil }
            self.remember(packs, user: user, fetchedAt: .now)
            return packs
        }
        listLoads[user] = task
        let result = await task.value
        listLoads[user] = nil
        refreshed[Self.listRefreshKey(user)] = .now
        return result
    }

    /// A pack's details. Callers asking at once share one request, and its links are kept
    /// for downloads.
    public func packDetail(packKey: String, version: Int, user: String) async -> StickerPackDetail? {
        let key = Self.packRefreshKey(packKey: packKey, version: version, user: user)
        if let pending = packLoads[key] { return await pending.value }
        let task = Task<StickerPackDetail?, Never> {
            guard let detail = try? await self.api().stickerPack(packKey, version: version) else { return nil }
            self.remember(detail, user: user, fetchedAt: .now)
            return detail
        }
        packLoads[key] = task
        let result = await task.value
        packLoads[key] = nil
        refreshed[key] = .now
        return result
    }

    /// Keeps the still-valid links of a pack list fetched at `fetchedAt`, such as one read from disk.
    public func remember(_ packs: [StickerPackSummary], user: String, fetchedAt: Date) {
        let until = fetchedAt.addingTimeInterval(Self.linkLifetime)
        guard until > .now else { return }
        for pack in packs {
            rememberCover(packKey: pack.packKey, version: pack.packVersion, thumbnail: pack.coverThumbnailUrl,
                          full: pack.coverFullUrl, user: user, until: until)
        }
    }

    /// Keeps the still-valid links of a pack's details fetched at `fetchedAt`.
    public func remember(_ detail: StickerPackDetail, user: String, fetchedAt: Date) {
        let until = fetchedAt.addingTimeInterval(Self.linkLifetime)
        guard until > .now else { return }
        rememberCover(packKey: detail.packKey, version: detail.packVersion, thumbnail: detail.coverThumbnailUrl,
                      full: detail.coverFullUrl, user: user, until: until)
        for sticker in detail.stickers {
            let reference = StickerReference(packKey: detail.packKey, packVersion: detail.packVersion, stickerId: sticker.stickerId)
            remember(sticker.thumbnailUrl, for: reference, variant: .thumbnail, user: user, until: until)
            remember(sticker.fullUrl, for: reference, variant: .full, user: user, until: until)
        }
    }

    private func rememberCover(packKey: String, version: Int, thumbnail: String, full: String, user: String, until: Date) {
        let cover = StickerReference(packKey: packKey, packVersion: version, stickerId: Self.coverID)
        remember(thumbnail, for: cover, variant: .thumbnail, user: user, until: until)
        remember(full, for: cover, variant: .full, user: user, until: until)
    }

    private func remember(_ value: String, for sticker: StickerReference, variant: StickerAssetVariant, user: String, until: Date) {
        guard let url = URL(string: value), let destination = destination(sticker, variant: variant, user: user) else { return }
        links[destination] = Link(url: url, until: until)
    }

    private func link(for destination: URL) -> URL? {
        guard let link = links[destination], link.until > .now else { return nil }
        return link.url
    }

    private func pendingRefresh(for sticker: StickerReference, user: String) async {
        if sticker.stickerId == Self.coverID, let pending = listLoads[user] { _ = await pending.value }
        let key = Self.packRefreshKey(packKey: sticker.packKey, version: sticker.packVersion, user: user)
        if let pending = packLoads[key] { _ = await pending.value }
    }

    /// Covers come with the pack list, stickers with their pack's details; a cover whose pack
    /// left the list still comes with the pack's details.
    private func refreshLinks(for sticker: StickerReference, user: String) async {
        let destination = destination(sticker, variant: .thumbnail, user: user)
        if sticker.stickerId == Self.coverID, recentlyIdle(Self.listRefreshKey(user)) {
            _ = await packList(user: user)
            if let destination, link(for: destination) != nil { return }
        }
        if recentlyIdle(Self.packRefreshKey(packKey: sticker.packKey, version: sticker.packVersion, user: user)) {
            _ = await packDetail(packKey: sticker.packKey, version: sticker.packVersion, user: user)
        }
    }

    /// Whether a refresh may start now: none is running or ran in the last half minute.
    /// A running one is awaited first.
    private func recentlyIdle(_ key: String) -> Bool {
        guard let last = refreshed[key] else { return true }
        return Date().timeIntervalSince(last) >= Self.refreshInterval
    }

    private static func listRefreshKey(_ user: String) -> String { "list|\(user)" }
    private static func packRefreshKey(packKey: String, version: Int, user: String) -> String { "pack|\(user)|\(packKey)|\(version)" }

    // MARK: Disk

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

    /// Sizing every pack on disk is slow, so it runs once downloads settle, off the store.
    private func scheduleBudget(user: String) {
        guard let folder = userFolder(user), budgetPending.insert(user).inserted else { return }
        Task.detached(priority: .background) { [weak self] in
            try? await Task.sleep(for: .seconds(5))
            await self?.budgetStarted(user)
            Self.enforceBudget(folder)
        }
    }

    private func budgetStarted(_ user: String) { budgetPending.remove(user) }

    private nonisolated static func enforceBudget(_ folder: URL) {
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
        for version in sized.sorted(by: { $0.used < $1.used }) where total > budgetBytes {
            try? manager.removeItem(at: version.url)
            total -= version.bytes
        }
    }
}

/// Android keeps the pack list for six hours and pack details indefinitely,
/// because a published pack version never changes.
///
/// The picker reads everything from here. A tapped pack is selected at once; its details
/// come from memory, then disk, then the API, and only the selected pack's are shown, so
/// answers arriving out of order never switch the picker to another pack.
@MainActor
public final class StickerCatalog: ObservableObject {
    @Published public private(set) var packs: [StickerPackSummary] = []
    /// The pack chosen in the picker, set the moment it is tapped.
    @Published public private(set) var selectedPackID: String?
    @Published public private(set) var lastUsed: StickerReference?
    @Published private var details: [String: StickerPackDetail] = [:]
    @Published private var pending: Set<String> = []
    @Published private var failed: Set<String> = []
    @Published private var listLoading = false
    @Published private var listFailed = false
    public let user: String?
    private let store: StickerAssetStore
    private let directory: URL
    private var orderedStickers: [String: [StickerSummary]] = [:]
    private var fetchedAt: [String: Date] = [:]
    private var lastAttempt: Date?
    private var loadTask: Task<Void, Never>?
    private var detailTasks: [String: Task<Void, Never>] = [:]
    private var prefetchTask: Task<Void, Never>?
    private static let revalidateInterval: TimeInterval = 6 * 60 * 60
    private static let prefetchCount = 18
    private nonisolated static let prefetchWidth = 3

    private struct CachedPacks: Codable { let fetchedAt: Date; let packs: [StickerPackSummary] }

    /// `directory` holds each account's cached pack list and pack details.
    public init(user: String?, store: StickerAssetStore, directory: URL) {
        self.user = user
        self.store = store
        self.directory = directory
        if let user, let data = UserDefaults.standard.data(forKey: Self.lastUsedKey(user)) {
            lastUsed = try? JSONDecoder().decode(StickerReference.self, from: data)
        }
    }

    /// The selected pack's details, once they are here.
    public var selected: StickerPackDetail? { selectedPackID.flatMap { details[$0] } }

    /// The selected pack's name, known from the pack list before its details arrive.
    public var selectedTitle: String? {
        selected?.title ?? packs.first { $0.id == selectedPackID }?.title
    }

    /// The selected pack's stickers in their published order.
    public var selectedStickers: [StickerSummary] { selectedPackID.flatMap { orderedStickers[$0] } ?? [] }

    /// Whether the person is waiting on the pack list or the selected pack.
    public var loading: Bool {
        listLoading || (selected == nil && selectedPackID.map(pending.contains) == true)
    }

    /// Whether the picker should hold placeholder tiles where the grid will be.
    public var awaitingPack: Bool { selected == nil && errorMessage == nil && (loading || selectedPackID != nil) }

    public var errorMessage: String? {
        if let id = selectedPackID, details[id] == nil, failed.contains(id), !pending.contains(id) {
            return "This pack couldn't load. Check your connection."
        }
        return packs.isEmpty && listFailed ? "Stickers couldn't load. Check your connection." : nil
    }

    /// The sticker a new placement starts with: the last one used, else the
    /// first sticker of the open pack.
    public var defaultSticker: StickerReference? {
        if let lastUsed { return lastUsed }
        guard let selected, let first = selectedStickers.first else { return nil }
        return .init(packKey: selected.packKey, packVersion: selected.packVersion, stickerId: first.stickerId)
    }

    /// Shows what is cached at once, then refreshes the pack list when it is old. `force`
    /// also retries a pack that failed.
    public func load(force: Bool = false) {
        guard let user else { return }
        if packs.isEmpty, let cached = read(CachedPacks.self, "packs.json") {
            packs = cached.packs
            let store = store
            Task { await store.remember(cached.packs, user: user, fetchedAt: cached.fetchedAt) }
        }
        selectInitialPack()
        if force, let id = selectedPackID, details[id] == nil, let pack = packs.first(where: { $0.id == id }) {
            show(pack)
        }
        guard loadTask == nil else { return }
        if !force, !packs.isEmpty, let lastAttempt, Date().timeIntervalSince(lastAttempt) < 10 { return }
        loadTask = Task { await refresh(force: force); loadTask = nil }
    }

    public func select(_ pack: StickerPackSummary) {
        guard selectedPackID != pack.id || details[pack.id] == nil else { return }
        selectedPackID = pack.id
        show(pack)
    }

    public func use(_ sticker: StickerReference) {
        lastUsed = sticker
        guard let user, let data = try? JSONEncoder().encode(sticker) else { return }
        UserDefaults.standard.set(data, forKey: Self.lastUsedKey(user))
    }

    private func refresh(force: Bool) async {
        guard let user else { return }
        lastAttempt = .now
        let cached = read(CachedPacks.self, "packs.json")
        let stale = force || cached.map { Date().timeIntervalSince($0.fetchedAt) > Self.revalidateInterval } ?? true
        if stale {
            if packs.isEmpty { listLoading = true; listFailed = false }
            if let fresh = await store.packList(user: user) {
                packs = fresh; listFailed = false
                write(CachedPacks(fetchedAt: .now, packs: fresh), "packs.json")
            } else if packs.isEmpty {
                listFailed = true
            }
            listLoading = false
        }
        selectInitialPack()
        warmDetails()
    }

    /// Opens the last used sticker's pack, else the first, the first time packs are known.
    private func selectInitialPack() {
        guard selectedPackID == nil else { return }
        let preferred = lastUsed.flatMap { used in packs.first { $0.packKey == used.packKey && $0.packVersion == used.packVersion } }
        if let pack = preferred ?? packs.first { select(pack) }
    }

    /// Shows the pack's details from memory or disk at once, fetching them when missing,
    /// and refreshing them quietly once their links have expired.
    private func show(_ pack: StickerPackSummary) {
        prefetchTask?.cancel(); prefetchTask = nil
        if details[pack.id] == nil, let cached = readDetail(pack) { install(cached.detail, fetchedAt: cached.fetchedAt) }
        guard let detail = details[pack.id] else { return fetch(pack) }
        if let fetched = fetchedAt[pack.id], Date().timeIntervalSince(fetched) > StickerAssetStore.linkLifetime { fetch(pack) }
        prefetch(detail)
    }

    /// Loads every pack's details in the background, so switching packs is instant.
    private func warmDetails() {
        for pack in packs where details[pack.id] == nil {
            if let cached = readDetail(pack) { install(cached.detail, fetchedAt: cached.fetchedAt) } else { fetch(pack) }
        }
    }

    private func fetch(_ pack: StickerPackSummary) {
        let id = pack.id
        guard let user, detailTasks[id] == nil else { return }
        pending.insert(id); failed.remove(id)
        let store = store
        detailTasks[id] = Task {
            let detail = await store.packDetail(packKey: pack.packKey, version: pack.packVersion, user: user)
            detailTasks[id] = nil
            pending.remove(id)
            guard let detail else { failed.insert(id); return }
            let first = details[id] == nil
            install(detail, fetchedAt: .now)
            write(detail, Self.detailName(pack))
            if first, selectedPackID == id { prefetch(detail) }
        }
    }

    private func install(_ detail: StickerPackDetail, fetchedAt date: Date) {
        orderedStickers[detail.id] = detail.stickers.sorted { $0.sortOrder < $1.sortOrder }
        fetchedAt[detail.id] = date
        details[detail.id] = detail
        failed.remove(detail.id)
        guard let user else { return }
        let store = store
        Task { await store.remember(detail, user: user, fetchedAt: date) }
    }

    /// Downloads and decodes the first stickers of the open pack a few at a time, stopping
    /// when another pack is chosen.
    private func prefetch(_ detail: StickerPackDetail) {
        guard let user else { return }
        prefetchTask?.cancel()
        let references = (orderedStickers[detail.id] ?? []).prefix(Self.prefetchCount).map {
            (StickerReference(packKey: detail.packKey, packVersion: detail.packVersion, stickerId: $0.stickerId), $0.thumbnailUrl)
        }
        let store = store
        prefetchTask = Task.detached(priority: .utility) {
            await withTaskGroup(of: Void.self) { group in
                var queue = references.makeIterator()
                func next() -> Bool {
                    guard !Task.isCancelled, let (sticker, hint) = queue.next() else { return false }
                    group.addTask {
                        _ = await store.image(sticker, variant: .thumbnail, user: user, hint: hint, maxPixelSize: StickerAssetStore.thumbnailPixels)
                    }
                    return true
                }
                for _ in 0..<Self.prefetchWidth where !next() { break }
                while await group.next() != nil { _ = next() }
            }
        }
    }

    // MARK: Disk

    private var folder: URL? {
        guard let user, UUID(uuidString: user) != nil else { return nil }
        return directory.appendingPathComponent(user.lowercased(), isDirectory: true)
    }

    private static func detailName(_ pack: StickerPackSummary) -> String { "pack-\(pack.packKey)-\(pack.packVersion).json" }

    /// A cached pack's details, dated by when they were saved.
    private func readDetail(_ pack: StickerPackSummary) -> (detail: StickerPackDetail, fetchedAt: Date)? {
        let name = Self.detailName(pack)
        guard let detail = read(StickerPackDetail.self, name), let url = folder?.appendingPathComponent(name) else { return nil }
        let saved = (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
        return (detail, saved)
    }

    private func read<Value: Decodable>(_ type: Value.Type, _ name: String) -> Value? {
        guard let url = folder?.appendingPathComponent(name), let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    private func write<Value: Encodable>(_ value: Value, _ name: String) {
        guard let folder, let data = try? JSONEncoder().encode(value) else { return }
        Task.detached(priority: .utility) {
            try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try? data.write(to: folder.appendingPathComponent(name), options: .atomic)
        }
    }

    private static func lastUsedKey(_ user: String) -> String { "stickerLastUsed.\(user)" }
}
