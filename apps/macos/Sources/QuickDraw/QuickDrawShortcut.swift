import AppKit
import Carbon.HIToolbox

/// The key combination that opens Quick Draw, anywhere in macOS.
public struct QuickDrawShortcut: Codable, Equatable, Sendable {
    /// The virtual key code, as `NSEvent.keyCode` reports it.
    public let keyCode: UInt16
    /// Command, Control, Option and Shift, as `NSEvent.ModifierFlags` raw values.
    public let modifierFlags: UInt
    /// The key's own character, for menus and display.
    public let key: String

    public init(keyCode: UInt16, modifierFlags: NSEvent.ModifierFlags, key: String) {
        self.keyCode = keyCode
        self.modifierFlags = modifierFlags.intersection(Self.allowedModifiers).rawValue
        self.key = key.lowercased()
    }

    /// Control-Command-M.
    public static let standard = QuickDrawShortcut(keyCode: UInt16(kVK_ANSI_M), modifierFlags: [.control, .command], key: "m")

    static let allowedModifiers: NSEvent.ModifierFlags = [.control, .option, .shift, .command]

    public var modifiers: NSEvent.ModifierFlags { NSEvent.ModifierFlags(rawValue: modifierFlags) }

    /// A shortcut must hold Command, Control or Option, so ordinary typing never triggers it.
    public var isUsable: Bool { !modifiers.intersection([.command, .control, .option]).isEmpty }

    /// The shortcut as macOS menus write it, such as ⌃⌘M.
    public var displayString: String {
        var symbols = ""
        if modifiers.contains(.control) { symbols += "⌃" }
        if modifiers.contains(.option) { symbols += "⌥" }
        if modifiers.contains(.shift) { symbols += "⇧" }
        if modifiers.contains(.command) { symbols += "⌘" }
        return symbols + keyName
    }

    private var keyName: String {
        switch Int(keyCode) {
        case kVK_Space: "Space"
        case kVK_Return: "↩"
        case kVK_Tab: "⇥"
        case kVK_Delete: "⌫"
        case kVK_ForwardDelete: "⌦"
        case kVK_LeftArrow: "←"
        case kVK_RightArrow: "→"
        case kVK_UpArrow: "↑"
        case kVK_DownArrow: "↓"
        case kVK_F1: "F1"
        case kVK_F2: "F2"
        case kVK_F3: "F3"
        case kVK_F4: "F4"
        case kVK_F5: "F5"
        case kVK_F6: "F6"
        case kVK_F7: "F7"
        case kVK_F8: "F8"
        case kVK_F9: "F9"
        case kVK_F10: "F10"
        case kVK_F11: "F11"
        case kVK_F12: "F12"
        default: key.uppercased()
        }
    }

    var carbonModifiers: UInt32 {
        var result = 0
        if modifiers.contains(.command) { result |= cmdKey }
        if modifiers.contains(.control) { result |= controlKey }
        if modifiers.contains(.option) { result |= optionKey }
        if modifiers.contains(.shift) { result |= shiftKey }
        return UInt32(result)
    }
}
