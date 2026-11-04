import Foundation
import Synchronization

// The apps' own analytics events, sent to the backend's `POST /analytics/events`, which passes them
// on to PostHog. Names and values follow docs/analytics/tracking-plan.md, "App events (Phase 2)":
// the server rejects anything else.

public enum AnalyticsEventName: String, Codable, Sendable {
    case onboardingStepViewed = "mulberry_onboarding_step_viewed"
    case wallpaperSetupStep = "mulberry_wallpaper_setup_step"
    case pushOpened = "mulberry_push_opened"
}

/// An onboarding or pairing screen.
public enum OnboardingStep: String, CaseIterable, Sendable {
    case welcome
    case signIn = "sign_in"
    case profileName = "profile_name"
    case partnerDetails = "partner_details"
    case pairingChoice = "pairing_choice"
    case inviteCodeShown = "invite_code_shown"
    case redeemCodeEntry = "redeem_code_entry"
    case inviteReview = "invite_review"
    case waitingForPartner = "waiting_for_partner"
    case pairedCelebration = "paired_celebration"
    case notificationsPermission = "notifications_permission"
    case wallpaperSetup = "wallpaper_setup"
    case onboardingFinished = "onboarding_finished"
}

/// A step of setting up a surface that shows the drawings.
public enum WallpaperSetupStep: String, CaseIterable, Sendable {
    case intro
    case chooseBackground = "choose_background"
    case setWallpaper = "set_wallpaper"
    case installShortcut = "install_shortcut"
    case createAutomation = "create_automation"
    case grantPermission = "grant_permission"
    case enableOverlay = "enable_overlay"
    case runTest = "run_test"
    case finished
}

public enum WallpaperSetupOutcome: String, CaseIterable, Sendable {
    case viewed, completed, failed, skipped
}

/// Why a setup step failed: snake_case, at most 64 characters, nothing personal.
public enum WallpaperSetupFailure: String, CaseIterable, Sendable {
    case notificationsDenied = "notifications_denied"
    case shortcutError = "shortcut_error"
    case shortcutCancelled = "shortcut_cancelled"
    case testNoResponse = "test_no_response"
}

/// The kind of notification opened, from its payload's `type`.
public enum PushType: String, CaseIterable, Sendable {
    case canvasNudge = "CANVAS_NUDGE"
    case drawReminder = "DRAW_REMINDER"
    case reaction = "REACTION"
    case pairingConfirmed = "PAIRING_CONFIRMED"
    case pairingDisconnected = "PAIRING_DISCONNECTED"
    case partnerVisibilityChanged = "PARTNER_VISIBILITY_CHANGED"
    case anniversaryChanged = "ANNIVERSARY_CHANGED"
    case other = "OTHER"

    public init(payloadType: String?) {
        self = payloadType.flatMap(Self.init(rawValue:)) ?? .other
    }
}

public enum AnalyticsEvent: Equatable, Sendable {
    case onboardingStepViewed(OnboardingStep)
    case wallpaperSetupStep(surface: PresenceSurfaceType, step: WallpaperSetupStep, outcome: WallpaperSetupOutcome,
                            failureReason: WallpaperSetupFailure? = nil)
    case pushOpened(PushType)

    public var name: AnalyticsEventName {
        switch self {
        case .onboardingStepViewed: .onboardingStepViewed
        case .wallpaperSetupStep: .wallpaperSetupStep
        case .pushOpened: .pushOpened
        }
    }

    public var properties: [String: String] {
        switch self {
        case let .onboardingStepViewed(step):
            return ["step": step.rawValue]
        case let .wallpaperSetupStep(surface, step, outcome, failureReason):
            var properties = ["surface_type": surface.rawValue, "step": step.rawValue, "outcome": outcome.rawValue]
            if outcome == .failed, let failureReason { properties["failure_reason"] = failureReason.rawValue }
            return properties
        case let .pushOpened(type):
            return ["push_type": type.rawValue]
        }
    }
}

/// An event as sent and as kept on disk until it is sent. Its id and time are fixed when it
/// happens, so a retry never counts it twice.
public struct AnalyticsRecord: Codable, Equatable, Sendable {
    public let id: String
    public let event: AnalyticsEventName
    public let occurredAt: String
    public let properties: [String: String]

    public init(_ event: AnalyticsEvent, at date: Date = Date(), id: UUID = UUID()) {
        self.id = id.uuidString.lowercased()
        self.event = event.name
        occurredAt = ServerDate.string(date)
        properties = event.properties
    }
}

/// What became of a batch: delivered, or refused for good, drops it; anything else keeps it.
public enum AnalyticsDelivery: Equatable, Sendable {
    case drop, keep
}

public protocol AnalyticsSender: Sendable {
    func sendAnalytics(_ events: [AnalyticsRecord]) async -> AnalyticsDelivery
}

/// Sends through the backend client as it is when a batch goes, so the queue can exist before it.
public struct BackendAnalyticsSender: AnalyticsSender {
    private let client: @Sendable () async -> BackendClient?

    public init(client: @escaping @Sendable () async -> BackendClient?) {
        self.client = client
    }

    public func sendAnalytics(_ events: [AnalyticsRecord]) async -> AnalyticsDelivery {
        guard let client = await client() else { return .keep }
        return await client.sendAnalytics(events)
    }
}

/// The install's anonymous analytics id. In UserDefaults rather than the Keychain, so deleting the
/// app starts a new one; signing out keeps it.
public enum AnalyticsInstall {
    static let key = "analyticsAnonymousId"

    public static func anonymousID(defaults: UserDefaults = .standard) -> String {
        if let existing = defaults.string(forKey: key), UUID(uuidString: existing) != nil { return existing.lowercased() }
        let created = UUID().uuidString.lowercased()
        defaults.set(created, forKey: key)
        return created
    }
}

/// Unsent events, kept in a file and sent in batches. It never shows anything or waits on
/// anyone: a failed send just stays for the next try.
public actor AnalyticsQueue {
    public static let capacity = 200
    public static let batchSize = 50

    private let file: URL
    private let sender: any AnalyticsSender
    private let sendDelay: Duration
    private var pending: [AnalyticsRecord]
    private var flushing = false
    private var scheduled: Task<Void, Never>?

    public init(file: URL, sender: any AnalyticsSender, sendDelay: Duration = .seconds(5)) {
        self.file = file
        self.sender = sender
        self.sendDelay = sendDelay
        pending = (try? Data(contentsOf: file)).flatMap { try? JSONDecoder().decode([AnalyticsRecord].self, from: $0) } ?? []
    }

    public var unsent: [AnalyticsRecord] { pending }

    /// Keeps the event, dropping the oldest past the cap, and sends it a few seconds later.
    public func add(_ record: AnalyticsRecord) {
        pending.append(record)
        if pending.count > Self.capacity { pending.removeFirst(pending.count - Self.capacity) }
        save()
        guard scheduled == nil else { return }
        scheduled = Task { [sendDelay] in
            try? await Task.sleep(for: sendDelay)
            await self.sendScheduled()
        }
    }

    private func sendScheduled() async {
        scheduled = nil
        await flush()
    }

    /// Sends everything waiting, a batch at a time, until a batch has to wait.
    public func flush() async {
        guard !flushing else { return }
        flushing = true
        defer { flushing = false }
        while !pending.isEmpty {
            let batch = Array(pending.prefix(Self.batchSize))
            guard await sender.sendAnalytics(batch) == .drop else { return }
            let sent = Set(batch.map(\.id))
            pending.removeAll { sent.contains($0.id) }
            save()
        }
    }

    private func save() {
        do {
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try JSONEncoder().encode(pending).write(to: file, options: .atomic)
        } catch { /* Analytics never interrupts anything; the events stay in memory. */ }
    }
}

/// The app's one queue, for screens anywhere to record what they show.
public enum AppAnalytics {
    private static let current = Mutex<AnalyticsQueue?>(nil)

    /// Starts recording into `queue`, and sends what an earlier launch left.
    public static func start(_ queue: AnalyticsQueue) {
        current.withLock { $0 = queue }
        Task { await queue.flush() }
    }

    /// Records the event as happening now. Before `start`, it is dropped.
    public static func track(_ event: AnalyticsEvent) {
        let record = AnalyticsRecord(event)
        guard let queue = current.withLock({ $0 }) else { return }
        Task { await queue.add(record) }
    }

    public static func flush() async {
        guard let queue = current.withLock({ $0 }) else { return }
        await queue.flush()
    }
}

extension BackendClient: AnalyticsSender {}
