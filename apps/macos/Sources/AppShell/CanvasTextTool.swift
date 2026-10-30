import AppKit
import CanvasDrawing
import CanvasEditing
import ProductCore
import SwiftUI

// Text on the Mac canvas works the way pointer and keyboard tools do: with the Text tool, click
// and type in place; a frame moves, resizes and rotates the selected text; a floating bar sets
// its style. What is typed is laid out by TextKit exactly as the shared renderer draws it.

// MARK: Typing in place

/// The text being typed, drawn by AppKit's text view at the element's size, wrap width, rotation
/// and colours, with the background pill behind it. Clicks outside it fall through to the canvas.
struct InlineTextEditor: NSViewRepresentable {
    @Binding var draft: CanvasTextElement
    let canvasSize: CGSize
    let zoom: CGFloat
    let offset: CGPoint
    /// Changes when a format bar control should hand typing back to the text.
    let refocus: Int
    /// Esc: stop typing and keep the text selected.
    let onFinish: () -> Void

    func makeNSView(context: Context) -> InlineTextHost {
        let host = InlineTextHost()
        host.textView.delegate = context.coordinator
        host.textView.string = draft.text
        DispatchQueue.main.async {
            host.window?.makeFirstResponder(host.textView)
            host.textView.setSelectedRange(NSRange(location: (host.textView.string as NSString).length, length: 0))
        }
        return host
    }

    func updateNSView(_ host: InlineTextHost, context: Context) {
        context.coordinator.parent = self
        host.apply(draft, canvasSize: canvasSize, zoom: zoom, offset: offset)
        if context.coordinator.refocus != refocus {
            context.coordinator.refocus = refocus
            host.window?.makeFirstResponder(host.textView)
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(parent: self)
    }

    @MainActor
    final class Coordinator: NSObject, NSTextViewDelegate {
        var parent: InlineTextEditor
        var refocus: Int

        init(parent: InlineTextEditor) {
            self.parent = parent
            refocus = parent.refocus
        }

        func textDidChange(_ notification: Notification) {
            guard let textView = notification.object as? NSTextView else { return }
            parent.draft.text = textView.string
        }

        func textView(_ textView: NSTextView, doCommandBy selector: Selector) -> Bool {
            if selector == #selector(NSResponder.cancelOperation(_:)) {
                parent.onFinish()
                return true
            }
            return false
        }
    }
}

final class InlineTextHost: NSView {
    let container = InlineTextContainer()
    var textView: NSTextView { container.textView }

    override init(frame: NSRect) {
        super.init(frame: frame)
        addSubview(container)
    }

    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool { true }

    /// Only the text itself takes the pointer; a click elsewhere finishes typing on the canvas.
    override func hitTest(_ point: NSPoint) -> NSView? {
        guard let hit = super.hitTest(point), hit !== self else { return nil }
        return hit
    }

    func apply(_ element: CanvasTextElement, canvasSize: CGSize, zoom: CGFloat, offset: CGPoint) {
        let scale = CGFloat(element.scale) * zoom
        container.apply(element, wrap: max(1, CGFloat(element.boxWidth) * canvasSize.width) * scale, scale: scale)
        let center = CGPoint(
            x: element.center.x * canvasSize.width * zoom + offset.x,
            y: element.center.y * canvasSize.height * zoom + offset.y
        )
        // Rotate about the element's centre, as the renderer does: place upright, then turn.
        container.frameCenterRotation = 0
        let size = container.fittingSize
        container.frame = CGRect(x: center.x - size.width / 2, y: center.y - size.height / 2, width: size.width, height: size.height)
        container.frameCenterRotation = element.rotationRad * 180 / .pi
    }
}

/// The text view inside a margin wide enough for the background pill, which it draws itself.
final class InlineTextContainer: NSView {
    let textView: NSTextView
    private var pill: (color: NSColor, radius: CGFloat)?
    private var padding: CGFloat = 0
    private var textSize = CGSize.zero

    override init(frame: NSRect) {
        // TextKit 1, the same layout engine the canvas renderer draws with.
        let storage = NSTextStorage()
        let manager = NSLayoutManager()
        let textContainer = NSTextContainer(size: CGSize(width: 100, height: CGFloat.greatestFiniteMagnitude))
        textContainer.lineFragmentPadding = 0
        textContainer.widthTracksTextView = false
        manager.addTextContainer(textContainer)
        storage.addLayoutManager(manager)
        textView = NSTextView(frame: .zero, textContainer: textContainer)
        textView.isRichText = false
        textView.allowsUndo = true
        textView.drawsBackground = false
        textView.textContainerInset = .zero
        textView.isVerticallyResizable = false
        textView.isHorizontallyResizable = false
        textView.focusRingType = .none
        textView.isAutomaticQuoteSubstitutionEnabled = false
        textView.isAutomaticDashSubstitutionEnabled = false
        textView.isAutomaticTextReplacementEnabled = false
        super.init(frame: frame)
        addSubview(textView)
    }

    required init?(coder: NSCoder) {
        nil
    }

    override var isFlipped: Bool { true }

    override var fittingSize: NSSize {
        NSSize(width: textSize.width + padding * 2, height: textSize.height + padding * 2)
    }

    func apply(_ element: CanvasTextElement, wrap: CGFloat, scale: CGFloat) {
        let font = CanvasTextStyle.font(element.font, size: CanvasTextStyle.baseSize * scale)
        let colour = NSColor(argb: CanvasTextStyle.textColor(element))
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = CanvasTextStyle.alignment(element.alignment)
        let attributes: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: colour, .paragraphStyle: paragraph]
        if let storage = textView.textStorage {
            storage.setAttributes(attributes, range: NSRange(location: 0, length: storage.length))
        }
        textView.typingAttributes = attributes
        textView.insertionPointColor = colour
        textView.selectedTextAttributes = [.backgroundColor: colour.withAlphaComponent(0.25)]

        padding = element.backgroundPillEnabled ? CanvasTextStyle.pillPadding * scale : 2
        pill = element.backgroundPillEnabled ? (NSColor(argb: element.colorArgb), CanvasTextStyle.pillCornerRadius * scale) : nil

        guard let manager = textView.layoutManager, let textContainer = textView.textContainer else { return }
        textContainer.size = CGSize(width: wrap, height: .greatestFiniteMagnitude)
        manager.ensureLayout(for: textContainer)
        let lineHeight = manager.defaultLineHeight(for: font)
        let used = manager.usedRect(for: textContainer)
        textSize = CGSize(width: wrap, height: max(lineHeight, ceil(used.height)))
        textView.frame = CGRect(x: padding, y: padding, width: textSize.width, height: textSize.height)
        needsDisplay = true
    }

    /// The pill hugs the typed lines, as the renderer's tight bounds do.
    override func draw(_ dirtyRect: NSRect) {
        guard let pill, let manager = textView.layoutManager, let textContainer = textView.textContainer else { return }
        var left = CGFloat.infinity, right = -CGFloat.infinity
        manager.enumerateLineFragments(forGlyphRange: manager.glyphRange(for: textContainer)) { _, used, _, _, _ in
            left = min(left, used.minX)
            right = max(right, used.maxX)
        }
        if !(left.isFinite && right > left) {
            // Empty: a narrow pill around the caret.
            let caret = textView.alignment == .left ? 0 : textView.alignment == .right ? textSize.width : textSize.width / 2
            left = caret - 4
            right = caret + 4
        }
        let rect = CGRect(x: padding + left, y: padding, width: right - left, height: textSize.height)
            .insetBy(dx: -padding, dy: -padding)
        pill.color.setFill()
        NSBezierPath(roundedRect: rect, xRadius: pill.radius, yRadius: pill.radius).fill()
    }
}

// MARK: Selection frame

/// The selected text's or sticker's frame, as in Figma: drag inside to move, a corner to resize,
/// just outside a corner to rotate, the pointer turning to show it (it settles level, or on a
/// quarter turn, within 3°; Shift snaps to 15°), double-click to edit the words or swap the sticker.
struct ElementSelectionFrame<Element: CanvasTransformable>: View {
    let geometry: ElementFrameGeometry
    let original: Element
    let canvasSize: CGSize
    let zoom: CGFloat
    /// What VoiceOver calls the selection.
    let label: String
    let onChange: (Element) -> Void
    let onCommit: (Element) -> Void
    let onEdit: () -> Void

    @State private var start: Element?
    @State private var latest: Element?
    /// Whether the rotation sits on a quarter turn, so the trackpad ticks as it settles there.
    @State private var squared = false

    private let handle: CGFloat = 8
    /// The rotation zone beyond each corner, outside its resize handle.
    private let rotateZone: CGFloat = 22

    var body: some View {
        let size = geometry.body.size
        ZStack {
            // Under the frame and the handles, so only the area beyond a corner rotates.
            ForEach(Corner.allCases, id: \.self) { corner in
                Color.clear
                    .frame(width: rotateZone, height: rotateZone)
                    .contentShape(Rectangle())
                    .position(
                        x: corner.x * size.width + corner.outward.dx * (rotateZone / 2 - 2),
                        y: corner.y * size.height + corner.outward.dy * (rotateZone / 2 - 2)
                    )
                    .gesture(rotateGesture)
                    .pointerStyle(RotateCursor.style(degrees: corner.degrees + geometry.angle * 180 / .pi))
                    .help("Rotate")
            }
            Rectangle()
                .strokeBorder(mulberryRed, lineWidth: 1.25)
                .contentShape(Rectangle())
                .gesture(moveGesture)
                .simultaneousGesture(TapGesture(count: 2).onEnded(onEdit))
                .pointerStyle(.grabIdle)
            ForEach(Corner.allCases, id: \.self) { corner in
                square
                    .position(x: corner.x * size.width, y: corner.y * size.height)
                    .gesture(scaleGesture)
                    .pointerStyle(corner.resizeStyle)
            }
        }
        .frame(width: size.width, height: size.height)
        .rotationEffect(.radians(geometry.angle))
        .position(geometry.bodyCenter)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
    }

    private var square: some View {
        Rectangle()
            .fill(.white)
            .overlay(Rectangle().strokeBorder(mulberryRed, lineWidth: 1.25))
            .frame(width: handle, height: handle)
            .frame(width: 18, height: 18)
            .contentShape(Rectangle())
    }

    private var moveGesture: some Gesture {
        DragGesture(minimumDistance: 2, coordinateSpace: .named(CanvasSpace.name))
            .onChanged { drag in
                let from = start ?? original
                if start == nil { start = original }
                var moved = from
                moved.center = DrawingPoint(
                    x: min(1, max(0, from.center.x + drag.translation.width / (canvasSize.width * zoom))),
                    y: min(1, max(0, from.center.y + drag.translation.height / (canvasSize.height * zoom)))
                )
                update(moved)
            }
            .onEnded { _ in finish() }
    }

    private var scaleGesture: some Gesture {
        DragGesture(minimumDistance: 1, coordinateSpace: .named(CanvasSpace.name))
            .onChanged { drag in
                let from = start ?? original
                if start == nil { start = original }
                let center = geometry.center
                let before = hypot(drag.startLocation.x - center.x, drag.startLocation.y - center.y)
                let now = hypot(drag.location.x - center.x, drag.location.y - center.y)
                guard before > 1 else { return }
                var resized = from
                resized.scale = min(Element.scaleRange.upperBound,
                                    max(Element.scaleRange.lowerBound, from.scale * now / before))
                update(resized)
            }
            .onEnded { _ in finish() }
    }

    private var rotateGesture: some Gesture {
        DragGesture(minimumDistance: 1, coordinateSpace: .named(CanvasSpace.name))
            .onChanged { drag in
                let from = start ?? original
                if start == nil {
                    start = original
                    squared = ElementRotation.isSquare(original.rotationRad)
                }
                let center = geometry.center
                let turned = atan2(drag.location.y - center.y, drag.location.x - center.x)
                    - atan2(drag.startLocation.y - center.y, drag.startLocation.x - center.x)
                var angle = from.rotationRad + turned
                if NSEvent.modifierFlags.contains(.shift) {
                    let step = Double.pi / 12
                    angle = (angle / step).rounded() * step
                } else if let square = ElementRotation.quarterTurn(near: angle) {
                    // As the iPhone's rotation slider settles level near zero.
                    angle = square
                }
                let onSquare = ElementRotation.isSquare(angle)
                if onSquare != squared {
                    squared = onSquare
                    if onSquare { Haptics.select() }
                }
                var rotated = from
                rotated.rotationRad = angle
                update(rotated)
            }
            .onEnded { _ in finish() }
    }

    private func update(_ element: Element) {
        latest = element
        onChange(element)
    }

    /// One drag is one change, saved when the pointer lifts.
    private func finish() {
        if let latest { onCommit(latest) }
        start = nil
        latest = nil
    }

    private enum Corner: CaseIterable {
        case topLeft, topRight, bottomLeft, bottomRight

        var x: CGFloat { self == .topLeft || self == .bottomLeft ? 0 : 1 }
        var y: CGFloat { self == .topLeft || self == .topRight ? 0 : 1 }
        /// Away from the frame, diagonally.
        var outward: CGVector { CGVector(dx: x * 2 - 1, dy: y * 2 - 1) }
        /// The rotate pointer's turn for this corner, clockwise from the top right's.
        var degrees: Double {
            switch self {
            case .topRight: 0
            case .bottomRight: 90
            case .bottomLeft: 180
            case .topLeft: 270
            }
        }

        var resizeStyle: PointerStyle {
            switch self {
            case .topLeft, .bottomRight: .frameResize(position: .topLeading)
            case .topRight, .bottomLeft: .frameResize(position: .topTrailing)
            }
        }
    }
}

/// The canvas layer's coordinate space, shared by the selection frame's gestures.
enum CanvasSpace {
    static let name = "canvas"
}

// MARK: Format bar

/// The style of the selected or typed text, floating above it: font, size, colour, alignment,
/// background and delete. Neutral like the canvas's other glass, with Mulberry's own button.
struct TextFormatBar: View {
    let element: CanvasTextElement
    let onChange: (CanvasTextElement) -> Void
    let onDelete: () -> Void

    @State private var colourOpen = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Full where it fits. Narrower canvases drop the dividers first, and only then trade the
    /// font's name for an Aa button.
    var body: some View {
        ViewThatFits(in: .horizontal) {
            bar(showsName: true, dividers: true)
            bar(showsName: true, dividers: false)
            bar(showsName: false, dividers: true)
            bar(showsName: false, dividers: false)
        }
        // A new font changes the bar's width: it eases to the new size around its centre.
        .animation(reduceMotion ? nil : .smooth(duration: 0.25), value: element.font)
        .animation(reduceMotion ? nil : .smooth(duration: 0.2), value: points)
    }

    private func bar(showsName: Bool, dividers: Bool) -> some View {
        HStack(spacing: 2) {
            fontMenu(showsName: showsName)
            if dividers { divider }
            FormatButton(title: "Smaller", symbol: "minus", enabled: element.scale > CanvasTextElement.scaleRange.lowerBound) {
                resize(by: 1 / 1.1)
            }
            Text("\(points)")
                .font(.system(size: 13, weight: .medium))
                .monospacedDigit()
                .contentTransition(.numericText(value: Double(points)))
                .frame(minWidth: 26)
                .accessibilityLabel("Size \(points) points")
            FormatButton(title: "Larger", symbol: "plus", enabled: element.scale < CanvasTextElement.scaleRange.upperBound) {
                resize(by: 1.1)
            }
            if dividers { divider }
            colourButton
            HStack(spacing: 2) {
                alignment(.left, symbol: "text.alignleft", title: "Align left")
                alignment(.center, symbol: "text.aligncenter", title: "Centre")
                alignment(.right, symbol: "text.alignright", title: "Align right")
            }
            FormatButton(
                title: "Background",
                symbol: element.backgroundPillEnabled ? "a.square.fill" : "a.square",
                selected: element.backgroundPillEnabled
            ) {
                set(\.backgroundPillEnabled, !element.backgroundPillEnabled)
            }
            if dividers { divider }
            FormatButton(title: "Delete", symbol: "trash", action: onDelete)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .glassEffect(.regular, in: Capsule())
        .fixedSize()
    }

    /// Text size in points at the canvas's own scale, 34 being the default.
    private var points: Int {
        Int((CanvasTextStyle.baseSize * element.scale).rounded())
    }

    private var divider: some View {
        Divider().frame(height: 18).padding(.horizontal, 3)
    }

    private func fontMenu(showsName: Bool) -> some View {
        Menu {
            Picker("Font", selection: Binding(get: { element.font }, set: { set(\.font, $0); Haptics.select() })) {
                ForEach(CanvasTextFont.allCases, id: \.self) { font in
                    Text(CanvasTextStyle.displayName(font)).tag(font)
                }
            }
            .pickerStyle(.inline)
            .labelsHidden()
        } label: {
            FormatSurface {
                if showsName {
                    HStack(spacing: 5) {
                        Text(CanvasTextStyle.displayName(element.font))
                            .font(.custom(CanvasTextStyle.postScriptName(element.font), size: 15))
                            .contentTransition(.interpolate)
                            .lineLimit(1)
                            .truncationMode(.tail)
                            .frame(maxWidth: 76, alignment: .leading)
                        Image(systemName: "chevron.down")
                            .font(.system(size: 9, weight: .semibold))
                            .foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 8)
                    .frame(height: 28)
                } else {
                    Text("Aa")
                        .font(.custom(CanvasTextStyle.postScriptName(element.font), size: 15))
                        .frame(width: 28, height: 28)
                }
            }
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
        .menuIndicator(.hidden)
        .fixedSize()
        .help("Font")
        .accessibilityLabel("Font: \(CanvasTextStyle.displayName(element.font))")
    }

    private var colourButton: some View {
        Button { colourOpen = true } label: {
            FormatSurface(selected: colourOpen) {
                ColourPaletteIcon(color: element.colorArgb, size: 19)
                    .frame(width: 28, height: 28)
            }
        }
        .buttonStyle(.plain)
        .help("Colour")
        .accessibilityLabel("Text colour")
        .popover(isPresented: $colourOpen, arrowEdge: .bottom) {
            PaletteColourPicker(palette: CanvasEditingDefaults.palette, selected: element.colorArgb) { colour in
                set(\.colorArgb, colour)
                Haptics.select()
                colourOpen = false
            }
        }
    }

    private func alignment(_ value: CanvasTextAlign, symbol: String, title: String) -> some View {
        FormatButton(title: title, symbol: symbol, selected: element.alignment == value) {
            set(\.alignment, value)
            Haptics.select()
        }
    }

    private func resize(by factor: Double) {
        set(\.scale, min(CanvasTextElement.scaleRange.upperBound, max(CanvasTextElement.scaleRange.lowerBound, element.scale * factor)))
    }

    private func set<Value>(_ keyPath: WritableKeyPath<CanvasTextElement, Value>, _ value: Value) {
        var updated = element
        updated[keyPath: keyPath] = value
        onChange(updated)
    }
}

/// The format bar's button: an icon in a 28-point square, softly filled on hover and filled
/// more firmly when its option is on. Neutral, never the system's blue.
struct FormatButton: View {
    let title: String
    let symbol: String
    var selected = false
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            FormatSurface(selected: selected) {
                Image(systemName: symbol)
                    .font(.system(size: 14, weight: .medium))
                    .frame(width: 28, height: 28)
            }
            .opacity(enabled ? 1 : 0.35)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .help(title)
        .accessibilityLabel(title)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

struct FormatSurface<Content: View>: View {
    var selected = false
    @ViewBuilder let content: Content
    @State private var hovering = false

    var body: some View {
        content
            .foregroundStyle(.primary)
            .background(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(.primary.opacity(selected ? 0.16 : hovering ? 0.07 : 0))
            )
            .contentShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            .onHover { hovering = $0 }
            .animation(.easeOut(duration: 0.12), value: hovering)
    }
}

/// Figma's rotate pointer: a quarter arc with an arrowhead at each end, bowed toward the corner
/// it turns from and following the selection's own rotation. Drawn once per 5° and kept.
@MainActor
enum RotateCursor {
    private static var cache: [Int: PointerStyle] = [:]

    static func style(degrees: Double) -> PointerStyle {
        let step = ((Int((degrees / 5).rounded()) * 5) % 360 + 360) % 360
        if let cached = cache[step] { return cached }
        let style = PointerStyle.image(Image(nsImage: image(degrees: Double(step))), hotSpot: .center)
        cache[step] = style
        return style
    }

    static func image(degrees: Double) -> NSImage {
        let side: CGFloat = 28
        return NSImage(size: NSSize(width: side, height: side), flipped: true) { _ in
            guard let context = NSGraphicsContext.current?.cgContext else { return false }
            context.translateBy(x: side / 2, y: side / 2)
            context.rotate(by: degrees * .pi / 180)
            context.translateBy(x: -side / 2, y: -side / 2)
            // The arc's middle sits on the hot spot; it bows up and to the right.
            let radius: CGFloat = 10
            let center = CGPoint(x: side / 2 - radius * 0.7071, y: side / 2 + radius * 0.7071)
            let arc = CGMutablePath()
            arc.addArc(center: center, radius: radius, startAngle: -.pi / 2, endAngle: 0, clockwise: false)
            let top = CGPoint(x: center.x, y: center.y - radius)
            let right = CGPoint(x: center.x + radius, y: center.y)
            let heads = CGMutablePath()
            // Pointing back along the arc at each end: left at the top, down at the right.
            heads.addLines(between: [CGPoint(x: top.x - 4.5, y: top.y), CGPoint(x: top.x + 1, y: top.y - 4.5), CGPoint(x: top.x + 1, y: top.y + 4.5)])
            heads.closeSubpath()
            heads.addLines(between: [CGPoint(x: right.x, y: right.y + 4.5), CGPoint(x: right.x - 4.5, y: right.y - 1), CGPoint(x: right.x + 4.5, y: right.y - 1)])
            heads.closeSubpath()
            for (colour, width) in [(NSColor.white, 4.0), (NSColor.black, 1.6)] {
                context.setStrokeColor(colour.cgColor)
                context.setFillColor(colour.cgColor)
                context.setLineWidth(width)
                context.setLineJoin(.round)
                context.setLineCap(.round)
                context.addPath(arc)
                context.strokePath()
                context.addPath(heads)
                if colour == .white { context.drawPath(using: .fillStroke) } else { context.fillPath() }
            }
            return true
        }
    }
}
