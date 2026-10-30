import AppKit
import CanvasDrawing
import Combine
import CoreText
import ProductCore
import SwiftUI

public enum CanvasRenderingModule {
    public static let name = "CanvasRendering"
}

/// The pair's stroke style setting. The backend stores `dry` or `round`; Android's older
/// spellings still read correctly.
public enum CanvasStrokeRenderMode: String, Codable, Sendable {
    case dryBrush
    case roundStroke

    public init(rawBackendValue: String?) {
        switch rawBackendValue?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "round", "round_stroke", "round-stroke", "round_stroke_only", "round-stroke-only", "round_stroke_only_strokes":
            self = .roundStroke
        default:
            self = .dryBrush
        }
    }
}

public enum CanvasDiagnosticSeverity: String, Codable, Equatable, Sendable {
    case info
    case warning
    case error
}

/// Something the renderer had to work around, such as a font or sticker it could not load.
public struct CanvasDiagnostic: Codable, Equatable, Sendable {
    public var code: String
    public var message: String
    public var severity: CanvasDiagnosticSeverity
    public var elementID: String?

    public init(code: String, message: String, severity: CanvasDiagnosticSeverity = .warning, elementID: String? = nil) {
        self.code = code
        self.message = message
        self.severity = severity
        self.elementID = elementID
    }
}

public enum CanvasRenderSurface: Sendable {
    case passiveOverlay
    case fullApp
    case quickDraw
    case test
}

public struct CanvasRenderInput: @unchecked Sendable {
    public var state: DrawingState
    public var viewport: CGRect
    public var scale: CGFloat
    public var strokeRenderMode: CanvasStrokeRenderMode
    public var surface: CanvasRenderSurface
    public var showsEditingBackground: Bool
    public var selectedElementID: String?
    /// Decoded sticker artwork; stickers without it draw Android's placeholder.
    public var stickerImages: [StickerReference: CanvasImage]
    /// Set on a live canvas, so stickers fade in as their artwork arrives instead.
    public var stickerTimeline: StickerArtworkTimeline?

    public init(
        state: DrawingState,
        viewport: CGRect,
        scale: CGFloat = 1,
        strokeRenderMode: CanvasStrokeRenderMode = .dryBrush,
        surface: CanvasRenderSurface = .passiveOverlay,
        showsEditingBackground: Bool = false,
        selectedElementID: String? = nil,
        stickerImages: [StickerReference: CanvasImage] = [:],
        stickerTimeline: StickerArtworkTimeline? = nil
    ) {
        self.state = state
        self.viewport = viewport
        self.scale = scale
        self.strokeRenderMode = strokeRenderMode
        self.surface = surface
        self.showsEditingBackground = showsEditingBackground
        self.selectedElementID = selectedElementID
        self.stickerImages = stickerImages
        self.stickerTimeline = stickerTimeline
    }
}

public struct CanvasRenderResult: Sendable {
    public var diagnostics: [CanvasDiagnostic]

    public init(diagnostics: [CanvasDiagnostic] = []) {
        self.diagnostics = diagnostics
    }
}

/// Draws the canvas with the renderer the iPhone and iPad use, inside a Mac viewport,
/// adding the Mac's editing background and selection ring.
public final class CanvasRenderer {
    public init() {
        CanvasFonts.registerBundledFonts()
    }

    @discardableResult
    public func render(_ input: CanvasRenderInput, in context: CGContext) -> CanvasRenderResult {
        context.saveGState()
        defer { context.restoreGState() }

        if input.showsEditingBackground {
            context.setFillColor(NSColor.windowBackgroundColor.withAlphaComponent(0.82).cgColor)
            context.fill(input.viewport)
        }

        context.translateBy(x: input.viewport.minX, y: input.viewport.minY)
        let size = input.viewport.size
        let elements = input.state.canvasElements
        DoodleRenderer.draw(
            input.state.strokes,
            elements: elements,
            stickerImages: input.stickerImages,
            stickerTimeline: input.stickerTimeline,
            dry: input.strokeRenderMode == .dryBrush,
            in: context,
            size: size
        )

        if let selectedElementID = input.selectedElementID,
           let selected = elements.first(where: { $0.id == selectedElementID }) {
            drawSelection(for: selected, canvasSize: size, context: context)
        }

        return CanvasRenderResult(diagnostics: elements.compactMap { element in
            guard let sticker = element.sticker, input.stickerImages[sticker.sticker] == nil else { return nil }
            return CanvasDiagnostic(
                code: "missing_sticker_asset",
                message: "Rendered sticker placeholder for \(sticker.sticker.packKey)#\(sticker.sticker.packVersion)/\(sticker.sticker.stickerId).",
                severity: .info,
                elementID: sticker.id
            )
        })
    }

    private func drawSelection(for element: CanvasElement, canvasSize: CGSize, context: CGContext) {
        let scale: Double
        switch element {
        case let .text(text):
            scale = text.scale
        case let .sticker(sticker):
            scale = sticker.scale
        }
        let point = CGPoint(x: element.center.x * canvasSize.width, y: element.center.y * canvasSize.height)
        let radius = max(16, CGFloat(scale) * canvasSize.width * 0.14)
        context.setStrokeColor(NSColor.controlAccentColor.cgColor)
        context.setLineWidth(2)
        context.setLineDash(phase: 0, lengths: [5, 5])
        context.strokeEllipse(in: CGRect(x: point.x - radius, y: point.y - radius, width: radius * 2, height: radius * 2))
        context.setLineDash(phase: 0, lengths: [])
    }
}

/// What a live canvas shows.
public final class CanvasRenderModel: ObservableObject {
    @Published public var state: DrawingState
    @Published public var strokeRenderMode: CanvasStrokeRenderMode
    @Published public var stickerImages: [StickerReference: CanvasImage]
    /// Assigned again on each frame of a sticker fade, which draws the stickers again.
    @Published public var stickerTimeline: StickerArtworkTimeline?
    /// The stroke being drawn on this Mac, shown at every point. Its operations are saved in
    /// batches of points, so `state` has it only as far as the last batch.
    @Published public var activeStroke: DrawingStroke?

    public init(
        state: DrawingState = DrawingState(),
        strokeRenderMode: CanvasStrokeRenderMode = .dryBrush,
        stickerImages: [StickerReference: CanvasImage] = [:]
    ) {
        self.state = state
        self.strokeRenderMode = strokeRenderMode
        self.stickerImages = stickerImages
    }
}

/// Draws a live canvas in three layers, so a frame costs only what changed rather than the whole
/// drawing. Finished strokes are rasterized away from the main thread and shown as one image;
/// strokes being drawn, here or by the partner, are drawn above it as they grow; text and stickers
/// sit on top, drawn again only when they change.
@MainActor
public final class CanvasRenderView: NSView {
    private let settledView = SettledStrokesView()
    private let liveView = LiveStrokesView()
    private let elementsView = CanvasElementsView()
    private let rasterizer = StrokeRasterizer()
    private var cancellables: Set<AnyCancellable> = []

    private var dry = true
    /// Finished strokes, in painting order. These change only when a stroke finishes or goes.
    private var settled: [DrawingStroke] = []
    /// Strokes still being drawn. These change with every batch of points.
    private var inProgress: [DrawingStroke] = []
    private var activeStroke: DrawingStroke?
    private var elements: [JSONValue] = []
    private var stickerTimeline: StickerArtworkTimeline?
    /// What the image on screen shows, and the IDs of its strokes.
    private var shown: StrokeRasterizer.Request?
    private var shownIDs: Set<String> = []
    private var rasterizing = false

    public init(model: CanvasRenderModel) {
        super.init(frame: .zero)
        wantsLayer = true
        layer?.backgroundColor = NSColor.clear.cgColor
        for layerView in [settledView, liveView, elementsView] as [NSView] {
            layerView.frame = bounds
            layerView.autoresizingMask = [.width, .height]
            addSubview(layerView)
        }
        // Each publisher sends its new value as it is set, and its current one straight away.
        model.$state
            .sink { [weak self] state in MainActor.assumeIsolated { self?.show(state) } }
            .store(in: &cancellables)
        model.$strokeRenderMode
            .removeDuplicates()
            .sink { [weak self] mode in MainActor.assumeIsolated { self?.show(dry: mode == .dryBrush) } }
            .store(in: &cancellables)
        model.$activeStroke
            .sink { [weak self] stroke in MainActor.assumeIsolated { self?.show(activeStroke: stroke) } }
            .store(in: &cancellables)
        model.$stickerImages
            .removeDuplicates()
            .sink { [weak self] images in MainActor.assumeIsolated { self?.show(stickerImages: images) } }
            .store(in: &cancellables)
        model.$stickerTimeline
            .sink { [weak self] timeline in MainActor.assumeIsolated { self?.show(stickerTimeline: timeline) } }
            .store(in: &cancellables)
    }

    public required init?(coder: NSCoder) {
        nil
    }

    public override var isFlipped: Bool { true }

    /// It only draws; the pointer belongs to the canvas input above it and to the title bar.
    public override func hitTest(_ point: NSPoint) -> NSView? { nil }

    public override func setFrameSize(_ newSize: NSSize) {
        super.setFrameSize(newSize)
        rasterize()
    }

    public override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        rasterize()
    }

    public override func viewDidChangeBackingProperties() {
        super.viewDidChangeBackingProperties()
        rasterize()
    }

    private func show(_ state: DrawingState) {
        let settled = state.finishedStrokes
        let inProgress = state.strokes.filter { $0.finished != true }
        let settledChanged = settled != self.settled
        if settledChanged {
            self.settled = settled
            rasterize()
        }
        if settledChanged || inProgress != self.inProgress {
            self.inProgress = inProgress
            redrawLive()
        }
        if state.elements != elements {
            elements = state.elements
            elementsView.elements = state.canvasElements
            elementsView.needsDisplay = true
        }
    }

    private func show(dry: Bool) {
        self.dry = dry
        rasterize()
        redrawLive()
    }

    private func show(activeStroke: DrawingStroke?) {
        guard activeStroke != self.activeStroke else { return }
        self.activeStroke = activeStroke
        redrawLive()
    }

    private func show(stickerImages: [StickerReference: CanvasImage]) {
        elementsView.stickerImages = stickerImages
        elementsView.needsDisplay = true
    }

    /// A fading sticker is drawn again on every frame, while its timeline stays the same.
    private func show(stickerTimeline: StickerArtworkTimeline?) {
        guard stickerTimeline != self.stickerTimeline || stickerTimeline?.isAnimating() == true else { return }
        self.stickerTimeline = stickerTimeline
        elementsView.stickerTimeline = stickerTimeline
        elementsView.needsDisplay = true
    }

    /// Asks for an image of the settled strokes, unless the one on screen or being made has them.
    /// One image is made at a time; changes meanwhile are caught up with when it is done.
    private func rasterize() {
        guard !rasterizing, bounds.width > 0, bounds.height > 0, let scale = window?.backingScaleFactor else { return }
        let request = StrokeRasterizer.Request(strokes: settled, size: bounds.size, scale: scale, dry: dry)
        guard request != shown else { return }
        rasterizing = true
        Task { [rasterizer] in
            let raster = await rasterizer.render(request)
            rasterizing = false
            guard let raster else { return }
            show(raster)
            rasterize()
        }
    }

    private func show(_ raster: StrokeRasterizer.Raster) {
        shown = raster.request
        shownIDs = Set(raster.request.strokes.map(\.id))
        settledView.show(raster)
        redrawLive()
    }

    /// Settled strokes the image does not have yet, such as one that has just finished, are drawn
    /// with the strokes in progress until it does. This Mac's own stroke is drawn from its every
    /// point rather than from its saved batches.
    private func redrawLive() {
        let active = activeStroke?.id
        liveView.strokes = settled.filter { !shownIDs.contains($0.id) && $0.id != active }
            + inProgress.filter { $0.id != active }
            + (activeStroke.map { [$0] } ?? [])
        liveView.dry = dry
        liveView.needsDisplay = true
    }
}

/// The settled strokes' image. Until the image for a new size arrives, the old one stretches to fit.
private final class SettledStrokesView: NSView {
    private var image: CGImage?
    private var scale: CGFloat = 1

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layerContentsRedrawPolicy = .onSetNeedsDisplay
    }

    required init?(coder: NSCoder) {
        nil
    }

    override var wantsUpdateLayer: Bool { true }

    override func hitTest(_ point: NSPoint) -> NSView? { nil }

    func show(_ raster: StrokeRasterizer.Raster) {
        image = raster.image
        scale = raster.request.scale
        needsDisplay = true
    }

    override func updateLayer() {
        guard let layer else { return }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        layer.contentsGravity = .resize
        layer.contents = image
        layer.contentsScale = scale
        CATransaction.commit()
    }
}

/// Strokes being drawn, drawn in full on every change: there are only ever a few.
private final class LiveStrokesView: NSView {
    var strokes: [DrawingStroke] = []
    var dry = true

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layerContentsRedrawPolicy = .duringViewResize
    }

    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool { true }

    override func hitTest(_ point: NSPoint) -> NSView? { nil }

    override func draw(_ dirtyRect: NSRect) {
        guard !strokes.isEmpty, let context = NSGraphicsContext.current?.cgContext else { return }
        DoodleRenderer.draw(strokes, dry: dry, in: context, size: bounds.size)
    }
}

/// Text and stickers, above every stroke.
private final class CanvasElementsView: NSView {
    var elements: [CanvasElement] = []
    var stickerImages: [StickerReference: CanvasImage] = [:]
    var stickerTimeline: StickerArtworkTimeline?

    override init(frame: NSRect) {
        super.init(frame: frame)
        CanvasFonts.registerBundledFonts()
        wantsLayer = true
        layerContentsRedrawPolicy = .duringViewResize
    }

    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool { true }

    override func hitTest(_ point: NSPoint) -> NSView? { nil }

    override func draw(_ dirtyRect: NSRect) {
        guard !elements.isEmpty, let context = NSGraphicsContext.current?.cgContext else { return }
        DoodleRenderer.draw([], elements: elements, stickerImages: stickerImages, stickerTimeline: stickerTimeline,
                            dry: true, in: context, size: bounds.size)
    }
}

/// A live canvas in SwiftUI. The view follows its model itself, so SwiftUI never redraws it.
public struct CanvasRenderSurfaceView: NSViewRepresentable {
    private let model: CanvasRenderModel

    public init(model: CanvasRenderModel) {
        self.model = model
    }

    public func makeNSView(context: Context) -> CanvasRenderView {
        CanvasRenderView(model: model)
    }

    public func updateNSView(_ nsView: CanvasRenderView, context: Context) {}
}

public enum CanvasRenderFixture {
    /// A stroke, a text label and a sticker, as a partner's device would send them.
    public static func previewOperations() -> [DrawingOperation] {
        let stroke = DrawingStroke(
            id: "stroke-1",
            colorArgb: 0xFFFF4F8B,
            width: 0.028,
            createdAt: 1,
            points: [
                DrawingPoint(x: 0.14, y: 0.18),
                DrawingPoint(x: 0.24, y: 0.3),
                DrawingPoint(x: 0.37, y: 0.22),
                DrawingPoint(x: 0.5, y: 0.42),
                DrawingPoint(x: 0.68, y: 0.29),
                DrawingPoint(x: 0.82, y: 0.46)
            ]
        )
        let text = CanvasTextElement(
            id: "text-1",
            text: "Miss you",
            createdAt: 4,
            center: DrawingPoint(x: 0.5, y: 0.62),
            rotationRad: -0.12,
            scale: 1.08,
            boxWidth: 0.58,
            colorArgb: 0xFFFFD166,
            backgroundPillEnabled: true,
            font: .virgil,
            alignment: .center
        )
        let sticker = CanvasStickerElement(
            id: "sticker-1",
            createdAt: 5,
            center: DrawingPoint(x: 0.48, y: 0.78),
            rotationRad: 0.16,
            scale: 0.22,
            sticker: StickerReference(packKey: "fixture-pack", packVersion: 1, stickerId: "heart")
        )
        // Built from fixed values, so restoring them cannot fail.
        let strokeOperations = (try? DrawingOperation.restore(stroke)) ?? []
        return strokeOperations + [.add(.text(text)), .add(.sticker(sticker))]
    }

    public static func previewState() -> DrawingState {
        var state = DrawingState()
        for operation in previewOperations() {
            try? state.apply(operation)
        }
        return state
    }
}

/// The canvas fonts ship in the app's Resources/Fonts. iOS lists them in its Info.plist;
/// the Mac registers them once at launch so the shared renderer finds them by name.
public enum CanvasFonts {
    private static let registered: Void = {
        let candidates = [
            Bundle.main.resourceURL?.appendingPathComponent("Fonts"),
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent("Resources/Fonts"),
            URL(fileURLWithPath: #filePath)
                .deletingLastPathComponent()
                .deletingLastPathComponent()
                .deletingLastPathComponent()
                .appendingPathComponent("Resources/Fonts")
        ].compactMap { $0 }
        guard let directory = candidates.first(where: { FileManager.default.fileExists(atPath: $0.path) }),
              let files = try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil) else {
            return
        }
        for file in files where file.pathExtension == "ttf" {
            CTFontManagerRegisterFontsForURL(file as CFURL, .process, nil)
        }
    }()

    public static func registerBundledFonts() {
        _ = registered
    }
}

/// The canvas drawn once, offscreen, to read colours from as the eyedropper moves over it.
public final class CanvasColourSampler {
    private let bitmap: NSBitmapImageRep

    public init?(input: CanvasRenderInput, renderer: CanvasRenderer = CanvasRenderer()) {
        guard let output = CanvasOffscreenRenderer.renderBitmap(input: input, renderer: renderer) else { return nil }
        bitmap = output.bitmap
    }

    /// The colour under a point of the canvas, or nil where nothing is drawn.
    public func colour(at point: CGPoint) -> UInt32? {
        CanvasOffscreenRenderer.colour(in: bitmap, at: point)
    }
}

public enum CanvasOffscreenRenderer {
    public static func renderPNG(
        input: CanvasRenderInput,
        renderer: CanvasRenderer = CanvasRenderer()
    ) -> (data: Data, diagnostics: [CanvasDiagnostic])? {
        guard let output = renderBitmap(input: input, renderer: renderer) else { return nil }
        let bitmap = output.bitmap
        let result = output.result
        guard let data = bitmap.representation(using: .png, properties: [:]) else {
            return nil
        }
        return (data, result.diagnostics)
    }

    /// The canvas drawn once as a picture of `size` points, at `scale` pixels per point, for
    /// showing where a live canvas view is not wanted.
    public static func renderImage(
        input: CanvasRenderInput,
        size: CGSize,
        scale: CGFloat,
        renderer: CanvasRenderer = CanvasRenderer()
    ) -> NSImage? {
        var scaled = input
        scaled.viewport = CGRect(origin: .zero, size: CGSize(width: size.width * scale, height: size.height * scale))
        guard let bitmap = renderBitmap(input: scaled, renderer: renderer)?.bitmap else { return nil }
        bitmap.size = size
        let image = NSImage(size: size)
        image.addRepresentation(bitmap)
        return image
    }

    public static func sampleColor(
        input: CanvasRenderInput,
        at point: CGPoint,
        renderer: CanvasRenderer = CanvasRenderer()
    ) -> UInt32? {
        CanvasColourSampler(input: input, renderer: renderer)?.colour(at: point)
    }

    /// Reads a colour from an already drawn bitmap.
    static func colour(in bitmap: NSBitmapImageRep, at point: CGPoint) -> UInt32? {
        let x = Int(point.x.rounded(.down))
        let y = Int(point.y.rounded(.down))
        guard x >= 0, y >= 0, x < bitmap.pixelsWide, y < bitmap.pixelsHigh,
              let sampled = bitmap.colorAt(x: x, y: y)?.usingColorSpace(.deviceRGB),
              sampled.alphaComponent > 0.02 else {
            return nil
        }
        let alpha = UInt32((sampled.alphaComponent * 255).rounded().clamped(to: 0...255))
        let red = UInt32((sampled.redComponent * 255).rounded().clamped(to: 0...255))
        let green = UInt32((sampled.greenComponent * 255).rounded().clamped(to: 0...255))
        let blue = UInt32((sampled.blueComponent * 255).rounded().clamped(to: 0...255))
        return (alpha << 24) | (red << 16) | (green << 8) | blue
    }

    public static func hasVisiblePixels(_ data: Data) -> Bool {
        guard let image = NSImage(data: data),
              let tiff = image.tiffRepresentation,
              let bitmap = NSBitmapImageRep(data: tiff) else {
            return false
        }
        for y in 0..<bitmap.pixelsHigh {
            for x in 0..<bitmap.pixelsWide {
                guard let color = bitmap.colorAt(x: x, y: y), color.alphaComponent > 0.02 else {
                    continue
                }
                return true
            }
        }
        return false
    }

    static func renderBitmap(
        input: CanvasRenderInput,
        renderer: CanvasRenderer
    ) -> (bitmap: NSBitmapImageRep, result: CanvasRenderResult)? {
        let width = Int(input.viewport.width.rounded(.up))
        let height = Int(input.viewport.height.rounded(.up))
        guard width > 0, height > 0,
              let bitmap = NSBitmapImageRep(
                bitmapDataPlanes: nil,
                pixelsWide: width,
                pixelsHigh: height,
                bitsPerSample: 8,
                samplesPerPixel: 4,
                hasAlpha: true,
                isPlanar: false,
                colorSpaceName: .deviceRGB,
                bytesPerRow: 0,
                bitsPerPixel: 0
              ),
              let context = NSGraphicsContext(bitmapImageRep: bitmap)?.cgContext else {
            return nil
        }
        context.clear(CGRect(x: 0, y: 0, width: width, height: height))
        // A bitmap context starts bottom-up; the canvas draws top-down, as in the flipped view.
        context.translateBy(x: 0, y: CGFloat(height))
        context.scaleBy(x: 1, y: -1)
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(cgContext: context, flipped: true)
        let result = renderer.render(input, in: context)
        NSGraphicsContext.restoreGraphicsState()
        return (bitmap, result)
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
