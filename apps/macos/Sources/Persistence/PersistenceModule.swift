import Foundation
import ProductCore

public enum PersistenceModule {
    public static let name = "Persistence"
}

/// What the Mac keeps between launches apart from the canvas, which ProductCore's
/// `DrawingStore` saves in `drawingsDirectory` with its unsent edits.
public struct MacLocalStore: @unchecked Sendable {
    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public static var drawingsDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Mulberry", isDirectory: true)
            .appendingPathComponent("Drawings", isDirectory: true)
    }

    /// The last account state the server sent, so the menu and overlay can show it at launch
    /// and while offline. Kept per account, so it never shows another person's pairing.
    public func cachedBootstrap(userId: String) -> BootstrapState? {
        defaults.data(forKey: bootstrapKey(userId)).flatMap { try? JSONDecoder().decode(BootstrapState.self, from: $0) }
    }

    public func cacheBootstrap(_ state: BootstrapState) {
        guard let userId = state.userId, let data = try? JSONEncoder().encode(state) else { return }
        defaults.set(data, forKey: bootstrapKey(userId))
    }

    public func clearBootstrap(userId: String) {
        defaults.removeObject(forKey: bootstrapKey(userId))
    }

    /// The account was deleted: every setting kept for it is keyed by its id, so all of them go.
    public func forgetAccount(userId: String) {
        for key in defaults.dictionaryRepresentation().keys where key.contains(userId) {
            defaults.removeObject(forKey: key)
        }
    }

    /// Identifies this Mac's overlay to the backend across launches.
    public var installationID: String {
        if let existing = defaults.string(forKey: "installationID") {
            return existing
        }
        let created = UUID().uuidString.lowercased()
        defaults.set(created, forKey: "installationID")
        return created
    }

    private func bootstrapKey(_ userId: String) -> String {
        "bootstrap.\(userId)"
    }
}
