import AppKit
import Auth
import Overlay
import ProductCore
import Stickers
import Sync
import SwiftUI

/// One portrait panel of frosted glass holding the canvas, with the streak and profile in the
/// window toolbar. Settings and the streak open as sheets until they are designed.
struct MainWindowView: View {
    @ObservedObject var router: AppRouter
    @ObservedObject var authController: AuthSessionController
    @ObservedObject var appStateController: AppStateController
    @ObservedObject var syncController: CanvasSyncController
    @ObservedObject var overlayController: OverlayController
    @ObservedObject var stickerLoader: StickerImageLoader
    let editorModel: CanvasEditorModel
    @StateObject private var avatar = ProfileAvatar()
    @State private var reactionsOpen = false

    var body: some View {
        content
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background { WindowGlass() }
            // The traffic lights keep the leading edge; the streak and profile sit at the trailing one.
            .toolbar {
                if let bootstrap = appStateController.loadState.bootstrap, authController.state.isSignedIn {
                    ToolbarSpacer(.flexible)
                    if bootstrap.isPaired {
                        // Reactions stand apart; the streak and profile share one glass group.
                        ToolbarItem { reactionButton }
                        ToolbarSpacer(.fixed)
                        ToolbarItem { streakButton(bootstrap) }
                    }
                    ToolbarItem { profileMenu(bootstrap) }
                }
            }
            .task(id: appStateController.loadState.bootstrap?.userPhotoUrl) {
                await avatar.load(appStateController.loadState.bootstrap?.userPhotoUrl)
            }
            .sheet(isPresented: routeBinding(.settings, .overlayStatus)) {
                SettingsSheet(
                    authController: authController,
                    appStateController: appStateController,
                    syncController: syncController,
                    overlayController: overlayController
                ) { router.goHome() }
            }
            .sheet(isPresented: routeBinding(.streak)) {
                StreakSheet(bootstrap: appStateController.loadState.bootstrap) { router.goHome() }
            }
    }

    @ViewBuilder private var content: some View {
        switch authController.state {
        case .signedIn:
            if let bootstrap = appStateController.loadState.bootstrap {
                if bootstrap.isPaired {
                    FullAppCanvasEditor(
                        authController: authController,
                        appStateController: appStateController,
                        syncController: syncController,
                        stickerLoader: stickerLoader,
                        model: editorModel
                    )
                } else {
                    UnpairedPanel()
                }
            } else {
                ProgressView()
            }
        case .refreshing:
            ProgressView()
        case .signedOut, .signingIn, .failed:
            SignInPanel(authController: authController) { router.goHome() }
        }
    }

    private func routeBinding(_ routes: AppRoute...) -> Binding<Bool> {
        Binding(
            get: { routes.contains(router.selectedRoute) },
            set: { open in if !open { router.goHome() } }
        )
    }

    /// As on iPhone and iPad: Mulberry's streak bolt and the day count.
    private func streakButton(_ bootstrap: BootstrapState) -> some View {
        let days = bootstrap.currentStreakDays
        return Button { router.open(.streak) } label: {
            HStack(spacing: 5) {
                if let bolt = ToolbarArtwork.image("streak_bolt.png", height: 17) {
                    Image(nsImage: bolt)
                }
                Text(days, format: .number)
                    .font(.body.weight(.semibold))
                    .monospacedDigit()
                    .contentTransition(.numericText(value: Double(days)))
            }
            .padding(.horizontal, 2)
            .animation(.snappy, value: days)
        }
        .help("Daily streak")
        .accessibilityLabel("Daily streak: \(days) \(days == 1 ? "day" : "days")")
    }

    private var reactionButton: some View {
        Button { reactionsOpen = true } label: { Image(systemName: "heart") }
            .help("Reactions")
            .accessibilityLabel("Reactions")
            .popover(isPresented: $reactionsOpen, arrowEdge: .bottom) {
                HStack(spacing: 6) {
                    ForEach(ReactionType.allCases) { reaction in
                        Button {
                            reactionsOpen = false
                            Task { try? await authController.api.sendReaction(reaction) }
                        } label: {
                            Text(reaction.emoji)
                                .font(.system(size: 26))
                                .frame(width: 40, height: 40)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .help(reaction.sendLabel)
                        .accessibilityLabel(reaction.sendLabel)
                    }
                }
                .padding(10)
            }
    }

    private func profileMenu(_ bootstrap: BootstrapState) -> some View {
        Menu {
            Button("Settings…") { router.open(.settings) }
                .keyboardShortcut(",", modifiers: .command)
            Divider()
            Button("Sign Out") {
                Task { await authController.signOut() }
            }
        } label: {
            // Sync shows itself only when something is wrong, as a dot on the avatar.
            Image(nsImage: avatar.image(
                initial: bootstrap.userDisplayName ?? bootstrap.userEmail,
                syncError: syncController.status.lastError != nil
            ))
        }
        .menuIndicator(.hidden)
        .help(bootstrap.userDisplayName ?? "Profile")
        .accessibilityLabel("Profile")
    }
}

/// Frosted Liquid Glass behind the whole window, title bar included: the desktop shows
/// through softly while strokes stay legible.
private struct WindowGlass: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        // Glass lifts towards white; in Dark Mode a dark tint keeps it smoky instead.
        Rectangle()
            .fill(colorScheme == .dark ? Color.black.opacity(0.45) : Color(nsColor: .windowBackgroundColor).opacity(0.45))
            .glassEffect(.regular, in: Rectangle())
            .ignoresSafeArea()
    }
}

/// The profile photo as a small circular image. Toolbar menus size their label from its image,
/// so the photo is drawn at toolbar size rather than left at the photo's own size.
@MainActor
private final class ProfileAvatar: ObservableObject {
    @Published private var photo: NSImage?
    private var loadedURL: String?
    private static let side: CGFloat = 22

    func load(_ url: String?) async {
        guard url != loadedURL else { return }
        loadedURL = url
        guard let url = url.flatMap(URL.init(string:)),
              let (data, response) = try? await URLSession.shared.data(from: url),
              (response as? HTTPURLResponse)?.statusCode == 200 else {
            photo = nil
            return
        }
        guard loadedURL == url.absoluteString else { return }
        photo = NSImage(data: data)
    }

    func image(initial name: String?, syncError: Bool) -> NSImage {
        let side = Self.side
        let photo = photo
        let initial = name?.trimmingCharacters(in: .whitespaces).first.map { String($0).uppercased() } ?? "M"
        return NSImage(size: NSSize(width: side, height: side), flipped: false) { rect in
            NSGraphicsContext.saveGraphicsState()
            NSBezierPath(ovalIn: rect).addClip()
            if let photo, photo.size.width > 0, photo.size.height > 0 {
                // Fill the circle, cropping the longer side.
                let scale = max(side / photo.size.width, side / photo.size.height)
                let size = NSSize(width: photo.size.width * scale, height: photo.size.height * scale)
                photo.draw(in: NSRect(x: (side - size.width) / 2, y: (side - size.height) / 2, width: size.width, height: size.height))
            } else {
                NSColor(srgbRed: 179 / 255, green: 19 / 255, blue: 41 / 255, alpha: 1).setFill()
                rect.fill()
                let text = NSAttributedString(string: initial, attributes: [
                    .font: NSFont.systemFont(ofSize: 11, weight: .semibold),
                    .foregroundColor: NSColor.white
                ])
                let textSize = text.size()
                text.draw(at: NSPoint(x: (side - textSize.width) / 2, y: (side - textSize.height) / 2))
            }
            NSGraphicsContext.restoreGraphicsState()
            if syncError {
                NSColor(srgbRed: 179 / 255, green: 19 / 255, blue: 41 / 255, alpha: 1).setFill()
                NSBezierPath(ovalIn: NSRect(x: side - 8, y: side - 8, width: 8, height: 8)).fill()
            }
            return true
        }
    }
}

private struct SignInPanel: View {
    @ObservedObject var authController: AuthSessionController
    let onSignedIn: () -> Void

    var body: some View {
        VStack(spacing: 18) {
            Spacer()
            Image(nsImage: NSApp.applicationIconImage)
                .resizable()
                .frame(width: 96, height: 96)
                .accessibilityHidden(true)
            Text("Mulberry")
                .font(.largeTitle.weight(.semibold))
            Button {
                Task {
                    await authController.signIn()
                    if authController.state.isSignedIn { onSignedIn() }
                }
            } label: {
                Text(authController.state.isBusy ? "Signing In…" : "Sign in with Google")
                    .frame(maxWidth: 220)
            }
            .buttonStyle(.glassProminent)
            .tint(mulberryRed)
            .controlSize(.large)
            .disabled(authController.state.isBusy)
            if case let .failed(failure) = authController.state {
                Text(failure.message)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 32)
            }
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }
}

/// Pairing on the Mac is designed later; until then a person pairs on their phone.
private struct UnpairedPanel: View {
    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: "person.2")
                .font(.system(size: 34, weight: .light))
                .foregroundStyle(.secondary)
            Text("Pair on your iPhone to start drawing together.")
                .font(.title3)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
    }
}

/// Placeholder until the streak screen is designed.
private struct StreakSheet: View {
    let bootstrap: BootstrapState?
    let onDone: () -> Void

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: "flame.fill")
                .font(.system(size: 40))
                .foregroundStyle(.orange)
            Text(bootstrap?.streakTitle ?? "Streak")
                .font(.title2.weight(.semibold))
            Button("Done", action: onDone)
                .keyboardShortcut(.defaultAction)
        }
        .padding(32)
        .frame(width: 300)
    }
}

/// Placeholder until settings are designed: the account, the overlay and sync status.
private struct SettingsSheet: View {
    @ObservedObject var authController: AuthSessionController
    @ObservedObject var appStateController: AppStateController
    @ObservedObject var syncController: CanvasSyncController
    @ObservedObject var overlayController: OverlayController
    let onDone: () -> Void

    var body: some View {
        Form {
            Section("Account") {
                LabeledContent("Signed in as", value: authController.state.statusDetail)
                if let bootstrap = appStateController.loadState.bootstrap {
                    LabeledContent("Partner", value: bootstrap.partnerTitle)
                }
            }
            Section("Overlay") {
                Toggle("Show overlay", isOn: Binding(
                    get: { overlayController.isVisible },
                    set: { $0 ? overlayController.show() : overlayController.hide() }
                ))
                Picker("Display", selection: Binding(
                    get: { overlayController.selectedDisplayID ?? overlayController.displays.first?.id },
                    set: { if let id = $0 { overlayController.selectDisplay(id: id) } }
                )) {
                    ForEach(overlayController.displays) { display in
                        Text(display.name).tag(Optional(display.id))
                    }
                }
                Button("Reset Position", action: overlayController.resetPosition)
                LabeledContent("Quick Draw", value: overlayController.hotKeyStatus.message)
            }
            Section("Sync") {
                LabeledContent("Status", value: syncController.connectionState.title)
                LabeledContent("Revision", value: "\(syncController.status.lastAppliedServerRevision)")
                if syncController.status.pendingCount > 0 {
                    LabeledContent("Waiting to send", value: "\(syncController.status.pendingCount)")
                }
                if let error = syncController.status.lastError {
                    Text(error).foregroundStyle(.secondary)
                }
                Button("Recover Canvas Now", action: syncController.recoverNow)
            }
        }
        .formStyle(.grouped)
        .frame(width: 420, height: 480)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Done", action: onDone)
            }
        }
    }
}
