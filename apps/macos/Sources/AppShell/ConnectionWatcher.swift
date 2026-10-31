import AppKit
import Network

/// Calls back as the Mac wakes from sleep or its network returns: the moments a live connection
/// has likely dropped, and whatever waited for one can go. It lives as long as the app.
@MainActor
final class ConnectionWatcher {
    private let monitor = NWPathMonitor()
    private var online: Bool?
    private var wakeObserver: NSObjectProtocol?

    init(reconnected: @escaping @MainActor @Sendable () -> Void) {
        monitor.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            Task { @MainActor in
                guard let self else { return }
                // Only as it comes back, not at the first report after launch.
                if online, self.online == false { reconnected() }
                self.online = online
            }
        }
        monitor.start(queue: .main)
        wakeObserver = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: .main
        ) { _ in
            MainActor.assumeIsolated { reconnected() }
        }
    }
}
