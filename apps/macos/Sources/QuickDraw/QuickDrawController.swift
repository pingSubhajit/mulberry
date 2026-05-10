import AppKit
import Carbon.HIToolbox
import Overlay

private let quickDrawHotKeySignature: OSType = 0x4D554C42
private let quickDrawHotKeyID: UInt32 = 1

@MainActor
public final class QuickDrawController: NSObject {
    private let overlayController: OverlayController
    private var hotKeyRef: EventHotKeyRef?
    private var hotKeyHandler: EventHandlerRef?
    private var keyMonitor: Any?
    private var didInstallAppLifecycleObserver = false

    public init(overlayController: OverlayController) {
        self.overlayController = overlayController
        super.init()
    }

    public func stop() {
        if let hotKeyRef {
            UnregisterEventHotKey(hotKeyRef)
            self.hotKeyRef = nil
        }
        if let hotKeyHandler {
            RemoveEventHandler(hotKeyHandler)
            self.hotKeyHandler = nil
        }
        if let keyMonitor {
            NSEvent.removeMonitor(keyMonitor)
            self.keyMonitor = nil
        }
    }

    public func start() {
        registerHotKey()
        installKeyMonitor()
        observeAppLifecycle()
    }

    public func enterQuickDraw() {
        _ = overlayController.enterQuickDraw(fullscreenGuard: FullscreenSpaceGuard.evaluate())
    }

    public func exitQuickDraw() {
        overlayController.exitQuickDraw()
    }

    private func registerHotKey() {
        let hotKeyID = EventHotKeyID(signature: quickDrawHotKeySignature, id: quickDrawHotKeyID)
        let registrationStatus = RegisterEventHotKey(
            UInt32(kVK_ANSI_M),
            UInt32(cmdKey | controlKey),
            hotKeyID,
            GetApplicationEventTarget(),
            0,
            &hotKeyRef
        )

        guard registrationStatus == noErr else {
            overlayController.updateHotKeyStatus(.failed(code: registrationStatus))
            return
        }

        var eventType = EventTypeSpec(
            eventClass: OSType(kEventClassKeyboard),
            eventKind: UInt32(kEventHotKeyPressed)
        )
        let handlerStatus = InstallEventHandler(
            GetApplicationEventTarget(),
            { _, event, userData in
                var receivedHotKeyID = EventHotKeyID()
                let status = GetEventParameter(
                    event,
                    EventParamName(kEventParamDirectObject),
                    EventParamType(typeEventHotKeyID),
                    nil,
                    MemoryLayout<EventHotKeyID>.size,
                    nil,
                    &receivedHotKeyID
                )

                guard status == noErr,
                      receivedHotKeyID.signature == quickDrawHotKeySignature,
                      receivedHotKeyID.id == quickDrawHotKeyID,
                      let userData
                else {
                    return noErr
                }

                let controller = Unmanaged<QuickDrawController>.fromOpaque(userData).takeUnretainedValue()
                Task { @MainActor in
                    controller.enterQuickDraw()
                }
                return noErr
            },
            1,
            &eventType,
            Unmanaged.passUnretained(self).toOpaque(),
            &hotKeyHandler
        )

        if handlerStatus == noErr {
            overlayController.updateHotKeyStatus(.registered())
        } else {
            overlayController.updateHotKeyStatus(.failed(code: handlerStatus))
        }
    }

    private func installKeyMonitor() {
        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard event.keyCode == 53 else { return event }
            Task { @MainActor in
                self?.exitQuickDraw()
            }
            return nil
        }
    }

    private func observeAppLifecycle() {
        guard !didInstallAppLifecycleObserver else { return }
        didInstallAppLifecycleObserver = true
        NotificationCenter.default.addObserver(
            forName: NSApplication.didResignActiveNotification,
            object: NSApp,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.exitQuickDraw()
            }
        }
    }
}

enum FullscreenSpaceGuard {
    static func evaluate() -> FullscreenGuardResult {
        guard let frontmostApplication = NSWorkspace.shared.frontmostApplication,
              frontmostApplication.processIdentifier != ProcessInfo.processInfo.processIdentifier
        else {
            return .allowed
        }

        let windowInfo = CGWindowListCopyWindowInfo(.optionOnScreenOnly, kCGNullWindowID) as? [[String: Any]] ?? []
        let screenFrames = NSScreen.screens.map(\.frame)
        for window in windowInfo {
            guard let ownerPID = window[kCGWindowOwnerPID as String] as? pid_t,
                  ownerPID == frontmostApplication.processIdentifier,
                  let layer = window[kCGWindowLayer as String] as? Int,
                  layer == 0,
                  let bounds = window[kCGWindowBounds as String] as? [String: Any],
                  let x = bounds["X"] as? CGFloat,
                  let y = bounds["Y"] as? CGFloat,
                  let width = bounds["Width"] as? CGFloat,
                  let height = bounds["Height"] as? CGFloat
            else {
                continue
            }

            let windowFrame = CGRect(x: x, y: y, width: width, height: height)
            if screenFrames.contains(where: { nearlyEqual(windowFrame, $0) }) {
                return .blocked
            }
        }

        return .allowed
    }

    private static func nearlyEqual(_ lhs: CGRect, _ rhs: CGRect) -> Bool {
        abs(lhs.minX - rhs.minX) < 2 &&
            abs(lhs.minY - rhs.minY) < 2 &&
            abs(lhs.width - rhs.width) < 2 &&
            abs(lhs.height - rhs.height) < 2
    }
}
