import Auth
import Overlay
import SwiftUI

struct MainWindowView: View {
    @ObservedObject var router: AppRouter
    @ObservedObject var authController: AuthSessionController
    @ObservedObject var overlayController: OverlayController

    var body: some View {
        HStack(spacing: 0) {
            sidebar

            Divider()

            NavigationStack(path: $router.path) {
                RoutePlaceholderView(
                    route: router.selectedRoute,
                    authController: authController,
                    overlayController: overlayController,
                    onOpen: openRoute,
                    onPush: router.push
                )
                .navigationDestination(for: AppRoute.self) { route in
                    RoutePlaceholderView(
                        route: route,
                        authController: authController,
                        overlayController: overlayController,
                        onOpen: openRoute,
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

            ForEach(sidebarRoutes) { route in
                Button {
                    openRoute(route)
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

    private var sidebarRoutes: [AppRoute] {
        authController.state.isSignedIn ? AppRoute.sidebarRoutes : [.authLanding, .settings]
    }

    private func openRoute(_ route: AppRoute) {
        if authController.state.isSignedIn || route == .settings || route == .authLanding {
            router.open(route)
        } else {
            router.open(.authLanding)
        }
    }

    private func iconName(for route: AppRoute) -> String {
        switch route {
        case .authLanding: "person.crop.circle"
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
    @ObservedObject var authController: AuthSessionController
    @ObservedObject var overlayController: OverlayController
    let onOpen: (AppRoute) -> Void
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
        case .authLanding:
            VStack(alignment: .leading, spacing: 12) {
                Text(authController.state.statusDetail)
                    .font(.body)
                    .foregroundStyle(.secondary)

                Button {
                    Task {
                        await authController.signIn()
                        if authController.state.isSignedIn {
                            onOpen(.canvasHome)
                        }
                    }
                } label: {
                    Label(authController.state.isBusy ? "Signing In..." : "Sign in with Google", systemImage: "person.crop.circle.badge.checkmark")
                }
                .disabled(authController.state.isBusy)

                if case let .failed(failure) = authController.state {
                    Text(failure.message)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        case .overlayStatus:
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Button(overlayController.isVisible ? "Hide Overlay" : "Show Overlay") {
                        overlayController.isVisible ? overlayController.hide() : overlayController.show()
                    }
                    Button("Reset Position", action: overlayController.resetPosition)
                }

                Divider()

                Text("Selected display: \(overlayController.selectedDisplayName)")
                    .font(.headline)
                let selectedDisplayID = overlayController.selectedDisplayID ?? overlayController.displays.first?.id
                ForEach(overlayController.displays) { display in
                    Button {
                        overlayController.selectDisplay(id: display.id)
                    } label: {
                        HStack {
                            Image(systemName: selectedDisplayID == display.id ? "checkmark.circle.fill" : "circle")
                            VStack(alignment: .leading) {
                                Text(display.name)
                                Text(display.frameDescription)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }

                Divider()

                Text("Frame: \(overlayController.currentFrameDescription)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text("Hotkey: \(overlayController.hotKeyStatus.message)")
                    .font(.caption)
                    .foregroundStyle(overlayController.hotKeyStatus.isRegistered ? Color.secondary : Color.orange)
            }
        case .pairingHub:
            Button("Enter Invite Code") {
                onPush(.inviteCodeEntry)
            }
        case .settings:
            VStack(alignment: .leading, spacing: 12) {
                Text("Account: \(authController.state.statusTitle)")
                    .font(.headline)
                Text(authController.state.statusDetail)
                    .font(.caption)
                    .foregroundStyle(.secondary)

                HStack {
                    Button("Overlay Settings") {
                        onPush(.overlayStatus)
                    }
                    if authController.state.isSignedIn {
                        Button("Sign Out") {
                            Task {
                                await authController.signOut()
                                onOpen(.authLanding)
                            }
                        }
                    }
                }
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
            "Use your Google account to connect this Mac to Mulberry."
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
            "Overlay is \(overlayController.isVisible ? "visible" : "hidden"). Passive mode is transparent, click-through, below normal windows, and pinned to the selected display across Spaces."
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
