import SwiftUI

/// Apple Pencil's squeeze palette, after Freeform's: the tools on a Liquid Glass arc around the
/// Pencil tip. Freeform's comes from PencilKit's tool picker, which shows its tools only while the
/// full picker is on screen, so Mulberry draws its own.
struct PencilRadialPalette: View {
    @ObservedObject var editor: DoodleEditor
    /// Opens under the tip instead of over it, for a tip near the top of the screen.
    var below = false
    /// How far the tip sits right of the arc's centre, when the arc is nudged to stay on screen.
    var tipOffset: CGFloat = 0
    let dismiss: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var selection
    @AccessibilityFocusState private var focus: Item?
    /// False until the first frame, so the band and items bloom out of the tip.
    @State private var opened = false
    // The colour and size items open their own popovers over the arc, which stays open.
    @State private var coloursOpen = false
    @State private var widthOpen = false
    @State private var customColourOpen = false

    static let radius: CGFloat = 125
    static let thickness: CGFloat = 60
    /// How far the arc reaches from the tip, for keeping it on screen.
    static var reach: CGFloat { radius + thickness / 2 }

    private enum Item: CaseIterable, Hashable { case undo, redo, eraser, brush, eyedropper, colour, width }

    /// Left to right over the tip, turned 20° toward the left like Freeform's; mirrored when below.
    private var angles: [Double] {
        let start = below ? 185.0 : 175.0, end = below ? 35.0 : 325.0
        let count = Item.allCases.count
        return (0..<count).map { start + (end - start) * Double($0) / Double(count - 1) }
    }

    var body: some View {
        let side = Self.reach * 2
        let items = Array(Item.allCases.enumerated())
        let shown = opened || reduceMotion
        let tip = CGPoint(x: side / 2 + tipOffset, y: side / 2)
        ZStack {
            Color.clear
                .glassEffect(.regular, in: ArcBand(radius: Self.radius, thickness: Self.thickness,
                                                   from: angles.first ?? 0, to: angles.last ?? 0,
                                                   progress: shown ? 1 : 0.8))
                // The band grows out of the tip, opening a little along the arc as it goes.
                .scaleEffect(shown ? 1 : 0.2, anchor: UnitPoint(x: tip.x / side, y: tip.y / side))
                .opacity(shown ? 1 : 0)
                .animation(reduceMotion ? nil : .spring(duration: 0.24, bounce: 0.15), value: opened)
                .accessibilityHidden(true)
            ForEach(items, id: \.offset) { index, item in
                button(item)
                    .accessibilityFocused($focus, equals: item)
                    // VoiceOver reads along the arc, left to right.
                    .accessibilitySortPriority(Double(items.count - index))
                    .scaleEffect(shown ? 1 : 0.3)
                    .opacity(shown ? 1 : 0)
                    // Each item flies out of the tip to its place on the arc, a beat after the one before.
                    .position(shown ? point(at: angles[index], in: side) : tip)
                    .animation(reduceMotion ? nil : .spring(duration: 0.24, bounce: 0.2).delay(Double(index) * 0.012), value: opened)
            }
        }
        .frame(width: side, height: side)
        .animation(.snappy(duration: 0.16), value: editor.tool)
        // A light tap as it opens, a tick for each tool chosen; Apple Pencil Pro plays them in the hand.
        .sensoryFeedback(.impact(weight: .light), trigger: opened) { _, isOpen in isOpen }
        .sensoryFeedback(.selection, trigger: editor.tool)
        // A modal menu for VoiceOver: focus starts on the current tool, and the scrub gesture closes it.
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
        .accessibilityLabel("Tools")
        .accessibilityAction(.escape, dismiss)
        .onAppear {
            opened = true
            focus = switch editor.tool {
            case .eraser: .eraser
            case .eyedropper: .eyedropper
            default: .brush
            }
        }
    }

    private func point(at degrees: Double, in side: CGFloat) -> CGPoint {
        let radians = degrees * .pi / 180
        return CGPoint(x: side / 2 + Self.radius * cos(radians), y: side / 2 + Self.radius * sin(radians))
    }

    @ViewBuilder private func button(_ item: Item) -> some View {
        switch item {
        case .undo: action("CanvasUndo", "Undo", enabled: editor.canUndo) { editor.undo() }
        case .redo: action("CanvasRedo", "Redo", enabled: editor.canRedo) { editor.redo() }
        case .eraser: tool(.eraser, "CanvasErase", "Eraser")
        case .brush: tool(.brush, "CanvasBrush", "Brush")
        case .eyedropper: tool(.eyedropper, "CanvasEyedropper", "Eyedropper")
        case .colour:
            Button { coloursOpen = true } label: {
                Circle().fill(Color(uiColor: UIColor(argb: editor.selectedColor)))
                    .frame(width: 42, height: 42)
                    .overlay(Circle().strokeBorder(.white.opacity(0.6), lineWidth: 2))
                    .itemFrame()
            }
            .buttonStyle(RadialItemStyle())
            .accessibilityLabel("Colour").accessibilityHint("Shows every colour")
            .popover(isPresented: $coloursOpen) { colours.presentationCompactAdaptation(.popover) }
        case .width:
            Button { widthOpen = true } label: {
                Circle().fill(Color(uiColor: UIColor(argb: editor.color)))
                    .frame(width: 8 + (editor.width - 4) / 38 * 30, height: 8 + (editor.width - 4) / 38 * 30)
                    .itemFrame()
            }
            .buttonStyle(RadialItemStyle())
            .accessibilityLabel("Brush size").accessibilityValue("\(Int(editor.width))")
            .accessibilityHint("Shows the size slider")
            .popover(isPresented: $widthOpen) {
                Slider(value: $editor.width, in: CanvasToolStore.widths, step: 1)
                    .tint(Color(uiColor: UIColor(argb: editor.color))).accessibilityLabel("Brush size")
                    .frame(width: 240).padding(20)
                    .presentationCompactAdaptation(.popover)
            }
        }
    }

    /// Every colour, and the custom colour; choosing one returns to the brush, as the palette does.
    private var colours: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.fixed(40), spacing: 12), count: 5), spacing: 12) {
            ForEach(DoodleEditor.palette, id: \.self) { color in
                Button {
                    editor.selectedColor = color
                    if editor.tool != .text { editor.tool = .brush }
                    coloursOpen = false
                } label: {
                    Circle().fill(Color(uiColor: UIColor(argb: color))).frame(width: 36, height: 36)
                        .overlay(Circle().stroke(MulberryDesign.muted.opacity(0.3)))
                        .padding(2)
                        .overlay { if color == editor.selectedColor { Circle().strokeBorder(MulberryDesign.ink, lineWidth: 2) } }
                }
                .buttonStyle(RadialItemStyle())
                .accessibilityLabel("Colour \(String(format: "%06X", color & 0xFFFFFF))")
                .accessibilityAddTraits(editor.selectedColor == color ? .isSelected : [])
            }
            Button { customColourOpen = true } label: {
                CustomColourWell(color: editor.selectedColor)
                    .frame(width: 28, height: 28).scaleEffect(1.43)
                    .frame(width: 40, height: 40).contentShape(Circle())
                    .accessibilityHidden(true)
            }.buttonStyle(RadialItemStyle()).accessibilityLabel("Custom colour")
        }
        .padding(20)
        .sheet(isPresented: $customColourOpen) {
            CustomColourSheet(color: $editor.selectedColor) {
                if editor.tool != .text { editor.tool = .brush }
                customColourOpen = false; coloursOpen = false
            }
        }
    }

    /// Choosing a tool moves the highlight to it, then closes the arc once the move has registered.
    private func tool(_ tool: DoodleTool, _ image: String, _ title: String) -> some View {
        let selected = editor.tool == tool
        return Button {
            editor.tool = tool
            Task { try? await Task.sleep(for: .milliseconds(reduceMotion ? 0 : 100)); dismiss() }
        } label: {
            Image(image).resizable().scaledToFit().frame(width: 42, height: 42)
                .itemFrame()
                .background {
                    if selected {
                        Circle().fill(MulberryDesign.accent.opacity(0.28))
                            .matchedGeometryEffect(id: "selection", in: selection)
                    }
                }
        }
        .buttonStyle(RadialItemStyle())
        .accessibilityLabel(title)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityShowsLargeContentViewer { Image(image); Text(title) }
    }

    private func action(_ image: String, _ title: String, enabled: Bool, perform: @escaping () -> Void) -> some View {
        Button(action: perform) {
            Image(image).resizable().scaledToFit().frame(width: 38, height: 38)
                .opacity(enabled ? 1 : 0.35)
                .itemFrame()
        }
        .buttonStyle(RadialItemStyle())
        .disabled(!enabled)
        .accessibilityLabel(title)
        .accessibilityShowsLargeContentViewer { Image(image); Text(title) }
    }
}

private extension View {
    /// Each item's round target: 54 pt, highlighted when a Pencil or pointer hovers over it.
    func itemFrame() -> some View {
        frame(width: 54, height: 54)
            .contentShape(Circle())
            .contentShape(.hoverEffect, Circle())
            .hoverEffect(.highlight)
    }
}

/// Items dip and darken while pressed, the way system palette buttons respond.
private struct RadialItemStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(Circle().fill(.primary.opacity(configuration.isPressed ? 0.12 : 0)))
            .scaleEffect(configuration.isPressed ? 0.88 : 1)
            .animation(.snappy(duration: 0.12), value: configuration.isPressed)
    }
}

/// A band along part of a circle, with rounded ends, centred in its frame. Angles are in degrees,
/// measured clockwise from the right on screen. Progress opens it from the middle outward.
struct ArcBand: Shape {
    let radius: CGFloat
    let thickness: CGFloat
    let from: Double
    let to: Double
    var progress: Double = 1

    var animatableData: Double {
        get { progress }
        set { progress = newValue }
    }

    func path(in rect: CGRect) -> Path {
        let middle = (from + to) / 2, half = (to - from) / 2 * progress
        var centreLine = Path()
        let steps = 60
        for step in 0...steps {
            let degrees = middle - half + 2 * half * Double(step) / Double(steps)
            let radians = degrees * .pi / 180
            let point = CGPoint(x: rect.midX + radius * cos(radians), y: rect.midY + radius * sin(radians))
            if step == 0 { centreLine.move(to: point) } else { centreLine.addLine(to: point) }
        }
        return centreLine.strokedPath(StrokeStyle(lineWidth: thickness, lineCap: .round, lineJoin: .round))
    }
}
