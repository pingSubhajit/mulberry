import SwiftUI

/// Android's Virgil prompt and Squee asset, with the same 1.8-second idle bob.
struct CanvasBlankStateGuidance: View {
    let visible: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var entered = false

    private var shown: Bool { visible && entered }
    private var squirrelHeight: CGFloat { typeSize.isAccessibilitySize ? 96 : 160 }

    /// Fills the visible canvas: the prompt sits in its middle and Squee at its foot.
    var body: some View {
        ZStack(alignment: .bottomLeading) {
            VStack(spacing: 10) {
                Text("Draw them something cute")
                    .font(.custom("Virgil3YOFF", size: 20, relativeTo: .title3))
                    .foregroundStyle(MulberryDesign.accent)
                Text("Whatever you draw will appear at the wallpaper of their phone")
                    .font(.custom("Virgil3YOFF", size: 14, relativeTo: .subheadline))
                    .foregroundStyle(MulberryDesign.muted)
                    .lineSpacing(3)
                    .frame(maxWidth: 252)
            }
            .tracking(0.25)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .opacity(shown ? 1 : 0)
            .animation(reduceMotion ? nil : .easeInOut(duration: shown ? 0.14 : 0.12), value: shown)
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            Group {
                if shown && !reduceMotion && scenePhase == .active {
                    squirrel.phaseAnimator([SqueePhase.rest, .raised, .lowered]) { image, phase in
                        image.offset(y: phase == .raised ? -6 : 0)
                    } animation: { phase in
                        switch phase {
                        case .rest: .linear(duration: 0.24)
                        case .raised: .timingCurve(0, 0, 0.2, 1, duration: 0.66)
                        case .lowered: .easeIn(duration: 0.9)
                        }
                    }
                } else {
                    squirrel
                }
            }
            .offset(x: shown || reduceMotion ? -22 : -squirrelHeight * 432 / 498 - 50)
            .opacity(shown ? 1 : 0)
            .animation(reduceMotion ? nil : .timingCurve(0.4, 0, 0.2, 1, duration: shown ? 0.32 : 0.26), value: shown)
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
