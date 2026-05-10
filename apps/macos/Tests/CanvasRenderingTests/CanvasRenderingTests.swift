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
