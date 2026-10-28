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
    private static let space = "canvasScreen"
    @Environment(\.scenePhase) private var scenePhase

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

    var body: some View {
        GeometryReader { geometry in
            // Use part of the bottom safe area, while retaining space for the Home gesture.
            let bottomReach = min(16, max(0, geometry.safeAreaInsets.bottom - 16))
            let height = geometry.size.height + bottomReach
            let tools = toolbarFrame == .zero ? CGRect(x: 0, y: height - 72, width: geometry.size.width, height: 72) : toolbarFrame
            let size = Self.canvasSize(in: CGSize(width: geometry.size.width, height: tools.minY))
            let navigationMiddle = -Self.navigationBarHeight(contentTop: geometry.frame(in: .global).minY) / 2
            ZStack(alignment: .bottom) {
                DoodleSurface(editor: editor, dry: model.bootstrap?.canvasStrokeRenderMode == "dry")
                    .frame(width: size.width, height: size.height)
                    // Centred between the middles of the navigation bar and the tools, so it runs under both.
                    .position(x: geometry.size.width / 2, y: (navigationMiddle + tools.midY) / 2)
                // The visible part of the canvas, between the bars.
                CanvasBlankStateGuidance(visible: editor.strokes.isEmpty && editor.elements.isEmpty)
                    .frame(height: tools.minY).frame(maxHeight: .infinity, alignment: .top)
                // Between the bars, so the banner stays clear of both.
                StreakCanvasCelebration(streaks: model.streaks) { model.showStreak = true }
                    .frame(height: tools.minY).frame(maxHeight: .infinity, alignment: .top)
                toolbar
                    .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(Self.space)) } action: { toolbarFrame = $0 }
                    .fullScreenCover(item: $editor.stickerSession) { session in
                        CanvasStickerEditorView(session: session, catalog: editor.stickers,
                                                onDone: editor.commitSticker, onDelete: editor.deleteEditingSticker)
                    }
            }
            .frame(width: geometry.size.width, height: height, alignment: .top)
            .coordinateSpace(.named(Self.space))
        }
        .background(MulberryDesign.background)
            .onChange(of: model.drawing) { _, _ in editor.receive() }
            .onChange(of: model.canvasClearVersion) { _, _ in editor.resetHistory() }
            .onChange(of: scenePhase) { _, value in
                if value != .active { editor.end() }
                else if editor.errorMessage != nil { editor.retry() }
            }
            .onDisappear { editor.end() }
            .sheet(isPresented: $paletteOpen) { colorPicker }
            .fullScreenCover(item: $editor.textSession) { session in
                CanvasTextEditorView(session: session, palette: DoodleEditor.palette,
                                     onDone: editor.commitText, onDelete: editor.deleteEditingText)
            }
    }

    private var brushColor: Color { Color(uiColor: UIColor(argb: editor.color)) }
    private var selectedColor: Color { Color(uiColor: UIColor(argb: editor.selectedColor)) }

    private func pick(_ color: UInt32) {
        editor.selectedColor = color
        // Text keeps its tool; any other colour choice returns to the brush.
        if editor.tool != .text { editor.tool = .brush }
    }

    private var toolbar: some View {
        GlassEffectContainer(spacing: 4) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    toolButton("Undo", image: "CanvasUndo", enabled: editor.canUndo, action: editor.undo)
                    toolButton("Redo", image: "CanvasRedo", enabled: editor.canRedo, action: editor.redo)
                    toolButton("Brush", image: "CanvasBrush", selected: editor.tool == .brush) { editor.tool = .brush }
                    Button { widthOpen.toggle() } label: {
                        Circle().fill(brushColor)
                            .frame(width: 8 + (editor.width - 4) / 38 * 38, height: 8 + (editor.width - 4) / 38 * 38)
                            .frame(width: 56, height: 56)
                    }.buttonStyle(.plain)
                        .glassEffect(.regular.interactive(), in: .circle)
                        .accessibilityLabel("Brush width")
                        .accessibilityValue("\(Int(editor.width))")
                        .popover(isPresented: $widthOpen, attachmentAnchor: .rect(.bounds), arrowEdge: .bottom) {
                            widthPicker.presentationCompactAdaptation(.popover)
                        }
                    Button { paletteOpen = true } label: {
                        Circle().fill(selectedColor).frame(width: 56, height: 56)
                    }.buttonStyle(.plain)
                        .glassEffect(.clear.interactive(), in: .circle)
                        .accessibilityLabel("Colour palette")
                    toolButton("Eyedropper", image: "CanvasEyedropper", selected: editor.tool == .eyedropper) { editor.tool = .eyedropper }
                    toolButton("Eraser", image: "CanvasErase", selected: editor.tool == .eraser) { editor.tool = .eraser }
                    toolButton("Text", image: "CanvasText", selected: editor.tool == .text, enabled: editor.canEditElements) { editor.toggleText() }
                    toolButton("Stickers", image: "CanvasSticker", selected: editor.tool == .sticker, enabled: editor.canEditElements) { editor.toggleSticker() }
                    toolButton("Clear canvas", image: "CanvasClear") { clearConfirmation = true }
                        .confirmationDialog("Clear canvas?", isPresented: $clearConfirmation, titleVisibility: .visible) {
                            Button("Clear", role: .destructive) { editor.clear() }
                            Button("Cancel", role: .cancel) {}
                        } message: { Text("This removes all strokes from the shared canvas.") }
                }
                .buttonBorderShape(.circle)
                .controlSize(.large)
                .padding(.horizontal, 8).padding(.vertical, 8)
            }.fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 12)
    }

    private func toolButton(_ title: String, image: String, selected: Bool = false, enabled: Bool = true, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(image).resizable().renderingMode(image == "CanvasText" ? .template : .original)
                .scaledToFit().frame(width: 34, height: 34)
                .foregroundStyle(MulberryDesign.ink)
                .opacity(enabled ? 1 : 0.35)
                .frame(width: 56, height: 56)
        }.disabled(!enabled).accessibilityLabel(title).accessibilityAddTraits(selected ? .isSelected : [])
        .buttonStyle(.plain)
        .glassEffect(.regular.tint(selected ? MulberryDesign.accent.opacity(0.12) : .clear).interactive(), in: .circle)
        .overlay {
            if selected { Circle().strokeBorder(MulberryDesign.accent.opacity(0.4), lineWidth: 1.5).allowsHitTesting(false) }
        }
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
            .padding(.top, 40)
            .padding(.bottom, 16)
            .onGeometryChange(for: CGFloat.self) { geometry in
                geometry.size.height.rounded(.up)
            } action: { height in
                paletteHeight = max(1, height)
            }
        }
        .scrollBounceBehavior(.basedOnSize)
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
    var body: some View {
        ScrollView {
            VStack(spacing: 26) {
                HStack(spacing: -16) {
                    ProfileAvatar(url: model.bootstrap?.userPhotoUrl, name: model.bootstrap?.userDisplayName, size: 100)
                    ProfileAvatar(url: nil, name: nil, size: 100, unknown: true)
                }.padding(.top, 64)
                Text("\(model.bootstrap?.userDisplayName ?? "You") & who?")
                    .font(MulberryDesign.font(30, weight: "Bold", relativeTo: .largeTitle)).multilineTextAlignment(.center)
                Text("Invite your partner to Mulberry to unlock the full experience")
                    .font(MulberryDesign.font(15)).foregroundStyle(MulberryDesign.muted).multilineTextAlignment(.center)
                PrimaryAction(title: "Share invite code") { inviteOpen = true }
                Button("Have a code? Enter code") { model.showInviteEntry = true }.frame(minHeight: 44)
                InlineError(message: model.errorMessage)
            }.padding(28)
        }.frame(maxWidth: .infinity, maxHeight: .infinity).background(MulberryDesign.background)
            .sheet(isPresented: $inviteOpen) { InviteShareView(model: model) }
    }
}
