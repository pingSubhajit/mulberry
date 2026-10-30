import AppKit
import CanvasEditing
import ProductCore
import SwiftUI

/// The editing state a canvas and its toolbars share. The window and Quick Draw each have their
/// own: in Quick Draw the tools live in a panel beside the overlay, not in the canvas's view.
@MainActor
final class CanvasEditorModel: ObservableObject {
    /// The editing engine. Drawing changes its stroke with every pointer move, which no toolbar
    /// shows, so only changes to what the controls show are published.
    var engine = CanvasStrokeEditingEngine() {
        willSet {
            if Controls(newValue) != Controls(engine) { objectWillChange.send() }
        }
    }
    /// Text is being typed in place: ⌘Z belongs to the text.
    @Published var isTyping = false
    /// The sticker panel beside the Sticker tool's button.
    @Published var stickerPanelOpen = false
    /// Set by the canvas: the sticker panel's contents, which place what is picked.
    var stickerPanel: () -> AnyView = { AnyView(EmptyView()) }
    /// The colour under the eyedropper before a click picks it, shown on the colour and width
    /// buttons as the iPhone shows it while a finger slides.
    @Published var eyedropperPreview: UInt32?
    /// Set by the canvas: Esc finishes typing, deselects, or leaves the eyedropper. False when
    /// there was nothing to cancel.
    var handleEscape: () -> Bool = { false }
    /// Set by the canvas: finishes any typing, before Quick Draw closes.
    var finishEditing: () -> Void = {}

    private let submit: ([DrawingOperation]) -> Void

    init(submit: @escaping ([DrawingOperation]) -> Void) {
        self.submit = submit
    }

    var tool: CanvasEditingTool { engine.toolState.activeTool }

    /// Choosing the Sticker tool opens its panel; choosing it again closes it.
    func select(_ tool: CanvasEditingTool) {
        if tool == .sticker {
            stickerPanelOpen = engine.toolState.activeTool == .sticker ? !stickerPanelOpen : true
        } else {
            stickerPanelOpen = false
        }
        engine.toolState.setActiveTool(tool)
    }

    func undo() {
        submit(engine.undo())
    }

    func redo() {
        submit(engine.redo())
    }

    /// What the toolbars, menus and canvas show of the engine.
    private struct Controls: Equatable {
        let toolState: CanvasEditingToolState
        let viewportTransform: CanvasViewportTransform
        let canUndo: Bool
        let canRedo: Bool
        let clearConfirmationRequested: Bool

        init(_ engine: CanvasStrokeEditingEngine) {
            toolState = engine.toolState
            viewportTransform = engine.viewportTransform
            canUndo = engine.canUndo
            canRedo = engine.canRedo
            clearConfirmationRequested = engine.clearConfirmationRequested
        }
    }
}

/// One glass circle of the toolbar: an iOS illustration or an SF Symbol, ringed in Mulberry red
/// when selected. The whole circle is the button.
struct CanvasToolButton: View {
    let title: String
    var help: String?
    var artwork: String?
    var symbol: String?
    let size: CGFloat
    var selected = false
    var enabled = true
    var glass: Glass = .regular
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Group {
                if let symbol {
                    Image(systemName: symbol).font(.system(size: size * 0.44, weight: .medium))
                } else if let artwork, let image = ToolbarArtwork.image(artwork) {
                    Image(nsImage: image)
                        .resizable()
                        .renderingMode(image.isTemplate ? .template : .original)
                        .scaledToFit()
                        .frame(width: size * 0.72, height: size * 0.72)
                }
            }
            .foregroundStyle(.primary)
            .opacity(enabled ? 1 : 0.35)
            .frame(width: size, height: size)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .glassEffect(glass.tint(selected ? mulberryRed.opacity(0.22) : .clear).interactive(), in: .circle)
        .overlay {
            // Solid enough to read on clear glass over any desktop, not only on the window's frost.
            if selected { Circle().strokeBorder(mulberryRed.opacity(0.85), lineWidth: 2).allowsHitTesting(false) }
        }
        .help(help ?? title)
        .accessibilityLabel(title)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// The six drawing tools, in the iOS order.
enum CanvasToolSlot: CaseIterable, Identifiable {
    case brush, text, stickers, eraser, eyedropper, clear

    var id: Self { self }

    var title: String {
        switch self {
        case .brush: "Brush"
        case .text: "Text"
        case .stickers: "Stickers"
        case .eraser: "Eraser"
        case .eyedropper: "Eyedropper"
        case .clear: "Clear canvas"
        }
    }

    var artwork: String {
        switch self {
        case .brush: "brush.png"
        case .text: "text.svg"
        case .stickers: "sticker.png"
        case .eraser: "eraser.png"
        case .eyedropper: "eyedropper.png"
        case .clear: "clear.png"
        }
    }

    /// The single key that picks the tool, as in Photoshop and Figma.
    var shortcut: String? {
        switch self {
        case .brush: "b"
        case .text: "t"
        case .stickers: "s"
        case .eraser: "e"
        case .eyedropper: "i"
        case .clear: nil
        }
    }

    /// The tooltip, with the tool's key.
    var help: String {
        if self == .clear { return "\(title) (⌘⌫)" }
        return shortcut.map { "\(title) (\($0.uppercased()))" } ?? title
    }

    var tool: CanvasEditingTool? {
        switch self {
        case .brush: .draw
        case .text: .text
        case .stickers: .sticker
        case .eraser: .erase
        case .eyedropper: .eyedropper
        case .clear: nil
        }
    }
}

extension CanvasToolButton {
    /// A drawing tool's button: selects the tool, or asks before clearing the canvas.
    init(_ slot: CanvasToolSlot, model: CanvasEditorModel, size: CGFloat, canvasIsEmpty: Bool) {
        if let tool = slot.tool {
            self.init(title: slot.title, help: slot.help, artwork: slot.artwork, size: size, selected: model.tool == tool) {
                model.select(tool)
            }
        } else {
            self.init(title: slot.title, help: slot.help, artwork: slot.artwork, size: size, enabled: !canvasIsEmpty) {
                model.engine.requestClearCanvas()
            }
        }
    }
}

/// The colour button: the iOS palette face, opening the palette with its custom picker.
struct CanvasColourButton: View {
    @ObservedObject var model: CanvasEditorModel
    let size: CGFloat
    var glass: Glass = .regular
    var arrowEdge: Edge = .leading
    @State private var open = false

    var body: some View {
        Button { open = true } label: {
            ColourPaletteIcon(color: model.eyedropperPreview ?? model.engine.toolState.selectedColorArgb, size: size * 0.62)
                .frame(width: size, height: size)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .glassEffect(glass.interactive(), in: .circle)
        .help("Colour: \(ColourNames.name(model.engine.toolState.selectedColorArgb))")
        .accessibilityLabel("Colour palette")
        .accessibilityValue(ColourNames.name(model.engine.toolState.selectedColorArgb))
        .popover(isPresented: $open, arrowEdge: arrowEdge) {
            PaletteColourPicker(palette: CanvasEditingDefaults.palette, selected: model.engine.toolState.selectedColorArgb) { colour in
                model.engine.toolState.setSelectedColor(colour)
                Haptics.select()
                open = false
            }
        }
    }
}

/// The brush width: a dot as wide as the brush, opening AppKit's vertical slider.
struct CanvasWidthButton: View {
    @ObservedObject var model: CanvasEditorModel
    let size: CGFloat
    var glass: Glass = .regular
    var arrowEdge: Edge = .leading
    @State private var open = false

    var body: some View {
        let range = CanvasEditingDefaults.minBrushWidthPx...CanvasEditingDefaults.maxBrushWidthPx
        let width = CGFloat(model.engine.toolState.selectedBrushWidthPx)
        let dot = size * (0.15 + 0.55 * (width - CGFloat(range.lowerBound)) / CGFloat(range.upperBound - range.lowerBound))
        Button { open = true } label: {
            Circle().fill(Color(argb: model.eyedropperPreview ?? model.engine.toolState.brushColorArgb))
                .frame(width: dot, height: dot)
                .frame(width: size, height: size)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .glassEffect(glass.interactive(), in: .circle)
        .help("Brush width")
        .accessibilityLabel("Brush width")
        .accessibilityValue("\(Int(model.engine.toolState.selectedBrushWidthPx))")
        .popover(isPresented: $open, arrowEdge: arrowEdge) {
            VerticalWidthSlider(
                value: Binding(
                    get: { model.engine.toolState.selectedBrushWidthPx },
                    set: { model.engine.toolState.setBrushWidth($0) }
                ),
                range: range,
                colour: NSColor(argb: model.engine.toolState.brushColorArgb)
            )
            .frame(width: 24, height: 180)
            .padding(14)
        }
    }
}
