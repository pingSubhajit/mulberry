import SwiftUI

enum DoodleTool: String { case brush, eyedropper, eraser }

@MainActor
final class DoodleEditor: ObservableObject {
    static let palette: [UInt32] = [0xFFB31329, 0xFFFF6A2A, 0xFFFFE66D, 0xFF2457D6, 0xFF5BB7E8, 0xFF006B4F, 0xFF567A3A, 0xFF80D8B0, 0xFFE85072, 0xFFA78BFA, 0xFF3B2F8F, 0xFFF5B83D, 0xFF141414, 0xFFF7F4EF, 0xFF3A3A3A, 0xFF7A4A32, 0xFFB56A43, 0xFFD8A0A8, 0xFF00A53C]
    @Published var tool: DoodleTool = .brush
    @Published var color: UInt32 = 0xFFB31329
    // Android widths are physical canvas pixels. A reference width of 1080
    // preserves the same relative brush weight on Retina and Android screens.
    @Published var width: Double = 10
    @Published private(set) var strokes: [DrawingStroke] = []
    @Published private(set) var history = DrawingHistory()
    @Published private(set) var saving = false
    @Published var errorMessage: String? {
        didSet { if validSession { model.canvasSaveError = errorMessage } }
    }
    private let model: MulberryModel
    private let pair: String?
    private let user: String?
    private var active: DrawingStroke?
    private var ended = false
    private var emittedPoints = 0
    private var lastAppend = Date.distantPast
    private var pending: [DrawingOperation] = []
    private var overlay: [DrawingOperation] = []
    private var draining = false

    init(model: MulberryModel) {
        self.model = model; pair = model.bootstrap?.pairSessionId; user = model.userId
        strokes = model.drawing.strokes
        model.retryCanvasSave = { [weak self] in self?.retry() }
    }

    var canUndo: Bool { active == nil && !saving && history.canUndo }
    var canRedo: Bool { active == nil && !saving && history.canRedo }

    func receive() {
        var state = model.drawing
        // Overlay contains only non-append edits; their replay is idempotent.
        for operation in overlay { try? state.apply(operation) }
        if let active {
            state.strokes.removeAll { $0.id == active.id }
            state.strokes.append(active)
        }
        strokes = state.strokes
    }

    func begin(at point: CGPoint, size: CGSize) {
        guard active == nil, !saving, errorMessage == nil, validSession else { return }
        switch tool {
        case .brush:
            let stroke = DrawingStroke(colorArgb: color, width: width / 1080, points: [normalized(point, size)])
            active = stroke; ended = false; emittedPoints = 1; lastAppend = .now
            do {
                queue([.init(type: "ADD_STROKE", strokeId: stroke.id,
                             payload: try .encoded(StrokeStart(id: stroke.id, colorArgb: stroke.colorArgb, width: stroke.width, createdAt: stroke.createdAt, firstPoint: stroke.points[0])))])
            } catch { errorMessage = error.localizedDescription }
            receive()
        case .eraser: erase(at: point, size: size)
        case .eyedropper:
            let background = UIColor(MulberryDesign.background)
            if let sampled = DoodleRenderer.color(at: point, strokes: strokes, dry: model.bootstrap?.canvasStrokeRenderMode == "dry", size: size, background: background) {
                color = sampled; tool = .brush
            }
        }
    }

    func move(points: [CGPoint], size: CGSize) {
        guard validSession, errorMessage == nil else { return }
        if tool == .eraser {
            for point in points { erase(at: point, size: size) }
            return
        }
        guard active != nil, !ended else { return }
        for point in points {
            let next = normalized(point, size)
            if next != active?.points.last { active?.points.append(next) }
        }
        receive()
        if Date().timeIntervalSince(lastAppend) >= 1 / Double(max(1, model.appendHz)) { appendPoints() }
    }

    func end() {
        guard var stroke = active, !ended else { return }
        appendPoints()
        stroke.finished = true; active = stroke; ended = true
        history.recordDraw(stroke)
        queue([.init(type: "FINISH_STROKE", strokeId: stroke.id)])
        receive()
    }

    func undo() {
        guard canUndo else { return }
        do { queue(try history.undo(), preview: true) }
        catch { errorMessage = error.localizedDescription }
    }
    func redo() {
        guard canRedo else { return }
        do { queue(try history.redo(), preview: true) }
        catch { errorMessage = error.localizedDescription }
    }
    func clear() {
        end(); history.reset()
        queue([.init(type: "CLEAR_CANVAS")], preview: true)
    }
    func retry() {
        errorMessage = nil
        Task { await drain() }
    }

    func resetHistory() { history.reset() }

    private var validSession: Bool { model.userId == user && model.bootstrap?.pairSessionId == pair && model.bootstrap?.isPaired == true }

    private func appendPoints() {
        guard let active else { return }
        do {
            while emittedPoints < active.points.count {
                let end = min(emittedPoints + 64, active.points.count)
                let points = Array(active.points[emittedPoints..<end])
                queue([.init(type: "APPEND_POINTS", strokeId: active.id, payload: try .encoded(StrokePoints(points: points)))])
                emittedPoints = end
            }
            lastAppend = .now
        } catch { errorMessage = error.localizedDescription }
    }

    private func erase(at point: CGPoint, size: CGSize) {
        guard let stroke = strokes.reversed().first(where: { $0.finished == true && Self.hits($0, at: point, size: size) }) else { return }
        history.recordErase(stroke)
        queue([.init(type: "DELETE_STROKE", strokeId: stroke.id)], preview: true)
    }

    private func queue(_ operations: [DrawingOperation], preview: Bool = false) {
        pending += operations
        if preview { overlay += operations; receive() }
        saving = true
        Task { await drain() }
    }

    private func drain() async {
        guard !draining, errorMessage == nil, validSession else { return }
        draining = true
        defer { draining = false; saving = !pending.isEmpty }
        while !pending.isEmpty {
            guard validSession else { return }
            let batch = pending
            do {
                try await model.enqueue(batch)
                pending.removeFirst(batch.count)
                let savedIDs = Set(batch.map(\.id))
                overlay.removeAll { savedIDs.contains($0.id) }
                if pending.isEmpty && ended { active = nil; ended = false }
                receive()
            } catch {
                errorMessage = "Your last edit could not be saved. \(error.localizedDescription)"
                return
            }
        }
    }

    private func normalized(_ point: CGPoint, _ size: CGSize) -> DrawingPoint {
        .init(x: min(1, max(0, point.x / max(1, size.width))), y: min(1, max(0, point.y / max(1, size.height))))
    }

    private static func hits(_ stroke: DrawingStroke, at point: CGPoint, size: CGSize) -> Bool {
        let radius = max(8, stroke.width * min(size.width, size.height) / 2 + 5)
        let points = stroke.points.map { CGPoint(x: $0.x * size.width, y: $0.y * size.height) }
        guard let first = points.first else { return false }
        if points.count == 1 { return hypot(point.x - first.x, point.y - first.y) <= radius }
        for (a, b) in zip(points, points.dropFirst()) {
            let dx = b.x - a.x, dy = b.y - a.y
            let length = dx * dx + dy * dy
            let t = length == 0 ? 0 : min(1, max(0, ((point.x - a.x) * dx + (point.y - a.y) * dy) / length))
            if hypot(point.x - a.x - t * dx, point.y - a.y - t * dy) <= radius { return true }
        }
        return false
    }
}

struct DoodleSurface: UIViewRepresentable {
    @ObservedObject var editor: DoodleEditor
    let dry: Bool
    func makeUIView(context: Context) -> DoodleTouchView {
        let view = DoodleTouchView()
        view.accessibilityLabel = "Shared drawing canvas"
        view.accessibilityIdentifier = "drawingCanvas"
        return view
    }
    func updateUIView(_ view: DoodleTouchView, context: Context) {
        view.editor = editor; view.strokes = editor.strokes; view.dry = dry
        view.backgroundColor = UIColor(MulberryDesign.background)
        view.setNeedsDisplay()
    }
}

final class DoodleTouchView: UIView {
    weak var editor: DoodleEditor?
    var strokes: [DrawingStroke] = []
    var dry = true
    private weak var trackedTouch: UITouch?
    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        DoodleRenderer.draw(strokes, dry: dry, in: context, size: bounds.size)
    }
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard trackedTouch == nil, let touch = touches.first else { return }
        trackedTouch = touch; editor?.begin(at: touch.location(in: self), size: bounds.size)
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = trackedTouch, touches.contains(touch) else { return }
        editor?.move(points: (event?.coalescedTouches(for: touch) ?? [touch]).map { $0.location(in: self) }, size: bounds.size)
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let touch = trackedTouch, touches.contains(touch) else { return }
        editor?.move(points: [touch.location(in: self)], size: bounds.size)
        editor?.end(); trackedTouch = nil
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { editor?.end(); trackedTouch = nil }
}
