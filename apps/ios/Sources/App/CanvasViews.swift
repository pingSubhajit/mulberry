import SwiftUI

struct DoodleCanvasView: View {
    @ObservedObject var model: MulberryModel
    @StateObject private var editor: DoodleEditor
    @State private var widthOpen = false
    @State private var paletteOpen = false
    @State private var clearConfirmation = false
    @Environment(\.scenePhase) private var scenePhase

    init(model: MulberryModel) {
        self.model = model
        _editor = StateObject(wrappedValue: DoodleEditor(model: model))
    }

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                DoodleSurface(editor: editor, dry: model.bootstrap?.canvasStrokeRenderMode == "dry")
                if editor.strokes.isEmpty {
                    VStack(spacing: 10) {
                        Text("Draw them something cute").font(MulberryDesign.font(20, weight: "SemiBold", relativeTo: .title2))
                        Text("Whatever you draw will appear at the wallpaper of their phone.")
                            .font(MulberryDesign.font(14)).foregroundStyle(MulberryDesign.muted)
                    }.multilineTextAlignment(.center).padding(36).allowsHitTesting(false)
                }
            }.frame(maxWidth: .infinity, maxHeight: .infinity).clipped()
            toolbar

        }.background(MulberryDesign.background)
            .onChange(of: model.drawing) { _, _ in editor.receive() }
            .onChange(of: model.canvasClearVersion) { _, _ in editor.resetHistory() }
            .onChange(of: scenePhase) { _, value in
                if value != .active { editor.end() }
                else if editor.errorMessage != nil { editor.retry() }
            }
            .onDisappear { editor.end() }
            .sheet(isPresented: $paletteOpen) { colorPicker }
            .confirmationDialog("Clear canvas?", isPresented: $clearConfirmation, titleVisibility: .visible) {
                Button("Clear", role: .destructive) { editor.clear() }
                Button("Cancel", role: .cancel) {}
            } message: { Text("This removes all strokes from the shared canvas.") }
    }

    private var brushColor: Color { Color(uiColor: UIColor(argb: editor.color)) }

    private var toolbar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 7) {
                toolButton("Undo", image: "CanvasUndo", enabled: editor.canUndo, action: editor.undo)
                toolButton("Redo", image: "CanvasRedo", enabled: editor.canRedo, action: editor.redo)
                toolButton("Brush", image: "CanvasBrush", selected: editor.tool == .brush) { editor.tool = .brush }
                Button { widthOpen.toggle() } label: {
                    Circle().fill(brushColor)
                        .frame(width: 8 + (editor.width - 4) / 38 * 14, height: 8 + (editor.width - 4) / 38 * 14)
                        .frame(width: 49, height: 49)
                        .background(MulberryDesign.surface, in: Circle())
                        .overlay(Circle().strokeBorder(brushColor, lineWidth: 4))
                }.buttonStyle(.plain).accessibilityLabel("Brush width")
                    .accessibilityValue("\(Int(editor.width))")
                    .popover(isPresented: $widthOpen, attachmentAnchor: .rect(.bounds), arrowEdge: .bottom) {
                        widthPicker.presentationCompactAdaptation(.popover)
                    }
                Button { paletteOpen = true } label: {
                    Circle().fill(brushColor).frame(width: 35, height: 35)
                        .frame(width: 49, height: 49)
                        .background(MulberryDesign.surface, in: Circle())
                }.buttonStyle(.plain).accessibilityLabel("Colour palette")
                toolButton("Eyedropper", image: "CanvasEyedropper", selected: editor.tool == .eyedropper) { editor.tool = .eyedropper }
                toolButton("Eraser", image: "CanvasErase", selected: editor.tool == .eraser) { editor.tool = .eraser }
                toolButton("Text", image: "CanvasText", enabled: false) {}
                    .accessibilityHint("Not available in this version")
                toolButton("Stickers", image: "CanvasSticker", enabled: false) {}
                    .accessibilityHint("Not available in this version")
                toolButton("Clear canvas", image: "CanvasClear") { clearConfirmation = true }
            }.padding(10)
        }.fixedSize(horizontal: false, vertical: true)
            .background(MulberryDesign.soft, in: Capsule())
            .padding(.horizontal, 12).padding(.vertical, 8)
    }

    private func toolButton(_ title: String, image: String, selected: Bool = false, enabled: Bool = true, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(image).resizable().renderingMode(image == "CanvasText" ? .template : .original)
                .scaledToFit().padding(7).frame(width: 49, height: 49)
                .foregroundStyle(MulberryDesign.ink)
                .background(MulberryDesign.surface, in: Circle())
                .overlay(Circle().strokeBorder(selected ? MulberryDesign.accent : .clear, lineWidth: 4))
                .opacity(enabled ? 1 : 0.35)
        }.buttonStyle(.plain).disabled(!enabled).accessibilityLabel(title).accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var widthPicker: some View {
        Slider(value: $editor.width, in: 4...42, step: 1)
            .tint(brushColor).accessibilityLabel("Brush width")
            .frame(width: 200, height: 44)
            .rotationEffect(.degrees(-90))
            .frame(width: 52, height: 220).padding(10)
    }

    private var colorPicker: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 44), spacing: 16)], spacing: 16) {
                        ForEach(DoodleEditor.palette, id: \.self) { color in
                            Button { editor.color = color; editor.tool = .brush; paletteOpen = false } label: {
                                Circle().fill(Color(uiColor: UIColor(argb: color))).frame(width: 38, height: 38)
                                    .overlay(Circle().stroke(MulberryDesign.muted.opacity(0.3)))
                                    .overlay { if color == editor.color { Image(systemName: "checkmark").foregroundStyle(color == 0xFFF7F4EF || color == 0xFFFFE66D ? .black : .white) } }
                                    .frame(width: 44, height: 44)
                            }.buttonStyle(.plain).accessibilityLabel("Colour \(String(format: "%06X", color & 0xFFFFFF))").accessibilityAddTraits(editor.color == color ? .isSelected : [])
                        }
                    }
                    ColorPicker("Custom colour", selection: Binding(get: { Color(uiColor: UIColor(argb: editor.color)) }, set: { editor.color = UIColor($0).argb; editor.tool = .brush }), supportsOpacity: false)
                }.padding(24)
            }.navigationTitle("Colour palette").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { paletteOpen = false } } }
        }.presentationDetents([.medium, .large])
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
