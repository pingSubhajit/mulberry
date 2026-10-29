import SwiftUI

/// Where a tapped notification leads. Like quick actions, a route from a cold launch waits until
/// sign-in and onboarding have finished.
enum AppRoute: Equatable {
    case canvas, wallpaperSetup, settings

    /// The route for a notification, from the type the server or the app put in it. Mulberry's
    /// own wallpaper and setup-test notices carry no type and open nothing special.
    init?(notificationType type: String?) {
        switch type {
        case "CANVAS_UPDATED", "PAIRING_CONFIRMED", "PAIRING_DISCONNECTED", "DRAW_REMINDER": self = .canvas
        case "CANVAS_NUDGE": self = .wallpaperSetup
        case DoodlesOffReminders.type: self = .settings
        default: return nil
        }
    }
}

@MainActor
final class AppRoutes: ObservableObject {
    static let shared = AppRoutes()
    @Published var pending: AppRoute?
}

