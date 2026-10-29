import SwiftUI

struct DoodleCanvasView: View {
    @ObservedObject var model: MulberryModel
    @StateObject private var editor: DoodleEditor
    @State private var widthOpen = false
    @State private var paletteOpen = false
    @State private var customColourOpen = false
    @State private var paletteHeight: CGFloat = 280
    @State private var clearConfirmation = false
    @State private var toolbarFrame = CGRect.zero
    /// The floating palette a Pencil squeeze or double-tap asked for, at the Pencil's tip.
    @State private var pencilPalette: PencilRequest?
    /// Wider than tall: on iPad the colours then have their own column instead of a palette button.
    @State private var wideWindow = false
    /// In wide iPad windows, the colour column folds into a colour button on the tool column.
    @AppStorage("canvasColourRailCollapsed") private var colourRailCollapsed = false
    /// Lets the colour column's glass melt into the colour button and back.
    @Namespace private var glassSpace
    /// Where a squeeze opened the radial palette, in the canvas screen's coordinates.
    @State private var radialAt: CGPoint?
    /// The drawing surface's frame on the canvas screen, for placing Pencil palettes at the tip.
    @State private var surfaceFrame = CGRect.zero
    private static let pencilSpace = "pencilSpace"
    private static let space = "canvasScreen"
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(model: MulberryModel) {
        self.model = model
        _editor = StateObject(wrappedValue: DoodleEditor(model: model))
    }

    /// Strokes are stored relative to the drawing area's width and height, so it keeps the shape it had
    /// when inset 24 pt at the sides and 28 pt in all vertically, scaled up to span the full width.
    static func canvasSize(in slot: CGSize) -> CGSize {
        let inset = CGSize(width: slot.width - 48, height: slot.height - 28)
        guard inset.width > 0, inset.height > 0 else { return slot }
        return CGSize(width: slot.width, height: inset.height * slot.width / inset.width)
    }

    /// The navigation bar lies between the status bar and the top of this view.
    private static func navigationBarHeight(contentTop: CGFloat) -> CGFloat {
        let window = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first
        return max(0, contentTop - (window?.safeAreaInsets.top ?? contentTop))
    }

    /// The canvas stands in for a phone wallpaper, so a wide window keeps that shape rather than
    /// filling the space. It is within a few percent of the iPhone canvas, so drawings match on both.
    static let tabletAspect: CGFloat = 9 / 16

    var body: some View {
        GeometryReader { geometry in
            Group {
                if sizeClass == .regular { regularLayout(geometry) } else { compactLayout(geometry) }
            }
            .fullScreenCover(item: $editor.stickerSession) { session in
                CanvasStickerEditorView(session: session, catalog: editor.stickers,
                                        onDone: editor.commitSticker, onDelete: editor.deleteEditingSticker)
            }
            .coordinateSpace(.named(Self.pencilSpace))
            // A tap beside the canvas puts the palette away too.
            .background { if radialAt != nil { Color.clear.contentShape(.rect).onTapGesture { closeRadial() } } }
            .overlay { radialPalette(in: geometry.size) }
            .animation(reduceMotion ? nil : .spring(duration: 0.22, bounce: 0.15), value: radialAt)
        }
        .onGeometryChange(for: Bool.self) { $0.size.width > $0.size.height } action: { wideWindow = $0 }
        .onChange(of: editor.pencilRequest) { _, request in
            guard let request else { return }
            switch request.kind {
            case .inkAttributes: widthOpen = true
            // With every colour already in its column, the floating palette stands in for the palette button.
            case .colours where !(sizeClass == .regular && wideWindow): paletteOpen = true
            case .colours: pencilPalette = request
            case .palette:
                // A second squeeze closes it, as with the system palette.
                guard radialAt == nil else { closeRadial(); return }
                radialAt = request.location.map { CGPoint(x: surfaceFrame.minX + $0.x, y: surfaceFrame.minY + $0.y) }
                    ?? CGPoint(x: surfaceFrame.midX, y: surfaceFrame.midY)
            }
        }
        // Touching the canvas, or choosing a tool anywhere, puts the palette away; the touch still draws.
        .onChange(of: editor.canvasTouches) { _, _ in closeRadial() }
        // After the selection highlight has moved, when the tool was chosen on the arc itself.
        .onChange(of: editor.tool) { _, _ in
            Task { try? await Task.sleep(for: .milliseconds(reduceMotion ? 0 : 100)); closeRadial() }
        }
        .background(MulberryDesign.background)
            .onChange(of: model.drawing) { _, _ in editor.receive() }
            .onChange(of: model.canvasClearVersion) { _, _ in editor.resetHistory() }
            .onChange(of: scenePhase) { _, value in
                if value != .active { editor.end(); radialAt = nil }
                else if editor.errorMessage != nil { editor.retry() }
            }
            .onDisappear { editor.end() }
            .fullScreenCover(item: $editor.textSession) { session in
                CanvasTextEditorView(session: session, palette: DoodleEditor.palette,
                                     onDone: editor.commitText, onDelete: editor.deleteEditingText)
            }
    }

    /// Phones and narrow iPad windows: the canvas spans the width and runs under both bars.
    private func compactLayout(_ geometry: GeometryProxy) -> some View {
        // Use part of the bottom safe area, while retaining space for the Home gesture.
        let bottomReach = min(16, max(0, geometry.safeAreaInsets.bottom - 16))
        let height = geometry.size.height + bottomReach
        let tools = toolbarFrame == .zero ? CGRect(x: 0, y: height - 72, width: geometry.size.width, height: 72) : toolbarFrame
        let size = Self.canvasSize(in: CGSize(width: geometry.size.width, height: tools.minY))
        let navigationMiddle = -Self.navigationBarHeight(contentTop: geometry.frame(in: .global).minY) / 2
        return ZStack(alignment: .bottom) {
            DoodleSurface(editor: editor, dry: model.bootstrap?.canvasStrokeRenderMode == "dry")
                .frame(width: size.width, height: size.height)
                .modifier(PencilPalettePopover(request: $pencilPalette) { pencilPaletteContent })
                .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(Self.pencilSpace)) } action: { surfaceFrame = $0 }
                // Centred between the middles of the navigation bar and the tools, so it runs under both.
                .position(x: geometry.size.width / 2, y: (navigationMiddle + tools.midY) / 2)
            // The visible part of the canvas, between the bars.
            CanvasBlankStateGuidance(visible: editor.strokes.isEmpty && editor.elements.isEmpty)
                .frame(height: tools.minY).frame(maxHeight: .infinity, alignment: .top)
            // Between the bars, so the banner stays clear of both.
            StreakCanvasCelebration(streaks: model.streaks) { model.showStreak = true }
                .frame(height: tools.minY).frame(maxHeight: .infinity, alignment: .top)
            // Actions stand apart from the tools: undo and redo on the left, colour and width on the right.
            VStack(spacing: 28) {
                HStack(alignment: .bottom) {
                    // Clear glass lets them blend into the canvas, apart from the tools below.
                    VStack(spacing: 10) { undoButton(glass: .clear); redoButton(glass: .clear) }
                    Spacer()
                    VStack(spacing: 10) { paletteButton(edge: .trailing, glass: .clear); widthButton(edge: .trailing, glass: .clear) }
                }
                .padding(.horizontal, 8)
                compactToolbar
                    .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(Self.space)) } action: { toolbarFrame = $0 }
            }
            .buttonBorderShape(.circle)
            .controlSize(.large)
            .frame(maxWidth: 6 * 56 + 5 * 10 + 16)
            .padding(.horizontal, 8)
        }
        .frame(width: geometry.size.width, height: height, alignment: .top)
        .coordinateSpace(.named(Self.space))
    }

    /// Full iPad windows: a phone-shaped canvas running the full height of the screen, behind the
    /// navigation bar and the tools. Taller than wide, the tools sit beneath it. Wider than tall,
    /// the window splits in two: the tools and a column of every colour on the left, the canvas on the right.
    private func regularLayout(_ geometry: GeometryProxy) -> some View {
        let wide = geometry.size.width > geometry.size.height
        let margin: CGFloat = 24, gap: CGFloat = 24, toolDepth: CGFloat = 72
        // The left panel: the tool column, then the colour column (a 44 pt swatch with 8 pt padding).
        let panelWidth: CGFloat = toolDepth + 12 + 60
        let screenTop = -geometry.frame(in: .global).minY
        let screenBottom = geometry.size.height + geometry.safeAreaInsets.bottom
        // Like the phone, the tools use part of the bottom safe area while leaving the Home gesture room.
        let toolsBottom = geometry.size.height + min(16, max(0, geometry.safeAreaInsets.bottom - 16))
        let toolsMiddle = toolsBottom - toolDepth / 2
        let span = screenBottom - screenTop
        let canvasLeft = wide ? margin + panelWidth + gap : margin
        let roomWidth = geometry.size.width - margin - canvasLeft
        let height = min(span, roomWidth / Self.tabletAspect)
        let size = CGSize(width: height * Self.tabletAspect, height: height)
        let center = CGPoint(x: canvasLeft + roomWidth / 2, y: (screenTop + screenBottom) / 2)
        // The part of the canvas below the navigation bar and clear of the tools, where the prompt
        // and the streak banner belong.
        let visibleTop = max(0, center.y - height / 2)
        let visibleBottom = min(wide ? geometry.size.height : toolsBottom - toolDepth, center.y + height / 2)
        return ZStack(alignment: .topLeading) {
            // Transparent and unframed, like the phone's: drawings sit straight on the screen.
            DoodleSurface(editor: editor, dry: model.bootstrap?.canvasStrokeRenderMode == "dry")
                .frame(width: size.width, height: size.height)
                .modifier(PencilPalettePopover(request: $pencilPalette) { pencilPaletteContent })
                .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(Self.pencilSpace)) } action: { surfaceFrame = $0 }
                .position(center)
            Group {
                CanvasBlankStateGuidance(visible: editor.strokes.isEmpty && editor.elements.isEmpty)
                    .padding(.bottom, 16)
                StreakCanvasCelebration(streaks: model.streaks) { model.showStreak = true }
            }
            .frame(width: size.width, height: max(0, visibleBottom - visibleTop))
            .position(x: center.x, y: (visibleTop + visibleBottom) / 2)
            if wide {
                // One glass container, so the colour column folds into the colour button as a single
                // piece of glass, and the button unfolds back into the column.
                GlassEffectContainer(spacing: 4) {
                    HStack(alignment: .center, spacing: 12) {
                        toolColumn(palette: colourRailCollapsed)
                        if !colourRailCollapsed { colourColumn }
                    }
                }
                .animation(railAnimation, value: colourRailCollapsed)
                // The canvas keeps its place when the colours fold away.
                .frame(width: panelWidth, height: geometry.size.height - margin * 2, alignment: .leading)
                .position(x: margin + panelWidth / 2, y: geometry.size.height / 2)
            } else {
                toolbar(.horizontal)
                    .frame(maxWidth: roomWidth)
                    .position(x: geometry.size.width / 2, y: toolsMiddle)
            }
        }
        .frame(width: geometry.size.width, height: geometry.size.height)
    }

    /// Every colour at once beside the tools, in place of the palette button. Scrolls when the
    /// window is too short for all of them.
    private var colourColumn: some View {
        VStack(spacing: 0) {
            ScrollView(.vertical, showsIndicators: false) {
                VStack(spacing: 10) {
                    ForEach(DoodleEditor.palette, id: \.self) { color in
                        Button { pick(color) } label: {
                            Circle().fill(Color(uiColor: UIColor(argb: color))).frame(width: 40, height: 40)
                                .overlay(Circle().stroke(MulberryDesign.muted.opacity(0.3)))
                                .padding(2)
                                .overlay {
                                    if color == editor.selectedColor {
                                        Circle().strokeBorder(MulberryDesign.ink, lineWidth: 2)
                                    }
                                }
                                .frame(width: 44, height: 44)
                                .contentShape(Circle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Colour \(String(format: "%06X", color & 0xFFFFFF))")
                        .accessibilityAddTraits(editor.selectedColor == color ? .isSelected : [])
                    }
                    Button { customColourOpen = true } label: {
                        CustomColourWell(color: editor.selectedColor)
                            .frame(width: 28, height: 28).scaleEffect(1.43)
                            .frame(width: 44, height: 44)
                            .contentShape(Circle())
                            .accessibilityHidden(true)
                    }.buttonStyle(.plain).accessibilityLabel("Custom colour")
                }
                .padding(8)
            }
            .scrollBounceBehavior(.basedOnSize)
            Button { setColourRail(collapsed: true) } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(MulberryDesign.ink)
                    .frame(width: 44, height: 44)
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Hide colours")
            .padding(.bottom, 8)
        }
        // As tall as the tool column beside it: two groups of 56 pt buttons, 10 pt apart, with 8 pt padding.
        .frame(width: 60).frame(maxHeight: 9 * 56 + 7 * 10 + Self.groupGap + 16)
        .glassEffect(.regular, in: .capsule)
        .glassEffectID(Self.colourGlass, in: glassSpace)
        .glassEffectTransition(reduceMotion ? .materialize : .matchedGeometry)
        .sheet(isPresented: $customColourOpen) {
            CustomColourSheet(color: $editor.selectedColor) {
                if editor.tool != .text { editor.tool = .brush }
                customColourOpen = false
            }
        }
    }

    private var brushColor: Color { Color(uiColor: UIColor(argb: editor.color)) }

    /// Quick, with the overshoot glass popovers have, so the glass bulges past its size and settles.
    /// Reduce Motion fades instead.
    private var railAnimation: Animation {
        reduceMotion ? .easeInOut(duration: 0.15) : .spring(duration: 0.4, bounce: 0.4)
    }

    private func setColourRail(collapsed: Bool) {
        withAnimation(railAnimation) { colourRailCollapsed = collapsed }
    }

    private func pick(_ color: UInt32) {
        editor.selectedColor = color
        // Text keeps its tool; any other colour choice returns to the brush.
        if editor.tool != .text { editor.tool = .brush }
    }

    /// One row on iPad; a column beside the canvas in wide iPad windows. It scrolls only when it does not fit.
    /// In the column, the palette button stands in for the folded colour column and opens it again.
    private func toolbar(_ axis: Axis, palette: Bool = true) -> some View {
        GlassEffectContainer(spacing: 4) {
            ViewThatFits(in: axis == .horizontal ? .horizontal : .vertical) {
                tools(axis, palette: palette)
                ScrollView(axis == .horizontal ? .horizontal : .vertical, showsIndicators: false) { tools(axis, palette: palette) }
                    .fixedSize(horizontal: axis == .vertical, vertical: axis == .horizontal)
            }
        }
    }

    /// The column beside the canvas in wide iPad windows, sharing its glass container with the colour column.
    private func toolColumn(palette: Bool) -> some View {
        ViewThatFits(in: .vertical) {
            tools(.vertical, palette: palette)
            ScrollView(.vertical, showsIndicators: false) { tools(.vertical, palette: palette) }
                .fixedSize(horizontal: true, vertical: false)
        }
    }

    /// Phones: only the tools, spread across the row; the actions stand above it at either end.
    private var compactToolbar: some View {
        GlassEffectContainer(spacing: 4) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 0) {
                    brushButton; Spacer(minLength: 4)
                    textButton; Spacer(minLength: 4)
                    stickerButton; Spacer(minLength: 4)
                    eraserButton; Spacer(minLength: 4)
                    eyedropperButton; Spacer(minLength: 4)
                    clearButton
                }
                .padding(8)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) { brushButton; textButton; stickerButton; eraserButton; eyedropperButton; clearButton }
                        .padding(8)
                }
                .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    /// The glass the colour column and the colour button share as one folds into the other.
    private static let colourGlass = "colours"

    /// The space between the actions and the tools.
    private static let groupGap: CGFloat = 40

    private func tools(_ axis: Axis, palette: Bool) -> some View {
        // Popovers open toward the canvas: above a row, to the right of the left-hand column.
        let edge: Edge = axis == .horizontal ? .bottom : .leading
        let groups = axis == .horizontal ? AnyLayout(HStackLayout(spacing: Self.groupGap)) : AnyLayout(VStackLayout(spacing: Self.groupGap))
        let group = axis == .horizontal ? AnyLayout(HStackLayout(spacing: 10)) : AnyLayout(VStackLayout(spacing: 10))
        // The actions first, then the tools.
        return groups {
            group {
                undoButton()
                redoButton()
                if palette { paletteButton(edge: edge, opensRail: axis == .vertical) }
                widthButton(edge: edge)
            }
            group {
                brushButton
                textButton
                stickerButton
                eraserButton
                eyedropperButton
                clearButton
            }
        }
        .buttonBorderShape(.circle)
        .controlSize(.large)
        .padding(8)
    }

    private func undoButton(glass: Glass = .regular) -> some View {
        toolButton("Undo", image: "CanvasUndo", symbol: "arrow.uturn.backward", enabled: editor.canUndo, glass: glass, action: editor.undo)
    }

    private func redoButton(glass: Glass = .regular) -> some View {
        toolButton("Redo", image: "CanvasRedo", symbol: "arrow.uturn.forward", enabled: editor.canRedo, glass: glass, action: editor.redo)
    }

    private var brushButton: some View {
        toolButton("Brush", image: "CanvasBrush", selected: editor.tool == .brush) { editor.tool = .brush }
    }

    private var eyedropperButton: some View {
        toolButton("Eyedropper", image: "CanvasEyedropper", selected: editor.tool == .eyedropper) { editor.tool = .eyedropper }
    }

    private var eraserButton: some View {
        toolButton("Eraser", image: "CanvasErase", selected: editor.tool == .eraser) { editor.tool = .eraser }
    }

    private var textButton: some View {
        toolButton("Text", image: "CanvasText", selected: editor.tool == .text, enabled: editor.canEditElements) { editor.toggleText() }
    }

    private var stickerButton: some View {
        toolButton("Stickers", image: "CanvasSticker", selected: editor.tool == .sticker, enabled: editor.canEditElements) { editor.toggleSticker() }
    }

    private var clearButton: some View {
        toolButton("Clear canvas", image: "CanvasClear") { clearConfirmation = true }
            .confirmationDialog("Clear canvas?", isPresented: $clearConfirmation, titleVisibility: .visible) {
                Button("Clear", role: .destructive) { editor.clear() }
                Button("Cancel", role: .cancel) {}
            } message: { Text("This removes all strokes from the shared canvas.") }
    }

    private func widthButton(edge: Edge, glass: Glass = .regular) -> some View {
        Button { widthOpen.toggle() } label: {
            Circle().fill(brushColor)
                .frame(width: 8 + (editor.width - 4) / 38 * 38, height: 8 + (editor.width - 4) / 38 * 38)
                .frame(width: 56, height: 56)
                // The whole glass circle opens the slider, not only the dot.
                .contentShape(Circle())
        }.buttonStyle(.plain)
            .glassEffect(glass.interactive(), in: .circle)
            .accessibilityLabel("Brush width")
            .accessibilityValue("\(Int(editor.width))")
            .popover(isPresented: $widthOpen, attachmentAnchor: .rect(.bounds), arrowEdge: edge) {
                widthPicker.presentationCompactAdaptation(.popover)
            }
    }

    private func paletteButton(edge: Edge, glass: Glass = .regular, opensRail: Bool = false) -> some View {
        Button { if opensRail { setColourRail(collapsed: false) } else { paletteOpen = true } } label: {
            ColourPaletteIcon(color: editor.selectedColor, size: 32)
                .frame(width: 56, height: 56)
                .contentShape(Circle())
        }.buttonStyle(.plain)
            .glassEffect(glass.interactive(), in: .circle)
            .glassEffectID(opensRail ? Self.colourGlass : nil, in: glassSpace)
            .glassEffectTransition(reduceMotion ? .materialize : .matchedGeometry)
            .accessibilityLabel(opensRail ? "Show colours" : "Colour palette")
            // A popover beside the button on iPad; a sheet on phones.
            .popover(isPresented: $paletteOpen, attachmentAnchor: .rect(.bounds), arrowEdge: edge) {
                colorPicker.presentationCompactAdaptation(.sheet)
            }
    }

    private func toolButton(_ title: String, image: String, symbol: String? = nil, selected: Bool = false, enabled: Bool = true,
                            glass: Glass = .regular, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Group {
                // Undo and redo are plain SF Symbols; the tools keep their illustrations.
                if let symbol {
                    Image(systemName: symbol)
                        .font(.system(size: 22, weight: .medium))
                        .foregroundStyle(MulberryDesign.ink)
                } else {
                    Image(image).resizable().renderingMode(image == "CanvasText" ? .template : .original)
                        .scaledToFit().frame(width: 34, height: 34)
                        .foregroundStyle(MulberryDesign.ink)
                }
            }
                .opacity(enabled ? 1 : 0.35)
                .frame(width: 56, height: 56)
                // The whole glass circle is the button, not only the drawn icon.
                .contentShape(Circle())
        }.disabled(!enabled).accessibilityLabel(title).accessibilityAddTraits(selected ? .isSelected : [])
        .buttonStyle(.plain)
        .glassEffect(glass.tint(selected ? MulberryDesign.accent.opacity(0.12) : .clear).interactive(), in: .circle)
        .overlay {
            if selected { Circle().strokeBorder(MulberryDesign.accent.opacity(0.4), lineWidth: 1.5).allowsHitTesting(false) }
        }
    }

    /// The squeeze palette around the Pencil tip, kept on screen; a tap anywhere else closes it.
    @ViewBuilder private func radialPalette(in size: CGSize) -> some View {
        if let tip = radialAt {
            let reach = PencilRadialPalette.reach
            // Over the tip unless that would run off the top of the screen.
            let below = tip.y - reach < 0
            let centre = CGPoint(x: min(max(tip.x, reach), size.width - reach), y: tip.y)
            // Only the arc takes touches; the canvas around it keeps drawing, which closes the arc.
            PencilRadialPalette(editor: editor, below: below, tipOffset: tip.x - centre.x, dismiss: closeRadial)
                .position(centre)
                // It blooms out of the tip by itself and shrinks back into it. The positioned palette
                // fills the overlay, so the tip is anchored as a fraction of the whole overlay.
                .transition(reduceMotion ? .opacity : .asymmetric(
                    insertion: .identity,
                    removal: .scale(scale: 0.2, anchor: UnitPoint(x: tip.x / size.width, y: tip.y / size.height))
                        .combined(with: .opacity)))
        }
    }

    private func closeRadial() {
        guard radialAt != nil else { return }
        withAnimation(reduceMotion ? .easeOut(duration: 0.1) : .spring(duration: 0.16, bounce: 0)) { radialAt = nil }
    }

    /// Apple Pencil's contextual palette: the drawing tools, every colour and the brush size, at the tip.
    private var pencilPaletteContent: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 10) {
                toolButton("Brush", image: "CanvasBrush", selected: editor.tool == .brush) { editor.tool = .brush }
                toolButton("Eraser", image: "CanvasErase", selected: editor.tool == .eraser) { editor.tool = .eraser }
                toolButton("Eyedropper", image: "CanvasEyedropper", selected: editor.tool == .eyedropper) { editor.tool = .eyedropper }
            }
            LazyVGrid(columns: Array(repeating: GridItem(.fixed(36), spacing: 10), count: 6), spacing: 10) {
                ForEach(DoodleEditor.palette, id: \.self) { color in
                    Button { pick(color); pencilPalette = nil } label: {
                        Circle().fill(Color(uiColor: UIColor(argb: color))).frame(width: 32, height: 32)
                            .overlay(Circle().stroke(MulberryDesign.muted.opacity(0.3)))
                            .padding(2)
                            .overlay { if color == editor.selectedColor { Circle().strokeBorder(MulberryDesign.ink, lineWidth: 2) } }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Colour \(String(format: "%06X", color & 0xFFFFFF))")
                    .accessibilityAddTraits(editor.selectedColor == color ? .isSelected : [])
                }
            }
            Slider(value: $editor.width, in: CanvasToolStore.widths, step: 1)
                .tint(brushColor).accessibilityLabel("Brush width")
        }
        .padding(20)
        .frame(width: 6 * 36 + 5 * 10 + 40)
    }

    private var widthPicker: some View {
        Slider(value: $editor.width, in: CanvasToolStore.widths, step: 1)
            .tint(brushColor).accessibilityLabel("Brush width")
            .frame(width: 200, height: 44)
            .rotationEffect(.degrees(-90))
            .frame(width: 52, height: 220).padding(10)
    }

    private var colorPicker: some View {
        ScrollView {
            VStack(spacing: 24) {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 44), spacing: 16)], spacing: 16) {
                    ForEach(DoodleEditor.palette, id: \.self) { color in
                        Button { pick(color); paletteOpen = false } label: {
                            Circle().fill(Color(uiColor: UIColor(argb: color))).frame(width: 44, height: 44)
                                .overlay(Circle().stroke(MulberryDesign.muted.opacity(0.3)))
                                .overlay { if color == editor.selectedColor { Image(systemName: "checkmark").foregroundStyle(color == 0xFFF7F4EF || color == 0xFFFFE66D ? .black : .white) } }
                                .frame(width: 44, height: 44)
                        }.buttonStyle(.plain).accessibilityLabel("Colour \(String(format: "%06X", color & 0xFFFFFF))").accessibilityAddTraits(editor.selectedColor == color ? .isSelected : [])
                    }
                    Button { customColourOpen = true } label: {
                        CustomColourWell(color: editor.selectedColor)
                            .frame(width: 28, height: 28)
                            .scaleEffect(1.5)
                            .frame(width: 44, height: 44)
                            .contentShape(Circle())
                            .accessibilityHidden(true)
                    }.buttonStyle(.plain).accessibilityLabel("Custom colour")
                }
            }
            .padding(.horizontal, 24)
            // A sheet leaves room for its drag indicator; a popover is even all round.
            .padding(.top, sizeClass == .regular ? 24 : 40)
            .padding(.bottom, sizeClass == .regular ? 24 : 16)
            .onGeometryChange(for: CGFloat.self) { geometry in
                geometry.size.height.rounded(.up)
            } action: { height in
                paletteHeight = max(1, height)
            }
        }
        .scrollBounceBehavior(.basedOnSize)
        // Sized to its swatches as a popover; a sheet sizes itself by its detent.
        .frame(width: sizeClass == .regular ? 360 : nil, height: sizeClass == .regular ? paletteHeight : nil)
        .presentationDetents([.height(paletteHeight)])
        .presentationDragIndicator(.visible)
        .sheet(isPresented: $customColourOpen) {
            CustomColourSheet(color: $editor.selectedColor) {
                if editor.tool != .text { editor.tool = .brush }
                paletteOpen = false
                customColourOpen = false
            }
        }
    }
}

struct UnpairedCanvasView: View {
    @ObservedObject var model: MulberryModel
    @State private var inviteOpen = false
    @Environment(\.horizontalSizeClass) private var sizeClass
    var body: some View {
        GeometryReader { geometry in
        ScrollView {
            VStack(spacing: 26) {
                HStack(spacing: -16) {
                    ProfileAvatar(url: model.bootstrap?.userPhotoUrl, name: model.bootstrap?.userDisplayName, size: 100)
                    ProfileAvatar(url: nil, name: nil, size: 100, unknown: true)
                }.padding(.top, sizeClass == .regular ? 0 : 64)
                Text("\(model.bootstrap?.userDisplayName ?? "You") & who?")
                    .font(MulberryDesign.font(30, weight: "Bold", relativeTo: .largeTitle)).multilineTextAlignment(.center)
                Text("Invite your partner to Mulberry to unlock the full experience")
                    .font(MulberryDesign.font(15)).foregroundStyle(MulberryDesign.muted).multilineTextAlignment(.center)
                PrimaryAction(title: "Share invite code") { inviteOpen = true }
                Button("Have a code? Enter code") { model.showInviteEntry = true }.frame(minHeight: 44)
                InlineError(message: model.errorMessage)
            }.padding(28).readableColumn()
                // Centred in a tall iPad window rather than hugging the top.
                .frame(minHeight: sizeClass == .regular ? geometry.size.height : nil)
        }
        }.frame(maxWidth: .infinity, maxHeight: .infinity).background(MulberryDesign.background)
            .sheet(isPresented: $inviteOpen) { InviteShareView(model: model) }
    }
}

/// Opens the Pencil palette where the Pencil hovers, or mid-canvas when it is lifted away.
private struct PencilPalettePopover<Palette: View>: ViewModifier {
    @Binding var request: PencilRequest?
    @ViewBuilder let palette: () -> Palette

    func body(content: Content) -> some View {
        content.popover(isPresented: Binding(get: { request != nil }, set: { if !$0 { request = nil } }),
                        attachmentAnchor: anchor) {
            palette().presentationCompactAdaptation(.popover)
        }
    }

    private var anchor: PopoverAttachmentAnchor {
        guard let location = request?.location else { return .point(.center) }
        return .rect(.rect(CGRect(origin: location, size: .zero)))
    }
}

