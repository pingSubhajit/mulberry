import UIKit
import UserNotifications

struct WallpaperExport: Codable {
    let id: UUID
    let userId: String
    let pairId: String?
    let revision: Int64
    let probe: Int?
    let preparedAt: Date
    var drawingsVisible: Bool? = nil
    var contentSignature: String? = nil
    var filename: String { "mulberry-\(id.uuidString).png" }
}

@MainActor
final class ProductWallpaperService {
    static let shared = ProductWallpaperService()
    private let notifications: any WallpaperNotifications
    init(notifications: (any WallpaperNotifications)? = nil) { self.notifications = notifications ?? SystemWallpaperNotifications() }
    private var journals: [String: DeliveryJournal] = [:]
    private let exportDirectory = AppRuntime.directory.appendingPathComponent("WallpaperExports", isDirectory: true)

    func journal(user: String, pair: String?) throws -> DeliveryJournal {
        guard UUID(uuidString: user) != nil, pair == nil || UUID(uuidString: pair!) != nil else { throw DrawingError.wrongSession }
        let key = "\(user)-\(pair ?? "unpaired")"
        if let existing = journals[key] { return existing }
        let directory = AppRuntime.directory.appendingPathComponent("Delivery/\(key)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
                                                attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        let journal = try DeliveryJournal(directory: directory)
        journals[key] = journal
        return journal
    }

    func prepare() async throws -> URL {
        let api = try AppRuntime.api()
        guard let user = await api.signedInUserId else { throw BackendError.signedOut }
        var state = cachedState(user: user)
        var refreshed = false
        do {
            let fresh = try await api.bootstrap(quick: true)
            guard fresh.userId == user else { throw BackendError.sessionChanged }
            try cache(fresh)
            state = fresh
            refreshed = true
        } catch {
            guard WallpaperContent.canUseOfflineCopy(after: error), state != nil else { throw error }
        }
        guard let state else { throw BackendError.signedOut }
        try await validateSession(state)
        let journal = try journal(user: user, pair: state.pairSessionId)
        var canvas = DrawingState()
        if let pair = state.pairSessionId, state.isPaired {
            let document = try await AppRuntime.drawings.load(userId: user, pairSessionId: pair)
            do {
                if refreshed {
                    let operations = try await api.canvasOperations(after: document.confirmed.revision, quick: true)
                    try await validateSession(state)
                    _ = try await AppRuntime.drawings.receive(userId: user, pairSessionId: pair, operations: operations)
                }
            } catch {
                guard WallpaperContent.canUseOfflineCopy(after: error) else { throw error }
            }
            // Re-read after network awaits so new local edits are included too.
            canvas = try await AppRuntime.drawings.load(userId: user, pairSessionId: pair).visible
        }
        let background = WallpaperLibrary(); background.load(user: user)
        if let error = background.errorMessage { throw BackendError.rejected(0, error) }
        let size = try WallpaperRenderSize.current()
        let region = try WallpaperRenderSize.visibleRegion()
        let elements = background.showDrawings ? canvas.canvasElements : []
        // Like Android, a Shortcut run downloads any sticker artwork not cached yet.
        var stickerImages: [StickerReference: UIImage] = [:]
        for sticker in Set(elements.compactMap(\.sticker?.sticker)) {
            stickerImages[sticker] = await StickerAssetStore.shared.image(sticker, variant: .full, user: user)
        }
        let missingArtwork = elements.contains { $0.sticker.map { stickerImages[$0.sticker] == nil } ?? false }
        let rendered = DoodleRenderer.wallpaper(background: background.image, strokes: background.showDrawings ? canvas.strokes : [],
                                                elements: elements, stickerImages: stickerImages,
                                                dry: state.canvasStrokeRenderMode == "dry", pixelSize: size,
                                                displayScale: try WallpaperRenderSize.displayScale(),
                                                canvasSize: CanvasViewportStore.load(user: user, pair: state.pairSessionId),
                                                canvasRegion: region)
        guard user == (await api.signedInUserId) else { throw BackendError.sessionChanged }
        try await validateSession(state)
        guard let data = rendered.pngData() else { throw WallpaperImageError.invalidImage }
        let history = await journal.all()
        let probe = history.last(where: { $0.stage == "local_notification_scheduled" && Date().timeIntervalSince($0.timestamp) < 120 })?.revision
        let export = WallpaperExport(id: UUID(), userId: user, pairId: state.pairSessionId, revision: canvas.revision, probe: probe, preparedAt: .now, drawingsVisible: background.showDrawings,
                                     // A placeholder stands in for missing artwork; keep the wallpaper out of date so it renders again.
                                     contentSignature: try signature(canvas: canvas, background: background, state: state) + (missingArtwork ? "-incomplete" : ""))
        try FileManager.default.createDirectory(at: exportDirectory, withIntermediateDirectories: true,
                                                attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        let file = exportDirectory.appendingPathComponent(export.filename)
        try data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        try JSONEncoder().encode(export).write(to: metadataURL(export.id), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        try await journal.append(.init(stage: "image_ready", revision: probe, detail: "Prepared canvas revision \(canvas.revision). Wallpaper has not been confirmed."))
        pruneExports(except: export.id)
        return file
    }

    func record(filename: String) async throws {
        guard filename.hasPrefix("mulberry-"), filename.hasSuffix(".png"),
              let id = UUID(uuidString: String(filename.dropFirst(9).dropLast(4))) else { throw DeliveryError.invalidRevision }
        let export = try JSONDecoder().decode(WallpaperExport.self, from: Data(contentsOf: metadataURL(id)))
        guard export.id == id, Date().timeIntervalSince(export.preparedAt) < 1800 else { throw DeliveryError.invalidRevision }
        guard let user = try await AppRuntime.api().signedInUserId,
              let state = cachedState(user: user) else { throw BackendError.signedOut }
        try await validateSession(state)
        guard export.userId == state.userId, export.pairId == state.pairSessionId else { throw DrawingError.wrongSession }
        let journal = try journal(user: export.userId, pair: export.pairId)
        try await journal.append(.init(stage: "shortcut_reported_applied", revision: export.probe,
                                      detail: "Set Wallpaper returned for canvas revision \(export.revision). This is not a pixel readback."))
        UserDefaults.standard.set(Date(), forKey: "wallpaperReceipt.\(export.userId).\(export.pairId ?? "unpaired")")
        UserDefaults.standard.set(export.revision, forKey: "wallpaperRevision.\(export.userId).\(export.pairId ?? "unpaired")")
        UserDefaults.standard.set(export.drawingsVisible == true, forKey: "wallpaperDrawingsVisible.\(export.userId).\(export.pairId ?? "unpaired")")
        if let signature = export.contentSignature {
            UserDefaults.standard.set(signature, forKey: "wallpaperContent.\(export.userId).\(export.pairId ?? "unpaired")")
        }
        let delivered = await notifications.delivered()
        try await validateSession(state)
        let identifiers = delivered.compactMap { item -> String? in
            let info = item.content.userInfo
            guard info["pairSessionId"] as? String == (export.pairId ?? "unpaired") else { return nil }
            // Remote FCM payloads identify the immutable pair. Local probes also
            // identify the user; neither may clean up another pairing's notice.
            if info["type"] as? String == "CANVAS_UPDATED" {
                guard let value = info["latestRevision"] as? String, let revision = Int64(value),
                      revision >= 0, revision <= export.revision else { return nil }
                return item.identifier
            }
            guard info["kind"] as? String == "wallpaper_update", info["userId"] as? String == export.userId else { return nil }
            if let probe = info["probe"] as? Int { return probe == export.probe ? item.identifier : nil }
            if let signature = info["contentSignature"] as? String {
                return signature == export.contentSignature ? item.identifier : nil
            }
            guard let revision = info["revision"] as? Int64, revision <= export.revision else { return nil }
            return item.identifier
        }
        notifications.removeDelivered(identifiers)
        try await journal.append(.init(stage: "wallpaper_notifications_removed", revision: export.probe,
                                      detail: "Removed \(identifiers.count) matching delivered wallpaper notifications."))
        // A newer edit made during this Shortcut must not be marked as applied.
        await scheduleLocalUpdate()
    }

    func scheduleLocalUpdate() async {
        do {
            guard let user = try await AppRuntime.api().signedInUserId, let state = cachedState(user: user) else { return }
            let pair = state.pairSessionId ?? "unpaired"
            guard UserDefaults.standard.object(forKey: "wallpaperReceipt.\(user).\(pair)") != nil else { return }
            let canvas = try await localCanvas(state)
            let background = WallpaperLibrary(); background.load(user: user)
            if background.errorMessage != nil { return }
            let signature = try signature(canvas: canvas, background: background, state: state)
            try await validateSession(state)
            let identifier = "mulberry-canvas-\(pair)"
            if signature == UserDefaults.standard.string(forKey: "wallpaperContent.\(user).\(pair)") {
                notifications.removePending([identifier])
                return
            }
            let content = UNMutableNotificationContent()
            content.title = "Mulberry canvas updated"
            content.body = "Your wallpaper update is ready."
            content.interruptionLevel = .passive
            content.threadIdentifier = "mulberry-wallpaper"
            content.userInfo = ["kind": "wallpaper_update", "userId": user, "pairSessionId": pair,
                                "revision": canvas.revision, "contentSignature": signature]
            try await notifications.add(.init(identifier: identifier, content: content,
                trigger: UNTimeIntervalNotificationTrigger(timeInterval: 3, repeats: false)))
        } catch { /* Keep the unapplied content dirty; retry on the next background transition. */ }
    }

    /// True when the wallpaper would look different from the last image the Shortcut applied.
    /// Before the first completed run there is nothing applied, so an update is always useful.
    func hasUnappliedChanges() async -> Bool {
        do {
            guard let user = try await AppRuntime.api().signedInUserId, let state = cachedState(user: user) else { return false }
            guard let applied = UserDefaults.standard.string(forKey: "wallpaperContent.\(user).\(state.pairSessionId ?? "unpaired")"),
                  let current = try await currentSignature(state) else { return true }
            return current != applied
        } catch { return true }
    }

    /// Nil when the saved background cannot be opened, so nothing reliable can be compared or rendered.
    private func currentSignature(_ state: BootstrapState) async throws -> String? {
        guard let user = state.userId else { return nil }
        let canvas = try await localCanvas(state)
        let background = WallpaperLibrary(); background.load(user: user)
        if background.errorMessage != nil { return nil }
        return try signature(canvas: canvas, background: background, state: state)
    }

    /// Erases an account's wallpaper renders and delivery history once the account is deleted.
    func forget(user: String) {
        journals = journals.filter { !$0.key.hasPrefix("\(user)-") }
        let delivery = AppRuntime.directory.appendingPathComponent("Delivery", isDirectory: true)
        for directory in (try? FileManager.default.contentsOfDirectory(at: delivery, includingPropertiesForKeys: nil)) ?? []
        where directory.lastPathComponent.hasPrefix("\(user)-") {
            try? FileManager.default.removeItem(at: directory)
        }
        for metadata in (try? FileManager.default.contentsOfDirectory(at: exportDirectory, includingPropertiesForKeys: nil)) ?? []
        where metadata.pathExtension == "json" {
            guard let export = try? JSONDecoder().decode(WallpaperExport.self, from: Data(contentsOf: metadata)), export.userId == user else { continue }
            try? FileManager.default.removeItem(at: exportDirectory.appendingPathComponent(export.filename))
            try? FileManager.default.removeItem(at: metadata)
        }
    }

    private func cachedState(user: String) -> BootstrapState? {
        guard let data = UserDefaults.standard.data(forKey: "bootstrap.\(user)"),
              let state = try? JSONDecoder().decode(BootstrapState.self, from: data), state.userId == user else { return nil }
        return state
    }

    private func cache(_ state: BootstrapState) throws {
        guard let user = state.userId else { throw BackendError.signedOut }
        UserDefaults.standard.set(try JSONEncoder().encode(state), forKey: "bootstrap.\(user)")
    }

    private func validateSession(_ state: BootstrapState) async throws {
        guard let user = state.userId, user == (try await AppRuntime.api().signedInUserId) else { throw BackendError.sessionChanged }
        guard let current = cachedState(user: user), current.pairSessionId == state.pairSessionId,
              current.isPaired == state.isPaired else { throw DrawingError.wrongSession }
    }

    private func localCanvas(_ state: BootstrapState) async throws -> DrawingState {
        guard let user = state.userId, let pair = state.pairSessionId, state.isPaired else { return DrawingState() }
        return try await AppRuntime.drawings.load(userId: user, pairSessionId: pair).visible
    }

    private func signature(canvas: DrawingState, background: WallpaperLibrary, state: BootstrapState) throws -> String {
        let viewport = state.userId.flatMap { CanvasViewportStore.load(user: $0, pair: state.pairSessionId) }
        return try WallpaperContent.signature(canvas: canvas, backgroundID: background.choice.id,
                                       showDrawings: background.showDrawings, renderMode: state.canvasStrokeRenderMode,
                                       canvasViewport: viewport.map { [Double($0.width), Double($0.height)] } ?? [])
            // A wallpaper rendered before iPad's square layout is out of date.
            + (WallpaperRenderSize.square ? "-square" : "")
    }

    private func metadataURL(_ id: UUID) -> URL { exportDirectory.appendingPathComponent("\(id.uuidString).json") }
    private func pruneExports(except id: UUID) {
        let files = (try? FileManager.default.contentsOfDirectory(at: exportDirectory, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        for file in files where !file.lastPathComponent.contains(id.uuidString) {
            if let date = try? file.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate, Date().timeIntervalSince(date) > 86400 {
                try? FileManager.default.removeItem(at: file)
            }
        }
    }
}

@MainActor
enum WallpaperRenderSize {
    /// iPadOS keeps one wallpaper for both orientations and scales it to cover the screen. A square
    /// of the long side covers either way without scaling, as Apple's own iPad wallpapers do; a
    /// portrait image would be enlarged and cropped in landscape.
    static var square: Bool { UIDevice.current.userInterfaceIdiom == .pad }

    /// The wallpaper image to render, in pixels: the screen on iPhone, a square on iPad.
    static func current() throws -> CGSize {
        let screen = try screen()
        guard square else { return screen }
        let side = max(screen.width, screen.height)
        return CGSize(width: side, height: side)
    }

    /// The part of the wallpaper visible in every orientation, in points: the centred square of the
    /// short side on iPad, where the canvas belongs. Nil on iPhone, where the whole image shows.
    static func visibleRegion() throws -> CGSize? {
        guard square else { return nil }
        let side = try min(screen().width, screen().height) / displayScale()
        return CGSize(width: side, height: side)
    }

    /// The screen's own size in pixels, portrait.
    static func screen() throws -> CGSize {
        if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first {
            let size = scene.screen.nativeBounds.size
            UserDefaults.standard.set([size.width, size.height], forKey: "wallpaperPixelSize")
            UserDefaults.standard.set(scene.screen.nativeScale, forKey: "wallpaperDisplayScale")
            return size
        }
        if let dimensions = UserDefaults.standard.array(forKey: "wallpaperPixelSize") as? [Double], dimensions.count == 2, dimensions.allSatisfy({ $0 > 0 && $0 <= 8192 }) {
            return CGSize(width: dimensions[0], height: dimensions[1])
        }
        throw BackendError.rejected(0, "Open Mulberry once on this phone, then run the Shortcut again.")
    }

    static func displayScale() throws -> CGFloat {
        if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first {
            return scene.screen.nativeScale
        }
        let scale = UserDefaults.standard.double(forKey: "wallpaperDisplayScale")
        guard scale >= 1, scale <= 4 else {
            throw BackendError.rejected(0, "Open Mulberry once on this phone, then run the Shortcut again.")
        }
        return scale
    }
}
