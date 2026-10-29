import AppKit
import SwiftUI

/// The empty canvas's invitation, from the iPhone and Android: the Virgil prompt in the middle and
/// Squee with his pencil at the canvas's foot, bobbing on Android's 1.8-second idle. Squee ducks
/// away as the first stroke lands and pops back up when the canvas clears.
struct CanvasBlankStateGuidance: View {
    let visible: Bool
    /// Room kept at the canvas's foot for the window's tools.
    var footInset: CGFloat = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.appearsActive) private var appearsActive
    @State private var entered = false

    private var shown: Bool { visible && entered }
    private var bobbing: Bool { shown && !reduceMotion && appearsActive }
    private let squirrelHeight: CGFloat = 150

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            VStack(spacing: 10) {
                Text("Draw them something cute")
                    .font(.custom("Virgil3YOFF", size: 22, relativeTo: .title3))
                    .foregroundStyle(mulberryRed)
                Text("Whatever you draw will appear on their wallpaper")
                    .font(.custom("Virgil3YOFF", size: 15, relativeTo: .subheadline))
                    .foregroundStyle(.secondary)
                    .lineSpacing(3)
                    .frame(maxWidth: 260)
            }
            .tracking(0.25)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .opacity(shown ? 1 : 0)
            .animation(.easeInOut(duration: shown ? 0.25 : 0.15), value: shown)
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            // One view throughout, so nothing cross-fades: the bob simply rests while Squee is away.
            squirrel.phaseAnimator([SqueePhase.rest, .raised, .lowered]) { image, phase in
                image.offset(y: bobbing && phase == .raised ? -6 : 0)
            } animation: { phase in
                switch phase {
                case .rest: .linear(duration: 0.24)
                case .raised: .timingCurve(0, 0, 0.2, 1, duration: 0.66)
                case .lowered: .easeIn(duration: 0.9)
                }
            }
            .offset(x: -14)
            .scaleEffect(shown || reduceMotion ? 1 : 0.85, anchor: .bottom)
            .offset(y: shown || reduceMotion ? 0 : 24)
            .opacity(shown ? 1 : 0)
            .animation(reduceMotion ? .easeInOut(duration: 0.15) : shown ? .spring(duration: 0.4, bounce: 0.3) : .easeIn(duration: 0.18), value: shown)
            .padding(.bottom, footInset)
        }
        .padding(.horizontal, 24)
        .allowsHitTesting(false)
        .accessibilityElement(children: .combine)
        .accessibilityHidden(!visible)
        .onAppear { entered = true }
    }

    @ViewBuilder private var squirrel: some View {
        if let image = CanvasArtwork.squee {
            Image(nsImage: image).resizable().scaledToFit()
                .frame(width: squirrelHeight * 432 / 498, height: squirrelHeight)
                .accessibilityHidden(true)
        }
    }

    private enum SqueePhase: CaseIterable { case rest, raised, lowered }
}

/// The canvas's own artwork, shipped in Resources/Canvas.
@MainActor
enum CanvasArtwork {
    static let squee: NSImage? = AppResources.url("Canvas/squee_with_pencil.png").flatMap(NSImage.init(contentsOf:))
}
