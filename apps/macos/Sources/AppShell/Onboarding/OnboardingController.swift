import AppKit
import Auth
import CanvasRendering
import SwiftUI
import Sync

/// Shows onboarding over the whole screen the menu bar is on, above the menu bar and the Dock,
/// while Mulberry is the active app. Switching to another app lowers it among ordinary windows, so
/// onboarding never traps anyone; Google's sign-in comes in front of it.
@MainActor
final class OnboardingController {
    private let flow: OnboardingFlow
    private let statusButton: () -> NSStatusBarButton?
    private var window: OnboardingWindow?
    private var monitors: [Any] = []
    private var observers: [NSObjectProtocol] = []
    private var signingIn = false

    /// Above the menu bar and the Dock.
    private static let raised = NSWindow.Level(rawValue: NSWindow.Level.statusBar.rawValue + 1)

    init(flow: OnboardingFlow, statusButton: @escaping () -> NSStatusBarButton?) {
        self.flow = flow
        self.statusButton = statusButton
    }

    var isShowing: Bool { window != nil }

    func show(authController: AuthSessionController, ended: @escaping (OnboardingFlow.Ending) -> Void) {
        guard window == nil else { return }
        // Tangerine and Poppins, from the app's fonts.
        CanvasFonts.registerBundledFonts()
        let screen = statusButton()?.window?.screen ?? NSScreen.screens.first ?? NSScreen.main
        let frame = screen?.frame ?? NSRect(x: 0, y: 0, width: 1512, height: 982)
        let window = OnboardingWindow(contentRect: frame, styleMask: [.borderless], backing: .buffered, defer: false)
        window.isOpaque = false
        window.backgroundColor = .clear
        window.hasShadow = false
        window.isReleasedWhenClosed = false
        window.collectionBehavior = [.fullScreenAuxiliary, .canJoinAllSpaces]
        window.acceptsMouseMovedEvents = true
        window.onEscape = { [weak self] in self?.escape() }
        let host = NSHostingView(rootView: OnboardingView(flow: flow, authController: authController))
        host.sizingOptions = []
        window.contentView = host
        window.setFrame(frame, display: true)
        self.window = window

        flow.signingIn = { [weak self] busy in
            self?.signingIn = busy
            self?.updateLevel()
        }
        flow.finished = { [weak self] ending in
            self?.close()
            ended(ending)
        }
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        updateLevel()
        window.makeKeyAndOrderFront(nil)
        installMonitors()
        let center = NotificationCenter.default
        for name in [NSApplication.didBecomeActiveNotification, NSApplication.didResignActiveNotification] {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.updateLevel() }
            })
        }
        observers.append(center.addObserver(forName: NSApplication.didChangeScreenParametersNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.fitScreen() }
        })
        // Found as the phone leaves: the icon may not be placed yet at launch.
        flow.locateIcon = { [weak self] in self?.iconPoint() }
        flow.screenHeight = frame.height
        Task { await flow.run() }
    }

    func close() {
        monitors.forEach(NSEvent.removeMonitor)
        monitors = []
        observers.forEach(NotificationCenter.default.removeObserver)
        observers = []
        window?.orderOut(nil)
        window = nil
    }

    /// Above everything while Mulberry is in front; among other windows while the person is in
    /// another app, or signing in with Google.
    private func updateLevel() {
        guard let window else { return }
        let level: NSWindow.Level = NSApp.isActive && !signingIn ? Self.raised : .normal
        if window.level != level { window.level = level }
    }

    private func fitScreen() {
        guard let window, let screen = statusButton()?.window?.screen ?? NSScreen.screens.first else { return }
        window.setFrame(screen.frame, display: true)
    }

    /// The menu bar icon's middle, in the window's top-left coordinates.
    private func iconPoint() -> CGPoint? {
        guard let window, let button = statusButton(), let buttonWindow = button.window else { return nil }
        let icon = buttonWindow.convertToScreen(button.convert(button.bounds, to: nil))
        return CGPoint(x: icon.midX - window.frame.minX, y: window.frame.maxY - icon.midY)
    }

    private func escape() {
        if flow.lock.unlocked == false, flow.scene == .phone {
            flow.lock.hintSwipe()
        } else {
            flow.skipIntro()
        }
    }

    private var swiping = false
    private var swipeVelocity: CGFloat = 0

    /// Two fingers up the trackpad, or a mouse wheel's notch, open Mulberry on the phone, as they
    /// open the home screen on the menu bar's lock screen.
    private func installMonitors() {
        if let scroll = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel, handler: { [weak self] event in
            MainActor.assumeIsolated { self?.scrolled(event) ?? false } ? nil : event
        }) { monitors.append(scroll) }
    }

    private func scrolled(_ event: NSEvent) -> Bool {
        let lock = flow.lock
        guard event.window === window, flow.scene == .phone, flow.lockShown, !lock.unlocked else { return false }
        let delta = event.scrollingDeltaY * (event.isDirectionInvertedFromDevice ? 1 : -1)
        guard event.hasPreciseScrollingDeltas else {
            if delta < -0.5 { lock.setUnlocked(true) }
            return true
        }
        guard event.momentumPhase.isEmpty else { return swiping }
        if event.phase.contains(.began) {
            swiping = true
            swipeVelocity = 0
        }
        guard swiping else { return false }
        if event.phase.contains(.changed) {
            lock.swipe = min(0, lock.swipe + delta)
            swipeVelocity = delta
        }
        if event.phase.contains(.ended) || event.phase.contains(.cancelled) {
            swiping = false
            lock.swipeEnded(predicted: lock.swipe + swipeVelocity * 12)
        }
        return true
    }
}

/// A borderless window that takes the keyboard, for the phone's fields, Return and Esc.
final class OnboardingWindow: NSWindow {
    var onEscape: () -> Void = {}

    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { true }

    override func cancelOperation(_ sender: Any?) {
        onEscape()
    }
}
