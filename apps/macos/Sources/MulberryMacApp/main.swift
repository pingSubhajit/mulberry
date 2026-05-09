import AppKit
import AppShell

@main
enum MulberryMacMain {
    @MainActor private static var delegate: MulberryApplicationDelegate?

    @MainActor
    static func main() {
        let delegate = MulberryApplicationDelegate()
        Self.delegate = delegate

        let application = NSApplication.shared
        application.delegate = delegate
        application.setActivationPolicy(.accessory)
        application.run()
    }
}
