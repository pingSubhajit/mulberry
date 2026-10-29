import SwiftUI

// Isolated visual harness for Settings. It restores a cached sample account and
// never signs in. Launch arguments: "unpaired", "hidden" (the partner can't see
// new drawings), "bottom", or a page: "profile", "partner", "about".
private let previewUser = "00000000-0000-4000-8000-00000000c0de"
private struct PreviewTokens: SessionTokenStore {
    func load() throws -> SessionTokens? { .init(accessToken: "preview", refreshToken: "preview", userId: previewUser) }
    func save(_ tokens: SessionTokens) throws {}
    func clear() throws {}
}
@MainActor enum AppRuntime {
    static let directory = FileManager.default.temporaryDirectory.appendingPathComponent("SettingsPreview")
    static let drawings = DrawingStore(directory: directory.appendingPathComponent("Drawings"))
    // Nothing listens here, so the restored account is never replaced or signed out.
    static let backend = try! BackendClient(baseURL: URL(string: "http://127.0.0.1:9/")!, tokenStore: PreviewTokens(),
                                            transport: URLSessionTransport(session: .shared), shortTransport: URLSessionTransport(session: .shared))
    static func api() throws -> BackendClient { backend }
}
enum CrashReporting {
    static func testCrash() -> Never { fatalError("Preview test crash") }
    static func testError() {}
}
@MainActor enum GoogleAuthentication {
    static func signIn() async throws -> String { throw BackendError.signedOut }
    static func signOut() {}
    static func isCancellation(_ error: Error) -> Bool { false }
}
@MainActor final class PushRegistration {
    static let shared = PushRegistration()
    static let deviceId = "settings-preview"
    func signOut() async {}
}
@MainActor final class HomeQuickActions {
    static let shared = HomeQuickActions()
    var pending: Int?
}

@main struct SettingsPreview: App {
    var body: some Scene { WindowGroup { SettingsPreviewRoot().tint(MulberryDesign.accent) } }
}

private struct SettingsPreviewRoot: View {
    private let arguments = ProcessInfo.processInfo.arguments
    @StateObject private var model = MulberryModel()
    @StateObject private var library = WallpaperLibrary()
    @State private var open = true

    var body: some View {
        MulberryDesign.canvas.ignoresSafeArea()
            .sheet(isPresented: $open) {
                Group {
                    if model.bootstrap == nil { ProgressView() }
                    else if arguments.contains("profile") { NavigationStack { ProfileSettingsView(model: model) } }
                    else if arguments.contains("partner") { NavigationStack { PartnerSettingsView(model: model) } }
                    else if arguments.contains("about") { NavigationStack { AboutView() } }
                    else { SettingsView(model: model, library: library) {}.defaultScrollAnchor(arguments.contains("bottom") ? .bottom : nil) }
                }
                .interactiveDismissDisabled()
            }
            .task {
                UserDefaults.standard.set(try? JSONEncoder().encode(sample), forKey: "bootstrap.\(previewUser)")
                await model.restore()
                library.load(user: previewUser)
                // The unreachable backend reports an error the real app would not show.
                try? await Task.sleep(for: .seconds(1.5))
                model.errorMessage = nil
            }
    }

    private var sample: BootstrapState {
        let paired = !arguments.contains("unpaired")
        let status: JSONValue = .object(["updatedAt": .string("2026-10-28T09:00:00Z"),
                                         "wallpaperSyncEnabled": .bool(!arguments.contains("hidden")),
                                         "wallpaperSelectedOnHome": .bool(false), "wallpaperSelectedOnLock": .bool(true),
                                         "canSeeLatestDrawings": .bool(true), "hasEverBeenAbleToSee": .bool(true)])
        let presence: JSONValue = .object(["canSeeLatestDrawings": .bool(true), "surfaces": .array([])])
        let state: JSONValue = .object([
            "authStatus": .string("SIGNED_IN"), "onboardingCompleted": .bool(true), "hasWallpaperConfigured": .bool(true),
            "canvasStrokeRenderMode": .string("dry"), "userId": .string(previewUser), "userEmail": .string("subhajit@example.com"),
            "userDisplayName": .string("Subhajit"), "partnerDisplayName": .string("Ananya"),
            "partnerWallpaperStatus": paired ? status : .null, "anniversaryDate": .string("2023-02-14"),
            "pairedAt": paired ? .string("2025-06-02T10:00:00Z") : .null, "currentStreakDays": .number(paired ? 12 : 0),
            "pairingStatus": .string(paired ? "PAIRED" : "UNPAIRED"), "pairSessionId": paired ? .string("preview-pair") : .null,
            "ownPresence": presence, "partnerPresence": presence,
        ])
        return try! JSONDecoder().decode(BootstrapState.self, from: JSONEncoder().encode(state))
    }
}
