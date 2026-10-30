import SwiftUI

/// The reactions on a Liquid Glass arc over a finger held on the phone's canvas, blooming out of
/// the fingertip like the Pencil's squeeze palette. Slide onto one and let go, or lift and tap one.
struct ReactionRadialPicker: View {
    @ObservedObject var pointer: ReactionPointer
    /// Opens under the finger instead of over it, for a finger near the top of the screen.
    var below = false
    /// How far the finger sits right of the arc's centre, when the arc is nudged to stay on screen.
    var tipOffset: CGFloat = 0
    /// Where this view's frame starts in the pointer's coordinates.
    var origin: CGPoint = .zero
    let send: (ReactionType) -> Void
    let dismiss: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var opened = false
    /// The reaction under the sliding finger.
    @State private var hovered: ReactionType?

    static let radius: CGFloat = 118
    static let thickness: CGFloat = 64
    static var reach: CGFloat { radius + thickness / 2 }

    /// Left to right over the finger, turned 20° toward the left like the Pencil's palette; mirrored when below.
    private var angles: [Double] {
        let start = below ? 185.0 : 175.0, end = below ? 35.0 : 325.0
        let count = ReactionType.allCases.count
        return (0..<count).map { start + (end - start) * Double($0) / Double(count - 1) }
    }

    var body: some View {
        let side = Self.reach * 2
        let shown = opened || reduceMotion
        let tip = CGPoint(x: side / 2 + tipOffset, y: side / 2)
        let items = Array(ReactionType.allCases.enumerated())
        ZStack {
            Color.clear
                .glassEffect(.regular, in: ArcBand(radius: Self.radius, thickness: Self.thickness,
                                                   from: angles.first ?? 0, to: angles.last ?? 0,
                                                   progress: shown ? 1 : 0.8))
                .scaleEffect(shown ? 1 : 0.2, anchor: UnitPoint(x: tip.x / side, y: tip.y / side))
                .opacity(shown ? 1 : 0)
                .animation(reduceMotion ? nil : .spring(duration: 0.24, bounce: 0.15), value: opened)
                .accessibilityHidden(true)
            ForEach(items, id: \.element) { index, type in
                Button { send(type) } label: {
                    ReactionArtwork(type: type, size: 42)
                        .frame(width: 54, height: 54)
                        .contentShape(Circle())
                }
                .buttonStyle(ReactionButtonStyle())
                .accessibilityLabel(type.sendLabel)
                // VoiceOver reads along the arc, left to right.
                .accessibilitySortPriority(Double(items.count - index))
                // Lifts toward the finger sliding over it.
                .scaleEffect(hovered == type ? 1.3 : 1)
                .offset(y: hovered == type ? (below ? 8 : -8) : 0)
                .animation(.snappy(duration: 0.16), value: hovered)
                .scaleEffect(shown ? 1 : 0.3)
                .opacity(shown ? 1 : 0)
                // Each flies out of the fingertip to its place on the arc, a beat after the one before.
                .position(shown ? point(at: angles[index], in: side) : tip)
                .animation(reduceMotion ? nil : .spring(duration: 0.26, bounce: 0.22).delay(Double(index) * 0.014), value: opened)
            }
        }
        .frame(width: side, height: side)
        .sensoryFeedback(.selection, trigger: hovered) { _, new in new != nil }
        .onChange(of: pointer.location) { _, location in hovered = location.flatMap { reaction(near: $0, in: side) } }
        .onChange(of: pointer.lifts) { _, _ in
            if let hovered { send(hovered) }
            hovered = nil
        }
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
        .accessibilityLabel("Reactions")
        .accessibilityAction(.escape, dismiss)
        .onAppear { opened = true }
    }

    private func point(at degrees: Double, in side: CGFloat) -> CGPoint {
        let radians = degrees * .pi / 180
        return CGPoint(x: side / 2 + Self.radius * cos(radians), y: side / 2 + Self.radius * sin(radians))
    }

    /// The reaction whose place on the arc is closest to the finger, if the finger is on the band.
    private func reaction(near location: CGPoint, in side: CGFloat) -> ReactionType? {
        let local = CGPoint(x: location.x - origin.x, y: location.y - origin.y)
        let nearest = ReactionType.allCases.indices.min { a, b in
            distance(local, point(at: angles[a], in: side)) < distance(local, point(at: angles[b], in: side))
        }
        guard let nearest, distance(local, point(at: angles[nearest], in: side)) < Self.thickness * 0.6 else { return nil }
        return ReactionType.allCases[nearest]
    }

    private func distance(_ a: CGPoint, _ b: CGPoint) -> CGFloat { hypot(a.x - b.x, a.y - b.y) }
}
