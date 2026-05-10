import AppKit
import CanvasCore
import Combine
import CoreText
import SwiftUI

public enum CanvasRenderingModule {
    public static let name = "CanvasRendering"
}

public enum CanvasRenderSurface: Sendable {
    case passiveOverlay
    case fullApp
    case quickDraw
    case test
}

public struct CanvasRenderInput: Sendable {
    public var state: CanvasState
    public var viewport: CGRect
    public var scale: CGFloat
    public var strokeRenderMode: CanvasStrokeRenderMode
    public var surface: CanvasRenderSurface
    public var showsEditingBackground: Bool
    public var selectedElementID: String?

    public init(
        state: CanvasState,
        viewport: CGRect,
        scale: CGFloat = 1,
        strokeRenderMode: CanvasStrokeRenderMode = .dryBrush,
        surface: CanvasRenderSurface = .passiveOverlay,
        showsEditingBackground: Bool = false,
        selectedElementID: String? = nil
    ) {
        self.state = state
        self.viewport = viewport
        self.scale = scale
        self.strokeRenderMode = strokeRenderMode
        self.surface = surface
        self.showsEditingBackground = showsEditingBackground
        self.selectedElementID = selectedElementID
    }
}

public struct CanvasRenderResult: Sendable {
    public var diagnostics: [CanvasDiagnostic]

    public init(diagnostics: [CanvasDiagnostic] = []) {
        self.diagnostics = diagnostics
    }
}

public protocol CanvasStickerAssetResolving {
    func image(for element: CanvasStickerElement) -> CGImage?
}

public struct EmptyStickerAssetResolver: CanvasStickerAssetResolving {
    public init() {}
    public func image(for element: CanvasStickerElement) -> CGImage? { nil }
}

public final class CanvasRenderer {
    private let fontResolver: CanvasFontResolver
    private let stickerAssetResolver: CanvasStickerAssetResolving

    public init(
        fontResolver: CanvasFontResolver = CanvasFontResolver(),
        stickerAssetResolver: CanvasStickerAssetResolving = EmptyStickerAssetResolver()
    ) {
        self.fontResolver = fontResolver
        self.stickerAssetResolver = stickerAssetResolver
    }

    @discardableResult
    public func render(_ input: CanvasRenderInput, in context: CGContext) -> CanvasRenderResult {
        var diagnostics: [CanvasDiagnostic] = []
        context.saveGState()
        defer { context.restoreGState() }

        if input.showsEditingBackground {
            context.setFillColor(NSColor.windowBackgroundColor.withAlphaComponent(0.82).cgColor)
            context.fill(input.viewport)
        }

        input.state.committedStrokes.forEach { stroke in
            drawStroke(stroke, input: input, context: context)
        }
        input.state.remoteActiveStrokes.values
            .sorted { $0.createdAt == $1.createdAt ? $0.id < $1.id : $0.createdAt < $1.createdAt }
            .forEach { stroke in
                drawStroke(stroke, input: input, context: context)
            }
        if let localActiveStroke = input.state.localActiveStroke {
            drawStroke(localActiveStroke, input: input, context: context)
        }

        for element in input.state.committedElements {
            switch element {
            case let .text(textElement):
                diagnostics.append(contentsOf: drawTextElement(textElement, input: input, context: context))
            case let .sticker(stickerElement):
                diagnostics.append(contentsOf: drawStickerElement(stickerElement, input: input, context: context))
            }
        }

        if let selectedElementID = input.selectedElementID,
           let selected = input.state.committedElements.first(where: { $0.id == selectedElementID }) {
            drawSelection(for: selected, input: input, context: context)
        }

        return CanvasRenderResult(diagnostics: diagnostics)
    }

    private func drawStroke(_ stroke: CanvasStroke, input: CanvasRenderInput, context: CGContext) {
        let renderStroke = stroke.denormalized(in: input.viewport)
        switch input.strokeRenderMode {
        case .roundStroke:
            RoundStrokeRenderer.draw(stroke: renderStroke, in: context)
        case .dryBrush:
            DryBrushStrokeRenderer.draw(stroke: renderStroke, in: context)
        }
    }

    private func drawTextElement(
        _ element: CanvasTextElement,
        input: CanvasRenderInput,
        context: CGContext
    ) -> [CanvasDiagnostic] {
        let center = element.center.denormalized(in: input.viewport)
        let wrapWidth = max(CGFloat(element.boxWidth) * input.viewport.width, 1)
        let font = fontResolver.font(for: element.font, size: 34)
        let textColor: NSColor
        let elementColor = NSColor(argb: element.colorArgb)
        if element.backgroundPillEnabled {
            textColor = elementColor.relativeLuminance > 0.55 ? .black : .white
        } else {
            textColor = elementColor
        }

        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = switch element.alignment {
        case .left: .left
        case .center: .center
        case .right: .right
        }
        let attributed = NSAttributedString(
            string: element.text,
            attributes: [
                .font: font,
                .foregroundColor: textColor,
                .paragraphStyle: paragraph
            ]
        )
        let bounding = attributed.boundingRect(
            with: CGSize(width: wrapWidth, height: CGFloat.greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading]
        ).integral
        let textRect = CGRect(
            x: -wrapWidth / 2,
            y: -bounding.height / 2,
            width: wrapWidth,
            height: max(bounding.height, 1)
        )

        context.saveGState()
        context.translateBy(x: center.x, y: center.y)
        context.rotate(by: CGFloat(element.rotationRad))
        context.scaleBy(x: CGFloat(element.scale), y: CGFloat(element.scale))

        if element.backgroundPillEnabled {
            let padding: CGFloat = 12
            let rect = textRect.insetBy(dx: -padding, dy: -padding)
            context.setFillColor(elementColor.cgColor)
            context.fillPath(
                for: CGPath(
                    roundedRect: rect,
                    cornerWidth: 18,
                    cornerHeight: 18,
                    transform: nil
                )
            )
        }

        attributed.draw(with: textRect, options: [.usesLineFragmentOrigin, .usesFontLeading])
        context.restoreGState()
        return fontResolver.diagnostics(for: element.font)
    }

    private func drawStickerElement(
        _ element: CanvasStickerElement,
        input: CanvasRenderInput,
        context: CGContext
    ) -> [CanvasDiagnostic] {
        let center = element.center.denormalized(in: input.viewport)
        let maxSize = max(CGFloat(element.scale).clamped(to: 0.08...1.6) * input.viewport.width, 1)
        let image = stickerAssetResolver.image(for: element)
        let size = resolvedStickerSize(maxSize: maxSize, image: image)
        let rect = CGRect(x: -size.width / 2, y: -size.height / 2, width: size.width, height: size.height)

        context.saveGState()
        context.translateBy(x: center.x, y: center.y)
        context.rotate(by: CGFloat(element.rotationRad))
        if let image {
            context.draw(image, in: rect)
        } else {
            drawStickerPlaceholder(rect: rect, element: element, context: context)
        }
        context.restoreGState()

        guard image == nil else { return [] }
        return [CanvasDiagnostic(
            code: "missing_sticker_asset",
            message: "Rendered sticker fallback for \(element.packKey)#\(element.packVersion)/\(element.stickerId).",
            severity: .info,
            operationId: element.id
        )]
    }

    private func drawStickerPlaceholder(rect: CGRect, element: CanvasStickerElement, context: CGContext) {
        let hue = CGFloat(abs(element.stableHash % 360)) / 360
        let fill = NSColor(calibratedHue: hue, saturation: 0.32, brightness: 0.96, alpha: 0.72)
        let stroke = NSColor(calibratedHue: hue, saturation: 0.42, brightness: 0.66, alpha: 0.95)
        let path = CGPath(roundedRect: rect, cornerWidth: 18, cornerHeight: 18, transform: nil)
        context.setFillColor(fill.cgColor)
        context.addPath(path)
        context.fillPath()
        context.setStrokeColor(stroke.cgColor)
        context.setLineWidth(2)
        context.addPath(path)
        context.strokePath()

        let symbolRect = rect.insetBy(dx: rect.width * 0.28, dy: rect.height * 0.28)
        context.setStrokeColor(stroke.withAlphaComponent(0.86).cgColor)
        context.setLineWidth(max(2, min(rect.width, rect.height) * 0.04))
        context.strokeEllipse(in: symbolRect)
    }

    private func drawSelection(for element: CanvasElement, input: CanvasRenderInput, context: CGContext) {
        let center: CanvasPoint
        let scale: Float
        switch element {
        case let .text(text):
            center = text.center
            scale = text.scale
        case let .sticker(sticker):
            center = sticker.center
            scale = sticker.scale
        }
        let point = center.denormalized(in: input.viewport)
        let radius = max(16, CGFloat(scale) * input.viewport.width * 0.14)
        context.setStrokeColor(NSColor.controlAccentColor.cgColor)
        context.setLineWidth(2)
        context.setLineDash(phase: 0, lengths: [5, 5])
        context.strokeEllipse(in: CGRect(x: point.x - radius, y: point.y - radius, width: radius * 2, height: radius * 2))
        context.setLineDash(phase: 0, lengths: [])
    }

    private func resolvedStickerSize(maxSize: CGFloat, image: CGImage?) -> CGSize {
        guard let image, image.width > 0, image.height > 0 else {
            return CGSize(width: maxSize, height: maxSize)
        }
        let width = CGFloat(image.width)
        let height = CGFloat(image.height)
        if width >= height {
            return CGSize(width: maxSize, height: maxSize * height / width)
        }
        return CGSize(width: maxSize * width / height, height: maxSize)
    }
}

public final class CanvasRenderModel: ObservableObject {
    @Published public var state: CanvasState
    @Published public var strokeRenderMode: CanvasStrokeRenderMode
    @Published public var diagnostics: [CanvasDiagnostic]

    public init(
        state: CanvasState = CanvasState(),
        strokeRenderMode: CanvasStrokeRenderMode = .dryBrush,
        diagnostics: [CanvasDiagnostic] = []
    ) {
        self.state = state
        self.strokeRenderMode = strokeRenderMode
        self.diagnostics = diagnostics
    }
}

@MainActor
public final class CanvasRenderView: NSView {
    private let model: CanvasRenderModel
    private let renderer: CanvasRenderer
    private let surface: CanvasRenderSurface
    private let showsEditingBackground: Bool
    private var cancellable: AnyCancellable?

    public init(
        model: CanvasRenderModel,
        renderer: CanvasRenderer = CanvasRenderer(),
        surface: CanvasRenderSurface = .passiveOverlay,
        showsEditingBackground: Bool = false
    ) {
        self.model = model
        self.renderer = renderer
        self.surface = surface
        self.showsEditingBackground = showsEditingBackground
        super.init(frame: .zero)
        wantsLayer = true
        layer?.backgroundColor = NSColor.clear.cgColor
        cancellable = model.objectWillChange.sink { [weak self] _ in
            DispatchQueue.main.async {
                self?.needsDisplay = true
            }
        }
    }

    public required init?(coder: NSCoder) {
        nil
    }

    public override var isFlipped: Bool { true }

    public override func draw(_ dirtyRect: NSRect) {
        super.draw(dirtyRect)
        guard let context = NSGraphicsContext.current?.cgContext else { return }
        let result = renderer.render(
            CanvasRenderInput(
                state: model.state,
                viewport: bounds,
                scale: window?.backingScaleFactor ?? NSScreen.main?.backingScaleFactor ?? 1,
                strokeRenderMode: model.strokeRenderMode,
                surface: surface,
                showsEditingBackground: showsEditingBackground
            ),
            in: context
        )
        if model.diagnostics != result.diagnostics {
            DispatchQueue.main.async { [weak model] in
                model?.diagnostics = result.diagnostics
            }
        }
    }
}

public struct CanvasRenderSurfaceView: NSViewRepresentable {
    @ObservedObject private var model: CanvasRenderModel
    private let renderer: CanvasRenderer
    private let surface: CanvasRenderSurface
    private let showsEditingBackground: Bool

    public init(
        model: CanvasRenderModel,
        renderer: CanvasRenderer = CanvasRenderer(),
        surface: CanvasRenderSurface = .fullApp,
        showsEditingBackground: Bool = true
    ) {
        self.model = model
        self.renderer = renderer
        self.surface = surface
        self.showsEditingBackground = showsEditingBackground
    }

    public func makeNSView(context: Context) -> CanvasRenderView {
        CanvasRenderView(
            model: model,
            renderer: renderer,
            surface: surface,
            showsEditingBackground: showsEditingBackground
        )
    }

    public func updateNSView(_ nsView: CanvasRenderView, context: Context) {
        nsView.needsDisplay = true
    }
}

public enum CanvasRenderFixture {
    public static func previewOperations() -> [CanvasOperation] {
        [
            CanvasOperation(
                clientOperationId: "fixture-add-stroke-1",
                actorUserId: "partner",
                pairSessionId: "pair",
                type: .addStroke,
                strokeId: "stroke-1",
                payload: .addStroke(AddStrokePayload(
                    id: "stroke-1",
                    colorArgb: 0xFFFF4F8B,
                    width: 0.028,
                    createdAt: 1,
                    firstPoint: CanvasPoint(x: 0.14, y: 0.18)
                )),
                clientCreatedAt: "2026-01-01T00:00:00.000Z",
                serverRevision: 1,
                createdAt: "2026-01-01T00:00:00.000Z"
            ),
            CanvasOperation(
                clientOperationId: "fixture-append-stroke-1",
                actorUserId: "partner",
                pairSessionId: "pair",
                type: .appendPoints,
                strokeId: "stroke-1",
                payload: .appendPoints(AppendPointsPayload(points: [
                    CanvasPoint(x: 0.24, y: 0.3),
                    CanvasPoint(x: 0.37, y: 0.22),
                    CanvasPoint(x: 0.5, y: 0.42),
                    CanvasPoint(x: 0.68, y: 0.29),
                    CanvasPoint(x: 0.82, y: 0.46)
                ])),
                clientCreatedAt: "2026-01-01T00:00:00.100Z",
                serverRevision: 2,
                createdAt: "2026-01-01T00:00:00.100Z"
            ),
            CanvasOperation(
                clientOperationId: "fixture-finish-stroke-1",
                actorUserId: "partner",
                pairSessionId: "pair",
                type: .finishStroke,
                strokeId: "stroke-1",
                payload: .finishStroke,
                clientCreatedAt: "2026-01-01T00:00:00.200Z",
                serverRevision: 3,
                createdAt: "2026-01-01T00:00:00.200Z"
            ),
            CanvasOperation(
                clientOperationId: "fixture-text-1",
                actorUserId: "user",
                pairSessionId: "pair",
                type: .addTextElement,
                strokeId: "text-1",
                payload: .addTextElement(CanvasTextElement(
                    id: "text-1",
                    text: "Miss you",
                    createdAt: 4,
                    center: CanvasPoint(x: 0.5, y: 0.62),
                    rotationRad: -0.12,
                    scale: 1.08,
                    boxWidth: 0.58,
                    colorArgb: 0xFFFFD166,
                    backgroundPillEnabled: true,
                    font: .virgil,
                    alignment: .center
                )),
                clientCreatedAt: "2026-01-01T00:00:00.300Z",
                serverRevision: 4,
                createdAt: "2026-01-01T00:00:00.300Z"
            ),
            CanvasOperation(
                clientOperationId: "fixture-sticker-1",
                actorUserId: "partner",
                pairSessionId: "pair",
                type: .addStickerElement,
                strokeId: "sticker-1",
                payload: .addStickerElement(CanvasStickerElement(
                    id: "sticker-1",
                    createdAt: 5,
                    center: CanvasPoint(x: 0.48, y: 0.78),
                    rotationRad: 0.16,
                    scale: 0.22,
                    packKey: "fixture-pack",
                    packVersion: 1,
                    stickerId: "heart"
                )),
                clientCreatedAt: "2026-01-01T00:00:00.400Z",
                serverRevision: 5,
                createdAt: "2026-01-01T00:00:00.400Z"
            )
        ]
    }

    public static func previewState() -> CanvasReducerResult {
        CanvasReducer().apply(previewOperations())
    }
}

private enum RoundStrokeRenderer {
    static func draw(stroke: RenderStroke, in context: CGContext) {
        guard !stroke.points.isEmpty else { return }
        context.setStrokeColor(NSColor(argb: stroke.colorArgb).cgColor)
        context.setFillColor(NSColor(argb: stroke.colorArgb).cgColor)
        context.setLineWidth(stroke.width)
        context.setLineCap(.round)
        context.setLineJoin(.round)

        if stroke.points.count == 1, let point = stroke.points.first {
            context.fillEllipse(in: CGRect(
                x: point.x - stroke.width / 2,
                y: point.y - stroke.width / 2,
                width: stroke.width,
                height: stroke.width
            ))
            return
        }

        context.beginPath()
        context.move(to: stroke.points[0])
        stroke.points.dropFirst().forEach { context.addLine(to: $0) }
        context.strokePath()
    }
}

private enum DryBrushStrokeRenderer {
    static func draw(stroke: RenderStroke, in context: CGContext) {
        guard stroke.points.count >= 2 else {
            RoundStrokeRenderer.draw(stroke: stroke, in: context)
            return
        }
        drawBody(stroke: stroke, in: context)
        drawBristles(stroke: stroke, in: context)
    }

    private static func drawBody(stroke: RenderStroke, in context: CGContext) {
        context.saveGState()
        context.setStrokeColor(NSColor(argb: stroke.colorArgb).withAlphaComponent(132 / 255).cgColor)
        context.setLineCap(.butt)
        context.setLineJoin(.round)
        context.setLineWidth(max(1.5, stroke.width * 0.42))
        context.addPath(smoothPath(points: stroke.points))
        context.strokePath()
        context.restoreGState()
    }

    private static func drawBristles(stroke: RenderStroke, in context: CGContext) {
        let bristleCount = Int((stroke.width / 2.6).rounded()).clamped(to: 6...14)
        let bristleWidth = max(0.85, stroke.width / (CGFloat(bristleCount) * 1.75))
        let halfWidth = stroke.width / 2

        for index in 0..<bristleCount {
            let lane = bristleCount == 1
                ? 0
                : -halfWidth + ((CGFloat(index) / CGFloat(bristleCount - 1)) * stroke.width)
            let seed = stroke.seed(index: index)
            let jitter = (CGFloat(seed & 0xFF) / 255 - 0.5) * stroke.width * 0.24
            let alpha = CGFloat(164 + ((seed >> 8) % 70)) / 255
            let widthJitter = 0.78 + (CGFloat((seed >> 16) & 0xFF) / 255) * 0.7

            context.saveGState()
            context.setStrokeColor(NSColor(argb: stroke.colorArgb).withAlphaComponent(alpha).cgColor)
            context.setLineCap(.butt)
            context.setLineJoin(.round)
            context.setLineWidth(bristleWidth * widthJitter)
            context.addPath(bristlePath(
                points: stroke.points,
                laneOffset: lane + jitter,
                seed: seed,
                strokeWidth: stroke.width
            ))
            context.strokePath()
            context.restoreGState()
        }
    }

    private static func smoothPath(points: [CGPoint]) -> CGPath {
        let path = CGMutablePath()
        path.move(to: points[0])
        if points.count > 2 {
            for index in 1..<(points.count - 1) {
                let point = points[index]
                let next = points[index + 1]
                path.addQuadCurve(to: CGPoint(x: (point.x + next.x) / 2, y: (point.y + next.y) / 2), control: point)
            }
        }
        path.addLine(to: points[points.count - 1])
        return path
    }

    private static func bristlePath(points: [CGPoint], laneOffset: CGFloat, seed: Int, strokeWidth: CGFloat) -> CGPath {
        let path = CGMutablePath()
        var drawing = false
        let startTrim = seed % 3
        let endTrim = (seed >> 3) % 4

        for index in points.indices {
            let point = points[index]
            let noise = seededNoise(seed: seed, index: index)
            let shouldGap = index > startTrim &&
                index < points.count - 1 - endTrim &&
                noise < 0.085
            if shouldGap {
                drawing = false
                continue
            }

            let normal = normalAt(index: index, points: points)
            let tangent = tangentAt(index: index, points: points)
            let feather = (seededNoise(seed: seed ^ 0x6D2B79F5, index: index) - 0.5) * strokeWidth * 0.18
            let next = CGPoint(
                x: point.x + normal.dx * laneOffset + tangent.dx * feather,
                y: point.y + normal.dy * laneOffset + tangent.dy * feather
            )
            if !drawing {
                path.move(to: next)
                drawing = true
            } else {
                path.addLine(to: next)
            }
        }

        return path
    }

    private static func normalAt(index: Int, points: [CGPoint]) -> CGVector {
        let tangent = tangentAt(index: index, points: points)
        return CGVector(dx: -tangent.dy, dy: tangent.dx)
    }

    private static func tangentAt(index: Int, points: [CGPoint]) -> CGVector {
        let previous = points[max(0, index - 1)]
        let next = points[min(points.count - 1, index + 1)]
        let dx = next.x - previous.x
        let dy = next.y - previous.y
        let length = max(hypot(dx, dy), 0.001)
        return CGVector(dx: dx / length, dy: dy / length)
    }

    private static func seededNoise(seed: Int, index: Int) -> CGFloat {
        let mixed = Int32(truncatingIfNeeded: seed ^ Int(truncatingIfNeeded: Int32(index) &* Int32(bitPattern: 0x9E3779B9))).mixed
        return CGFloat((UInt32(bitPattern: mixed) >> 1) & 0x7FFFFFFF) / CGFloat(Int32.max)
    }
}

private struct RenderStroke {
    let id: String
    let colorArgb: UInt32
    let width: CGFloat
    let points: [CGPoint]

    func seed(index: Int) -> Int {
        var hash = Int32(0x45D9F3B)
        for scalar in id.unicodeScalars {
            hash = (hash &* 31) ^ Int32(bitPattern: scalar.value)
        }
        hash = (hash &* 31) ^ Int32(bitPattern: colorArgb)
        hash = (hash &* 31) ^ Int32(widthFloatBits)
        hash = (hash &* 31) ^ Int32(index)
        return Int(UInt32(bitPattern: hash.mixed) & 0x7FFFFFFF)
    }

    private var widthFloatBits: UInt32 {
        Float(width).bitPattern
    }
}

public final class CanvasFontResolver {
    private var registeredPostScriptNames: [CanvasTextFont: String] = [:]
    private var fallbackDiagnostics: [CanvasTextFont: CanvasDiagnostic] = [:]

    public init() {}

    public func font(for canvasFont: CanvasTextFont, size: CGFloat) -> NSFont {
        if let postScriptName = postScriptName(for: canvasFont),
           let font = NSFont(name: postScriptName, size: size) {
            return font
        }
        if canvasFont != .poppins {
            fallbackDiagnostics[canvasFont] = CanvasDiagnostic(
                code: "font_fallback",
                message: "Canvas font \(canvasFont.rawValue) fell back to Poppins.",
                severity: .info
            )
        }
        if let fallbackName = postScriptName(for: .poppins),
           let font = NSFont(name: fallbackName, size: size) {
            return font
        }
        return NSFont.systemFont(ofSize: size)
    }

    public func diagnostics(for canvasFont: CanvasTextFont) -> [CanvasDiagnostic] {
        fallbackDiagnostics[canvasFont].map { [$0] } ?? []
    }

    private func postScriptName(for canvasFont: CanvasTextFont) -> String? {
        if let existing = registeredPostScriptNames[canvasFont] {
            return existing
        }
        guard let url = fontURL(for: canvasFont),
              let provider = CGDataProvider(url: url as CFURL),
              let font = CGFont(provider) else {
            return nil
        }
        CTFontManagerRegisterGraphicsFont(font, nil)
        guard let postScriptName = font.postScriptName as String? else {
            return nil
        }
        registeredPostScriptNames[canvasFont] = postScriptName
        return postScriptName
    }

    private func fontURL(for canvasFont: CanvasTextFont) -> URL? {
        let fileName = switch canvasFont {
        case .poppins: "poppins_regular.ttf"
        case .virgil: "virgil_regular.ttf"
        case .dmSans: "dm_sans_regular.ttf"
        case .spaceMono: "space_mono_regular.ttf"
        case .playfairDisplay: "playfair_display_regular.ttf"
        case .bangers: "bangers_regular.ttf"
        case .permanentMarker: "permanent_marker_regular.ttf"
        case .kalam: "kalam_regular.ttf"
        case .oswald: "oswald_regular.ttf"
        }
        let candidates = [
            Bundle.main.resourceURL?.appendingPathComponent("Fonts/\(fileName)"),
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
                .appendingPathComponent("Resources/Fonts/\(fileName)"),
            URL(fileURLWithPath: #filePath)
                .deletingLastPathComponent()
                .deletingLastPathComponent()
                .deletingLastPathComponent()
                .appendingPathComponent("Resources/Fonts/\(fileName)")
        ].compactMap { $0 }
        return candidates.first(where: { FileManager.default.fileExists(atPath: $0.path) })
    }
}

public enum CanvasOffscreenRenderer {
    public static func renderPNG(
        input: CanvasRenderInput,
        renderer: CanvasRenderer = CanvasRenderer()
    ) -> (data: Data, diagnostics: [CanvasDiagnostic])? {
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
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(cgContext: context, flipped: true)
        context.clear(CGRect(x: 0, y: 0, width: width, height: height))
        let result = renderer.render(input, in: context)
        NSGraphicsContext.restoreGraphicsState()
        guard let data = bitmap.representation(using: .png, properties: [:]) else {
            return nil
        }
        return (data, result.diagnostics)
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
}

private extension CanvasStroke {
    func denormalized(in viewport: CGRect) -> RenderStroke {
        RenderStroke(
            id: id,
            colorArgb: colorArgb,
            width: CGFloat(width) * min(max(viewport.width, 1), max(viewport.height, 1)),
            points: points.map { $0.denormalized(in: viewport) }
        )
    }
}

private extension CanvasPoint {
    func denormalized(in viewport: CGRect) -> CGPoint {
        CGPoint(
            x: viewport.minX + CGFloat(x) * viewport.width,
            y: viewport.minY + CGFloat(y) * viewport.height
        )
    }
}

private extension CanvasStickerElement {
    var stableHash: Int {
        "\(packKey)-\(packVersion)-\(stickerId)".unicodeScalars.reduce(0) { partial, scalar in
            Int(Int32(truncatingIfNeeded: partial) &* 31) ^ Int(scalar.value)
        }
    }
}

private extension NSColor {
    convenience init(argb: UInt32) {
        let alpha = CGFloat((argb >> 24) & 0xFF) / 255
        let red = CGFloat((argb >> 16) & 0xFF) / 255
        let green = CGFloat((argb >> 8) & 0xFF) / 255
        let blue = CGFloat(argb & 0xFF) / 255
        self.init(calibratedRed: red, green: green, blue: blue, alpha: alpha)
    }

    var relativeLuminance: CGFloat {
        guard let rgb = usingColorSpace(.deviceRGB) else { return 0 }
        return 0.2126 * rgb.redComponent + 0.7152 * rgb.greenComponent + 0.0722 * rgb.blueComponent
    }
}

private extension CGContext {
    func fillPath(for path: CGPath) {
        addPath(path)
        fillPath()
    }
}

private extension FloatingPoint {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}

private extension Int32 {
    var mixed: Int32 {
        var value = self
        value = value ^ Int32(bitPattern: UInt32(bitPattern: value) >> 16)
        value = value &* Int32(bitPattern: 0x7FEB352D)
        value = value ^ Int32(bitPattern: UInt32(bitPattern: value) >> 15)
        value = value &* Int32(bitPattern: 0x846CA68B)
        value = value ^ Int32(bitPattern: UInt32(bitPattern: value) >> 16)
        return value
    }
}
