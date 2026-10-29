import SwiftUI

/// "What happens after I enter the code?", Android's pairing help: what joining does, as a short
/// timeline under the faceted heart, on Mulberry's deep red.
struct PairingHelpView: View {
    let close: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shown = false

    private static let ground = Color(red: 0x35 / 255, green: 0, blue: 0x07 / 255)
    private static let steps = [
        "After you join, you’ll both be connected instantly",
        "You can start drawing right away, what you send appears on their screen",
        "This is just between the two of you. No one else can see it",
    ]

    var body: some View {
        ZStack(alignment: .top) {
            Self.ground.ignoresSafeArea()
            Image("PairingHelpHeart").resizable().scaledToFit()
                .frame(maxWidth: 440)
                .ignoresSafeArea(edges: .top)
                .opacity(shown ? 1 : 0)
                .accessibilityHidden(true)
            ScrollView {
                VStack(spacing: 16) {
                    Text("KNOW HOW IT WORKS")
                        .font(MulberryDesign.font(11, weight: "SemiBold", relativeTo: .caption))
                        .tracking(1.2)
                        .foregroundStyle(.white)
                        .padding(.horizontal, 14).padding(.vertical, 7)
                        .background(Capsule().fill(LinearGradient(colors: [Color(red: 0.63, green: 0.45, blue: 0.05), Color(red: 0.86, green: 0.65, blue: 0.07)],
                                                                  startPoint: .leading, endPoint: .trailing)))
                        .entrance(shown, step: 0, reduceMotion: reduceMotion)
                    Text("What happens after I enter the code?")
                        .font(MulberryDesign.font(30, weight: "Medium", relativeTo: .largeTitle))
                        .foregroundStyle(.white)
                        .multilineTextAlignment(.center)
                        .accessibilityAddTraits(.isHeader)
                        .entrance(shown, step: 1, reduceMotion: reduceMotion)
                    Text("The code connects you to one person. Once connected, you’ll share a private space where you can send drawings that appear on each other’s screen.")
                        .font(MulberryDesign.font(15, relativeTo: .body))
                        .foregroundStyle(.white.opacity(0.8))
                        .multilineTextAlignment(.center)
                        .entrance(shown, step: 2, reduceMotion: reduceMotion)
                    timeline
                        .padding(.vertical, 4)
                        .entrance(shown, step: 3, reduceMotion: reduceMotion)
                    Text("You can disconnect any time you want, no question will be asked")
                        .font(MulberryDesign.font(14, weight: "SemiBold", relativeTo: .subheadline))
                        .foregroundStyle(.white)
                        .multilineTextAlignment(.center)
                        .entrance(shown, step: 4, reduceMotion: reduceMotion)
                }
                .padding(.horizontal, 32)
                .padding(.top, 196)
                .padding(.bottom, 24)
                .frame(maxWidth: 520)
                .frame(maxWidth: .infinity)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .safeAreaInset(edge: .bottom) {
            Button(action: close) {
                Text("Alright, sounds great")
                    .font(MulberryDesign.font(17, weight: "SemiBold"))
                    .foregroundStyle(Color(white: 0.35))
                    .frame(maxWidth: .infinity, minHeight: 54)
                    .background(.white, in: Capsule())
            }
            .buttonStyle(.plain)
            .frame(maxWidth: 460)
            .padding(.horizontal, 24).padding(.bottom, 12)
            .entrance(shown, step: 5, reduceMotion: reduceMotion)
        }
        .overlay(alignment: .topTrailing) {
            Button(action: close) {
                Image(systemName: "xmark")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .padding(.trailing, 12)
            .accessibilityLabel("Close")
        }
        .environment(\.colorScheme, .dark)
        .onAppear {
            withAnimation(reduceMotion ? .easeOut(duration: 0.2) : .easeOut(duration: 0.5)) { shown = true }
        }
    }

    /// The three things joining does, joined by a line through their markers.
    private var timeline: some View {
        VStack(alignment: .leading, spacing: 18) {
            ForEach(Self.steps, id: \.self) { step in
                HStack(spacing: 22) {
                    Circle().fill(Color(red: 0.81, green: 0.11, blue: 0.21))
                        .overlay(Circle().strokeBorder(Color(white: 0.89), lineWidth: 1))
                        .frame(width: 14, height: 14)
                    Text(step)
                        .font(MulberryDesign.font(13, relativeTo: .footnote))
                        .foregroundStyle(.white.opacity(0.8))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .background(alignment: .leading) {
            Rectangle().fill(Color(red: 0.5, green: 0.05, blue: 0.12))
                .frame(width: 2)
                .padding(.vertical, 12)
                .padding(.leading, 6)
        }
        .accessibilityElement(children: .combine)
    }
}

private extension View {
    /// Rises into place a beat after the block before it, or simply appears under Reduce Motion.
    func entrance(_ shown: Bool, step: Int, reduceMotion: Bool) -> some View {
        opacity(shown ? 1 : 0)
            .offset(y: shown || reduceMotion ? 0 : 18)
            .animation(reduceMotion ? .easeOut(duration: 0.2) : .spring(duration: 0.5, bounce: 0.2).delay(0.06 * Double(step)), value: shown)
    }
}
