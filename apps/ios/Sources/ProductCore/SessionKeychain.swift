import Foundation
import Security

/// Shared by foreground app work and its App Intents. AfterFirstUnlock lets an
/// authorized Shortcut refresh the wallpaper while the phone is locked.
public struct SessionKeychain: SessionTokenStore {
    private let service: String
    private let account = "backend-session"
    public init(service: String) { self.service = service }

    public func load() throws -> SessionTokens? {
        var query = identity
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw KeychainFailure(status: status) }
        return try JSONDecoder().decode(SessionTokens.self, from: data)
    }

    public func save(_ tokens: SessionTokens) throws {
        let data = try JSONEncoder().encode(tokens)
        let attributes: [String: Any] = [kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        var status = SecItemUpdate(identity as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd(identity.merging(attributes) { _, new in new } as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw KeychainFailure(status: status) }
    }

    public func clear() throws {
        let status = SecItemDelete(identity as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw KeychainFailure(status: status) }
    }

    private var identity: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }
}

private struct KeychainFailure: LocalizedError {
    let status: OSStatus
    var errorDescription: String? { "Mulberry could not access your saved sign-in. Please unlock your phone and try again." }
}
