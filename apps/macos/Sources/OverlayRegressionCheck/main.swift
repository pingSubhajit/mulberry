import AppKit
import Darwin
import Foundation
import Overlay

@MainActor
private func checkQuickDrawExitKeepsPassiveOverlayVisible() -> Bool {
    _ = NSApplication.shared
    let suiteName = "OverlayRegressionCheck-\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suiteName)!
    defaults.removePersistentDomain(forName: suiteName)

    let controller = OverlayController(settingsStore: OverlaySettingsStore(defaults: defaults))
    guard controller.isVisible == false else {
        fputs("Expected overlay to start hidden for isolated settings.\n", stderr)
        return false
    }
    guard controller.enterQuickDraw(fullscreenGuard: .allowed) else {
        fputs("Expected Quick Draw to enter when fullscreen guard allows it.\n", stderr)
        return false
    }
    guard controller.isVisible, controller.isQuickDrawActive else {
        fputs("Expected overlay to be visible and interactive in Quick Draw.\n", stderr)
        return false
    }

    controller.exitQuickDraw()

    guard controller.isVisible, controller.isQuickDrawActive == false else {
        fputs("Expected passive overlay to remain visible after exiting Quick Draw.\n", stderr)
        return false
    }
    return true
}

let passed = MainActor.assumeIsolated {
    checkQuickDrawExitKeepsPassiveOverlayVisible()
}
if !passed {
    exit(1)
}
