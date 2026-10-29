import CanvasEditing
import Foundation
import ProductCore
import Testing

@Test func androidDefaultsAndPaletteAreStable() {
    #expect(CanvasEditingDefaults.defaultColorArgb == 0xFFB31329)
    #expect(CanvasEditingDefaults.defaultBrushWidthPx == 10)
    #expect(CanvasEditingDefaults.minBrushWidthPx == 4)
    #expect(CanvasEditingDefaults.maxBrushWidthPx == 42)
    #expect(CanvasEditingDefaults.strokeHitTolerancePx == 16)
    #expect(CanvasEditingDefaults.palette == [
        0xFFB31329,
        0xFFFF6A2A,
        0xFFFFE66D,
        0xFF2457D6,
        0xFF5BB7E8,
        0xFF006B4F,
        0xFF567A3A,
        0xFF80D8B0,
        0xFFE85072,
        0xFFA78BFA,
        0xFF3B2F8F,
        0xFFF5B83D,
        0xFF141414,
        0xFFF7F4EF,
        0xFF3A3A3A,
        0xFF7A4A32,
        0xFFB56A43,
        0xFFD8A0A8,
        0xFF00A53C
    ])
}

@Test func aToolIsAlwaysSelectedStartingWithTheBrush() {
    let state = CanvasEditingToolState()
    #expect(state.activeTool == .draw)
    #expect(CanvasEditingTool.allCases == [.draw, .erase, .text, .sticker, .eyedropper])
}

@Test func selectedColorAndEyedropperMatchAndroidToolBehavior() {
    var state = CanvasEditingToolState()
    #expect(state.selectedColorArgb == CanvasEditingDefaults.defaultColorArgb)

    state.setSelectedColor(0xFF2457D6)
    #expect(state.brushColorArgb == 0xFF2457D6)
    #expect(state.textColorArgb == CanvasEditingDefaults.defaultColorArgb)

    state.setActiveTool(.text)
    state.setSelectedColor(0xFFFFE66D)
    #expect(state.brushColorArgb == 0xFF2457D6)
    #expect(state.textColorArgb == 0xFFFFE66D)
    #expect(state.selectedColorArgb == 0xFFFFE66D)

    state.setActiveTool(.eyedropper)
    state.commitEyedropperColor(0xFF00A53C)
    #expect(state.brushColorArgb == 0xFF00A53C)
    #expect(state.textColorArgb == 0xFF00A53C)
    #expect(state.activeTool == .text)
}

@Test func viewportTransformNormalizesAndDenormalizesPoints() {
    let surface = CGSize(width: 400, height: 800)
    let transform = CanvasViewportTransform(scale: 2, offset: CGPoint(x: 20, y: 40))

    let normalized = transform.normalizedPoint(
        fromRenderedPoint: CGPoint(x: 220, y: 440),
        surfaceSize: surface
    )
    #expect(normalized == DrawingPoint(x: 0.25, y: 0.25))

    let rendered = transform.renderedPoint(
        fromNormalizedPoint: DrawingPoint(x: 0.25, y: 0.25),
        surfaceSize: surface
    )
    #expect(rendered.x == 220)
    #expect(rendered.y == 440)
}

@Test func viewportZoomClampsAndKeepsAnchorStable() {
    let transform = CanvasViewportTransform(scale: 1, offset: .zero)
    let zoomed = transform.zoomed(by: 10, around: CGPoint(x: 100, y: 100))

    #expect(zoomed.scale == 4)
    #expect(zoomed.contentPoint(fromRenderedPoint: CGPoint(x: 100, y: 100)) == CGPoint(x: 100, y: 100))
}

@Test func brushWidthNormalizesAgainstReferenceDimension() {
    let normalized = normalizeBrushWidth(widthPx: 10, surfaceSize: CGSize(width: 400, height: 800))
    #expect(normalized == 0.025)
    #expect(denormalizeBrushWidth(normalized, surfaceSize: CGSize(width: 400, height: 800)) == 10)
}

@Test func availabilityPolicyAllowsOfflineCapableLocalEditingOnlyWhenStateIsUsable() {
    let policy = CanvasEditingAvailabilityPolicy()

    #expect(policy.availability(for: CanvasEditingAvailabilityInput(
        isAuthenticated: false,
        isPaired: true,
        pairSessionID: "pair",
        hasUsableCanvasState: true
    )) == .disabled(.signedOut))

    #expect(policy.availability(for: CanvasEditingAvailabilityInput(
        isAuthenticated: true,
        isPaired: false,
        pairSessionID: nil,
        hasUsableCanvasState: true
    )) == .disabled(.unpaired))

    #expect(policy.availability(for: CanvasEditingAvailabilityInput(
        isAuthenticated: true,
        isPaired: true,
        pairSessionID: "pair",
        hasUsableCanvasState: false
    )) == .disabled(.waitingForCanvasState))

    #expect(policy.availability(for: CanvasEditingAvailabilityInput(
        isAuthenticated: true,
        isPaired: true,
        pairSessionID: "pair",
        hasUsableCanvasState: true
    )) == .enabled)
}

@Test func operationFactoryCreatesDeterministicLocalOperationMetadata() throws {
    let uuid = try #require(UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"))
    let date = try fixedDate()
    let factory = CanvasEditingOperationFactory(
        uuidProvider: { uuid },
        nowProvider: { date }
    )
    let stroke = DrawingStroke(
        id: "stroke-1",
        colorArgb: 0xFFB31329,
        width: 0.025,
        createdAt: 1_777_777_777_000,
        points: [DrawingPoint(x: 0.1, y: 0.2)]
    )

    let operation = try factory.makeAddStroke(stroke)

    #expect(operation.clientOperationId == "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    #expect(operation.type == "ADD_STROKE")
    #expect(operation.strokeId == "stroke-1")
    #expect(operation.serverRevision == nil)
    #expect(operation.clientCreatedAt == "2026-05-10T12:34:56.789Z")
    #expect(operation.clientLocalDate == ServerDate.localDay(date))
    let payload = try operation.payload.decode(StrokeStart.self)
    #expect(payload.id == "stroke-1")
    #expect(payload.colorArgb == 0xFFB31329)
    #expect(payload.width == 0.025)
    #expect(payload.createdAt == 1_777_777_777_000)
    #expect(payload.firstPoint == DrawingPoint(x: 0.1, y: 0.2))
}

@Test func editingMetadataRoundTripsAndKeepsClampedValues() throws {
    let metadata = CanvasEditingMetadata(
        toolState: CanvasEditingToolState(
            activeTool: .text,
            previousTool: .draw,
            brushColorArgb: 0xFF2457D6,
            textColorArgb: 0xFFFFE66D,
            selectedBrushWidthPx: 99
        ),
        lastViewportWidthPx: 1200,
        lastViewportHeightPx: 900
    )

    let loaded = try JSONDecoder().decode(CanvasEditingMetadata.self, from: JSONEncoder().encode(metadata))

    #expect(loaded == metadata)
    #expect(loaded.toolState.previousTool == .text)
    #expect(loaded.toolState.selectedBrushWidthPx == CanvasEditingDefaults.maxBrushWidthPx)
}

@Test func brushEmitsAddAppendAndFinishInOrderWithFinalFlush() throws {
    var engine = makeStrokeEngine(appendBatchSize: 10)
    engine.toolState.setActiveTool(.draw)
    let surface = CGSize(width: 400, height: 800)
    let date = try fixedDate()

    var operations = engine.startStroke(at: CGPoint(x: 40, y: 80), surfaceSize: surface, date: date)
    operations += engine.appendStrokePoint(at: CGPoint(x: 80, y: 120), surfaceSize: surface, date: date)
    #expect(operations.map(\.type) == ["ADD_STROKE"])

    operations += engine.finishStroke(date: date)
    #expect(operations.map(\.type) == ["ADD_STROKE", "APPEND_POINTS", "FINISH_STROKE"])
    #expect(Set(operations.map(\.clientOperationId)).count == 3)
    #expect(try operations[1].payload.decode(StrokePoints.self).points == [DrawingPoint(x: 0.2, y: 0.15)])

    // The operations draw the same stroke the engine tracked, finished.
    var state = DrawingState()
    for operation in operations { try state.apply(operation) }
    #expect(state.finishedStrokes.map(\.points) == [[DrawingPoint(x: 0.1, y: 0.1), DrawingPoint(x: 0.2, y: 0.15)]])
}

@Test func eraserChoosesTopmostHitStrokeAndEmitsDelete() throws {
    var engine = makeStrokeEngine()
    engine.toolState.setActiveTool(.erase)
    let diagonal = [DrawingPoint(x: 0.1, y: 0.1), DrawingPoint(x: 0.9, y: 0.9)]
    let state = try drawingState([
        DrawingStroke(id: "bottom", colorArgb: 0xFF111111, width: 0.02, createdAt: 1, points: diagonal, finished: true),
        DrawingStroke(id: "top", colorArgb: 0xFF222222, width: 0.02, createdAt: 2, points: diagonal, finished: true)
    ])

    let operations = engine.eraseStroke(
        at: CGPoint(x: 200, y: 400),
        in: state,
        surfaceSize: CGSize(width: 400, height: 800)
    )

    #expect(operations.map(\.type) == ["DELETE_STROKE"])
    #expect(operations.first?.strokeId == "top")
}

@Test func clearRequiresRequestAndOnlyConfirmEmitsClear() {
    var engine = makeStrokeEngine()
    #expect(engine.clearConfirmationRequested == false)

    engine.requestClearCanvas()
    #expect(engine.clearConfirmationRequested)

    engine.cancelClearCanvas()
    #expect(engine.clearConfirmationRequested == false)

    engine.requestClearCanvas()
    let operations = engine.confirmClearCanvas()
    #expect(operations.map(\.type) == ["CLEAR_CANVAS"])
    #expect(engine.clearConfirmationRequested == false)
    #expect(engine.canUndo == false)
    #expect(engine.canRedo == false)
}

@Test func undoDrawEmitsDeleteAndRedoReplaysWithNewStrokeID() throws {
    var engine = makeStrokeEngine(appendBatchSize: 1)
    engine.toolState.setActiveTool(.draw)
    let surface = CGSize(width: 400, height: 800)
    let date = try fixedDate()

    _ = engine.startStroke(at: CGPoint(x: 40, y: 80), surfaceSize: surface, date: date)
    _ = engine.appendStrokePoint(at: CGPoint(x: 80, y: 120), surfaceSize: surface, date: date)
    _ = engine.finishStroke(date: date)

    let undo = engine.undo(date: date)
    #expect(undo.map(\.type) == ["DELETE_STROKE"])
    #expect(undo.first?.strokeId == "stroke-1")
    #expect(engine.canRedo)

    let redo = engine.redo()
    #expect(redo.map(\.type) == ["ADD_STROKE", "APPEND_POINTS", "FINISH_STROKE"])
    let replayed = try #require(redo.first?.strokeId)
    #expect(replayed != "stroke-1")
    #expect(redo.allSatisfy { $0.strokeId == replayed })
}

@Test func undoEraseReplaysDeletedStrokeWithNewID() throws {
    var engine = makeStrokeEngine()
    engine.toolState.setActiveTool(.erase)
    let state = try drawingState([
        DrawingStroke(id: "deleted", colorArgb: 0xFF111111, width: 0.02, createdAt: 1,
                      points: [DrawingPoint(x: 0.1, y: 0.1), DrawingPoint(x: 0.2, y: 0.2)], finished: true)
    ])

    _ = engine.eraseStroke(
        at: CGPoint(x: 40, y: 80),
        in: state,
        surfaceSize: CGSize(width: 400, height: 800),
        date: try fixedDate()
    )

    let undo = engine.undo(date: try fixedDate())
    #expect(undo.map(\.type) == ["ADD_STROKE", "APPEND_POINTS", "FINISH_STROKE"])
    #expect(undo.first?.strokeId != "deleted")
}

@Test func undoMidStrokeDropsTheStrokeBeingDrawn() throws {
    var engine = makeStrokeEngine()
    engine.toolState.setActiveTool(.draw)
    _ = engine.startStroke(at: CGPoint(x: 40, y: 80), surfaceSize: CGSize(width: 400, height: 800))
    #expect(engine.canUndo)
    #expect(engine.canRedo == false)

    let undo = engine.undo()
    #expect(undo.map(\.type) == ["DELETE_STROKE"])
    #expect(undo.first?.strokeId == "stroke-1")
    #expect(engine.activeStroke == nil)
    #expect(engine.canUndo == false)
}

// MARK: Text and stickers

private func text(_ value: String = "Miss you") -> CanvasTextElement {
    CanvasTextElement(id: "text-1", text: value, createdAt: 1, center: DrawingPoint(x: 0.4, y: 0.6), colorArgb: 0xFFB31329)
}

private let heart = StickerReference(packKey: "love", packVersion: 2, stickerId: "heart")

private func applying(_ operations: [DrawingOperation], to state: DrawingState) throws -> DrawingState {
    var next = state
    for operation in operations { try next.apply(operation) }
    return next
}

@Test func newTextCommitsAnAddAndBlankNewTextNothing() throws {
    var engine = makeStrokeEngine()
    #expect(engine.commit(.text(text("   ")), isNew: true, in: DrawingState()).isEmpty)
    #expect(engine.canUndo == false)

    let operations = engine.commit(.text(text()), isNew: true, in: DrawingState())
    #expect(operations.map(\.type) == ["ADD_TEXT_ELEMENT"])
    let state = try applying(operations, to: DrawingState())
    #expect(state.canvasElements == [.text(text())])
}

@Test func editingExistingTextUpdatesOnlyWhenChangedAndBlankDeletes() throws {
    var engine = makeStrokeEngine()
    let state = try applying(engine.commit(.text(text()), isNew: true, in: DrawingState()), to: DrawingState())

    #expect(engine.commit(.text(text()), isNew: false, in: state).isEmpty)

    var edited = text("Love you")
    edited.font = .virgil
    edited.alignment = .left
    edited.backgroundPillEnabled = true
    edited.rotationRad = 0.3
    edited.scale = 1.4
    let update = engine.commit(.text(edited), isNew: false, in: state)
    #expect(update.map(\.type) == ["UPDATE_TEXT_ELEMENT"])
    // Every field travels in the Android payload.
    #expect(try applying(update, to: state).canvasElements == [.text(edited)])

    let cleared = engine.commit(.text(text("")), isNew: false, in: state)
    #expect(cleared.map(\.type) == ["DELETE_TEXT_ELEMENT"])
    #expect(cleared.first?.strokeId == "text-1")
}

@Test func elementUndoAndRedoEmitCompensatingOperations() throws {
    var engine = makeStrokeEngine()
    var state = try applying(engine.commit(.text(text()), isNew: true, in: DrawingState()), to: DrawingState())
    let moved = CanvasElement.text({ var value = text(); value.center = DrawingPoint(x: 0.7, y: 0.2); return value }())
    state = try applying(engine.update(moved, in: state), to: state)
    state = try applying(engine.delete(elementID: "text-1", in: state), to: state)
    #expect(state.canvasElements.isEmpty)

    let undoDelete = engine.undo()
    #expect(undoDelete.map(\.type) == ["ADD_TEXT_ELEMENT"])
    state = try applying(undoDelete, to: state)
    #expect(state.canvasElements == [moved])

    let undoMove = engine.undo()
    #expect(undoMove.map(\.type) == ["UPDATE_TEXT_ELEMENT"])
    state = try applying(undoMove, to: state)
    #expect(state.canvasElements == [.text(text())])

    let undoAdd = engine.undo()
    #expect(undoAdd.map(\.type) == ["DELETE_TEXT_ELEMENT"])
    state = try applying(undoAdd, to: state)
    #expect(state.canvasElements.isEmpty)

    #expect(engine.redo().map(\.type) == ["ADD_TEXT_ELEMENT"])
    #expect(engine.redo().map(\.type) == ["UPDATE_TEXT_ELEMENT"])
    #expect(engine.redo().map(\.type) == ["DELETE_TEXT_ELEMENT"])
}

@Test func stickersCommitOnlyOnceChosenAndKeepEveryField() throws {
    var engine = makeStrokeEngine()
    let unchosen = CanvasStickerElement(id: "sticker-1", center: DrawingPoint(x: 0.5, y: 0.5), sticker: StickerReference(packKey: "", packVersion: 0, stickerId: ""))
    #expect(engine.commit(.sticker(unchosen), isNew: true, in: DrawingState()).isEmpty)

    let placed = CanvasStickerElement(id: "sticker-1", createdAt: 7, center: DrawingPoint(x: 0.3, y: 0.8), rotationRad: -0.4, scale: 0.5, sticker: heart)
    let add = engine.commit(.sticker(placed), isNew: true, in: DrawingState())
    #expect(add.map(\.type) == ["ADD_STICKER_ELEMENT"])
    let state = try applying(add, to: DrawingState())
    #expect(state.canvasElements == [.sticker(placed)])

    var bigger = CanvasElement.sticker(placed)
    bigger.scale(by: 100)
    #expect(bigger.sticker?.scale == CanvasStickerElement.scaleRange.upperBound)
    #expect(engine.update(bigger, in: state).map(\.type) == ["UPDATE_STICKER_ELEMENT"])
    #expect(engine.delete(elementID: "sticker-1", in: state).map(\.type) == ["DELETE_STICKER_ELEMENT"])
}

private func drawingState(_ strokes: [DrawingStroke]) throws -> DrawingState {
    var state = DrawingState()
    for stroke in strokes {
        for operation in try DrawingOperation.restore(stroke) { try state.apply(operation) }
    }
    return state
}

private func makeStrokeEngine(appendBatchSize: Int = 4) -> CanvasStrokeEditingEngine {
    let operationIndex = SequenceCounter()
    let strokeIndex = SequenceCounter()
    return CanvasStrokeEditingEngine(
        appendBatchSize: appendBatchSize,
        operationFactory: CanvasEditingOperationFactory(
            uuidProvider: {
                UUID(uuidString: String(format: "00000000-0000-0000-0000-%012d", operationIndex.next()))!
            },
            nowProvider: {
                DateFormatter.testISO.date(from: "2026-05-10T12:34:56.789Z")!
            }
        ),
        strokeIDProvider: {
            "stroke-\(strokeIndex.next())"
        }
    )
}

private final class SequenceCounter: @unchecked Sendable {
    private var value = 0

    func next() -> Int {
        value += 1
        return value
    }
}

private func fixedDate() throws -> Date {
    try #require(DateFormatter.testISO.date(from: "2026-05-10T12:34:56.789Z"))
}

private extension DateFormatter {
    static let testISO: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        return formatter
    }()
}
