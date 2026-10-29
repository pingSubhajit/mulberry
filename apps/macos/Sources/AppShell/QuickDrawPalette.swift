import AppKit
import CanvasEditing
import Overlay
import ProductCore
import Sync
import SwiftUI

/// Quick Draw's tools, attached outside the overlay's edge as the iPad's landscape tool column sits
/// beside its canvas: the actions, then the drawing tools, then Done.
struct QuickDrawPalette: View {
    @ObservedObject var model: CanvasEditorModel
    @ObservedObject var syncController: CanvasSyncController
    /// Which side of the overlay the panel is on.
    let side: HorizontalEdge
    /// Drags the overlay from the grip's mouse-down, calling back once it is dropped.
    let dragOverlay: (NSEvent, @escaping @MainActor () -> Void) -> Void
    let done: () -> Void

    @State private var moving = false
    @State private var gripHovered = false

    /// Popovers open toward the canvas.
    private var towardCanvas: Edge { side == .leading ? .trailing : .leading }

    var body: some View {
        GeometryReader { proxy in
            let size = buttonSize(for: proxy.size.height)
            GlassEffectContainer(spacing: 4) {
                toolColumn(size)
            }
            .frame(width: proxy.size.width, height: proxy.size.height, alignment: side == .leading ? .trailing : .leading)
        }
    }

    /// Eleven circles and the grip in the iPad's rhythm, as large as the overlay's height allows.
    private func buttonSize(for height: CGFloat) -> CGFloat {
        min(52, max(30, (height - 16) / 14.8))
    }

    private var canvasIsEmpty: Bool {
        syncController.drawing.strokes.isEmpty && syncController.drawing.elements.isEmpty
    }

    private func toolColumn(_ size: CGFloat) -> some View {
        VStack(spacing: size * 0.43) {
            grip(size)
            VStack(spacing: size * 0.18) {
                CanvasToolButton(title: "Undo", help: "Undo (⌘Z)", symbol: "arrow.uturn.backward", size: size, enabled: model.engine.canUndo && !model.isTyping, action: model.undo)
                CanvasToolButton(title: "Redo", help: "Redo (⇧⌘Z)", symbol: "arrow.uturn.forward", size: size, enabled: model.engine.canRedo && !model.isTyping, action: model.redo)
                CanvasColourButton(model: model, size: size, arrowEdge: towardCanvas)
                CanvasWidthButton(model: model, size: size, arrowEdge: towardCanvas)
            }
            VStack(spacing: size * 0.18) {
                ForEach(CanvasToolSlot.allCases) { slot in
                    CanvasToolButton(slot, model: model, size: size, canvasIsEmpty: canvasIsEmpty)
                }
            }
            doneButton(size)
        }
        .padding(8)
    }

    /// Drags the overlay, with this panel following it. A button's hit area with no button drawn:
    /// six dots, muted until the pointer is over them and bright while dragging, shadowed so they
    /// read on any desktop. SF Symbols has no 3x2 grip.
    private func grip(_ size: CGFloat) -> some View {
        let dot = size * 0.1
        return Grid(horizontalSpacing: dot * 0.8, verticalSpacing: dot * 0.8) {
            ForEach(0..<2, id: \.self) { _ in
                GridRow {
                    ForEach(0..<3, id: \.self) { _ in
                        Circle().frame(width: dot, height: dot)
                    }
                }
            }
        }
        .foregroundStyle(.white.opacity(moving ? 1 : gripHovered ? 0.8 : 0.45))
        .shadow(color: .black.opacity(0.55), radius: 2, y: 1)
        .animation(.easeOut(duration: 0.15), value: gripHovered)
        .animation(.easeOut(duration: 0.15), value: moving)
        .frame(width: size, height: size)
        // The window server sends clicks on fully transparent pixels to whatever is behind the
        // panel, which ends Quick Draw; an unseen fill keeps the whole circle the grip's.
        .background(Circle().fill(.black.opacity(0.02)))
        .contentShape(Circle())
        .onHover { gripHovered = $0 }
        .overlay {
            GripDragArea { mouseDown in
                moving = true
                dragOverlay(mouseDown) { moving = false }
            }
        }
        .help("Move")
        .accessibilityLabel("Move the drawing")
    }

    private func doneButton(_ size: CGFloat) -> some View {
        Button(action: done) {
            Image(systemName: "checkmark")
                .font(.system(size: size * 0.38, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: size, height: size)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .glassEffect(.regular.tint(mulberryRed).interactive(), in: .circle)
        .help("Done (Esc)")
        .accessibilityLabel("Done")
    }
}

/// The grip's mouse-down, handed to the window server's window drag: a SwiftUI drag in a window
/// that moves under the pointer lags, and cannot carry it to another display. The pointer stays
/// the arrow, as over a title bar.
private struct GripDragArea: NSViewRepresentable {
    let drag: (NSEvent) -> Void

    func makeNSView(context: Context) -> GripView {
        GripView()
    }

    func updateNSView(_ view: GripView, context: Context) {
        view.drag = drag
    }

    final class GripView: NSView {
        var drag: (NSEvent) -> Void = { _ in }

        override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
        override var mouseDownCanMoveWindow: Bool { false }

        override func mouseDown(with event: NSEvent) {
            drag(event)
        }
    }
}
