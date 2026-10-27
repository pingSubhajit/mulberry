import SwiftUI
import UserNotifications

@MainActor
final class DeliveryViewModel: ObservableObject {
    @Published var server = AppServices.shared.server
    @Published var events: [DeliveryEvent] = []
    @Published var image: UIImage?
    @Published var prepared: PreparedCanvas?
    @Published var status = "Ready. The event log shows past runs."
    @Published var isBusy = false
    @Published var notificationStatus = "Not checked"
    @Published var health = SetupHealth.assess(access: .notRequested, events: [])
    @Published var notificationDetail = ""
    @Published var deliveredUpdateCount = 0
    @Published var notificationsAllowed = false
    private var isRefreshing = false
    @Published var token = UserDefaults.standard.string(forKey: "apnsToken") ?? "Not registered"

    func saveServer() throws {
        _ = try CanvasClient(server: server)
        AppServices.shared.server = server
    }

    func fetch(source: String = "manual_preview") async {
        await run {
            try self.saveServer()
            let result = try await AppServices.shared.service().prepare(source: source)
            let bytes = try Data(contentsOf: result.fileURL)
            guard let image = UIImage(data: bytes) else { throw DeliveryError.invalidImage }
            self.image = image
            self.prepared = result
            self.status = "Revision \(result.revision) fetched. Wallpaper unchanged."
        }
    }

    func publishAndSchedule() async {
        await run {
            try self.saveServer()
            let settings = await UNUserNotificationCenter.current().notificationSettings()
            guard [.authorized, .provisional, .ephemeral].contains(settings.authorizationStatus) else {
                self.status = "Allow notifications before running this test."
                return
            }
            let manifest = try await CanvasClient(server: self.server).publish()
            let content = UNMutableNotificationContent()
            content.title = "Mulberry canvas updated"
            content.body = "Test revision \(manifest.revision). This is a local notification."
            content.userInfo = ["revision": manifest.revision, "transport": "local_mock", "kind": "wallpaper_update"]
            content.interruptionLevel = .passive
            content.threadIdentifier = "mulberry-delivery-lab"
            try await UNUserNotificationCenter.current().add(UNNotificationRequest(
                identifier: "mulberry-revision-\(manifest.revision)", content: content,
                trigger: UNTimeIntervalNotificationTrigger(timeInterval: 5, repeats: false)))
            await AppServices.shared.log("local_notification_scheduled", revision: manifest.revision,
                                        detail: "5 second delay; this is not APNs delivery")
            self.status = "Test starts in 5 seconds. Go Home or lock your phone, then return here."
        }
    }

    func requestNotifications() async {
        await run {
            let granted = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert])
            self.status = granted ? "Notifications allowed." : "Notifications denied. Change this in Settings."
            if granted { UIApplication.shared.registerForRemoteNotifications() }
        }
    }

    func confirmVisible() async {
        guard let prepared else { return }
        await run {
            try await AppServices.shared.service().log(stage: "user_confirmed_wallpaper", revision: prepared.revision,
                detail: "Tester says the wallpaper shows this revision. Not an OS readback.")
            self.status = "Visual check recorded for revision \(prepared.revision)."
        }
    }

    func refresh() async {
        guard !isRefreshing else { return }
        isRefreshing = true
        defer { isRefreshing = false }
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        let signature = "\(settings.authorizationStatus.rawValue):\(settings.notificationCenterSetting.rawValue):\(settings.lockScreenSetting.rawValue):\(settings.alertSetting.rawValue):\(settings.scheduledDeliverySetting.rawValue)"
        let previous = UserDefaults.standard.string(forKey: "notificationSettingsSnapshot")
        if let previous, previous != signature {
            await AppServices.shared.log("notification_settings_changed", detail: "Delivery settings changed; a new test is needed.")
        }
        UserDefaults.standard.set(signature, forKey: "notificationSettingsSnapshot")
        let history = await AppServices.shared.journal.all()
        events = history.reversed()
        var access: NotificationAccess
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral:
            notificationStatus = "Allowed"
            access = .allowed
        case .denied:
            notificationStatus = "Off"
            access = .denied
        default:
            notificationStatus = "Not requested"
            access = .notRequested
        }
        notificationsAllowed = access == .allowed
        if access == .allowed && settings.notificationCenterSetting != .enabled && settings.lockScreenSetting != .enabled && settings.alertSetting != .enabled {
            access = .noPresentation
        }
        health = SetupHealth.assess(access: access, events: history)
        if status.hasPrefix("Test starts") && health.state != .waiting {
            status = health.title + ". " + health.detail
        }
        let delivery = settings.scheduledDeliverySetting == .enabled ? "Scheduled Summary may delay updates." : "Immediate delivery is selected."
        notificationDetail = "Notification Center: \(settings.notificationCenterSetting == .enabled ? "on" : "off"). \(delivery)"
        let delivered = await UNUserNotificationCenter.current().deliveredNotifications()
        deliveredUpdateCount = delivered.filter { $0.request.content.userInfo["kind"] as? String == "wallpaper_update" }.count
        token = UserDefaults.standard.string(forKey: "apnsToken") ?? "Not registered"
    }

    func openNotificationSettings() {
        UIApplication.shared.open(URL(string: UIApplication.openNotificationSettingsURLString)!)
    }

    func runShortcut() async {
        await AppServices.shared.log("manual_shortcut_requested", detail: "First-run or repair run; does not test the notification trigger.")
        var url = URLComponents(string: "shortcuts://x-callback-url/run-shortcut")!
        url.queryItems = [URLQueryItem(name: "name", value: "Mulberry Wallpaper POC"),
                         URLQueryItem(name: "x-success", value: "mulberry-lab://refresh"),
                         URLQueryItem(name: "x-error", value: "mulberry-lab://shortcut-error"),
                         URLQueryItem(name: "x-cancel", value: "mulberry-lab://shortcut-cancelled")]
        let opened = await UIApplication.shared.open(url.url!)
        if !opened { status = "Shortcuts could not open. Install Apple's Shortcuts app, then try again." }
    }

    private func run(_ operation: () async throws -> Void) async {
        guard !isBusy else { return }
        isBusy = true
        defer { isBusy = false }
        do { try await operation() }
        catch { status = error.localizedDescription }
        await refresh()
    }
}
