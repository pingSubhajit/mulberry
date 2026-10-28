import Foundation
import Testing
@testable import ProductCore

@Test func textPayloadMatchesAndroidAndSurvivesTheWire() throws {
    let element = CanvasTextElement(id: "text-1", text: "Hi\nyou", createdAt: 1_700_000_000_000, center: .init(x: 0, y: 1),
                                    rotationRad: 0.5, scale: 2.5, colorArgb: 0xFFB31329, backgroundPillEnabled: true,
                                    font: .permanentMarker, alignment: .right)
    let operation = DrawingOperation.addText(element)
    #expect(operation.type == "ADD_TEXT_ELEMENT" && operation.strokeId == "text-1")
    #expect(operation.payload["font"]?.string == "PERMANENT_MARKER")
    #expect(operation.payload["alignment"]?.string == "RIGHT")
    // Encoding and decoding through JSON may turn 0 and 1 into Booleans.
    let wire = try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(operation.payload))
    #expect(CanvasTextElement(json: wire) == element)
}

@Test func partnerTextUsesAndroidDefaultsForMissingFields() throws {
    let raw = #"{"kind":"TEXT","id":"p","center":{"x":0.25,"y":0.75},"font":"COMIC_SANS","colorArgb":4278190335}"#
    let element = try #require(CanvasTextElement(json: JSONDecoder().decode(JSONValue.self, from: Data(raw.utf8))))
    #expect(element.font == .poppins && element.alignment == .center)
    #expect(element.scale == 1 && element.boxWidth == 0.7 && element.colorArgb == 0xFF0000FF)
    let sticker = #"{"kind":"STICKER","id":"s","center":{"x":0.5,"y":0.5}}"#
    #expect(CanvasTextElement(json: try JSONDecoder().decode(JSONValue.self, from: Data(sticker.utf8))) == nil)
}

@Test func textOperationsUpsertAndDeleteInPaintOrder() throws {
    var state = DrawingState()
    let first = CanvasTextElement(id: "a", text: "one", center: .init(x: 0.2, y: 0.2), colorArgb: 0xFF141414)
    let second = CanvasTextElement(id: "b", text: "two", center: .init(x: 0.4, y: 0.4), colorArgb: 0xFF141414)
    try state.apply(.addText(first)); try state.apply(.addText(second))
    var moved = first; moved.center = .init(x: 0.9, y: 0.9)
    try state.apply(.updateText(moved))
    #expect(state.textElements == [second, moved])
    try state.apply(.deleteText("b"))
    #expect(state.textElements == [moved])
    try state.apply(.init(type: "CLEAR_CANVAS"))
    #expect(state.textElements.isEmpty)
}

@Test func textUndoRedoRestoresEachEdit() throws {
    var state = DrawingState()
    var history = DrawingHistory()
    let added = CanvasTextElement(id: "t", text: "hello", center: .init(x: 0.5, y: 0.5), colorArgb: 0xFFB31329)
    var edited = added; edited.text = "hello there"; edited.font = .kalam
    try state.apply(.addText(added)); history.recordAdd(.text(added))
    try state.apply(.updateText(edited)); history.recordUpdate(from: .text(added), to: .text(edited))
    try state.apply(.deleteText("t")); history.recordDelete(.text(edited))

    for operation in try history.undo() { try state.apply(operation) }
    #expect(state.textElements == [edited])
    for operation in try history.undo() { try state.apply(operation) }
    #expect(state.textElements == [added])
    for operation in try history.undo() { try state.apply(operation) }
    #expect(state.textElements.isEmpty && !history.canUndo)
    for _ in 0..<3 { for operation in try history.redo() { try state.apply(operation) } }
    #expect(state.textElements.isEmpty && !history.canRedo)
}

@Test func unchangedTextEditIsNotAnUndoStep() {
    var history = DrawingHistory()
    let element = CanvasTextElement(text: "same", center: .init(x: 0.5, y: 0.5), colorArgb: 0xFFB31329)
    history.recordUpdate(from: .text(element), to: .text(element))
    #expect(!history.canUndo)
}

@Test func wallpaperSignatureTracksTextOnlyWhenPresent() throws {
    var state = DrawingState()
    let empty = try WallpaperContent.signature(canvas: state, backgroundID: "bg", showDrawings: true, renderMode: "dry")
    try state.apply(.addText(.init(text: "hi", center: .init(x: 0.5, y: 0.5), colorArgb: 0xFFB31329)))
    let withText = try WallpaperContent.signature(canvas: state, backgroundID: "bg", showDrawings: true, renderMode: "dry")
    let hidden = try WallpaperContent.signature(canvas: state, backgroundID: "bg", showDrawings: false, renderMode: "dry")
    #expect(empty != withText)
    #expect(hidden == (try WallpaperContent.signature(canvas: DrawingState(), backgroundID: "bg", showDrawings: false, renderMode: "dry")))
}
