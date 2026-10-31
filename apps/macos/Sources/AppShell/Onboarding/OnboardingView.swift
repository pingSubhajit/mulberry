import AppKit
import Auth
import SwiftUI

/// The whole screen during onboarding: the darkened, blurred desktop and the film over it.
struct OnboardingView: View {
    @ObservedObject var flow: OnboardingFlow
    @ObservedObject var authController: AuthSessionController

    var body: some View {
        GeometryReader { proxy in
            let size = proxy.size
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let phoneScale = Self.phoneScale(for: size)
            ZStack {
                OnboardingBackdrop()
                    .opacity(flow.backdropShown ? 1 : 0)
                    .onTapGesture { flow.skipIntro() }
                if flow.scene == .intro {
                    IntroScene(flow: flow, authController: authController, center: center, height: size.height)
                }
                // The window an account's setup began in, turning into the phone.
                if let shell = flow.shell {
                    WindowShell(shell: shell, target: Self.phoneFrame(center: center, scale: phoneScale),
                                targetRadius: Self.phoneRadius * phoneScale, morph: flow.shellMorph)
                }
                // The phone stays through its flight into the menu bar, under what comes next.
                if flow.scene == .phone || flow.phoneFlying {
                    phone(scale: phoneScale, center: center)
                }
                if flow.scene == .phone {
                    swipePrompt
                        .position(x: center.x, y: center.y + Self.phoneHeight * phoneScale / 2 + 40)
                }
                if flow.doodleShown, let doodle = flow.doodle {
                    Image(nsImage: doodle)
                        .resizable()
                        .frame(width: OnboardingFlow.desktopCanvas.width, height: OnboardingFlow.desktopCanvas.height)
                        .scaleEffect(flow.doodleShrunk ? phoneScale / OnboardingFlow.desktopScale : 1)
                        .position(center)
                        .allowsHitTesting(false)
                        .accessibilityLabel("A heart and a note saying hi, drawn on the desktop")
                }
                if flow.scene == .waiting {
                    Text("We’ll let you know when \(flow.partnerDisplayName) joins")
                        .font(.system(size: 24))
                        .foregroundStyle(.white)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 40)
                        .opacity(flow.waitingShown ? 1 : 0)
                        .offset(y: flow.waitingShown ? 0 : 8)
                        .position(center)
                }
                if flow.scene == .welcome {
                    PairWelcome(flow: flow)
                        .position(center)
                }
                if flow.musicPlaying {
                    muteButton
                        .position(x: size.width - 44, y: 56)
                        .opacity(flow.backdropShown ? 1 : 0)
                }
                #if DEBUG
                replayButton
                    .position(x: size.width - (flow.musicPlaying ? 86 : 44), y: 56)
                    .opacity(flow.backdropShown ? 1 : 0)
                #endif
            }
            .frame(width: size.width, height: size.height)
        }
        .environment(\.colorScheme, .dark)
        .ignoresSafeArea()
    }

    /// The phone as tall as three quarters of the screen, as a phone held up to it.
    static let phoneHeight = LockScreenView.screen.height + LockScreenView.bezel * 2

    static func phoneScale(for size: CGSize) -> CGFloat {
        min(1.4, max(0.8, size.height * 0.74 / phoneHeight))
    }

    /// The phone's outer corners, round its bezel.
    static let phoneRadius = LockScreenView.screenRadius + LockScreenView.bezel

    /// The phone itself, without the room for its shadow: its middle is the screen's.
    static func phoneFrame(center: CGPoint, scale: CGFloat) -> CGRect {
        let size = CGSize(width: (LockScreenView.screen.width + LockScreenView.bezel * 2) * scale, height: phoneHeight * scale)
        return CGRect(x: center.x - size.width / 2, y: center.y - size.height / 2, width: size.width, height: size.height)
    }

    private func phone(scale: CGFloat, center: CGPoint) -> some View {
        // The panel holds room for the phone's shadow, more below it than above: its middle sits
        // that much lower than the phone's.
        let panelOffset = (LockScreenView.panelSize.height / 2) - (LockScreenView.topInset + Self.phoneHeight / 2)
        let away = flow.phoneAway
        let target = flow.iconPoint ?? CGPoint(x: center.x, y: 0)
        return LockScreenView(
            model: flow.lock,
            reactions: flow.reactions,
            presence: flow.presence,
            unlockedContent: AnyView(OnboardingPhoneApp(flow: flow)),
            chrome: flow.lockShown ? 1 : 0,
            iPhoneLook: true
        ) { [weak flow] in flow?.lock.hintSwipe() }
            .frame(width: LockScreenView.panelSize.width, height: LockScreenView.panelSize.height)
            // One picture as it fades, so the screens inside do not show through each other.
            .compositingGroup()
            .scaleEffect((away ? 0.03 : flow.phoneShown || flow.formsFromWindow ? 1 : 1.05) * scale)
            .blur(radius: flow.phoneShown || away || flow.formsFromWindow ? 0 : 8)
            .opacity(away ? 0 : flow.phoneShown ? 1 : 0)
            .position(x: away ? target.x : center.x, y: away ? target.y : center.y + panelOffset * scale)
    }

    private var swipePrompt: some View {
        VStack(spacing: 6) {
            Image(systemName: "chevron.compact.up")
                .font(.system(size: 22, weight: .semibold))
                .phaseAnimator([0.0, -5.0]) { arrow, lift in
                    arrow.offset(y: lift)
                } animation: { _ in .easeInOut(duration: 0.8) }
            Text("Swipe up from the bottom or press \(Image(systemName: "arrow.up.square"))")
                .font(.system(size: 15))
        }
        .foregroundStyle(.white.opacity(0.85))
        .opacity(flow.swipePromptShown ? 1 : 0)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Swipe up from the bottom or press the Up Arrow key")
    }

    #if DEBUG
    /// Development builds only: onboarding again from the start.
    private var replayButton: some View {
        Button {
            flow.replay()
        } label: {
            Image(systemName: "arrow.counterclockwise")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.white.opacity(0.85))
                .frame(width: 34, height: 34)
                .contentShape(.circle)
        }
        .buttonStyle(.plain)
        .glassEffect(.clear.interactive(), in: .circle)
        .help("Replay onboarding")
        .accessibilityLabel("Replay onboarding")
    }
    #endif

    private var muteButton: some View {
        Button {
            flow.setMuted(!flow.muted)
        } label: {
            Image(systemName: flow.muted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.white.opacity(0.85))
                .contentTransition(.symbolEffect(.replace))
                .frame(width: 34, height: 34)
                .contentShape(.circle)
        }
        .buttonStyle(.plain)
        .glassEffect(.clear.interactive(), in: .circle)
        .help(flow.muted ? "Play music" : "Mute music")
        .accessibilityLabel(flow.muted ? "Play music" : "Mute music")
    }
}

/// The window an account's setup began in, as it turns into the phone: it draws itself from the
/// window's frame and corners into the phone's, its contents giving way to the phone's black. The
/// sign-in window's dark is drawn again here; the canvas can't be, so the canvas window fades away
/// behind a shape darkening out of it.
private struct WindowShell: View {
    let shell: OnboardingFlow.Shell
    let target: CGRect
    let targetRadius: CGFloat
    let morph: CGFloat

    /// A macOS 26 window's corners: tighter without a toolbar, as the sign-in window has none.
    private var windowRadius: CGFloat { shell.signIn ? 16 : 26 }

    var body: some View {
        let from = shell.frame
        let frame = CGRect(x: from.minX + (target.minX - from.minX) * morph,
                           y: from.minY + (target.minY - from.minY) * morph,
                           width: from.width + (target.width - from.width) * morph,
                           height: from.height + (target.height - from.height) * morph)
        let shape = RoundedRectangle(cornerRadius: windowRadius + (targetRadius - windowRadius) * morph, style: .continuous)
        ZStack {
            Color.black
            if shell.signIn {
                SignInBackdrop().opacity(1 - morph)
            }
        }
        .opacity(shell.signIn ? 1 : morph)
        .frame(width: frame.width, height: frame.height)
        .clipShape(shape)
        .overlay { shape.strokeBorder(.white.opacity(0.16 * morph), lineWidth: 1) }
        .shadow(color: .black.opacity(0.35), radius: 18, y: 10)
        .position(x: frame.midX, y: frame.midY)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// The desktop behind onboarding, darkened and softly blurred: the blur lets some of the desktop
/// through, so it stays recognisable behind the film.
private struct OnboardingBackdrop: View {
    var body: some View {
        ZStack {
            BehindWindowBlur().opacity(0.92)
            Color.black.opacity(0.45)
        }
    }
}

struct BehindWindowBlur: NSViewRepresentable {
    func makeNSView(context: Context) -> NSVisualEffectView {
        let view = NSVisualEffectView()
        view.material = .fullScreenUI
        view.blendingMode = .behindWindow
        view.state = .active
        return view
    }

    func updateNSView(_ view: NSVisualEffectView, context: Context) {}
}

// MARK: The film's opening

/// "Subhajit Kundu presents", the rising mark, and "Welcome to Mulberry" with Continue with Google.
private struct IntroScene: View {
    @ObservedObject var flow: OnboardingFlow
    @ObservedObject var authController: AuthSessionController
    let center: CGPoint
    let height: CGFloat

    /// The welcome lifts a little to make room for the sign-in button.
    private var lift: CGFloat { flow.signInShown ? -32 : 0 }

    var body: some View {
        ZStack {
            // The name alone in the middle, then "presents" arriving beneath it, lifting it.
            ZStack {
                Text("Subhajit Kundu")
                    .font(.system(size: 30, weight: .regular))
                    .offset(y: flow.presentsShown ? -26 : 0)
                Text("presents")
                    .font(.custom("Tangerine", size: 44))
                    .opacity(flow.presentsShown ? 1 : 0)
                    .offset(y: flow.presentsShown ? 19 : 27)
            }
            .foregroundStyle(.white)
            .opacity(flow.creditShown ? 1 : 0)
            .offset(y: flow.creditShown ? 0 : 6)
            .position(x: center.x, y: center.y + MarkFlight.creditY)
            .accessibilityElement(children: .combine)

            FlyingMark(progress: flow.logoFlight, screenHeight: height)
                .position(x: center.x, y: center.y + MarkFlight.restY + lift)

            VStack(spacing: 0) {
                Text("Welcome to")
                    .font(.system(size: 21, weight: .regular))
                    .foregroundStyle(.white.opacity(0.88))
                Text("Mulberry")
                    .font(.custom("Poppins-Bold", size: 56))
                    .foregroundStyle(.white)
                    .padding(.top, -2)
            }
            .opacity(flow.welcomeShown ? 1 : 0)
            .offset(y: flow.welcomeShown ? 0 : 8)
            .position(x: center.x, y: center.y + MarkFlight.welcomeY + lift)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)

            VStack(spacing: 14) {
                GoogleCapsule(busy: authController.state.isBusy, size: .large) {
                    Task { await flow.signIn() }
                }
                if let error = flow.signInError {
                    Text(error)
                        .font(.system(size: 13))
                        .foregroundStyle(.white.opacity(0.75))
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: 360)
                        .transition(.opacity)
                }
            }
            .scaleEffect(flow.signInShown ? 1 : 0.9)
            .opacity(flow.signInShown ? 1 : 0)
            .allowsHitTesting(flow.signInShown)
            .position(x: center.x, y: center.y + 144 + lift + (flow.signInError == nil ? 0 : 18))
        }
        .opacity(flow.introShown ? 1 : 0)
    }
}

/// Where the intro's pieces sit, in points below the screen's middle, and the mark's rise
/// between them, so the film can time the credit and the welcome to where the mark is.
enum MarkFlight {
    static let restY: CGFloat = -56
    static let creditY: CGFloat = 4
    static let welcomeY: CGFloat = 48
    /// Half the credit's and the welcome's heights, as set.
    static let creditHalfHeight: CGFloat = 44
    static let welcomeHalfHeight: CGFloat = 50
    static let duration: Double = 2.0
    static let rest = CGSize(width: 50, height: 82)
    static let startScale: CGFloat = 14
    /// Time on the way to how far along the rise: it arrives already moving, from below the
    /// screen, and slows steadily into place rather than crawling at the end.
    static let curve = UnitCurve.bezier(startControlPoint: UnitPoint(x: 0.22, y: 0.5), endControlPoint: UnitPoint(x: 0.5, y: 1))

    // The mark rises from close below the screen to its place above the title, shrinking as it
    // goes, as if moving away: its size and its height follow the one rise, so it is largest and
    // quickest up close and smaller and slower as it settles.

    /// Close to shrinking evenly to the eye, a touch gentler at first: shrinking any faster there
    /// would pull its top edge down as it rises, leaving it waiting below the screen.
    static func scale(_ rise: CGFloat) -> CGFloat { 1 + (startScale - 1) * (1 - rise) * (1 - rise) }

    /// The mark's middle, below the screen's middle, as far along as `rise`. At the start its top is
    /// just below the screen's foot.
    static func middle(_ rise: CGFloat, screenHeight: CGFloat) -> CGFloat {
        let startMiddle = screenHeight / 2 + rest.height * startScale / 2 + 10
        return startMiddle + (restY - startMiddle) * rise
    }

    /// How far along the rise the mark's top reaches `y`.
    static func riseWhereTop(reaches y: CGFloat, screenHeight: CGFloat) -> CGFloat {
        solve(screenHeight: screenHeight) { middle($0, screenHeight: screenHeight) - rest.height * scale($0) / 2 - y }
    }

    /// How far along the rise the mark's foot reaches `y`.
    static func riseWhereBottom(reaches y: CGFloat, screenHeight: CGFloat) -> CGFloat {
        solve(screenHeight: screenHeight) { middle($0, screenHeight: screenHeight) + rest.height * scale($0) / 2 - y }
    }

    /// The seconds into the rise at which it is `rise` along.
    static func time(at rise: CGFloat) -> Double {
        Double(curve.inverse.value(at: Double(rise))) * duration
    }

    /// Where `distance` crosses zero as the mark rises: it only ever moves up.
    private static func solve(screenHeight: CGFloat, _ distance: (CGFloat) -> CGFloat) -> CGFloat {
        var low: CGFloat = 0, high: CGFloat = 1
        guard distance(high) <= 0 else { return 1 }
        for _ in 0..<30 {
            let mid = (low + high) / 2
            if distance(mid) > 0 { low = mid } else { high = mid }
        }
        return high
    }
}

/// The Mulberry mark on its way up: large, low and glowing red at first, small and plain at rest.
/// Drawn at each moment's size, so the vector stays sharp throughout. `progress` is time along the
/// rise; the curve turns it into distance.
private struct FlyingMark: View, Animatable {
    var progress: CGFloat
    let screenHeight: CGFloat

    nonisolated var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }

    var body: some View {
        let rise = CGFloat(MarkFlight.curve.value(at: Double(progress)))
        let scale = MarkFlight.scale(rise)
        let size = CGSize(width: MarkFlight.rest.width * scale, height: MarkFlight.rest.height * scale)
        // The glow belongs to the mark up close, and fades as it recedes.
        let glow = pow((scale - 1) / (MarkFlight.startScale - 1), 1.2)
        ZStack {
            Circle()
                .fill(RadialGradient(colors: [mulberryRed.opacity(0.75), mulberryRed.opacity(0)],
                                     center: .center, startRadius: 0, endRadius: size.width * 1.1))
                .frame(width: size.width * 2.2, height: size.width * 2.2)
                .blur(radius: 30 * scale / MarkFlight.startScale)
                .opacity(glow)
            if let mark = SettingsArtwork.image("BrandMark.svg") {
                Image(nsImage: mark).resizable().frame(width: size.width, height: size.height)
            }
        }
        .offset(y: MarkFlight.middle(rise, screenHeight: screenHeight) - MarkFlight.restY)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// Google's own G, the same file the iPhone app's sign-in uses.
struct GoogleMark: View {
    private static let image = AppResources.url("Onboarding/GoogleMark.svg").flatMap(NSImage.init(contentsOf:))

    var body: some View {
        if let image = Self.image {
            Image(nsImage: image).resizable().scaledToFit().accessibilityHidden(true)
        }
    }
}

// MARK: Together

/// The two photos meeting, with the iPhone's tilted hearts and arrow between them.
private struct PairWelcome: View {
    @ObservedObject var flow: OnboardingFlow

    private var firstName: String {
        flow.userDisplayName.split(separator: " ").first.map(String.init) ?? flow.userDisplayName
    }

    var body: some View {
        VStack(spacing: 0) {
            // Faded as one, without the glass button: macOS draws nothing of a group holding Liquid
            // Glass while the group's opacity animates, so it would pop in at the end instead.
            VStack(spacing: 0) {
                ZStack {
                    HeroDecorations()
                        .scaleEffect(1.5)
                        // Its hearts and arrow sit a little right of their frame's middle.
                        .offset(x: -2, y: 2)
                        .accessibilityHidden(true)
                    WelcomePhoto(image: flow.userPhoto, url: flow.userPhotoURL, name: flow.userDisplayName)
                        .offset(x: flow.pairShown ? -96 : -130)
                    WelcomePhoto(image: flow.partnerPhoto, url: flow.partnerPhotoURL, name: flow.partnerDisplayName)
                        .offset(x: flow.pairShown ? 96 : 130)
                }
                .frame(height: 170)
                Text(firstName.isEmpty ? "Welcome" : "Welcome \(firstName)")
                    .font(.system(size: 38, weight: .regular))
                    .foregroundStyle(.white.opacity(0.92))
                    .padding(.top, 26)
                    .accessibilityAddTraits(.isHeader)
                Text(subtitle)
                    .font(.system(size: 17))
                    .foregroundStyle(.white.opacity(0.62))
                    .padding(.top, 6)
            }
            .opacity(flow.pairShown ? 1 : 0)
            // The button arrives on its own, growing in rather than fading.
            ZStack {
                if flow.continueShown {
                    Button {
                        flow.continueToMulberry()
                    } label: {
                        Text("Continue")
                            .font(.system(size: 15, weight: .semibold))
                            .frame(width: 220, height: 30)
                    }
                    .buttonStyle(.glassProminent)
                    .tint(mulberryRed)
                    .controlSize(.large)
                    .keyboardShortcut(.defaultAction)
                    .transition(.scale(scale: 0.6))
                }
            }
            .frame(height: 44)
            .padding(.top, 34)
        }
        .multilineTextAlignment(.center)
        // Leaving, it all goes together, settling back a little as it fades.
        .scaleEffect(flow.leaving ? 0.96 : 1)
        .opacity(flow.leaving ? 0 : 1)
        .allowsHitTesting(!flow.leaving)
    }

    private var subtitle: String {
        if case .partnerJoined = flow.start { return "\(flow.partnerDisplayName) just joined you" }
        return "\(flow.partnerDisplayName) will greatly appreciate the gesture"
    }
}

/// A photo fetched ahead of the welcome, or fetched now if it is not there yet.
private struct WelcomePhoto: View {
    let image: NSImage?
    let url: String?
    let name: String

    var body: some View {
        if let image {
            Image(nsImage: image).resizable().scaledToFill()
                .frame(width: 112, height: 112)
                .clipShape(Circle())
                .accessibilityLabel(name)
        } else {
            PersonAvatar(url: url, name: name, size: 112)
        }
    }
}
