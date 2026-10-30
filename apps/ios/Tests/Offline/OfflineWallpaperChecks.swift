import UIKit
import UserNotifications

// Only external services are replaced. The model, editor, disk store, image
// renderer and Shortcut's wallpaper service below are the production classes.
@MainActor enum AppRuntime {
    static let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    static let drawings = DrawingStore(directory: directory.appendingPathComponent("Drawings"))
    static var backend: BackendClient!
    static func api() throws -> BackendClient { backend }
}
@MainActor enum GoogleAuthentication {
    static func signIn() async throws -> String { throw BackendError.signedOut }
    static func signOut() {}
    static func isCancellation(_ error: Error) -> Bool { false }
}
@MainActor final class PushRegistration {
    static let shared = PushRegistration()
    static let deviceId = "offline-check"
    func signOut() async {}
}
@MainActor final class HomeQuickActions {
    static let shared = HomeQuickActions()
    var pending: Int?
}
struct OfflineTokens: SessionTokenStore {
    func load() -> SessionTokens? { .init(accessToken: "test", refreshToken: "test", userId: "11111111-1111-4111-8111-111111111111") }
    func save(_ tokens: SessionTokens) {}
    func clear() {}
}
actor OfflineTransport: BackendTransport {
    enum Mode { case offline, denied, serverError, online }
    var mode: Mode = .offline
    var requests = 0
    func setMode(_ value: Mode) { mode = value }
    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        requests += 1
        if mode == .offline { throw URLError(.cannotConnectToHost) }
        if mode == .online {
            let body = request.url!.path == "/bootstrap"
                ? try Data(contentsOf: Bundle.main.url(forResource: "bootstrap", withExtension: "json")!)
                : Data(#"{"operations":[]}"#.utf8)
            return (body, HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        }
        let status = mode == .denied ? 401 : 503
        return (Data(#"{"message":"Unavailable"}"#.utf8), HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!)
    }
}
@MainActor final class TestNotifications: WallpaperNotifications {
    var pending: [String: UNNotificationRequest] = [:]
    var received: [UNNotificationRequest] = []
    func add(_ request: UNNotificationRequest) async throws { pending[request.identifier] = request }
    func delivered() async -> [UNNotificationRequest] { received }
    func removeDelivered(_ identifiers: [String]) { received.removeAll { identifiers.contains($0.identifier) } }
    func removePending(_ identifiers: [String]) { for id in identifiers { pending.removeValue(forKey: id) } }
    func deliver() { received += pending.values; pending = [:] }
}
@main struct OfflineWallpaperChecks {
    @MainActor static func main() async throws {
        let user = "11111111-1111-4111-8111-111111111111"
        let pair = "22222222-2222-4222-8222-222222222222"
        let fixture = try Data(contentsOf: Bundle.main.url(forResource: "bootstrap", withExtension: "json")!)
        let defaults = UserDefaults.standard
        for key in defaults.dictionaryRepresentation().keys { defaults.removeObject(forKey: key) }
        defaults.set(fixture, forKey: "bootstrap.\(user)")
        defaults.set([1206.0, 2622.0], forKey: "wallpaperPixelSize")
        defaults.set(3.0, forKey: "wallpaperDisplayScale")
        defer { try? FileManager.default.removeItem(at: AppRuntime.directory) }
        let transport = OfflineTransport()
        let notifications = TestNotifications()
        let service = ProductWallpaperService(notifications: notifications)
        AppRuntime.backend = try BackendClient(baseURL: URL(string: "https://offline.invalid")!, tokenStore: OfflineTokens(), transport: transport)
        let stroke = DrawingStroke(id: "offline-stroke", colorArgb: 0xFFB31329, width: 0.02, points: [.init(x: 0.2, y: 0.2), .init(x: 0.7, y: 0.7)], finished: true)
        _ = try await AppRuntime.drawings.enqueue(userId: user, pairSessionId: pair, operations: DrawingOperation.restore(stroke))
        var failures = 0
        func check(_ passed: Bool, _ message: String) {
            print("\(passed ? "PASS" : "FAIL"): \(message)")
            if !passed { failures += 1 }
        }
        let model = MulberryModel(wallpaperService: service)
        await model.restore()
        check(model.canvas.state.strokes == [stroke], "Cold offline launch restores saved strokes")
        do {
            try await model.enqueue([.init(type: "CLEAR_CANVAS")])
            check(model.canvas.state.strokes.isEmpty, "Offline editing works after cold launch")
            try await model.enqueue(DrawingOperation.restore(stroke))
        } catch { check(false, "Offline editing works after cold launch: \(error)") }
        // Complete a first run. Completion is local and must not make a request.
        let image = try await service.prepare()
        check(UIImage(contentsOfFile: image.path)?.cgImage?.width == 1206, "Shortcut exports an offline wallpaper at native resolution")
        await model.setActive(false)
        let beforeRecord = await transport.requests
        try await service.record(filename: image.lastPathComponent)
        check(await transport.requests == beforeRecord, "Offline completion does not call the backend")
        check(notifications.pending.isEmpty, "Unchanged content does not launch another Shortcut")

        let editor = DoodleEditor(model: model)
        let touchView = DoodleTouchView(frame: CGRect(x: 0, y: 0, width: 400, height: 800))
        touchView.editor = editor
        touchView.layoutSubviews()
        check(CanvasViewportStore.load(user: user, pair: pair) == CGSize(width: 400, height: 800), "Actual canvas view persists its measured drawing area")
        check(CanvasViewportStore.load(user: user, pair: "another-pair") == nil, "Canvas dimensions stay scoped to the pairing")
        editor.begin(at: CGPoint(x: 40, y: 40), size: CGSize(width: 400, height: 800))
        editor.move(points: [CGPoint(x: 100, y: 200)], size: CGSize(width: 400, height: 800))
        editor.end()
        // Background immediately, while the editor may still be saving its last batch.
        await model.setActive(false, updateWallpaper: true)
        for _ in 0..<100 where model.canvas.state.finishedStrokes.count < 2 { try await Task.sleep(for: .milliseconds(10)) }
        check(model.canvas.state.strokes.count == 2 && model.canvas.pendingEdits > 0, "Real editor saves new offline strokes")
        await model.setActive(false, updateWallpaper: true)
        check(notifications.pending.count == 1, "Going Home schedules local edits without a server revision")
        notifications.deliver()
        let updated = try await service.prepare()
        let background = WallpaperLibrary(); background.load(user: user)
        let expected = DoodleRenderer.wallpaper(background: background.image, strokes: model.canvas.state.strokes, dry: false,
                                                pixelSize: CGSize(width: 1206, height: 2622), displayScale: 3, canvasSize: CGSize(width: 400, height: 800))
        check(UIImage(contentsOfFile: updated.path)!.pngData() == UIImage(data: expected.pngData()!)!.pngData(), "Export contains the actual saved offline strokes")
        try await service.record(filename: updated.lastPathComponent)
        check(notifications.received.isEmpty && notifications.pending.isEmpty, "Completed offline update clears its notification")

        // Resizing the drawing area changes placement without changing strokes.
        touchView.frame.size.height = 600
        touchView.layoutSubviews()
        await service.scheduleLocalUpdate()
        check(notifications.pending.count == 1, "Canvas layout change invalidates the old wallpaper receipt")
        let resized = try await service.prepare()
        check(try Data(contentsOf: resized) != Data(contentsOf: updated), "Background Shortcut uses the saved canvas dimensions")
        touchView.frame.size.height = 800
        touchView.layoutSubviews()
        try await service.record(filename: resized.lastPathComponent)
        check(notifications.pending.count == 1, "A receipt for old canvas dimensions cannot acknowledge a later layout")
        let restoredLayout = try await service.prepare()
        try await service.record(filename: restoredLayout.lastPathComponent)
        check(notifications.pending.isEmpty, "Current canvas layout receipt stops redundant updates")

        background.select(WallpaperChoice.builtIns[1])
        await model.setActive(false, updateWallpaper: true)
        check(notifications.pending.count == 1, "Changing the base photo schedules an update without new strokes")
        notifications.deliver()
        let changedPhoto = try await service.prepare()
        check(try Data(contentsOf: changedPhoto) != Data(contentsOf: updated), "Offline base-photo change reaches the exported image")
        try await service.record(filename: changedPhoto.lastPathComponent)
        check(notifications.pending.isEmpty, "Photo change is acknowledged locally")

        let photoData = UIGraphicsImageRenderer(size: CGSize(width: 24, height: 24)).image {
            UIColor.blue.setFill(); $0.fill(CGRect(x: 0, y: 0, width: 24, height: 24))
        }.jpegData(compressionQuality: 0.95)!
        check(await background.importPhoto(photoData), "Importing a local photo works offline")
        await model.setActive(false, updateWallpaper: true)
        let imported = try await service.prepare()
        check(try Data(contentsOf: imported) != Data(contentsOf: changedPhoto), "Imported photo reaches the offline export")
        try await service.record(filename: imported.lastPathComponent)

        background.setShowDrawings(false)
        await model.setActive(false, updateWallpaper: true)
        check(notifications.pending.count == 1, "Hiding strokes invalidates the wallpaper")
        let hidden = try await service.prepare()
        try await service.record(filename: hidden.lastPathComponent)
        background.setShowDrawings(true)
        let visible = try await service.prepare()
        try await service.record(filename: visible.lastPathComponent)

        // A Shortcut finishing an older image cannot acknowledge a newer edit.
        let old = try await service.prepare()
        try await model.enqueue([.init(type: "CLEAR_CANVAS")])
        notifications.deliver()
        let unrelated = UNMutableNotificationContent(); unrelated.title = "Pairing update"
        notifications.received.append(.init(identifier: "unrelated", content: unrelated, trigger: nil))
        try await service.record(filename: old.lastPathComponent)
        check(notifications.pending.count == 1, "Edit during a Shortcut stays pending after the old receipt")
        check(notifications.received.count == 2, "Old receipt keeps newer and unrelated notifications")
        let clear = try await service.prepare()
        try await service.record(filename: clear.lastPathComponent)
        check(notifications.received.map(\.identifier) == ["unrelated"], "Latest receipt removes only its matching update")

        let reopened = MulberryModel(wallpaperService: service)
        await reopened.restore()
        check(reopened.canvas.state.strokes.isEmpty && reopened.canvas.pendingEdits > 0, "Cold relaunch keeps offline clears and pending uploads")
        await reopened.setActive(false)

        await transport.setMode(.online)
        check((try? await service.prepare()) != nil, "Online refresh still renders the local outbox")
        await transport.setMode(.serverError)
        check((try? await service.prepare()) != nil, "Temporary server failure uses the local copy")
        var otherPair = try JSONSerialization.jsonObject(with: fixture) as! [String: Any]
        otherPair["pairSessionId"] = "33333333-3333-4333-8333-333333333333"
        defaults.set(try JSONSerialization.data(withJSONObject: otherPair), forKey: "bootstrap.\(user)")
        do { try await service.record(filename: clear.lastPathComponent); check(false, "Old pairing receipt rejected") }
        catch { check(true, "Old pairing receipt rejected") }
        defaults.set(fixture, forKey: "bootstrap.\(user)")
        await transport.setMode(.denied)
        check((try? await service.prepare()) == nil, "Rejected login cannot use cached wallpaper")
        check(await AppRuntime.backend.signedInUserId == nil, "Rejected refresh clears the local session")
        exit(failures == 0 ? 0 : 1)
    }
}
