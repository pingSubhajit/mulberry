import CanvasCore
import CanvasEditing
import Foundation
import Persistence
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
    #expect(normalized == CanvasPoint(x: 0.25, y: 0.25))

    let rendered = transform.renderedPoint(
        fromNormalizedPoint: CanvasPoint(x: 0.25, y: 0.25),
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
    let date = try #require(DateFormatter.testISO.date(from: "2026-05-10T12:34:56.789Z"))
    let factory = CanvasEditingOperationFactory(
        uuidProvider: { uuid },
        nowProvider: { date }
    )

    let operation = factory.makeAddStroke(
        strokeId: "stroke-1",
        colorArgb: 0xFFB31329,
        width: 0.025,
        firstPoint: CanvasPoint(x: 0.1, y: 0.2),
        createdAt: 1_777_777_777_000,
        actorUserId: "user",
        pairSessionId: "pair"
    )

    #expect(operation.clientOperationId == "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    #expect(operation.actorUserId == "user")
    #expect(operation.pairSessionId == "pair")
    #expect(operation.type == .addStroke)
    #expect(operation.strokeId == "stroke-1")
    #expect(operation.clientCreatedAt == "2026-05-10T12:34:56.789Z")
    #expect(operation.clientLocalDate == "2026-05-10")
    guard case let .addStroke(payload) = operation.payload else {
        Issue.record("Expected add stroke payload")
        return
    }
    #expect(payload.id == "stroke-1")
    #expect(payload.colorArgb == 0xFFB31329)
    #expect(payload.width == 0.025)
    #expect(payload.firstPoint == CanvasPoint(x: 0.1, y: 0.2))
}

@Test func editingMetadataPersistsThroughDatabaseRoundTrip() throws {
    let database = try makeDatabase()
    let metadata = CanvasEditingMetadata(
        toolState: CanvasEditingToolState(
            activeTool: .text,
            lastNonNoneTool: .draw,
            brushColorArgb: 0xFF2457D6,
            textColorArgb: 0xFFFFE66D,
            selectedBrushWidthPx: 99
        ),
        lastViewportWidthPx: 1200,
        lastViewportHeightPx: 900
    )

    try database.saveCanvasEditingMetadata(metadata)
    let loaded = try database.canvasEditingMetadata()

    #expect(loaded.toolState.activeTool == .text)
    #expect(loaded.toolState.lastNonNoneTool == .text)
    #expect(loaded.toolState.brushColorArgb == 0xFF2457D6)
    #expect(loaded.toolState.textColorArgb == 0xFFFFE66D)
    #expect(loaded.toolState.selectedBrushWidthPx == CanvasEditingDefaults.maxBrushWidthPx)
    #expect(loaded.lastViewportWidthPx == 1200)
    #expect(loaded.lastViewportHeightPx == 900)
}

@Test func brushEmitsAddAppendAndFinishInOrderWithFinalFlush() throws {
    var engine = makeStrokeEngine(appendBatchSize: 10)
    engine.toolState.setActiveTool(.draw)
    let surface = CGSize(width: 400, height: 800)
    let date = try fixedDate()

    var operations = engine.startStroke(at: CGPoint(x: 40, y: 80), surfaceSize: surface, date: date)
    operations += engine.appendStrokePoint(at: CGPoint(x: 80, y: 120), surfaceSize: surface, date: date)
    #expect(operations.map(\.type) == [.addStroke])

    operations += engine.finishStroke(date: date)
    #expect(operations.map(\.type) == [.addStroke, .appendPoints, .finishStroke])
    #expect(Set(operations.map(\.clientOperationId)).count == 3)

    guard case let .appendPoints(payload) = operations[1].payload else {
        Issue.record("Expected append points payload")
        return
    }
    #expect(payload.points == [CanvasPoint(x: 0.2, y: 0.15)])
}

@Test func eraserChoosesTopmostHitStrokeAndEmitsDelete() throws {
    var engine = makeStrokeEngine()
    engine.toolState.setActiveTool(.erase)
    let bottom = CanvasStroke(
        id: "bottom",
        colorArgb: 0xFF111111,
        width: 0.02,
        points: [CanvasPoint(x: 0.1, y: 0.1), CanvasPoint(x: 0.9, y: 0.9)],
        createdAt: 1
    )
    let top = CanvasStroke(
        id: "top",
        colorArgb: 0xFF222222,
        width: 0.02,
        points: [CanvasPoint(x: 0.1, y: 0.1), CanvasPoint(x: 0.9, y: 0.9)],
        createdAt: 2
    )
    let state = CanvasState(committedStrokes: [bottom, top])

    let operations = engine.eraseStroke(
        at: CGPoint(x: 200, y: 400),
        in: state,
        surfaceSize: CGSize(width: 400, height: 800)
    )

    #expect(operations.map(\.type) == [.deleteStroke])
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
    #expect(operations.map(\.type) == [.clearCanvas])
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
    #expect(undo.map(\.type) == [.deleteStroke])
    #expect(undo.first?.strokeId == "stroke-1")

    let redo = engine.redo(date: date)
    #expect(redo.map(\.type) == [.addStroke, .appendPoints, .finishStroke])
    #expect(redo.first?.strokeId == "stroke-2")
    #expect(redo.last?.strokeId == "stroke-2")
}

@Test func undoEraseReplaysDeletedStrokeWithNewID() throws {
    var engine = makeStrokeEngine()
    engine.toolState.setActiveTool(.erase)
    let stroke = CanvasStroke(
        id: "deleted",
        colorArgb: 0xFF111111,
        width: 0.02,
        points: [CanvasPoint(x: 0.1, y: 0.1), CanvasPoint(x: 0.2, y: 0.2)],
        createdAt: 1
    )
    let state = CanvasState(committedStrokes: [stroke])

    _ = engine.eraseStroke(
        at: CGPoint(x: 40, y: 80),
        in: state,
        surfaceSize: CGSize(width: 400, height: 800),
        date: try fixedDate()
    )

    let undo = engine.undo(date: try fixedDate())
    #expect(undo.map(\.type) == [.addStroke, .appendPoints, .finishStroke])
    #expect(undo.first?.strokeId == "stroke-1")
}

private func makeDatabase() throws -> MulberryDatabase {
    let directory = FileManager.default.temporaryDirectory
        .appendingPathComponent("mulberry-canvas-editing-tests-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    return try MulberryDatabase(databaseURL: directory.appendingPathComponent("test.sqlite"))
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
