import Foundation
import Testing
@testable import ProductCore

@Test func undoEraseRewritesEarlierDrawWithoutTouchingPartnerStroke() throws {
    let mine = DrawingStroke(colorArgb: 0xFFB31329, width: 0.01, points: [.init(x: 0.1, y: 0.2)], finished: true)
    let partner = DrawingStroke(colorArgb: 0xFF006B4F, width: 0.02, points: [.init(x: 0.7, y: 0.8)], finished: true)
    var state = DrawingState()
    var history = DrawingHistory()
    for operation in try DrawingOperation.restore(mine) + DrawingOperation.restore(partner) { try state.apply(operation) }
    history.recordDraw(mine)
    history.recordErase(mine)
    try state.apply(.init(type: "DELETE_STROKE", strokeId: mine.id))
    for operation in try history.undo() { try state.apply(operation) }
    let restored = try #require(state.strokes.first { $0.id != partner.id })
    #expect(restored.id != mine.id)
    #expect(restored.points == mine.points)
    for operation in try history.undo() { try state.apply(operation) }
    #expect(state.strokes == [partner])
    for operation in try history.redo() { try state.apply(operation) }
    #expect(state.strokes.count == 2)
    #expect(state.strokes.contains(partner))
    for operation in try history.redo() { try state.apply(operation) }
    #expect(state.strokes == [partner])
    #expect(!history.canRedo)
}

@Test func newDrawClearsRedoAndClearDropsHistory() throws {
    var history = DrawingHistory()
    let stroke = DrawingStroke(colorArgb: 0xFFB31329, width: 0.01, points: [.init(x: 0.1, y: 0.2)], finished: true)
    history.recordDraw(stroke)
    _ = try history.undo()
    #expect(history.canRedo)
    history.recordDraw(stroke)
    #expect(!history.canRedo)
    history.reset()
    #expect(!history.canUndo && !history.canRedo)
}
