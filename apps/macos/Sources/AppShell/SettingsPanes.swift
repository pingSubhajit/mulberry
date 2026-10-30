import AppKit
import Auth
import Overlay
import ProductCore
import QuickDraw
import SwiftUI
import Sync

// MARK: General

struct GeneralPane: View {
    @ObservedObject var preferences: MacPreferences
    @ObservedObject var launchAtLogin: LaunchAtLogin
    @ObservedObject var overlayController: OverlayController
    @ObservedObject var appStateController: AppStateController
    @ObservedObject var account: AccountSettings
    let recordingShortcut: (Bool) -> Void

    var body: some View {
        let bootstrap = appStateController.loadState.bootstrap
        let paired = bootstrap?.isPaired == true
        SettingsPage {
            SettingsSection {
                SettingsToggleRow("Open at Login", isOn: Binding(get: { launchAtLogin.isEnabled }, set: { launchAtLogin.setEnabled($0) }))
                if launchAtLogin.needsApproval {
                    SettingsNotice(
                        message: "macOS needs your permission in Login Items before Mulberry can open at login.",
                        action: ("Open Login Items", launchAtLogin.openLoginItems)
                    )
                }
                if let error = launchAtLogin.errorMessage {
                    SettingsNotice(message: error)
                }
                SettingsToggleRow("Show in Menu Bar", isOn: $preferences.showsMenuBarIcon)
                SettingsToggleRow(
                    "Show in Dock",
                    detail: preferences.showsMenuBarIcon
                        ? "When off, Mulberry is in the Dock only while a window is open."
                        : "Stays on while the menu bar icon is hidden.",
                    isOn: $preferences.showsInDock
                )
                .disabled(!preferences.showsMenuBarIcon)
            }

            SettingsSection("Quick Draw") {
                SettingsRow("Shortcut", detail: "Draw on the desktop from any app.") {
                    HStack(spacing: 8) {
                        ShortcutRecorder(shortcut: preferences.quickDrawShortcut, recording: recordingShortcut) {
                            preferences.quickDrawShortcut = $0
                        }
                        Button {
                            preferences.quickDrawShortcut = .standard
                        } label: {
                            Image(systemName: "arrow.counterclockwise")
                                .font(.system(size: 12, weight: .semibold))
                                .frame(width: 30, height: 30)
                                .background(.primary.opacity(0.09), in: Circle())
                        }
                        .buttonStyle(.plain)
                        .opacity(preferences.quickDrawShortcut == .standard ? 0.35 : 1)
                        .disabled(preferences.quickDrawShortcut == .standard)
                        .help("Reset to \(QuickDrawShortcut.standard.displayString)")
                        .accessibilityLabel("Reset shortcut")
                    }
                }
                if let problem = overlayController.hotKeyStatus.problem {
                    SettingsNotice(message: problem)
                }
            }

            SettingsSection("Canvas", footer: paired ? nil : "Pair with your partner to change the canvas.") {
                SettingsToggleRow(
                    "Dry Brush Strokes",
                    detail: "Strokes look painted with a dry brush, for both of you.",
                    isOn: Binding(
                        get: { bootstrap?.canvasStrokeRenderMode == "dry" },
                        set: { enabled in Task { _ = await account.setDryBrush(enabled) } }
                    )
                )
                .disabled(!paired || account.busy)
                if let error = account.errorMessage {
                    SettingsNotice(message: error)
                }
            }
        }
        .onAppear(perform: launchAtLogin.refresh)
        // Approval happens in System Settings, so look again on coming back.
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            launchAtLogin.refresh()
        }
    }
}

/// Click, then press the new combination. Esc cancels; a combination needs Command, Control or
/// Option so it never fires while typing.
private struct ShortcutRecorder: View {
    let shortcut: QuickDrawShortcut
    let recording: (Bool) -> Void
    let onChange: (QuickDrawShortcut) -> Void
    @StateObject private var recorder = KeyRecorder()

    var body: some View {
        Button {
            recorder.isRecording ? recorder.stop() : recorder.start(onRecording: recording, onChange: onChange)
        } label: {
            Text(recorder.isRecording ? "Press keys…" : shortcut.displayString)
                .font(.system(size: 14, weight: .semibold))
                .tracking(recorder.isRecording ? 0 : 1)
                .foregroundStyle(recorder.isRecording ? AnyShapeStyle(mulberryRed) : AnyShapeStyle(.primary))
                .padding(.horizontal, 14)
                .frame(minWidth: 92, minHeight: 30)
                .background(.primary.opacity(0.09), in: .rect(cornerRadius: 8, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .strokeBorder(mulberryRed.opacity(recorder.isRecording ? 0.7 : 0), lineWidth: 1.5)
                }
                .contentTransition(.opacity)
        }
        .buttonStyle(.plain)
        .help(recorder.isRecording ? "Press a key with Command, Control or Option. Esc cancels." : "Change shortcut")
        .accessibilityLabel("Quick Draw shortcut")
        .accessibilityValue(recorder.isRecording ? "Recording" : shortcut.displayString)
        .onDisappear(perform: recorder.stop)
        .animation(.easeOut(duration: 0.15), value: recorder.isRecording)
    }
}

@MainActor
private final class KeyRecorder: ObservableObject {
    @Published private(set) var isRecording = false
    private var monitor: Any?
    private var onRecording: (Bool) -> Void = { _ in }

    func start(onRecording: @escaping (Bool) -> Void, onChange: @escaping (QuickDrawShortcut) -> Void) {
        guard !isRecording else { return }
        self.onRecording = onRecording
        isRecording = true
        onRecording(true)
        monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            let keyCode = event.keyCode
            let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
            let key = event.charactersIgnoringModifiers ?? ""
            // While recording, every key press belongs to the recorder.
            MainActor.assumeIsolated {
                guard let self else { return }
                if keyCode == 53 { return self.stop() }
                let shortcut = QuickDrawShortcut(keyCode: keyCode, modifierFlags: flags, key: key)
                guard shortcut.isUsable else { return NSSound.beep() }
                onChange(shortcut)
                self.stop()
            }
            return nil
        }
    }

    func stop() {
        guard isRecording else { return }
        if let monitor { NSEvent.removeMonitor(monitor) }
        monitor = nil
        isRecording = false
        onRecording(false)
    }
}

// MARK: Desktop

struct DesktopPane: View {
    @ObservedObject var overlayController: OverlayController

    var body: some View {
        SettingsPage {
            SettingsSection {
                SettingsToggleRow(
                    "Show on Desktop",
                    detail: "The shared canvas sits behind your windows.",
                    isOn: Binding(get: { overlayController.isVisible }, set: { $0 ? overlayController.show() : overlayController.hide() })
                )
                SettingsRow("Display") {
                    SettingsMenu(
                        options: overlayController.displays.map { ($0.id, $0.name) },
                        selection: Binding(
                            get: { overlayController.selectedDisplayID ?? overlayController.displays.first?.id ?? "" },
                            set: { overlayController.selectDisplay(id: $0) }
                        )
                    )
                }
            }

            SettingsSection("Appearance") {
                SettingsRow("Visibility", detail: "Quick Draw shows it fully.") {
                    HStack(spacing: 10) {
                        Image(systemName: "circle.dotted")
                            .foregroundStyle(.secondary)
                            .accessibilityHidden(true)
                        Slider(value: Binding(get: { overlayController.opacity }, set: { overlayController.setOpacity($0) }), in: 0.2...1)
                            .tint(mulberryRed)
                            .frame(width: 150)
                            .accessibilityLabel("Visibility")
                            .accessibilityValue("\(Int((overlayController.opacity * 100).rounded())) percent")
                        Image(systemName: "circle.fill")
                            .foregroundStyle(.secondary)
                            .accessibilityHidden(true)
                    }
                }
                SettingsRow("Position", detail: "Centre the drawing on its display again.") {
                    Button("Reset", action: overlayController.resetPosition)
                        .buttonStyle(.settings)
                }
            }
        }
    }
}

// MARK: Account

struct AccountPane: View {
    let environment: SettingsEnvironment
    @ObservedObject var authController: AuthSessionController
    @ObservedObject var appStateController: AppStateController
    @ObservedObject var account: AccountSettings
    @State private var name = ""
    @State private var choosingPhoto = false
    @State private var preparingPhoto = false
    @State private var confirmingLogOut = false

    private var bootstrap: BootstrapState? { appStateController.loadState.bootstrap }
    private var trimmed: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        if authController.state.isSignedIn, let bootstrap {
            SettingsPage {
                HStack(spacing: 18) {
                    EditablePhoto(url: bootstrap.userPhotoUrl, name: bootstrap.userDisplayName, busy: preparingPhoto || account.busy) {
                        choosingPhoto = true
                    }
                    VStack(alignment: .leading, spacing: 3) {
                        Text(bootstrap.userDisplayName?.nonEmpty ?? "Mulberry")
                            .font(.custom("Poppins-SemiBold", size: 24, relativeTo: .title))
                        Text(bootstrap.userEmail ?? "")
                            .font(.system(size: 13))
                            .foregroundStyle(.secondary)
                            .textSelection(.enabled)
                    }
                }
                .padding(.horizontal, 6)
                .padding(.bottom, 26)

                SettingsSection("Profile") {
                    SettingsRow("Name") {
                        HStack(spacing: 8) {
                            SettingsTextField(placeholder: "Your name", text: $name, onSubmit: saveName)
                            if trimmed != (bootstrap.userDisplayName ?? "") {
                                Button("Save", action: saveName)
                                    .buttonStyle(.settingsProminent)
                                    .disabled(trimmed.isEmpty || account.busy)
                                    .transition(.opacity.combined(with: .scale(scale: 0.9)))
                            }
                        }
                        .animation(.easeOut(duration: 0.15), value: trimmed != (bootstrap.userDisplayName ?? ""))
                    }
                    SettingsRow("Photo") {
                        Button("Choose…") { choosingPhoto = true }
                            .buttonStyle(.settings)
                            .disabled(preparingPhoto || account.busy)
                    }
                    if let error = account.errorMessage {
                        SettingsNotice(message: error)
                    }
                }

                SettingsSection("Google Account", footer: "Your account and pairing stay saved when you log out.") {
                    SettingsRow(bootstrap.userEmail ?? "Signed in") {
                        Button("Log Out…") { confirmingLogOut = true }
                            .buttonStyle(.settings)
                    }
                }
            }
            .onAppear { name = bootstrap.userDisplayName ?? "" }
            .onChange(of: bootstrap.userDisplayName) { _, saved in name = saved ?? "" }
            .fileImporter(isPresented: $choosingPhoto, allowedContentTypes: ProfilePhoto.contentTypes) { result in
                guard case let .success(url) = result else { return }
                preparingPhoto = true
                Task {
                    defer { preparingPhoto = false }
                    do {
                        _ = await account.updatePhoto(try await ProfilePhoto.jpeg(from: url))
                    } catch {
                        account.errorMessage = error.localizedDescription
                    }
                }
            }
            .confirmationDialog("Log out of Mulberry?", isPresented: $confirmingLogOut) {
                Button("Log Out", role: .destructive) { Task { await authController.signOut() } }
            } message: {
                Text("Your account and pairing stay saved.")
            }
        } else {
            SettingsEmptyState(
                symbol: "person.crop.circle",
                title: "Not Signed In",
                message: "Sign in with Google to draw with your partner."
            ) {
                Button("Sign In with Google") { Task { await authController.signIn() } }
                    .buttonStyle(.settingsProminent)
                    .disabled(authController.state.isBusy)
            }
        }
    }

    private func saveName() {
        guard !trimmed.isEmpty, trimmed != bootstrap?.userDisplayName else { return }
        Task { _ = await account.updateName(trimmed) }
    }
}

/// A trailing text field in a soft well, as Raycast's inputs.
private struct SettingsTextField: View {
    let placeholder: String
    @Binding var text: String
    var onSubmit: () -> Void = {}
    @FocusState private var focused: Bool

    var body: some View {
        TextField(placeholder, text: $text)
            .textFieldStyle(.plain)
            .font(.system(size: 14))
            .multilineTextAlignment(.trailing)
            .focused($focused)
            .onSubmit(onSubmit)
            .padding(.horizontal, 10)
            .frame(width: 200, height: 30)
            .background(.primary.opacity(focused ? 0.1 : 0.06), in: .rect(cornerRadius: 8, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .strokeBorder(mulberryRed.opacity(focused ? 0.6 : 0), lineWidth: 1)
            }
            .animation(.easeOut(duration: 0.12), value: focused)
    }
}

/// A photo ringed in Mulberry red; clicking it chooses a new one.
private struct EditablePhoto: View {
    let url: String?
    let name: String?
    let busy: Bool
    let choose: () -> Void
    @State private var hovered = false

    var body: some View {
        Button(action: choose) {
            PersonAvatar(url: url, name: name, size: 72)
                .overlay {
                    if busy {
                        Circle().fill(.black.opacity(0.35))
                        ProgressView().controlSize(.small)
                    } else if hovered {
                        Circle().fill(.black.opacity(0.3))
                        Image(systemName: "camera.fill").foregroundStyle(.white)
                    }
                }
                .padding(3)
                .overlay { Circle().strokeBorder(mulberryRed, lineWidth: 3) }
        }
        .buttonStyle(.plain)
        .disabled(busy)
        .onHover { hovered = $0 }
        .animation(.easeOut(duration: 0.12), value: hovered)
        .help("Change photo")
        .accessibilityLabel("Change photo")
    }
}

/// A pane with nothing to set yet, and the one thing to do about it.
private struct SettingsEmptyState<Actions: View>: View {
    let symbol: String
    let title: String
    let message: String
    @ViewBuilder let actions: () -> Actions

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.system(size: 40, weight: .light))
                .foregroundStyle(.secondary)
            Text(title)
                .font(.custom("Poppins-SemiBold", size: 20, relativeTo: .title2))
            Text(message)
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            actions()
                .padding(.top, 6)
        }
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

extension SettingsEmptyState where Actions == EmptyView {
    init(symbol: String, title: String, message: String) {
        self.init(symbol: symbol, title: title, message: message) { EmptyView() }
    }
}

// MARK: Partner

struct PartnerPane: View {
    @ObservedObject var appStateController: AppStateController
    @ObservedObject var account: AccountSettings
    @ObservedObject var streaks: StreakModel
    @State private var name = ""
    @State private var anniversary = ""
    @State private var jpeg: Data?
    @State private var choosingPhoto = false
    @State private var preparingPhoto = false
    @State private var confirmingUnpair = false

    private var bootstrap: BootstrapState? { appStateController.loadState.bootstrap }
    private var trimmed: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        if let bootstrap, bootstrap.isPaired {
            TimelineView(.periodic(from: .now, by: 30)) { context in
                page(bootstrap, editable: bootstrap.canEditPartner(at: context.date))
            }
            .onAppear(perform: load)
            .onChange(of: bootstrap.partnerDisplayName) { _, _ in load() }
            .onChange(of: bootstrap.anniversaryDate) { _, _ in load() }
            .fileImporter(isPresented: $choosingPhoto, allowedContentTypes: ProfilePhoto.contentTypes) { result in
                guard case let .success(url) = result else { return }
                preparingPhoto = true
                Task {
                    defer { preparingPhoto = false }
                    do { jpeg = try await ProfilePhoto.jpeg(from: url) } catch { account.errorMessage = error.localizedDescription }
                }
            }
            .confirmationDialog("Unpair from \(bootstrap.partnerDisplayName?.nonEmpty ?? "your partner")?", isPresented: $confirmingUnpair) {
                Button("Unpair", role: .destructive) { Task { _ = await account.unpair() } }
            } message: {
                Text("Your profile stays, but shared drawing stops until you pair again.")
            }
        } else {
            SettingsEmptyState(
                symbol: "heart",
                title: "Not Paired",
                message: "Pair with your partner from Mulberry on your iPhone or iPad."
            )
        }
    }

    private func page(_ bootstrap: BootstrapState, editable: Bool) -> some View {
        SettingsPage {
            CoupleHero(bootstrap: bootstrap, partnerPhoto: jpeg, streakDays: streaks.streak?.currentStreakDays ?? bootstrap.currentStreakDays)
                .padding(.bottom, 26)

            SettingsSection("Their Profile", footer: lockNote(bootstrap, editable: editable)) {
                SettingsRow("Photo") {
                    Button(jpeg == nil ? "Choose…" : "Chosen") { choosingPhoto = true }
                        .buttonStyle(.settings)
                        .disabled(!editable || preparingPhoto)
                }
                SettingsRow("Name") {
                    SettingsTextField(placeholder: "Partner’s name", text: $name)
                        .disabled(!editable)
                }
                SettingsRow("Anniversary") {
                    if let date = AnniversaryDay.date(anniversary) {
                        DatePicker(
                            "Anniversary",
                            selection: Binding(get: { date }, set: { anniversary = AnniversaryDay.string($0) }),
                            in: ...Date(),
                            displayedComponents: .date
                        )
                        .labelsHidden()
                        .datePickerStyle(.field)
                        .disabled(!editable)
                    } else {
                        Button("Add") { anniversary = AnniversaryDay.string(Date()) }
                            .buttonStyle(.settings)
                            .disabled(!editable)
                    }
                }
                if changed(bootstrap) {
                    HStack(spacing: 8) {
                        Spacer()
                        Button("Revert", action: load)
                            .buttonStyle(.settings)
                        Button("Save Changes") {
                            Task {
                                if await account.updatePartner(name: trimmed, anniversary: anniversary, jpeg: jpeg) { jpeg = nil }
                            }
                        }
                        .buttonStyle(.settingsProminent)
                        .disabled(!editable || account.busy || preparingPhoto || trimmed.isEmpty || anniversary.isEmpty)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }
                if let error = account.errorMessage {
                    SettingsNotice(message: error)
                }
            }

            if let visibility = PartnerVisibility(status: bootstrap.partnerWallpaperStatus) {
                SettingsSection("Their Wallpaper") {
                    SettingsRow(visibility.title, detail: visibility.detail(bootstrap.partnerDisplayName?.nonEmpty ?? "Your partner")) {
                        Image(systemName: visibility.symbol)
                            .font(.system(size: 16))
                            .foregroundStyle(visibility.ok ? AnyShapeStyle(.green) : AnyShapeStyle(.yellow))
                    }
                }
            }

            SettingsSection("Pairing") {
                SettingsRow("Unpair", detail: "Shared drawing stops until you pair again.") {
                    Button("Unpair…") { confirmingUnpair = true }
                        .buttonStyle(.settings)
                        .disabled(account.busy)
                }
            }
        }
    }

    private func lockNote(_ bootstrap: BootstrapState, editable: Bool) -> String {
        if let unlock = bootstrap.partnerProfileUnlockDate, !editable {
            return "You can edit these again \(unlock.formatted(.relative(presentation: .named)))."
        }
        return "Name, photo and anniversary can change once every 72 hours."
    }

    private func load() {
        name = bootstrap?.partnerDisplayName ?? ""
        anniversary = bootstrap?.anniversaryDate ?? ""
        jpeg = nil
    }

    private func changed(_ bootstrap: BootstrapState) -> Bool {
        trimmed != (bootstrap.partnerDisplayName ?? "") || anniversary != (bootstrap.anniversaryDate ?? "") || jpeg != nil
    }
}

/// The two of you, as the iPhone's settings hero shows it: your ringed photo, theirs beside it,
/// doodled hearts between, then how long you have been together and the streak, under the streak
/// popover's red glow.
private struct CoupleHero: View {
    let bootstrap: BootstrapState
    /// A new partner photo chosen but not saved yet.
    let partnerPhoto: Data?
    let streakDays: Int

    var body: some View {
        VStack(spacing: 14) {
            ZStack {
                HeroDecorations().offset(x: 6, y: 2)
                    .accessibilityHidden(true)
                PersonAvatar(url: bootstrap.userPhotoUrl, name: bootstrap.userDisplayName, size: 78)
                    .padding(4)
                    .overlay { Circle().strokeBorder(mulberryRed, lineWidth: 4) }
                    .offset(x: -56)
                partner
                    .offset(x: 54, y: -1)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 120)
            VStack(spacing: 4) {
                Text(bootstrap.partnerDisplayName?.nonEmpty ?? "Your partner")
                    .font(.custom("Poppins-SemiBold", size: 22, relativeTo: .title2))
                if let since = AnniversaryDay.date(bootstrap.anniversaryDate) {
                    Text("together since \(since.formatted(date: .abbreviated, time: .omitted))")
                        .font(.system(size: 13))
                        .foregroundStyle(.secondary)
                }
            }
            HStack(spacing: 10) {
                stat(daysTogether, unit: "days together") {
                    Image(systemName: "heart.fill").foregroundStyle(mulberryRed)
                }
                stat(streakDays, unit: "day streak") {
                    if let bolt = ToolbarArtwork.image("streak_bolt.png", height: 15) { Image(nsImage: bolt) }
                }
            }
            .padding(.top, 6)
        }
        .padding(20)
        .background {
            ZStack(alignment: .top) {
                RoundedRectangle(cornerRadius: 18, style: .continuous).fill(.primary.opacity(0.055))
                LinearGradient(colors: [mulberryRed.opacity(0.28), mulberryRed.opacity(0)], startPoint: .top, endPoint: .bottom)
                    .frame(height: 170)
            }
            .clipShape(.rect(cornerRadius: 18, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(.primary.opacity(0.06), lineWidth: 1)
            }
        }
    }

    @ViewBuilder private var partner: some View {
        if let partnerPhoto, let image = NSImage(data: partnerPhoto) {
            Image(nsImage: image).resizable().scaledToFill()
                .frame(width: 70, height: 70)
                .clipShape(Circle())
        } else {
            PersonAvatar(url: bootstrap.partnerPhotoUrl, name: bootstrap.partnerDisplayName, size: 70)
        }
    }

    private var daysTogether: Int {
        guard let paired = bootstrap.pairedAt.flatMap(ServerDate.parse) else { return 0 }
        return max(0, Calendar.current.dateComponents([.day], from: paired, to: .now).day ?? 0)
    }

    private func stat<Icon: View>(_ value: Int, unit: String, @ViewBuilder icon: () -> Icon) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 6) {
                icon()
                Text(value, format: .number)
                    .font(.custom("Poppins-SemiBold", size: 20, relativeTo: .title2))
                    .contentTransition(.numericText(value: Double(value)))
            }
            Text(unit).font(.system(size: 12.5)).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(.primary.opacity(0.06), in: .rect(cornerRadius: 12, style: .continuous))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(value) \(unit)")
    }
}

/// The iPhone's tilted hearts and arrow between the two photos.
private struct HeroDecorations: View {
    private let hearts: [(size: CGFloat, angle: Double, x: CGFloat, y: CGFloat)] = [
        (19, 12.85, 4, 0), (19, -13.72, 26, 21), (17, 26.27, 34, 66),
        (19, -19.25, 4, 84), (31, 9.04, 22, 78),
    ]

    var body: some View {
        ZStack(alignment: .topLeading) {
            if let arrow = SettingsArtwork.image("SettingsHeroArrow.svg") {
                Image(nsImage: arrow).resizable().renderingMode(.template)
                    .frame(width: 39, height: 11)
                    .foregroundStyle(.primary)
                    .offset(x: 15, y: 12)
            }
            if let heart = SettingsArtwork.image("SettingsHeroHeart.png") {
                ForEach(hearts.indices, id: \.self) { index in
                    let spec = hearts[index]
                    Image(nsImage: heart).resizable().scaledToFit()
                        .frame(width: spec.size, height: spec.size)
                        .rotationEffect(.degrees(spec.angle))
                        .offset(x: spec.x, y: spec.y)
                }
            }
        }
        .frame(width: 55, height: 114, alignment: .topLeading)
    }
}

/// Whether the partner's wallpaper shows new drawings, as their phone last reported.
private enum PartnerVisibility {
    case syncOff, wallpaperNotSet, fine

    init?(status: PartnerWallpaperStatus?) {
        guard let status else { return nil }
        if !status.wallpaperSyncEnabled { self = .syncOff }
        else if !status.wallpaperSelectedOnHome && !status.wallpaperSelectedOnLock { self = .wallpaperNotSet }
        else { self = .fine }
    }

    var ok: Bool { self == .fine }

    var title: String {
        switch self {
        case .syncOff: "Doodles are off on their phone"
        case .wallpaperNotSet: "Mulberry isn’t their wallpaper yet"
        case .fine: "Your drawings reach their wallpaper"
        }
    }

    var symbol: String { ok ? "checkmark.circle.fill" : "exclamationmark.triangle.fill" }

    func detail(_ partner: String) -> String? {
        switch self {
        case .syncOff: "Ask \(partner) to turn on “Show doodles on wallpaper” in Settings."
        case .wallpaperNotSet: "Ask \(partner) to finish wallpaper setup."
        case .fine: nil
        }
    }
}

// MARK: Advanced

struct AdvancedPane: View {
    @ObservedObject var syncController: CanvasSyncController

    var body: some View {
        SettingsPage {
            SettingsSection("Sync") {
                SettingsRow("Status") {
                    HStack(spacing: 7) {
                        Circle().fill(statusColour).frame(width: 8, height: 8).accessibilityHidden(true)
                        SettingsValue(syncController.connectionState.title)
                    }
                }
                SettingsRow("Canvas Revision") {
                    SettingsValue(syncController.status.lastAppliedServerRevision.formatted())
                }
                SettingsRow("Waiting to Send") {
                    SettingsValue(syncController.status.pendingCount.formatted())
                }
                if let recovered = syncController.status.lastSuccessfulRecoveryAt {
                    SettingsRow("Last Caught Up") {
                        SettingsValue(recovered.formatted(.relative(presentation: .named)))
                    }
                }
                if let error = syncController.status.lastError {
                    SettingsNotice(message: error)
                }
            }
            SettingsSection {
                SettingsRow("Recover Canvas", detail: "Fetch the whole canvas again from the server.") {
                    Button("Recover", action: syncController.recoverNow)
                        .buttonStyle(.settings)
                }
            }
            #if DEBUG
            // Development builds only: checks that reports reach Crashlytics, as the iPhone's
            // Developer options do.
            SettingsSection("Crash Reporting", footer: "A crash is reported when Mulberry next opens.") {
                SettingsRow("Test Error") {
                    Button("Send", action: CrashReporting.testError).buttonStyle(.settings)
                }
                SettingsRow("Test Crash") {
                    Button("Crash Now") { CrashReporting.testCrash() }.buttonStyle(.settings)
                }
            }
            #endif
        }
    }

    private var statusColour: Color {
        switch syncController.connectionState {
        case .connected: .green
        case .connecting: .yellow
        case .error: .red
        case .disconnected, .signedOut: .gray
        }
    }
}

// MARK: About

struct AboutPane: View {
    let openFeedback: () -> Void
    @State private var document: LegalDocument?

    var body: some View {
        SettingsPage {
            VStack(spacing: 10) {
                if let mark = SettingsArtwork.image("BrandMark.svg") {
                    Image(nsImage: mark).resizable().scaledToFit()
                        .frame(width: 54, height: 54)
                        .padding(16)
                        .background(.primary.opacity(0.07), in: .rect(cornerRadius: 24, style: .continuous))
                        .shadow(color: mulberryRed.opacity(0.35), radius: 22, y: 8)
                        .accessibilityHidden(true)
                }
                Text("Mulberry")
                    .font(.custom("Poppins-SemiBold", size: 26, relativeTo: .title))
                Text("A shared canvas that lives on your desktop.")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                Text("Version \(AppVersion.version) (\(AppVersion.build))")
                    .font(.system(size: 12))
                    .foregroundStyle(.tertiary)
                    .textSelection(.enabled)
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 8)
            .padding(.bottom, 28)

            SettingsSection {
                LinkRow(title: "Send Feedback", symbol: "bubble.left.fill", tint: .purple, action: openFeedback)
            }
            SettingsSection {
                LinkRow(title: "Privacy Policy", symbol: "hand.raised.fill", tint: .blue) { document = .privacy }
                LinkRow(title: "Terms of Use", symbol: "doc.text.fill", tint: .gray) { document = .terms }
            }
            SettingsSection {
                LinkRow(title: "Website", symbol: "globe", tint: .teal, external: true) {
                    NSWorkspace.shared.open(URL(string: "https://mulberry.my")!)
                }
                LinkRow(title: "Source Code", symbol: "chevron.left.forwardslash.chevron.right", tint: .indigo, external: true) {
                    NSWorkspace.shared.open(URL(string: "https://github.com/pingSubhajit/mulberry")!)
                }
            }
        }
        .sheet(item: $document) { LegalDocumentSheet(document: $0) }
    }
}

/// A whole-row link with its tile, brightening under the pointer.
private struct LinkRow: View {
    let title: String
    let symbol: String
    let tint: Color
    var external = false
    let action: () -> Void
    @State private var hovered = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                SettingsTile(symbol: symbol, tint: tint)
                Text(title).font(.system(size: 14, weight: .medium))
                Spacer()
                Image(systemName: external ? "arrow.up.right" : "chevron.right")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 16)
            .frame(minHeight: 50)
            .background(.primary.opacity(hovered ? 0.04 : 0))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hovered = $0 }
        .animation(.easeOut(duration: 0.12), value: hovered)
    }
}

enum AppVersion {
    static var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0" }
    static var build: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1" }
}

enum LegalDocument: String, Identifiable {
    case privacy = "privacy_policy", terms = "terms_of_use"

    var id: String { rawValue }
    var title: String { self == .privacy ? "Privacy Policy" : "Terms of Use" }

    var text: String {
        guard let url = AppResources.url("Legal/\(rawValue).txt"),
              let text = try? String(contentsOf: url, encoding: .utf8) else {
            return "This document could not be loaded. Please visit mulberry.my."
        }
        return text
    }
}

private struct LegalDocumentSheet: View {
    let document: LegalDocument
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            Text(document.title)
                .font(.custom("Poppins-SemiBold", size: 18, relativeTo: .title3))
                .padding(.top, 20)
                .padding(.bottom, 12)
            Divider()
            ScrollView {
                Text(document.text)
                    .font(.system(size: 13))
                    .lineSpacing(3)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(24)
            }
            Divider()
            HStack {
                Spacer()
                Button("Done") { dismiss() }
                    .buttonStyle(.settingsProminent)
                    .keyboardShortcut(.defaultAction)
            }
            .padding(14)
        }
        .frame(width: 560, height: 540)
    }
}

/// The iPhone's settings artwork, shipped in Resources/Settings.
@MainActor
enum SettingsArtwork {
    private static var cache: [String: NSImage] = [:]

    static func image(_ name: String) -> NSImage? {
        if let cached = cache[name] { return cached }
        guard let url = AppResources.url("Settings/\(name)"), let image = NSImage(contentsOf: url) else { return nil }
        cache[name] = image
        return image
    }
}
