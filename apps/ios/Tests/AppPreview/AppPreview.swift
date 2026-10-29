import SwiftUI

// The whole app shell with a cached sample account, for checking layouts on any
// simulator without signing in. Launch arguments: "unpaired", "signin" (signed out),
// "wallpapers" (the first-run wallpaper picker), "reactions=HEART:3,KISS:1" (the partner's
// reactions arriving, once). Nothing is sent to a real server. To rotate an
// iPad, use T3's agent-device: `agent-device orientation landscape-left`.
private let previewUser = "00000000-0000-4000-8000-00000000c0de"
private let arguments = ProcessInfo.processInfo.arguments

private struct PreviewTokens: SessionTokenStore {
    func load() throws -> SessionTokens? {
        arguments.contains("signin") ? nil : .init(accessToken: "preview", refreshToken: "preview", userId: previewUser)
    }
    func save(_ tokens: SessionTokens) throws {}
    func clear() throws {}
}
/// Answers the reaction endpoints with the batch the launch arguments ask for; every other
/// request fails as if offline.
private actor PreviewTransport: BackendTransport {
    private var pending: String? = {
        guard let value = arguments.first(where: { $0.hasPrefix("reactions=") })?.dropFirst(10) else { return nil }
        var counts: [String: Int] = [:]
        for part in value.split(separator: ",") {
            let pair = part.split(separator: ":")
            counts[pair[0].lowercased() + "Count"] = pair.count > 1 ? Int(pair[1]) ?? 1 : 1
        }
        let fields = ["heart", "hug", "kiss", "smile", "laugh", "sparkle"].map { "\"\($0)Count\":\(counts[$0 + "Count"] ?? 0)" }
        return "{\"pending\":{\"generation\":1,\(fields.joined(separator: ","))}}"
    }()

    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let body: String
        switch request.url?.path {
        case "/reactions/pending": body = pending ?? #"{"pending":null}"#
        case "/reactions/lease": body = #"{"status":"CLAIMED"}"#
        case "/reactions/confirm": pending = nil; body = #"{"ok":true}"#
        case "/reactions/send": body = #"{"ok":true}"#
        default: throw URLError(.cannotConnectToHost)
        }
        return (Data(body.utf8), HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
    }
}

@MainActor enum AppRuntime {
    static let directory = FileManager.default.temporaryDirectory.appendingPathComponent("AppPreview")
    static let drawings = DrawingStore(directory: directory.appendingPathComponent("Drawings"))
    // Nothing listens here, so the restored account is never replaced or signed out.
    static let backend = try! BackendClient(baseURL: URL(string: "http://127.0.0.1:9/")!, tokenStore: PreviewTokens(),
                                            transport: PreviewTransport())
    static func api() throws -> BackendClient { backend }
}
@MainActor enum GoogleAuthentication {
    static func signIn() async throws -> String { throw BackendError.signedOut }
    static func handle(_ url: URL) -> Bool { false }
    static func signOut() {}
    static func isCancellation(_ error: Error) -> Bool { false }
}
@MainActor final class PushRegistration {
    static let shared = PushRegistration()
    static let deviceId = "app-preview"
    func refresh() async {}
    func signOut() async {}
}

@main struct AppPreview: App {
    @StateObject private var model = MulberryModel()
    @StateObject private var wallpapers = WallpaperLibrary()

    init() {
        UserDefaults.standard.set(!arguments.contains("wallpapers"), forKey: "wallpaperVisited.\(previewUser)")
        if arguments.contains("signin") {
            UserDefaults.standard.removeObject(forKey: "bootstrap.\(previewUser)")
        } else {
            UserDefaults.standard.set(try? JSONEncoder().encode(Self.sample), forKey: "bootstrap.\(previewUser)")
        }
    }

    var body: some Scene {
        WindowGroup {
            root
        }
    }

    private var root: some View {
        MulberryRootView(model: model, wallpapers: wallpapers)
            .tint(MulberryDesign.accent)
            .foregroundStyle(MulberryDesign.ink)
    }

    private static var sample: BootstrapState {
        let paired = !arguments.contains("unpaired")
        let status: JSONValue = .object(["updatedAt": .string("2026-10-28T09:00:00Z"), "wallpaperSyncEnabled": .bool(true),
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
