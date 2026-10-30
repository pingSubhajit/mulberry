import AppKit
import Auth
import Combine
import Network
import ProductCore
import SwiftUI
import Sync
import UserNotifications

/// Mulberry's first run on a Mac, as a short film that ends with the person drawing.
///
/// The screen darkens and blurs; "Subhajit Kundu presents" fades in as the Mulberry mark rises from
/// the bottom, shrinking and losing its glow, and settles into "Welcome to Mulberry" as the credit
/// fades. Continue with Google pops up. Once signed in, two hearts draw themselves on the desktop
/// and an iPhone forms around them: what is drawn on a Mac appears on a phone. It lights up as the
/// menu bar's lock screen and bobs to ask for a swipe. Swiping opens Mulberry on the phone: the
/// person's details, then joining their partner with a code or inviting them. With the two paired
/// the phone flies into the menu bar icon, the two photos meet, and Continue opens Quick Draw. A
/// person still waiting for their partner is told they will hear when the partner joins.
///
/// A soft acoustic guitar plays throughout and fades as Quick Draw opens.
@MainActor
final class OnboardingFlow: ObservableObject {
    enum Scene: Equatable { case intro, phone, waiting, welcome }
    /// Mulberry, open on the phone.
    enum AppScreen: Equatable { case home, splash, profile, code, acceptance, invite }
    /// Where the person goes once onboarding ends.
    enum Ending { case quickDraw, openApp, quiet }

    /// Where a run of the flow starts.
    enum Start: Equatable {
        /// The whole film, from the darkening screen.
        case intro
        /// Signed in already, after quitting part way: the phone, waiting for the swipe.
        case phone
        /// The partner has joined since: the two photos alone.
        case partnerJoined(Ending)
    }

    // The film's state, read by the views.
    @Published private(set) var scene = Scene.intro
    @Published private(set) var backdropShown = false
    @Published private(set) var creditShown = false
    @Published private(set) var presentsShown = false
    /// The mark's rise, from below the screen, large and glowing, to its place above the title.
    @Published private(set) var logoFlight: CGFloat = 0
    @Published private(set) var welcomeShown = false
    @Published private(set) var signInShown = false
    @Published private(set) var introShown = true
    @Published private(set) var signInError: String?
    @Published private(set) var hearts: NSImage?
    @Published private(set) var heartsShown = false
    @Published private(set) var heartsShrunk = false
    @Published private(set) var phoneShown = false
    @Published private(set) var lockShown = false
    @Published private(set) var swipePromptShown = false
    @Published private(set) var appScreen = AppScreen.home
    /// Mulberry opening from its icon on the home screen, 0 to 1.
    @Published private(set) var launch: CGFloat = 0
    @Published private(set) var iconPressed = false
    /// The phone on its way into the menu bar icon.
    @Published private(set) var phoneAway = false
    /// The phone on its way, kept on screen until it reaches the icon.
    @Published private(set) var phoneFlying = false
    @Published private(set) var waitingShown = false
    /// The two photos, fetched ahead so they are there the moment the two of them meet.
    @Published private(set) var userPhoto: NSImage?
    @Published private(set) var partnerPhoto: NSImage?
    @Published private(set) var pairShown = false
    @Published private(set) var continueShown = false
    /// Onboarding on its way out: the two photos, the button and the line go together.
    @Published private(set) var leaving = false
    @Published private(set) var muted = false
    @Published private(set) var musicPlaying = false
    /// The screen's height, which sets how far the mark rises.
    var screenHeight: CGFloat = 982
    /// Where the menu bar icon is, in the window, for the phone to fly to.
    @Published private(set) var iconPoint: CGPoint?
    var locateIcon: () -> CGPoint? = { nil }

    // The phone's Mulberry.
    @Published var name = ""
    @Published var partnerName = ""
    @Published var anniversary: Date?
    @Published var code = ""
    @Published var openAtLogin = true
    @Published private(set) var busy = false
    @Published private(set) var error: String?
    @Published private(set) var invite: CreatedInvite? {
        didSet { if invite?.code != oldValue?.code { inviteShared = false } }
    }
    /// The code has gone out through the share menu, so Continue leads.
    @Published var inviteShared = false
    /// Whether the details form, the one place that asks about Open at Login, has been shown.
    private var askedOpenAtLogin = false

    /// The lock screen the phone shows: the menu bar's own, with the hearts as its drawing.
    let lock = LockScreenModel()
    let presence = LockScreenPresence()
    let reactions: ReactionsModel

    let start: Start
    let music = OnboardingMusic()
    private let authController: AuthSessionController
    private let appStateController: AppStateController
    private let syncController: CanvasSyncController
    private let launchAtLogin: LaunchAtLogin
    /// Ends the run: the window goes, then the person is taken where the ending says.
    var finished: (Ending) -> Void = { _ in }
    /// Signing in lowers the window, so Google's sign-in can come in front of it.
    var signingIn: (Bool) -> Void = { _ in }

    /// The phone's status bar tells the truth about this Mac, as the menu bar's does.
    private let network = NWPathMonitor()
    private var unlockWatch: AnyCancellable?
    private var hinting: Task<Void, Never>?
    private var watchingPartner: Task<Void, Never>?
    private var ending = false

    init(start: Start, authController: AuthSessionController, appStateController: AppStateController,
         syncController: CanvasSyncController, launchAtLogin: LaunchAtLogin, deviceID: String) {
        self.start = start
        self.authController = authController
        self.appStateController = appStateController
        self.syncController = syncController
        self.launchAtLogin = launchAtLogin
        let api = authController.api
        // Never bound to an account: nothing arrives on the phone's island while onboarding.
        reactions = ReactionsModel(api: { api }, deviceID: deviceID)
        presence.shown = true
        lock.account = .paired
        lock.battery = MacBattery.reading()
        network.pathUpdateHandler = { [weak lock] path in
            let kind = MacNetwork.kind(of: path)
            DispatchQueue.main.async { lock?.network = kind }
        }
        network.start(queue: DispatchQueue(label: "com.subhajit.mulberry.onboarding.network"))
        // The lock screen's buttons wait for the swipe, as an iPhone's do until it is unlocked.
        let hint: () -> Void = { [weak lock] in lock?.hintSwipe() }
        lock.actions = LockScreenModel.Actions(quickDraw: hint, openCanvas: hint, openStreak: hint,
                                               toggleOverlay: hint, openSettings: hint, send: { _ in hint() })
    }

    private var bootstrap: BootstrapState? { appStateController.loadState.bootstrap }
    private var api: BackendClient { authController.api }
    private var reduceMotion: Bool { NSWorkspace.shared.accessibilityDisplayShouldReduceMotion }

    /// The partner's name as the person wrote it, for the screens that speak of them.
    var partnerDisplayName: String {
        bootstrap?.partnerDisplayName?.nonEmpty ?? partnerName.nonEmpty ?? "your partner"
    }

    var userDisplayName: String { bootstrap?.userDisplayName?.nonEmpty ?? name.nonEmpty ?? "" }
    var userPhotoURL: String? { bootstrap?.userPhotoUrl }
    var partnerPhotoURL: String? { bootstrap?.partnerPhotoUrl }
    /// Who sent the invite, by name: the invite's, else the partner's name on the account.
    var inviterName: String {
        bootstrap?.invite?.inviterDisplayName.nonEmpty ?? bootstrap?.partnerDisplayName?.nonEmpty ?? "Your partner"
    }

    // MARK: The film

    func run() async {
        music.start()
        musicPlaying = music.playing
        // Everything drawn once hidden, so the first fades run.
        await pause(0.05)
        switch start {
        case .intro: await playIntro()
        case .phone:
            await show(backdrop: true)
            #if DEBUG
            // `-onboarding hearts`: the film from just after signing in.
            if UserDefaults.standard.string(forKey: "onboarding") == "hearts" {
                introShown = false
                return await afterSignIn()
            }
            #endif
            await showPhone(drawn: true)
        case let .partnerJoined(ending):
            await loadPhotos()
            await show(backdrop: true)
            await welcome(ending: ending)
        }
    }

    private func show(backdrop: Bool) async {
        withAnimation(.easeInOut(duration: 0.9)) { backdropShown = backdrop }
        await pause(0.5)
    }

    private func playIntro() async {
        await show(backdrop: true)
        withAnimation(.easeOut(duration: 0.8)) { creditShown = true }
        await pause(1.0)
        withAnimation(.smooth(duration: 0.7)) { presentsShown = true }
        await pause(0.8)
        if reduceMotion {
            logoFlight = 1
            await pause(1.6)
            withAnimation(.easeInOut(duration: 0.6)) { creditShown = false; welcomeShown = true }
        } else {
            // The mark rises; time runs evenly and the mark's curve eases its distance.
            withAnimation(.linear(duration: MarkFlight.duration)) { logoFlight = 1 }
            // The credit is gone just before the mark's top reaches it, and the welcome comes once
            // the mark has risen clear of where it sits.
            let fade = 0.45
            let creditBottom = MarkFlight.creditY + MarkFlight.creditHalfHeight
            let reachesCredit = MarkFlight.time(at: MarkFlight.riseWhereTop(reaches: creditBottom, screenHeight: screenHeight))
            let welcomeTop = MarkFlight.welcomeY - MarkFlight.welcomeHalfHeight
            let clearsWelcome = MarkFlight.time(at: MarkFlight.riseWhereBottom(reaches: welcomeTop, screenHeight: screenHeight))
            let creditFades = max(0, reachesCredit - fade)
            await pause(creditFades)
            withAnimation(.easeIn(duration: fade)) { creditShown = false }
            await pause(max(fade, clearsWelcome - creditFades))
            withAnimation(.easeOut(duration: 0.8)) { welcomeShown = true }
        }
        // The button follows the welcome.
        await pause(2.6)
        withAnimation(reduceMotion ? .easeOut(duration: 0.3) : .spring(duration: 0.55, bounce: 0.32)) { signInShown = true }
    }

    /// Esc during the film: straight to signing in.
    func skipIntro() {
        guard scene == .intro, !signInShown else { return }
        withAnimation(.easeOut(duration: 0.35)) {
            creditShown = false
            presentsShown = true
            logoFlight = 1
            welcomeShown = true
            signInShown = true
        }
    }

    func signIn() async {
        guard !authController.state.isBusy else { return }
        signInError = nil
        signingIn(true)
        music.duck(true)
        await authController.signIn()
        music.duck(false)
        signingIn(false)
        switch authController.state {
        case .signedIn:
            await waitForAccount()
            await afterSignIn()
        case let .failed(failure) where !failure.cancelled:
            withAnimation(.easeOut(duration: 0.2)) { signInError = failure.message }
        default:
            break
        }
    }

    /// The account's details arrive just after the session.
    private func waitForAccount() async {
        for _ in 0..<50 where bootstrap == nil { await pause(0.1) }
    }

    private func afterSignIn() async {
        withAnimation(.easeInOut(duration: 0.6)) { introShown = false }
        await pause(0.7)
        await drawHearts()
        await pause(0.5)
        await showPhone(drawn: false)
    }

    /// The hearts draw themselves on the desktop, a point at a time.
    private func drawHearts() async {
        heartsShown = true
        let total = OnboardingHearts.pointCount
        let duration = reduceMotion ? 0.0 : 2.6
        let frames = max(1, Int(duration * 40))
        for frame in 1...frames {
            let count = Int((Double(frame) / Double(frames)) * Double(total))
            hearts = OnboardingHearts.image(upTo: count, size: Self.desktopCanvas, scale: 2)
            await pause(duration / Double(frames))
        }
        hearts = OnboardingHearts.image(size: Self.desktopCanvas, scale: 2)
    }

    /// The drawing's size on the desktop, larger than on the phone it shrinks into.
    static var desktopCanvas: CGSize {
        CGSize(width: LockScreenView.canvasSize.width * desktopScale, height: LockScreenView.canvasSize.height * desktopScale)
    }

    static let desktopScale: CGFloat = 1.5

    /// The phone forms around the hearts, then lights up as a lock screen asking for a swipe.
    private func showPhone(drawn: Bool) async {
        scene = .phone
        lock.drawing = OnboardingHearts.image(size: LockScreenView.canvasSize, scale: 4)
        lock.unlocked = false
        lock.swipe = 0
        // Drawn once hidden first, so it forms rather than appearing whole.
        await pause(0.05)
        if drawn {
            withAnimation(.easeOut(duration: 0.8)) { phoneShown = true }
        } else {
            withAnimation(.smooth(duration: 1.0)) {
                heartsShrunk = true
                phoneShown = true
            }
            await pause(1.0)
            heartsShown = false
        }
        await pause(0.6)
        Task { await loadPhotos() }
        withAnimation(.easeInOut(duration: 0.7)) { lockShown = true }
        await pause(0.9)
        withAnimation(.easeOut(duration: 0.6)) { swipePromptShown = true }
        unlockWatch = lock.$unlocked.removeDuplicates().filter { $0 }.sink { [weak self] _ in
            Task { await self?.unlocked() }
        }
        #if DEBUG
        // A forced screen opens on its own, for looking at it; `-onboardingScreen route` opens
        // whatever the account's state leads to, and `waiting` the end for a partner yet to join.
        if UserDefaults.standard.string(forKey: "onboardingScreen") != nil {
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(3))
                self?.lock.setUnlocked(true)
            }
        }
        #endif
        // It bobs now and then, as iOS does, until the swipe comes.
        hinting = Task { [weak self] in
            while !Task.isCancelled {
                self?.lock.hintSwipe()
                try? await Task.sleep(for: .seconds(3.2))
            }
        }
    }

    /// Swiped up: the home screen, with Mulberry alone in the dock, opens it.
    private func unlocked() async {
        unlockWatch = nil
        hinting?.cancel()
        withAnimation(.easeOut(duration: 0.3)) { swipePromptShown = false }
        launch = 0
        appScreen = .home
        await pause(0.9)
        iconPressed = true
        await pause(0.14)
        iconPressed = false
        withAnimation(reduceMotion ? .easeInOut(duration: 0.3) : .spring(duration: 0.5, bounce: 0.08)) { launch = 1 }
        await pause(0.55)
        appScreen = .splash
        await pause(0.7)
        await route()
    }

    /// The phone's next screen, from where the account stands.
    private func route() async {
        #if DEBUG
        // `-onboardingScreen profile|code|acceptance|invite` shows that screen with nothing sent.
        if UserDefaults.standard.string(forKey: "onboardingScreen") == "waiting" {
            return await leave(to: .waiting)
        }
        if let screen = Self.debugScreen {
            if screen == .invite, invite == nil, let json = #"{"inviteId":"debug","code":"482913","expiresAt":"\#(ServerDate.string(Date().addingTimeInterval(86_400)))"}"#.data(using: .utf8) {
                invite = try? JSONDecoder().decode(CreatedInvite.self, from: json)
                // `-onboardingInviteShared YES`: as if the code had already been shared.
                inviteShared = UserDefaults.standard.bool(forKey: "onboardingInviteShared")
            }
            return show(screen)
        }
        #endif
        guard let bootstrap else { return show(.profile) }
        if bootstrap.isPaired {
            await leave(to: .welcome)
        } else if bootstrap.pairingStatus == "INVITE_PENDING_ACCEPTANCE" {
            show(.acceptance)
        } else if !bootstrap.onboardingCompleted || Self.needsPartnerDetails(bootstrap) {
            name = name.nonEmpty ?? bootstrap.userDisplayName ?? authController.state.displayName ?? ""
            partnerName = partnerName.nonEmpty ?? bootstrap.partnerDisplayName ?? ""
            anniversary = anniversary ?? bootstrap.anniversaryDate.flatMap(AnniversaryDay.date)
            show(.profile)
        } else {
            show(.invite)
            await createInvite()
        }
    }

    #if DEBUG
    private static var debugScreen: AppScreen? {
        switch UserDefaults.standard.string(forKey: "onboardingScreen") {
        case "profile": .profile
        case "code": .code
        case "acceptance": .acceptance
        case "invite": .invite
        default: nil
        }
    }

    /// Nothing is sent while a debug screen is forced.
    private var debugging: Bool { Self.debugScreen != nil }
    #else
    private var debugging: Bool { false }
    #endif

    /// An invite needs the partner's name and the anniversary, which a disconnect clears.
    private static func needsPartnerDetails(_ bootstrap: BootstrapState) -> Bool {
        bootstrap.partnerDisplayName?.nonEmpty == nil || bootstrap.anniversaryDate?.nonEmpty == nil
    }

    private func show(_ screen: AppScreen) {
        error = nil
        if screen == .profile { askedOpenAtLogin = true }
        withAnimation(.smooth(duration: 0.4)) { appScreen = screen }
        watchingPartner?.cancel()
        // While the code is on screen, the partner joining takes the person straight to them. A
        // screen forced for testing asks the server nothing.
        if screen == .invite, !debugging {
            watchingPartner = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(4))
                    guard let self, !Task.isCancelled, self.appScreen == .invite else { return }
                    if let state = try? await self.api.bootstrap(), state.isPaired {
                        self.apply(state)
                        await self.leave(to: .welcome)
                        return
                    }
                }
            }
        }
    }

    // MARK: The phone's Mulberry

    var profileComplete: Bool {
        name.nonEmpty != nil && partnerName.nonEmpty != nil && anniversary != nil
    }

    func saveProfile() async {
        guard profileComplete, let anniversary else { return }
        await perform {
            let draft = ProfileDraft(displayName: self.name.trimmed, partnerDisplayName: self.partnerName.trimmed,
                                     anniversaryDate: ServerDate.localDay(anniversary))
            self.apply(try await self.api.saveProfile(draft))
            await self.route()
        }
    }

    func enterCode() { show(.code) }

    func back() { Task { await route() } }

    func redeem() async {
        guard code.count == 6 else { return }
        await perform {
            self.apply(try await self.api.redeemInvite(code: self.code).bootstrapState)
            await self.route()
        }
    }

    func acceptInvite() async {
        guard let id = bootstrap?.invite?.inviteId else { return }
        await perform {
            self.apply(try await self.api.acceptInvite(id: id).bootstrapState)
            await self.route()
        }
    }

    func declineInvite() async {
        guard let id = bootstrap?.invite?.inviteId else { return }
        await perform {
            self.apply(try await self.api.declineInvite(id: id))
            await self.route()
        }
    }

    func createInvite() async {
        await perform { self.invite = try await self.api.createInvite() }
    }

    /// The code shared, or kept for later: the phone goes to the menu bar to wait there.
    func continueWithoutPartner() async {
        await leave(to: .waiting)
    }

    private func perform(_ work: @escaping () async throws -> Void) async {
        guard !busy, !debugging else { return }
        busy = true
        error = nil
        defer { busy = false }
        do {
            try await work()
        } catch {
            withAnimation(.easeOut(duration: 0.2)) { self.error = Self.message(for: error) }
        }
    }

    private static func message(for error: Error) -> String {
        switch error.localizedDescription {
        case "You cannot redeem your own invite": "That’s your own code. Share it with your partner."
        case "Invite code has expired", "Invite code is no longer valid": "That code has expired. Ask your partner for a new one."
        default: error.localizedDescription
        }
    }

    private func apply(_ state: BootstrapState) {
        appStateController.apply(state, syncController: syncController)
    }

    // MARK: Endings

    /// The phone shrinks into the menu bar icon, where Mulberry lives from now on.
    private func leave(to scene: Scene) async {
        watchingPartner?.cancel()
        iconPoint = locateIcon()
        if scene == .welcome { Task { await loadPhotos() } }
        let flight = reduceMotion ? 0.3 : 0.9
        phoneFlying = true
        withAnimation(reduceMotion ? .easeIn(duration: flight) : .timingCurve(0.45, 0, 0.8, 0.45, duration: flight)) { phoneAway = true }
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(flight + 0.1))
            self?.phoneFlying = false
        }
        // What comes next starts while the phone is still on its way, so the screen is never empty.
        await pause(0.2)
        if scene == .welcome {
            await welcome(ending: .quickDraw)
        } else {
            await waiting()
        }
    }

    private func welcome(ending: Ending) async {
        pendingEnding = ending
        scene = .welcome
        // Drawn once hidden first: fading in from the moment it is added, it would not fade at all.
        await pause(0.05)
        withAnimation(.easeOut(duration: 0.8)) { pairShown = true }
        await pause(2.6)
        withAnimation(reduceMotion ? .easeOut(duration: 0.3) : .spring(duration: 0.5, bounce: 0.3)) { continueShown = true }
        #if DEBUG
        // `-onboardingAutoContinue YES` presses Continue a moment later and stays on the desktop,
        // for watching the way out.
        if UserDefaults.standard.bool(forKey: "onboardingAutoContinue") {
            await pause(1.2)
            pendingEnding = .quiet
            continueToMulberry()
        }
        #endif
    }

    private var pendingEnding = Ending.quickDraw

    /// The account's photo and the partner's, once each.
    private func loadPhotos() async {
        async let user = userPhoto == nil ? Self.image(at: userPhotoURL) : nil
        async let partner = partnerPhoto == nil ? Self.image(at: partnerPhotoURL) : nil
        if let image = await user { userPhoto = image }
        if let image = await partner { partnerPhoto = image }
    }

    private nonisolated static func image(at address: String?) async -> NSImage? {
        guard let address, let url = URL(string: address),
              let (data, _) = try? await URLSession.shared.data(from: url) else { return nil }
        return NSImage(data: data)
    }

    /// The two photos' Continue.
    func continueToMulberry() {
        finish(pendingEnding)
    }

    /// Told the partner's arrival will be news, the person is left to it.
    private func waiting() async {
        scene = .waiting
        await pause(0.05)
        withAnimation(.easeOut(duration: 0.7)) { waitingShown = true }
        // The promise is the moment to ask. The ending does not wait on the answer: macOS keeps
        // the request on screen after onboarding has gone.
        Task { _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) }
        await pause(2.8)
        withAnimation(.easeIn(duration: 0.6)) { waitingShown = false }
        await pause(0.6)
        finish(.quiet)
    }

    private func finish(_ ending: Ending) {
        guard !self.ending else { return }
        self.ending = true
        network.cancel()
        // Only a choice the person saw on the form.
        if !debugging, askedOpenAtLogin, openAtLogin != launchAtLogin.isEnabled {
            launchAtLogin.setEnabled(openAtLogin)
        }
        music.fadeOut()
        Task {
            // What is on screen leaves as one, quickly, then the desktop comes back.
            withAnimation(.easeIn(duration: 0.28)) {
                leaving = true
                musicPlaying = false
            }
            await pause(0.18)
            withAnimation(.easeInOut(duration: 0.5)) { backdropShown = false }
            await pause(0.5)
            finished(ending)
        }
    }

    #if DEBUG
    /// Plays onboarding again from the start, from the button beside the music's.
    var replay: () -> Void = {}

    /// This run is replaced: it stops where it is and never ends onboarding.
    func abandon() {
        ending = true
        finished = { _ in }
        hinting?.cancel()
        watchingPartner?.cancel()
        network.cancel()
        music.fadeOut(over: 0.3)
    }
    #endif

    func setMuted(_ value: Bool) {
        muted = value
        music.setMuted(value)
    }

    private func pause(_ seconds: Double) async {
        try? await Task.sleep(for: .seconds(seconds))
    }
}

private extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}

private extension AuthSessionState {
    var displayName: String? {
        if case let .signedIn(session) = self { return session.userDisplayName }
        return nil
    }
}
