import CanvasDrawing
import CanvasRendering
import Foundation
import ImageIO
import ProductCore
import Testing

@Test func offscreenRendererProducesVisiblePixelsAndStickerFallbackDiagnostics() throws {
    let state = CanvasRenderFixture.previewState()
    #expect(state.finishedStrokes.count == 1)
    #expect(state.canvasElements.count == 2)
    let output = try #require(CanvasOffscreenRenderer.renderPNG(
        input: CanvasRenderInput(
            state: state,
            viewport: CGRect(x: 0, y: 0, width: 360, height: 640),
            strokeRenderMode: .dryBrush,
            surface: .test
        )
    ))

    #expect(CanvasOffscreenRenderer.hasVisiblePixels(output.data))
    #expect(output.diagnostics.contains { $0.code == "missing_sticker_asset" })
}

@Test func offscreenRendererSamplesRenderedCanvasColor() throws {
    var state = DrawingState()
    let stroke = DrawingStroke(
        id: "sample",
        colorArgb: 0xFF2457D6,
        width: 0.2,
        createdAt: 1,
        points: [DrawingPoint(x: 0.25, y: 0.5), DrawingPoint(x: 0.75, y: 0.5)],
        finished: true
    )
    for operation in try DrawingOperation.restore(stroke) {
        try state.apply(operation)
    }
    let color = try #require(CanvasOffscreenRenderer.sampleColor(
        input: CanvasRenderInput(
            state: state,
            viewport: CGRect(x: 0, y: 0, width: 100, height: 100),
            strokeRenderMode: .roundStroke,
            surface: .test
        ),
        at: CGPoint(x: 50, y: 50)
    ))

    #expect(color.isClose(to: 0xFF2457D6, tolerance: 32))
    #expect(CanvasOffscreenRenderer.sampleColor(
        input: CanvasRenderInput(
            state: state,
            viewport: CGRect(x: 0, y: 0, width: 100, height: 100),
            strokeRenderMode: .roundStroke,
            surface: .test
        ),
        at: CGPoint(x: 2, y: 2)
    ) == nil)
}

@Test func everyCanvasFontIsRegisteredUnderTheNameIOSUses() {
    CanvasFonts.registerBundledFonts()
    for font in CanvasTextFont.allCases {
        #expect(CanvasTextStyle.font(font, size: 24).fontName == CanvasTextStyle.postScriptName(font))
    }
}

@Test func offscreenRenderingIsTopDownLikeTheView() throws {
    var state = DrawingState()
    let stroke = DrawingStroke(
        id: "top", colorArgb: 0xFF2457D6, width: 0.1, createdAt: 1,
        points: [DrawingPoint(x: 0.2, y: 0.1), DrawingPoint(x: 0.8, y: 0.1)], finished: true
    )
    for operation in try DrawingOperation.restore(stroke) {
        try state.apply(operation)
    }
    let input = CanvasRenderInput(state: state, viewport: CGRect(x: 0, y: 0, width: 100, height: 100), strokeRenderMode: .roundStroke)

    #expect(CanvasOffscreenRenderer.sampleColor(input: input, at: CGPoint(x: 50, y: 10)) != nil)
    #expect(CanvasOffscreenRenderer.sampleColor(input: input, at: CGPoint(x: 50, y: 90)) == nil)
}

@Test func strokeRenderModeParsesBackendAndAndroidValues() {
    #expect(CanvasStrokeRenderMode(rawBackendValue: "dry") == .dryBrush)
    #expect(CanvasStrokeRenderMode(rawBackendValue: "round") == .roundStroke)
    #expect(CanvasStrokeRenderMode(rawBackendValue: "round_stroke_only") == .roundStroke)
    #expect(CanvasStrokeRenderMode(rawBackendValue: "dry-brush") == .dryBrush)
    #expect(CanvasStrokeRenderMode(rawBackendValue: "hybrid") == .dryBrush)
    #expect(CanvasStrokeRenderMode(rawBackendValue: nil) == .dryBrush)
}

/// Re-record the golden on purpose with `CANVAS_RECORD_GOLDENS=1 swift test`. A mismatch leaves
/// the new render in Fixtures/canvas/rendered to compare.
///
/// Pixels are compared, not PNG bytes: macOS versions encode the same image differently and
/// anti-alias edges a shade apart, so CI's older macOS never matched a golden recorded here.
@Test func committedGoldenFixtureMatchesCurrentRenderer() throws {
    let rendered = try #require(renderGoldenFixture())
    let goldenURL = fixtureRoot().appendingPathComponent("goldens/preview-dry-brush.png")
    let fileManager = FileManager.default
    if ProcessInfo.processInfo.environment["CANVAS_RECORD_GOLDENS"] == "1" || !fileManager.fileExists(atPath: goldenURL.path) {
        try fileManager.createDirectory(at: goldenURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try rendered.data.write(to: goldenURL)
        return
    }
    let golden = try #require(PixelImage(png: Data(contentsOf: goldenURL)))
    let current = try #require(PixelImage(png: rendered.data))
    let difference = current.difference(from: golden, tolerance: GoldenTolerance.channel)
    let matches = difference != nil && difference!.changed <= current.count / GoldenTolerance.pixelsPerChange
    if !matches {
        let renderedURL = fixtureRoot().appendingPathComponent("rendered/preview-dry-brush.png")
        try? rendered.data.write(to: renderedURL)
    }
    #expect(difference != nil, "The render is \(current.width)×\(current.height); the golden is \(golden.width)×\(golden.height)")
    if let difference {
        #expect(difference.changed <= current.count / GoldenTolerance.pixelsPerChange,
                "\(difference.changed) of \(current.count) pixels differ by more than \(GoldenTolerance.channel); the largest by \(difference.largest)")
    }
}

@Test func goldenToleranceStillCatchesAMissingStroke() throws {
    var state = DrawingState()
    // The fixture's text and sticker, without the stroke before them.
    for operation in CanvasRenderFixture.previewOperations().suffix(2) {
        try state.apply(operation)
    }
    #expect(state.finishedStrokes.isEmpty && state.canvasElements.count == 2)
    let rendered = try #require(renderGoldenFixture(state))
    let golden = try #require(PixelImage(png: Data(contentsOf: fixtureRoot().appendingPathComponent("goldens/preview-dry-brush.png"))))
    let difference = try #require(PixelImage(png: rendered.data)?.difference(from: golden, tolerance: GoldenTolerance.channel))
    #expect(difference.changed > golden.count / GoldenTolerance.pixelsPerChange)
}

/// How far a render may drift from its golden: a channel a few shades off at an anti-aliased
/// edge passes, while a missing or moved stroke, sticker or text, thousands of pixels, fails.
private enum GoldenTolerance {
    static let channel = 16
    /// At most one pixel in this many may differ by more than `channel`.
    static let pixelsPerChange = 400
}

/// A PNG's pixels as premultiplied sRGB RGBA, however the file was encoded.
private struct PixelImage {
    let width: Int
    let height: Int
    let bytes: [UInt8]
    var count: Int { width * height }

    init?(png: Data) {
        guard let source = CGImageSourceCreateWithData(png as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil),
              let space = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        let width = image.width, height = image.height
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        let drawn = bytes.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8,
                                          bytesPerRow: width * 4, space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
            else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn else { return nil }
        self.width = width; self.height = height; self.bytes = bytes
    }

    /// How many pixels have a channel more than `tolerance` apart, and the largest gap; nil
    /// when the sizes differ.
    func difference(from other: PixelImage, tolerance: Int) -> (changed: Int, largest: Int)? {
        guard width == other.width, height == other.height else { return nil }
        var changed = 0, largest = 0
        for pixel in 0..<count {
            var gap = 0
            for channel in 0..<4 {
                let index = pixel * 4 + channel
                gap = max(gap, abs(Int(bytes[index]) - Int(other.bytes[index])))
            }
            largest = max(largest, gap)
            if gap > tolerance { changed += 1 }
        }
        return (changed, largest)
    }
}

private func renderGoldenFixture(_ state: DrawingState = CanvasRenderFixture.previewState()) -> (data: Data, diagnostics: [CanvasDiagnostic])? {
    return CanvasOffscreenRenderer.renderPNG(
        input: CanvasRenderInput(
            state: state,
            viewport: CGRect(x: 0, y: 0, width: 360, height: 640),
            strokeRenderMode: .dryBrush,
            surface: .test
        )
    )
}

private func fixtureRoot() -> URL {
    URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appendingPathComponent("Fixtures/canvas")
}

private extension UInt32 {
    func isClose(to expected: UInt32, tolerance: UInt32) -> Bool {
        let channels: [(UInt32) -> UInt32] = [
            { ($0 >> 24) & 0xFF },
            { ($0 >> 16) & 0xFF },
            { ($0 >> 8) & 0xFF },
            { $0 & 0xFF }
        ]
        return channels.allSatisfy { channel in
            let lhs = channel(self)
            let rhs = channel(expected)
            return lhs > rhs ? lhs - rhs <= tolerance : rhs - lhs <= tolerance
        }
    }
}
