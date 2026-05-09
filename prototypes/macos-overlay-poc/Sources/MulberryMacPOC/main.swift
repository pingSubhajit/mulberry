import AppKit
import Carbon.HIToolbox
import SwiftUI

private let quickDrawHotKeySignature: OSType = 0x4D554C42
private let quickDrawHotKeyID: UInt32 = 1

struct CanvasStroke {
    let color: NSColor
    let width: CGFloat
    var points: [CGPoint]
}

@MainActor
final class CanvasModel: ObservableObject {
    @Published var strokes: [CanvasStroke] = [] {
        didSet { onChange?() }
    }
    @Published var activeStroke: CanvasStroke? {
        didSet { onChange?() }
    }
    @Published var selectedColor: NSColor = .systemPink {
        didSet { onChange?() }
    }
    @Published var brushWidth: CGFloat = 8 {
        didSet { onChange?() }
    }
    @Published var isQuickDrawActive = false {
        didSet { onChange?() }
    }

    var onChange: (() -> Void)?

    func clear() {
        strokes.removeAll()
        activeStroke = nil
    }
}

@MainActor
final class CanvasView: NSView {
    private let model: CanvasModel

    init(model: CanvasModel) {
        self.model = model
        super.init(frame: .zero)
        wantsLayer = true
        layer?.masksToBounds = false
        model.onChange = { [weak self] in
            self?.needsDisplay = true
        }
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var isFlipped: Bool {
        true
    }

    override func draw(_ dirtyRect: NSRect) {
        super.draw(dirtyRect)

        let canvasRect = bounds.insetBy(dx: 10, dy: 10)
        if model.isQuickDrawActive {
            NSColor(calibratedWhite: 1, alpha: 0.82).setFill()
            NSBezierPath(roundedRect: canvasRect, xRadius: 28, yRadius: 28).fill()

            NSColor(calibratedWhite: 0, alpha: 0.22).setStroke()
            let outline = NSBezierPath(roundedRect: canvasRect, xRadius: 28, yRadius: 28)
            outline.lineWidth = 2
            outline.stroke()
        }

        drawSamplePayload(in: canvasRect)
        model.strokes.forEach(drawStroke)
        if let activeStroke = model.activeStroke {
            drawStroke(activeStroke)
        }
    }

    override func mouseDown(with event: NSEvent) {
        guard model.isQuickDrawActive else { return }
        let point = convert(event.locationInWindow, from: nil)
        model.activeStroke = CanvasStroke(
            color: model.selectedColor,
            width: model.brushWidth,
            points: [point]
        )
    }

    override func mouseDragged(with event: NSEvent) {
        guard model.isQuickDrawActive, var stroke = model.activeStroke else { return }
        stroke.points.append(convert(event.locationInWindow, from: nil))
        model.activeStroke = stroke
    }

    override func mouseUp(with event: NSEvent) {
        guard model.isQuickDrawActive, var stroke = model.activeStroke else { return }
        stroke.points.append(convert(event.locationInWindow, from: nil))
        model.strokes.append(stroke)
        model.activeStroke = nil
    }

    private func drawSamplePayload(in rect: NSRect) {
        drawSampleStroke(
            points: [
                CGPoint(x: 0.18, y: 0.26),
                CGPoint(x: 0.26, y: 0.17),
                CGPoint(x: 0.39, y: 0.16),
                CGPoint(x: 0.51, y: 0.24),
                CGPoint(x: 0.63, y: 0.20),
                CGPoint(x: 0.74, y: 0.25),
                CGPoint(x: 0.81, y: 0.35)
            ],
            color: NSColor.systemPink,
            width: 9,
            in: rect
        )
        drawSampleStroke(
            points: [
                CGPoint(x: 0.20, y: 0.72),
                CGPoint(x: 0.33, y: 0.66),
                CGPoint(x: 0.48, y: 0.73),
                CGPoint(x: 0.62, y: 0.65),
                CGPoint(x: 0.78, y: 0.69)
            ],
            color: NSColor.systemBlue,
            width: 7,
            in: rect
        )
        drawHeart(center: point(x: 0.73, y: 0.50, in: rect), size: 84)
        drawTextCard(in: rect)
        drawSticker(in: rect)
        drawSparkles(in: rect)
    }

    private func drawStroke(_ stroke: CanvasStroke) {
        guard stroke.points.count > 1 else { return }

        let path = NSBezierPath()
        path.move(to: stroke.points[0])
        for point in stroke.points.dropFirst() {
            path.line(to: point)
        }

        stroke.color.setStroke()
        path.lineCapStyle = .round
        path.lineJoinStyle = .round
        path.lineWidth = stroke.width
        path.stroke()
    }

    private func drawSampleStroke(points: [CGPoint], color: NSColor, width: CGFloat, in rect: NSRect) {
        guard let first = points.first else { return }

        let path = NSBezierPath()
        path.move(to: point(x: first.x, y: first.y, in: rect))
        for point in points.dropFirst() {
            path.line(to: self.point(x: point.x, y: point.y, in: rect))
        }

        color.setStroke()
        path.lineCapStyle = .round
        path.lineJoinStyle = .round
        path.lineWidth = width
        path.stroke()
    }

    private func drawHeart(center: CGPoint, size: CGFloat) {
        let path = NSBezierPath()
        let scale = size / 100
        path.move(to: CGPoint(x: center.x, y: center.y + 30 * scale))
        path.curve(
            to: CGPoint(x: center.x - 46 * scale, y: center.y - 8 * scale),
            controlPoint1: CGPoint(x: center.x - 10 * scale, y: center.y + 8 * scale),
            controlPoint2: CGPoint(x: center.x - 42 * scale, y: center.y + 18 * scale)
        )
        path.curve(
            to: CGPoint(x: center.x, y: center.y - 48 * scale),
            controlPoint1: CGPoint(x: center.x - 50 * scale, y: center.y - 32 * scale),
            controlPoint2: CGPoint(x: center.x - 20 * scale, y: center.y - 40 * scale)
        )
        path.curve(
            to: CGPoint(x: center.x + 46 * scale, y: center.y - 8 * scale),
            controlPoint1: CGPoint(x: center.x + 20 * scale, y: center.y - 40 * scale),
            controlPoint2: CGPoint(x: center.x + 50 * scale, y: center.y - 32 * scale)
        )
        path.curve(
            to: CGPoint(x: center.x, y: center.y + 30 * scale),
            controlPoint1: CGPoint(x: center.x + 42 * scale, y: center.y + 18 * scale),
            controlPoint2: CGPoint(x: center.x + 10 * scale, y: center.y + 8 * scale)
        )
        path.close()

        NSColor.systemRed.setFill()
        path.fill()
    }

    private func drawTextCard(in rect: NSRect) {
        let cardRect = NSRect(
            x: rect.minX + rect.width * 0.15,
            y: rect.minY + rect.height * 0.41,
            width: rect.width * 0.45,
            height: 62
        )
        NSColor(calibratedWhite: 1, alpha: model.isQuickDrawActive ? 0.72 : 0.48).setFill()
        NSBezierPath(roundedRect: cardRect, xRadius: 24, yRadius: 24).fill()

        let attributes: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: 24, weight: .semibold),
            .foregroundColor: NSColor(calibratedWhite: 0.07, alpha: 0.88)
        ]
        NSString(string: "Hello world").draw(
            in: cardRect.insetBy(dx: 20, dy: 15),
            withAttributes: attributes
        )
    }

    private func drawSticker(in rect: NSRect) {
        let center = point(x: 0.32, y: 0.58, in: rect)
        let body = NSBezierPath(ovalIn: NSRect(x: center.x - 34, y: center.y - 26, width: 68, height: 52))
        NSColor.systemOrange.setFill()
        body.fill()

        NSColor.white.setFill()
        NSBezierPath(ovalIn: NSRect(x: center.x - 18, y: center.y - 12, width: 10, height: 10)).fill()
        NSBezierPath(ovalIn: NSRect(x: center.x + 8, y: center.y - 12, width: 10, height: 10)).fill()

        NSColor(calibratedWhite: 0.15, alpha: 0.9).setStroke()
        let smile = NSBezierPath()
        smile.move(to: CGPoint(x: center.x - 12, y: center.y + 8))
        smile.curve(
            to: CGPoint(x: center.x + 14, y: center.y + 8),
            controlPoint1: CGPoint(x: center.x - 4, y: center.y + 19),
            controlPoint2: CGPoint(x: center.x + 7, y: center.y + 19)
        )
        smile.lineWidth = 3
        smile.stroke()
    }

    private func drawSparkles(in rect: NSRect) {
        [CGPoint(x: 0.17, y: 0.20), CGPoint(x: 0.85, y: 0.30), CGPoint(x: 0.61, y: 0.79)].forEach { sparkle in
            let center = point(x: sparkle.x, y: sparkle.y, in: rect)
            NSColor.systemYellow.setStroke()
            let path = NSBezierPath()
            path.move(to: CGPoint(x: center.x, y: center.y - 14))
            path.line(to: CGPoint(x: center.x, y: center.y + 14))
            path.move(to: CGPoint(x: center.x - 14, y: center.y))
            path.line(to: CGPoint(x: center.x + 14, y: center.y))
            path.lineCapStyle = .round
            path.lineWidth = 4
            path.stroke()
        }
    }

    private func point(x: CGFloat, y: CGFloat, in rect: NSRect) -> CGPoint {
        CGPoint(x: rect.minX + rect.width * x, y: rect.minY + rect.height * y)
    }
}

final class OverlayPanel: NSPanel {
    override var canBecomeKey: Bool {
        true
    }

    override var canBecomeMain: Bool {
        true
    }
}

struct ToolPaletteView: View {
    @ObservedObject var model: CanvasModel
    let onDone: () -> Void
    let onClear: () -> Void

    private let colors: [NSColor] = [
        .systemPink,
        .systemBlue,
        .systemGreen,
        .systemOrange,
        .labelColor
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 8) {
                ForEach(colors, id: \.self) { color in
                    Button {
                        model.selectedColor = color
                    } label: {
                        Circle()
                            .fill(Color(nsColor: color))
                            .frame(width: 22, height: 22)
                            .overlay(
                                Circle()
                                    .stroke(model.selectedColor == color ? Color.primary : Color.clear, lineWidth: 2)
                            )
                    }
                    .buttonStyle(.plain)
                }
            }

            HStack {
                Image(systemName: "paintbrush.pointed")
                Slider(value: Binding(
                    get: { Double(model.brushWidth) },
                    set: { model.brushWidth = CGFloat($0) }
                ), in: 2...24)
                .frame(width: 130)
            }

            HStack(spacing: 8) {
                Button("Clear", action: onClear)
                Button("Done", action: onDone)
                    .keyboardShortcut(.escape, modifiers: [])
            }
        }
        .padding(14)
        .frame(width: 230)
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }
}

@MainActor
final class AppController: NSObject, NSApplicationDelegate {
    private let model = CanvasModel()
    private var statusItem: NSStatusItem?
    private var overlayPanel: OverlayPanel?
    private var palettePanel: OverlayPanel?
    private var keyMonitor: Any?
    private var hotKeyRef: EventHotKeyRef?
    private var hotKeyHandler: EventHandlerRef?

    private let passiveLevel = NSWindow.Level(rawValue: NSWindow.Level.normal.rawValue - 1)
    private let quickDrawLevel = NSWindow.Level.floating

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        createStatusMenu()
        createOverlay()
        registerQuickDrawHotKey()
        observeAppLifecycle()
    }

    func applicationWillTerminate(_ notification: Notification) {
        if let keyMonitor {
            NSEvent.removeMonitor(keyMonitor)
        }
        if let hotKeyRef {
            UnregisterEventHotKey(hotKeyRef)
        }
        if let hotKeyHandler {
            RemoveEventHandler(hotKeyHandler)
        }
    }

    @objc private func enterQuickDraw() {
        model.isQuickDrawActive = true
        NSApp.activate(ignoringOtherApps: true)

        overlayPanel?.level = quickDrawLevel
        overlayPanel?.ignoresMouseEvents = false
        overlayPanel?.makeKeyAndOrderFront(nil)

        showPalette()
    }

    @objc private func exitQuickDraw() {
        model.isQuickDrawActive = false
        palettePanel?.orderOut(nil)

        overlayPanel?.ignoresMouseEvents = true
        overlayPanel?.level = passiveLevel
        overlayPanel?.orderFrontRegardless()
    }

    @objc private func clearCanvas() {
        model.clear()
    }

    @objc private func sendHeart() {
        NSSound.beep()
    }

    @objc private func quit() {
        NSApp.terminate(nil)
    }

    private func createStatusMenu() {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        item.button?.image = NSImage(systemSymbolName: "scribble.variable", accessibilityDescription: "Mulberry")
        item.button?.imagePosition = .imageOnly

        let menu = NSMenu()
        let quickDrawItem = NSMenuItem(title: "Quick Draw", action: #selector(enterQuickDraw), keyEquivalent: "m")
        quickDrawItem.keyEquivalentModifierMask = [.command, .control]
        menu.addItem(quickDrawItem)
        menu.addItem(NSMenuItem(title: "Send Heart", action: #selector(sendHeart), keyEquivalent: "h"))
        menu.addItem(NSMenuItem(title: "Clear POC Canvas", action: #selector(clearCanvas), keyEquivalent: ""))
        menu.addItem(NSMenuItem.separator())
        let streak = NSMenuItem(title: "Streak: 3 days", action: nil, keyEquivalent: "")
        streak.isEnabled = false
        menu.addItem(streak)
        let mode = NSMenuItem(title: "Passive Overlay: Behind Windows", action: nil, keyEquivalent: "")
        mode.isEnabled = false
        menu.addItem(mode)
        menu.addItem(NSMenuItem.separator())
        menu.addItem(NSMenuItem(title: "Quit", action: #selector(quit), keyEquivalent: "q"))

        item.menu = menu
        statusItem = item
    }

    private func createOverlay() {
        let screenFrame = NSScreen.main?.visibleFrame ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
        let size = NSSize(width: 400, height: 400)
        let frame = NSRect(
            x: screenFrame.maxX - size.width - 48,
            y: screenFrame.minY + 48,
            width: size.width,
            height: size.height
        )

        let panel = OverlayPanel(
            contentRect: frame,
            styleMask: [.borderless],
            backing: .buffered,
            defer: false
        )
        panel.backgroundColor = .clear
        panel.collectionBehavior = [.canJoinAllSpaces, .stationary, .ignoresCycle]
        panel.hasShadow = false
        panel.hidesOnDeactivate = false
        panel.ignoresMouseEvents = true
        panel.isMovableByWindowBackground = false
        panel.isOpaque = false
        panel.level = passiveLevel
        panel.contentView = CanvasView(model: model)
        panel.orderFrontRegardless()

        overlayPanel = panel
    }

    private func showPalette() {
        guard let overlayPanel else { return }

        if palettePanel == nil {
            let palette = OverlayPanel(
                contentRect: paletteFrame(relativeTo: overlayPanel.frame),
                styleMask: [.borderless],
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
                rootView: ToolPaletteView(
                    model: model,
                    onDone: { [weak self] in self?.exitQuickDraw() },
                    onClear: { [weak self] in self?.clearCanvas() }
                )
            )
            palettePanel = palette
        }

        palettePanel?.setFrame(paletteFrame(relativeTo: overlayPanel.frame), display: true)
        palettePanel?.level = quickDrawLevel
        palettePanel?.orderFrontRegardless()
    }

    private func paletteFrame(relativeTo overlayFrame: NSRect) -> NSRect {
        NSRect(
            x: overlayFrame.maxX + 12,
            y: overlayFrame.midY - 72,
            width: 230,
            height: 144
        )
    }

    private func observeAppLifecycle() {
        NotificationCenter.default.addObserver(
            forName: NSApplication.didResignActiveNotification,
            object: NSApp,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.exitQuickDraw()
            }
        }

        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard let self else { return event }
            if self.isQuickDrawHotkey(event) {
                Task { @MainActor in
                    self.enterQuickDraw()
                }
                return nil
            }
            guard event.keyCode == 53 else { return event }
            Task { @MainActor in
                self.exitQuickDraw()
            }
            return nil
        }

    }

    private func isQuickDrawHotkey(_ event: NSEvent) -> Bool {
        let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        return event.charactersIgnoringModifiers?.lowercased() == "m" &&
            flags.contains(.command) &&
            flags.contains(.control) &&
            !flags.contains(.option) &&
            !flags.contains(.shift)
    }

    private func registerQuickDrawHotKey() {
        let hotKeyID = EventHotKeyID(signature: quickDrawHotKeySignature, id: quickDrawHotKeyID)
        RegisterEventHotKey(
            UInt32(kVK_ANSI_M),
            UInt32(cmdKey | controlKey),
            hotKeyID,
            GetApplicationEventTarget(),
            0,
            &hotKeyRef
        )

        var eventType = EventTypeSpec(
            eventClass: OSType(kEventClassKeyboard),
            eventKind: UInt32(kEventHotKeyPressed)
        )

        InstallEventHandler(
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

                let controller = Unmanaged<AppController>.fromOpaque(userData).takeUnretainedValue()
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
    }
}

let app = NSApplication.shared
let controller = AppController()
app.delegate = controller
app.run()
