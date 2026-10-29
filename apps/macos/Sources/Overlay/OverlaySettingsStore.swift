import AppKit
import Foundation

public struct OverlaySettings: Equatable, Sendable {
    public var isVisible: Bool
    public var selectedDisplayID: String?
    public var relativeFrame: CGRect?

    public static let defaults = OverlaySettings(
        isVisible: false,
        selectedDisplayID: nil,
        relativeFrame: nil
    )
}

public struct OverlayDisplay: Identifiable, Equatable, Sendable {
    public let id: String
    public let name: String
    public let frameDescription: String
    let screenFrame: CGRect
    let visibleFrame: CGRect
}

public struct OverlayHotKeyStatus: Equatable, Sendable {
    public let isRegistered: Bool
    /// Why the shortcut is not working, when it is not.
    public let problem: String?

    public static let notRegistered = OverlayHotKeyStatus(isRegistered: false, problem: nil)

    public static func registered(_ shortcut: String) -> OverlayHotKeyStatus {
        OverlayHotKeyStatus(isRegistered: true, problem: nil)
    }

    public static func failed(_ shortcut: String, code: Int32) -> OverlayHotKeyStatus {
        // eventHotKeyExistsErr: another app registered the same combination first.
        OverlayHotKeyStatus(
            isRegistered: false,
            problem: code == -9878 ? "Another app already uses \(shortcut)." : "\(shortcut) could not be set up (\(code))."
        )
    }
}

public final class OverlaySettingsStore {
    private enum Key {
        static let isVisible = "mac.overlay.isVisible"
        static let selectedDisplayID = "mac.overlay.selectedDisplayID"
        static let frameX = "mac.overlay.frame.x"
        static let frameY = "mac.overlay.frame.y"
        static let frameWidth = "mac.overlay.frame.width"
        static let frameHeight = "mac.overlay.frame.height"
        static let opacity = "mac.overlay.opacity"
    }

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public func load() -> OverlaySettings {
        let selectedDisplayID = defaults.string(forKey: Key.selectedDisplayID)
        let relativeFrame: CGRect?
        if defaults.object(forKey: Key.frameX) != nil,
           defaults.object(forKey: Key.frameY) != nil,
           defaults.object(forKey: Key.frameWidth) != nil,
           defaults.object(forKey: Key.frameHeight) != nil {
            relativeFrame = CGRect(
                x: defaults.double(forKey: Key.frameX),
                y: defaults.double(forKey: Key.frameY),
                width: defaults.double(forKey: Key.frameWidth),
                height: defaults.double(forKey: Key.frameHeight)
            )
        } else {
            relativeFrame = nil
        }

        return OverlaySettings(
            isVisible: defaults.bool(forKey: Key.isVisible),
            selectedDisplayID: selectedDisplayID,
            relativeFrame: relativeFrame
        )
    }

    /// How strongly the drawing shows on the desktop, from 0.2 to 1. Quick Draw always shows it fully.
    public func loadOpacity() -> Double {
        defaults.object(forKey: Key.opacity) == nil ? 1 : min(1, max(0.2, defaults.double(forKey: Key.opacity)))
    }

    public func saveOpacity(_ opacity: Double) {
        defaults.set(opacity, forKey: Key.opacity)
    }

    public func save(_ settings: OverlaySettings) {
        defaults.set(settings.isVisible, forKey: Key.isVisible)
        if let selectedDisplayID = settings.selectedDisplayID {
            defaults.set(selectedDisplayID, forKey: Key.selectedDisplayID)
        } else {
            defaults.removeObject(forKey: Key.selectedDisplayID)
        }

        if let relativeFrame = settings.relativeFrame {
            defaults.set(relativeFrame.origin.x, forKey: Key.frameX)
            defaults.set(relativeFrame.origin.y, forKey: Key.frameY)
            defaults.set(relativeFrame.size.width, forKey: Key.frameWidth)
            defaults.set(relativeFrame.size.height, forKey: Key.frameHeight)
        } else {
            defaults.removeObject(forKey: Key.frameX)
            defaults.removeObject(forKey: Key.frameY)
            defaults.removeObject(forKey: Key.frameWidth)
            defaults.removeObject(forKey: Key.frameHeight)
        }
    }
}
