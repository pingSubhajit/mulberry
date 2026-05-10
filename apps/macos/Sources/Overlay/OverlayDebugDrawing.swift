import AppKit
import SwiftUI

struct OverlayDebugStroke {
    let color: NSColor
    let width: CGFloat
    var points: [CGPoint]
}

@MainActor
final class OverlayDebugDrawingModel: ObservableObject {
    @Published var strokes: [OverlayDebugStroke] = [] {
        didSet { onChange?() }
    }
    @Published var activeStroke: OverlayDebugStroke? {
        didSet { onChange?() }
    }
    @Published var selectedColor: NSColor = .systemPink
    @Published var brushWidth: CGFloat = 8
    @Published var isQuickDrawActive = false {
        didSet { onChange?() }
    }

    var onChange: (() -> Void)?

    func clear() {
        strokes.removeAll()
        activeStroke = nil
    }
}

@MainActor
final class OverlayDebugCanvasView: NSView {
    private let model: OverlayDebugDrawingModel

    init(model: OverlayDebugDrawingModel) {
        self.model = model
        super.init(frame: .zero)
        wantsLayer = true
        layer?.backgroundColor = NSColor.clear.cgColor
        model.onChange = { [weak self] in
            self?.needsDisplay = true
        }
    }

    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool { true }

    override func draw(_ dirtyRect: NSRect) {
        super.draw(dirtyRect)
        drawPhase3Frame()
        model.strokes.forEach(drawStroke)
        if let activeStroke = model.activeStroke {
            drawStroke(activeStroke)
        }
    }

    override func mouseDown(with event: NSEvent) {
        guard model.isQuickDrawActive else { return }
        model.activeStroke = OverlayDebugStroke(
            color: model.selectedColor,
            width: model.brushWidth,
            points: [convert(event.locationInWindow, from: nil)]
        )
    }

    override func mouseDragged(with event: NSEvent) {
        guard model.isQuickDrawActive, var stroke = model.activeStroke else { return }
        stroke.points.append(convert(event.locationInWindow, from: nil))
        model.activeStroke = stroke
    }

    override func mouseUp(with event: NSEvent) {
        guard model.isQuickDrawActive, var stroke = model.activeStroke else { return }
        stroke.points.append(convert(event.locationInWindow, from: nil))
        model.strokes.append(stroke)
        model.activeStroke = nil
    }

    private func drawPhase3Frame() {
        let rect = bounds.insetBy(dx: 8, dy: 8)
        let path = NSBezierPath(roundedRect: rect, xRadius: 28, yRadius: 28)
        path.lineWidth = model.isQuickDrawActive ? 3 : 2
        NSColor.systemPink.withAlphaComponent(model.isQuickDrawActive ? 0.64 : 0.42).setStroke()
        path.setLineDash([8, 8], count: 2, phase: 0)
        path.stroke()

        let label = model.isQuickDrawActive ? "Quick Draw debug layer" : "Overlay placeholder"
        let attributes: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: 11, weight: .medium),
            .foregroundColor: NSColor.secondaryLabelColor
        ]
        NSString(string: label).draw(
            at: CGPoint(x: rect.minX + 14, y: rect.minY + 12),
            withAttributes: attributes
        )
    }

    private func drawStroke(_ stroke: OverlayDebugStroke) {
        guard stroke.points.count > 1 else { return }
        let path = NSBezierPath()
        path.move(to: stroke.points[0])
        stroke.points.dropFirst().forEach(path.line)
        stroke.color.setStroke()
        path.lineCapStyle = .round
        path.lineJoinStyle = .round
        path.lineWidth = stroke.width
        path.stroke()
    }
}

struct QuickDrawPaletteView: View {
    @ObservedObject var model: OverlayDebugDrawingModel
    let onDone: () -> Void
    let onClear: () -> Void

    private let colors: [NSColor] = [
        .systemPink,
        .systemBlue,
        .systemGreen,
        .systemOrange,
        .labelColor
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Quick Draw")
                .font(.headline)

            HStack(spacing: 8) {
                ForEach(colors, id: \.self) { color in
                    Button {
                        model.selectedColor = color
                    } label: {
                        Circle()
                            .fill(Color(nsColor: color))
                            .frame(width: 22, height: 22)
                            .overlay(
                                Circle()
                                    .stroke(model.selectedColor == color ? Color.primary : Color.clear, lineWidth: 2)
                            )
                    }
                    .buttonStyle(.plain)
                }
            }

            HStack {
                Image(systemName: "paintbrush.pointed")
                Slider(
                    value: Binding(
                        get: { Double(model.brushWidth) },
                        set: { model.brushWidth = CGFloat($0) }
                    ),
                    in: 2...24
                )
                .frame(width: 140)
            }

            HStack(spacing: 8) {
                Button("Clear", action: onClear)
                Button("Done", action: onDone)
                    .keyboardShortcut(.escape, modifiers: [])
            }
        }
        .padding(14)
        .frame(width: 250)
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}
