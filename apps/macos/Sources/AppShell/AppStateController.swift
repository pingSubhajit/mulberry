import Auth
import Foundation
import Networking
import Overlay
import Persistence
import Sync

public struct MacBootstrapState: Sendable, Equatable {
    public let authStatus: String
    public let onboardingCompleted: Bool
    public let userID: String?
    public let userEmail: String?
    public let userDisplayName: String?
    public let partnerDisplayName: String?
    public let pairingStatus: String
    public let pairSessionID: String?
    public let currentStreakDays: Int
    public let partnerPresence: PresenceSummary

    public var isPaired: Bool {
        pairingStatus == "PAIRED"
    }

    public var partnerTitle: String {
        isPaired ? (partnerDisplayName ?? "Partner") : "Not paired"
    }

    public var streakTitle: String {
        currentStreakDays == 1 ? "Streak: 1 day" : "Streak: \(currentStreakDays) days"
    }

    public init(dto: BootstrapDTO) {
        authStatus = dto.authStatus ?? "SIGNED_OUT"
        onboardingCompleted = dto.onboardingCompleted ?? false
        userID = dto.userId
        userEmail = dto.userEmail
        userDisplayName = dto.userDisplayName
        partnerDisplayName = dto.partnerDisplayName
        pairingStatus = dto.pairingStatus ?? "UNPAIRED"
        pairSessionID = dto.pairSessionId
        currentStreakDays = dto.currentStreakDays ?? 0
        partnerPresence = PresenceSummary(dto: dto.partnerPresence)
    }
}

public struct PresenceSummary: Sendable, Equatable {
    public let canSeeLatestDrawings: Bool
    public let surfaces: [PresenceSurface]

    public init(dto: PresenceSummaryDTO?) {
        canSeeLatestDrawings = dto?.canSeeLatestDrawings ?? false
        surfaces = dto?.surfaces.map(PresenceSurface.init(dto:)) ?? []
    }
}

public struct PresenceSurface: Sendable, Equatable {
    public let surfaceType: String
    public let deviceInstanceID: String
    public let configured: Bool
    public let enabled: Bool
    public let canSeeLatestDrawings: Bool
    public let hasEverBeenAbleToSee: Bool
    public let updatedAt: String

    public init(dto: PresenceSurfaceDTO) {
        surfaceType = dto.surfaceType
        deviceInstanceID = dto.deviceInstanceId
        configured = dto.configured
        enabled = dto.enabled
        canSeeLatestDrawings = dto.canSeeLatestDrawings
        hasEverBeenAbleToSee = dto.hasEverBeenAbleToSee
        updatedAt = dto.updatedAt
    }
}

public enum AppStateLoadState: Sendable, Equatable {
    case idle
    case loading
    case loaded(MacBootstrapState)
    case failed(String)

    public var bootstrap: MacBootstrapState? {
        if case let .loaded(state) = self {
            return state
        }
        return nil
    }
}

@MainActor
public final class AppStateController: ObservableObject {
    @Published public private(set) var loadState: AppStateLoadState = .idle

    private let authController: AuthSessionController
    private let apiClient: BootstrapAPIClient
    private let database: MulberryDatabase
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()
    private var lastLoadedUserID: String?

    public init(
        authController: AuthSessionController,
        database: MulberryDatabase,
        configuration: MacAppConfiguration
    ) {
        self.authController = authController
        self.database = database
        self.apiClient = BootstrapAPIClient(configuration: configuration)
    }

    public func restoreCachedBootstrap() {
        do {
            guard let cached = try database.cachedBootstrap(),
                  let data = cached.payloadJSON.data(using: .utf8)
            else {
                return
            }
            let dto = try decoder.decode(BootstrapDTO.self, from: data)
            loadState = .loaded(MacBootstrapState(dto: dto))
        } catch {
            loadState = .failed("Unable to load saved app state.")
        }
    }

    public func acceptAuthState(
        _ authState: AuthSessionState,
        overlayController: OverlayController,
        syncController: CanvasSyncController
    ) {
        switch authState {
        case let .signedIn(session):
            if lastLoadedUserID == session.userID, loadState.bootstrap != nil {
                updateSyncSession(authState, syncController: syncController)
                return
            }
            lastLoadedUserID = session.userID
            cacheAndPublish(session.bootstrap)
            updateSyncSession(authState, syncController: syncController)
            Task {
                await refreshBootstrapAndReportOverlay(
                    overlayController: overlayController,
                    syncController: syncController
                )
            }
        case .refreshing, .signingIn:
            if loadState.bootstrap == nil {
                loadState = .loading
            }
            updateSyncSession(authState, syncController: syncController)
        case .signedOut:
            lastLoadedUserID = nil
            loadState = .idle
            try? database.clearSessionState()
            syncController.reset()
        case let .failed(failure):
            loadState = .failed(failure.message)
            updateSyncSession(authState, syncController: syncController)
        }
    }

    public func refreshBootstrapAndReportOverlay(
        overlayController: OverlayController,
        syncController: CanvasSyncController
    ) async {
        loadState = loadState.bootstrap == nil ? .loading : loadState
        do {
            let bootstrap = try await apiClient.getBootstrap(authorizer: makeAuthorizer())
            cacheAndPublish(bootstrap)
            updateSyncSession(authController.state, syncController: syncController)
            await reportOverlayPresenceIfNeeded(
                bootstrap: bootstrap,
                overlayController: overlayController,
                syncController: syncController
            )
        } catch {
            loadState = .failed(userFacingMessage(for: error))
        }
    }

    public func reportOverlayPresenceIfNeeded(
        bootstrap: BootstrapDTO,
        overlayController: OverlayController,
        syncController: CanvasSyncController
    ) async {
        guard bootstrap.pairingStatus == "PAIRED" else {
            return
        }

        do {
            let deviceInstanceID = try database.installationID()
            let updated = try await apiClient.updatePresenceSurface(
                surfaceType: "MACOS_OVERLAY",
                request: PresenceSurfaceUpdateRequest(
                    deviceInstanceId: deviceInstanceID,
                    configured: true,
                    enabled: overlayController.isVisible,
                    canSeeLatestDrawings: syncController.canReportOverlayCanSeeLatestDrawings(
                        isOverlayVisible: overlayController.isVisible
                    ),
                    details: [
                        "overlayVisible": .bool(overlayController.isVisible),
                        "selectedDisplayName": .string(overlayController.selectedDisplayName),
                        "syncDemand": .string(syncController.demand.title),
                        "syncState": .string(syncController.connectionState.title),
                        "lastAppliedServerRevision": .number(Double(syncController.status.lastAppliedServerRevision)),
                        "latestKnownServerRevision": .number(Double(syncController.status.latestKnownServerRevision)),
                        "lastSuccessfulRecoveryAt": syncController.status.lastSuccessfulRecoveryAt
                            .map { .string(ISO8601DateFormatter().string(from: $0)) } ?? .null
                    ]
                ),
                authorizer: makeAuthorizer()
            )
            cacheAndPublish(updated)
        } catch {
            // Presence reporting should not block bootstrap display. Sync and diagnostics
            // phases will surface persistent reporting failures more explicitly.
        }
    }

    private func cacheAndPublish(_ dto: BootstrapDTO) {
        do {
            let data = try encoder.encode(dto)
            let payload = String(decoding: data, as: UTF8.self)
            try database.saveBootstrap(
                payloadJSON: payload,
                session: PersistedSessionState(
                    userID: dto.userId,
                    userEmail: dto.userEmail,
                    userDisplayName: dto.userDisplayName,
                    pairSessionID: dto.pairSessionId
                )
            )
            loadState = .loaded(MacBootstrapState(dto: dto))
        } catch {
            loadState = .failed("Unable to save app state.")
        }
    }

    private func updateSyncSession(_ authState: AuthSessionState, syncController: CanvasSyncController) {
        guard case let .signedIn(session) = authState,
              let bootstrap = loadState.bootstrap ?? Optional(MacBootstrapState(dto: session.bootstrap)),
              bootstrap.isPaired,
              let pairSessionID = bootstrap.pairSessionID
        else {
            syncController.updateSession(userID: nil, pairSessionID: nil, shouldSync: false)
            return
        }
        syncController.updateSession(
            userID: session.userID,
            pairSessionID: pairSessionID,
            shouldSync: true
        )
    }

    private func makeAuthorizer() -> AuthenticatedRequestAuthorizer {
        AuthenticatedRequestAuthorizer(
            currentAccessToken: { [weak authController] in
                await MainActor.run {
                    authController?.currentAccessToken
                }
            },
            refreshAccessToken: { [weak authController] in
                guard let authController else {
                    throw APIError.missingToken
                }
                return try await authController.refreshForAuthenticatedRetry()
            }
        )
    }

    private func userFacingMessage(for error: Error) -> String {
        if let localizedError = error as? LocalizedError, let description = localizedError.errorDescription {
            return description
        }
        return error.localizedDescription
    }
}
