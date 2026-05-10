import CanvasCore
import CanvasRendering
import Foundation
import Testing

@Test func offscreenRendererProducesVisiblePixelsAndStickerFallbackDiagnostics() throws {
    let result = CanvasRenderFixture.previewState()
    let output = try #require(CanvasOffscreenRenderer.renderPNG(
        input: CanvasRenderInput(
            state: result.state,
            viewport: CGRect(x: 0, y: 0, width: 360, height: 640),
            strokeRenderMode: .dryBrush,
            surface: .test
        )
    ))

    #expect(CanvasOffscreenRenderer.hasVisiblePixels(output.data))
    #expect(output.diagnostics.contains { $0.code == "missing_sticker_asset" })
}

@Test func offscreenRendererSamplesRenderedCanvasColor() throws {
    let state = CanvasState(committedStrokes: [
        CanvasStroke(
            id: "sample",
            colorArgb: 0xFF2457D6,
            width: 0.2,
            points: [
                CanvasPoint(x: 0.25, y: 0.5),
                CanvasPoint(x: 0.75, y: 0.5)
            ],
            createdAt: 1
        )
    ])
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

@Test func everyCanvasFontResolvesWithoutCrashing() {
    let resolver = CanvasFontResolver()
    for font in CanvasTextFont.allCases {
        #expect(resolver.font(for: font, size: 24).pointSize > 0)
    }
}

@Test func committedGoldenFixtureMatchesCurrentRendererWhenPresent() throws {
    let rendered = try #require(renderGoldenFixture())
    let goldenURL = fixtureRoot()
        .appendingPathComponent("goldens/preview-dry-brush.png")
    guard FileManager.default.fileExists(atPath: goldenURL.path) else {
        return
    }
    let golden = try Data(contentsOf: goldenURL)
    #expect(rendered.data == golden)
}

private func renderGoldenFixture() -> (data: Data, diagnostics: [CanvasDiagnostic])? {
    let state = CanvasRenderFixture.previewState().state
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
