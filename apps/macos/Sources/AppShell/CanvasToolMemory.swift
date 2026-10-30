import CanvasEditing
import Combine
import Foundation

/// Remembers the canvas's tools as the iPhone and iPad do: the brush and text colours, the brush
/// width and the drawing tool, saved per account and pair. The canvas window and Quick Draw share
/// them, so a colour or width chosen in one is waiting in the other, and after a relaunch.
///
/// Like the iPhone, only a resting tool is remembered: the eyedropper picks once, and the Text and
/// Sticker tools start cold, so the canvas comes back to the brush or the eraser.
@MainActor
final class CanvasToolMemory {
    private struct Remembered: Codable, Equatable {
        var brushColorArgb: UInt32
        var textColorArgb: UInt32
        var widthPx: Float
        var tool: CanvasEditingTool

        init(_ state: CanvasEditingToolState) {
            brushColorArgb = state.brushColorArgb
            textColorArgb = state.textColorArgb
            widthPx = state.selectedBrushWidthPx
            tool = Self.resting(state.activeTool) ? state.activeTool : state.previousTool
        }

        static func resting(_ tool: CanvasEditingTool) -> Bool { tool == .draw || tool == .erase }
    }

    private let models: [CanvasEditorModel]
    private let defaults: UserDefaults
    private var key: String?
    private var remembered: Remembered?
    private var cancellables: [AnyCancellable] = []

    init(models: [CanvasEditorModel], defaults: UserDefaults = .standard) {
        self.models = models
        self.defaults = defaults
        for model in models {
            model.objectWillChange.sink { [weak self, weak model] _ in
                // After the change lands: objectWillChange arrives before it.
                DispatchQueue.main.async {
                    guard let self, let model else { return }
                    self.changed(model)
                }
            }
            .store(in: &cancellables)
        }
    }

    /// Loads the account's and pair's tools into every canvas; a new account or pair starts fresh.
    func bind(user: String?, pair: String?) {
        let next = user.map { "canvasTools.\($0).\(pair ?? "unpaired")" }
        guard next != key else { return }
        key = next
        let saved = next.flatMap { defaults.data(forKey: $0) }.flatMap { try? JSONDecoder().decode(Remembered.self, from: $0) }
        let state = saved ?? Remembered(CanvasEditingToolState())
        remembered = state
        models.forEach { apply(state, to: $0) }
    }

    /// A canvas changed its tools: save what is worth keeping, and give it to the other canvas.
    private func changed(_ model: CanvasEditorModel) {
        guard let key else { return }
        let state = Remembered(model.engine.toolState)
        guard state != remembered else { return }
        remembered = state
        if let data = try? JSONEncoder().encode(state) { defaults.set(data, forKey: key) }
        for other in models where other !== model { apply(state, to: other) }
    }

    private func apply(_ state: Remembered, to model: CanvasEditorModel) {
        var tools = model.engine.toolState
        tools.brushColorArgb = state.brushColorArgb
        tools.textColorArgb = state.textColorArgb
        tools.setBrushWidth(state.widthPx)
        // A canvas mid-way through Text, Stickers or the eyedropper keeps that tool.
        if Remembered.resting(tools.activeTool) { tools.setActiveTool(state.tool) }
        if tools != model.engine.toolState { model.engine.toolState = tools }
    }
}
