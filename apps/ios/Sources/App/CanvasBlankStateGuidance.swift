import SwiftUI

/// Android's Virgil prompt and Squee asset, with the same 1.8-second idle bob.
struct CanvasBlankStateGuidance: View {
    let visible: Bool
    /// Where Squee's left edge sits, when something else stands at the canvas's left foot.
    /// Clear glass magnifies a picture this detailed into a blot, so Squee keeps clear of it.
    var squirrelLeading: CGFloat?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var entered = false

    private var shown: Bool { visible && entered }
    private var bobbing: Bool { shown && !reduceMotion && scenePhase == .active }
    /// The iPad's larger canvas gets a larger prompt.
    private let pad = UIDevice.current.userInterfaceIdiom == .pad
    private var squirrelHeight: CGFloat { typeSize.isAccessibilitySize ? 96 : 160 }

    /// Fills the visible canvas: the prompt sits in its middle and Squee at its foot.
    var body: some View {
        ZStack(alignment: .bottomLeading) {
            VStack(spacing: 10) {
                Text("Draw them something cute")
                    .font(.custom("Virgil3YOFF", size: pad ? 24 : 20, relativeTo: .title3))
                    .foregroundStyle(MulberryDesign.accent)
                Text("Whatever you draw will appear at the wallpaper of their phone")
                    .font(.custom("Virgil3YOFF", size: pad ? 17 : 14, relativeTo: .subheadline))
                    .foregroundStyle(MulberryDesign.muted)
                    .lineSpacing(3)
                    .frame(maxWidth: pad ? 300 : 252)
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
            .offset(x: squirrelLeading.map { $0 - 32 } ?? -22)
            // Ducks down where it stands as the first stroke lands, staying on the canvas, and pops
            // back up when the canvas clears.
            .scaleEffect(shown || reduceMotion ? 1 : 0.85, anchor: .bottom)
            .offset(y: shown || reduceMotion ? 0 : 24)
            .opacity(shown ? 1 : 0)
            .animation(reduceMotion ? .easeInOut(duration: 0.15) : shown ? .spring(duration: 0.4, bounce: 0.3) : .easeIn(duration: 0.18), value: shown)
        }
        .padding(.horizontal, 32)
        .allowsHitTesting(false)
        .accessibilityElement(children: .combine)
        .accessibilityHidden(!visible)
        .onAppear { entered = true }
    }

    private var squirrel: some View {
        Image("CanvasSquirrel").resizable().scaledToFit()
            .frame(width: squirrelHeight * 432 / 498, height: squirrelHeight)
            .accessibilityHidden(true)
    }

    private enum SqueePhase: CaseIterable { case rest, raised, lowered }
}
