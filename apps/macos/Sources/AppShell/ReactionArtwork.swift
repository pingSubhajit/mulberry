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
