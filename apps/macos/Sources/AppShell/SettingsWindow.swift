import AppKit
import Auth
import Overlay
import ProductCore
import QuickDraw
import SwiftUI
import Sync

/// The panes of Settings, in the sidebar's order: you, then this Mac, then the two of you.
enum SettingsPane: String, CaseIterable, Identifiable, Hashable {
    case account, general, desktop, partner, advanced, about

    var id: Self { self }

    var title: String {
        switch self {
        case .account: "Account"
        case .general: "General"
        case .desktop: "Desktop"
        case .partner: "Partner"
        case .advanced: "Advanced"
        case .about: "About"
        }
    }

    var symbol: String {
        switch self {
        case .account: "person.fill"
        case .general: "gearshape.fill"
        case .desktop: "menubar.dock.rectangle"
        case .partner: "heart.fill"
        case .advanced: "wrench.and.screwdriver.fill"
        case .about: "info"
        }
    }

    /// The iPhone's tile colours for the same rows.
    var tint: Color {
        switch self {
        case .account: .blue
        case .general: .gray
        case .desktop: .teal
        case .partner: .pink
        case .advanced: Color(white: 0.45)
        case .about: .indigo
        }
    }

    /// What each pane holds, so searching for a setting finds its pane.
    var keywords: [String] {
        switch self {
        case .account: ["account", "name", "photo", "email", "google", "log out", "sign out", "sign in"]
        case .general: ["login", "startup", "menu bar", "dock", "shortcut", "hotkey", "quick draw", "keyboard", "canvas", "brush", "dry", "strokes"]
        case .desktop: ["desktop", "overlay", "display", "screen", "visibility", "opacity", "position", "reset"]
        case .partner: ["partner", "anniversary", "unpair", "pairing", "wallpaper", "streak", "together"]
        case .advanced: ["sync", "revision", "recover", "offline", "connection"]
        case .about: ["about", "version", "build", "feedback", "privacy", "terms", "website", "source"]
        }
    }

    func matches(_ query: String) -> Bool {
        let query = query.trimmingCharacters(in: .whitespaces).lowercased()
        return query.isEmpty || title.lowercased().contains(query) || keywords.contains { $0.contains(query) }
    }
}

/// A page pushed over a pane, as System Settings pushes a detail page with a back button.
enum SettingsSubpage: Hashable {
    case feedback

    var title: String {
        switch self {
        case .feedback: "Send Feedback"
        }
    }
}

/// Which pane Settings shows, kept by the window so the menu bar and toolbar can open a pane.
@MainActor
final class SettingsNavigation: ObservableObject {
    /// Choosing a pane, even the one showing, returns to its first page.
    @Published var pane: SettingsPane = .general {
        didSet { subpage = nil }
    }
    @Published var subpage: SettingsSubpage?
}

/// What the panes read and change. One object, so the window's view takes a single dependency.
@MainActor
struct SettingsEnvironment {
    let authController: AuthSessionController
    let appStateController: AppStateController
    let syncController: CanvasSyncController
    let overlayController: OverlayController
    let preferences: MacPreferences
    let launchAtLogin: LaunchAtLogin
    let account: AccountSettings
    let streaks: StreakModel
    /// Recording a new Quick Draw shortcut pauses the current one.
    let recordingShortcut: (Bool) -> Void
}

/// Settings in its own window: a sidebar of panes and cards of rows, over the same smoky glass as
/// the canvas window.
@MainActor
final class SettingsWindowController: NSWindowController, NSWindowDelegate {
    private let navigation = SettingsNavigation()
    private let onClose: () -> Void

    init(environment: SettingsEnvironment, onClose: @escaping () -> Void) {
        self.onClose = onClose
        let hostingController = NSHostingController(rootView: SettingsView(
            navigation: navigation,
            environment: environment,
            authController: environment.authController,
            appStateController: environment.appStateController
        ))
        hostingController.sizingOptions = []
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 820, height: 620),
            styleMask: [.titled, .closable, .miniaturizable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        window.contentViewController = hostingController
        window.title = "Settings"
        window.titleVisibility = .hidden
        // Clear, so the frosted glass behind the panes shows the desktop softly.
        window.isOpaque = false
        window.backgroundColor = .clear
        window.setContentSize(NSSize(width: 820, height: 620))
        window.collectionBehavior.insert(.fullScreenNone)
        window.standardWindowButton(.zoomButton)?.isHidden = true
        window.isReleasedWhenClosed = false
        window.center()
        // Remembers where the window was, at its one size.
        window.setFrameAutosaveName("MulberrySettings")
        super.init(window: window)
        window.delegate = self
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func show(_ pane: SettingsPane?, subpage: SettingsSubpage? = nil) {
        if let pane { navigation.pane = pane }
        if let subpage { navigation.subpage = subpage }
        showWindow(nil)
        window?.makeKeyAndOrderFront(nil)
        // No field takes the keyboard until it is clicked, as in System Settings.
        DispatchQueue.main.async { [weak self] in self?.window?.makeFirstResponder(nil) }
    }

    var isVisible: Bool { window?.isVisible == true }

    func windowWillClose(_ notification: Notification) {
        onClose()
    }
}

private struct SettingsView: View {
    @ObservedObject var navigation: SettingsNavigation
    let environment: SettingsEnvironment
    @ObservedObject var authController: AuthSessionController
    @ObservedObject var appStateController: AppStateController
    @State private var query = ""

    private var bootstrap: BootstrapState? { appStateController.loadState.bootstrap }

    var body: some View {
        HStack(spacing: 0) {
            sidebar
                .frame(width: 236)
            Rectangle()
                .fill(.primary.opacity(0.08))
                .frame(width: 1)
                .ignoresSafeArea()
            ZStack {
                if let subpage = navigation.subpage {
                    subpageView(subpage)
                        .transition(.move(edge: .trailing).combined(with: .opacity))
                } else {
                    detail
                        .id(navigation.pane)
                        .transition(.opacity)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .clipped()
        }
        .animation(.easeOut(duration: 0.15), value: navigation.pane)
        .animation(.smooth(duration: 0.28), value: navigation.subpage)
        .background { SettingsGlass() }
        .ignoresSafeArea()
        .tint(mulberryRed)
    }

    // MARK: Sidebar

    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 0) {
            SettingsSearchField(text: $query)
                .padding(.horizontal, 12)
                .padding(.top, 52)
                .padding(.bottom, 14)
            ScrollView {
                VStack(alignment: .leading, spacing: 2) {
                    if SettingsPane.account.matches(query) {
                        accountRow
                            .padding(.bottom, 12)
                    }
                    ForEach([SettingsPane.general, .desktop, .partner].filter { $0.matches(query) }) { pane in
                        paneRow(pane)
                    }
                    let lower = [SettingsPane.advanced, .about].filter { $0.matches(query) }
                    if !lower.isEmpty {
                        Spacer().frame(height: 14)
                        ForEach(lower) { pane in paneRow(pane) }
                    }
                    if SettingsPane.allCases.allSatisfy({ !$0.matches(query) }) {
                        Text("No Results")
                            .font(.system(size: 13))
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 10)
                            .padding(.top, 8)
                    }
                }
                .padding(.horizontal, 10)
                .padding(.bottom, 16)
            }
            .scrollIndicators(.never)
        }
        .onChange(of: query) { _, query in
            // The first match opens as you type, as in System Settings.
            if !navigation.pane.matches(query), let first = SettingsPane.allCases.first(where: { $0.matches(query) }) {
                navigation.pane = first
            }
        }
    }

    private var accountRow: some View {
        SidebarButton(selected: navigation.pane == .account) {
            navigation.pane = .account
        } label: {
            HStack(spacing: 11) {
                PersonAvatar(url: bootstrap?.userPhotoUrl, name: bootstrap?.userDisplayName, size: 38)
                VStack(alignment: .leading, spacing: 1) {
                    Text(authController.state.isSignedIn ? bootstrap?.userDisplayName?.nonEmpty ?? "Mulberry" : "Sign In")
                        .font(.system(size: 14, weight: .semibold))
                        .lineLimit(1)
                    Text("Account")
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.vertical, 4)
        }
    }

    private func paneRow(_ pane: SettingsPane) -> some View {
        SidebarButton(selected: navigation.pane == pane) {
            navigation.pane = pane
        } label: {
            HStack(spacing: 10) {
                SettingsTile(symbol: pane.symbol, tint: pane.tint)
                Text(pane.title)
                    .font(.system(size: 14, weight: .medium))
                if pane == .partner, bootstrap?.isPaired == false {
                    Spacer()
                    Text("Not Paired")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    // MARK: Detail

    @ViewBuilder private var detail: some View {
        switch navigation.pane {
        case .account:
            AccountPane(environment: environment, authController: authController, appStateController: appStateController, account: environment.account)
        case .general:
            GeneralPane(
                preferences: environment.preferences,
                launchAtLogin: environment.launchAtLogin,
                overlayController: environment.overlayController,
                appStateController: appStateController,
                account: environment.account,
                recordingShortcut: environment.recordingShortcut
            )
        case .desktop:
            DesktopPane(overlayController: environment.overlayController)
        case .partner:
            PartnerPane(appStateController: appStateController, account: environment.account, streaks: environment.streaks)
        case .advanced:
            AdvancedPane(syncController: environment.syncController)
        case .about:
            AboutPane { navigation.subpage = .feedback }
        }
    }
}

/// The canvas window's frosted glass, smoky in Dark Mode.
private struct SettingsGlass: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        Rectangle()
            .fill(colorScheme == .dark ? Color.black.opacity(0.5) : Color(nsColor: .windowBackgroundColor).opacity(0.6))
            .glassEffect(.regular, in: Rectangle())
            .ignoresSafeArea()
    }
}

extension SettingsView {
    /// A pushed page: back and its title in the title bar's row, the page filling the rest.
    fileprivate func subpageView(_ subpage: SettingsSubpage) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 6) {
                Button { navigation.subpage = nil } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 14, weight: .semibold))
                        .frame(width: 28, height: 28)
                        .contentShape(.rect(cornerRadius: 7, style: .continuous))
                }
                .buttonStyle(SettingsBackButtonStyle())
                .keyboardShortcut("[", modifiers: .command)
                .help("Back to \(navigation.pane.title)")
                .accessibilityLabel("Back to \(navigation.pane.title)")
                Text(subpage.title)
                    .font(.system(size: 15, weight: .semibold))
                    .accessibilityAddTraits(.isHeader)
                Spacer()
            }
            .padding(.horizontal, 16)
            .frame(height: 52)
            switch subpage {
            case .feedback:
                FeedbackPage(api: { authController.api })
            }
        }
    }
}

/// The back chevron: quiet until the pointer is over it, as in System Settings.
private struct SettingsBackButtonStyle: ButtonStyle {
    @State private var hovered = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(.secondary)
            .background(.primary.opacity(configuration.isPressed ? 0.12 : hovered ? 0.07 : 0), in: .rect(cornerRadius: 7, style: .continuous))
            .onHover { hovered = $0 }
            .animation(.easeOut(duration: 0.12), value: hovered)
    }
}

/// A sidebar row: a soft highlight on hover, a firmer one when selected.
private struct SidebarButton<Label: View>: View {
    let selected: Bool
    let action: () -> Void
    @ViewBuilder let label: () -> Label
    @State private var hovered = false

    var body: some View {
        Button(action: action) {
            label()
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 8)
                .padding(.vertical, 6)
                .background(
                    .primary.opacity(selected ? 0.1 : hovered ? 0.05 : 0),
                    in: .rect(cornerRadius: 9, style: .continuous)
                )
                .contentShape(.rect(cornerRadius: 9, style: .continuous))
        }
        .buttonStyle(.plain)
        .onHover { hovered = $0 }
        .animation(.easeOut(duration: 0.12), value: hovered)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

private struct SettingsSearchField: View {
    @Binding var text: String
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: 7) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(.secondary)
            TextField("Search Settings", text: $text)
                .textFieldStyle(.plain)
                .font(.system(size: 13))
                .focused($focused)
            if !text.isEmpty {
                Button { text = "" } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear search")
            }
        }
        .padding(.horizontal, 10)
        .frame(height: 32)
        .background(.primary.opacity(0.07), in: .rect(cornerRadius: 9, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 9, style: .continuous)
                .strokeBorder(focused ? mulberryRed.opacity(0.6) : .primary.opacity(0.06), lineWidth: 1)
        }
    }
}

/// The iPhone's settings tile, with a little depth: a white symbol on a tinted rounded square,
/// lit from the top.
struct SettingsTile: View {
    let symbol: String
    let tint: Color
    var size: CGFloat = 24

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.5, weight: .semibold))
            .foregroundStyle(.white)
            .shadow(color: .black.opacity(0.25), radius: 0.5, y: 0.5)
            .frame(width: size, height: size)
            .background {
                RoundedRectangle(cornerRadius: size * 0.28, style: .continuous)
                    .fill(tint.gradient)
                    .overlay {
                        RoundedRectangle(cornerRadius: size * 0.28, style: .continuous)
                            .strokeBorder(
                                LinearGradient(colors: [.white.opacity(0.35), .white.opacity(0.05)], startPoint: .top, endPoint: .bottom),
                                lineWidth: 0.75
                            )
                    }
                    .shadow(color: .black.opacity(0.25), radius: 1.5, y: 1)
            }
            .accessibilityHidden(true)
    }
}

/// A round photo, or the initial on Mulberry red until it loads.
struct PersonAvatar: View {
    let url: String?
    let name: String?
    let size: CGFloat

    var body: some View {
        AsyncImage(url: url.flatMap(URL.init(string:))) { image in
            image.resizable().scaledToFill()
        } placeholder: {
            Text(String(name?.trimmingCharacters(in: .whitespaces).prefix(1) ?? "M").uppercased())
                .font(.system(size: size * 0.42, weight: .semibold))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(mulberryRed)
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .accessibilityLabel(name ?? "Profile photo")
    }
}
