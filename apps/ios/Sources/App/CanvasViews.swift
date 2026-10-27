import SwiftUI

struct DoodleCanvasView: View {
    @ObservedObject var model: MulberryModel
    @StateObject private var editor: DoodleEditor
    @State private var widthOpen = false
    @State private var paletteOpen = false
    @State private var customColourOpen = false
    @State private var paletteHeight: CGFloat = 280
    @State private var clearConfirmation = false
    @Environment(\.scenePhase) private var scenePhase

    init(model: MulberryModel) {
        self.model = model
        _editor = StateObject(wrappedValue: DoodleEditor(model: model))
    }

    var body: some View {
        GeometryReader { geometry in
            VStack(spacing: 0) {
                ZStack {
                    DoodleSurface(editor: editor, dry: model.bootstrap?.canvasStrokeRenderMode == "dry")
                        .padding(8)
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(MulberryDesign.canvas)
                    .overlay(alignment: .bottom) {
                        CanvasBlankStateGuidance(visible: editor.strokes.isEmpty)
                            .padding(.bottom, 34)
                    }
                    .overlay {
                        StreakCanvasCelebration(streaks: model.streaks) { model.showStreak = true }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 24))
                    .overlay(RoundedRectangle(cornerRadius: 24).strokeBorder(MulberryDesign.accent.opacity(0.30), lineWidth: 2))
                    .padding(.horizontal, 16).padding(.top, 12)
                toolbar

            }
            // Use part of the bottom safe area, while retaining space for the Home gesture.
            .padding(.bottom, -min(16, max(0, geometry.safeAreaInsets.bottom - 16)))
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
    }

    private var brushColor: Color { Color(uiColor: UIColor(argb: editor.color)) }

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
                        Circle().fill(brushColor).frame(width: 56, height: 56)
                    }.buttonStyle(.plain)
                        .glassEffect(.clear.interactive(), in: .circle)
                        .accessibilityLabel("Colour palette")
                    toolButton("Eyedropper", image: "CanvasEyedropper", selected: editor.tool == .eyedropper) { editor.tool = .eyedropper }
                    toolButton("Eraser", image: "CanvasErase", selected: editor.tool == .eraser) { editor.tool = .eraser }
                    toolButton("Text", image: "CanvasText", enabled: false) {}
                        .accessibilityHint("Not available in this version")
                    toolButton("Stickers", image: "CanvasSticker", enabled: false) {}
                        .accessibilityHint("Not available in this version")
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
        Slider(value: $editor.width, in: 4...42, step: 1)
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
                        Button { editor.color = color; editor.tool = .brush; paletteOpen = false } label: {
                            Circle().fill(Color(uiColor: UIColor(argb: color))).frame(width: 44, height: 44)
                                .overlay(Circle().stroke(MulberryDesign.muted.opacity(0.3)))
                                .overlay { if color == editor.color { Image(systemName: "checkmark").foregroundStyle(color == 0xFFF7F4EF || color == 0xFFFFE66D ? .black : .white) } }
                                .frame(width: 44, height: 44)
                        }.buttonStyle(.plain).accessibilityLabel("Colour \(String(format: "%06X", color & 0xFFFFFF))").accessibilityAddTraits(editor.color == color ? .isSelected : [])
                    }
                    Button { customColourOpen = true } label: {
                        CustomColourWell(color: editor.color)
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
            CustomColourSheet(color: $editor.color) {
                editor.tool = .brush
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
