import Auth
import Foundation
import Overlay
import Persistence
import ProductCore
import Sync

extension BootstrapState {
    var partnerTitle: String {
        isPaired ? (partnerDisplayName ?? "Partner") : "Not paired"
    }

    var streakTitle: String {
        currentStreakDays == 1 ? "Streak: 1 day" : "Streak: \(currentStreakDays) days"
    }
}

public enum AppStateLoadState: Sendable, Equatable {
    case idle
    case loading
    case loaded(BootstrapState)
    case failed(String)

    public var bootstrap: BootstrapState? {
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
    private let store: MacLocalStore
    private var lastLoadedUserID: String?

    public init(authController: AuthSessionController, store: MacLocalStore) {
        self.authController = authController
        self.store = store
    }

    private var api: BackendClient { authController.api }

    public func acceptAuthState(
        _ authState: AuthSessionState,
        overlayController: OverlayController,
        syncController: CanvasSyncController
    ) {
        switch authState {
        case let .signedIn(session):
            if lastLoadedUserID == session.userID, loadState.bootstrap != nil {
                updateSyncSession(syncController: syncController)
                return
            }
            lastLoadedUserID = session.userID
            loadState = .loaded(session.bootstrap)
            updateSyncSession(syncController: syncController)
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
        case .signedOut:
            lastLoadedUserID = nil
            loadState = .idle
            syncController.reset()
        case let .failed(failure):
            loadState = .failed(failure.message)
            syncController.updateSession(userID: nil, pairSessionID: nil, shouldSync: false)
        }
    }

    public func refreshBootstrapAndReportOverlay(
        overlayController: OverlayController,
        syncController: CanvasSyncController
    ) async {
        loadState = loadState.bootstrap == nil ? .loading : loadState
        do {
            let bootstrap = try await api.bootstrap()
            publish(bootstrap)
            updateSyncSession(syncController: syncController)
            await reportOverlayPresenceIfNeeded(overlayController: overlayController, syncController: syncController)
        } catch {
            if await api.signedInUserId == nil {
                authController.sessionEnded()
            } else if loadState.bootstrap == nil {
                loadState = .failed(error.localizedDescription)
            }
        }
    }

    /// Tells the partner whether this Mac's overlay shows their latest drawings.
    public func reportOverlayPresenceIfNeeded(
        overlayController: OverlayController,
        syncController: CanvasSyncController
    ) async {
        guard loadState.bootstrap?.isPaired == true else {
            return
        }

        do {
            let updated = try await api.setPresence(
                surface: .macOverlay,
                deviceId: store.installationID,
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
                    "lastSuccessfulRecoveryAt": syncController.status.lastSuccessfulRecoveryAt
                        .map { .string(ServerDate.string($0)) } ?? .null
                ]
            )
            publish(updated)
        } catch {
            // Presence reporting should not block bootstrap display. Sync and diagnostics
            // phases will surface persistent reporting failures more explicitly.
        }
    }

    /// A profile, partner or pairing change made in Settings: the canvas follows a new pairing.
    public func apply(_ bootstrap: BootstrapState, syncController: CanvasSyncController) {
        publish(bootstrap)
        updateSyncSession(syncController: syncController)
    }

    private func publish(_ bootstrap: BootstrapState) {
        guard bootstrap.userId == lastLoadedUserID else { return }
        loadState = .loaded(bootstrap)
        authController.accept(bootstrap)
    }

    private func updateSyncSession(syncController: CanvasSyncController) {
        guard let bootstrap = loadState.bootstrap, bootstrap.isPaired, let pairSessionID = bootstrap.pairSessionId else {
            syncController.updateSession(userID: nil, pairSessionID: nil, shouldSync: false)
            return
        }
        syncController.updateSession(userID: bootstrap.userId, pairSessionID: pairSessionID, shouldSync: true)
    }
}
