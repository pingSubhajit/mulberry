import SwiftUI

enum DoodleTool: String, Codable { case brush, eyedropper, eraser, text, sticker }

/// Mirrors Android's canvas metadata: brush colour, text colour, width and tool
/// survive restarts, and start fresh for a new user or pair session.
struct CanvasToolSettings: Codable, Equatable {
    var tool: DoodleTool = .brush
    var color: UInt32 = 0xFFB31329
    var width: Double = 10
    var textColor: UInt32 = 0xFFB31329

    init() {}
    init(tool: DoodleTool, color: UInt32, width: Double, textColor: UInt32) {
        self.tool = tool; self.color = color; self.width = width; self.textColor = textColor
    }

    // Settings saved before text existed have no text colour.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let defaults = CanvasToolSettings()
        tool = try c.decodeIfPresent(DoodleTool.self, forKey: .tool) ?? defaults.tool
        color = try c.decodeIfPresent(UInt32.self, forKey: .color) ?? defaults.color
        width = try c.decodeIfPresent(Double.self, forKey: .width) ?? defaults.width
        textColor = try c.decodeIfPresent(UInt32.self, forKey: .textColor) ?? defaults.textColor
    }
}

/// A text element open in the full-screen editor. New text is only added on Done.
struct CanvasTextSession: Identifiable, Equatable {
    var element: CanvasTextElement
    let isNew: Bool
    var id: String { element.id }
}

/// A sticker open in its editor. A new sticker has an empty reference until
/// the catalog provides a default or the user picks one.
struct CanvasStickerSession: Identifiable, Equatable {
    var element: CanvasStickerElement
    let isNew: Bool
    var id: String { element.id }
}

@MainActor enum CanvasToolStore {
    static let widths: ClosedRange<Double> = 4...42

    static func load(user: String?, pair: String?) -> CanvasToolSettings {
        guard let user, let data = UserDefaults.standard.data(forKey: key(user, pair)),
              var settings = try? JSONDecoder().decode(CanvasToolSettings.self, from: data) else { return .init() }
        settings.width = min(widths.upperBound, max(widths.lowerBound, settings.width))
        // The eyedropper is a one-shot sampling mode, not a resting tool. Text
        // placement also starts cold in the brush, as Android starts with no tool.
        if [.eyedropper, .text, .sticker].contains(settings.tool) { settings.tool = .brush }
        return settings
    }

    static func save(_ settings: CanvasToolSettings, user: String?, pair: String?) {
        guard let user, let data = try? JSONEncoder().encode(settings) else { return }
        UserDefaults.standard.set(data, forKey: key(user, pair))
    }

    private static func key(_ user: String, _ pair: String?) -> String { "canvasTools.\(user).\(pair ?? "unpaired")" }
}

@MainActor
final class DoodleEditor: ObservableObject {
    static let palette: [UInt32] = [0xFFB31329, 0xFFFF6A2A, 0xFFFFE66D, 0xFF2457D6, 0xFF5BB7E8, 0xFF006B4F, 0xFF567A3A, 0xFF80D8B0, 0xFFE85072, 0xFFA78BFA, 0xFF3B2F8F, 0xFFF5B83D, 0xFF141414, 0xFFF7F4EF, 0xFF3A3A3A, 0xFF7A4A32, 0xFFB56A43, 0xFFD8A0A8, 0xFF00A53C]
    @Published var tool: DoodleTool {
        didSet {
            if tool != oldValue, Self.drawingTools.contains(oldValue) { previousTool = oldValue }
            saveTools()
        }
    }
    /// Apple Pencil's double-tap and squeeze move between these; text and stickers open editors.
    private static let drawingTools: Set<DoodleTool> = [.brush, .eraser, .eyedropper]
    /// The drawing tool before the current one, for Apple Pencil's "switch to previous tool".
    private(set) var previousTool: DoodleTool = .brush
    /// A double-tap or squeeze asking the canvas to show something.
    @Published var pencilRequest: PencilRequest?
    /// A finger held still on the phone's canvas, asking for the reactions there.
    @Published var reactionRequest: ReactionRequest?
    /// The held finger after the reactions open, so sliding onto one and letting go sends it.
    let reactionPointer = ReactionPointer()
    /// Counts touches that start on the canvas, so the squeeze palette closes as drawing begins.
    @Published private(set) var canvasTouches = 0
    func canvasTouched() { canvasTouches += 1 }
    /// The stroke being drawn, which the canvas extends with predicted Pencil points.
    var activeStroke: DrawingStroke? { ended ? nil : active }
    @Published var color: UInt32 { didSet { saveTools() } }
    // Android widths are physical canvas pixels. A reference width of 1080
    // preserves the same relative brush weight on Retina and Android screens.
    @Published var width: Double { didSet { saveTools() } }
    @Published var textColor: UInt32 { didSet { saveTools() } }
    @Published private(set) var strokes: [DrawingStroke] = []
    @Published private(set) var elements: [CanvasElement] = []
    @Published var textSession: CanvasTextSession?
    @Published var stickerSession: CanvasStickerSession?
    let stickers: StickerCatalog
    /// Artwork the canvas has loaded, so the eyedropper samples stickers too.
    var stickerImages: [StickerReference: UIImage] = [:]
    @Published private(set) var history = DrawingHistory()
    @Published private(set) var saving = false
    @Published var errorMessage: String? {
        didSet { if validSession { model.canvasSaveError = errorMessage } }
    }
    private let model: MulberryModel
    private let pair: String?
    let user: String?
    private var active: DrawingStroke?
    private var ended = false
    private var emittedPoints = 0
    private var lastAppend = Date.distantPast
    private var pending: [DrawingOperation] = []
    private var overlay: [DrawingOperation] = []
    private var draining = false
    private var decodedElements: [JSONValue] = []

    init(model: MulberryModel) {
        self.model = model; pair = model.bootstrap?.pairSessionId; user = model.userId
        let tools = CanvasToolStore.load(user: user, pair: pair)
        tool = tools.tool; color = tools.color; width = tools.width; textColor = tools.textColor
        strokes = model.drawing.strokes
        elements = model.drawing.canvasElements; decodedElements = model.drawing.elements
        stickers = StickerCatalog(user: user)
        model.retryCanvasSave = { [weak self] in self?.retry() }
    }

    var canUndo: Bool { active == nil && !saving && history.canUndo }
    var canRedo: Bool { active == nil && !saving && history.canRedo }

    /// Android keeps one colour for text and one for every other tool.
    var selectedColor: UInt32 {
        get { tool == .text ? textColor : color }
        set { if tool == .text { textColor = newValue } else { color = newValue } }
    }

    func receive() {
        var state = model.drawing
        // Overlay contains only non-append edits; their replay is idempotent.
        for operation in overlay { try? state.apply(operation) }
        if let active {
            state.strokes.removeAll { $0.id == active.id }
            state.strokes.append(active)
        }
        strokes = state.strokes
        // Receive runs for every stroke point; decode elements only when they change.
        if state.elements != decodedElements {
            decodedElements = state.elements
            elements = state.canvasElements
        }
    }

    func begin(at point: CGPoint, size: CGSize) {
        guard active == nil, !saving, errorMessage == nil, validSession else { return }
        updateViewport(size)
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
            // The canvas is transparent, so an empty spot shows the screen behind it.
            let background = UIColor(MulberryDesign.background)
            if let sampled = DoodleRenderer.color(at: point, strokes: strokes, elements: elements, stickerImages: stickerImages,
                                                  dry: model.bootstrap?.canvasStrokeRenderMode == "dry", size: size, background: background) {
                color = sampled; textColor = sampled; tool = .brush
            }
        case .text, .sticker: break
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

    /// A held finger opens the reactions instead of drawing, so the dot it left goes, without an undo step.
    func discardStroke() {
        guard let stroke = active, !ended else { return }
        active = nil; ended = false
        queue([.init(type: "FINISH_STROKE", strokeId: stroke.id), .init(type: "DELETE_STROKE", strokeId: stroke.id)], preview: true)
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

    /// Carries out the action the user chose for double-tap or squeeze in Settings → Apple Pencil.
    /// The location is where the Pencil hovers over the canvas, if it does.
    func handlePencil(_ action: UIPencilPreferredAction, at location: CGPoint?) {
        switch action {
        case .switchEraser:
            tool = tool == .eraser ? (previousTool == .eraser ? .brush : previousTool) : .eraser
        case .switchPrevious:
            tool = previousTool == tool ? .brush : previousTool
        case .showColorPalette: pencilRequest = .init(kind: .colours, location: location)
        case .showInkAttributes: pencilRequest = .init(kind: .inkAttributes, location: location)
        case .showContextualPalette: pencilRequest = .init(kind: .palette, location: location)
        case .ignore, .runSystemShortcut: break
        @unknown default: break
        }
    }

    // MARK: Text and stickers

    var canEditElements: Bool { validSession && errorMessage == nil }
    private var editorOpen: Bool { textSession != nil || stickerSession != nil }

    /// Selecting the tool opens a new centered text, as on Android; selecting
    /// it again returns to the brush.
    func toggleText() {
        if tool == .text { tool = .brush; return }
        end()
        tool = .text
        startText(at: .init(x: 0.5, y: 0.5))
    }

    func startText(at center: DrawingPoint) {
        guard canEditElements, !editorOpen else { return }
        present { $0.textSession = .init(element: .init(center: center, colorArgb: self.textColor), isNew: true) }
    }

    /// Blank text is never saved: a new one is discarded and an edited one deleted.
    func commitText(_ element: CanvasTextElement) {
        guard let session = textSession, session.id == element.id else { return }
        present { $0.textSession = nil }
        let blank = element.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        if session.isNew {
            guard !blank else { return }
            addElement(.text(element))
        } else if blank {
            deleteElement(element.id)
        } else {
            updateElement(.text(element))
        }
    }

    func deleteEditingText() {
        guard let session = textSession else { return }
        present { $0.textSession = nil }
        if !session.isNew { deleteElement(session.id) }
    }

    /// Like text, the sticker tool opens a new centered sticker straight away.
    func toggleSticker() {
        if tool == .sticker { tool = .brush; return }
        end()
        tool = .sticker
        stickers.load()
        startSticker(at: .init(x: 0.5, y: 0.5))
    }

    /// Opens the picker with the last used sticker, or the open pack's first.
    /// Before the catalog loads the editor waits for the user's choice.
    func startSticker(at center: DrawingPoint) {
        guard canEditElements, !editorOpen else { return }
        let sticker = stickers.defaultSticker ?? StickerReference(packKey: "", packVersion: 0, stickerId: "")
        present { $0.stickerSession = .init(element: .init(center: center, sticker: sticker), isNew: true) }
    }

    func commitSticker(_ element: CanvasStickerElement) {
        guard let session = stickerSession, session.id == element.id else { return }
        present { $0.stickerSession = nil }
        guard !element.sticker.stickerId.isEmpty else { return }
        stickers.use(element.sticker)
        if session.isNew { addElement(.sticker(element)) } else { updateElement(.sticker(element)) }
    }

    func deleteEditingSticker() {
        guard let session = stickerSession else { return }
        present { $0.stickerSession = nil }
        if !session.isNew { deleteElement(session.id) }
    }

    /// Tapping existing text or a sticker reopens its editor.
    func edit(_ element: CanvasElement) {
        guard canEditElements, !editorOpen else { return }
        switch element {
        case .text(let text): present { $0.textSession = .init(element: text, isNew: false) }
        case .sticker(let sticker):
            stickers.load()
            present { $0.stickerSession = .init(element: sticker, isNew: false) }
        }
    }

    private func addElement(_ element: CanvasElement) {
        guard canEditElements else { return }
        history.recordAdd(element)
        queue([.add(element)], preview: true)
    }

    /// Saves a moved, rotated, rescaled or edited element as one undo step.
    func updateElement(_ element: CanvasElement) {
        guard canEditElements, let before = elements.first(where: { $0.id == element.id }), before != element else { return }
        history.recordUpdate(from: before, to: element)
        queue([.update(element)], preview: true)
    }

    func deleteElement(_ id: String) {
        guard canEditElements, let existing = elements.first(where: { $0.id == id }) else { return }
        history.recordDelete(existing)
        queue([.delete(existing)], preview: true)
    }

    // The editors fade themselves in and out; the cover must not slide.
    private func present(_ change: (DoodleEditor) -> Void) {
        var transaction = Transaction(); transaction.disablesAnimations = true
        withTransaction(transaction) { change(self) }
    }

    func updateViewport(_ size: CGSize) {
        guard validSession, let user else { return }
        CanvasViewportStore.save(size, user: user, pair: pair)
    }

    private func saveTools() {
        CanvasToolStore.save(.init(tool: tool, color: color, width: width, textColor: textColor), user: user, pair: pair)
    }

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
        view.elementTool = editor.tool == .text || editor.tool == .sticker ? editor.tool : nil
        // The element being edited is shown only in its editor until Done.
        view.elementLayer.user = editor.user
        view.elementLayer.update(elements: editor.elements, hidden: editor.textSession?.id ?? editor.stickerSession?.id)
        view.accessibilityValue = editor.elements.compactMap(\.text?.text).joined(separator: ", ")
        view.setNeedsDisplay()
    }
}

/// Text and stickers are drawn in their own layer above the strokes, so a
/// stroke in progress does not redraw them, and a drag does not redraw strokes.
final class CanvasElementLayerView: UIView {
    let renderer = CanvasElementRenderer()
    var user: String?
    /// Called when sticker artwork arrives, for the eyedropper.
    var onImagesChanged: (([StickerReference: UIImage]) -> Void)?
    private(set) var elements: [CanvasElement] = []
    private var hiddenID: String?
    /// A gesture's live position, drawn in place of the saved element.
    private var preview: CanvasElement?
    private var loading: Set<StickerReference> = []

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false; backgroundColor = .clear; isUserInteractionEnabled = false
        contentMode = .redraw
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    var visible: [CanvasElement] {
        elements.compactMap { element in
            element.id == hiddenID ? nil : element.id == preview?.id ? preview : element
        }
    }

    func update(elements: [CanvasElement], hidden: String?) {
        guard elements != self.elements || hidden != hiddenID else { return }
        self.elements = elements; hiddenID = hidden
        renderer.retain(elements)
        loadArtwork()
        setNeedsDisplay()
    }

    func show(preview: CanvasElement?) {
        guard preview != self.preview else { return }
        self.preview = preview
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        renderer.draw(visible, in: context, canvasSize: bounds.size)
    }

    /// Full artwork, downsampled for the screen; placeholders draw meanwhile.
    private func loadArtwork() {
        guard let user else { return }
        let missing = Set(elements.compactMap(\.sticker?.sticker)).subtracting(renderer.stickerImages.keys).subtracting(loading)
        for sticker in missing {
            loading.insert(sticker)
            Task { [weak self] in
                let image = await StickerAssetStore.shared.image(sticker, variant: .full, user: user, maxPixelSize: 1024)
                guard let self else { return }
                self.loading.remove(sticker)
                guard let image, self.user == user else { return }
                self.renderer.stickerImages[sticker] = image
                self.onImagesChanged?(self.renderer.stickerImages)
                self.setNeedsDisplay()
            }
        }
    }
}

final class DoodleTouchView: UIView, UIGestureRecognizerDelegate, UIPencilInteractionDelegate {
    weak var editor: DoodleEditor? {
        didSet {
            editor?.updateViewport(bounds.size)
            elementLayer.onImagesChanged = { [weak editor] images in editor?.stickerImages = images }
        }
    }
    var strokes: [DrawingStroke] = []
    var dry = true
    let elementLayer = CanvasElementLayerView()
    /// Text or sticker: tapping places or edits that kind; dragging, pinching
    /// and twisting move it. Other tools draw.
    var elementTool: DoodleTool? {
        didSet {
            guard elementTool != oldValue else { return }
            elementGestures.forEach { $0.isEnabled = elementTool != nil }
            reactionPress.isEnabled = elementTool != nil && UIDevice.current.userInterfaceIdiom == .phone
            endTransform(commit: false)
        }
    }
    private weak var trackedTouch: UITouch?
    /// On iPhone, a finger held still on the canvas opens the reactions, as on Android.
    private var reactionHold: (start: CGPoint, timer: DispatchWorkItem)?
    /// The finger that opened the reactions, while it stays down.
    private weak var heldTouch: UITouch?
    /// Where an erase or a colour pick will start, once the finger moves or lifts rather than holds.
    private var deferredStart: CGPoint?
    /// With text or stickers, their own gestures own the touches, so a long press opens the reactions.
    private let reactionPress = UILongPressGestureRecognizer()
    // Eraser: a tap on text or a sticker deletes it; dragging off it erases strokes.
    private var elementToErase: (id: String, start: CGPoint)?
    private var elementGestures: [UIGestureRecognizer] = []
    private var transforming: Set<ObjectIdentifier> = []
    private var elementTransform: (base: CanvasElement, current: CanvasElement)?
    /// Where iOS expects the Pencil to be next, drawn ahead of the stroke and never saved.
    private var predicted: [CGPoint] = []
    /// The brush or eraser outline under a hovering Pencil.
    private let hoverLayer = CAShapeLayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false; backgroundColor = .clear
        elementLayer.frame = bounds
        elementLayer.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(elementLayer)
        hoverLayer.isHidden = true
        layer.addSublayer(hoverLayer)
        addInteraction(UIPencilInteraction(delegate: self))
        addGestureRecognizer(UIHoverGestureRecognizer(target: self, action: #selector(hover(_:))))
        let tap = UITapGestureRecognizer(target: self, action: #selector(elementTap(_:)))
        let pan = UIPanGestureRecognizer(target: self, action: #selector(elementPan(_:)))
        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(elementPinch(_:)))
        let rotation = UIRotationGestureRecognizer(target: self, action: #selector(elementRotation(_:)))
        elementGestures = [tap, pan, pinch, rotation]
        for gesture in elementGestures {
            gesture.delegate = self; gesture.isEnabled = false
            addGestureRecognizer(gesture)
        }
        reactionPress.addTarget(self, action: #selector(elementHold(_:)))
        reactionPress.minimumPressDuration = 0.5
        reactionPress.allowedTouchTypes = [NSNumber(value: UITouch.TouchType.direct.rawValue)]
        reactionPress.delegate = self; reactionPress.isEnabled = false
        addGestureRecognizer(reactionPress)
        // A quick tap still places or edits; only a held finger becomes the reactions.
        tap.require(toFail: reactionPress)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func layoutSubviews() {
        super.layoutSubviews()
        editor?.updateViewport(bounds.size)
        setNeedsDisplay(); elementLayer.setNeedsDisplay()
    }
    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        DoodleRenderer.draw(strokes, dry: dry, in: context, size: bounds.size)
        if !predicted.isEmpty, let stroke = editor?.activeStroke, let last = stroke.points.last {
            let ahead = DrawingStroke(id: stroke.id, colorArgb: stroke.colorArgb, width: stroke.width,
                                      createdAt: stroke.createdAt, points: [last] + predicted.map(normalized))
            DoodleRenderer.draw([ahead], dry: dry, in: context, size: bounds.size)
        }
    }
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        editor?.canvasTouched()
        guard elementTool == nil, trackedTouch == nil, let touch = touches.first else { return }
        // With "Only Draw with Apple Pencil" on, a finger or palm never marks the canvas.
        if UIPencilInteraction.prefersPencilOnlyDrawing, touch.type != .pencil,
           editor?.tool == .brush || editor?.tool == .eraser { return }
        trackedTouch = touch
        hoverLayer.isHidden = true
        let point = touch.location(in: self)
        // On iPhone, a finger held still opens the reactions, whatever the tool.
        let holdable = UIDevice.current.userInterfaceIdiom == .phone && touch.type == .direct
        if editor?.tool == .eraser, let hit = elementLayer.renderer.hit(elementLayer.visible, at: point, canvasSize: bounds.size) {
            elementToErase = (hit.id, point)
            if holdable { holdForReactions(at: point) }
            return
        }
        // Erasing and picking a colour act as soon as they start, so on iPhone they wait until the
        // finger moves or lifts, in case it is held for the reactions instead.
        if holdable, editor?.tool == .eraser || editor?.tool == .eyedropper {
            deferredStart = point
            holdForReactions(at: point)
            return
        }
        editor?.begin(at: point, size: bounds.size)
        if holdable, editor?.tool == .brush { holdForReactions(at: point) }
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let held = heldTouch, touches.contains(held) { editor?.reactionPointer.location = held.location(in: self) }
        guard let touch = trackedTouch, touches.contains(touch) else { return }
        let point = touch.location(in: self)
        if let hold = reactionHold, hypot(point.x - hold.start.x, point.y - hold.start.y) > 10 { cancelHold() }
        if let start = deferredStart {
            guard hypot(point.x - start.x, point.y - start.y) > 10 else { return }
            deferredStart = nil
            editor?.begin(at: start, size: bounds.size)
        }
        if let pending = elementToErase {
            guard hypot(point.x - pending.start.x, point.y - pending.start.y) > 10 else { return }
            elementToErase = nil
            editor?.begin(at: point, size: bounds.size)
        }
        editor?.move(points: (event?.coalescedTouches(for: touch) ?? [touch]).map { $0.location(in: self) }, size: bounds.size)
        predicted = editor?.tool == .brush ? (event?.predictedTouches(for: touch) ?? []).map { $0.location(in: self) } : []
        setNeedsDisplay()
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let held = heldTouch, touches.contains(held) { heldTouch = nil; editor?.reactionPointer.lift() }
        guard let touch = trackedTouch, touches.contains(touch) else { return }
        cancelHold()
        // A tap: the erase or colour pick that waited happens now.
        if let start = deferredStart { deferredStart = nil; editor?.begin(at: start, size: bounds.size) }
        if let pending = elementToErase {
            editor?.deleteElement(pending.id)
        } else {
            editor?.move(points: [touch.location(in: self)], size: bounds.size)
            editor?.end()
        }
        elementToErase = nil; trackedTouch = nil; predicted = []
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let held = heldTouch, touches.contains(held) { heldTouch = nil; editor?.reactionPointer.location = nil }
        cancelHold()
        if elementToErase == nil { editor?.end() }
        elementToErase = nil; deferredStart = nil; trackedTouch = nil; predicted = []
        setNeedsDisplay()
    }

    private func holdForReactions(at point: CGPoint) {
        let timer = DispatchWorkItem { [weak self] in
            guard let self, let touch = self.trackedTouch else { return }
            self.reactionHold = nil
            self.heldTouch = touch
            // The rest of this touch neither draws nor erases.
            self.editor?.discardStroke()
            self.trackedTouch = nil; self.predicted = []
            self.deferredStart = nil; self.elementToErase = nil
            self.setNeedsDisplay()
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            self.editor?.reactionRequest = ReactionRequest(location: point)
        }
        reactionHold = (point, timer)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5, execute: timer)
    }

    private func cancelHold() {
        reactionHold?.timer.cancel()
        reactionHold = nil
    }

    // MARK: Apple Pencil

    func pencilInteraction(_ interaction: UIPencilInteraction, didReceiveTap tap: UIPencilInteraction.Tap) {
        editor?.handlePencil(UIPencilInteraction.preferredTapAction, at: tap.hoverPose?.location)
    }

    func pencilInteraction(_ interaction: UIPencilInteraction, didReceiveSqueeze squeeze: UIPencilInteraction.Squeeze) {
        guard squeeze.phase == .ended else { return }
        editor?.handlePencil(UIPencilInteraction.preferredSqueezeAction, at: squeeze.hoverPose?.location)
    }

    /// A hovering Pencil shows the brush's size and colour, or the eraser's reach, at its tip.
    @objc private func hover(_ gesture: UIHoverGestureRecognizer) {
        let drawing = elementTool == nil && trackedTouch == nil && (editor?.tool == .brush || editor?.tool == .eraser)
        // A trackpad pointer hovers at zero height and has its own cursor.
        guard drawing, gesture.zOffset > 0, gesture.state == .began || gesture.state == .changed, let editor else {
            hoverLayer.isHidden = true
            return
        }
        let eraser = editor.tool == .eraser
        let diameter = eraser ? 20 : max(4, editor.width / 1080 * min(bounds.width, bounds.height))
        let point = gesture.location(in: self)
        CATransaction.begin(); CATransaction.setDisableActions(true)
        hoverLayer.path = UIBezierPath(ovalIn: CGRect(x: point.x - diameter / 2, y: point.y - diameter / 2,
                                                      width: diameter, height: diameter)).cgPath
        let color = UIColor(argb: editor.color)
        hoverLayer.fillColor = eraser ? UIColor.clear.cgColor : color.withAlphaComponent(0.35).cgColor
        hoverLayer.strokeColor = eraser ? UIColor.label.withAlphaComponent(0.7).cgColor : color.cgColor
        hoverLayer.lineWidth = eraser ? 1.5 : 1
        hoverLayer.isHidden = false
        CATransaction.commit()
    }

    // MARK: Text and sticker tools

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        elementGestures.contains(gestureRecognizer) && elementGestures.contains(other)
    }

    // UIView's own hook, which also answers as these recognizers' delegate.
    override func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        if gestureRecognizer === reactionPress { return editor?.textSession == nil && editor?.stickerSession == nil }
        guard elementGestures.contains(gestureRecognizer) else { return super.gestureRecognizerShouldBegin(gestureRecognizer) }
        guard elementTool != nil, editor?.textSession == nil, editor?.stickerSession == nil else { return false }
        // Moving needs a target; a tap on empty canvas still places a new element.
        guard !(gestureRecognizer is UITapGestureRecognizer) else { return true }
        return elementTransform != nil || hitElement(at: gestureRecognizer.location(in: self)) != nil
    }

    @objc private func elementHold(_ gesture: UILongPressGestureRecognizer) {
        guard let editor else { return }
        switch gesture.state {
        case .began:
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            editor.reactionRequest = ReactionRequest(location: gesture.location(in: self))
        case .changed: editor.reactionPointer.location = gesture.location(in: self)
        case .ended: editor.reactionPointer.lift()
        default: editor.reactionPointer.location = nil
        }
    }

    @objc private func elementTap(_ gesture: UITapGestureRecognizer) {
        guard gesture.state == .ended, let editor else { return }
        let point = gesture.location(in: self)
        if let hit = hitElement(at: point) { editor.edit(hit) }
        else if elementTool == .text { editor.startText(at: normalized(point)) }
        else if elementTool == .sticker { editor.startSticker(at: normalized(point)) }
    }

    @objc private func elementPan(_ gesture: UIPanGestureRecognizer) {
        updateTransform(gesture) { element in
            let translation = gesture.translation(in: self)
            gesture.setTranslation(.zero, in: self)
            element.center = normalized(CGPoint(x: element.center.x * bounds.width + translation.x,
                                                y: element.center.y * bounds.height + translation.y))
        }
    }

    @objc private func elementPinch(_ gesture: UIPinchGestureRecognizer) {
        updateTransform(gesture) { element in
            element.scale(by: gesture.scale)
            gesture.scale = 1
        }
    }

    @objc private func elementRotation(_ gesture: UIRotationGestureRecognizer) {
        updateTransform(gesture) { element in
            element.rotationRad += gesture.rotation
            gesture.rotation = 0
        }
    }

    /// Pan, pinch and rotation share one target and save once, when the last ends.
    private func updateTransform(_ gesture: UIGestureRecognizer, _ change: (inout CanvasElement) -> Void) {
        switch gesture.state {
        case .began:
            if elementTransform == nil {
                guard let hit = hitElement(at: gesture.location(in: self)) else { return }
                elementTransform = (hit, hit)
            }
            transforming.insert(ObjectIdentifier(gesture))
            fallthrough
        case .changed:
            guard var current = elementTransform?.current else { return }
            change(&current)
            elementTransform?.current = current
            elementLayer.show(preview: current)
        default:
            transforming.remove(ObjectIdentifier(gesture))
            if transforming.isEmpty { endTransform(commit: true) }
        }
    }

    private func endTransform(commit: Bool) {
        transforming.removeAll()
        guard let elementTransform else { return }
        self.elementTransform = nil
        if commit, elementTransform.current != elementTransform.base, let editor {
            editor.updateElement(elementTransform.current)
            // Swap in the saved position now so the old one never flashes.
            elementLayer.update(elements: editor.elements, hidden: editor.textSession?.id ?? editor.stickerSession?.id)
        }
        elementLayer.show(preview: nil)
    }

    /// Like Android, each tool picks up only its own kind of element.
    private func hitElement(at point: CGPoint) -> CanvasElement? {
        let tool = elementTool
        return elementLayer.renderer.hit(elementLayer.visible, at: point, canvasSize: bounds.size) { element in
            switch element {
            case .text: tool == .text
            case .sticker: tool == .sticker
            }
        }
    }

    private func normalized(_ point: CGPoint) -> DrawingPoint {
        .init(x: min(1, max(0, point.x / max(1, bounds.width))), y: min(1, max(0, point.y / max(1, bounds.height))))
    }
}

/// Where the finger that opened the reactions is, in the canvas's coordinates, while it is down.
@MainActor
final class ReactionPointer: ObservableObject {
    @Published var location: CGPoint?
    /// Counts the times the finger lifted after sliding, to choose the reaction under it.
    @Published private(set) var lifts = 0
    func lift() {
        if location != nil { lifts += 1 }
        location = nil
    }
}

struct ReactionRequest: Equatable {
    let location: CGPoint
    let id = UUID()
}

struct PencilRequest: Equatable {
    enum Kind { case colours, inkAttributes, palette }
    let kind: Kind
    let location: CGPoint?
    /// Every gesture is a new request, even when it asks for the same thing.
    let id = UUID()
}
