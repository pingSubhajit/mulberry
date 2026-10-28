import SwiftUI

/// Android's TextEditorOverlay: the text is edited in place over a dimmed,
/// blurred canvas, with its style controls docked above the keyboard.
struct CanvasTextEditorView: View {
    let palette: [UInt32]
    let onDone: (CanvasTextElement) -> Void
    let onDelete: () -> Void

    @State private var draft: CanvasTextElement
    @State private var rotationDegrees: Double
    private let initialDegrees: Double
    @State private var panel: Panel?
    @State private var shown = false
    @State private var closing = false
    @State private var customColourOpen = false
    @State private var availableWidth: CGFloat = 0
    @FocusState private var focused: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    enum Panel { case font, size, rotation, colour }

    init(session: CanvasTextSession, palette: [UInt32], panel: Panel? = nil,
         onDone: @escaping (CanvasTextElement) -> Void, onDelete: @escaping () -> Void) {
        self.palette = palette; self.onDone = onDone; self.onDelete = onDelete
        _panel = State(initialValue: panel)
        var element = session.element
        element.scale = min(CanvasTextElement.scaleRange.upperBound, max(CanvasTextElement.scaleRange.lowerBound, element.scale))
        _draft = State(initialValue: element)
        // Gestures can leave any angle; the slider shows the same angle in -180...180.
        initialDegrees = CanvasEditorRotationPanel.degrees(fromRadians: element.rotationRad)
        _rotationDegrees = State(initialValue: initialDegrees)
    }

    var body: some View {
        ZStack {
            CanvasEditorBackdrop(shown: shown, onTap: finish)
                .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { availableWidth = $0 }
            textField
                .padding(.horizontal, 28)
                .opacity(shown ? 1 : 0)
                .scaleEffect(shown || reduceMotion ? 1 : 0.98)
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            CanvasEditorDoneButton(identifier: "textEditorDone", action: finish).opacity(shown ? 1 : 0)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            controls.opacity(shown ? 1 : 0)
        }
        .environment(\.colorScheme, .dark)
        .presentationBackground(.clear)
        .onAppear {
            focused = true
            withAnimation(reduceMotion ? nil : .easeOut(duration: 0.12)) { shown = true }
        }
        .sheet(isPresented: $customColourOpen, onDismiss: { focused = true }) {
            CustomColourSheet(color: $draft.colorArgb) { customColourOpen = false }
        }
    }

    // MARK: Canvas text

    private var textColor: Color { Color(uiColor: UIColor(argb: CanvasTextStyle.textColor(draft))) }
    private var elementColor: Color { Color(uiColor: UIColor(argb: draft.colorArgb)) }

    private var textAlignment: TextAlignment {
        switch draft.alignment {
        case .left: .leading
        case .center: .center
        case .right: .trailing
        }
    }

    private var textField: some View {
        // Android previews size by scaling the font, keeping the wrap width.
        TextField("", text: $draft.text,
                  prompt: Text("Type something").font(MulberryDesign.font(28)).foregroundStyle(.white.opacity(0.45)),
                  axis: .vertical)
            .font(.custom(CanvasTextStyle.postScriptName(draft.font), fixedSize: fontSize))
            .foregroundStyle(textColor)
            .tint(textColor)
            .multilineTextAlignment(textAlignment)
            .lineLimit(1...10)
            .focused($focused)
            .accessibilityLabel("Text")
            .accessibilityIdentifier("canvasTextField")
            .frame(width: fieldWidth)
            .padding(.horizontal, draft.backgroundPillEnabled ? 18 : 0)
            .padding(.vertical, draft.backgroundPillEnabled ? 14 : 0)
            .background {
                if draft.backgroundPillEnabled { RoundedRectangle(cornerRadius: 24).fill(elementColor) }
            }
            .rotationEffect(.degrees(rotationDegrees))
            .animation(reduceMotion ? nil : .snappy(duration: 0.2), value: draft.backgroundPillEnabled)
    }

    private var fontSize: CGFloat { min(160, max(12, CanvasTextStyle.baseSize * draft.scale)) }

    /// Android's wrap width, narrowed to the longest line so the pill hugs
    /// the text exactly as it will on the canvas.
    private var fieldWidth: CGFloat {
        let wrap = max(44, (availableWidth - 56) * min(0.95, max(0.35, draft.boxWidth)))
        let measured = draft.text.isEmpty
            ? NSString(string: "Type something").size(withAttributes: [.font: UIFont(name: "Poppins-Regular", size: 28) ?? .systemFont(ofSize: 28)]).width
            : NSString(string: draft.text).boundingRect(with: CGSize(width: wrap, height: .greatestFiniteMagnitude), options: .usesLineFragmentOrigin,
                                                        attributes: [.font: CanvasTextStyle.uiFont(draft.font, size: fontSize)], context: nil).width
        // Leave room for the caret at the end of the longest line.
        return min(wrap, ceil(measured) + 6)
    }

    // MARK: Controls

    private var controls: some View {
        VStack(spacing: 10) {
            if let panel {
                Group {
                    switch panel {
                    case .font: fontPanel
                    case .size: sizePanel
                    case .rotation: rotationPanel
                    case .colour: colourPanel
                    }
                }
                .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
            }
            GlassEffectContainer(spacing: 6) {
                HStack(spacing: 6) {
                    panelButton(.font, title: "Font", symbol: "textformat")
                    panelButton(.size, title: "Size", symbol: "textformat.size")
                    panelButton(.rotation, title: "Rotation", symbol: "rotate.right")
                    colourButton
                    CanvasEditorButton("Alignment", symbol: alignmentSymbol) { cycleAlignment() }
                        .accessibilityValue(alignmentName)
                    Spacer(minLength: 0)
                    CanvasEditorButton("Background", symbol: draft.backgroundPillEnabled ? "a.square.fill" : "a.square",
                                       selected: draft.backgroundPillEnabled) { draft.backgroundPillEnabled.toggle() }
                    CanvasEditorButton.delete(action: delete)
                }
            }
        }
        .padding(.horizontal, 12).padding(.bottom, 8)
        .animation(reduceMotion ? nil : .snappy(duration: 0.25), value: panel)
        .sensoryFeedback(.selection, trigger: draft.font)
        .sensoryFeedback(.selection, trigger: draft.alignment)
    }

    private func panelButton(_ target: Panel, title: String, symbol: String) -> some View {
        CanvasEditorButton(title, symbol: symbol, selected: panel == target) { panel = panel == target ? nil : target }
    }

    private var colourButton: some View {
        CanvasEditorButton(title: "Colour", selected: panel == .colour, action: { panel = panel == .colour ? nil : .colour }) {
            Circle().fill(elementColor)
                .padding(4)
                .background(Circle().fill(AngularGradient(colors: [.red, .yellow, .green, .cyan, .blue, .purple, .red], center: .center)))
                .frame(width: 24, height: 24)
        }
    }

    private var fontPanel: some View {
        ScrollViewReader { reader in
            ScrollView(.horizontal, showsIndicators: false) {
                GlassEffectContainer(spacing: 8) {
                    HStack(spacing: 8) {
                        ForEach(CanvasTextFont.allCases, id: \.self) { font in
                            let selected = draft.font == font
                            Button { draft.font = font } label: {
                                Text(CanvasTextStyle.displayName(font))
                                    .font(.custom(CanvasTextStyle.postScriptName(font), fixedSize: 15))
                                    .foregroundStyle(.white)
                                    .lineLimit(1)
                                    .padding(.horizontal, 16)
                                    .frame(minHeight: 40)
                                    .contentShape(Capsule())
                            }
                            .buttonStyle(.plain)
                            .glassEffect(.regular.tint(selected ? MulberryDesign.accent.opacity(0.35) : .clear).interactive(), in: .capsule)
                            .overlay {
                                if selected { Capsule().strokeBorder(MulberryDesign.accent, lineWidth: 2).allowsHitTesting(false) }
                            }
                            .accessibilityAddTraits(selected ? .isSelected : [])
                            .id(font)
                        }
                    }
                    .padding(.horizontal, 4).padding(.vertical, 2)
                }
            }
            .scrollClipDisabled()
            .onAppear { reader.scrollTo(draft.font, anchor: .center) }
        }
    }

    private var sizePanel: some View {
        CanvasEditorSliderPanel {
            Text("Aa").font(MulberryDesign.font(12, weight: "SemiBold")).accessibilityHidden(true)
            Slider(value: $draft.scale, in: CanvasTextElement.scaleRange)
                .accessibilityLabel("Size")
                .accessibilityValue("\(Int((draft.scale * 100).rounded())) percent")
            Text("Aa").font(MulberryDesign.font(18, weight: "SemiBold")).accessibilityHidden(true)
        }
    }

    private var rotationPanel: some View { CanvasEditorRotationPanel(degrees: $rotationDegrees) }

    private var colourPanel: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(palette, id: \.self) { colour in
                    let selected = draft.colorArgb == colour
                    Button { draft.colorArgb = colour } label: {
                        Circle().fill(Color(uiColor: UIColor(argb: colour)))
                            .frame(width: 36, height: 36)
                            .overlay(Circle().strokeBorder(selected ? MulberryDesign.accent : .white.opacity(0.25), lineWidth: selected ? 2.5 : 1))
                            .frame(width: 44, height: 44)
                            .contentShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Colour \(String(format: "%06X", colour & 0xFFFFFF))")
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
                Button { customColourOpen = true } label: {
                    CustomColourWell(color: draft.colorArgb)
                        .frame(width: 28, height: 28)
                        .scaleEffect(36 / 28)
                        .frame(width: 44, height: 44)
                        .overlay {
                            if !palette.contains(draft.colorArgb) {
                                Circle().strokeBorder(MulberryDesign.accent, lineWidth: 2.5).frame(width: 40, height: 40)
                            }
                        }
                        .contentShape(Circle())
                        .accessibilityHidden(true)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Custom colour")
                .accessibilityAddTraits(palette.contains(draft.colorArgb) ? [] : .isSelected)
            }
            .padding(.horizontal, 4)
        }
        .scrollClipDisabled()
        .sensoryFeedback(.selection, trigger: draft.colorArgb)
    }

    private var alignmentSymbol: String {
        switch draft.alignment {
        case .left: "text.alignleft"
        case .center: "text.aligncenter"
        case .right: "text.alignright"
        }
    }

    private var alignmentName: String {
        switch draft.alignment {
        case .left: "Left"
        case .center: "Centre"
        case .right: "Right"
        }
    }

    private func cycleAlignment() {
        switch draft.alignment {
        case .left: draft.alignment = .center
        case .center: draft.alignment = .right
        case .right: draft.alignment = .left
        }
    }

    // MARK: Exit

    private func finish() {
        var element = draft
        // An untouched slider keeps the exact angle, so an unchanged edit saves nothing.
        if rotationDegrees != initialDegrees { element.rotationRad = rotationDegrees * .pi / 180 }
        close { onDone(element) }
    }

    private func delete() { close(onDelete) }

    private func close(_ completion: @escaping () -> Void) {
        guard !closing else { return }
        closing = true; focused = false
        guard !reduceMotion else { return completion() }
        withAnimation(.easeIn(duration: 0.1)) { shown = false } completion: { completion() }
    }
}
