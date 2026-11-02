import AVFoundation
import SwiftUI
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif
#if canImport(ProductCore)
import ProductCore
#endif

/// The streak bolt glinting, then breaking: a transparent HEVC film that rests on its first frame,
/// the whole bolt, until `start`, and ends on the broken bolt the static artwork is cut from, so
/// handing back to that picture shows no change.
public struct StreakBoltBreak: View {
    /// When the pieces split, from the film's start.
    public static let crackTime: Double = 1.28
    /// When the shards have flown enough for the words below to follow.
    public static let revealTime: Double = 1.65
    /// The bolt's share of its frame in the broken artwork, against the whole bolt's: the broken
    /// bolt scales up by this to stand as tall as the whole one did.
    public static let brokenScale: CGFloat = 1.13

    private let url: URL
    private let start: Bool
    private let onCrack: @MainActor () -> Void
    private let onReveal: @MainActor () -> Void
    private let onFinish: @MainActor () -> Void
    @State private var playback: Playback?
    @State private var ready = false

    /// - Parameters:
    ///   - start: plays the film once, from when this turns true.
    ///   - onCrack: as the bolt splits, for a haptic.
    ///   - onReveal: once the shards have flown, for the words below.
    ///   - onFinish: on the last frame.
    public init(url: URL, start: Bool, onCrack: @escaping @MainActor () -> Void = {},
                onReveal: @escaping @MainActor () -> Void = {}, onFinish: @escaping @MainActor () -> Void = {}) {
        self.url = url
        self.start = start
        self.onCrack = onCrack
        self.onReveal = onReveal
        self.onFinish = onFinish
    }

    public var body: some View {
        Group {
            if let playback {
                PlayerLayerView(player: playback.player) { ready = true }
            } else {
                Color.clear
            }
        }
        // Nothing until the first frame is on screen, so the bolt never flashes empty.
        .opacity(ready ? 1 : 0)
        .animation(.easeOut(duration: 0.15), value: ready)
        .onAppear { prepare() }
        .onDisappear { playback?.stop() }
        .onChange(of: start, initial: true) { _, start in
            guard start else { return }
            prepare().play(onCrack: onCrack, onReveal: onReveal, onFinish: onFinish)
        }
    }

    @discardableResult
    private func prepare() -> Playback {
        if let playback { return playback }
        let created = Playback(url: url)
        playback = created
        return created
    }
}

/// One playing of the film, with its moments.
@MainActor
private final class Playback {
    let player: AVPlayer
    private var timeObservers: [Any] = []
    private var endObserver: NSObjectProtocol?
    private var started = false

    init(url: URL) {
        let item = AVPlayerItem(url: url)
        player = AVPlayer(playerItem: item)
        // A silent film: it never takes the audio from whatever else is playing.
        player.isMuted = true
        player.actionAtItemEnd = .pause
        player.preventsDisplaySleepDuringVideoPlayback = false
    }

    func play(onCrack: @escaping @MainActor () -> Void, onReveal: @escaping @MainActor () -> Void,
              onFinish: @escaping @MainActor () -> Void) {
        guard !started else { return }
        started = true
        for (time, action) in [(StreakBoltBreak.crackTime, onCrack), (StreakBoltBreak.revealTime, onReveal)] {
            let moment = NSValue(time: CMTime(seconds: time, preferredTimescale: 600))
            timeObservers.append(player.addBoundaryTimeObserver(forTimes: [moment], queue: .main) {
                MainActor.assumeIsolated { action() }
            })
        }
        endObserver = NotificationCenter.default.addObserver(
            forName: AVPlayerItem.didPlayToEndTimeNotification, object: player.currentItem, queue: .main
        ) { _ in
            MainActor.assumeIsolated { onFinish() }
        }
        player.play()
    }

    func stop() {
        player.pause()
        for observer in timeObservers { player.removeTimeObserver(observer) }
        timeObservers = []
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        endObserver = nil
    }
}

/// An `AVPlayerLayer` that keeps the film's alpha: it asks for BGRA frames and draws over a clear
/// background.
#if canImport(UIKit)
private struct PlayerLayerView: UIViewRepresentable {
    let player: AVPlayer
    let onReady: @MainActor () -> Void

    func makeUIView(context: Context) -> LayerView {
        let view = LayerView()
        view.backgroundColor = .clear
        view.isOpaque = false
        view.isUserInteractionEnabled = false
        context.coordinator.attach(player: player, to: view.playerLayer, onReady: onReady)
        return view
    }

    func updateUIView(_ view: LayerView, context: Context) {}

    func makeCoordinator() -> PlayerLayerCoordinator { PlayerLayerCoordinator() }

    final class LayerView: UIView {
        override static var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }
}
#else
private struct PlayerLayerView: NSViewRepresentable {
    let player: AVPlayer
    let onReady: @MainActor () -> Void

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        let layer = AVPlayerLayer()
        view.layer = layer
        view.wantsLayer = true
        context.coordinator.attach(player: player, to: layer, onReady: onReady)
        return view
    }

    func updateNSView(_ view: NSView, context: Context) {}

    func makeCoordinator() -> PlayerLayerCoordinator { PlayerLayerCoordinator() }
}
#endif

@MainActor
private final class PlayerLayerCoordinator {
    private var readiness: NSKeyValueObservation?

    func attach(player: AVPlayer, to layer: AVPlayerLayer, onReady: @escaping @MainActor () -> Void) {
        layer.pixelBufferAttributes = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        layer.videoGravity = .resizeAspect
        layer.backgroundColor = CGColor(gray: 0, alpha: 0)
        layer.isOpaque = false
        layer.player = player
        readiness = layer.observe(\.isReadyForDisplay, options: [.initial, .new]) { layer, _ in
            guard layer.isReadyForDisplay else { return }
            Task { @MainActor in onReady() }
        }
    }
}

/// One showing of the streak's bolt: whether this showing breaks it, and how far along it is.
/// Both apps' streak screens hold one, and decide once their refresh has the server's word.
@MainActor
public final class StreakBreakShowing: ObservableObject {
    public enum Phase: Sendable { case undecided, waiting, playing, done }

    @Published public private(set) var phase = Phase.undecided
    /// The words under the bolt, which wait for the shards.
    @Published public private(set) var copyRevealed = false
    /// Counts the cracks, for a haptic.
    @Published public private(set) var cracks = 0

    public init() {}

    /// The film while the bolt is about to break or breaking; for every other showing, and once it
    /// has broken, the artwork it ends on.
    public func showsFilm(_ streaks: StreakModel, film: URL?, reduceMotion: Bool) -> Bool {
        guard film != nil else { return false }
        switch phase {
        case .undecided: return streaks.breakPending && !reduceMotion
        case .waiting, .playing: return true
        case .done: return false
        }
    }

    public func hidesCopy(_ streaks: StreakModel, film: URL?, reduceMotion: Bool) -> Bool {
        showsFilm(streaks, film: film, reduceMotion: reduceMotion) && !copyRevealed
    }

    /// Breaks the bolt if this account hasn't watched it yet. Call once the screen's refresh is
    /// done, so another device's viewing counts.
    public func decide(_ streaks: StreakModel, film: URL?, reduceMotion: Bool, api: () -> BackendClient?,
                       presentation: Duration = .milliseconds(350)) async {
        guard phase == .undecided else { return }
        guard streaks.breakPending else { phase = .done; return }
        guard !reduceMotion, film != nil else {
            // The broken bolt, still, counts as seeing it.
            if let api = api() { streaks.breakShown(api: api) }
            phase = .done
            return
        }
        phase = .waiting
        // Lets the screen finish opening first.
        try? await Task.sleep(for: presentation)
        guard !Task.isCancelled else { return }
        if let api = api() { streaks.breakShown(api: api) }
        phase = .playing
    }

    public func cracked() { cracks += 1 }
    public func reveal() { copyRevealed = true }
    public func finished() { phase = .done }
}
