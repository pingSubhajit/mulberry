import ActivityKit
import UIKit

/// Starts and updates the reactions Live Activity. Until Mulberry is signed with a team that can
/// receive pushes, only the app can start it; the server will start it by push with the same state.
@MainActor
enum ReactionActivities {
    /// Shows a batch in the Dynamic Island and on the Lock Screen, updating the activity already
    /// there and lighting it up again, or starting one.
    static func show(_ batch: PendingReactionBatch, latest: ReactionType, partnerName: String, partnerPhotoURL: String? = nil) async {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { log("Live Activities are off"); return }
        let counts = Dictionary(uniqueKeysWithValues: ReactionType.allCases.compactMap { type in
            batch.count(type) > 0 ? (type.rawValue, batch.count(type)) : nil
        })
        let state = ReactionActivityAttributes.ContentState(counts: counts, latest: latest.rawValue)
        // Fresh for a few minutes, like a tap on the shoulder, then it fades from the island.
        let content = ActivityContent(state: state, staleDate: .now.addingTimeInterval(5 * 60))
        if let current = Activity<ReactionActivityAttributes>.activities.first(where: { $0.activityState == .active }) {
            await current.update(content, alertConfiguration: AlertConfiguration(
                title: "\(partnerName)", body: "Sent you \(latest.spamNoun) \(latest.emoji)", sound: .default))
        } else {
            do {
                let photo = await thumbnail(partnerPhotoURL)
                _ = try Activity.request(attributes: ReactionActivityAttributes(partnerName: partnerName, partnerPhoto: photo), content: content, pushType: nil)
                startedInForeground = true
            }
            catch { log("Could not start the reactions Live Activity: \(error)") }
        }
    }

    /// Started by the app while it was open, so it has not been away from the canvas yet. The
    /// server starts real ones by push while Mulberry is closed.
    private static var startedInForeground = false

    /// Mulberry went to the background, so whatever the activity shows has not been seen.
    static func appBackgrounded() { startedInForeground = false }

    /// The reactions the activity shows that the canvas has not played yet, if there is one.
    static var unplayed: PendingReactionBatch? {
        guard !startedInForeground,
              let state = Activity<ReactionActivityAttributes>.activities.first(where: { $0.activityState == .active })?.content.state,
              state.total > 0 else { return nil }
        return PendingReactionBatch(generation: 0, counts: Dictionary(uniqueKeysWithValues: state.kinds.map { ($0.type, $0.count) }))
    }

    /// The island's Send back button: sends the reaction, then shows it as sent until more arrive.
    static func sendBack(_ type: ReactionType) async throws {
        try await AppRuntime.api().sendReaction(type)
        guard let current = Activity<ReactionActivityAttributes>.activities.first(where: { $0.activityState == .active }) else { return }
        var state = current.content.state
        state.repliedWith = type.rawValue
        await current.update(ActivityContent(state: state, staleDate: current.content.staleDate))
    }

    /// The partner's photo as a JPEG small enough for the activity's 4 KB budget: 72 px square,
    /// at the highest quality that fits in about 2 KB.
    private static func thumbnail(_ url: String?) async -> Data? {
        guard let url = url.flatMap(URL.init(string:)),
              let (data, response) = try? await URLSession.shared.data(from: url),
              (response as? HTTPURLResponse)?.statusCode == 200,
              let photo = try? ImagePreparation.image(data, maximumDimension: 256) else { return nil }
        let side: CGFloat = 72
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let square = UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { _ in
            let scale = max(side / photo.size.width, side / photo.size.height)
            let fitted = CGSize(width: photo.size.width * scale, height: photo.size.height * scale)
            photo.draw(in: CGRect(x: (side - fitted.width) / 2, y: (side - fitted.height) / 2, width: fitted.width, height: fitted.height))
        }
        for quality in stride(from: 0.7, through: 0.3, by: -0.1) {
            if let jpeg = square.jpegData(compressionQuality: quality), jpeg.count <= 2_200 { return jpeg }
        }
        return nil
    }

    private static func log(_ message: String) {
        #if DEBUG
        print("[reactions] \(message)")
        #endif
    }

    /// The reactions have been seen in the app, so they leave the island and the Lock Screen.
    static func endAll() async {
        for activity in Activity<ReactionActivityAttributes>.activities {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
    }

    #if DEBUG
    /// For trying the Live Activity on a device: launch with `liveActivity=HEART:3,KISS:1`, or
    /// `liveActivity=end`. The last listed reaction counts as the latest. Stages separated by `;`
    /// arrive 4 seconds apart and add up, as more reactions would, so going home after the first
    /// shows the island updating: `liveActivity=HEART:2;SPARKLE:1;HUG:1`.
    static func runLaunchArgument(partnerName: String, partnerPhotoURL: String?) async {
        guard let value = ProcessInfo.processInfo.arguments.first(where: { $0.hasPrefix("liveActivity=") })?.dropFirst(13) else { return }
        if value == "end" { await endAll(); return }
        // Keeps running for the later stages while the app is in the background.
        let task = UIApplication.shared.beginBackgroundTask()
        defer { UIApplication.shared.endBackgroundTask(task) }
        var counts: [ReactionType: Int] = [:]
        for (index, stage) in value.split(separator: ";").enumerated() {
            if index > 0 { try? await Task.sleep(for: .seconds(4)) }
            var latest = ReactionType.heart
            for part in stage.split(separator: ",") {
                let pair = part.split(separator: ":")
                guard let type = ReactionType(rawValue: pair[0].uppercased()) else { continue }
                counts[type, default: 0] += pair.count > 1 ? Int(pair[1]) ?? 1 : 1
                latest = type
            }
            await show(PendingReactionBatch(generation: 1, counts: counts), latest: latest, partnerName: partnerName, partnerPhotoURL: partnerPhotoURL)
        }
    }
    #endif
}
