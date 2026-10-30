import SwiftUI
import UIKit

// On iPad, text and stickers are edited in place, as on the Mac: a tap selects one in a frame with
// handles, a bar floating above it styles it, and text is typed on the canvas itself, laid out
// exactly as the canvas draws it. The canvas's touch view draws the frame and handles its
// gestures; this layer, over the canvas in the canvas's own coordinates, holds the floating bars,
// the text being typed and the keyboard shortcuts. The iPhone keeps its full-screen editors.

struct InPlaceEditingLayer: View {
    @ObservedObject var editor: DoodleEditor
    let canvasSize: CGSize
    /// The part of the canvas the screen's bars leave visible, in the canvas's coordinates.
    let visibleTop: CGFloat
    let visibleBottom: CGFloat
    @State private var barSize = CGSize(width: 320, height: 56)
    /// Where the sticker picker last opened on the canvas, kept while it closes.
    @State private var placement = DrawingPoint(x: 0.5, y: 0.5)
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// What the bar styles: the text being typed, or the selection while its tool is chosen.
    private var target: CanvasElement? {
        if let typing = editor.typing { return .text(typing.element) }
        guard let selection = editor.shownSelection else { return nil }
        switch selection {
        case .text: return editor.tool == .text ? selection : nil
        case .sticker: return editor.tool == .sticker ? selection : nil
        }
    }

    var body: some View {
        ZStack(alignment: .topLeading) {
            if let typing = editor.typing {
                InlineCanvasTextEditor(
                    draft: Binding(get: { editor.typing?.element ?? typing.element }, set: { editor.typing?.element = $0 }),
                    canvasSize: canvasSize
                ) { editor.finishTyping(keepSelection: true) }
                .id(typing.id)
                .frame(width: canvasSize.width, height: canvasSize.height)
            }
            // Out of the way while the fingers move the selection, back when they lift.
            if let target, editor.elementPreview == nil {
                bar(for: target)
                    .fixedSize()
                    .onGeometryChange(for: CGSize.self) { $0.size } action: { barSize = $0 }
                    .position(barPosition(editor.frameGeometry(of: target, canvasSize: canvasSize)))
                    .transition(.opacity.combined(with: .scale(scale: reduceMotion ? 1 : 0.94)))
            }
        }
        .frame(width: canvasSize.width, height: canvasSize.height, alignment: .topLeading)
        // The sticker picker, opened where empty canvas was tapped; the sticker lands there.
        .overlay {
            Color.clear.frame(width: 1, height: 1)
                .position(x: placement.x * canvasSize.width, y: placement.y * canvasSize.height)
                .popover(isPresented: Binding(get: { editor.stickerPlacement != nil }, set: { if !$0 { editor.stickerPlacement = nil } })) {
                    StickerPickerPanel(catalog: editor.stickers) { editor.placeSticker($0, at: placement) }
                        .presentationCompactAdaptation(.popover)
                }
                .allowsHitTesting(false)
        }
        .onChange(of: editor.stickerPlacement) { _, point in if let point { placement = point } }
        .animation(reduceMotion ? nil : .snappy(duration: 0.18), value: target?.id)
        .background { InPlaceEditingShortcuts(editor: editor, canvasSize: canvasSize) }
    }

    @ViewBuilder private func bar(for element: CanvasElement) -> some View {
        switch element {
        case .text(let text):
            CanvasTextFormatBar(element: text, onChange: editor.applyTextFormat, onDelete: editor.deleteSelection)
        case .sticker(let sticker):
            CanvasStickerBar(
                sticker: sticker, catalog: editor.stickers,
                replacing: Binding(get: { editor.replacingSticker }, set: { editor.replacingSticker = $0 }),
                onReplace: editor.replaceSticker, onStraighten: editor.straightenSelection,
                onDuplicate: editor.duplicateSelection, onDelete: editor.deleteSelection
            )
        }
    }

    /// Above the element, clear of the rotation areas past its corners, or below it when there is
    /// no room above. A bar wider than the canvas centres on the element.
    private func barPosition(_ geometry: ElementFrameGeometry) -> CGPoint {
        let bounds = geometry.bounds
        let half = barSize.width / 2, height = barSize.height
        let x = barSize.width + 16 > canvasSize.width
            ? bounds.midX
            : min(max(bounds.midX, half + 8), canvasSize.width - half - 8)
        // Clear of the rotate handle above a selection; closer to text being typed, which has none.
        let clearance: CGFloat = editor.typing == nil ? 52 : 20
        let above = bounds.minY - clearance - height / 2
        let y = above - height / 2 >= visibleTop + 8 ? above : min(bounds.maxY + clearance + height / 2, visibleBottom - height / 2 - 8)
        return CGPoint(x: x, y: y)
    }
}

// MARK: Keyboard shortcuts

/// With a hardware keyboard, as on the Mac: Delete removes the selection, Return types into a text
/// or opens a sticker's Replace picker, Command-D duplicates a sticker, Esc lets go, the arrow keys
/// nudge by a point (ten with Shift), and Command-Z and Shift-Command-Z undo and redo. While
/// typing, the text takes the keys, and Esc only finishes it.
private struct InPlaceEditingShortcuts: View {
    @ObservedObject var editor: DoodleEditor
    let canvasSize: CGSize

    private var selected: Bool { editor.typing == nil && editor.selectedElement != nil }

    var body: some View {
        ZStack {
            shortcut("Delete", .delete, enabled: selected) { editor.deleteSelection() }
            shortcut("Edit", .return, enabled: selected) {
                switch editor.selectedElement {
                case .text(let text): editor.beginTyping(text)
                case .sticker: editor.replacingSticker = true
                case nil: break
                }
            }
            shortcut("Duplicate", "d", modifiers: .command, enabled: selected && editor.selectedElement?.sticker != nil) { editor.duplicateSelection() }
            shortcut("Done", .escape, enabled: editor.typing != nil || editor.selectedElement != nil) {
                if editor.typing != nil { editor.finishTyping(keepSelection: true) } else { editor.select(nil) }
            }
            shortcut("Undo", "z", modifiers: .command, enabled: editor.typing == nil && editor.canUndo) { editor.undo() }
            shortcut("Redo", "z", modifiers: [.command, .shift], enabled: editor.typing == nil && editor.canRedo) { editor.redo() }
            ForEach([EventModifiers(), .shift], id: \.rawValue) { modifiers in
                let step: CGFloat = modifiers == .shift ? 10 : 1
                shortcut("Left", .leftArrow, modifiers: modifiers, enabled: selected) { nudge(-step, 0) }
                shortcut("Right", .rightArrow, modifiers: modifiers, enabled: selected) { nudge(step, 0) }
                shortcut("Up", .upArrow, modifiers: modifiers, enabled: selected) { nudge(0, -step) }
                shortcut("Down", .downArrow, modifiers: modifiers, enabled: selected) { nudge(0, step) }
            }
        }
        .frame(width: 0, height: 0)
        .opacity(0)
        .accessibilityHidden(true)
    }

    private func nudge(_ dx: CGFloat, _ dy: CGFloat) {
        editor.nudgeSelection(dx: dx, dy: dy, canvasSize: canvasSize)
    }

    private func shortcut(_ title: String, _ key: KeyEquivalent, modifiers: EventModifiers = [], enabled: Bool,
                          action: @escaping () -> Void) -> some View {
        Button(title, action: action)
            .keyboardShortcut(key, modifiers: modifiers)
            .disabled(!enabled)
    }
}

// MARK: Text bar

/// The style of the selected or typed text, floating above it, as on the Mac: font, size, colour,
/// alignment, background and delete, at a size fingers can use.
struct CanvasTextFormatBar: View {
    let element: CanvasTextElement
    let onChange: (CanvasTextElement) -> Void
    let onDelete: () -> Void

    @State private var colourOpen = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Full where it fits. Narrower canvases drop the dividers first, then trade the font's name
    /// for Aa and the three alignments for one that cycles.
    var body: some View {
        ViewThatFits(in: .horizontal) {
            bar(compact: false, dividers: true)
            bar(compact: false, dividers: false)
            bar(compact: true, dividers: false)
        }
        .animation(reduceMotion ? nil : .smooth(duration: 0.25), value: element.font)
        .sensoryFeedback(.selection, trigger: element.font)
        .sensoryFeedback(.selection, trigger: element.alignment)
        .sensoryFeedback(.selection, trigger: element.colorArgb)
    }

    private func bar(compact: Bool, dividers: Bool) -> some View {
        HStack(spacing: 2) {
            fontMenu(compact: compact)
            if dividers { divider }
            BarButton(title: "Smaller", symbol: "minus", enabled: element.scale > CanvasTextElement.scaleRange.lowerBound) { resize(by: 1 / 1.1) }
            Text("\(points)")
                .font(.system(size: 15, weight: .medium))
                .monospacedDigit()
                .contentTransition(.numericText(value: Double(points)))
                .frame(minWidth: 30)
                .accessibilityLabel("Size \(points) points")
            BarButton(title: "Larger", symbol: "plus", enabled: element.scale < CanvasTextElement.scaleRange.upperBound) { resize(by: 1.1) }
            if dividers { divider }
            colourButton
            if compact {
                BarButton(title: "Alignment", symbol: alignmentSymbol(element.alignment)) { set(\.alignment, nextAlignment) }
            } else {
                ForEach([CanvasTextAlign.left, .center, .right], id: \.self) { value in
                    BarButton(title: alignmentTitle(value), symbol: alignmentSymbol(value), selected: element.alignment == value) { set(\.alignment, value) }
                }
            }
            BarButton(title: "Background", symbol: element.backgroundPillEnabled ? "a.square.fill" : "a.square",
                      selected: element.backgroundPillEnabled) { set(\.backgroundPillEnabled, !element.backgroundPillEnabled) }
            if dividers { divider }
            BarButton(title: "Delete", symbol: "trash", action: onDelete)
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 4)
        .glassEffect(.regular, in: .capsule)
        .fixedSize()
    }

    /// Text size in points at the canvas's own scale, 34 being the default.
    private var points: Int { Int((CanvasTextStyle.baseSize * element.scale).rounded()) }

    private var divider: some View { Divider().frame(height: 22).padding(.horizontal, 4) }

    private func fontMenu(compact: Bool) -> some View {
        Menu {
            Picker("Font", selection: Binding(get: { element.font }, set: { set(\.font, $0) })) {
                ForEach(CanvasTextFont.allCases, id: \.self) { font in
                    Text(CanvasTextStyle.displayName(font)).tag(font)
                }
            }
        } label: {
            Group {
                if compact {
                    Text("Aa").frame(width: 44, height: 44)
                } else {
                    HStack(spacing: 6) {
                        Text(CanvasTextStyle.displayName(element.font))
                            .lineLimit(1)
                            .frame(maxWidth: 96, alignment: .leading)
                        Image(systemName: "chevron.down").font(.system(size: 11, weight: .semibold)).foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 12)
                    .frame(height: 44)
                }
            }
            .font(.custom(CanvasTextStyle.postScriptName(element.font), fixedSize: 17))
            .foregroundStyle(MulberryDesign.ink)
            .contentShape(.capsule)
        }
        .menuIndicator(.hidden)
        .accessibilityLabel("Font: \(CanvasTextStyle.displayName(element.font))")
    }

    private var colourButton: some View {
        Button { colourOpen = true } label: {
            ColourPaletteIcon(color: element.colorArgb, size: 24)
                .frame(width: 44, height: 44)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Text colour")
        .popover(isPresented: $colourOpen, arrowEdge: .bottom) {
            TextColourPalette(selected: element.colorArgb) { colour in
                set(\.colorArgb, colour)
            } done: { colourOpen = false }
            .presentationCompactAdaptation(.popover)
        }
    }

    private var nextAlignment: CanvasTextAlign {
        switch element.alignment {
        case .left: .center
        case .center: .right
        case .right: .left
        }
    }

    private func alignmentSymbol(_ value: CanvasTextAlign) -> String {
        switch value {
        case .left: "text.alignleft"
        case .center: "text.aligncenter"
        case .right: "text.alignright"
        }
    }

    private func alignmentTitle(_ value: CanvasTextAlign) -> String {
        switch value {
        case .left: "Align left"
        case .center: "Centre"
        case .right: "Align right"
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

/// Every colour in a grid, then the custom colour well, in a popover from the text bar.
private struct TextColourPalette: View {
    let selected: UInt32
    let pick: (UInt32) -> Void
    let done: () -> Void
    @State private var custom: UInt32
    @State private var customOpen = false

    init(selected: UInt32, pick: @escaping (UInt32) -> Void, done: @escaping () -> Void) {
        self.selected = selected; self.pick = pick; self.done = done
        _custom = State(initialValue: selected)
    }

    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.fixed(44), spacing: 12), count: 5), spacing: 12) {
            ForEach(DoodleEditor.palette, id: \.self) { colour in
                Button { pick(colour); done() } label: {
                    Circle().fill(Color(uiColor: UIColor(argb: colour))).frame(width: 40, height: 40)
                        .overlay(Circle().stroke(MulberryDesign.muted.opacity(0.3)))
                        .padding(2)
                        .overlay { if colour == selected { Circle().strokeBorder(MulberryDesign.ink, lineWidth: 2) } }
                        .frame(width: 44, height: 44)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(ColourNames.name(colour))
                .accessibilityAddTraits(selected == colour ? .isSelected : [])
            }
            Button { customOpen = true } label: {
                CustomColourWell(color: selected)
                    .frame(width: 28, height: 28).scaleEffect(1.43)
                    .frame(width: 44, height: 44)
                    .contentShape(Circle())
                    .accessibilityHidden(true)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Custom colour")
        }
        .padding(20)
        .sheet(isPresented: $customOpen) {
            CustomColourSheet(color: $custom) {
                pick(custom)
                customOpen = false
                done()
            }
        }
    }
}

// MARK: Sticker bar and picker

/// The selected sticker's actions, floating above it: replace the artwork, straighten it when
/// tilted, duplicate it, delete it.
struct CanvasStickerBar: View {
    let sticker: CanvasStickerElement
    @ObservedObject var catalog: StickerCatalog
    @Binding var replacing: Bool
    let onReplace: (StickerReference) -> Void
    let onStraighten: () -> Void
    let onDuplicate: () -> Void
    let onDelete: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var tilted: Bool { abs(sticker.rotationRad.remainder(dividingBy: 2 * .pi)) > 0.001 }

    var body: some View {
        HStack(spacing: 2) {
            Button { replacing.toggle() } label: {
                Label("Replace", systemImage: "arrow.triangle.2.circlepath")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(MulberryDesign.ink)
                    .padding(.horizontal, 12)
                    .frame(height: 44)
                    .contentShape(.capsule)
            }
            .buttonStyle(.plain)
            .popover(isPresented: $replacing, arrowEdge: .bottom) {
                StickerPickerPanel(catalog: catalog, current: sticker.sticker, pick: onReplace)
                    .presentationCompactAdaptation(.popover)
            }
            if tilted {
                BarButton(title: "Straighten", symbol: "level", action: onStraighten)
                    .transition(.scale(scale: 0.6).combined(with: .opacity))
            }
            BarButton(title: "Duplicate", symbol: "plus.square.on.square", action: onDuplicate)
            Divider().frame(height: 22).padding(.horizontal, 4)
            BarButton(title: "Delete", symbol: "trash", action: onDelete)
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 4)
        .glassEffect(.regular, in: .capsule)
        .fixedSize()
        .animation(reduceMotion ? nil : .smooth(duration: 0.2), value: tilted)
    }
}

/// The published packs: covers along the top, the chosen pack's stickers in a grid below. Tap a
/// sticker to pick it, or drag it out onto the canvas to place it there.
struct StickerPickerPanel: View {
    @ObservedObject var catalog: StickerCatalog
    /// The sticker being replaced, ringed in the grid.
    var current: StickerReference?
    let pick: (StickerReference) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 8) {
                Text(catalog.selected?.title ?? "Stickers")
                    .font(MulberryDesign.font(17, weight: "SemiBold"))
                    .lineLimit(1)
                Spacer(minLength: 0)
                if catalog.loading && !catalog.packs.isEmpty { ProgressView().controlSize(.small) }
            }
            if let error = catalog.errorMessage {
                HStack(spacing: 10) {
                    Text(error).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                    Spacer(minLength: 0)
                    Button("Retry") { catalog.load(force: true) }.buttonStyle(.glass).controlSize(.small)
                }
            }
            if catalog.packs.count > 1 { packRow }
            grid
        }
        .padding(18)
        .frame(width: 380)
        .onAppear { catalog.load() }
    }

    private var packRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(catalog.packs) { pack in
                    let selected = catalog.selected?.id == pack.id
                    Button { catalog.select(pack) } label: {
                        StickerArtwork(sticker: .init(packKey: pack.packKey, packVersion: pack.packVersion, stickerId: StickerAssetStore.coverID),
                                       user: catalog.user, hint: pack.coverThumbnailUrl, maxPixelSize: 168)
                            .padding(6)
                            .frame(width: 52, height: 52)
                            .background(.primary.opacity(selected ? 0.12 : 0.04), in: .rect(cornerRadius: 14))
                            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(selected ? MulberryDesign.accent : .primary.opacity(0.06), lineWidth: selected ? 2 : 1))
                            .contentShape(.rect(cornerRadius: 14))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(pack.title)
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
        }
        .scrollClipDisabled()
    }

    @ViewBuilder private var grid: some View {
        if let pack = catalog.selected {
            ScrollView {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 8) {
                    let stickers = pack.stickers.sorted { $0.sortOrder < $1.sortOrder }
                    ForEach(Array(stickers.enumerated()), id: \.element.id) { index, sticker in
                        let reference = StickerReference(packKey: pack.packKey, packVersion: pack.packVersion, stickerId: sticker.stickerId)
                        let selected = current == reference
                        Button { pick(reference) } label: {
                            StickerArtwork(sticker: reference, user: catalog.user, hint: sticker.thumbnailUrl, maxPixelSize: 256)
                                .padding(6)
                                .aspectRatio(1, contentMode: .fit)
                                .background(.primary.opacity(selected ? 0.14 : 0.04), in: .rect(cornerRadius: 14))
                                .overlay { if selected { RoundedRectangle(cornerRadius: 14).strokeBorder(MulberryDesign.accent, lineWidth: 2) } }
                                .contentShape(.rect(cornerRadius: 14))
                        }
                        .buttonStyle(.plain)
                        .draggable(reference.dragString) {
                            StickerArtwork(sticker: reference, user: catalog.user, hint: sticker.thumbnailUrl, maxPixelSize: 256)
                                .frame(width: 96, height: 96)
                        }
                        // Stickers have no names, so VoiceOver reads the pack and the place in it.
                        .accessibilityLabel("\(pack.title), sticker \(index + 1) of \(stickers.count)")
                        .accessibilityAddTraits(selected ? .isSelected : [])
                    }
                }
            }
            .frame(height: 300)
            .scrollIndicators(.hidden)
        } else {
            Color.clear.frame(height: 300)
                .overlay { if catalog.loading { ProgressView() } }
        }
    }
}

/// A bar's icon button: 44 points square, softly filled when its option is on.
private struct BarButton: View {
    let title: String
    let symbol: String
    var selected = false
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(MulberryDesign.ink)
                .frame(width: 44, height: 44)
                .background(Circle().fill(MulberryDesign.ink.opacity(selected ? 0.12 : 0)).padding(2))
                .contentShape(Circle())
                .opacity(enabled ? 1 : 0.35)
        }
        .buttonStyle(.plain)
        .hoverEffect(.highlight)
        .disabled(!enabled)
        .accessibilityLabel(title)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

// MARK: Typing in place

/// The text being typed, drawn by UIKit's text view at the element's size, wrap width, rotation and
/// colours, with the background pill behind it, laid out by TextKit 1 as the canvas renderer lays
/// it out. Touches outside it fall through to the canvas. Scribble writes into it with the Pencil.
struct InlineCanvasTextEditor: UIViewRepresentable {
    @Binding var draft: CanvasTextElement
    let canvasSize: CGSize
    /// Esc, or putting the keyboard away: stop typing and keep the text selected.
    let onFinish: () -> Void

    func makeUIView(context: Context) -> InlineTextHostView {
        let host = InlineTextHostView()
        host.textView.delegate = context.coordinator
        host.textView.text = draft.text
        host.textView.onEscape = { context.coordinator.parent.onFinish() }
        host.textView.accessibilityLabel = "Text"
        DispatchQueue.main.async {
            host.textView.becomeFirstResponder()
            host.textView.selectedRange = NSRange(location: (host.textView.text as NSString).length, length: 0)
        }
        return host
    }

    func updateUIView(_ host: InlineTextHostView, context: Context) {
        context.coordinator.parent = self
        host.apply(draft, canvasSize: canvasSize)
    }

    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }

    @MainActor
    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: InlineCanvasTextEditor

        init(parent: InlineCanvasTextEditor) { self.parent = parent }

        func textViewDidChange(_ textView: UITextView) {
            parent.draft.text = textView.text
        }

        func textViewDidEndEditing(_ textView: UITextView) {
            parent.onFinish()
        }
    }
}

final class InlineTextHostView: UIView {
    let container = InlineTextContainerView()
    var textView: InlineTextView { container.textView }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        addSubview(container)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    /// Only the text itself takes touches; a tap elsewhere finishes typing on the canvas.
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard let hit = super.hitTest(point, with: event), hit !== self else { return nil }
        return hit
    }

    func apply(_ element: CanvasTextElement, canvasSize: CGSize) {
        let scale = CGFloat(element.scale)
        container.apply(element, wrap: max(1, CGFloat(element.boxWidth) * canvasSize.width) * scale, scale: scale)
        // Rotate about the element's centre, as the renderer does: place upright, then turn.
        container.transform = .identity
        container.bounds = CGRect(origin: .zero, size: container.fittingSize)
        container.center = CGPoint(x: element.center.x * canvasSize.width, y: element.center.y * canvasSize.height)
        container.transform = CGAffineTransform(rotationAngle: element.rotationRad)
    }
}

/// The text view inside a margin wide enough for the background pill, which it draws itself.
final class InlineTextContainerView: UIView {
    let textView: InlineTextView
    private var pill: (color: UIColor, radius: CGFloat)?
    private var padding: CGFloat = 0
    private var textSize = CGSize.zero

    override init(frame: CGRect) {
        // TextKit 1, the same layout engine the canvas renderer draws with.
        let storage = NSTextStorage()
        let manager = NSLayoutManager()
        let textContainer = NSTextContainer(size: CGSize(width: 100, height: CGFloat.greatestFiniteMagnitude))
        textContainer.lineFragmentPadding = 0
        textContainer.widthTracksTextView = false
        manager.addTextContainer(textContainer)
        storage.addLayoutManager(manager)
        textView = InlineTextView(frame: .zero, textContainer: textContainer)
        textView.backgroundColor = .clear
        textView.isScrollEnabled = false
        textView.textContainerInset = .zero
        textView.smartQuotesType = .no
        textView.smartDashesType = .no
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
        contentMode = .redraw
        addSubview(textView)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    var fittingSize: CGSize { CGSize(width: textSize.width + padding * 2, height: textSize.height + padding * 2) }

    func apply(_ element: CanvasTextElement, wrap: CGFloat, scale: CGFloat) {
        let font = CanvasTextStyle.font(element.font, size: CanvasTextStyle.baseSize * scale)
        let colour = UIColor(argb: CanvasTextStyle.textColor(element))
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = CanvasTextStyle.alignment(element.alignment)
        let attributes: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: colour, .paragraphStyle: paragraph]
        let storage = textView.textStorage
        storage.setAttributes(attributes, range: NSRange(location: 0, length: storage.length))
        textView.typingAttributes = attributes
        textView.tintColor = colour

        padding = element.backgroundPillEnabled ? CanvasTextStyle.pillPadding * scale : 2
        pill = element.backgroundPillEnabled ? (UIColor(argb: element.colorArgb), CanvasTextStyle.pillCornerRadius * scale) : nil

        let manager = textView.layoutManager, textContainer = textView.textContainer
        textContainer.size = CGSize(width: wrap, height: .greatestFiniteMagnitude)
        manager.ensureLayout(for: textContainer)
        let used = manager.usedRect(for: textContainer)
        textSize = CGSize(width: wrap, height: max(font.lineHeight, ceil(used.height)))
        textView.frame = CGRect(x: padding, y: padding, width: textSize.width, height: textSize.height)
        setNeedsDisplay()
    }

    /// The pill hugs the typed lines, as the renderer's tight bounds do.
    override func draw(_ rect: CGRect) {
        guard let pill else { return }
        let manager = textView.layoutManager, textContainer = textView.textContainer
        var left = CGFloat.infinity, right = -CGFloat.infinity
        manager.enumerateLineFragments(forGlyphRange: manager.glyphRange(for: textContainer)) { _, used, _, _, _ in
            left = min(left, used.minX)
            right = max(right, used.maxX)
        }
        if !(left.isFinite && right > left) {
            // Empty: a narrow pill around the caret.
            let alignment = (textView.typingAttributes[.paragraphStyle] as? NSParagraphStyle)?.alignment ?? .center
            let caret = alignment == .left ? 0 : alignment == .right ? textSize.width : textSize.width / 2
            left = caret - 4
            right = caret + 4
        }
        let body = CGRect(x: padding + left, y: padding, width: right - left, height: textSize.height)
            .insetBy(dx: -padding, dy: -padding)
        pill.color.setFill()
        UIBezierPath(roundedRect: body, cornerRadius: pill.radius).fill()
    }
}

/// The inline text view, which lets Esc on a hardware keyboard finish typing.
final class InlineTextView: UITextView {
    var onEscape: (() -> Void)?

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        if presses.contains(where: { $0.key?.keyCode == .keyboardEscape }) {
            onEscape?()
            return
        }
        super.pressesBegan(presses, with: event)
    }
}
