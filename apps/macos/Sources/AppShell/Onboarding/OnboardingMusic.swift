import AppKit
import AVFoundation

/// The soft acoustic guitar under onboarding. It fades in, carries on past its two minutes by
/// crossfading into itself, dips while Google's sign-in is open, and fades away as onboarding ends.
/// It stays silent with VoiceOver running, and when the licensed track is not in this build: the
/// track is not in the repository, so a build without it is simply quiet.
@MainActor
final class OnboardingMusic {
    static let volume: Float = 0.55
    private static let crossfade: TimeInterval = 4

    private var players: [AVAudioPlayer] = []
    private var current: AVAudioPlayer?
    private var loopTimer: Timer?
    private var target: Float = OnboardingMusic.volume
    private(set) var muted = false
    private(set) var playing = false

    static var available: Bool { url != nil }

    private static var url: URL? { AppResources.url("Onboarding/Music.m4a") }

    func start() {
        guard !playing, let url = Self.url, !NSWorkspace.shared.isVoiceOverEnabled,
              let first = try? AVAudioPlayer(contentsOf: url), let second = try? AVAudioPlayer(contentsOf: url) else { return }
        players = [first, second]
        playing = true
        begin(0, fade: 3)
    }

    /// Quieter while something else needs the person's attention, such as signing in.
    func duck(_ ducked: Bool) {
        target = ducked ? Self.volume * 0.3 : Self.volume
        guard !muted else { return }
        current?.setVolume(target, fadeDuration: 1)
    }

    func setMuted(_ muted: Bool) {
        self.muted = muted
        current?.setVolume(muted ? 0 : target, fadeDuration: 0.4)
    }

    /// Fades away, slowly, then stops.
    func fadeOut(over duration: TimeInterval = 3.5) {
        guard playing else { return }
        playing = false
        loopTimer?.invalidate()
        loopTimer = nil
        let fading = players
        fading.forEach { $0.setVolume(0, fadeDuration: duration) }
        DispatchQueue.main.asyncAfter(deadline: .now() + duration + 0.1) { [weak self] in
            fading.forEach { $0.stop() }
            if self?.playing == false { self?.players = [] }
        }
    }

    private func begin(_ index: Int, fade: TimeInterval) {
        let player = players[index]
        player.currentTime = 0
        player.volume = 0
        player.play()
        player.setVolume(muted ? 0 : target, fadeDuration: fade)
        current = player
        // Before the end, the other copy fades in as this one fades out.
        let wait = max(1, player.duration - Self.crossfade)
        loopTimer = Timer.scheduledTimer(withTimeInterval: wait, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.playing, self.players.count == 2 else { return }
                self.players[index].setVolume(0, fadeDuration: Self.crossfade)
                self.begin(1 - index, fade: Self.crossfade)
            }
        }
    }
}
