import Foundation
import Testing
@testable import ProductCore

// Android-shaped operation logs, first written for the Mac app's own reducer.

@Test func androidLogReplaysEveryOperationTypeAndClearRemovesEarlierContent() throws {
    let operations = try operationLog("all-operations").filter { $0.strokeId != "missing-stroke" }
    var state = DrawingState()
    for operation in operations { try state.apply(operation) }

    #expect(state.revision == 12)
    #expect(state.strokes.map(\.id) == ["stroke-2"])
    #expect(state.strokes.first?.finished == true)
    #expect(state.strokes.first?.points.count == 1)
    #expect(state.elements.isEmpty)
}

@Test func appendingToAStrokeThatNeverStartedIsRejected() throws {
    let operations = try operationLog("all-operations")
    var state = DrawingState()
    #expect(throws: DrawingError.missingStroke("missing-stroke")) {
        for operation in operations { try state.apply(operation) }
    }
}

@Test func pixelSpaceGeometryFromOldClientsIsRejected() throws {
    var state = DrawingState()
    #expect(throws: DrawingError.invalidStroke) {
        for operation in try operationLog("legacy-geometry") { try state.apply(operation) }
    }
    #expect(state.strokes.isEmpty)
}

@Test func updatingAnElementMovesItToTheTopOfThePaintOrder() throws {
    let text = CanvasTextElement(id: "shared-id", text: "one", center: .init(x: 0.4, y: 0.4), colorArgb: 0xFF000000)
    let sticker = CanvasStickerElement(id: "sticker", center: .init(x: 0.5, y: 0.5), sticker: .init(packKey: "pack", packVersion: 1, stickerId: "a"))
    var updated = text
    updated.text = "two"
    var state = DrawingState()
    for operation in [DrawingOperation.add(.text(text)), .add(.sticker(sticker)), .update(.text(updated))] { try state.apply(operation) }

    #expect(state.canvasElements.map(\.id) == ["sticker", "shared-id"])
    #expect(state.canvasElements.last?.text?.text == "two")
}

private struct OperationLog: Decodable { let operations: [DrawingOperation] }

private func operationLog(_ name: String) throws -> [DrawingOperation] {
    let url = try #require(Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures/canvas"))
    return try JSONDecoder().decode(OperationLog.self, from: Data(contentsOf: url)).operations
}
