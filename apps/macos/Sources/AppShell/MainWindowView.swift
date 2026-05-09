import Overlay
import SwiftUI

struct MainWindowView: View {
    @ObservedObject var router: AppRouter
    @ObservedObject var overlayController: PlaceholderOverlayController

    var body: some View {
        HStack(spacing: 0) {
            sidebar

            Divider()

            NavigationStack(path: $router.path) {
                RoutePlaceholderView(
                    route: router.selectedRoute,
                    overlayVisible: overlayController.isVisible,
                    onShowOverlay: overlayController.show,
                    onHideOverlay: overlayController.hide,
                    onResetOverlay: overlayController.resetPosition,
                    onPush: router.push
                )
                .navigationDestination(for: AppRoute.self) { route in
                    RoutePlaceholderView(
                        route: route,
                        overlayVisible: overlayController.isVisible,
                        onShowOverlay: overlayController.show,
                        onHideOverlay: overlayController.hide,
                        onResetOverlay: overlayController.resetPosition,
                        onPush: router.push
                    )
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text(router.selectedRoute.title)
                    .font(.headline)
            }
        }
        .frame(minWidth: 920, minHeight: 640)
    }

    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Mulberry")
                .font(.headline)
                .padding(.bottom, 12)

            ForEach(AppRoute.sidebarRoutes) { route in
                Button {
                    router.open(route)
                } label: {
                    Label(route.title, systemImage: iconName(for: route))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .background(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(router.selectedRoute == route ? Color.accentColor.opacity(0.18) : Color.clear)
                )
                .foregroundStyle(router.selectedRoute == route ? Color.primary : Color.secondary)
            }

            Spacer()
        }
        .padding(16)
        .frame(width: 220)
        .background(Color(nsColor: .controlBackgroundColor))
    }

    private func iconName(for route: AppRoute) -> String {
        switch route {
        case .canvasHome: "scribble"
        case .overlayStatus: "rectangle.on.rectangle"
        case .pairingHub: "person.2"
        case .streak: "flame"
        case .settings: "gearshape"
        default: "circle"
        }
    }
}

private struct RoutePlaceholderView: View {
    let route: AppRoute
    let overlayVisible: Bool
    let onShowOverlay: () -> Void
    let onHideOverlay: () -> Void
    let onResetOverlay: () -> Void
    let onPush: (AppRoute) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text(route.title)
                .font(.largeTitle.weight(.semibold))

            Text(description)
                .font(.body)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            controls

            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding(32)
        .navigationTitle(route.title)
    }

    @ViewBuilder
    private var controls: some View {
        switch route {
        case .canvasHome:
            Button("Open Canvas Surface") {
                onPush(.canvasSurface)
            }
        case .overlayStatus:
            HStack {
                Button(overlayVisible ? "Hide Overlay" : "Show Overlay") {
                    overlayVisible ? onHideOverlay() : onShowOverlay()
                }
                Button("Reset Position", action: onResetOverlay)
            }
        case .pairingHub:
            Button("Enter Invite Code") {
                onPush(.inviteCodeEntry)
            }
        case .settings:
            Button("Overlay Settings") {
                onPush(.overlayStatus)
            }
        default:
            EmptyView()
        }
    }

    private var description: String {
        switch route {
        case .bootstrap:
            "Placeholder for the startup resolver that will choose auth, onboarding, pairing, or canvas once real bootstrap services land."
        case .authLanding:
            "Placeholder for Google Sign-In."
        case .onboardingName:
            "Placeholder for name onboarding."
        case .onboardingDetails:
            "Placeholder for profile details onboarding."
        case .onboardingOverlay:
            "Placeholder for macOS overlay onboarding."
        case .pairingHub:
            "Placeholder for pairing status and invite controls."
        case .inviteCodeEntry:
            "Placeholder for entering a partner invite code."
        case .inviteAcceptance:
            "Placeholder for accepting an inbound invite."
        case .canvasHome:
            "Placeholder for the main Mulberry home surface. This will become the primary app UI that mirrors the mobile canvas experience."
        case .canvasSurface:
            "Placeholder for the full drawing surface."
        case .overlayStatus:
            "Overlay is \(overlayVisible ? "visible" : "hidden"). Phase 2 uses a transparent click-through placeholder window; production display selection and persistence land in Phase 3."
        case .overlayDisplay:
            "Placeholder for choosing the display that hosts the overlay."
        case .overlayHelp:
            "Placeholder for overlay troubleshooting and behavior notes."
        case .pairingHelp:
            "Placeholder for pairing help."
        case .streak:
            "Placeholder for streak summary. Current hardcoded state is --."
        case .settings:
            "Placeholder for app settings inside the main Mulberry window."
        }
    }
}
