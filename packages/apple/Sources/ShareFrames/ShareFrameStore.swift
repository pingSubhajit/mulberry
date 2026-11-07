import CoreGraphics
import Foundation
#if canImport(ProductCore)
import ProductCore
#endif

/// How the store reaches the catalog. The apps pass a closure so it never holds a stale client.
public typealias ShareFrameCatalogSource = @Sendable () async throws -> [ShareFrameCatalogItem]

/// The frames to offer and their images. The catalog decides which frames show. Its last reply is
/// kept on disk, so the share screen can open on it straight away; before it has ever answered, or
/// when it can't, the frames bundled with the app stand in. Images are kept on disk by key and
/// version, which never change once published, and a catalog frame that is also bundled is drawn
/// from the app's own copy.
public actor ShareFrameStore {
    public enum StoreError: Error, Equatable {
        case unreadableImage, downloadFailed
    }

    private let catalog: ShareFrameCatalogSource
    private let baseURL: URL?
    private let bundled: [ShareFrame]
    private let root: URL
    private let session: URLSession
    private var loads: [URL: Task<Data, Error>] = [:]

    /// `bundledDirectory` holds one folder per bundled frame, with its `layout.json` and its
    /// `frame.webp` or `frame.png`.
    public init(catalog: @escaping ShareFrameCatalogSource, baseURL: URL? = nil, bundledDirectory: URL?,
                root: URL = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
                    .appendingPathComponent("Mulberry/ShareFrames", isDirectory: true),
                session: URLSession = .shared) {
        self.catalog = catalog
        self.baseURL = baseURL
        self.bundled = bundledDirectory.map(Self.bundledFrames(in:)) ?? []
        self.root = root
        self.session = session
    }

    /// The bundled frames, arranged for `day`: what shows before the catalog answers.
    public nonisolated func bundledLineup(on day: String = ServerDate.localDay()) -> ShareFrameLineup {
        ShareFrameRules.lineup(bundled, on: day)
    }

    /// The catalog as it last answered, arranged for `day`: what the share screen opens on, so it
    /// starts on the right frames rather than swapping the bundled ones out a moment later. Nil
    /// until the catalog has once answered with frames this build can draw.
    public nonisolated func cachedLineup(on day: String = ServerDate.localDay()) -> ShareFrameLineup? {
        guard let data = try? Data(contentsOf: catalogFile),
              let list = try? JSONDecoder().decode(ShareFrameCatalogList.self, from: data) else { return nil }
        let frames = list.items.compactMap { ShareFrame($0, baseURL: baseURL) }.map(preferBundled)
        return frames.isEmpty ? nil : ShareFrameRules.lineup(frames, on: day)
    }

    /// The catalog's frames arranged for `day` (the bundled ones when it has none), or nil when it
    /// can't be reached. Each reply is kept for `cachedLineup`.
    public func catalogLineup(on day: String = ServerDate.localDay()) async -> ShareFrameLineup? {
        guard let items = try? await catalog() else { return nil }
        if let data = try? JSONEncoder().encode(CachedCatalog(items: items)) {
            try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            try? data.write(to: catalogFile, options: .atomic)
        }
        let frames = items.compactMap { ShareFrame($0, baseURL: baseURL) }.map(preferBundled)
        return ShareFrameRules.lineup(frames.isEmpty ? bundled : frames, on: day)
    }

    /// The catalog's frames arranged for `day`, or the bundled ones when it can't be reached.
    public func lineup(on day: String = ServerDate.localDay()) async -> ShareFrameLineup {
        await catalogLineup(on: day) ?? ShareFrameRules.lineup(bundled, on: day)
    }

    /// The catalog's last reply, in its own shape.
    private struct CachedCatalog: Encodable { let items: [ShareFrameCatalogItem] }

    private nonisolated var catalogFile: URL { root.appendingPathComponent("catalog.json") }

    /// The frame's full image.
    public func image(_ frame: ShareFrame) async throws -> CGImage {
        let data = try await bytes(frame.imageURL, cache: frame.bundled ? nil : cacheFile(frame, name: "frame"))
        guard let image = ShareFrameRenderer.decodeImage(data) else { throw StoreError.unreadableImage }
        return image
    }

    /// The frame's image at most `maxPixelSize` on its long side: the catalog's thumbnail when it
    /// has one, else the full image, downsampled.
    public func thumbnail(_ frame: ShareFrame, maxPixelSize: Int) async throws -> CGImage {
        let data: Data
        if !frame.bundled, let thumbnail = frame.thumbnailURL {
            data = try await bytes(thumbnail, cache: cacheFile(frame, name: "thumbnail"))
        } else {
            data = try await bytes(frame.imageURL, cache: frame.bundled ? nil : cacheFile(frame, name: "frame"))
        }
        guard let image = ShareFrameRenderer.decodeImage(data, maxPixelSize: maxPixelSize) else { throw StoreError.unreadableImage }
        return image
    }

    // MARK: Files

    private func bytes(_ url: URL, cache file: URL?) async throws -> Data {
        if url.isFileURL { return try Data(contentsOf: url) }
        if let file, let data = try? Data(contentsOf: file), !data.isEmpty { return data }
        if let pending = loads[url] { return try await pending.value }
        let session = session
        let task = Task<Data, Error> {
            var request = URLRequest(url: url)
            request.timeoutInterval = 45
            let (data, response) = try await session.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200, !data.isEmpty else { throw StoreError.downloadFailed }
            return data
        }
        loads[url] = task
        defer { loads[url] = nil }
        let data = try await task.value
        if let file {
            try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? data.write(to: file, options: .atomic)
        }
        return data
    }

    /// `<root>/<key>/<version>/<name>`: a published version's images never change.
    private func cacheFile(_ frame: ShareFrame, name: String) -> URL? {
        guard ShareFrameRules.isValidKey(frame.key), frame.version > 0 else { return nil }
        return root.appendingPathComponent(frame.key, isDirectory: true)
            .appendingPathComponent(String(frame.version), isDirectory: true)
            .appendingPathComponent(name)
    }

    /// A catalog frame the app already carries, same key, version and layout, is drawn from the
    /// app's copy rather than downloaded.
    private nonisolated func preferBundled(_ frame: ShareFrame) -> ShareFrame {
        guard let copy = bundled.first(where: { $0.key == frame.key && $0.version == frame.version && $0.layout == frame.layout }) else { return frame }
        return ShareFrame(key: frame.key, version: frame.version, title: frame.title, sortOrder: frame.sortOrder,
                          availableFrom: frame.availableFrom, availableUntil: frame.availableUntil, layout: frame.layout,
                          imageURL: copy.imageURL, thumbnailURL: nil, bundled: true)
    }

    /// The frames in `directory`, one folder each, named for its key. Bundled frames are version 1;
    /// their titles and order come from `BundledFrameInfo`.
    public static func bundledFrames(in directory: URL) -> [ShareFrame] {
        let folders = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        return folders.sorted { $0.lastPathComponent < $1.lastPathComponent }.compactMap { folder in
            guard let data = try? Data(contentsOf: folder.appendingPathComponent("layout.json")),
                  let layout = ShareFrameLayout.decodeIfSupported(data), layout.key == folder.lastPathComponent,
                  let image = ["frame.webp", "frame.png"].map(folder.appendingPathComponent)
                    .first(where: { FileManager.default.fileExists(atPath: $0.path) }) else { return nil }
            let info = BundledFrameInfo.known[layout.key] ?? BundledFrameInfo(title: layout.key.capitalized, sortOrder: 1000)
            return ShareFrame(key: layout.key, version: 1, title: info.title, sortOrder: info.sortOrder, layout: layout,
                              imageURL: image, bundled: true)
        }
    }
}

/// The bundled frames' titles and order, as the catalog lists them.
public struct BundledFrameInfo: Sendable {
    public let title: String
    public let sortOrder: Int

    public static let known: [String: BundledFrameInfo] = [
        "polaroid": BundledFrameInfo(title: "Polaroid", sortOrder: 10),
        "plain": BundledFrameInfo(title: "Plain", sortOrder: 20)
    ]
}
