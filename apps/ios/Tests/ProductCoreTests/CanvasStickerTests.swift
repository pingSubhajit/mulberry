import Foundation
import Testing
@testable import ProductCore

private let kitten = StickerReference(packKey: "spring-kittens", packVersion: 1, stickerId: "12")

@Test func stickerPayloadMatchesAndroidAndSurvivesTheWire() throws {
    let element = CanvasStickerElement(id: "s1", createdAt: 1_700_000_000_000, center: .init(x: 1, y: 0), rotationRad: -0.3, scale: 1, sticker: kitten)
    let operation = DrawingOperation.add(.sticker(element))
    #expect(operation.type == "ADD_STICKER_ELEMENT" && operation.strokeId == "s1")
    #expect(operation.payload["packKey"]?.string == "spring-kittens" && operation.payload["stickerId"]?.string == "12")
    let wire = try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(operation.payload))
    #expect(CanvasStickerElement(json: wire) == element)
    #expect(DrawingOperation.update(.sticker(element)).type == "UPDATE_STICKER_ELEMENT")
    #expect(DrawingOperation.delete(.sticker(element)).type == "DELETE_STICKER_ELEMENT")
}

@Test func textAndStickersShareOnePaintOrder() throws {
    var state = DrawingState()
    let text = CanvasTextElement(id: "t", text: "hi", center: .init(x: 0.5, y: 0.5), colorArgb: 0xFFB31329)
    let sticker = CanvasStickerElement(id: "s", center: .init(x: 0.4, y: 0.4), sticker: kitten)
    try state.apply(.add(.sticker(sticker))); try state.apply(.add(.text(text)))
    #expect(state.canvasElements.map(\.id) == ["s", "t"])
    var moved = sticker; moved.center = .init(x: 0.1, y: 0.1)
    try state.apply(.update(.sticker(moved)))
    #expect(state.canvasElements == [.text(text), .sticker(moved)])
    #expect(state.textElements == [text])
    try state.apply(.delete(.sticker(moved)))
    #expect(state.canvasElements == [.text(text)])
}

@Test func incompleteStickerPayloadIsSkipped() throws {
    let raw = #"{"kind":"STICKER","id":"s","center":{"x":0.5,"y":0.5},"packKey":"p","packVersion":0,"stickerId":"1"}"#
    #expect(CanvasElement(json: try JSONDecoder().decode(JSONValue.self, from: Data(raw.utf8))) == nil)
}

@Test func stickerUndoRedoAndPinchRange() throws {
    var state = DrawingState()
    var history = DrawingHistory()
    let sticker = CanvasElement.sticker(.init(id: "s", center: .init(x: 0.5, y: 0.5), sticker: kitten))
    var resized = sticker; resized.scale(by: 100)
    #expect(resized.sticker?.scale == CanvasStickerElement.scaleRange.upperBound)
    try state.apply(.add(sticker)); history.recordAdd(sticker)
    try state.apply(.update(resized)); history.recordUpdate(from: sticker, to: resized)
    for operation in try history.undo() { try state.apply(operation) }
    #expect(state.canvasElements == [sticker])
    for operation in try history.undo() { try state.apply(operation) }
    #expect(state.canvasElements.isEmpty)
    for operation in try history.redo() { try state.apply(operation) }
    for operation in try history.redo() { try state.apply(operation) }
    #expect(state.canvasElements == [resized])
}

@Test func stickerCatalogDecodesBackendResponses() throws {
    let raw = #"{"packKey":"spring-kittens","packVersion":1,"title":"Spring Kittens","description":"","coverThumbnailUrl":"http://h/c","coverFullUrl":"http://h/f","sortOrder":0,"featured":true,"stickers":[{"stickerId":"1","thumbnailUrl":"http://h/t","fullUrl":"http://h/u","width":1500,"height":1500,"sortOrder":0}]}"#
    let pack = try JSONDecoder().decode(StickerPackDetail.self, from: Data(raw.utf8))
    #expect(pack.id == "spring-kittens:1" && pack.stickers.first?.width == 1500)
}
