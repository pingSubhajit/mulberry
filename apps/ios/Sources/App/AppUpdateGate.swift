import SwiftUI
import FirebaseCore
import FirebaseRemoteConfig

/// Decides whether this build must update, should, or is fine, from the build numbers the admin
/// site publishes to Remote Config. The server can also turn the build away (`BackendClient.updateRequired`).
///
/// Remote Config's last values apply at once, so launch never waits; a fetch and Remote Config's
/// real-time updates bring newer ones. Without Firebase, or without a reply, nothing is blocked.
/// Debug builds skip all of it; the `appUpdate=required` and `appUpdate=recommended` launch
/// arguments show each state.
@MainActor
final class AppUpdateGate: ObservableObject {
    static let shared = AppUpdateGate()

    enum Status { case current, recommended, required }

    @Published private(set) var status: Status = .current
    /// Whether the dismissible prompt is due: below the recommended build, and not declined in the last day.
    @Published var asksToUpdate = false

    private static let minimumKey = "min_build_ios"
    private static let recommendedKey = "recommended_build_ios"
    private static let declinedAtKey = "appUpdateDeclinedAt"
    private static let declineCooldown: TimeInterval = 24 * 60 * 60
    private static let storeURL = URL(string: "itms-apps://apps.apple.com/app/id6829284245")!

    /// The `CFBundleVersion` the thresholds compare with, and the server sees.
    static let build = Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "") ?? 0

    private var remoteConfig: RemoteConfig?
    private var serverRequired = false
    /// The prompt asks once a launch at most.
    private var answeredThisLaunch = false
    private var observers: [NSObjectProtocol] = []

    /// Starts after Firebase is configured, at launch.
    func start() {
        observers.append(NotificationCenter.default.addObserver(forName: BackendClient.updateRequired, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.serverRequired = true; self?.evaluate() }
        })
        #if DEBUG
        evaluate()
        #else
        guard FirebaseApp.app() != nil else { return }
        let config = RemoteConfig.remoteConfig()
        let settings = RemoteConfigSettings()
        settings.minimumFetchInterval = 15 * 60
        config.configSettings = settings
        config.setDefaults([Self.minimumKey: 0 as NSNumber, Self.recommendedKey: 0 as NSNumber])
        remoteConfig = config
        evaluate()
        config.addOnConfigUpdateListener { [weak self] update, error in
            guard error == nil, update != nil else { return }
            Task { @MainActor in await self?.activate() }
        }
        observers.append(NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in await self?.fetch() }
        })
        Task { await fetch() }
        #endif
    }

    func openStore() {
        UIApplication.shared.open(Self.storeURL)
    }

    /// Updating opens the App Store; declining holds the prompt off for a day.
    func answerPrompt(update: Bool) {
        answeredThisLaunch = true
        asksToUpdate = false
        if update { openStore() } else { UserDefaults.standard.set(Date(), forKey: Self.declinedAtKey) }
    }

    private func fetch() async {
        guard let remoteConfig else { return }
        _ = try? await remoteConfig.fetchAndActivate()
        evaluate()
    }

    private func activate() async {
        guard let remoteConfig else { return }
        _ = try? await remoteConfig.activate()
        evaluate()
    }

    private func evaluate() {
        let next = currentStatus()
        status = next
        let declinedAt = UserDefaults.standard.object(forKey: Self.declinedAtKey) as? Date
        asksToUpdate = next == .recommended && !answeredThisLaunch
            && declinedAt.map { Date().timeIntervalSince($0) > Self.declineCooldown } ?? true
    }

    private func currentStatus() -> Status {
        if serverRequired { return .required }
        #if DEBUG
        switch ProcessInfo.processInfo.arguments.first(where: { $0.hasPrefix("appUpdate=") })?.dropFirst(10) {
        case "required": return .required
        case "recommended": return .recommended
        default: return .current
        }
        #else
        guard let remoteConfig else { return .current }
        if Self.build < remoteConfig.configValue(forKey: Self.minimumKey).numberValue.intValue { return .required }
        if Self.build < remoteConfig.configValue(forKey: Self.recommendedKey).numberValue.intValue { return .recommended }
        return .current
        #endif
    }
}

/// Stands in for the whole app while this build is below the minimum.
struct AppUpdateRequiredView: View {
    let update: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            Image("BrandMark")
                .resizable().scaledToFit()
                .frame(width: 40, height: 66)
                .accessibilityHidden(true)
            Text("Time to update")
                .font(MulberryDesign.font(29, weight: "Bold", relativeTo: .largeTitle))
                .multilineTextAlignment(.center)
                .padding(.top, 24)
                .accessibilityAddTraits(.isHeader)
            Text("This version of Mulberry is no longer supported.")
                .font(.body)
                .foregroundStyle(MulberryDesign.muted)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 12)
            Spacer()
        }
        .readableColumn(480)
        .padding(.horizontal, 20)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .safeAreaInset(edge: .bottom) {
            PrimaryAction(title: "Update", action: update)
                .readableColumn(480)
                .padding(.horizontal, 20).padding(.bottom, 12)
        }
        .background(MulberryDesign.background.ignoresSafeArea())
    }
}
