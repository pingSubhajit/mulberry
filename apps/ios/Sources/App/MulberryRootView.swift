import StoreKit
import SwiftUI

struct MulberryRootView: View {
    @ObservedObject var model: MulberryModel
    @ObservedObject var wallpapers: WallpaperLibrary
    @StateObject private var setup: WallpaperSetupModel
    @Environment(\.scenePhase) private var phase
    @State private var setupOpen = false
    /// The sheets the toolbar's buttons open grow out of them and shrink back in: the streak sheet
    /// and Settings. The wallpapers are a step deeper, so they slide in as pushes do.
    @Namespace private var toolbarZoom
    @State private var streakClosings = 0
    /// Wallpaper setup opened from Settings, as a sheet over it, as Settings shows the streak.
    @State private var settingsSetupOpen = false
    @ObservedObject private var quickActions = HomeQuickActions.shared
    @ObservedObject private var routes = AppRoutes.shared
    @ObservedObject private var inviteLinks = InviteLinks.shared
    @Environment(\.requestReview) private var requestReview
    @State private var quickClearConfirmation = false
    @State private var profilePhoto: UIImage?
    @State private var wallpaperOpen = false
    @State private var pairingConfirmationOpen = false

    init(model: MulberryModel, wallpapers: WallpaperLibrary) {
        self.model = model; self.wallpapers = wallpapers
        _setup = StateObject(wrappedValue: WallpaperSetupModel(model: model))
    }

    var body: some View {
        Group {
            if model.restoring {
                // The launch screen's background carries on while the saved session restores.
                DelayedProgressView().background(MulberryDesign.background)
            } else if model.bootstrap == nil {
                AuthenticationView(busy: model.busy, signingInWith: model.signingInWith, errorMessage: model.errorMessage) { method in Task { await model.signIn(with: method) } }
            } else if model.bootstrap?.pairingStatus == "INVITE_PENDING_ACCEPTANCE" {
                NavigationStack { InviteAcceptanceView(model: model) }
            } else if (model.joiningInvite || (inviteLinks.pending != nil && model.busy)) && model.bootstrap?.onboardingCompleted != true {
                // An invite from a link skips onboarding; the invite screen follows in a moment.
                DelayedProgressView().background(MulberryDesign.background)
            } else if model.bootstrap?.onboardingCompleted != true {
                NavigationStack { OnboardingNameView(model: model) }
            } else if model.showWallpaperOnboarding {
                NavigationStack {
                    WallpaperPickerView(library: wallpapers, onboarding: true, onSetup: { setupOpen = true }, onSkip: model.finishWallpaperOnboarding)
                }
            } else { mainCanvas }
        }
        .task {
            _ = try? WallpaperRenderSize.current(); await model.restore(); wallpapers.load(user: model.userId)
            #if DEBUG
            await ReactionActivities.runLaunchArgument(partnerName: model.bootstrap?.partnerDisplayName ?? "Your partner",
                                                       partnerPhotoURL: model.bootstrap?.partnerPhotoUrl)
            #endif
        }
        .task(id: model.bootstrap?.userPhotoUrl) { await loadProfilePhoto() }
        .onChange(of: model.userId) { _, user in wallpapers.load(user: user) }
        .onChange(of: quickActions.pending) { _, _ in handleQuickAction() }
        .onChange(of: routes.pending) { _, _ in handleRoute() }
        .onChange(of: model.showWallpaperOnboarding) { _, _ in handleQuickAction(); handleRoute() }
        .onChange(of: model.restoring) { _, _ in handleQuickAction(); handleRoute(); handleInviteLink() }
        .onChange(of: model.bootstrap) { _, _ in publishQuickActions(); handleQuickAction(); handleRoute(); handleInviteLink() }
        .onChange(of: model.busy) { _, busy in if !busy { handleInviteLink() } }
        .onChange(of: inviteLinks.pending) { _, code in if code != nil { openInviteLink() } }
        // Hidden doodles bring reminders until they are shown again; becoming active tops them up.
        .task(id: "\(model.userId ?? "")|\(wallpapers.showDrawings)|\(model.bootstrap?.isPaired == true)|\(phase == .active)") {
            await DoodlesOffReminders.sync(user: model.userId, hidden: !wallpapers.showDrawings, paired: model.bootstrap?.isPaired == true)
        }
        // A 3-day streak earns an App Store review ask, a moment after the canvas settles.
        .task(id: "\(model.bootstrap?.currentStreakDays ?? 0)|\(phase == .active)") {
            guard phase == .active, model.bootstrap?.isPaired == true, (model.bootstrap?.currentStreakDays ?? 0) >= 3 else { return }
            try? await Task.sleep(for: .seconds(2.5))
            guard !Task.isCancelled, !model.showSettings, !model.showStreak, !setupOpen,
                  ReviewPrompt.reserve(streakDays: model.bootstrap?.currentStreakDays ?? 0) else { return }
            requestReview()
        }
        .onChange(of: wallpapers.showDrawings) { _, _ in publishQuickActions() }
        .onReceive(model.reactions.$lastUsed.removeDuplicates()) { _ in publishQuickActions() }
        .confirmationDialog("Clear all doodles?", isPresented: $quickClearConfirmation, titleVisibility: .visible) {
            Button("Clear doodles", role: .destructive) { Task { await model.clearCanvas() } }
        } message: { Text("This removes the shared drawing for both of you.") }
        .task(id: phase) {
            if phase == .background { ReactionActivities.appBackgrounded() }
            await model.setActive(phase == .active, updateWallpaper: phase == .background)
            guard phase == .active else { return }
            while !Task.isCancelled {
                await model.refresh()
                await setup.refresh()
                await PushRegistration.shared.refresh()
                // Reaction pushes are not guaranteed to arrive, so the canvas also looks for them.
                await model.reactions.check()
                try? await Task.sleep(for: .seconds(5))
            }
        }
        .sheet(isPresented: $model.showStreak) {
            StreakSheet(streaks: model.streaks, api: { try? model.api }) {
                if let api = try? model.api { await model.streaks.refresh(api: api) }
            }
            .navigationTransition(.zoom(sourceID: StreakZoomSource.pill, in: toolbarZoom))
        }
        .onChange(of: model.showStreak) { _, open in
            if !open { streakClosings += 1 }
        }
        .task(id: model.showPairingConfirmation) {
            guard model.showPairingConfirmation else { pairingConfirmationOpen = false; return }
            // Allow the invite sheet to finish dismissing before presenting success.
            try? await Task.sleep(for: .milliseconds(500))
            guard !Task.isCancelled, model.bootstrap?.isPaired == true else { return }
            pairingConfirmationOpen = true
        }
        .sheet(isPresented: $pairingConfirmationOpen, onDismiss: { model.showPairingConfirmation = false }) {
            PairingConfirmedSheet(userName: model.bootstrap?.userDisplayName ?? "",
                                  partnerName: model.bootstrap?.partnerDisplayName ?? "your partner") {
                pairingConfirmationOpen = false
            }
        }
        .sheet(isPresented: $model.showInviteEntry) { InviteEntryView(model: model) }
        .sheet(isPresented: $model.showSettings) {
            SettingsView(model: model, library: wallpapers) { settingsSetupOpen = true }
                .sheet(isPresented: $settingsSetupOpen) { WallpaperSetupView(setup: setup, onDone: finishSetup) }
                .navigationTransition(.zoom(sourceID: ToolbarZoomSource.settings, in: toolbarZoom))
        }
        .sheet(isPresented: $setupOpen) { WallpaperSetupView(setup: setup, onDone: finishSetup) }
        .alert(model.inviteNotice?.title ?? "", isPresented: Binding(
            get: { model.inviteNotice != nil },
            set: { if !$0 { model.inviteNotice = nil } }
        ), presenting: model.inviteNotice) { _ in
            Button("OK", role: .cancel) {}
        } message: { notice in Text(notice.message) }
        // Invite links arrive here once Mulberry is installed, and from Safari's app banner.
        .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
            if let url = activity.webpageURL { inviteLinks.receive(url) }
        }
        .onOpenURL { url in
            if GoogleAuthentication.handle(url) { return }
            if inviteLinks.receive(url) { return }
            guard url.scheme == Bundle.main.bundleIdentifier else { return }
            switch url.host {
            case "wallpaper-return": Task { await setup.refresh() }
            case "shortcut-error": setup.message = "The Shortcut could not finish. Open it to check its actions and permissions."; showSetup()
            case "shortcut-cancelled": setup.message = "Setup was cancelled. Run the Shortcut again when ready."; showSetup()
            #if DEBUG
            // Opens wallpaper setup from a Mac: `devicectl device process launch --payload-url <bundle id>://wallpaper-setup`.
            case "wallpaper-setup": routes.pending = .wallpaperSetup
            #endif
            default: break
            }
        }
    }

    private func publishQuickActions() {
        quickActions.publish(signedIn: model.userId != nil, paired: model.bootstrap?.isPaired == true,
                             showDrawings: wallpapers.showDrawings, reaction: model.reactions.lastUsed)
    }

    /// A new invite link closes what's open over the app, so the invite screen or a notice can show.
    private func openInviteLink() {
        guard model.bootstrap != nil else { return }
        let covered = model.showSettings || model.showStreak || model.showInviteEntry || setupOpen || pairingConfirmationOpen
        model.showSettings = false; model.showStreak = false; model.showInviteEntry = false
        setupOpen = false; settingsSetupOpen = false; wallpaperOpen = false; pairingConfirmationOpen = false
        Task {
            if covered { try? await Task.sleep(for: .milliseconds(450)) }
            await model.redeemLinkedInvite()
        }
    }

    /// Uses a waiting invite as soon as the account can: after sign-in, or once a request finishes.
    private func handleInviteLink() {
        guard inviteLinks.pending != nil else { return }
        Task { await model.redeemLinkedInvite() }
    }

    /// Wallpaper setup is on screen, on its own or over Settings.
    private var setupShowing: Bool { setupOpen || settingsSetupOpen }

    /// Shows wallpaper setup, unless it's already showing over Settings, where it stays.
    private func showSetup() {
        if !settingsSetupOpen { setupOpen = true }
    }

    private func finishSetup() {
        setupOpen = false; settingsSetupOpen = false
        if setup.health.state == .responded { model.finishWallpaperOnboarding() }
    }

    /// Opens what a tapped notification is about, once the app is past sign-in and onboarding.
    private func handleRoute() {
        // Setup is already showing, as after the reminder to come back from Shortcuts.
        if routes.pending == .wallpaperSetup && setupShowing { routes.pending = nil; return }
        guard !model.restoring, model.bootstrap?.onboardingCompleted == true,
              !model.showWallpaperOnboarding, let route = routes.pending else { return }
        routes.pending = nil
        let covered = model.showSettings || model.showStreak || model.showInviteEntry || setupOpen
        model.showSettings = false; model.showStreak = false; model.showInviteEntry = false
        setupOpen = false; settingsSetupOpen = false; wallpaperOpen = false
        Task {
            await model.refresh()
            // Let a sheet that was open finish closing before another opens.
            if covered { try? await Task.sleep(for: .milliseconds(450)) }
            switch route {
            case .canvas: break
            case .wallpaperSetup: setupOpen = true
            case .settings: model.showSettings = true
            }
        }
    }

    private func handleQuickAction() {
        guard !model.restoring, model.bootstrap?.onboardingCompleted == true,
              !model.showWallpaperOnboarding, let action = quickActions.pending else { return }
        quickActions.pending = nil
        model.showSettings = false; model.showInviteEntry = false
        switch action {
        case .wallpaper: wallpaperOpen = true
        case .react:
            wallpaperOpen = false
            if model.bootstrap?.isPaired == true { Task { await model.reactions.send(model.reactions.lastUsed) } }
        case .clear:
            wallpaperOpen = false
            if model.bootstrap?.isPaired == true { quickClearConfirmation = true }
        case .toggle:
            wallpapers.setShowDrawings(!wallpapers.showDrawings)
            let key = "wallpaperReceipt.\(model.userId ?? "").\(model.bootstrap?.pairSessionId ?? "unpaired")"
            if UserDefaults.standard.object(forKey: key) != nil { Task { await setup.runShortcut() } }
            else { setupOpen = true }
        }
    }

    private var mainCanvas: some View {
        NavigationStack {
            Group {
                if model.bootstrap?.isPaired == true { DoodleCanvasView(model: model).id(model.bootstrap?.pairSessionId) }
                else { UnpairedCanvasView(model: model) }
            }
            .toolbar { header }
            .navigationDestination(isPresented: $wallpaperOpen) {
                WallpaperPickerView(library: wallpapers, onSetup: {
                    let key = "wallpaperReceipt.\(model.userId ?? "").\(model.bootstrap?.pairSessionId ?? "unpaired")"
                    if UserDefaults.standard.object(forKey: key) != nil { Task { await setup.runShortcut() } }
                    else { setupOpen = true }
                }).navigationTitle("Wallpapers").navigationBarTitleDisplayMode(.inline)
            }
        }
    }

    private func loadProfilePhoto() async {
        profilePhoto = nil
        guard let value = model.bootstrap?.userPhotoUrl, let url = URL(string: value) else { return }
        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            guard (response as? HTTPURLResponse)?.statusCode == 200, !Task.isCancelled,
                  model.bootstrap?.userPhotoUrl == value else { return }
            let photo = try ImagePreparation.image(data, maximumDimension: 128)
            let size = CGSize(width: 28, height: 28)
            profilePhoto = UIGraphicsImageRenderer(size: size).image { _ in
                UIBezierPath(ovalIn: CGRect(origin: .zero, size: size)).addClip()
                let scale = max(size.width / photo.size.width, size.height / photo.size.height)
                let fitted = CGSize(width: photo.size.width * scale, height: photo.size.height * scale)
                photo.draw(in: CGRect(x: (size.width - fitted.width) / 2, y: (size.height - fitted.height) / 2,
                                      width: fitted.width, height: fitted.height))
            }.withRenderingMode(.alwaysOriginal)
        } catch { /* Keep the standard Profile icon if the photo is unavailable. */ }
    }

    @ToolbarContentBuilder private var header: some ToolbarContent {
        if model.bootstrap?.isPaired == true {
            ToolbarItem(placement: .topBarTrailing) {
                StreakPill(streaks: model.streaks, fallbackDays: model.bootstrap?.currentStreakDays ?? 0,
                           closings: streakClosings) {
                    model.showStreak = true
                }
            }
            .matchedTransitionSource(id: StreakZoomSource.pill, in: toolbarZoom)
            // Its own capsule, apart from the wallpaper and profile pair.
            ToolbarSpacer(.fixed, placement: .topBarTrailing)
        }
        ToolbarItem(placement: .topBarTrailing) {
            Button { wallpaperOpen = true } label: {
                Image("WallpaperNav").resizable().renderingMode(.template).scaledToFit()
                    .frame(width: 30, height: 30)
                    .foregroundStyle(MulberryDesign.ink)
            }.accessibilityLabel("Wallpaper")
        }
        ToolbarItem(placement: .topBarTrailing) {
            Button { model.showSettings = true } label: {
                Group {
                    if let photo = profilePhoto {
                        Image(uiImage: photo).resizable().scaledToFill().clipShape(Circle())
                    } else {
                        Image(systemName: "person.crop.circle").resizable().scaledToFit()
                    }
                }.frame(width: 30, height: 30)
                    .foregroundStyle(MulberryDesign.ink)
            }.accessibilityLabel("Profile")
        }
        .matchedTransitionSource(id: ToolbarZoomSource.settings, in: toolbarZoom)
    }
}

/// Besides the streak pill, the toolbar buttons whose sheets zoom out of them.
private enum ToolbarZoomSource: Hashable { case settings }
