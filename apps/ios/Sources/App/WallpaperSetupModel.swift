import UIKit
import UserNotifications

enum SetupStep: Int, CaseIterable {
    case allow = 1, add, choose, automate, run
    /// Steps iOS cannot confirm, so they are remembered once the user takes them.
    static let selfReported: [SetupStep] = [.add, .choose, .automate]
}

@MainActor
final class WallpaperSetupModel: ObservableObject {
    @Published private(set) var health = SetupHealth.assess(access: .notRequested, events: [])
    @Published private(set) var permission = "Not checked"
    @Published private(set) var allowed = false
    @Published private(set) var busy = false
    @Published private(set) var remoteDeliveryMessage: String?
    @Published var message: String?
    @Published private(set) var shortcutAdded = false
    @Published private(set) var shortcutConfigured = false
    @Published private(set) var automationOn = false
    @Published private(set) var shortcutRan = false
    private var refreshing = false
    private var awaitingShortcutReturn: SetupStep?
    private var presenceReport: Task<Void, Never>?
    private var lastPresenceSignature: String?
    private let model: MulberryModel
    init(model: MulberryModel) { self.model = model }
    private var journal: DeliveryJournal? {
        guard let user = model.userId else { return nil }
        return try? ProductWallpaperService.shared.journal(user: user, pair: model.bootstrap?.pairSessionId)
    }
    var shortcutName: String { "Mulberry Sync" }
    private func stepKey(_ step: SetupStep) -> String { "wallpaperSetup.\(step).\(model.userId ?? "")" }
    private func mark(_ step: SetupStep, _ value: Bool) {
        UserDefaults.standard.set(value, forKey: stepKey(step))
        switch step {
        case .add: shortcutAdded = value
        case .choose: shortcutConfigured = value
        case .automate: automationOn = value
        case .allow, .run: break
        }
    }
    func markShortcutAdded() { mark(.add, true) }
    var template: URL? { Bundle.main.url(forResource: shortcutName, withExtension: "shortcut", subdirectory: "ShortcutTemplates") }

    /// Publishes only real changes. The screen polls every two seconds, and an unchanged
    /// assignment would still re-render it, rebuilding any open menu under the user's finger.
    private func update<Value: Equatable>(_ property: ReferenceWritableKeyPath<WallpaperSetupModel, Value>, _ value: Value) {
        if self[keyPath: property] != value { self[keyPath: property] = value }
    }

    func refresh() async {
        guard !refreshing, let journal else { return }
        refreshing = true; defer { refreshing = false }
        let pushBroken = UserDefaults.standard.string(forKey: "apnsRegistrationError") != nil || UserDefaults.standard.string(forKey: "pushRegistrationError") != nil
        update(\.remoteDeliveryMessage, pushBroken ? "Remote updates are not connected yet. Local Shortcut tests can still run." : nil)
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        let key = "notificationSettings.\(model.userId ?? "")"
        let signature = "\(settings.authorizationStatus.rawValue):\(settings.notificationCenterSetting.rawValue):\(settings.lockScreenSetting.rawValue):\(settings.alertSetting.rawValue):\(settings.scheduledDeliverySetting.rawValue)"
        if let previous = UserDefaults.standard.string(forKey: key), previous != signature {
            try? await journal.append(.init(stage: "notification_settings_changed", detail: "Notification delivery settings changed."))
        }
        UserDefaults.standard.set(signature, forKey: key)
        var access: NotificationAccess
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral: access = .allowed; update(\.permission, "Allowed")
        case .denied: access = .denied; update(\.permission, "Off")
        default: access = .notRequested; update(\.permission, "Not requested")
        }
        if access == .allowed && settings.notificationCenterSetting != .enabled && settings.lockScreenSetting != .enabled && settings.alertSetting != .enabled { access = .noPresentation }
        update(\.allowed, access == .allowed)
        let history = await journal.all()
        // Events before the latest reset belong to an earlier setup run.
        let events = history.lastIndex { $0.stage == "setup_reset" }.map { Array(history[($0 + 1)...]) } ?? history
        update(\.health, SetupHealth.assess(access: access, events: events))
        // The Shortcut normally removes its trigger notification; after a failed test nothing else will.
        if health.state == .incomplete { await clearTestNotifications() }
        let ran = events.contains { $0.stage == "shortcut_reported_applied" }
        update(\.shortcutRan, ran)
        update(\.shortcutAdded, ran || UserDefaults.standard.bool(forKey: stepKey(.add)))
        update(\.shortcutConfigured, ran || UserDefaults.standard.bool(forKey: stepKey(.choose)))
        // A manual run works with Automation off, so only a passed test proves this step.
        update(\.automationOn, health.state == .responded || UserDefaults.standard.bool(forKey: stepKey(.automate)))
        // A slow or unreachable backend must not hold up the next local refresh.
        if presenceReport == nil {
            presenceReport = Task { await reportPresence(); presenceReport = nil }
        }
    }

    private func reportPresence() async {
        guard !model.busy, let user = model.userId, let pair = model.bootstrap?.pairSessionId else { return }
        let receipt = UserDefaults.standard.object(forKey: "wallpaperReceipt.\(user).\(pair)") as? Date
        let revision = (UserDefaults.standard.object(forKey: "wallpaperRevision.\(user).\(pair)") as? NSNumber)?.int64Value
        let showDrawings = UserDefaults.standard.object(forKey: "showDrawings.\(user)") as? Bool ?? true
        let configured = receipt != nil
        let enabled = allowed && configured && showDrawings
        let renderedDrawings = UserDefaults.standard.bool(forKey: "wallpaperDrawingsVisible.\(user).\(pair)")
        let canSee = enabled && renderedDrawings && model.drawingConnection == .connected && revision.map { $0 >= model.canvas.state.revision } == true
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
        // The callback must belong to this exact app. Dev and production can be
        // installed together, so shared schemes can return to the wrong setup.
        guard let scheme = Bundle.main.bundleIdentifier else { return }
        var url = URLComponents(string: "shortcuts://x-callback-url/run-shortcut")!
        url.queryItems = [.init(name: "name", value: shortcutName), .init(name: "x-success", value: "\(scheme)://wallpaper-return"),
                          .init(name: "x-error", value: "\(scheme)://shortcut-error"), .init(name: "x-cancel", value: "\(scheme)://shortcut-cancelled")]
        if !(await UIApplication.shared.open(url.url!)) { message = "Shortcuts could not open. Install Apple’s Shortcuts app, then try again." }
        await refresh()
    }
    func startOver() async { await undoSteps(from: .add) }
    /// Redoing a step invalidates every later step, including the last run and test.
    func undoSteps(from first: SetupStep) async {
        for step in SetupStep.selfReported where step.rawValue >= first.rawValue { mark(step, false) }
        shortcutRan = false
        try? await journal?.append(.init(stage: "setup_reset", detail: "User restarted wallpaper setup."))
        message = nil
        await refresh()
    }
    /// Marks a Shortcut editor step only after the user comes back from Shortcuts.
    func returnedToApp() {
        guard let step = awaitingShortcutReturn else { return }
        awaitingShortcutReturn = nil
        mark(step, true)
    }
    func editShortcut(for step: SetupStep? = nil) {
        awaitingShortcutReturn = step
        var url = URLComponents(string: "shortcuts://open-shortcut")!
        url.queryItems = [.init(name: "name", value: shortcutName)]
        UIApplication.shared.open(url.url!)
    }
    private func clearTestNotifications() async {
        let center = UNUserNotificationCenter.current()
        let stale = await center.deliveredNotifications().map(\.request.identifier).filter { $0.hasPrefix("mulberry-test-") }
        if !stale.isEmpty { center.removeDeliveredNotifications(withIdentifiers: stale) }
    }
    /// Shortcuts has no public link to its automation list, so open the library where every copy is visible.
    func openShortcutsLibrary() { UIApplication.shared.open(URL(string: "shortcuts://")!) }
    func openSettings() { UIApplication.shared.open(URL(string: UIApplication.openNotificationSettingsURLString)!) }

    func test() async {
        guard !busy, let user = model.userId, let journal else { return }
        busy = true; message = nil; defer { busy = false }
        await clearTestNotifications()
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
            await refresh()
        } catch { message = error.localizedDescription }
    }
}
