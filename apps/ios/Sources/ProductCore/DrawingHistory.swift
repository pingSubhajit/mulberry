import Foundation

/// Local editing history changes only the strokes touched by this device. Undo
/// never restores a whole snapshot over a partner's concurrent drawing.
public struct DrawingHistory: Sendable {
    private enum Action: Sendable {
        case draw(DrawingStroke), erase(DrawingStroke)
        var stroke: DrawingStroke {
            switch self { case .draw(let value), .erase(let value): value }
        }
        func replacing(_ stroke: DrawingStroke) -> Action {
            switch self { case .draw: .draw(stroke); case .erase: .erase(stroke) }
        }
    }
    private var undoStack: [Action] = []
    private var redoStack: [Action] = []
    public init() {}
    public var canUndo: Bool { !undoStack.isEmpty }
    public var canRedo: Bool { !redoStack.isEmpty }
    public mutating func recordDraw(_ stroke: DrawingStroke) { record(.draw(stroke)) }
    public mutating func recordErase(_ stroke: DrawingStroke) { record(.erase(stroke)) }
    public mutating func reset() { undoStack = []; redoStack = [] }

    public mutating func undo() throws -> [DrawingOperation] {
        guard let action = undoStack.last else { return [] }
        let result: [DrawingOperation]
        switch action {
        case .draw(let stroke):
            result = [.init(type: "DELETE_STROKE", strokeId: stroke.id)]
            undoStack.removeLast(); redoStack.append(action)
        case .erase(let stroke):
            let restored = copy(stroke)
            result = try .restore(restored)
            undoStack.removeLast()
            rewrite(stroke.id, with: restored)
            redoStack.append(.erase(restored))
        }
        return result
    }

    public mutating func redo() throws -> [DrawingOperation] {
        guard let action = redoStack.last else { return [] }
        let result: [DrawingOperation]
        switch action {
        case .draw(let stroke):
            let restored = copy(stroke)
            result = try .restore(restored)
            redoStack.removeLast(); rewrite(stroke.id, with: restored); undoStack.append(.draw(restored))
        case .erase(let stroke):
            result = [.init(type: "DELETE_STROKE", strokeId: stroke.id)]
            redoStack.removeLast(); undoStack.append(action)
        }
        return result
    }

    private mutating func record(_ action: Action) {
        undoStack.append(action); redoStack = []
        if undoStack.count > 100 { undoStack.removeFirst(undoStack.count - 100) }
    }
    private func copy(_ stroke: DrawingStroke) -> DrawingStroke {
        var next = stroke; next.id = UUID().uuidString; next.createdAt = Int64(Date().timeIntervalSince1970 * 1000); next.finished = true
        return next
    }
    private mutating func rewrite(_ id: String, with stroke: DrawingStroke) {
        undoStack = undoStack.map { $0.stroke.id == id ? $0.replacing(stroke) : $0 }
        redoStack = redoStack.map { $0.stroke.id == id ? $0.replacing(stroke) : $0 }
    }
}

private extension Array where Element == DrawingOperation {
    static func restore(_ stroke: DrawingStroke) throws -> Self { try DrawingOperation.restore(stroke) }
}
