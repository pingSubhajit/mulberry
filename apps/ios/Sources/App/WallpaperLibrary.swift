import SwiftUI
import ImageIO

struct WallpaperChoice: Codable, Equatable, Identifiable {
    let id: String
    let title: String
    let asset: String?

    static let builtIns: [WallpaperChoice] = [
        .init(id: "default", title: "Mulberry", asset: "WallpaperDefault"),
        .init(id: "preset1", title: "Warm canyon", asset: "WallpaperPreset1"),
        .init(id: "preset2", title: "Red waves", asset: "WallpaperPreset2"),
        .init(id: "preset3", title: "Quiet dusk", asset: "WallpaperPreset3"),
        .init(id: "preset4", title: "Mulberry glow", asset: "WallpaperPreset4")
    ]
}

enum WallpaperImageError: LocalizedError {
    case invalidImage, downloadFailed
    var errorDescription: String? {
        switch self {
        case .invalidImage: "That photo could not be opened. Please choose another image."
        case .downloadFailed: "The wallpaper could not be downloaded. Please try again."
        }
    }
}

enum ImagePreparation {
    /// Decode only at wallpaper resolution, preserving EXIF orientation. Camera
    /// metadata is discarded when we encode the private, on-device copy.
    static func image(_ data: Data, maximumDimension: Int = 4096) throws -> UIImage {
        guard data.count <= 50_000_000,
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: maximumDimension,
                kCGImageSourceShouldCacheImmediately: true
              ] as CFDictionary) else { throw WallpaperImageError.invalidImage }
        return UIImage(cgImage: cgImage)
    }
    static func profileJPEG(_ data: Data) async throws -> Data {
        try await Task.detached(priority: .userInitiated) {
            let image = try image(data, maximumDimension: 1024)
            guard let jpeg = image.jpegData(compressionQuality: 0.88) else { throw WallpaperImageError.invalidImage }
            return jpeg
        }.value
    }
}

/// The background is private to this account and device. It is never included
/// in a canvas operation or uploaded to the shared canvas.
@MainActor
final class WallpaperLibrary: ObservableObject {
    @Published private(set) var choice = WallpaperChoice.builtIns[0]
    @Published private(set) var image = UIImage(named: "WallpaperDefault")!
    @Published private(set) var working = false
    @Published var errorMessage: String?
    @Published private(set) var showDrawings = true
    private(set) var userId: String?
    private var generation = 0

    func load(user: String?) {
        guard user != userId else { return }
        generation += 1; userId = user; working = false; errorMessage = nil
        choice = WallpaperChoice.builtIns[0]; image = UIImage(named: "WallpaperDefault")!
        showDrawings = true
        guard let user, UUID(uuidString: user) != nil else { return }
        showDrawings = UserDefaults.standard.object(forKey: "showDrawings.\(user)") as? Bool ?? true
        guard let data = UserDefaults.standard.data(forKey: "wallpaper.\(user)"),
              let saved = try? JSONDecoder().decode(WallpaperChoice.self, from: data) else { return }
        if let asset = saved.asset, let builtIn = WallpaperChoice.builtIns.first(where: { $0.asset == asset }), let cached = UIImage(named: asset) {
            choice = builtIn; image = cached
        } else if saved.asset == nil, let file = try? Self.photoURL(user: user, choice: saved), let data = try? Data(contentsOf: file), let cached = try? ImagePreparation.image(data) {
            choice = saved; image = cached
        } else { errorMessage = "Your saved wallpaper could not be opened. Choose it again to restore it." }
    }

    func select(_ choice: WallpaperChoice) {
        guard !working, let asset = choice.asset, let image = UIImage(named: asset), userId != nil else { return }
        generation += 1; self.choice = choice; self.image = image; errorMessage = nil
        persist()
    }

    func importPhoto(_ data: Data) async -> Bool {
        await saveImage(.init(id: UUID().uuidString, title: "Your photo", asset: nil)) { data }
    }

    func download(_ wallpaper: CatalogWallpaper) async -> Bool {
        await saveImage(.init(id: UUID().uuidString, title: wallpaper.title, asset: nil)) {
            var request = URLRequest(url: wallpaper.fullImageUrl)
            request.timeoutInterval = 45
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let response = response as? HTTPURLResponse, response.statusCode == 200 else { throw WallpaperImageError.downloadFailed }
            return data
        }
    }

    func setShowDrawings(_ enabled: Bool) {
        guard let userId else { return }
        showDrawings = enabled
        UserDefaults.standard.set(enabled, forKey: "showDrawings.\(userId)")
    }

    private func saveImage(_ choice: WallpaperChoice, data: () async throws -> Data) async -> Bool {
        guard !working, let user = userId else { return false }
        working = true; errorMessage = nil
        let epoch = generation
        defer { if epoch == generation { working = false } }
        do {
            let bytes = try await data()
            let jpeg = try await Task.detached(priority: .userInitiated) {
                let image = try ImagePreparation.image(bytes)
                guard let result = image.jpegData(compressionQuality: 0.93) else { throw WallpaperImageError.invalidImage }
                return result
            }.value
            guard epoch == generation, userId == user else { return false }
            let file = try Self.photoURL(user: user, choice: choice)
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try jpeg.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            var excluded = URLResourceValues(); excluded.isExcludedFromBackup = true
            var directory = file.deletingLastPathComponent(); try directory.setResourceValues(excluded)
            guard let image = UIImage(data: jpeg) else { throw WallpaperImageError.invalidImage }
            let previous = self.choice
            self.choice = choice; self.image = image; persist()
            if previous.asset == nil, let old = try? Self.photoURL(user: user, choice: previous) { try? FileManager.default.removeItem(at: old) }
            return true
        } catch {
            if epoch == generation { errorMessage = error.localizedDescription }
            return false
        }
    }

    private func persist() {
        guard let userId, let data = try? JSONEncoder().encode(choice) else { return }
        UserDefaults.standard.set(data, forKey: "wallpaper.\(userId)")
    }

    static func photoURL(user: String, choice: WallpaperChoice) throws -> URL {
        guard UUID(uuidString: user) != nil, UUID(uuidString: choice.id) != nil else { throw WallpaperImageError.invalidImage }
        return AppRuntime.directory.appendingPathComponent("Wallpapers/\(user)/\(choice.id).jpg")
    }
}
