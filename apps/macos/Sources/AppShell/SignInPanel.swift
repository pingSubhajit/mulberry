import AppKit
import Auth
import ProductCore
import SwiftUI

/// The account last signed in on this Mac: its name and photo outlive signing out, so the sign-in
/// window can greet whoever comes back.
struct LastAccount: Codable, Equatable {
    let name: String
    let photoURL: String?

    private static let key = "lastAccount"

    static func load(_ defaults: UserDefaults = .standard) -> LastAccount? {
        #if DEBUG
        // `-signInPreviewName "Name"` greets a pretend account.
        if let name = defaults.string(forKey: "signInPreviewName") {
            return LastAccount(name: name, photoURL: defaults.string(forKey: "signInPreviewPhoto"))
        }
        #endif
        return defaults.data(forKey: key).flatMap { try? JSONDecoder().decode(LastAccount.self, from: $0) }
    }

    static func remember(_ bootstrap: BootstrapState?, defaults: UserDefaults = .standard) {
        guard let name = bootstrap?.userDisplayName?.nonEmpty ?? bootstrap?.userEmail?.nonEmpty else { return }
        let account = LastAccount(name: name, photoURL: bootstrap?.userPhotoUrl)
        guard account != load(defaults), let data = try? JSONEncoder().encode(account) else { return }
        defaults.set(data, forKey: key)
    }
}

/// The window while signed out: wide and nearly opaque, the mark glowing above a welcome and
/// Continue with Google.
struct SignInPanel: View {
    @ObservedObject var authController: AuthSessionController
    @ObservedObject var windowState: MainWindowState
    @State private var account = LastAccount.load()
    @State private var shown = false
    @State private var document: LegalDocument?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var leaving: Bool { windowState.signInLeaving }
    /// Still waiting as the welcome leaves, rather than offering Continue again on the way out.
    private var busy: Bool { windowState.previewBusy || authController.state.isBusy || leaving }

    /// A failure worth reading: closing Google's window is not one.
    private var failure: String? {
        guard case let .failed(failure) = authController.state, !failure.cancelled else { return nil }
        return failure.message
    }

    var body: some View {
        ZStack {
            SignInBackdrop()

            VStack(spacing: 0) {
                mark
                welcome
                    .frame(maxWidth: 520)
                    .padding(.top, 34)
            }
            // A little above the middle, which the title bar's height makes look low.
            .padding(.bottom, 24)

            LegalNote { document = $0 }
                .frame(maxHeight: .infinity, alignment: .bottom)
                .padding(.bottom, 22)
                .opacity(shown && !leaving ? 1 : 0)
                .animation(entrance(delay: 0.36), value: shown)
        }
        .ignoresSafeArea()
        .environment(\.colorScheme, .dark)
        .animation(.easeIn(duration: 0.2), value: leaving)
        .task {
            // Drawn hidden once, then brought in: a view inserted and animated at once never animates.
            try? await Task.sleep(for: .seconds(0.05))
            shown = true
        }
        .onChange(of: authController.state.isSignedIn) { _, signedIn in
            if !signedIn { account = LastAccount.load() }
        }
        .sheet(item: $document) { LegalDocumentSheet(document: $0) }
    }

    private var mark: some View {
        ZStack {
            GlassMark()
                .frame(width: 58, height: 96)
        }
        .background { MarkGlow() }
        .opacity(shown && !leaving ? 1 : 0)
        .scaleEffect(leaving ? 0.96 : shown || reduceMotion ? 1 : 0.9)
        .blur(radius: shown || reduceMotion ? 0 : 10)
        .animation(entrance(delay: 0, duration: 1.1), value: shown)
        .accessibilityHidden(true)
    }

    private var welcome: some View {
        VStack(spacing: 0) {
            Text(account == nil ? "Welcome to Mulberry" : "Welcome back")
                .font(.custom("Poppins-Medium", size: 30, relativeTo: .largeTitle))
                .tracking(-0.3)
                .foregroundStyle(.white.opacity(0.94))
                .accessibilityAddTraits(.isHeader)
                .rising(shown && !leaving, entrance(delay: 0.12), reduceMotion: reduceMotion)
            Text(account == nil ? "A shared canvas that lives on your desktop." : "Your shared canvas is waiting.")
                .font(.system(size: 14))
                .foregroundStyle(.white.opacity(0.55))
                .padding(.top, 8)
                .rising(shown && !leaving, entrance(delay: 0.18), reduceMotion: reduceMotion)
            GoogleCapsule(busy: busy) {
                if windowState.previewSignIn != nil {
                    windowState.previewSignIn?()
                } else {
                    Task { await authController.signIn() }
                }
            }
            .padding(.top, 34)
            .rising(shown && !leaving, entrance(delay: 0.24), reduceMotion: reduceMotion)
            if let account {
                AccountChip(account: account)
                    .padding(.top, 20)
                    .rising(shown && !leaving, entrance(delay: 0.3), reduceMotion: reduceMotion)
            }
            if let failure {
                Text(failure)
                    .font(.system(size: 12))
                    .foregroundStyle(.white.opacity(0.7))
                    .multilineTextAlignment(.center)
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 18)
                    .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.2), value: failure)
    }

    /// Long and soft, settling rather than stopping.
    private func entrance(delay: Double, duration: Double = 0.8) -> Animation {
        reduceMotion ? .easeOut(duration: 0.3).delay(delay) : .timingCurve(0.16, 1, 0.3, 1, duration: duration).delay(delay)
    }
}

private extension View {
    /// Fades in from a few points lower.
    func rising(_ shown: Bool, _ animation: Animation, reduceMotion: Bool) -> some View {
        opacity(shown ? 1 : 0)
            .offset(y: shown || reduceMotion ? 0 : 10)
            .animation(animation, value: shown)
    }
}

/// Near-black, lifted a little in the middle. Almost opaque: the desktop is only a hint through it.
private struct SignInBackdrop: View {
    var body: some View {
        GeometryReader { proxy in
            let size = proxy.size
            ZStack {
                BehindWindowBlur()
                Color(red: 0.035, green: 0.03, blue: 0.035).opacity(0.96)
                // Squee and their partner in the meadow, rising out of the dark at the foot.
                if let meadow = Self.meadow {
                    Image(nsImage: meadow)
                        .resizable()
                        .scaledToFill()
                        .frame(width: size.width, height: size.height)
                        .clipped()
                        .mask {
                            LinearGradient(stops: [.init(color: .black, location: 0),
                                                   .init(color: .black.opacity(0.45), location: 0.3),
                                                   .init(color: .clear, location: 0.62)],
                                           startPoint: .bottom, endPoint: .top)
                        }
                        .opacity(0.09)
                        .allowsHitTesting(false)
                }
                RadialGradient(
                    colors: [.white.opacity(0.05), .white.opacity(0)],
                    center: UnitPoint(x: 0.5, y: 0.45),
                    startRadius: 0,
                    endRadius: size.width * 0.6
                )
                // Fine grain, so the soft gradients don't band on the dark.
                Image(nsImage: Self.grain)
                    .resizable(resizingMode: .tile)
                    .opacity(0.035)
                    .allowsHitTesting(false)
            }
        }
        .ignoresSafeArea()
    }
}

/// The mark as Liquid Glass, tinted Mulberry red, lit from above like the app icon's glyph.
private struct GlassMark: View {
    private let shape = MulberryMarkShape()

    var body: some View {
        ZStack {
            Color.clear
                .glassEffect(.regular.tint(mulberryRed), in: shape)
                // Glass greys in a window in the background; the mark keeps its red.
                .environment(\.appearsActive, true)
            shape.fill(LinearGradient(stops: [.init(color: .white.opacity(0.12), location: 0),
                                              .init(color: .white.opacity(0), location: 0.45),
                                              .init(color: .black.opacity(0.2), location: 1)],
                                      startPoint: .top, endPoint: .bottom))
                .allowsHitTesting(false)
        }
        .shadow(color: .black.opacity(0.5), radius: 10, y: 6)
    }
}

/// The mark's own light: a tight rim hugging its edges and a faint spill onto the dark, a little
/// below it, as if it shone on the surface. It breathes, barely.
private struct MarkGlow: View {
    @State private var breathing = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let shape = MulberryMarkShape()
        ZStack {
            shape.fill(mulberryRed).blur(radius: 24).opacity(0.12)
            shape.fill(mulberryRed).blur(radius: 6).opacity(0.3)
        }
        .offset(y: 4)
        .opacity(breathing ? 1 : 0.85)
        .allowsHitTesting(false)
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 4).repeatForever(autoreverses: true)) { breathing = true }
        }
    }
}

extension SignInBackdrop {
    static let meadow = AppResources.url("SignIn/Meadow.jpg").flatMap(NSImage.init(contentsOf:))

    /// Random greys, a device pixel each on a Retina display.
    static let grain: NSImage = {
        let side = 128
        guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: side, pixelsHigh: side, bitsPerSample: 8,
                                         samplesPerPixel: 1, hasAlpha: false, isPlanar: false,
                                         colorSpaceName: .deviceWhite, bytesPerRow: side, bitsPerPixel: 8),
              let pixels = rep.bitmapData else { return NSImage() }
        for index in 0..<(side * side) { pixels[index] = .random(in: 0...255) }
        let image = NSImage(size: NSSize(width: side / 2, height: side / 2))
        image.addRepresentation(rep)
        return image
    }()
}

/// Who was here last: their photo, or their initial, and their name.
private struct AccountChip: View {
    let account: LastAccount

    var body: some View {
        HStack(spacing: 8) {
            AsyncImage(url: account.photoURL.flatMap(URL.init(string:))) { phase in
                if let image = phase.image {
                    image.resizable().scaledToFill()
                } else {
                    ZStack {
                        mulberryRed
                        Text(account.name.prefix(1).uppercased())
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(.white)
                    }
                }
            }
            .frame(width: 22, height: 22)
            .clipShape(.circle)
            .overlay(Circle().strokeBorder(.white.opacity(0.12), lineWidth: 0.5))
            Text(account.name)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(.white.opacity(0.88))
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Last signed in as \(account.name)")
    }
}

/// "By continuing you agree to our Terms of Use and Privacy Policy", each opening in a sheet.
private struct LegalNote: View {
    let open: (LegalDocument) -> Void

    var body: some View {
        Text(text)
            .font(.system(size: 11.5))
            .foregroundStyle(.white.opacity(0.5))
            .tint(.white.opacity(0.72))
            .environment(\.openURL, OpenURLAction { url in
                open(url.host() == "terms" ? .terms : .privacy)
                return .handled
            })
    }

    private var text: AttributedString {
        var text = AttributedString("By continuing you agree to our ")
        text += link("Terms of Use", "terms")
        text += AttributedString(" and ")
        text += link("Privacy Policy", "privacy")
        return text
    }

    private func link(_ title: String, _ host: String) -> AttributedString {
        var link = AttributedString(title)
        link.link = URL(string: "mulberry-legal://\(host)")
        link.underlineStyle = .single
        return link
    }
}

/// Google's G and "Continue with Google" on a translucent capsule, with Return's keycap: Return
/// signs in. The sign-in window's size, or onboarding's larger one.
struct GoogleCapsule: View {
    enum Size {
        case regular, large

        var width: CGFloat? { self == .large ? 360 : nil }
        var height: CGFloat { self == .large ? 48 : 38 }
        var font: CGFloat { self == .large ? 15 : 13 }
        var mark: CGFloat { self == .large ? 18 : 15 }
        var keycap: CGSize { self == .large ? CGSize(width: 22, height: 20) : CGSize(width: 19, height: 17) }
    }

    let busy: Bool
    var size = Size.regular
    let action: () -> Void
    @State private var hovered = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: size == .large ? 10 : 9) {
                if busy {
                    ProgressView().controlSize(.small)
                } else {
                    GoogleMark().frame(width: size.mark, height: size.mark)
                }
                Text(busy ? "Waiting for Google…" : "Continue with Google")
                    .font(.system(size: size.font, weight: .medium))
                    .foregroundStyle(.white.opacity(0.94))
                if !busy {
                    Image(systemName: "return")
                        .font(.system(size: size == .large ? 10 : 9, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.6))
                        .frame(width: size.keycap.width, height: size.keycap.height)
                        .overlay(RoundedRectangle(cornerRadius: 4.5).strokeBorder(.white.opacity(0.28), lineWidth: 1))
                        .padding(.leading, 3)
                }
            }
            .padding(.leading, size.width == nil ? 16 : 0)
            .padding(.trailing, size.width == nil ? (busy ? 18 : 10) : 0)
            .frame(width: size.width, height: size.height)
            .background(.white.opacity(hovered && !busy ? 0.16 : 0.11), in: .capsule)
            .overlay(Capsule().strokeBorder(.white.opacity(0.1), lineWidth: 1))
            .shadow(color: .black.opacity(0.35), radius: 10, y: 4)
            .contentShape(.capsule)
        }
        .buttonStyle(PressScale())
        .disabled(busy)
        .onHover { hovered = $0 }
        .keyboardShortcut(.defaultAction)
        .animation(.easeOut(duration: 0.15), value: hovered)
        .animation(.easeOut(duration: 0.2), value: busy)
    }
}

/// Gives a little under the pointer.
private struct PressScale: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}
