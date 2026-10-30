import Combine
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
            if tool != oldValue, editsInPlace { leaveElementTool() }
            saveTools()
        }
    }
    /// Apple Pencil's double-tap and squeeze move between these; text and stickers open editors.
    private static let drawingTools: Set<DoodleTool> = [.brush, .eraser, .eyedropper]
    /// The drawing tool before the current one, for Apple Pencil's "switch to previous tool".
    private(set) var previousTool: DoodleTool = .brush
    /// A double-tap or squeeze asking the canvas to show something.
    @Published var pencilRequest: PencilRequest?
    /// The colour under the eyedropper while it is held or dragged. It is chosen when the finger
    /// lifts, as on Android.
    @Published private(set) var eyedropperPreview: UInt32?
    /// A finger held still on the phone's canvas, asking for the reactions there.
    @Published var reactionRequest: ReactionRequest?
    /// The held finger after the reactions open, so sliding onto one and letting go sends it.
    let reactionPointer = ReactionPointer()
    /// Counts touches that start on the canvas, so the squeeze palette closes as drawing begins.
    @Published private(set) var canvasTouches = 0
    func canvasTouched() { canvasTouches += 1 }
    /// The stroke being drawn, which the canvas extends with predicted Pencil points.
    private(set) var activeStroke: DrawingStroke?
    @Published var color: UInt32 { didSet { saveTools() } }
    // Android widths are physical canvas pixels. A reference width of 1080
    // preserves the same relative brush weight on Retina and Android screens.
    @Published var width: Double { didSet { saveTools() } }
    @Published var textColor: UInt32 { didSet { saveTools() } }
    /// Whether the canvas has any strokes, for the blank canvas's prompt and the Clear button.
    @Published private(set) var hasStrokes = false
    @Published private(set) var elements: [CanvasElement] = []
    /// The strokes to draw. They reach the canvas through its display rather than SwiftUI, so a
    /// stroke's points do not re-render the screen.
    private(set) var scene = StrokeScene()
    weak var display: StrokeSceneDisplay? {
        didSet { display?.show(scene) }
    }
    @Published var textSession: CanvasTextSession?
    @Published var stickerSession: CanvasStickerSession?

    /// On iPad, text and stickers are edited in place, as on the Mac: a tap selects one in a frame
    /// with handles, a bar floating above it styles it, and text is typed on the canvas. The
    /// iPhone keeps its full-screen editors.
    let editsInPlace = UIDevice.current.userInterfaceIdiom == .pad
    /// The text or sticker selected in its frame.
    @Published private(set) var selectedElementID: String?
    /// A gesture's live position for the selection, so its frame and bar follow the fingers.
    @Published var elementPreview: CanvasElement?
    /// Text being typed on the canvas.
    @Published var typing: CanvasTextSession?
    /// The sticker picker, open beside the Sticker tool.
    @Published var stickerPanelOpen = false
    /// The selected sticker's Replace picker.
    @Published var replacingSticker = false
    /// Where a tap on empty canvas asked for a new sticker: the picker opens there.
    @Published var stickerPlacement: DrawingPoint?
    /// Where the on-screen keyboard starts, in the canvas's coordinates as laid out unlifted.
    @Published var keyboardTop: CGFloat?
    /// Lays out text for the selection frame, as the canvas draws it.
    let layouts = CanvasElementRenderer()
    let stickers: StickerCatalog
    /// Artwork the canvas has loaded, so the eyedropper samples stickers too.
    var stickerImages: [StickerReference: UIImage] = [:]
    @Published private(set) var history = DrawingHistory()
    @Published var errorMessage: String? {
        didSet { if validSession { model.canvasSaveError = errorMessage } }
    }
    private let model: MulberryModel
    private let pair: String?
    let user: String?
    private var emittedPoints = 0
    private var lastAppend = Date.distantPast
    /// The stroke being drawn shows here but is not saved yet: a finger not yet told from a palm.
    private var provisional = false
    /// Edits not yet handed to the model to save, in the order they were made.
    private var pending: [DrawingOperation] = []
    private var draining = false
    /// The drawing as saved, which this device's unsaved edits are laid over.
    private var saved = DrawingState()
    private var savedFeed: AnyCancellable?
    /// Edits other than drawing (erasing, undo and redo, clearing, text and stickers), shown until
    /// saved. Replaying one is idempotent, so it is safe whether or not the save already shows it.
    private var overlay: [DrawingOperation] = []
    /// Strokes finished here whose last operations are not saved yet, shown until they are. A new
    /// stroke starts at once rather than waiting for these to save.
    private var unsavedStrokes: [DrawingStroke] = []
    /// The scene without the stroke being drawn, which changes with each point.
    private var composed = StrokeScene()
    private var decodedElements: [JSONValue] = []

    init(model: MulberryModel) {
        self.model = model; pair = model.bootstrap?.pairSessionId; user = model.userId
        let tools = CanvasToolStore.load(user: user, pair: pair)
        tool = tools.tool; color = tools.color; width = tools.width; textColor = tools.textColor
        stickers = StickerCatalog(user: user)
        model.retryCanvasSave = { [weak self] in self?.retry() }
        // The model publishes on the main actor, as it changes.
        savedFeed = model.canvas.$state.sink { [weak self] state in
            MainActor.assumeIsolated { self?.receive(state) }
        }
    }

    /// Only the history decides these, so the buttons hold still while strokes are drawn and saved.
    /// A tap mid-stroke is ignored rather than shown as disabled.
    var canUndo: Bool { history.canUndo }
    var canRedo: Bool { history.canRedo }
    private var drawing: Bool { activeStroke != nil }

    /// Android keeps one colour for text and one for every other tool.
    var selectedColor: UInt32 {
        get { tool == .text ? textColor : color }
        set { if tool == .text { textColor = newValue } else { color = newValue } }
    }

    private func receive(_ drawing: DrawingState) {
        saved = drawing
        composeScene()
    }

    /// Starts a stroke, an erase or a colour pick. A provisional stroke shows at once but is saved only
    /// once `confirmStroke` says it was meant; until then `discardStroke` leaves no trace of it.
    func begin(at point: CGPoint, size: CGSize, provisional: Bool = false) {
        guard activeStroke == nil, errorMessage == nil, validSession else { return }
        updateViewport(size)
        switch tool {
        case .brush:
            let stroke = DrawingStroke(colorArgb: color, width: width / 1080, points: [normalized(point, size)])
            activeStroke = stroke; emittedPoints = 1; lastAppend = .now
            self.provisional = provisional
            if !provisional {
                do { queue([try .startStroke(stroke)]) } catch { errorMessage = error.localizedDescription }
            }
            publishScene()
        case .eraser: erase(at: point, size: size)
        case .eyedropper: eyedropperPreview = sample(at: point, size: size)
        case .text, .sticker: break
        }
    }

    func move(points: [CGPoint], size: CGSize) {
        guard validSession, errorMessage == nil else { return }
        if tool == .eyedropper {
            if let point = points.last, let sampled = sample(at: point, size: size) { eyedropperPreview = sampled }
            return
        }
        if tool == .eraser {
            for point in points { erase(at: point, size: size) }
            return
        }
        guard activeStroke != nil else { return }
        for point in points {
            let next = normalized(point, size)
            if next != activeStroke?.points.last { activeStroke?.points.append(next) }
        }
        publishScene()
        if Date().timeIntervalSince(lastAppend) >= 1 / Double(max(1, model.appendHz)) { appendPoints() }
    }

    /// Saves a provisional stroke, and from now on its points as they come.
    func confirmStroke() {
        guard provisional, let stroke = activeStroke else { return }
        provisional = false
        do { queue([try .startStroke(stroke)]) } catch { errorMessage = error.localizedDescription }
        appendPoints()
    }

    /// Finishing a stroke confirms it.
    func end() {
        if tool == .eyedropper, let picked = eyedropperPreview {
            eyedropperPreview = nil
            color = picked; textColor = picked; tool = .brush
            return
        }
        confirmStroke()
        appendPoints()
        guard var stroke = activeStroke else { return }
        stroke.finished = true
        activeStroke = nil
        unsavedStrokes.append(stroke)
        history.recordDraw(stroke)
        queue([.init(type: "FINISH_STROKE", strokeId: stroke.id)])
        composeScene()
    }

    /// A stroke not meant to be drawn, by a palm or a finger held to open the reactions, goes without
    /// an undo step.
    func discardStroke() {
        guard let stroke = activeStroke else { return }
        activeStroke = nil
        // Never saved, so nothing to take back.
        if provisional {
            provisional = false
            publishScene()
            return
        }
        queue([.init(type: "FINISH_STROKE", strokeId: stroke.id), .init(type: "DELETE_STROKE", strokeId: stroke.id)], preview: true)
    }

    func undo() {
        guard canUndo, !drawing else { return }
        do { queue(try history.undo(), preview: true) }
        catch { errorMessage = error.localizedDescription }
    }
    func redo() {
        guard canRedo, !drawing else { return }
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
        // On iPad, as on the Mac, the tool waits for a tap where the text should go.
        if !editsInPlace { startText(at: .init(x: 0.5, y: 0.5)) }
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

    /// Like text, the sticker tool opens a new centered sticker straight away. On iPad it opens
    /// the sticker picker beside the tool instead; choosing the tool again closes the picker, and
    /// then returns to the brush.
    func toggleSticker() {
        if editsInPlace {
            if tool == .sticker {
                if stickerPanelOpen { stickerPanelOpen = false; tool = .brush } else { stickers.load(); stickerPanelOpen = true }
                return
            }
            end()
            tool = .sticker
            stickers.load()
            stickerPanelOpen = true
            return
        }
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

    // MARK: Editing in place (iPad)

    /// The element shown only in its editor, or typed on the canvas, until it is done.
    var hiddenElementID: String? { textSession?.id ?? stickerSession?.id ?? typing?.id }

    var selectedElement: CanvasElement? {
        guard let selectedElementID else { return nil }
        return elements.first { $0.id == selectedElementID }
    }

    /// The selection as it looks right now: mid-gesture, or as saved.
    var shownSelection: CanvasElement? {
        guard let selected = selectedElement else { return nil }
        return elementPreview?.id == selected.id ? elementPreview : selected
    }

    func select(_ id: String?) {
        guard selectedElementID != id else { return }
        selectedElementID = id
        replacingSticker = false
    }

    /// A text or sticker tool only works on its own kind, so leaving it lets go of its selection.
    private func leaveElementTool() {
        finishTyping(keepSelection: false)
        stickerPanelOpen = false
        stickerPlacement = nil
        if let selected = selectedElement {
            let kept = (tool == .text && selected.text != nil) || (tool == .sticker && selected.sticker != nil)
            if !kept { select(nil) }
        }
    }

    /// Starts typing a new text at a point, with the caret in place.
    func beginNewText(at center: DrawingPoint) {
        guard canEditElements, typing == nil else { return }
        select(nil)
        typing = CanvasTextSession(element: CanvasTextElement(center: center, colorArgb: textColor), isNew: true)
    }

    /// Types into a text already on the canvas.
    func beginTyping(_ text: CanvasTextElement) {
        guard canEditElements else { return }
        if tool != .text { tool = .text }
        select(text.id)
        typing = CanvasTextSession(element: text, isNew: false)
    }

    /// Esc or hiding the keyboard keeps the text selected; tapping away lets go of it. Blank text
    /// is never saved: a new one is discarded and an edited one deleted.
    func finishTyping(keepSelection: Bool) {
        guard let session = typing else { return }
        typing = nil
        let element = session.element
        let blank = element.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        textColor = element.colorArgb
        if session.isNew {
            if !blank { addElement(.text(element)) }
        } else if blank {
            deleteElement(element.id)
        } else {
            updateElement(.text(element))
        }
        select(keepSelection && !blank ? element.id : nil)
    }

    /// While typing, style changes wait for the text to finish; on a selected text each is saved.
    func applyTextFormat(_ updated: CanvasTextElement) {
        if typing?.id == updated.id { typing?.element = updated } else { updateElement(.text(updated)) }
    }

    /// The bar's delete: the text being typed, or the selection.
    func deleteSelection() {
        if let session = typing {
            typing = nil
            if !session.isNew { deleteElement(session.id) }
        } else if let id = selectedElementID {
            deleteElement(id)
        }
        select(nil)
    }

    /// A new sticker: in the middle of the canvas from a tap in the picker, or where it was
    /// dropped. It arrives selected, with the Sticker tool chosen.
    func placeSticker(_ reference: StickerReference, at center: DrawingPoint? = nil) {
        guard canEditElements else { return }
        finishTyping(keepSelection: false)
        let sticker = CanvasStickerElement(center: center ?? DrawingPoint(x: 0.5, y: 0.5), sticker: reference)
        addElement(.sticker(sticker))
        stickers.use(reference)
        if tool != .sticker { tool = .sticker }
        stickerPanelOpen = false
        stickerPlacement = nil
        select(sticker.id)
    }

    /// New artwork in the same place, at the same size and angle.
    func replaceSticker(with reference: StickerReference) {
        guard case .sticker(var sticker) = selectedElement else { return }
        sticker.sticker = reference
        updateElement(.sticker(sticker))
        stickers.use(reference)
        replacingSticker = false
    }

    func straightenSelection() {
        guard var element = selectedElement else { return }
        element.rotationRad = 0
        updateElement(element)
    }

    /// A copy a little down and to the right, selected, as the Mac's duplicate.
    func duplicateSelection() {
        guard case .sticker(let sticker) = selectedElement else { return }
        let copy = CanvasStickerElement(
            center: DrawingPoint(x: min(1, sticker.center.x + 0.04), y: min(1, sticker.center.y + 0.025)),
            rotationRad: sticker.rotationRad, scale: sticker.scale, sticker: sticker.sticker
        )
        addElement(.sticker(copy))
        select(copy.id)
    }

    /// The arrow keys move the selection by a point, or ten with Shift.
    func nudgeSelection(dx: CGFloat, dy: CGFloat, canvasSize: CGSize) {
        guard var element = selectedElement, canvasSize.width > 0, canvasSize.height > 0 else { return }
        element.center = DrawingPoint(x: min(1, max(0, element.center.x + dx / canvasSize.width)),
                                      y: min(1, max(0, element.center.y + dy / canvasSize.height)))
        updateElement(element)
    }

    #if DEBUG
    /// For layout checks on iPad without a partner, as the reactions' launch arguments: launch with
    /// "inPlace=text" or "inPlace=sticker" to add one and select it, "inPlace=typing" to start
    /// typing, or "inPlace=picker" to open the sticker picker.
    func runPreviewArgument() async {
        guard editsInPlace, let value = ProcessInfo.processInfo.arguments.first(where: { $0.hasPrefix("inPlace=") })?.dropFirst(8) else { return }
        try? await Task.sleep(for: .milliseconds(800))
        switch value {
        case "text":
            tool = .text
            let text = CanvasTextElement(text: "Good morning, love", center: .init(x: 0.5, y: 0.42), rotationRad: -0.12, scale: 1.1,
                                         colorArgb: 0xFFB31329, font: .permanentMarker)
            addElement(.text(text))
            select(text.id)
        case "sticker":
            placeSticker(StickerReference(packKey: "spring-kittens", packVersion: 1, stickerId: "3"), at: .init(x: 0.5, y: 0.5))
        case "typing":
            tool = .text
            beginNewText(at: .init(x: 0.5, y: 0.4))
            typing?.element.text = "See you at 7"
        case "picker":
            toggleSticker()
        default: break
        }
    }
    #endif

    /// Where the selection, or the text being typed, sits on the canvas.
    func frameGeometry(of element: CanvasElement, canvasSize: CGSize) -> ElementFrameGeometry {
        switch element {
        case .text(let text):
            .text(text, layout: layouts.layout(for: text, canvasWidth: canvasSize.width), canvasSize: canvasSize)
        case .sticker(let sticker):
            .sticker(sticker, image: stickerImages[sticker.sticker], canvasSize: canvasSize)
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
        guard let stroke = activeStroke, !provisional else { return }
        do {
            while emittedPoints < stroke.points.count {
                let end = min(emittedPoints + 64, stroke.points.count)
                queue([try .appendPoints(Array(stroke.points[emittedPoints..<end]), to: stroke.id)])
                emittedPoints = end
            }
            lastAppend = .now
        } catch { errorMessage = error.localizedDescription }
    }

    private func erase(at point: CGPoint, size: CGSize) {
        guard let stroke = scene.settled.reversed().first(where: { Self.hits($0, at: point, size: size) }) else { return }
        history.recordErase(stroke)
        queue([.init(type: "DELETE_STROKE", strokeId: stroke.id)], preview: true)
    }

    /// Edits are saved in the order they were made, one batch at a time, while drawing carries on.
    private func queue(_ operations: [DrawingOperation], preview: Bool = false) {
        pending += operations
        // A stroke deleted or cleared away here must not be laid back over the drawing.
        let unsaved = unsavedStrokes.count
        for operation in operations {
            if operation.type == "DELETE_STROKE" { unsavedStrokes.removeAll { $0.id == operation.strokeId } }
            if operation.type == "CLEAR_CANVAS" { unsavedStrokes.removeAll() }
        }
        if preview { overlay += operations }
        if preview || unsavedStrokes.count != unsaved { composeScene() }
        Task { await drain() }
    }

    private func drain() async {
        guard !draining, errorMessage == nil, validSession else { return }
        draining = true
        defer { draining = false }
        while !pending.isEmpty {
            guard validSession else { return }
            let batch = pending
            do {
                try await model.enqueue(batch)
                pending.removeFirst(batch.count)
                settle(batch)
            } catch {
                errorMessage = "Your last edit could not be saved. \(error.localizedDescription)"
                return
            }
        }
    }

    /// The saved drawing now holds these edits, so they no longer need laying over it. A stroke is
    /// saved with its last operation, FINISH_STROKE, as edits are saved in order.
    private func settle(_ batch: [DrawingOperation]) {
        let savedIDs = Set(batch.map(\.id))
        overlay.removeAll { savedIDs.contains($0.id) }
        let finished = Set(batch.filter { $0.type == "FINISH_STROKE" }.compactMap(\.strokeId))
        unsavedStrokes.removeAll { finished.contains($0.id) }
        composeScene()
    }

    /// Lays this device's unsaved edits over the saved drawing. This runs when either changes; a
    /// point added to the stroke being drawn only publishes the scene again.
    private func composeScene() {
        var state = saved
        for operation in overlay { try? state.apply(operation) }
        // This device's own copy of each of its unsaved strokes replaces any partial one saved so far.
        // A finished stroke goes on top, as finishing it does in the saved drawing.
        let local = Set(unsavedStrokes.map(\.id) + [activeStroke?.id].compactMap { $0 })
        let strokes = state.strokes.filter { !local.contains($0.id) } + unsavedStrokes
        composed = StrokeScene(settled: strokes.filter { $0.finished == true }, inProgress: strokes.filter { $0.finished != true })
        // Composing runs for every saved batch of points; decode elements only when they change.
        if state.elements != decodedElements {
            decodedElements = state.elements
            elements = state.canvasElements
        }
        publishScene()
    }

    private func publishScene() {
        scene = StrokeScene(settled: composed.settled, inProgress: composed.inProgress + (activeStroke.map { [$0] } ?? []))
        let any = !scene.settled.isEmpty || !scene.inProgress.isEmpty
        if hasStrokes != any { hasStrokes = any }
        display?.show(scene)
    }

    /// Lets go of the eyedropper without choosing, as when a second finger lands.
    func cancelEyedropper() { eyedropperPreview = nil }

    /// The colour at a point. The canvas is transparent, so an empty spot gives the screen behind it.
    private func sample(at point: CGPoint, size: CGSize) -> UInt32? {
        DoodleRenderer.color(at: point, strokes: scene.settled + scene.inProgress, elements: elements, stickerImages: stickerImages,
                             dry: model.bootstrap?.canvasStrokeRenderMode == "dry", size: size, background: UIColor(MulberryDesign.background))
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
        view.editor = editor; view.strokeCanvas.dry = dry
        view.elementTool = editor.tool == .text || editor.tool == .sticker ? editor.tool : nil
        // The element being edited is shown only in its editor until Done.
        view.elementLayer.user = editor.user
        view.elementLayer.update(elements: editor.elements, hidden: editor.hiddenElementID)
        view.accessibilityValue = editor.elements.compactMap(\.text?.text).joined(separator: ", ")
        view.updateSelection()
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
    /// Runs while stickers fade in or a slow download's placeholder pulses.
    private var displayLink: CADisplayLink?

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false; backgroundColor = .clear; isUserInteractionEnabled = false
        contentMode = .redraw
        renderer.stickerTimeline = StickerArtworkTimeline()
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

    /// Full artwork, downsampled for the screen. Artwork already in memory draws in the first
    /// frame; the rest fades in as it decodes, with a placeholder only if a download runs long.
    private func loadArtwork() {
        guard let user else { return }
        let missing = Set(elements.compactMap(\.sticker?.sticker)).subtracting(renderer.stickerImages.keys).subtracting(loading)
        var found = false
        for sticker in missing {
            if let image = StickerAssetStore.shared.cachedImage(sticker, variant: .full, user: user, maxPixelSize: StickerAssetStore.canvasPixels) {
                renderer.stickerImages[sticker] = image
                found = true
                continue
            }
            loading.insert(sticker)
            renderer.stickerTimeline?.request(sticker)
            Task { [weak self] in
                let image = await StickerAssetStore.shared.image(sticker, variant: .full, user: user, maxPixelSize: StickerAssetStore.canvasPixels)
                guard let self else { return }
                self.loading.remove(sticker)
                guard let image, self.user == user else {
                    self.renderer.stickerTimeline?.fail(sticker)
                    self.setNeedsDisplay()
                    return
                }
                self.renderer.stickerImages[sticker] = image
                self.renderer.stickerTimeline?.arrive(sticker)
                self.onImagesChanged?(self.renderer.stickerImages)
                self.animate()
            }
        }
        if found { onImagesChanged?(renderer.stickerImages) }
        if !loading.isEmpty { animate() }
    }

    private func animate() {
        renderer.stickerTimeline?.reduceMotion = UIAccessibility.isReduceMotionEnabled
        setNeedsDisplay()
        guard displayLink == nil else { return }
        let link = CADisplayLink(target: self, selector: #selector(step))
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    @objc private func step() {
        setNeedsDisplay()
        guard renderer.stickerTimeline?.isAnimating() != true else { return }
        renderer.stickerTimeline?.settle()
        displayLink?.invalidate()
        displayLink = nil
    }

    override func removeFromSuperview() {
        displayLink?.invalidate()
        displayLink = nil
        super.removeFromSuperview()
    }
}

final class DoodleTouchView: UIView, UIGestureRecognizerDelegate, UIPencilInteractionDelegate, UIPointerInteractionDelegate {
    weak var editor: DoodleEditor? {
        didSet {
            guard editor !== oldValue else { return }
            editor?.updateViewport(bounds.size)
            editor?.display = strokeCanvas
            elementLayer.onImagesChanged = { [weak editor] images in editor?.stickerImages = images }
        }
    }
    let strokeCanvas = StrokeCanvasView()
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
    /// The one touch that draws, erases or picks: the Pencil whenever it is down.
    private weak var trackedTouch: UITouch?
    /// What palm rejection knows of the finger being followed. Nil for the Pencil, and for tools
    /// that do not mark the canvas.
    private var finger: PalmRejection.Finger?
    private var palms = PalmRejection()
    /// The Pencil's touches, turned into what it really drew before the editor hears of them.
    private var pencil = PencilInput()
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
    /// The element under the fingers, and how far two fingers have twisted it, before settling.
    private var elementTransform: (base: CanvasElement, current: CanvasElement, twist: Double)?
    /// On iPad, a drag that began on the selection's corner handle or just beyond it.
    private var frameDrag: (control: FrameControl, base: CanvasElement, start: CGPoint)?
    /// The selection's frame and its corner handles, as on the Mac.
    private let selectionLayer = CAShapeLayer()
    private let handleLayer = CAShapeLayer()
    /// Whether the rotation sits on a quarter turn, so the fingers feel it settle there.
    private var squared = false
    private let settleFeedback = UISelectionFeedbackGenerator()
    /// The brush or eraser outline under a hovering Pencil.
    private let hoverLayer = CAShapeLayer()
    /// The eyedropper's colour, held above the finger so the finger does not hide it.
    private let loupeLayer = CAShapeLayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false; backgroundColor = .clear
        // Every contact, not only the first: the Pencil must reach the canvas past a resting palm,
        // and palm rejection counts the hand's other contacts.
        isMultipleTouchEnabled = true
        strokeCanvas.frame = bounds
        strokeCanvas.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(strokeCanvas)
        elementLayer.frame = bounds
        elementLayer.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(elementLayer)
        hoverLayer.isHidden = true
        layer.addSublayer(hoverLayer)
        loupeLayer.isHidden = true
        loupeLayer.path = UIBezierPath(ovalIn: CGRect(x: -22, y: -22, width: 44, height: 44)).cgPath
        loupeLayer.strokeColor = UIColor.white.cgColor
        loupeLayer.lineWidth = 3
        loupeLayer.shadowColor = UIColor.black.cgColor
        loupeLayer.shadowOpacity = 0.3
        loupeLayer.shadowRadius = 6
        loupeLayer.shadowOffset = CGSize(width: 0, height: 3)
        layer.addSublayer(loupeLayer)
        for shape in [selectionLayer, handleLayer] {
            shape.isHidden = true
            shape.strokeColor = UIColor(MulberryDesign.accent).cgColor
            shape.lineWidth = 1.25
            layer.addSublayer(shape)
        }
        selectionLayer.fillColor = UIColor.clear.cgColor
        handleLayer.fillColor = UIColor.white.cgColor
        if UIDevice.current.userInterfaceIdiom == .pad {
            addInteraction(UIPointerInteraction(delegate: self))
            NotificationCenter.default.addObserver(self, selector: #selector(keyboardChanged(_:)),
                                                   name: UIResponder.keyboardWillChangeFrameNotification, object: nil)
            NotificationCenter.default.addObserver(self, selector: #selector(keyboardChanged(_:)),
                                                   name: UIResponder.keyboardWillHideNotification, object: nil)
        }
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
        elementLayer.setNeedsDisplay()
        updateSelection()
    }
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        #if DEBUG
        InputTrace.shared.record("began", touches, event, in: self)
        #endif
        editor?.canvasTouched()
        notePencil(in: touches)
        guard elementTool == nil, heldTouch == nil,
              let touch = touches.first(where: { $0.type == .pencil }) ?? touches.first else { return }
        if let tracked = trackedTouch {
            guard touch.type == .pencil, tracked.type != .pencil else {
                // Another contact beside a finger not yet proved shows a resting hand.
                if tracked.type != .pencil { _ = review(tracked, in: event) }
                return
            }
            // The Pencil takes over from the hand that landed first; what the hand drew goes.
            abandonTrackedTouch()
        }
        if touch.type == .pencil {
            trackedTouch = touch
            hoverLayer.isHidden = true
            pencil.begin(ObjectIdentifier(touch), with: samples(of: touch, in: event))
            followPencil(predicted: [])
            return
        }
        if editor?.tool == .brush || editor?.tool == .eraser {
            let landing = PalmRejection.Finger(
                landed: touch.timestamp,
                radius: PalmRejection.radius(majorRadius: touch.majorRadius, tolerance: touch.majorRadiusTolerance)
            )
            guard verdict(on: landing, touch: touch, in: event) != .palm else { return }
            finger = landing
        }
        trackedTouch = touch
        hoverLayer.isHidden = true
        let point = touch.location(in: self)
        // On iPhone, a finger held still opens the reactions, whatever the tool.
        let holdable = UIDevice.current.userInterfaceIdiom == .phone && touch.type == .direct
        if beginErasingElement(at: point) {
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
        // A finger's stroke is saved only once it has proved not to be a palm.
        editor?.begin(at: point, size: bounds.size, provisional: finger != nil)
        showLoupe(at: point)
        if holdable, editor?.tool == .brush { holdForReactions(at: point) }
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        #if DEBUG
        InputTrace.shared.record("moved", touches, event, in: self)
        #endif
        if let held = heldTouch, touches.contains(held) { editor?.reactionPointer.location = held.location(in: self) }
        notePencil(in: touches)
        guard let touch = trackedTouch, touches.contains(touch) else { return }
        if touch.type == .pencil {
            pencil.add(samples(of: touch, in: event), to: ObjectIdentifier(touch))
            followPencil(predicted: event?.predictedTouches(for: touch) ?? [])
            return
        }
        guard review(touch, in: event) else { return }
        let point = touch.location(in: self)
        if let hold = reactionHold, hypot(point.x - hold.start.x, point.y - hold.start.y) > 10 { cancelHold() }
        if let start = deferredStart {
            guard hypot(point.x - start.x, point.y - start.y) > 10 else { return }
            deferredStart = nil
            editor?.begin(at: start, size: bounds.size)
        }
        continueMarking((event?.coalescedTouches(for: touch) ?? [touch]).map { $0.location(in: self) })
        strokeCanvas.prediction = prediction(continuing: (event?.predictedTouches(for: touch) ?? []).map { $0.location(in: self) })
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        #if DEBUG
        InputTrace.shared.record("ended", touches, event, in: self)
        #endif
        if let held = heldTouch, touches.contains(held) { heldTouch = nil; editor?.reactionPointer.lift() }
        notePencil(in: touches)
        guard let touch = trackedTouch, touches.contains(touch) else { return }
        if touch.type == .pencil { return liftPencil(touch, in: event) }
        guard review(touch, in: event) else { return }
        cancelHold()
        // A tap: the erase or colour pick that waited happens now.
        if let start = deferredStart { deferredStart = nil; editor?.begin(at: start, size: bounds.size) }
        if elementToErase == nil { editor?.move(points: [touch.location(in: self)], size: bounds.size) }
        // A finger lifted before it was proved, and never shown to be a palm, was a tap.
        endMarking()
        trackedTouch = nil; finger = nil; strokeCanvas.prediction = nil
    }
    /// A cancelled Pencil stroke is kept, as the Pencil meant it. A cancelled finger was taken by the
    /// system, such as an edge swipe, or rejected as a palm, so what it drew goes.
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        #if DEBUG
        InputTrace.shared.record("cancelled", touches, event, in: self)
        #endif
        if let held = heldTouch, touches.contains(held) { heldTouch = nil; editor?.reactionPointer.location = nil }
        notePencil(in: touches)
        // Only the touch that draws ends the stroke: a palm kept from drawing is cancelled too,
        // while the Pencil carries on.
        guard let touch = trackedTouch, touches.contains(touch) else { return }
        if touch.type == .pencil { return liftPencil(touch, in: event) }
        abandonTrackedTouch()
    }
    /// The Pencil's estimated forces, measured.
    override func touchesEstimatedPropertiesUpdated(_ touches: Set<UITouch>) {
        #if DEBUG
        InputTrace.shared.record("estimatedPropertiesUpdated", touches, nil, in: self)
        #endif
        for touch in touches where touch.type == .pencil {
            guard let index = touch.estimationUpdateIndex?.intValue else { continue }
            pencil.measure(index, force: touch.force, final: !touch.estimatedPropertiesExpectingUpdates.contains(.force))
        }
        followPencil(predicted: [])
    }

    // MARK: Apple Pencil input

    /// The samples a touch brought with this event, oldest first.
    private func samples(of touch: UITouch, in event: UIEvent?) -> [PencilInput.Sample] {
        (event?.coalescedTouches(for: touch) ?? [touch]).map { sample in
            PencilInput.Sample(
                index: sample.estimationUpdateIndex?.intValue,
                time: sample.timestamp,
                point: sample.location(in: self),
                force: sample.force,
                forceMeasured: !sample.estimatedPropertiesExpectingUpdates.contains(.force)
            )
        }
    }

    private func liftPencil(_ touch: UITouch, in event: UIEvent?) {
        pencil.add(samples(of: touch, in: event), to: ObjectIdentifier(touch))
        pencil.lift(ObjectIdentifier(touch))
        trackedTouch = nil
        followPencil(predicted: [])
        // Should a measurement never come, the samples still waiting are judged on their estimates.
        DispatchQueue.main.asyncAfter(deadline: .now() + PencilInput.patience) { [weak self] in
            self?.followPencil(predicted: [])
        }
    }

    /// Carries out what the Pencil has been judged to draw, and shows the samples still being
    /// measured, then where iOS expects the Pencil next, ahead of the stroke.
    private func followPencil(predicted: [UITouch]) {
        pencil.expire(at: ProcessInfo.processInfo.systemUptime)
        for command in pencil.commands() {
            switch command {
            case let .begin(point):
                if !beginErasingElement(at: point) {
                    editor?.begin(at: point, size: bounds.size)
                    showLoupe(at: point)
                }
            case let .move(points):
                continueMarking(points)
            case .end:
                endMarking()
            }
        }
        strokeCanvas.prediction = pencil.preview.flatMap { prediction(continuing: $0 + predicted.map { $0.location(in: self) }) }
    }

    // MARK: Marking

    /// With the eraser, a press on text or a sticker deletes it on release, unless it moves off.
    private func beginErasingElement(at point: CGPoint) -> Bool {
        guard editor?.tool == .eraser, let hit = elementLayer.renderer.hit(elementLayer.visible, at: point, canvasSize: bounds.size) else {
            return false
        }
        elementToErase = (hit.id, point)
        return true
    }

    private func continueMarking(_ points: [CGPoint]) {
        guard let point = points.last else { return }
        if let pending = elementToErase {
            guard hypot(point.x - pending.start.x, point.y - pending.start.y) > 10 else { return }
            elementToErase = nil
            editor?.begin(at: point, size: bounds.size)
        }
        editor?.move(points: points, size: bounds.size)
        showLoupe(at: point)
    }

    private func endMarking() {
        if let pending = elementToErase { editor?.deleteElement(pending.id) } else { editor?.end() }
        elementToErase = nil
        showLoupe(at: nil)
    }

    /// The stroke being drawn carried on through `points`: drawn ahead of it, never saved.
    private func prediction(continuing points: [CGPoint]) -> DrawingStroke? {
        guard editor?.tool == .brush, !points.isEmpty, let stroke = editor?.activeStroke, let last = stroke.points.last else { return nil }
        return DrawingStroke(id: stroke.id, colorArgb: stroke.colorArgb, width: stroke.width, createdAt: stroke.createdAt,
                             points: [last] + points.map { normalized($0) })
    }

    // MARK: Palm rejection

    private func notePencil(in touches: Set<UITouch>) {
        for touch in touches where touch.type == .pencil { palms.notePencil(at: touch.timestamp) }
    }

    /// Fingers and palms on the canvas besides `touch`.
    private func otherContacts(than touch: UITouch, in event: UIEvent?) -> Int {
        (event?.allTouches ?? []).count { other in
            other !== touch && other.view === self && other.type == .direct && other.phase != .ended && other.phase != .cancelled
        }
    }

    private func verdict(on finger: PalmRejection.Finger, touch: UITouch, in event: UIEvent?) -> PalmRejection.Verdict {
        palms.classify(finger, others: otherContacts(than: touch, in: event),
                       pencilOnly: UIPencilInteraction.prefersPencilOnlyDrawing, at: touch.timestamp)
    }

    /// Reads the followed finger again: once proved, its stroke is saved; shown to be a palm, it is
    /// let go and what it drew goes. False once it has been let go.
    private func review(_ touch: UITouch, in event: UIEvent?) -> Bool {
        guard var finger else { return true }
        finger.measure(majorRadius: touch.majorRadius, tolerance: touch.majorRadiusTolerance)
        switch verdict(on: finger, touch: touch, in: event) {
        case .palm:
            abandonTrackedTouch()
            return false
        case .finger where !finger.confirmed:
            finger.confirmed = true
            editor?.confirmStroke()
        case .finger, .pending:
            break
        }
        self.finger = finger
        return true
    }

    /// Lets go of the touch being followed without finishing what it started: its stroke goes,
    /// without an undo step.
    private func abandonTrackedTouch() {
        cancelHold()
        editor?.cancelEyedropper(); showLoupe(at: nil)
        editor?.discardStroke()
        elementToErase = nil; deferredStart = nil; trackedTouch = nil; finger = nil; strokeCanvas.prediction = nil
    }

    private func holdForReactions(at point: CGPoint) {
        let timer = DispatchWorkItem { [weak self] in
            guard let self, let touch = self.trackedTouch else { return }
            self.reactionHold = nil
            self.heldTouch = touch
            // The rest of this touch neither draws nor erases.
            self.editor?.discardStroke()
            self.trackedTouch = nil; self.finger = nil; self.strokeCanvas.prediction = nil
            self.deferredStart = nil; self.elementToErase = nil
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            self.editor?.reactionRequest = ReactionRequest(location: point)
        }
        reactionHold = (point, timer)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5, execute: timer)
    }

    /// Shows the eyedropper's colour above a point while it is picking, or hides it.
    private func showLoupe(at point: CGPoint?) {
        guard let point, editor?.tool == .eyedropper, let color = editor?.eyedropperPreview else {
            loupeLayer.isHidden = true
            return
        }
        CATransaction.begin(); CATransaction.setDisableActions(true)
        loupeLayer.position = CGPoint(x: point.x, y: point.y - 64)
        loupeLayer.fillColor = UIColor(argb: color).cgColor
        loupeLayer.isHidden = false
        CATransaction.commit()
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
        // A hovering Pencil is in use.
        if gesture.zOffset > 0, gesture.state == .began || gesture.state == .changed {
            palms.notePencil(at: ProcessInfo.processInfo.systemUptime)
        }
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
        // While typing, a tap away finishes the text; nothing moves.
        guard editor?.typing == nil else { return false }
        let location = gestureRecognizer.location(in: self)
        if gestureRecognizer is UIPanGestureRecognizer, frameControl(at: location, pointer: false) != nil { return true }
        return elementTransform != nil || hitElement(at: location) != nil
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
        if editor.editsInPlace { return tapInPlace(at: point, editor: editor) }
        if let hit = hitElement(at: point) { editor.edit(hit) }
        else if elementTool == .text { editor.startText(at: normalized(point)) }
        else if elementTool == .sticker { editor.startSticker(at: normalized(point)) }
    }

    @objc private func elementPan(_ gesture: UIPanGestureRecognizer) {
        if gesture.state == .began, frameDrag == nil, let control = frameControl(at: gesture.location(in: self), pointer: false),
           let selected = editor?.selectedElement {
            frameDrag = (control, selected, gesture.location(in: self))
            squared = ElementRotation.isSquare(selected.rotationRad)
        }
        if frameDrag != nil { return dragFrame(gesture) }
        updateTransform(gesture) { element in
            let translation = gesture.translation(in: self)
            gesture.setTranslation(.zero, in: self)
            element.center = normalized(CGPoint(x: element.center.x * bounds.width + translation.x,
                                                y: element.center.y * bounds.height + translation.y))
        }
    }

    @objc private func elementPinch(_ gesture: UIPinchGestureRecognizer) {
        let factor = gesture.scale
        gesture.scale = 1
        updateTransform(gesture) { element in element.scale(by: factor) }
    }

    @objc private func elementRotation(_ gesture: UIRotationGestureRecognizer) {
        let turn = gesture.rotation
        gesture.rotation = 0
        updateTransform(gesture) { element in
            guard editor?.editsInPlace == true, let transform = elementTransform else { element.rotationRad += turn; return }
            // On iPad the twist settles level, or on a quarter turn, as the Mac's rotation does.
            let twist = transform.twist + turn
            elementTransform?.twist = twist
            element.rotationRad = settle(transform.base.rotationRad + twist)
        }
    }

    /// Pan, pinch and rotation share one target and save once, when the last ends.
    private func updateTransform(_ gesture: UIGestureRecognizer, _ change: (inout CanvasElement) -> Void) {
        // A corner drag owns the selection until it ends; a second finger does not join it.
        guard frameDrag == nil else { return }
        switch gesture.state {
        case .began:
            if elementTransform == nil {
                guard frameDrag == nil, let hit = hitElement(at: gesture.location(in: self)) else { return }
                elementTransform = (hit, hit, 0)
                if editor?.editsInPlace == true {
                    editor?.select(hit.id)
                    squared = ElementRotation.isSquare(hit.rotationRad)
                }
            }
            transforming.insert(ObjectIdentifier(gesture))
            fallthrough
        case .changed:
            guard var current = elementTransform?.current else { return }
            change(&current)
            elementTransform?.current = current
            elementLayer.show(preview: current)
            if editor?.editsInPlace == true { editor?.elementPreview = current; updateSelection() }
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
            elementLayer.update(elements: editor.elements, hidden: editor.hiddenElementID)
        }
        elementLayer.show(preview: nil)
        if editor?.elementPreview != nil { editor?.elementPreview = nil }
        updateSelection()
    }

    // MARK: Editing in place (iPad)

    /// A tap with the Text or Sticker tool, as on the Mac. While typing, it finishes the text. On a
    /// text or sticker it selects it, and on the selected text it starts typing into it. On empty
    /// canvas the Text tool starts a new text there; the Sticker tool lets go of the selection, or
    /// with nothing selected, opens the sticker picker there to place a new one.
    private func tapInPlace(at point: CGPoint, editor: DoodleEditor) {
        if editor.typing != nil { return editor.finishTyping(keepSelection: false) }
        if let hit = hitElement(at: point) {
            if hit.id == editor.selectedElementID, case .text(let text) = hit { editor.beginTyping(text) } else { editor.select(hit.id) }
            return
        }
        if elementTool == .text { return editor.beginNewText(at: normalized(point)) }
        if editor.selectedElementID != nil || editor.stickerPanelOpen || editor.stickerPlacement != nil {
            editor.select(nil)
            editor.stickerPanelOpen = false
            editor.stickerPlacement = nil
        } else {
            editor.stickers.load()
            editor.stickerPlacement = normalized(point)
        }
    }

    /// A control of the selection's frame: a corner handle resizes; the handle above the frame
    /// rotates, as does the area just beyond a corner under a trackpad pointer, as on the Mac.
    enum FrameControl: Equatable {
        case resize(corner: Int)
        case rotate(corner: Int)
        case knob
    }

    /// How far above the frame's top edge the rotate handle sits.
    private static let knobReach: CGFloat = 30

    /// The selection's geometry while its frame shows: not while typing, and only with its tool.
    private var selectionGeometry: ElementFrameGeometry? {
        guard let editor, editor.editsInPlace, editor.typing == nil, elementTool != nil,
              let shown = editor.shownSelection else { return nil }
        return editor.frameGeometry(of: shown, canvasSize: bounds.size)
    }

    /// Which control of the selection's frame is under a point. Fingers get handles and rotation
    /// areas large enough to land on; a trackpad pointer gets the Mac's tighter ones.
    private func frameControl(at point: CGPoint, pointer: Bool) -> FrameControl? {
        guard let geometry = selectionGeometry else { return nil }
        let local = geometry.local(point)
        let body = geometry.body
        if hypot(local.x - body.midX, local.y - (body.minY - Self.knobReach)) <= (pointer ? 10 : 22) { return .knob }
        // Handles stay smaller than a quarter of a small element, so its middle still moves it.
        let handle: CGFloat = pointer ? 9 : min(22, max(10, min(body.width, body.height) / 4))
        let reach: CGFloat = pointer ? 26 : 46
        let corners = [CGPoint(x: body.minX, y: body.minY), CGPoint(x: body.maxX, y: body.minY),
                       CGPoint(x: body.maxX, y: body.maxY), CGPoint(x: body.minX, y: body.maxY)]
        for (index, corner) in corners.enumerated() {
            let distance = hypot(local.x - corner.x, local.y - corner.y)
            if distance <= handle { return .resize(corner: index) }
            // Beyond the corner, away from the body, on both axes.
            let outX = (local.x - corner.x) * (corner.x < body.midX ? -1 : 1)
            let outY = (local.y - corner.y) * (corner.y < body.midY ? -1 : 1)
            // Fingers rotate from the handle above; the corners' rotation areas are for a pointer.
            if pointer, distance <= reach, outX > -4, outY > -4, !body.contains(local) { return .rotate(corner: index) }
        }
        return nil
    }

    /// Resizes from a corner handle, or rotates from just beyond one, about the element's centre.
    private func dragFrame(_ gesture: UIPanGestureRecognizer) {
        guard let drag = frameDrag else { return }
        switch gesture.state {
        case .began, .changed:
            let geometry = editor?.frameGeometry(of: drag.base, canvasSize: bounds.size)
            let center = geometry?.center ?? .zero
            let location = gesture.location(in: self)
            var element = drag.base
            switch drag.control {
            case .resize:
                let before = hypot(drag.start.x - center.x, drag.start.y - center.y)
                guard before > 1 else { return }
                element.scale(by: hypot(location.x - center.x, location.y - center.y) / before)
            case .rotate, .knob:
                let turned = atan2(location.y - center.y, location.x - center.x) - atan2(drag.start.y - center.y, drag.start.x - center.x)
                var angle = drag.base.rotationRad + turned
                // Shift on a hardware keyboard snaps to 15°, as on the Mac.
                if gesture.modifierFlags.contains(.shift) {
                    angle = (angle / (Double.pi / 12)).rounded() * (Double.pi / 12)
                    noteSquare(angle)
                } else {
                    angle = settle(angle)
                }
                element.rotationRad = angle
            }
            elementTransform = (drag.base, element, 0)
            elementLayer.show(preview: element)
            editor?.elementPreview = element
            updateSelection()
        default:
            frameDrag = nil
            endTransform(commit: gesture.state == .ended)
        }
    }

    /// Settles an angle level, or on a quarter turn, within 3°, with a tick as it lands.
    private func settle(_ angle: Double) -> Double {
        let settled = ElementRotation.quarterTurn(near: angle) ?? angle
        noteSquare(settled)
        return settled
    }

    private func noteSquare(_ angle: Double) {
        let onSquare = ElementRotation.isSquare(angle)
        guard onSquare != squared else { return }
        squared = onSquare
        if onSquare { settleFeedback.selectionChanged() }
    }

    /// Draws the selection's frame and corner handles over the element, or hides them.
    func updateSelection() {
        guard let geometry = selectionGeometry else {
            selectionLayer.isHidden = true
            handleLayer.isHidden = true
            return
        }
        let corners = geometry.corners
        let frame = UIBezierPath()
        frame.move(to: corners[0])
        for corner in corners.dropFirst() { frame.addLine(to: corner) }
        frame.close()
        // The rotate handle: a stem up from the top edge's middle to a round knob.
        let edge = geometry.screenPoint(CGPoint(x: geometry.body.midX, y: geometry.body.minY))
        let knob = geometry.screenPoint(CGPoint(x: geometry.body.midX, y: geometry.body.minY - Self.knobReach))
        let stemEnd = geometry.screenPoint(CGPoint(x: geometry.body.midX, y: geometry.body.minY - Self.knobReach + 6))
        frame.move(to: edge)
        frame.addLine(to: stemEnd)
        let handles = UIBezierPath(ovalIn: CGRect(x: knob.x - 6, y: knob.y - 6, width: 12, height: 12))
        let side: CGFloat = 9
        for corner in corners {
            let square = UIBezierPath(rect: CGRect(x: -side / 2, y: -side / 2, width: side, height: side))
            square.apply(CGAffineTransform(rotationAngle: geometry.angle))
            square.apply(CGAffineTransform(translationX: corner.x, y: corner.y))
            handles.append(square)
        }
        CATransaction.begin(); CATransaction.setDisableActions(true)
        selectionLayer.path = frame.cgPath
        handleLayer.path = handles.cgPath
        selectionLayer.isHidden = false
        handleLayer.isHidden = false
        CATransaction.commit()
    }

    // MARK: Pointer

    /// A trackpad pointer turns into a resize arrow over a corner handle, and into the rotate
    /// arrow just beyond one, as on the Mac. Elsewhere it keeps its own shape.
    func pointerInteraction(_ interaction: UIPointerInteraction, regionFor request: UIPointerRegionRequest,
                            defaultRegion: UIPointerRegion) -> UIPointerRegion? {
        guard let control = frameControl(at: request.location, pointer: true), let geometry = selectionGeometry else { return nil }
        let degrees = Int((geometry.angle * 180 / .pi).rounded())
        let identifier: String
        switch control {
        case .resize(let corner): identifier = "resize|\(corner)|\(degrees)"
        case .rotate(let corner): identifier = "rotate|\(corner)|\(degrees)"
        // Bowed upward, between the top corners' turns.
        case .knob: identifier = "rotate|0.5|\(degrees)"
        }
        return UIPointerRegion(rect: CGRect(x: request.location.x - 1, y: request.location.y - 1, width: 2, height: 2), identifier: identifier)
    }

    func pointerInteraction(_ interaction: UIPointerInteraction, styleFor region: UIPointerRegion) -> UIPointerStyle? {
        guard let identifier = region.identifier as? String else { return nil }
        let parts = identifier.split(separator: "|")
        guard parts.count == 3, let corner = Double(parts[1]), let degrees = Double(parts[2]) else { return nil }
        // Clockwise from the top right, as the corners run clockwise from the top left.
        let turn = (corner - 1) * 90 + degrees
        let path = parts[0] == "rotate" ? PointerShapes.rotate(degrees: turn) : PointerShapes.resize(degrees: turn)
        return UIPointerStyle(shape: .path(path), constrainedAxes: [])
    }

    // MARK: Keyboard

    /// Where the on-screen keyboard starts, in the window, so the canvas can lift text clear of it.
    @objc private func keyboardChanged(_ notification: Notification) {
        guard let window, let editor else { return }
        let hiding = notification.name == UIResponder.keyboardWillHideNotification
        guard !hiding, let end = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else {
            if editor.keyboardTop != nil { editor.keyboardTop = nil }
            return
        }
        let frame = window.convert(end, from: window.screen.coordinateSpace)
        // A hardware keyboard leaves only its shortcut bar, or nothing, on screen.
        let top: CGFloat? = frame.height < 80 || frame.minY >= window.bounds.maxY ? nil : frame.minY
        if editor.keyboardTop != top { editor.keyboardTop = top }
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

/// The trackpad pointer's shapes over a selection's corners: a diagonal double arrow to resize,
/// and the Mac's curved double arrow to rotate. Both are drawn pointing toward the top right and
/// turned to the corner and the element's own angle.
enum PointerShapes {
    static func resize(degrees: Double) -> UIBezierPath {
        let shaft = UIBezierPath()
        shaft.move(to: CGPoint(x: -6, y: 6))
        shaft.addLine(to: CGPoint(x: 6, y: -6))
        let path = UIBezierPath(cgPath: shaft.cgPath.copy(strokingWithWidth: 2.2, lineCap: .round, lineJoin: .round, miterLimit: 1))
        path.append(triangle(tip: CGPoint(x: 9, y: -9), base: CGPoint(x: 3.5, y: -3.5)))
        path.append(triangle(tip: CGPoint(x: -9, y: 9), base: CGPoint(x: -3.5, y: 3.5)))
        path.apply(CGAffineTransform(rotationAngle: degrees * .pi / 180))
        return path
    }

    static func rotate(degrees: Double) -> UIBezierPath {
        let radius: CGFloat = 9
        // The arc's middle sits on the pointer's centre; it bows up and to the right.
        let center = CGPoint(x: -radius * 0.7071, y: radius * 0.7071)
        let arc = UIBezierPath(arcCenter: center, radius: radius, startAngle: -.pi / 2, endAngle: 0, clockwise: true)
        let path = UIBezierPath(cgPath: arc.cgPath.copy(strokingWithWidth: 2.2, lineCap: .round, lineJoin: .round, miterLimit: 1))
        let top = CGPoint(x: center.x, y: center.y - radius), right = CGPoint(x: center.x + radius, y: center.y)
        path.append(triangle(tip: CGPoint(x: top.x - 4.5, y: top.y), base: CGPoint(x: top.x + 1, y: top.y)))
        path.append(triangle(tip: CGPoint(x: right.x, y: right.y + 4.5), base: CGPoint(x: right.x, y: right.y - 1)))
        path.apply(CGAffineTransform(rotationAngle: degrees * .pi / 180))
        return path
    }

    /// An arrowhead from the middle of its base to its tip.
    private static func triangle(tip: CGPoint, base: CGPoint) -> UIBezierPath {
        let dx = tip.x - base.x, dy = tip.y - base.y
        let length = max(hypot(dx, dy), 0.001)
        let half: CGFloat = 4.2
        let nx = -dy / length * half, ny = dx / length * half
        let path = UIBezierPath()
        path.move(to: tip)
        path.addLine(to: CGPoint(x: base.x + nx, y: base.y + ny))
        path.addLine(to: CGPoint(x: base.x - nx, y: base.y - ny))
        path.close()
        return path
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
