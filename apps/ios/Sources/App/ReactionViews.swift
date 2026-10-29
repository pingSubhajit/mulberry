import SwiftUI
import ImageIO

/// A reaction's animated artwork, decoded once per size and kept while memory allows.
final class ReactionFrames: @unchecked Sendable {
    let images: [CGImage]
    /// When each frame ends, in seconds from the start of the loop.
    private let ends: [Double]
    var duration: Double { ends.last ?? 1 }

    private init(images: [CGImage], ends: [Double]) { self.images = images; self.ends = ends }

    func frame(at time: TimeInterval) -> CGImage {
        let t = time.truncatingRemainder(dividingBy: max(duration, 0.01))
        return images[min(images.count - 1, ends.firstIndex { $0 > t } ?? 0)]
    }

    private static let cache: NSCache<NSString, ReactionFrames> = {
        let cache = NSCache<NSString, ReactionFrames>()
        cache.totalCostLimit = 96 << 20
        return cache
    }()

    /// Pixel sizes the artwork is decoded at; the 512 px originals are larger than any use needs.
    static func pixels(for points: CGFloat, scale: CGFloat) -> Int {
        let needed = points * scale
        return needed <= 150 ? 144 : needed <= 300 ? 288 : 400
    }

    static func load(_ type: ReactionType, pixels: Int) async -> ReactionFrames? {
        let key = "\(type.rawValue)-\(pixels)" as NSString
        if let cached = cache.object(forKey: key) { return cached }
        guard let data = NSDataAsset(name: type.assetName)?.data else { return nil }
        let frames = await Task.detached(priority: .userInitiated) { decode(data, pixels: pixels) }.value
        if let frames { cache.setObject(frames, forKey: key, cost: frames.images.count * pixels * pixels * 4) }
        return frames
    }

    private static func decode(_ data: Data, pixels: Int) -> ReactionFrames? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                        kCGImageSourceThumbnailMaxPixelSize: pixels,
                                        kCGImageSourceShouldCacheImmediately: true]
        var images: [CGImage] = [], ends: [Double] = [], elapsed = 0.0
        for index in 0..<CGImageSourceGetCount(source) {
            guard let image = CGImageSourceCreateThumbnailAtIndex(source, index, options as CFDictionary) else { continue }
            let gif = (CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [CFString: Any])?[kCGImagePropertyGIFDictionary] as? [CFString: Any]
            let delay = (gif?[kCGImagePropertyGIFUnclampedDelayTime] as? Double) ?? (gif?[kCGImagePropertyGIFDelayTime] as? Double) ?? 0.1
            elapsed += delay > 0.011 ? delay : 0.1
            images.append(image); ends.append(elapsed)
        }
        return images.isEmpty ? nil : ReactionFrames(images: images, ends: ends)
    }
}

extension ReactionType {
    var assetName: String { "Reaction" + rawValue.prefix(1) + rawValue.dropFirst().lowercased() }
}

/// A reaction's artwork at a size. It loops from when it appears, or shows the frame for a
/// given time when a parent drives the animation. Reduce Motion holds a single frame.
struct ReactionArtwork: View {
    let type: ReactionType
    let size: CGFloat
    /// Seconds into the loop; nil loops on its own.
    var time: TimeInterval?
    @State private var frames: ReactionFrames?
    @State private var start = Date()
    @Environment(\.displayScale) private var displayScale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Group {
            if let frames {
                if reduceMotion { still(frames.frame(at: frames.duration * 0.5)) }
                else if let time { still(frames.frame(at: time)) }
                else {
                    TimelineView(.animation) { context in still(frames.frame(at: context.date.timeIntervalSince(start))) }
                }
            } else {
                // Until the artwork is decoded, the emoji stands in, as on Android.
                Text(type.emoji).font(.system(size: size * 0.7))
            }
        }
        .frame(width: size, height: size)
        .task(id: ReactionFrames.pixels(for: size, scale: displayScale)) {
            frames = await ReactionFrames.load(type, pixels: ReactionFrames.pixels(for: size, scale: displayScale))
        }
    }

    private func still(_ image: CGImage) -> some View {
        Image(decorative: image, scale: 1).resizable().interpolation(.high).scaledToFit()
    }
}

/// The six reactions in a row. Each pops up in turn, as Android's rail does.
struct ReactionPicker: View {
    let send: (ReactionType) -> Void
    @State private var shown = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 6) {
            ForEach(Array(ReactionType.allCases.enumerated()), id: \.element) { index, type in
                Button { send(type) } label: {
                    ReactionArtwork(type: type, size: 44)
                        .frame(width: 52, height: 52)
                        .contentShape(Circle())
                }
                .buttonStyle(ReactionButtonStyle())
                .accessibilityLabel(type.sendLabel)
                .scaleEffect(shown || reduceMotion ? 1 : 0.72)
                .offset(y: shown || reduceMotion ? 0 : 24)
                .opacity(shown || reduceMotion ? 1 : 0)
                .animation(.spring(duration: 0.32, bounce: 0.35).delay(0.04 + Double(index) * 0.035), value: shown)
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .onAppear { shown = true }
    }
}

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

private struct ReactionButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.86 : 1)
            .animation(.snappy(duration: 0.12), value: configuration.isPressed)
    }
}

/// The reaction just sent rises, wiggles and drops away, after Android's sent overlay.
struct ReactionSentBurst: View {
    let sent: ReactionsModel.Sent
    let done: () -> Void
    @State private var start = Date()
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private static let slide = 0.17, hold = 0.38, fall = 0.26
    private static let duration = slide + hold + fall + 0.05

    var body: some View {
        TimelineView(.animation) { context in
            let t = context.date.timeIntervalSince(start)
            let pose = reduceMotion ? Self.stillPose(t) : Self.pose(t)
            ReactionArtwork(type: sent.type, size: 128, time: t)
                .rotationEffect(.degrees(pose.rotation))
                .scaleEffect(pose.scale)
                .offset(x: pose.x, y: pose.y)
                .opacity(pose.opacity)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .sensoryFeedback(.impact(weight: .light), trigger: sent)
        .task(id: sent) {
            start = .now
            try? await Task.sleep(for: .seconds(Self.duration))
            if !Task.isCancelled { done() }
        }
    }

    private struct Pose { var x = 0.0, y = 0.0, scale = 1.0, rotation = 0.0, opacity = 1.0 }

    private static func pose(_ t: Double) -> Pose {
        let rise = ease(min(1, t / slide)), out = ease(min(1, max(0, (t - slide - hold) / fall)))
        let leaving = t > slide + hold
        // Rises 30 pt, sways while held, then drops 34 pt as it fades.
        let y = 18 - 30 * rise + 34 * out
        let swayEnvelope = min(1, t / slide) * (leaving ? 1 - 0.75 * out : 1)
        let x = leaving ? 10 * sin(out * .pi) - 6 * out * out : 0
        return Pose(x: x, y: y, scale: 0.92 + 0.14 * rise - 0.11 * out,
                     rotation: sin(2 * .pi * 3.5 * t) * 5 * swayEnvelope,
                     opacity: min(1, t / 0.11) * (1 - out))
    }

    private static func stillPose(_ t: Double) -> Pose {
        Pose(opacity: min(1, t / 0.15) * (1 - min(1, max(0, (t - slide - hold) / fall))))
    }

    private static func ease(_ x: Double) -> Double { x < 0.5 ? 2 * x * x : 1 - pow(-2 * x + 2, 2) / 2 }
}

/// The partner's reactions arriving, as Android plays them on the wallpaper: they rise into
/// place, bob together for a moment, then drift down like leaves and fade.
struct ReactionArrivalView: View {
    let arrival: ReactionsModel.Arrival
    let done: () -> Void
    @State private var start = Date()
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private static let slide = 0.26, wiggle = 1.2, fall = 0.85, fadeIn = 0.16, fadeOut = 0.65
    static let duration = slide + wiggle + fall

    var body: some View {
        let arrangement = ReactionArrangement(arrival.batch)
        GeometryReader { geometry in
            let size = geometry.size
            let base = max(28, min(size.width, size.height) * 0.24)
            TimelineView(.animation) { context in
                let t = context.date.timeIntervalSince(start)
                let motion = reduceMotion ? Motion(opacity: Self.opacity(t)) : Self.motion(t, size: size)
                ZStack {
                    ForEach(Array(arrangement.glyphs.enumerated()), id: \.offset) { index, glyph in
                        ReactionArtwork(type: glyph.type, size: base * glyph.scale, time: t)
                            .rotationEffect(.degrees(reduceMotion ? 0 : sin(2 * .pi * 3.2 * t + Double(index) * 0.7) * motion.wiggle))
                            .position(x: size.width * glyph.x + motion.x, y: size.height * glyph.y + motion.y)
                    }
                    if let caption = arrangement.caption {
                        // The caption holds still while the reactions move.
                        Text(caption)
                            .font(.custom("Virgil3YOFF", size: max(18, min(size.width, size.height) * 0.045), relativeTo: .title3))
                            .foregroundStyle(MulberryDesign.ink.opacity(0.92))
                            .multilineTextAlignment(.center)
                            // Clear of the phone's action buttons at either side.
                            .padding(.horizontal, 72)
                            .position(x: size.width / 2, y: size.height * 0.76)
                    }
                }
                .opacity(motion.opacity)
            }
        }
        .allowsHitTesting(false)
        .accessibilityElement()
        .accessibilityLabel(Self.announcement(arrival.batch))
        .sensoryFeedback(.impact(weight: .medium), trigger: arrival)
        .task(id: arrival) {
            start = .now
            AccessibilityNotification.Announcement(Self.announcement(arrival.batch)).post()
            try? await Task.sleep(for: .seconds(Self.duration))
            if !Task.isCancelled { done() }
        }
    }

    private struct Motion { var x = 0.0, y = 0.0, wiggle = 0.0, opacity = 1.0 }

    private static func motion(_ t: Double, size: CGSize) -> Motion {
        let height = size.height
        let falling = max(0, min(1, (t - slide - wiggle) / fall))
        let start = height * 0.06, raised = -height * 0.035, end = height * 0.09
        let y: Double
        if t < slide { y = start + (raised - start) * easeOutBack(t / slide) }
        else if t < slide + wiggle { y = raised + sin(2 * .pi * 3.2 * (t - slide)) * height * 0.006 }
        else { y = raised + (end - raised) * falling * falling }
        let x = t < slide + wiggle ? 0 : sin(2 * .pi * 1.15 * (t - slide - wiggle)) * size.width * 0.035 * (1 - falling * 0.35)
        let wiggleAmount = 7.5 * min(1, t / slide) * (t < slide + wiggle ? 1 : 1 - falling * 0.55)
        return Motion(x: x, y: y, wiggle: wiggleAmount, opacity: opacity(t))
    }

    private static func opacity(_ t: Double) -> Double {
        let fadeStart = slide + wiggle + fall - fadeOut
        if t < fadeIn { return t / fadeIn }
        if t < fadeStart { return 1 }
        let progress = min(1, (t - fadeStart) / fadeOut)
        return 1 - progress * progress
    }

    private static func easeOutBack(_ x: Double) -> Double {
        let c1 = 1.70158, c3 = c1 + 1
        return 1 + c3 * pow(x - 1, 3) + c1 * pow(x - 1, 2)
    }

    private static func announcement(_ batch: PendingReactionBatch) -> String {
        let parts = ReactionType.allCases.filter { batch.count($0) > 0 }.map { "\(batch.count($0)) \($0.emoji)" }
        return "Your partner sent " + parts.joined(separator: ", ")
    }
}
