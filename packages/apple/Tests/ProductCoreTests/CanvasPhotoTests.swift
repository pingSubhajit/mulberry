import Foundation
import Testing
@testable import ProductCore

@Test func photoPayloadSurvivesTheWireAndAppliesInPaintOrder() throws {
    let photo = CanvasPhotoElement(id: "p1", createdAt: 1_700_000_000_000, center: .init(x: 0.4, y: 0.6), rotationRad: 0.2, scale: 0.5,
                                   photoId: "8d1c5e9a-0c55-4c39-9f0e-0d6f5f3b2a11", cutoutPhotoId: nil, aspectRatio: 0.75, style: .polaroid)
    let operation = DrawingOperation.add(.photo(photo))
    #expect(operation.type == "ADD_PHOTO_ELEMENT" && operation.strokeId == "p1")
    #expect(operation.payload["style"]?.string == "POLAROID" && operation.payload["cutoutPhotoId"] == nil)
    let wire = try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(operation.payload))
    #expect(CanvasPhotoElement(json: wire) == photo)

    var state = DrawingState()
    try state.apply(operation)
    var restyled = photo
    restyled.style = .sticker
    restyled.cutoutPhotoId = "0b6f2c1e-7d0e-4a7e-bb59-5f8a9b0c1d22"
    try state.apply(.update(.photo(restyled)))
    #expect(state.canvasElements == [.photo(restyled)])
    #expect(state.elements.first?["kind"]?.string == "PHOTO")
    try state.apply(.delete(.photo(restyled)))
    #expect(state.canvasElements.isEmpty)
}

@Test func anUnknownStyleIsAFrameAndAnUnknownKindIsSkipped() throws {
    let photo = #"{"kind":"PHOTO","id":"p","center":{"x":0.5,"y":0.5},"photoId":"x","aspectRatio":1.5,"style":"NEON"}"#
    #expect(CanvasElement(json: try JSONDecoder().decode(JSONValue.self, from: Data(photo.utf8)))?.photo?.style == .frame)
    let future = #"{"kind":"VIDEO","id":"v","center":{"x":0.5,"y":0.5}}"#
    #expect(CanvasElement(json: try JSONDecoder().decode(JSONValue.self, from: Data(future.utf8))) == nil)
}

@Test func anElementOperationFromANewerAppIsSkipped() throws {
    var state = DrawingState()
    try state.apply(.init(type: "ADD_VIDEO_ELEMENT", strokeId: "v", payload: .object(["id": .string("v")])))
    #expect(state.elements.isEmpty)
}

@Test func anyOperationFromANewerAppIsSkippedWhileItsRevisionCounts() throws {
    var state = DrawingState()
    try state.apply(.init(type: "SET_CANVAS_THEME", payload: .object(["theme": .string("night")]), serverRevision: 7))
    #expect(state.strokes.isEmpty && state.elements.isEmpty && state.revision == 7)
}
