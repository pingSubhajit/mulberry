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

private func makeDatabase() throws -> MulberryDatabase {
    let directory = FileManager.default.temporaryDirectory
        .appendingPathComponent("mulberry-canvas-editing-tests-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    return try MulberryDatabase(databaseURL: directory.appendingPathComponent("test.sqlite"))
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
