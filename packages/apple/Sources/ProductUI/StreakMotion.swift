import SwiftUI

/// The streak screens' small motions, shared by iPhone, iPad and Mac. Brief, played once, and
/// still under Reduce Motion, where the screens only fade.
public enum StreakMotion {
    /// From the screen opening to today's moment, once everything has arrived: the day filling
    /// in, or its ring calling.
    public static let todayMoment: Duration = .milliseconds(850)
    /// From the screen opening to the light crossing the bolt, as the bolt settles.
    public static let glint: Duration = .milliseconds(250)
    /// The week's days arrive left to right, this far apart.
    public static let dayStagger = 0.035
}

public extension View {
    /// Settles into place once `arrived` turns true, after `delay`: up from `rise` points lower,
    /// from `scale`, from blurred by `blur`. Under Reduce Motion it only fades in.
    func streakEntrance(_ arrived: Bool, delay: Double = 0, rise: CGFloat = 0, from scale: CGFloat = 1,
                        blur: CGFloat = 0, animation: Animation = .smooth(duration: 0.45)) -> some View {
        modifier(StreakEntrance(arrived: arrived, delay: delay, rise: rise, scale: scale, blur: blur, animation: animation))
    }

    /// A band of light crossing the bolt, on its own pixels only, each time `trigger` changes.
    func boltGlint(_ trigger: Int) -> some View { modifier(BoltGlint(trigger: trigger)) }

    /// Squashes and springs back, as a bolt pressed, each time `trigger` changes.
    func boltSquish(_ trigger: Int) -> some View { modifier(BoltSquish(trigger: trigger)) }

    /// A hop, after `delay`, each time `trigger` changes: the toolbar bolt taking the streak back.
    func boltBounce(_ trigger: Int, after delay: Double = 0) -> some View {
        modifier(BoltBounce(trigger: trigger, delay: delay))
    }

    /// A ring rippling out once from a day still to do, each time `trigger` changes.
    func dayRipple(_ trigger: Int, color: Color) -> some View { modifier(DayRipple(trigger: trigger, color: color)) }

    /// A day swelling as it fills in, each time `trigger` changes.
    func dayPop(_ trigger: Int) -> some View { modifier(DayPop(trigger: trigger)) }
}

/// The checkmark of a day that has just filled in draws itself; under Reduce Motion it fades.
public func streakCheckTransition(reduceMotion: Bool) -> AnyTransition {
    reduceMotion ? .opacity : AnyTransition(.symbolEffect(.drawOn))
}

/// A soft light behind the bolt, as if it gave off its colour.
public struct BoltGlow: View {
    private let color: Color

    public init(color: Color) { self.color = color }

    public var body: some View {
        // Low and even through the middle, so the bolt keeps its contrast against it, and long
        // in its tail, so it spreads wide before it's gone.
        EllipticalGradient(stops: [.init(color: color.opacity(0.2), location: 0),
                                   .init(color: color.opacity(0.15), location: 0.35),
                                   .init(color: color.opacity(0.06), location: 0.7),
                                   .init(color: color.opacity(0), location: 1)],
                           center: .center, startRadiusFraction: 0, endRadiusFraction: 0.5)
            .allowsHitTesting(false)
    }
}

private struct StreakEntrance: ViewModifier {
    let arrived: Bool
    let delay: Double
    let rise: CGFloat
    let scale: CGFloat
    let blur: CGFloat
    let animation: Animation
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        let moving = !arrived && !reduceMotion
        content
            .opacity(arrived ? 1 : 0)
            .scaleEffect(moving ? scale : 1)
            .offset(y: moving ? rise : 0)
            .blur(radius: moving ? blur : 0)
            .animation(reduceMotion ? .easeOut(duration: 0.2) : animation.delay(delay), value: arrived)
    }
}

private struct BoltGlint: ViewModifier {
    let trigger: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content.overlay {
            if !reduceMotion {
                GeometryReader { geometry in
                    let size = geometry.size
                    LinearGradient(stops: [.init(color: .white.opacity(0), location: 0),
                                           .init(color: .white.opacity(0.42), location: 0.5),
                                           .init(color: .white.opacity(0), location: 1)],
                                   startPoint: .leading, endPoint: .trailing)
                        .frame(width: size.width * 0.22, height: size.height * 1.6)
                        .rotationEffect(.degrees(20))
                        // From just left of the bolt's art to just right of it, so it rests out of sight.
                        .keyframeAnimator(initialValue: -0.8, trigger: trigger) { band, travel in
                            band.offset(x: travel * size.width)
                        } keyframes: { _ in
                            LinearKeyframe(-0.8, duration: 0.01)
                            LinearKeyframe(0.8, duration: 0.95, timingCurve: .easeInOut)
                        }
                        .frame(width: size.width, height: size.height)
                }
                .blendMode(.plusLighter)
                .mask { content }
                .allowsHitTesting(false)
            }
        }
    }
}

private struct Squish {
    var x = 1.0
    var y = 1.0
}

private struct BoltSquish: ViewModifier {
    let trigger: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        if reduceMotion {
            content
        } else {
            content.keyframeAnimator(initialValue: Squish(), trigger: trigger) { view, squish in
                view.scaleEffect(x: squish.x, y: squish.y)
            } keyframes: { _ in
                KeyframeTrack(\.x) {
                    CubicKeyframe(1.06, duration: 0.09)
                    SpringKeyframe(1, duration: 0.55, spring: .bouncy(duration: 0.45, extraBounce: 0.15))
                }
                KeyframeTrack(\.y) {
                    CubicKeyframe(0.9, duration: 0.09)
                    SpringKeyframe(1, duration: 0.55, spring: .bouncy(duration: 0.45, extraBounce: 0.15))
                }
            }
        }
    }
}

private struct BoltBounce: ViewModifier {
    let trigger: Int
    let delay: Double
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        if reduceMotion {
            content
        } else {
            content.keyframeAnimator(initialValue: 1.0, trigger: trigger) { view, scale in
                view.scaleEffect(scale)
            } keyframes: { _ in
                LinearKeyframe(1, duration: delay)
                CubicKeyframe(1.28, duration: 0.14)
                SpringKeyframe(1, duration: 0.5, spring: .bouncy(duration: 0.4, extraBounce: 0.2))
            }
        }
    }
}

private struct Ripple {
    var scale = 1.0
    var opacity = 0.0
}

private struct DayRipple: ViewModifier {
    let trigger: Int
    let color: Color
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content.background {
            if !reduceMotion {
                Circle().strokeBorder(color, lineWidth: 2)
                    .keyframeAnimator(initialValue: Ripple(), trigger: trigger) { ring, ripple in
                        ring.scaleEffect(ripple.scale).opacity(ripple.opacity)
                    } keyframes: { _ in
                        KeyframeTrack(\.scale) {
                            LinearKeyframe(1, duration: 0.01)
                            CubicKeyframe(1.75, duration: 0.95)
                        }
                        KeyframeTrack(\.opacity) {
                            LinearKeyframe(0.9, duration: 0.01)
                            CubicKeyframe(0, duration: 0.95)
                        }
                    }
                    .allowsHitTesting(false)
            }
        }
    }
}

private struct DayPop: ViewModifier {
    let trigger: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        if reduceMotion {
            content
        } else {
            content.keyframeAnimator(initialValue: 1.0, trigger: trigger) { view, scale in
                view.scaleEffect(scale)
            } keyframes: { _ in
                CubicKeyframe(1.22, duration: 0.16)
                SpringKeyframe(1, duration: 0.55, spring: .bouncy(duration: 0.45, extraBounce: 0.15))
            }
        }
    }
}
