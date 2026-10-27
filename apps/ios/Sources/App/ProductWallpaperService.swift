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
    var filename: String { "mulberry-\(id.uuidString).png" }
}

@MainActor
final class ProductWallpaperService {
    static let shared = ProductWallpaperService()
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
        let state = try await api.bootstrap()
        guard let user = state.userId else { throw BackendError.signedOut }
        let journal = try journal(user: user, pair: state.pairSessionId)
        var canvas = DrawingState()
        if let pair = state.pairSessionId, state.isPaired {
            let document = try await AppRuntime.drawings.load(userId: user, pairSessionId: pair)
            let operations = try await api.canvasOperations(after: document.confirmed.revision)
            let updated = try await AppRuntime.drawings.receive(userId: user, pairSessionId: pair, operations: operations)
            canvas = try updated.visible
        }
        let background = WallpaperLibrary(); background.load(user: user)
        if let error = background.errorMessage { throw BackendError.rejected(0, error) }
        let size = try WallpaperRenderSize.current()
        let rendered = DoodleRenderer.wallpaper(background: background.image, strokes: background.showDrawings ? canvas.strokes : [],
                                                dry: state.canvasStrokeRenderMode == "dry", pixelSize: size,
                                                displayScale: try WallpaperRenderSize.displayScale())
        guard user == (await api.signedInUserId) else { throw BackendError.sessionChanged }
        let current = try await api.bootstrap()
        guard current.userId == user, current.pairSessionId == state.pairSessionId else { throw DrawingError.wrongSession }
        guard let data = rendered.pngData() else { throw WallpaperImageError.invalidImage }
        let history = await journal.all()
        let probe = history.last(where: { $0.stage == "local_notification_scheduled" && Date().timeIntervalSince($0.timestamp) < 120 })?.revision
        let export = WallpaperExport(id: UUID(), userId: user, pairId: state.pairSessionId, revision: canvas.revision, probe: probe, preparedAt: .now, drawingsVisible: background.showDrawings)
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
        let state = try await AppRuntime.api().bootstrap()
        guard export.userId == state.userId, export.pairId == state.pairSessionId else { throw DrawingError.wrongSession }
        let journal = try journal(user: export.userId, pair: export.pairId)
        try await journal.append(.init(stage: "shortcut_reported_applied", revision: export.probe,
                                      detail: "Set Wallpaper returned for canvas revision \(export.revision). This is not a pixel readback."))
        UserDefaults.standard.set(Date(), forKey: "wallpaperReceipt.\(export.userId).\(export.pairId ?? "unpaired")")
        UserDefaults.standard.set(export.revision, forKey: "wallpaperRevision.\(export.userId).\(export.pairId ?? "unpaired")")
        UserDefaults.standard.set(export.drawingsVisible == true, forKey: "wallpaperDrawingsVisible.\(export.userId).\(export.pairId ?? "unpaired")")
        let center = UNUserNotificationCenter.current()
        let delivered = await center.deliveredNotifications()
        let identifiers = delivered.compactMap { item -> String? in
            let info = item.request.content.userInfo
            guard info["pairSessionId"] as? String == (export.pairId ?? "unpaired") else { return nil }
            // Remote FCM payloads identify the immutable pair. Local probes also
            // identify the user; neither may clean up another pairing's notice.
            if info["type"] as? String == "CANVAS_UPDATED" {
                guard let value = info["latestRevision"] as? String, let revision = Int64(value),
                      revision >= 0, revision <= export.revision else { return nil }
                return item.request.identifier
            }
            guard info["kind"] as? String == "wallpaper_update", info["userId"] as? String == export.userId else { return nil }
            if let probe = info["probe"] as? Int { return probe == export.probe ? item.request.identifier : nil }
            guard let revision = info["revision"] as? Int64, revision <= export.revision else { return nil }
            return item.request.identifier
        }
        center.removeDeliveredNotifications(withIdentifiers: identifiers)
        try await journal.append(.init(stage: "wallpaper_notifications_removed", revision: export.probe,
                                      detail: "Removed \(identifiers.count) matching delivered wallpaper notifications."))
    }

    func scheduleCanvasUpdate(user: String, pair: String, revision: Int64) async {
        let receiptKey = "wallpaperReceipt.\(user).\(pair)"
        // Do not start Shortcuts before the user has completed a run for this pair.
        guard UserDefaults.standard.object(forKey: receiptKey) != nil else { return }
        let applied = UserDefaults.standard.object(forKey: "wallpaperRevision.\(user).\(pair)") as? NSNumber
        guard applied == nil || applied!.int64Value < revision else { return }
        let content = UNMutableNotificationContent()
        content.title = "Mulberry canvas updated"
        content.body = "Your wallpaper update is ready."
        content.interruptionLevel = .passive
        content.threadIdentifier = "mulberry-wallpaper"
        content.userInfo = ["kind": "wallpaper_update", "userId": user, "pairSessionId": pair, "revision": revision]
        try? await UNUserNotificationCenter.current().add(.init(identifier: "mulberry-canvas-\(pair)", content: content,
            trigger: UNTimeIntervalNotificationTrigger(timeInterval: 3, repeats: false)))
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
    static func current() throws -> CGSize {
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

@MainActor
final class WallpaperSetupModel: ObservableObject {
    @Published private(set) var health = SetupHealth.assess(access: .notRequested, events: [])
    @Published private(set) var permission = "Not checked"
    @Published private(set) var allowed = false
    @Published private(set) var busy = false
    @Published private(set) var remoteDeliveryMessage: String?
    @Published var message: String?
    private var refreshing = false
    private var lastPresenceSignature: String?
    private let model: MulberryModel
    init(model: MulberryModel) { self.model = model }
    private var journal: DeliveryJournal? {
        guard let user = model.userId else { return nil }
        return try? ProductWallpaperService.shared.journal(user: user, pair: model.bootstrap?.pairSessionId)
    }
    var shortcutName: String { Bundle.main.bundleIdentifier?.hasSuffix(".dev") == true ? "Mulberry Wallpaper Dev" : "Mulberry Wallpaper" }
    var template: URL? { Bundle.main.url(forResource: shortcutName, withExtension: "shortcut", subdirectory: "ShortcutTemplates") }

    func refresh() async {
        guard !refreshing, let journal else { return }
        refreshing = true; defer { refreshing = false }
        if UserDefaults.standard.string(forKey: "apnsRegistrationError") != nil || UserDefaults.standard.string(forKey: "pushRegistrationError") != nil {
            remoteDeliveryMessage = "Remote updates are not connected yet. Local Shortcut tests can still run."
        } else { remoteDeliveryMessage = nil }
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        let key = "notificationSettings.\(model.userId ?? "")"
        let signature = "\(settings.authorizationStatus.rawValue):\(settings.notificationCenterSetting.rawValue):\(settings.lockScreenSetting.rawValue):\(settings.alertSetting.rawValue):\(settings.scheduledDeliverySetting.rawValue)"
        if let previous = UserDefaults.standard.string(forKey: key), previous != signature {
            try? await journal.append(.init(stage: "notification_settings_changed", detail: "Notification delivery settings changed."))
        }
        UserDefaults.standard.set(signature, forKey: key)
        var access: NotificationAccess
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral: access = .allowed; permission = "Allowed"
        case .denied: access = .denied; permission = "Off"
        default: access = .notRequested; permission = "Not requested"
        }
        if access == .allowed && settings.notificationCenterSetting != .enabled && settings.lockScreenSetting != .enabled && settings.alertSetting != .enabled { access = .noPresentation }
        allowed = access == .allowed
        health = SetupHealth.assess(access: access, events: await journal.all())
        await reportPresence()
    }

    private func reportPresence() async {
        guard !model.busy, let user = model.userId, let pair = model.bootstrap?.pairSessionId else { return }
        let receipt = UserDefaults.standard.object(forKey: "wallpaperReceipt.\(user).\(pair)") as? Date
        let revision = (UserDefaults.standard.object(forKey: "wallpaperRevision.\(user).\(pair)") as? NSNumber)?.int64Value
        let showDrawings = UserDefaults.standard.object(forKey: "showDrawings.\(user)") as? Bool ?? true
        let configured = receipt != nil
        let enabled = allowed && configured && showDrawings
        let renderedDrawings = UserDefaults.standard.bool(forKey: "wallpaperDrawingsVisible.\(user).\(pair)")
        let canSee = enabled && renderedDrawings && model.drawingConnection == .connected && revision.map { $0 >= model.drawing.revision } == true
        let signature = "\(user):\(pair):\(configured):\(enabled):\(canSee):\(revision ?? -1):\(health.state)"
        guard signature != lastPresenceSignature else { return }
        do {
            _ = try await model.api.setPresence(deviceId: PushRegistration.deviceId, configured: configured, enabled: enabled,
                canSeeLatestDrawings: canSee, details: ["lastAppliedRevision": revision.map { .number(Double($0)) } ?? .null,
                                                     "shortcutState": .string(String(describing: health.state))])
            guard user == model.userId, pair == model.bootstrap?.pairSessionId else { return }
            lastPresenceSignature = signature
        } catch { /* Retry on the next health refresh without interrupting drawing. */ }
    }

    func requestPermission() async {
        do {
            if permission == "Off" { openSettings(); return }
            if try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert]) { UIApplication.shared.registerForRemoteNotifications() }
            await refresh()
        } catch { message = error.localizedDescription }
    }

    func runShortcut() async {
        try? await journal?.append(.init(stage: "manual_shortcut_requested", detail: "Manual wallpaper update; not an automatic delivery test."))
        let scheme = Bundle.main.bundleIdentifier?.hasSuffix(".dev") == true ? "mulberry-dev" : "mulberry"
        var url = URLComponents(string: "shortcuts://x-callback-url/run-shortcut")!
        url.queryItems = [.init(name: "name", value: shortcutName), .init(name: "x-success", value: "\(scheme)://wallpaper-return"),
                          .init(name: "x-error", value: "\(scheme)://shortcut-error"), .init(name: "x-cancel", value: "\(scheme)://shortcut-cancelled")]
        if !(await UIApplication.shared.open(url.url!)) { message = "Shortcuts could not open. Install Apple’s Shortcuts app, then try again." }
        await refresh()
    }
    func editShortcut() {
        var url = URLComponents(string: "shortcuts://open-shortcut")!
        url.queryItems = [.init(name: "name", value: shortcutName)]
        UIApplication.shared.open(url.url!)
    }
    func openSettings() { UIApplication.shared.open(URL(string: UIApplication.openNotificationSettingsURLString)!) }

    func test() async {
        guard !busy, let user = model.userId, let journal else { return }
        busy = true; message = nil; defer { busy = false }
        do {
            await refresh()
            guard allowed else { message = "Allow notifications before testing wallpaper updates."; return }
            let history = await journal.all()
            let probe = (history.compactMap(\.revision).max() ?? 0) + 1
            let content = UNMutableNotificationContent()
            content.title = "Mulberry canvas updated"
            content.body = "Your wallpaper update is ready."
            content.interruptionLevel = .passive
            content.threadIdentifier = "mulberry-wallpaper"
            content.userInfo = ["kind": "wallpaper_update", "userId": user, "pairSessionId": model.bootstrap?.pairSessionId ?? "unpaired", "probe": probe]
            try await UNUserNotificationCenter.current().add(.init(identifier: "mulberry-test-\(UUID().uuidString)", content: content,
                                                                  trigger: UNTimeIntervalNotificationTrigger(timeInterval: 5, repeats: false)))
            try await journal.append(.init(stage: "local_notification_scheduled", revision: probe, detail: "Local delivery test. Not a remote push test."))
            message = "Go Home or lock your phone. The test starts in 5 seconds. Return here after the wallpaper changes."
            await refresh()
        } catch { message = error.localizedDescription }
    }
}
