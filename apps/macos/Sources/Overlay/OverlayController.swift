import AppKit
import SwiftUI

public enum OverlayMode: Equatable, Sendable {
    case passive
    case quickDraw
}

@MainActor
public final class OverlayController: NSObject, ObservableObject {
    public static let aspectRatio = CGSize(width: 9, height: 20)
    public static let defaultSize = CGSize(width: 450, height: 1_000)

    @Published public private(set) var isVisible: Bool
    @Published public private(set) var mode: OverlayMode = .passive
    @Published public private(set) var selectedDisplayID: String?
    @Published public private(set) var currentFrameDescription = "Not shown"
    @Published public private(set) var hotKeyStatus = OverlayHotKeyStatus.notRegistered

    public var isQuickDrawActive: Bool { mode == .quickDraw }
    public var displays: [OverlayDisplay] { Self.availableDisplays() }
    public var selectedDisplayName: String {
        resolveSelectedDisplay().name
    }

    private let settingsStore: OverlaySettingsStore
    private var panel: OverlayPanel?
    private var palettePanel: OverlayPanel?
    private let debugModel = OverlayDebugDrawingModel()
    private let passiveLevel = NSWindow.Level(rawValue: NSWindow.Level.normal.rawValue - 1)
    private let quickDrawLevel = NSWindow.Level.floating

    public init(settingsStore: OverlaySettingsStore = OverlaySettingsStore()) {
        self.settingsStore = settingsStore
        let settings = settingsStore.load()
        self.isVisible = settings.isVisible
        self.selectedDisplayID = settings.selectedDisplayID
        super.init()
        updateFrameDescription()
        observeDisplayChanges()
        if settings.isVisible {
            show(persist: false)
        }
    }

    public func updateHotKeyStatus(_ status: OverlayHotKeyStatus) {
        hotKeyStatus = status
    }

    public func show() {
        show(persist: true)
    }

    public func hide() {
        if mode == .quickDraw {
            exitQuickDraw()
        }
        panel?.orderOut(nil)
        isVisible = false
        persistCurrentSettings()
    }

    public func toggle() {
        if isVisible {
            hide()
        } else {
            show()
        }
    }

    public func resetPosition() {
        let panel = panel ?? makePanel()
        self.panel = panel
        panel.setFrame(defaultFrame(on: resolveSelectedDisplay()), display: true)
        persistFrame()
        updateFrameDescription()
        if isVisible {
            panel.orderFrontRegardless()
        }
    }

    public func selectDisplay(id: String) {
        selectedDisplayID = id
        let display = resolveSelectedDisplay()
        let panel = panel ?? makePanel()
        self.panel = panel
        panel.setFrame(defaultFrame(on: display), display: true)
        if isVisible {
            panel.orderFrontRegardless()
        }
        persistCurrentSettings()
        updateFrameDescription()
    }

    public func enterQuickDraw(fullscreenGuard: FullscreenGuardResult = .allowed) -> Bool {
        guard fullscreenGuard == .allowed else {
            presentFullscreenPrompt()
            return false
        }

        let panel = panel ?? makePanel()
        self.panel = panel
        if !isVisible {
            show(persist: false)
        }

        mode = .quickDraw
        debugModel.isQuickDrawActive = true
        panel.styleMask = [.borderless, .resizable]
        panel.collectionBehavior = [.canJoinAllSpaces, .stationary, .ignoresCycle]
        panel.level = quickDrawLevel
        panel.ignoresMouseEvents = false
        panel.isMovableByWindowBackground = true
        panel.contentAspectRatio = Self.aspectRatio
        panel.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        showPalette()
        return true
    }

    public func exitQuickDraw() {
        guard mode == .quickDraw else { return }
        persistFrame()
        mode = .passive
        debugModel.isQuickDrawActive = false
        palettePanel?.orderOut(nil)

        panel?.styleMask = [.borderless, .nonactivatingPanel]
        panel?.level = passiveLevel
        panel?.ignoresMouseEvents = true
        panel?.isMovableByWindowBackground = false
        panel?.orderFrontRegardless()
        isVisible = true
        persistCurrentSettings()
        updateFrameDescription()
    }

    public func clearDebugStrokes() {
        debugModel.clear()
    }

    private func show(persist: Bool) {
        let panel = panel ?? makePanel()
        self.panel = panel
        panel.setFrame(resolvedFrame(), display: true)
        configurePassivePanel(panel)
        panel.orderFrontRegardless()
        isVisible = true
        updateFrameDescription()
        if persist {
            persistCurrentSettings()
        }
    }

    private func makePanel() -> OverlayPanel {
        let panel = OverlayPanel(
            contentRect: resolvedFrame(),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.delegate = self
        panel.backgroundColor = .clear
        panel.isOpaque = false
        panel.hasShadow = false
        panel.hidesOnDeactivate = false
        panel.contentView = OverlayDebugCanvasView(model: debugModel)
        configurePassivePanel(panel)
        return panel
    }

    private func configurePassivePanel(_ panel: NSPanel) {
        panel.backgroundColor = .clear
        panel.collectionBehavior = [.canJoinAllSpaces, .stationary, .ignoresCycle]
        panel.hasShadow = false
        panel.hidesOnDeactivate = false
        panel.ignoresMouseEvents = true
        panel.isMovableByWindowBackground = false
        panel.isOpaque = false
        panel.level = passiveLevel
        panel.contentAspectRatio = Self.aspectRatio
    }

    private func showPalette() {
        guard let panel else { return }
        if palettePanel == nil {
            let palette = OverlayPanel(
                contentRect: paletteFrame(relativeTo: panel.frame),
                styleMask: [.borderless, .nonactivatingPanel],
                backing: .buffered,
                defer: false
            )
            palette.backgroundColor = .clear
            palette.collectionBehavior = [.canJoinAllSpaces, .stationary, .ignoresCycle]
            palette.hasShadow = true
            palette.hidesOnDeactivate = false
            palette.isOpaque = false
            palette.level = quickDrawLevel
            palette.contentView = NSHostingView(
                rootView: QuickDrawPaletteView(
                    model: debugModel,
                    onDone: { [weak self] in self?.exitQuickDraw() },
                    onClear: { [weak self] in self?.clearDebugStrokes() }
                )
            )
            palettePanel = palette
        }

        palettePanel?.setFrame(paletteFrame(relativeTo: panel.frame), display: true)
        palettePanel?.level = quickDrawLevel
        palettePanel?.orderFrontRegardless()
    }

    private func paletteFrame(relativeTo overlayFrame: NSRect) -> NSRect {
        let preferred = NSRect(
            x: overlayFrame.maxX + 12,
            y: overlayFrame.midY - 86,
            width: 250,
            height: 172
        )
        let screen = resolveSelectedDisplay()
        if screen.visibleFrame.contains(preferred) {
            return preferred
        }
        return NSRect(
            x: max(screen.visibleFrame.minX + 12, overlayFrame.minX - preferred.width - 12),
            y: min(max(screen.visibleFrame.minY + 12, preferred.minY), screen.visibleFrame.maxY - preferred.height - 12),
            width: preferred.width,
            height: preferred.height
        )
    }

    private func resolvedFrame() -> CGRect {
        let display = resolveSelectedDisplay()
        let settings = settingsStore.load()
        if let relativeFrame = settings.relativeFrame {
            let absolute = CGRect(
                x: display.screenFrame.minX + relativeFrame.minX,
                y: display.screenFrame.minY + relativeFrame.minY,
                width: relativeFrame.width,
                height: relativeFrame.height
            )
            return clampedFrame(absolute, to: display.visibleFrame)
        }
        return defaultFrame(on: display)
    }

    private func defaultFrame(on display: OverlayDisplay) -> CGRect {
        let size = clampedDefaultSize(for: display.visibleFrame)
        return CGRect(
            x: display.visibleFrame.midX - size.width / 2,
            y: display.visibleFrame.midY - size.height / 2,
            width: size.width,
            height: size.height
        )
    }

    private func clampedDefaultSize(for visibleFrame: CGRect) -> CGSize {
        let requested = Self.defaultSize
        let maxWidth = visibleFrame.width * 0.82
        let maxHeight = visibleFrame.height * 0.82
        let scale = min(1, maxWidth / requested.width, maxHeight / requested.height)
        return CGSize(width: requested.width * scale, height: requested.height * scale)
    }

    private func clampedFrame(_ frame: CGRect, to visibleFrame: CGRect) -> CGRect {
        let size = CGSize(
            width: min(max(frame.width, 180), visibleFrame.width * 0.95),
            height: min(max(frame.height, 400), visibleFrame.height * 0.95)
        )
        let aspectHeight = size.width * Self.aspectRatio.height / Self.aspectRatio.width
        let adjustedSize = aspectHeight <= visibleFrame.height * 0.95
            ? CGSize(width: size.width, height: aspectHeight)
            : CGSize(width: size.height * Self.aspectRatio.width / Self.aspectRatio.height, height: size.height)
        let origin = CGPoint(
            x: min(max(frame.origin.x, visibleFrame.minX), visibleFrame.maxX - adjustedSize.width),
            y: min(max(frame.origin.y, visibleFrame.minY), visibleFrame.maxY - adjustedSize.height)
        )
        return CGRect(origin: origin, size: adjustedSize)
    }

    private func persistCurrentSettings() {
        persistFrame()
        settingsStore.save(
            OverlaySettings(
                isVisible: isVisible,
                selectedDisplayID: selectedDisplayID,
                relativeFrame: settingsStore.load().relativeFrame
            )
        )
    }

    private func persistFrame() {
        guard let panel else { return }
        let display = resolveSelectedDisplay()
        let relativeFrame = CGRect(
            x: panel.frame.minX - display.screenFrame.minX,
            y: panel.frame.minY - display.screenFrame.minY,
            width: panel.frame.width,
            height: panel.frame.height
        )
        settingsStore.save(
            OverlaySettings(
                isVisible: isVisible,
                selectedDisplayID: selectedDisplayID,
                relativeFrame: relativeFrame
            )
        )
        updateFrameDescription()
    }

    private func updateFrameDescription() {
        guard let panel else {
            currentFrameDescription = "Not shown"
            return
        }
        currentFrameDescription = "\(Int(panel.frame.width)) x \(Int(panel.frame.height)) at \(Int(panel.frame.minX)), \(Int(panel.frame.minY))"
    }

    private func resolveSelectedDisplay() -> OverlayDisplay {
        let allDisplays = displays
        if let selectedDisplayID,
           let selected = allDisplays.first(where: { $0.id == selectedDisplayID }) {
            return selected
        }
        return allDisplays.first ?? OverlayDisplay(
            id: "main",
            name: "Main Display",
            frameDescription: "Unknown",
            screenFrame: NSScreen.main?.frame ?? CGRect(x: 0, y: 0, width: 1_440, height: 900),
            visibleFrame: NSScreen.main?.visibleFrame ?? CGRect(x: 0, y: 0, width: 1_440, height: 900)
        )
    }

    private func observeDisplayChanges() {
        NotificationCenter.default.addObserver(
            forName: NSApplication.didChangeScreenParametersNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                if self.displays.first(where: { $0.id == self.selectedDisplayID }) == nil {
                    self.selectedDisplayID = NSScreen.main.flatMap(Self.displayID(for:))
                }
                self.panel?.setFrame(self.resolvedFrame(), display: true)
                self.persistCurrentSettings()
            }
        }
    }

    private func presentFullscreenPrompt() {
        let alert = NSAlert()
        alert.messageText = "Quick Draw is not available in this Space"
        alert.informativeText = "Leave fullscreen or open Mulberry from a normal desktop Space to use Quick Draw."
        alert.alertStyle = .informational
        alert.addButton(withTitle: "OK")
        alert.runModal()
    }

    public static func availableDisplays() -> [OverlayDisplay] {
        NSScreen.screens.enumerated().map { index, screen in
            let id = displayID(for: screen) ?? "display-\(index)"
            let size = "\(Int(screen.frame.width)) x \(Int(screen.frame.height))"
            let name = index == 0 ? "Main Display" : "Display \(index + 1)"
            return OverlayDisplay(
                id: id,
                name: name,
                frameDescription: size,
                screenFrame: screen.frame,
                visibleFrame: screen.visibleFrame
            )
        }
    }

    private static func displayID(for screen: NSScreen) -> String? {
        guard let number = screen.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber else {
            return nil
        }
        return number.stringValue
    }
}

extension OverlayController: NSWindowDelegate {
    public func windowDidMove(_ notification: Notification) {
        if mode == .quickDraw {
            palettePanel?.setFrame(paletteFrame(relativeTo: panel?.frame ?? .zero), display: true)
        }
        persistFrame()
    }

    public func windowDidResize(_ notification: Notification) {
        if mode == .quickDraw {
            palettePanel?.setFrame(paletteFrame(relativeTo: panel?.frame ?? .zero), display: true)
        }
        persistFrame()
    }

    public func windowWillResize(_ sender: NSWindow, to frameSize: NSSize) -> NSSize {
        let width = max(frameSize.width, 180)
        return NSSize(width: width, height: width * Self.aspectRatio.height / Self.aspectRatio.width)
    }
}

public enum FullscreenGuardResult: Equatable, Sendable {
    case allowed
    case blocked
}

final class OverlayPanel: NSPanel {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { true }
}
