import ImageIO
import ProductCore
import SwiftUI

// The iPhone and iPad's reaction artwork and picker, from apps/ios/Sources/App/ReactionViews.swift,
// with the same GIFs, shipped in Resources/Reactions.

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

    /// NSCache is thread-safe, though not marked Sendable.
    nonisolated(unsafe) private static let cache: NSCache<NSString, ReactionFrames> = {
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
        guard let url = AppResources.url("Reactions/\(type.artworkName).gif") else { return nil }
        let frames = await Task.detached(priority: .userInitiated) {
            (try? Data(contentsOf: url)).flatMap { decode($0, pixels: pixels) }
        }.value
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
    /// The artwork's file name, as the iOS asset catalog names it.
    var artworkName: String { "Reaction" + rawValue.prefix(1) + rawValue.dropFirst().lowercased() }
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
                .help(type.sendLabel)
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
                            .foregroundStyle(.primary.opacity(0.92))
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 48)
                            .position(x: size.width / 2, y: size.height * 0.76)
                    }
                }
                .opacity(motion.opacity)
            }
        }
        .allowsHitTesting(false)
        .accessibilityElement()
        .accessibilityLabel(Self.announcement(arrival.batch))
        .task(id: arrival) {
            start = .now
            Haptics.tap()
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

    static func announcement(_ batch: PendingReactionBatch) -> String {
        let parts = ReactionType.allCases.filter { batch.count($0) > 0 }.map { "\(batch.count($0)) \($0.emoji)" }
        return "Your partner sent " + parts.joined(separator: ", ")
    }
}

/// The partner's reactions arriving, and the one just sent leaving, over a canvas.
struct ReactionStage: View {
    @ObservedObject var reactions: ReactionsModel
    let surface: ReactionsModel.Surface

    var body: some View {
        ZStack {
            if reactions.surface == surface, let arrival = reactions.arrival {
                ReactionArrivalView(arrival: arrival) { Task { await reactions.played(arrival) } }
                    .id(arrival.id)
            }
            if reactions.surface == surface, let sent = reactions.sent {
                ReactionSentBurst(sent: sent) { reactions.finishedSending(sent) }
                    .id(sent.id)
            }
        }
        .allowsHitTesting(false)
    }
}
