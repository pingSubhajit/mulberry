import UIKit
import UserNotifications

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
    var shortcutName: String { "Mulberry Sync" }
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
