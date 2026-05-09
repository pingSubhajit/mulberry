import Foundation

public enum AppRoute: String, CaseIterable, Identifiable, Hashable, Sendable {
    case bootstrap
    case authLanding
    case onboardingName
    case onboardingDetails
    case onboardingOverlay
    case pairingHub
    case inviteCodeEntry
    case inviteAcceptance
    case canvasHome
    case canvasSurface
    case overlayStatus
    case overlayDisplay
    case overlayHelp
    case pairingHelp
    case streak
    case settings

    public var id: String { rawValue }

    var title: String {
        switch self {
        case .bootstrap: "Bootstrap"
        case .authLanding: "Sign In"
        case .onboardingName: "Name"
        case .onboardingDetails: "Profile"
        case .onboardingOverlay: "Overlay Setup"
        case .pairingHub: "Pairing"
        case .inviteCodeEntry: "Invite Code"
        case .inviteAcceptance: "Accept Invite"
        case .canvasHome: "Canvas"
        case .canvasSurface: "Canvas Surface"
        case .overlayStatus: "Overlay"
        case .overlayDisplay: "Display"
        case .overlayHelp: "Overlay Help"
        case .pairingHelp: "Pairing Help"
        case .streak: "Streak"
        case .settings: "Settings"
        }
    }

    static let sidebarRoutes: [AppRoute] = [
        .canvasHome,
        .overlayStatus,
        .pairingHub,
        .streak,
        .settings
    ]
}
