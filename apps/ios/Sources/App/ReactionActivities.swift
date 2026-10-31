import ActivityKit
import UIKit

/// The reactions Live Activity. The server starts it by push while Mulberry is closed, and updates
/// it as more reactions arrive, with the same content state the app would show; it ends once the
/// reactions are seen in the app. The app passes the server the tokens it needs for that.
@MainActor
enum ReactionActivities {
    /// The activities whose tokens are followed, and the last push token each reported.
    private static var followed: [String: String?] = [:]

    /// Follows the push-to-start token and every activity's push token, from launch, so an activity
    /// the server starts while Mulberry is in the background reports its token too.
    static func followTokens() {
        Task {
            for await token in Activity<ReactionActivityAttributes>.pushToStartTokenUpdates {
                PushRegistration.shared.liveActivityTokenChanged(.pushToStart(hex(token)))
            }
        }
        Task {
            for await activity in Activity<ReactionActivityAttributes>.activityUpdates {
                follow(activity)
                // Started while Mulberry is open: the canvas plays these reactions, or already has.
                if UIApplication.shared.applicationState == .active {
                    startedWhileOpen = true
                    NotificationCenter.default.post(name: .mulberryReactionPush, object: nil)
                }
            }
        }
        let open = Activity<ReactionActivityAttributes>.activities.filter { [.active, .stale].contains($0.activityState) }
        // One that ended while Mulberry was not running, as after iOS's eight hours, is no longer there to update.
        if open.isEmpty { PushRegistration.shared.liveActivityTokenChanged(.activity(nil)) }
        open.forEach(follow)
    }

    private static func follow(_ activity: Activity<ReactionActivityAttributes>) {
        guard followed[activity.id] == nil else { return }
        followed[activity.id] = .some(nil)
        Task {
            for await token in activity.pushTokenUpdates {
                followed[activity.id] = hex(token)
                PushRegistration.shared.liveActivityTokenChanged(.activity(hex(token)))
            }
        }
        Task {
            for await state in activity.activityStateUpdates where state == .ended || state == .dismissed {
                guard followed.removeValue(forKey: activity.id) != nil else { break }
                // Another still showing keeps its token; with none, the next reactions start one.
                PushRegistration.shared.liveActivityTokenChanged(.activity(followed.values.compactMap { $0 }.first))
                break
            }
        }
    }

    private static func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }

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
                let attributes = ReactionActivityAttributes(partnerName: partnerName, partnerPhoto: photo)
                // With a push token the server can update it too; a build signed without push cannot have one.
                let activity = try (try? Activity.request(attributes: attributes, content: content, pushType: .token))
                    ?? Activity.request(attributes: attributes, content: content, pushType: nil)
                follow(activity)
                startedInForeground = true
            }
            catch { log("Could not start the reactions Live Activity: \(error)") }
        }
    }

    /// Started by the app while it was open, so it has not been away from the canvas yet. The
    /// server starts the others by push.
    private static var startedInForeground = false

    /// Started by push while Mulberry was open, for reactions the canvas plays itself.
    private(set) static var startedWhileOpen = false

    /// Mulberry went to the background, so whatever the activity shows has not been seen.
    static func appBackgrounded() { startedInForeground = false; startedWhileOpen = false }

    /// The reactions the activity shows that the canvas has not played yet, if there is one.
    static var unplayed: PendingReactionBatch? {
        guard !startedInForeground, !startedWhileOpen,
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

    /// The reactions have been seen in the app, so they leave the island and the Lock Screen, and
    /// the next ones start a new activity.
    static func endAll() async {
        startedWhileOpen = false
        let activities = Activity<ReactionActivityAttributes>.activities
        guard !activities.isEmpty else { return }
        followed.removeAll()
        PushRegistration.shared.liveActivityTokenChanged(.activity(nil))
        for activity in activities {
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
