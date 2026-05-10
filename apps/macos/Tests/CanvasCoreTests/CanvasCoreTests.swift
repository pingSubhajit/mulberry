import CanvasCore
import Foundation
import Testing

@Test func reducerAppliesEveryOperationTypeAndCompactsAfterClear() throws {
    let operations = try loadFixture("all-operations")
    let result = CanvasReducer().apply(operations)

    #expect(result.state.revision == 12)
    #expect(result.state.committedStrokes.map(\.id) == ["stroke-2"])
    #expect(result.state.committedStrokes.first?.points.count == 1)
    #expect(result.state.committedElements.isEmpty)
    #expect(result.state.remoteActiveStrokes.isEmpty)
    #expect(result.diagnostics.contains { $0.code == "missing_active_stroke" })
}

@Test func textAndStickerUpsertsMoveElementsToEnd() {
    let text = CanvasTextElement(
        id: "shared-id",
        text: "one",
        createdAt: 1,
        center: CanvasPoint(x: 0.4, y: 0.4),
        boxWidth: 0.6,
        colorArgb: 0xFF000000
    )
    let sticker = CanvasStickerElement(
        id: "sticker",
        createdAt: 2,
        center: CanvasPoint(x: 0.5, y: 0.5),
        packKey: "pack",
        packVersion: 1,
        stickerId: "a"
    )
    let updatedText = text.copy(text: "two", center: CanvasPoint(x: 0.6, y: 0.6))
    let operations = [
        op(id: "1", type: .addTextElement, strokeId: text.id, payload: .addTextElement(text), revision: 1),
        op(id: "2", type: .addStickerElement, strokeId: sticker.id, payload: .addStickerElement(sticker), revision: 2),
        op(id: "3", type: .updateTextElement, strokeId: text.id, payload: .updateTextElement(updatedText), revision: 3)
    ]

    let result = CanvasReducer().apply(operations)

    #expect(result.state.committedElements.map(\.id) == ["sticker", "shared-id"])
    guard case let .text(finalText) = result.state.committedElements.last else {
        Issue.record("Expected final text element")
        return
    }
    #expect(finalText.text == "two")
}

@Test func legacyGeometryIsDiagnosticAndNotApplied() throws {
    let operations = try loadFixture("legacy-geometry")
    let result = CanvasReducer().apply(operations)

    #expect(result.state.isEmpty)
    #expect(result.diagnostics.first?.code == "legacy_geometry")
    #expect(result.diagnostics.first?.severity == .error)
}

@Test func strokeRenderModeParsesAndroidAliases() {
    #expect(CanvasStrokeRenderMode(rawBackendValue: "round_stroke_only") == .roundStroke)
    #expect(CanvasStrokeRenderMode(rawBackendValue: "dry-brush") == .dryBrush)
    #expect(CanvasStrokeRenderMode(rawBackendValue: "hybrid") == .dryBrush)
    #expect(CanvasStrokeRenderMode(rawBackendValue: nil) == .dryBrush)
}

private func loadFixture(_ name: String) throws -> [CanvasOperation] {
    let url = fixtureRoot()
        .appendingPathComponent("\(name).json")
    let data = try Data(contentsOf: url)
    return try JSONDecoder().decode(CanvasOperationFixture.self, from: data).operations
}

private func fixtureRoot() -> URL {
    URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appendingPathComponent("Fixtures/canvas")
}

private func op(
    id: String,
    type: CanvasOperationType,
    strokeId: String?,
    payload: CanvasOperationPayload,
    revision: Int64
) -> CanvasOperation {
    CanvasOperation(
        clientOperationId: id,
        actorUserId: "user",
        pairSessionId: "pair",
        type: type,
        strokeId: strokeId,
        payload: payload,
        clientCreatedAt: "2026-01-01T00:00:00.000Z",
        serverRevision: revision,
        createdAt: "2026-01-01T00:00:00.000Z"
    )
}

private struct CanvasOperationFixture: Decodable {
    let operations: [CanvasOperation]
}

private extension CanvasTextElement {
    func copy(text: String, center: CanvasPoint) -> CanvasTextElement {
        CanvasTextElement(
            id: id,
            text: text,
            createdAt: createdAt,
            center: center,
            rotationRad: rotationRad,
            scale: scale,
            boxWidth: boxWidth,
            colorArgb: colorArgb,
            backgroundPillEnabled: backgroundPillEnabled,
            font: font,
            alignment: alignment
        )
    }
}
