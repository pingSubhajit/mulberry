import AppKit
import CanvasDrawing
import CanvasRendering
import ProductCore
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

    /// Quick Draw's canvas editor and its tools, made by the app, which owns sync and editing.
    public var makeQuickDrawCanvas: (() -> NSView)?
    public var makeQuickDrawPalette: ((HorizontalEdge) -> NSView)?
    /// Esc in Quick Draw: finishes typing, deselects or leaves the eyedropper first. False when
    /// there was nothing to cancel, so Esc leaves Quick Draw.
    public var quickDrawEscape: (() -> Bool)?
    /// Called before Quick Draw closes, so text being typed is kept.
    public var quickDrawWillExit: (() -> Void)?

    private let settingsStore: OverlaySettingsStore
    private var panel: OverlayPanel?
    private var palettePanel: QuickDrawPalettePanel?
    private var paletteSide: HorizontalEdge = .leading
    private var passiveView: NSView?
    /// The grip is dragging: the overlay moves with the palette, so nothing else repositions it.
    private var isDragging = false
    private let canvasModel: CanvasRenderModel
    private let passiveLevel = NSWindow.Level(rawValue: NSWindow.Level.normal.rawValue - 1)
    private let quickDrawLevel = NSWindow.Level.floating

    public init(settingsStore: OverlaySettingsStore = OverlaySettingsStore()) {
        self.settingsStore = settingsStore
        let settings = settingsStore.load()
        self.isVisible = settings.isVisible
        self.selectedDisplayID = settings.selectedDisplayID
        self.canvasModel = CanvasRenderModel(
            state: CanvasRenderFixture.previewState(),
            strokeRenderMode: .dryBrush
        )
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

    public func updateCanvasState(
        _ state: DrawingState,
        strokeRenderMode: CanvasStrokeRenderMode,
        stickerImages: [StickerReference: CanvasImage]
    ) {
        canvasModel.state = state
        canvasModel.strokeRenderMode = strokeRenderMode
        canvasModel.stickerImages = stickerImages
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
        panel.styleMask = [.borderless, .resizable]
        panel.collectionBehavior = [.canJoinAllSpaces, .stationary, .ignoresCycle]
        panel.level = quickDrawLevel
        panel.ignoresMouseEvents = false
        // Drawing, not dragging: the palette's grip moves the overlay.
        panel.isMovableByWindowBackground = false
        panel.contentAspectRatio = Self.aspectRatio
        if let canvas = makeQuickDrawCanvas?() {
            passiveView = panel.contentView
            panel.contentView = canvas
        }
        panel.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        showPalette()
        return true
    }

    public func exitQuickDraw() {
        guard mode == .quickDraw else { return }
        quickDrawWillExit?()
        persistFrame()
        mode = .passive
        closePalette()
        if let passiveView {
            panel?.contentView = passiveView
            self.passiveView = nil
        }

        panel?.styleMask = [.borderless, .nonactivatingPanel]
        panel?.level = passiveLevel
        panel?.ignoresMouseEvents = true
        panel?.isMovableByWindowBackground = false
        panel?.orderFrontRegardless()
        isVisible = true
        persistCurrentSettings()
        updateFrameDescription()
    }

    /// Drags the overlay by the palette's grip. The window server drags the palette, carrying the
    /// overlay as its child window, as it drags any window by its title bar: between displays too,
    /// each with its own Spaces, which a window moved in code cannot cross. `dropped` is called once
    /// the button is released and the overlay has settled.
    public func dragQuickDraw(with mouseDown: NSEvent, dropped: @escaping @MainActor () -> Void) {
        guard mode == .quickDraw, let palette = palettePanel else { return dropped() }
        isDragging = true
        palette.performDrag(with: mouseDown)
        Task { @MainActor [weak self] in
            // The drag runs in the window server; the app only learns it ended from the button.
            while NSEvent.pressedMouseButtons & 1 != 0 {
                try? await Task.sleep(for: .milliseconds(30))
            }
            guard let self else { return }
            isDragging = false
            settleAfterDrag()
            dropped()
        }
    }

    /// Takes the display the overlay was dropped on, fits the overlay inside it, and puts the
    /// palette beside it on whichever side has room.
    private func settleAfterDrag() {
        guard let panel else { return }
        let centre = CGPoint(x: panel.frame.midX, y: panel.frame.midY)
        let displays = displays
        if let display = displays.first(where: { $0.screenFrame.contains(centre) })
            ?? displays.min(by: { distance(from: centre, to: $0.screenFrame) < distance(from: centre, to: $1.screenFrame) }) {
            selectedDisplayID = display.id
            let settled = clampedFrame(panel.frame, to: display.visibleFrame)
            if settled != panel.frame {
                palettePanel?.removeChildWindow(panel)
                panel.setFrame(settled, display: true)
                if let palette = palettePanel { palette.addChildWindow(panel, ordered: .above) }
                placePaletteBesideOverlay()
            }
        }
        if preferredPaletteSide(for: panel.frame) != paletteSide {
            closePalette()
            showPalette()
        }
        persistCurrentSettings()
        updateFrameDescription()
    }

    private func distance(from point: CGPoint, to rect: CGRect) -> CGFloat {
        hypot(max(rect.minX - point.x, 0, point.x - rect.maxX), max(rect.minY - point.y, 0, point.y - rect.maxY))
    }

    /// Esc while Quick Draw is open. True when it was handled.
    public func handleQuickDrawEscape() -> Bool {
        guard mode == .quickDraw else { return false }
        if quickDrawEscape?() == true { return true }
        exitQuickDraw()
        return true
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
        panel.contentView = CanvasRenderView(
            model: canvasModel,
            surface: .passiveOverlay,
            showsEditingBackground: false
        )
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
        paletteSide = preferredPaletteSide(for: panel.frame)
        let palette = QuickDrawPalettePanel(
            contentRect: paletteFrame(relativeTo: panel.frame),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        palette.backgroundColor = .clear
        palette.collectionBehavior = [.canJoinAllSpaces, .stationary, .ignoresCycle]
        palette.hasShadow = false
        palette.hidesOnDeactivate = false
        palette.isOpaque = false
        palette.level = quickDrawLevel
        palette.contentView = makeQuickDrawPalette?(paletteSide)
        palettePanel = palette
        palette.orderFrontRegardless()
        // The window server moves a child with its parent, in the same frame.
        palette.addChildWindow(panel, ordered: .above)
    }

    /// Puts the palette back beside the overlay after the overlay itself moved or resized. The
    /// overlay is detached meanwhile, or moving the palette would carry it along again.
    private func placePaletteBesideOverlay() {
        guard mode == .quickDraw, let panel, let palette = palettePanel else { return }
        let frame = paletteFrame(relativeTo: panel.frame)
        guard frame != palette.frame else { return }
        palette.removeChildWindow(panel)
        palette.setFrame(frame, display: true)
        palette.addChildWindow(panel, ordered: .above)
    }

    private func closePalette() {
        guard let palette = palettePanel else { return }
        // Detached first: ordering out a parent orders out its children too.
        if let panel { palette.removeChildWindow(panel) }
        palette.orderOut(nil)
        palette.contentView = nil
        palettePanel = nil
    }

    /// The width of the tool column beside the overlay.
    private static let paletteWidth: CGFloat = 80
    private static let paletteGap: CGFloat = 12

    /// On the overlay's left, as on the iPad, unless the screen has no room there.
    private func preferredPaletteSide(for overlayFrame: NSRect) -> HorizontalEdge {
        let screen = resolveSelectedDisplay().visibleFrame
        return overlayFrame.minX - Self.paletteGap - Self.paletteWidth >= screen.minX ? .leading : .trailing
    }

    private func paletteFrame(relativeTo overlayFrame: NSRect) -> NSRect {
        NSRect(
            x: paletteSide == .leading
                ? overlayFrame.minX - Self.paletteGap - Self.paletteWidth
                : overlayFrame.maxX + Self.paletteGap,
            y: overlayFrame.minY,
            width: Self.paletteWidth,
            height: overlayFrame.height
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
        // Mid-drag the overlay is following the palette; the frame is saved when it is dropped.
        guard !isDragging else { return }
        placePaletteBesideOverlay()
        persistFrame()
    }

    public func windowDidResize(_ notification: Notification) {
        placePaletteBesideOverlay()
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

/// Quick Draw's tools. Clicking them leaves the overlay key, so the keyboard stays with the canvas.
final class QuickDrawPalettePanel: NSPanel {
    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
}
