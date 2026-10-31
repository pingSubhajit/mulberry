import StoreKit
import SwiftUI

struct MulberryRootView: View {
    @ObservedObject var model: MulberryModel
    @ObservedObject var wallpapers: WallpaperLibrary
    @StateObject private var setup: WallpaperSetupModel
    @Environment(\.scenePhase) private var phase
    @State private var setupOpen = false
    @State private var setupAfterSettings = false
    @ObservedObject private var quickActions = HomeQuickActions.shared
    @ObservedObject private var routes = AppRoutes.shared
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
                AuthenticationView(isSigningIn: model.busy, errorMessage: model.errorMessage) { Task { await model.signIn() } }
            } else if model.bootstrap?.pairingStatus == "INVITE_PENDING_ACCEPTANCE" {
                NavigationStack { InviteAcceptanceView(model: model) }
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
        .onChange(of: model.restoring) { _, _ in handleQuickAction(); handleRoute() }
        .onChange(of: model.bootstrap) { _, _ in publishQuickActions(); handleQuickAction(); handleRoute() }
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
            StreakSheet(streaks: model.streaks) {
                if let api = try? model.api { await model.streaks.refresh(api: api) }
            }
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
        .sheet(isPresented: $model.showSettings, onDismiss: {
            if setupAfterSettings { setupAfterSettings = false; setupOpen = true }
        }) {
            SettingsView(model: model, library: wallpapers) {
                setupAfterSettings = true; model.showSettings = false
            }
        }
        .sheet(isPresented: $setupOpen) {
            WallpaperSetupView(setup: setup) {
                setupOpen = false
                if setup.health.state == .responded { model.finishWallpaperOnboarding() }
            }
        }
        .onOpenURL { url in
            if GoogleAuthentication.handle(url) { return }
            guard url.scheme == Bundle.main.bundleIdentifier else { return }
            switch url.host {
            case "wallpaper-return": Task { await setup.refresh() }
            case "shortcut-error": setup.message = "The Shortcut could not finish. Open it to check its actions and permissions."; setupOpen = true
            case "shortcut-cancelled": setup.message = "Setup was cancelled. Run the Shortcut again when ready."; setupOpen = true
            default: break
            }
        }
    }

    private func publishQuickActions() {
        quickActions.publish(signedIn: model.userId != nil, paired: model.bootstrap?.isPaired == true,
                             showDrawings: wallpapers.showDrawings, reaction: model.reactions.lastUsed)
    }

    /// Opens what a tapped notification is about, once the app is past sign-in and onboarding.
    private func handleRoute() {
        guard !model.restoring, model.bootstrap?.onboardingCompleted == true,
              !model.showWallpaperOnboarding, let route = routes.pending else { return }
        routes.pending = nil
        let covered = model.showSettings || model.showStreak || model.showInviteEntry || setupOpen
        model.showSettings = false; model.showStreak = false; model.showInviteEntry = false
        setupOpen = false; wallpaperOpen = false
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
                StreakPill(streaks: model.streaks, fallbackDays: model.bootstrap?.currentStreakDays ?? 0) {
                    model.showStreak = true
                }
            }
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
    }
}
