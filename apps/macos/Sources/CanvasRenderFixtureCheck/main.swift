import CanvasRendering
import Foundation

let fileManager = FileManager.default
let packageRoot = URL(fileURLWithPath: fileManager.currentDirectoryPath)
let fixtureRoot = packageRoot.appendingPathComponent("Tests/Fixtures/canvas")
let renderedURL = fixtureRoot.appendingPathComponent("rendered/preview-dry-brush.png")
let goldenURL = fixtureRoot.appendingPathComponent("goldens/preview-dry-brush.png")
let recordGoldens = ProcessInfo.processInfo.environment["CANVAS_RECORD_GOLDENS"] == "1"

let state = CanvasRenderFixture.previewState().state
guard let rendered = CanvasOffscreenRenderer.renderPNG(
    input: CanvasRenderInput(
        state: state,
        viewport: CGRect(x: 0, y: 0, width: 360, height: 640),
        strokeRenderMode: .dryBrush,
        surface: .test
    )
) else {
    fputs("Canvas render fixture check failed: renderer returned no PNG.\n", stderr)
    exit(1)
}

guard CanvasOffscreenRenderer.hasVisiblePixels(rendered.data) else {
    fputs("Canvas render fixture check failed: rendered PNG has no visible pixels.\n", stderr)
    exit(1)
}

try fileManager.createDirectory(
    at: renderedURL.deletingLastPathComponent(),
    withIntermediateDirectories: true
)
try rendered.data.write(to: renderedURL)

if recordGoldens || !fileManager.fileExists(atPath: goldenURL.path) {
    try fileManager.createDirectory(
        at: goldenURL.deletingLastPathComponent(),
        withIntermediateDirectories: true
    )
    try rendered.data.write(to: goldenURL)
    print("Recorded canvas golden: \(goldenURL.path)")
    exit(0)
}

let golden = try Data(contentsOf: goldenURL)
if golden != rendered.data {
    fputs(
        """
        Canvas render fixture check failed: preview-dry-brush.png does not match the committed golden.
        Rendered: \(renderedURL.path)
        Golden: \(goldenURL.path)
        Re-record intentionally with CANVAS_RECORD_GOLDENS=1 swift run CanvasRenderFixtureCheck.

        """,
        stderr
    )
    exit(1)
}

let diagnosticSummary = rendered.diagnostics.map(\.code).joined(separator: ",")
print("Canvas render fixture check passed diagnostics=[\(diagnosticSummary)]")
