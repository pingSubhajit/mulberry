import AppKit
import Auth
import CanvasDrawing
import CanvasEditing
import CanvasRendering
import Overlay
import ProductCore
import Stickers
import Sync
import SwiftUI

/// Where the canvas editor is shown: the main window, with its toolbars, or Quick Draw, on the
/// desktop overlay with its tools in a panel beside it.
enum CanvasEditorLayout {
    case window
    case quickDraw
}

struct FullAppCanvasEditor: View {
    @ObservedObject var authController: AuthSessionController
    @ObservedObject var appStateController: AppStateController
    @ObservedObject var syncController: CanvasSyncController
    @ObservedObject var stickerLoader: StickerImageLoader
    @ObservedObject var model: CanvasEditorModel
    let reactions: ReactionsModel
    let layout: CanvasEditorLayout

    init(
        authController: AuthSessionController,
        appStateController: AppStateController,
        syncController: CanvasSyncController,
        stickerLoader: StickerImageLoader,
        model: CanvasEditorModel,
        reactions: ReactionsModel,
        layout: CanvasEditorLayout = .window
    ) {
        self.authController = authController
        self.appStateController = appStateController
        self.syncController = syncController
        self.stickerLoader = stickerLoader
        self.model = model
        self.reactions = reactions
        self.layout = layout
        // The overlay has no title bar to keep clear of.
        _titleBarHeight = State(initialValue: layout == .window ? 52 : 0)
    }

    @StateObject private var canvasModel = CanvasRenderModel()
    /// The shared editing engine, read and changed in place.
    private var editor: CanvasStrokeEditingEngine {
        get { model.engine }
        nonmutating set { model.engine = newValue }
    }
    @State private var elementRenderer = CanvasElementRenderer()
    /// Text being typed in place on the canvas. A new one is not on the canvas until it is finished.
    @State private var textEdit: CanvasElementSession<CanvasTextElement>?
    @State private var selectedTextID: String?
    /// The selected text while its frame is dragged, resized or rotated, before the change is saved.
    @State private var textPreview: CanvasTextElement?
    /// Bumped to give the canvas keyboard focus back, for Delete, Return and the arrow keys.
    @State private var focusRequest = 0
    /// Bumped to return typing focus to the text after a format bar change.
    @State private var typingFocus = 0
    @State private var formatBarSize = CGSize.zero
    /// Where the format bar settled for this text. Style changes leave it there; moving the text,
    /// another selection or a resized window settles it again.
    @State private var formatBarAnchor: CGPoint?
    @State private var formatBarEpoch = 0
    /// Where the eyedropper is over the canvas, for its loupe.
    @State private var eyedropperLoupe: CGPoint?
    @State private var colourSampler = ColourSamplerCache()
    /// The title bar's height, which the canvas runs under but does not draw in.
    @State private var titleBarHeight: CGFloat = 52

    private struct FormatBarPlacement: Equatable {
        let id: String
        let center: DrawingPoint
        let rotation: Double
        let zoom: CGFloat
        let offset: CGPoint
        let canvas: CGSize
        let epoch: Int
    }
    @State private var selectedStickerID: String?
    /// The selected sticker while its frame is dragged, resized or rotated, before the change is saved.
    @State private var stickerPreview: CanvasStickerElement?
    /// The sticker bar's Replace panel.
    @State private var replacingSticker = false
    @State private var drag: ElementDrag?
    /// A text or sticker pressed with the eraser: lifting without moving deletes it.
    @State private var elementToErase: (id: String, start: CGPoint)?
    /// Strokes already erased in this drag, before the deletions come back from sync.
    @State private var erasedThisDrag: Set<String> = []
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme

    /// An element being dragged to a new place; it saves as one update when released.
    private struct ElementDrag {
        let original: CanvasElement
        let start: CGPoint
        var current: CanvasElement
        var moved = false
    }

    private var userID: String? { appStateController.loadState.bootstrap?.userId }

    var body: some View {
        content
        .onAppear {
            syncCanvasModel()
            model.handleEscape = { escape() }
            model.finishEditing = { finishTyping(keepSelection: false) }
            model.stickerPanel = { AnyView(stickerPanel(current: nil) { placeSticker($0, at: nil) }) }
        }
        // Leaving the Text tool finishes any typing and drops the selection, from any toolbar.
        .onChange(of: editor.toolState.activeTool) { _, tool in
            if tool != .eyedropper { endEyedropperPreview() }
            if tool != .sticker {
                selectedStickerID = nil
                replacingSticker = false
            }
            guard tool != .text else { return }
            finishTyping(keepSelection: false)
            selectedTextID = nil
        }
        .onChange(of: textEdit != nil) { _, typing in model.isTyping = typing }
        .onReceive(syncController.objectWillChange) { _ in
            DispatchQueue.main.async {
                syncCanvasModel()
            }
        }
        .onReceive(stickerLoader.objectWillChange) { _ in
            DispatchQueue.main.async {
                syncCanvasModel()
            }
        }
        .alert("Clear canvas?", isPresented: clearBinding) {
            Button("Cancel", role: .cancel) {
                editor.cancelClearCanvas()
            }
            Button("Clear", role: .destructive) {
                submit(editor.confirmClearCanvas())
            }
        } message: {
            Text("This removes every stroke, text item, and sticker from the shared canvas.")
        }
    }

    @ViewBuilder private var content: some View {
        switch layout {
        case .window:
            GeometryReader { proxy in
                ZStack(alignment: .bottom) {
                    canvasSurface
                    toolbar(width: proxy.size.width)
                        .padding(.bottom, 14)
                }
            }
            .toolbar {
                ToolbarItemGroup(placement: .navigation) {
                    Button(action: model.undo) { Image(systemName: "arrow.uturn.backward") }
                        .keyboardShortcut("z", modifiers: .command)
                        // While typing, ⌘Z belongs to the text.
                        .disabled(!editor.canUndo || textEdit != nil)
                        .help("Undo")
                        .accessibilityLabel("Undo")
                    Button(action: model.redo) { Image(systemName: "arrow.uturn.forward") }
                        .keyboardShortcut("z", modifiers: [.command, .shift])
                        .disabled(!editor.canRedo || textEdit != nil)
                        .help("Redo")
                        .accessibilityLabel("Redo")
                }
            }
        case .quickDraw:
            // The window's frosted glass behind the drawing while it is being edited on the desktop,
            // a little clearer. Glass lifts towards white; in Dark Mode a dark tint keeps it smoky.
            canvasSurface
                .background {
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .fill(colorScheme == .dark ? Color.black.opacity(0.35) : Color(nsColor: .windowBackgroundColor).opacity(0.3))
                        .glassEffect(.regular, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
                }
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        }
    }

    /// Esc, in order: finish typing, deselect the text, leave the eyedropper. False when there was
    /// nothing to cancel.
    private func escape() -> Bool {
        if textEdit != nil {
            finishTyping(keepSelection: true)
            return true
        }
        if selectedTextID != nil {
            selectedTextID = nil
            return true
        }
        if selectedStickerID != nil {
            selectedStickerID = nil
            replacingSticker = false
            return true
        }
        if editor.toolState.activeTool == .eyedropper {
            editor.toolState.setActiveTool(editor.toolState.previousTool)
            return true
        }
        return false
    }

    /// The shared canvas keeps the phones' 9:20 shape, centred in the slightly wider window.
    private var canvasSurface: some View {
        GeometryReader { window in
            let _ = layout == .window ? updateTitleBarHeight(window.safeAreaInsets.top) : ()
            let height = min(window.size.height, window.size.width * 20 / 9)
            canvasLayers
                .frame(width: height * 9 / 20, height: height)
                // The partner's reactions arrive over the drawing, as on the phones.
                .overlay { ReactionStage(reactions: reactions, surface: layout == .window ? .window : .quickDraw) }
                .clipped()
                .position(x: window.size.width / 2, y: window.size.height / 2)
        }
        // The canvas runs under the title bar too.
        .ignoresSafeArea()
    }

    private var canvasLayers: some View {
        GeometryReader { proxy in
            ZStack(alignment: .topLeading) {
                CanvasRenderSurfaceView(
                    model: canvasModel,
                    surface: .fullApp,
                    showsEditingBackground: false
                )
                .scaleEffect(editor.viewportTransform.scale, anchor: .topLeading)
                .offset(
                    x: editor.viewportTransform.offset.x,
                    y: editor.viewportTransform.offset.y
                )

                // Squee waits on the empty canvas, clear of the window's tool row.
                CanvasBlankStateGuidance(
                    visible: canvasIsEmpty && textEdit == nil && editingIsEnabled,
                    footInset: layout == .window ? toolRowClearance(width: proxy.size.width) : 12
                )
                .padding(.top, titleBarHeight)
                .frame(width: proxy.size.width, height: proxy.size.height)

                CanvasInputOverlayView(
                    activeTool: editor.toolState.activeTool,
                    editingIsEnabled: editingIsEnabled,
                    focusRequest: focusRequest,
                    topInset: titleBarHeight,
                    cursor: { point in canvasCursor(at: point, canvasSize: proxy.size) },
                    onDrawStart: { point in
                        submit(editor.startStroke(at: point, surfaceSize: proxy.size))
                    },
                    onDrawMove: { point in
                        submit(editor.appendStrokePoint(at: point, surfaceSize: proxy.size))
                    },
                    onDrawEnd: {
                        submit(editor.finishStroke())
                    },
                    onEraseDown: { point in
                        eraseBegan(at: point, canvasSize: proxy.size)
                    },
                    onEraseMove: { point in
                        eraseMoved(to: point, canvasSize: proxy.size)
                    },
                    onEraseUp: { point in
                        eraseEnded(at: point, canvasSize: proxy.size)
                    },
                    onElementDown: { point in
                        elementDown(at: point, canvasSize: proxy.size)
                    },
                    onElementDrag: { point in
                        elementDragged(to: point, canvasSize: proxy.size)
                    },
                    onElementUp: {
                        elementReleased()
                    },
                    onDoubleClick: { point in
                        editElement(at: point, canvasSize: proxy.size)
                    },
                    onKey: { key in
                        handleKey(key, canvasSize: proxy.size)
                    },
                    onEyedropper: { point in
                        sampleEyedropper(at: point, surfaceSize: proxy.size)
                    },
                    onEyedropperHover: { point in
                        previewEyedropper(at: point, surfaceSize: proxy.size)
                    },
                    onCancel: {
                        if editor.toolState.activeTool == .eyedropper {
                            editor.toolState.setActiveTool(editor.toolState.previousTool)
                        }
                    }
                )
                // Below the title bar: the canvas is cropped there, and the band is the title bar's.
                .padding(.top, titleBarHeight)

                textLayers(canvasSize: proxy.size)
                stickerLayers(canvasSize: proxy.size)

                if let loupe = eyedropperLoupe, let colour = model.eyedropperPreview {
                    EyedropperLoupe(colour: colour)
                        .position(loupePosition(loupe, in: proxy.size))
                        .allowsHitTesting(false)
                        .transition(.scale(scale: 0.6).combined(with: .opacity))
                }

                if editingIsEnabled == false {
                    Text(editingDisabledMessage)
                        .font(.callout.weight(.medium))
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .glassEffect(.regular, in: Capsule())
                        .frame(maxWidth: .infinity)
                        .padding(.top, 64)
                        .allowsHitTesting(false)
                }
            }
            .coordinateSpace(name: CanvasSpace.name)
            // A sticker dragged from the panel lands where it is dropped.
            .dropDestination(for: String.self) { items, location in
                guard editingIsEnabled, let sticker = items.lazy.compactMap(StickerDragPayload.sticker).first else { return false }
                placeSticker(sticker, at: normalized(location, canvasSize: proxy.size))
                return true
            }
            // The canvas keeps its 9:20 geometry from the window's top, cropped under the title bar.
            .mask(alignment: .top) {
                VStack(spacing: 0) {
                    Color.clear.frame(height: titleBarHeight)
                    Rectangle()
                }
            }
        }
    }

    private var clearBinding: Binding<Bool> {
        Binding(
            get: { editor.clearConfirmationRequested },
            set: { requested in
                if requested == false {
                    editor.cancelClearCanvas()
                }
            }
        )
    }

    // MARK: Toolbar

    /// As on iPhone: the tools in one compact row, with colour and width standing apart in a
    /// clear-glass column at the window's right edge, under the toolbar's right end. Undo and redo sit by the traffic lights and reactions
    /// by the streak. The circles scale with the window.
    private func toolbar(width: CGFloat) -> some View {
        let size = min(52, max(32, (width - 56) / 6.9))
        return VStack(spacing: 28) {
            // Lined up with the right edge of the window toolbar's streak and profile group.
            HStack {
                Spacer()
                VStack(spacing: 10) {
                    CanvasColourButton(model: model, size: size, glass: .clear)
                    CanvasWidthButton(model: model, size: size, glass: .clear)
                }
            }
            .padding(.trailing, 8)
            .frame(width: width)
            GlassEffectContainer(spacing: 4) {
                HStack(spacing: 0) {
                    ForEach(CanvasToolSlot.allCases) { slot in
                        if slot != .brush { Spacer(minLength: 4) }
                        CanvasToolSlotButton(slot: slot, model: model, size: size, canvasIsEmpty: canvasIsEmpty, panelEdge: .top)
                    }
                }
                .padding(8)
            }
            // The phone's row: six tools 10 points apart at most, with 8 points of padding.
            .frame(maxWidth: 6 * size + 5 * 10 + 16)
        }
        .disabled(!editingIsEnabled)
    }

    /// The height the window's tool row takes from the canvas's foot, with a little air above it.
    private func toolRowClearance(width: CGFloat) -> CGFloat {
        let size = min(52, max(32, (width - 56) / 6.9))
        return size + 16 + 14 + 12
    }

    private var tool: CanvasEditingTool { editor.toolState.activeTool }

    private var canvasIsEmpty: Bool {
        syncController.drawing.strokes.isEmpty && syncController.drawing.elements.isEmpty
    }

    private var editingIsEnabled: Bool {
        let bootstrap = appStateController.loadState.bootstrap
        return CanvasEditingAvailabilityPolicy().availability(for: CanvasEditingAvailabilityInput(
            isAuthenticated: authController.state.isSignedIn,
            isPaired: bootstrap?.isPaired ?? false,
            pairSessionID: bootstrap?.pairSessionId ?? syncController.status.pairSessionID,
            hasUsableCanvasState: true,
            hasHardLocalStateError: false
        )).isEnabled
    }

    private var editingDisabledMessage: String {
        let bootstrap = appStateController.loadState.bootstrap
        let availability = CanvasEditingAvailabilityPolicy().availability(for: CanvasEditingAvailabilityInput(
            isAuthenticated: authController.state.isSignedIn,
            isPaired: bootstrap?.isPaired ?? false,
            pairSessionID: bootstrap?.pairSessionId ?? syncController.status.pairSessionID,
            hasUsableCanvasState: true,
            hasHardLocalStateError: false
        ))
        switch availability {
        case .enabled:
            return ""
        case .disabled(.signedOut):
            return "Sign in to draw"
        case .disabled(.unpaired):
            return "Pair with your partner to draw"
        case .disabled(.missingPairSession):
            return "Waiting for pair session"
        case .disabled(.waitingForCanvasState):
            return "Loading canvas"
        case .disabled(.localStateError):
            return "Local canvas state needs recovery"
        }
    }

    private func submit(_ operations: [DrawingOperation]) {
        syncController.submit(operations)
    }

    private func sampleEyedropper(at renderedPoint: CGPoint, surfaceSize: CGSize) {
        guard let color = sampledColour(at: renderedPoint, surfaceSize: surfaceSize) else { return }
        editor.toolState.commitEyedropperColor(color)
        endEyedropperPreview()
        Haptics.select()
    }

    /// As the eyedropper moves: the colour under it on the buttons, and in a loupe beside it.
    private func previewEyedropper(at renderedPoint: CGPoint?, surfaceSize: CGSize) {
        guard let renderedPoint, editor.toolState.activeTool == .eyedropper else { return endEyedropperPreview() }
        model.eyedropperPreview = sampledColour(at: renderedPoint, surfaceSize: surfaceSize)
        withAnimation(.easeOut(duration: 0.12)) { eyedropperLoupe = renderedPoint }
    }

    private func endEyedropperPreview() {
        if model.eyedropperPreview != nil { model.eyedropperPreview = nil }
        if eyedropperLoupe != nil { withAnimation(.easeOut(duration: 0.12)) { eyedropperLoupe = nil } }
    }

    /// The colour under a point, read from the canvas drawn once offscreen until it changes.
    private func sampledColour(at renderedPoint: CGPoint, surfaceSize: CGSize) -> UInt32? {
        let key = ColourSamplerCache.Key(
            state: syncController.drawing,
            size: surfaceSize,
            mode: canvasModel.strokeRenderMode,
            stickers: stickerLoader.images.count
        )
        if colourSampler.key != key {
            colourSampler.key = key
            colourSampler.sampler = CanvasColourSampler(input: CanvasRenderInput(
                state: key.state,
                viewport: CGRect(origin: .zero, size: surfaceSize),
                strokeRenderMode: key.mode,
                surface: .fullApp,
                showsEditingBackground: false,
                stickerImages: stickerLoader.images
            ))
        }
        return colourSampler.sampler?.colour(at: editor.viewportTransform.contentPoint(fromRenderedPoint: renderedPoint))
    }

    /// Up and to the left of the eyedropper's tip, clear of its glyph, kept on the canvas.
    private func loupePosition(_ point: CGPoint, in size: CGSize) -> CGPoint {
        let offset: CGFloat = 34
        let x = point.x - offset < 24 ? point.x + offset : point.x - offset
        let y = point.y - offset < titleBarHeight + 24 ? point.y + offset : point.y - offset
        return CGPoint(x: min(max(x, 24), size.width - 24), y: y)
    }

    // MARK: Text and stickers

    private func normalized(_ renderedPoint: CGPoint, canvasSize: CGSize) -> DrawingPoint {
        editor.viewportTransform.normalizedPoint(fromRenderedPoint: renderedPoint, surfaceSize: canvasSize)
    }

    private func element(
        at renderedPoint: CGPoint,
        canvasSize: CGSize,
        where include: (CanvasElement) -> Bool = { _ in true }
    ) -> CanvasElement? {
        elementRenderer.stickerImages = stickerLoader.images
        let point = editor.viewportTransform.contentPoint(fromRenderedPoint: renderedPoint)
        return elementRenderer.hit(syncController.drawing.canvasElements, at: point, canvasSize: canvasSize, where: include)
    }

    /// Text tool: clicking away from the text being typed finishes it; pressing a text selects it
    /// and starts moving it; pressing empty canvas starts typing there. Sticker tool: pressing a
    /// sticker selects it and starts moving it; pressing empty canvas deselects.
    private func elementDown(at point: CGPoint, canvasSize: CGSize) {
        if editor.toolState.activeTool == .text {
            if textEdit != nil {
                finishTyping(keepSelection: false)
                return
            }
            if let hit = element(at: point, canvasSize: canvasSize, where: { $0.text != nil }) {
                selectedTextID = hit.id
                drag = ElementDrag(original: hit, start: point, current: hit)
                return
            }
            selectedTextID = nil
            textEdit = CanvasElementSession(
                element: CanvasTextElement(center: normalized(point, canvasSize: canvasSize), colorArgb: editor.toolState.textColorArgb),
                isNew: true
            )
            return
        }
        guard editor.toolState.activeTool == .sticker else { return }
        model.stickerPanelOpen = false
        replacingSticker = false
        if let hit = element(at: point, canvasSize: canvasSize, where: { $0.sticker != nil }) {
            selectedStickerID = hit.id
            drag = ElementDrag(original: hit, start: point, current: hit)
            return
        }
        selectedStickerID = nil
    }

    private func elementDragged(to point: CGPoint, canvasSize: CGSize) {
        guard var drag else { return }
        let scale = max(editor.viewportTransform.scale, 0.0001)
        let dx = (point.x - drag.start.x) / scale, dy = (point.y - drag.start.y) / scale
        drag.moved = drag.moved || hypot(dx, dy) > 3
        var moved = drag.original
        moved.center = DrawingPoint(
            x: min(1, max(0, moved.center.x + dx / max(canvasSize.width, 1))),
            y: min(1, max(0, moved.center.y + dy / max(canvasSize.height, 1)))
        )
        drag.current = moved
        self.drag = drag
        // An element dragged straight away takes its frame and bar with it.
        switch moved {
        case let .text(text): textPreview = text
        case let .sticker(sticker): stickerPreview = sticker
        }
        syncCanvasModel()
    }

    /// A drag saves the new position. A click without moving leaves the element selected.
    private func elementReleased() {
        guard let drag else { return }
        self.drag = nil
        textPreview = nil
        stickerPreview = nil
        formatBarEpoch += 1
        if drag.moved {
            submit(editor.update(drag.current, in: syncController.drawing))
        }
        focusRequest += 1
        syncCanvasModel()
    }

    // MARK: Eraser

    /// As on iPhone and iPad: pressing a text or sticker deletes it on release unless the pointer
    /// moves; otherwise the eraser removes every stroke it passes over.
    private func eraseBegan(at point: CGPoint, canvasSize: CGSize) {
        erasedThisDrag = []
        if let hit = element(at: point, canvasSize: canvasSize) {
            elementToErase = (hit.id, point)
            return
        }
        eraseStroke(at: point, canvasSize: canvasSize)
    }

    private func eraseMoved(to point: CGPoint, canvasSize: CGSize) {
        if let pending = elementToErase {
            guard hypot(point.x - pending.start.x, point.y - pending.start.y) > 10 else { return }
            elementToErase = nil
        }
        eraseStroke(at: point, canvasSize: canvasSize)
    }

    private func eraseEnded(at point: CGPoint, canvasSize: CGSize) {
        if let pending = elementToErase {
            submit(editor.delete(elementID: pending.id, in: syncController.drawing))
        } else {
            eraseStroke(at: point, canvasSize: canvasSize)
        }
        elementToErase = nil
        erasedThisDrag = []
    }

    private func eraseStroke(at point: CGPoint, canvasSize: CGSize) {
        var state = syncController.drawing
        state.strokes.removeAll { erasedThisDrag.contains($0.id) }
        let operations = editor.eraseStroke(at: point, in: state, surfaceSize: canvasSize)
        erasedThisDrag.formUnion(operations.compactMap(\.strokeId))
        submit(operations)
    }

    /// Double-clicking an element with any tool edits a text's words, or selects a sticker with
    /// its Replace panel open.
    private func editElement(at point: CGPoint, canvasSize: CGSize) -> Bool {
        guard let hit = element(at: point, canvasSize: canvasSize) else { return false }
        switch hit {
        case let .text(text):
            beginTyping(text)
        case let .sticker(sticker):
            selectSticker(sticker.id)
            replacingSticker = true
        }
        return true
    }

    // MARK: Pointer

    /// Each tool's pointer over the canvas: the brush and eraser tips at their size, the I-beam
    /// for text, and an eyedropper. The text tool shows the arrow over a text it would select.
    private func canvasCursor(at point: CGPoint, canvasSize: CGSize) -> NSCursor {
        guard editingIsEnabled else { return .arrow }
        switch editor.toolState.activeTool {
        case .draw:
            return CanvasCursors.tip(diameter: CGFloat(editor.toolState.selectedBrushWidthPx) * editor.viewportTransform.scale)
        case .erase:
            return CanvasCursors.tip(diameter: 20)
        case .text:
            return element(at: point, canvasSize: canvasSize, where: { $0.text != nil }) == nil ? .iBeam : .arrow
        case .eyedropper:
            return CanvasCursors.eyedropper
        case .sticker:
            return .arrow
        }
    }

    private func updateTitleBarHeight(_ height: CGFloat) {
        guard height > 0, abs(height - titleBarHeight) > 0.5 else { return }
        DispatchQueue.main.async { titleBarHeight = height }
    }

    // MARK: Text tool

    private var selectedText: CanvasTextElement? {
        guard let selectedTextID else { return nil }
        return syncController.drawing.canvasElements.first { $0.id == selectedTextID }?.text
    }

    @ViewBuilder private func textLayers(canvasSize: CGSize) -> some View {
        let zoom = editor.viewportTransform.scale
        let offset = editor.viewportTransform.offset
        if let edit = textEdit {
            InlineTextEditor(
                draft: Binding(get: { textEdit?.element ?? edit.element }, set: { textEdit?.element = $0 }),
                canvasSize: canvasSize,
                zoom: zoom,
                offset: offset,
                refocus: typingFocus
            ) {
                finishTyping(keepSelection: true)
            }
            .id(edit.element.id)
        } else if let selected = selectedText {
            ElementSelectionFrame(
                geometry: textGeometry(textPreview ?? selected, canvasSize: canvasSize),
                original: selected,
                canvasSize: canvasSize,
                zoom: zoom,
                label: "Selected text: \(selected.text)",
                onChange: { changed in
                    textPreview = changed
                    syncCanvasModel()
                },
                onCommit: { changed in
                    textPreview = nil
                    submit(editor.update(.text(changed), in: syncController.drawing))
                    formatBarEpoch += 1
                    focusRequest += 1
                    syncCanvasModel()
                },
                onEdit: { beginTyping(selected) }
            )
        }
        if let target = textEdit?.element ?? textPreview ?? selectedText {
            let geometry = textGeometry(target, canvasSize: canvasSize)
            let natural = formatBarPosition(geometry, canvasSize: canvasSize)
            // In its own panel over the window, so it shows whole even past the canvas's edge.
            FloatingBar {
                TextFormatBar(element: target, onChange: applyFormat, onDelete: deleteText)
                    .onGeometryChange(for: CGSize.self) { $0.size } action: { formatBarSize = $0 }
            }
            .frame(width: 1, height: 1)
            .position(anchoredFormatBar(natural: natural, geometry: geometry))
                .onChange(of: FormatBarPlacement(
                    id: target.id, center: target.center, rotation: target.rotationRad,
                    zoom: zoom, offset: offset, canvas: canvasSize, epoch: formatBarEpoch
                ), initial: true) {
                    formatBarAnchor = natural
                }
        }
    }

    // MARK: Sticker tool

    private var selectedSticker: CanvasStickerElement? {
        guard let selectedStickerID else { return nil }
        return syncController.drawing.canvasElements.first { $0.id == selectedStickerID }?.sticker
    }

    /// The selected sticker as it looks right now: mid-drag, mid-resize, or as saved.
    private var shownSticker: CanvasStickerElement? {
        if let drag, drag.moved, case let .sticker(sticker) = drag.current, sticker.id == selectedStickerID { return sticker }
        return stickerPreview ?? selectedSticker
    }

    @ViewBuilder private func stickerLayers(canvasSize: CGSize) -> some View {
        if let selected = selectedSticker, let shown = shownSticker, textEdit == nil {
            let geometry = stickerGeometry(shown, canvasSize: canvasSize)
            ElementSelectionFrame(
                geometry: geometry,
                original: selected,
                canvasSize: canvasSize,
                zoom: editor.viewportTransform.scale,
                label: "Selected sticker",
                onChange: { changed in
                    stickerPreview = changed
                    syncCanvasModel()
                },
                onCommit: { changed in
                    stickerPreview = nil
                    submit(editor.update(.sticker(changed), in: syncController.drawing))
                    formatBarEpoch += 1
                    focusRequest += 1
                    syncCanvasModel()
                },
                onEdit: { replacingSticker = true }
            )
            let natural = formatBarPosition(geometry, canvasSize: canvasSize)
            FloatingBar {
                StickerBar(
                    sticker: shown,
                    panel: { current, pick in AnyView(stickerPanel(current: current, pick: pick)) },
                    replacing: $replacingSticker,
                    onReplace: { reference in replace(selected, with: reference) },
                    onStraighten: { straighten(selected) },
                    onDuplicate: { duplicate(selected) },
                    onDelete: { deleteSticker(selected) }
                )
                .onGeometryChange(for: CGSize.self) { $0.size } action: { formatBarSize = $0 }
            }
            .frame(width: 1, height: 1)
            .position(anchoredFormatBar(natural: natural, geometry: geometry))
            .onChange(of: FormatBarPlacement(
                id: shown.id, center: shown.center, rotation: shown.rotationRad,
                zoom: editor.viewportTransform.scale, offset: editor.viewportTransform.offset, canvas: canvasSize, epoch: formatBarEpoch
            ), initial: true) {
                formatBarAnchor = natural
            }
        }
    }

    /// The sticker panel, for placing a new sticker or replacing the selected one's artwork.
    @ViewBuilder private func stickerPanel(current: StickerReference?, pick: @escaping (StickerReference) -> Void) -> some View {
        if let userID {
            StickerPickerPanel(catalog: stickerLoader.catalog(user: userID), user: userID, store: stickerLoader.store, current: current, onPick: pick)
        }
    }

    /// Where a sticker sits on screen, at its drawn size.
    private func stickerGeometry(_ sticker: CanvasStickerElement, canvasSize: CGSize) -> ElementFrameGeometry {
        let zoom = editor.viewportTransform.scale
        let offset = editor.viewportTransform.offset
        let size = CanvasStickerGeometry.size(sticker, image: stickerLoader.images[sticker.sticker], canvasWidth: canvasSize.width)
        let width = size.width * zoom, height = size.height * zoom
        return ElementFrameGeometry(
            center: CGPoint(x: sticker.center.x * canvasSize.width * zoom + offset.x, y: sticker.center.y * canvasSize.height * zoom + offset.y),
            body: CGRect(x: -width / 2, y: -height / 2, width: width, height: height),
            angle: sticker.rotationRad
        )
    }

    /// A new sticker: in the middle of the canvas from a click in the panel, or where it was
    /// dropped. It arrives selected, with the Sticker tool chosen.
    private func placeSticker(_ reference: StickerReference, at center: DrawingPoint?) {
        let sticker = CanvasStickerElement(center: center ?? DrawingPoint(x: 0.5, y: 0.5), sticker: reference)
        submit(editor.commit(.sticker(sticker), isNew: true, in: syncController.drawing))
        if let userID { stickerLoader.catalog(user: userID).use(reference) }
        finishTyping(keepSelection: false)
        selectedTextID = nil
        if editor.toolState.activeTool != .sticker { editor.toolState.setActiveTool(.sticker) }
        model.stickerPanelOpen = false
        selectSticker(sticker.id)
        Haptics.select()
    }

    private func selectSticker(_ id: String) {
        if editor.toolState.activeTool != .sticker { editor.toolState.setActiveTool(.sticker) }
        selectedStickerID = id
        formatBarEpoch += 1
        focusRequest += 1
        syncCanvasModel()
    }

    /// New artwork in the same place, at the same size and angle.
    private func replace(_ sticker: CanvasStickerElement, with reference: StickerReference) {
        var replaced = sticker
        replaced.sticker = reference
        submit(editor.update(.sticker(replaced), in: syncController.drawing))
        if let userID { stickerLoader.catalog(user: userID).use(reference) }
        focusRequest += 1
        Haptics.select()
    }

    private func straighten(_ sticker: CanvasStickerElement) {
        var level = sticker
        level.rotationRad = 0
        submit(editor.update(.sticker(level), in: syncController.drawing))
        formatBarEpoch += 1
        focusRequest += 1
    }

    /// A copy a little down and to the right, selected, as Figma's duplicate.
    private func duplicate(_ sticker: CanvasStickerElement) {
        let copy = CanvasStickerElement(
            center: DrawingPoint(x: min(1, sticker.center.x + 0.04), y: min(1, sticker.center.y + 0.025)),
            rotationRad: sticker.rotationRad,
            scale: sticker.scale,
            sticker: sticker.sticker
        )
        submit(editor.commit(.sticker(copy), isNew: true, in: syncController.drawing))
        selectSticker(copy.id)
    }

    private func deleteSticker(_ sticker: CanvasStickerElement) {
        submit(editor.delete(elementID: sticker.id, in: syncController.drawing))
        selectedStickerID = nil
        replacingSticker = false
        focusRequest += 1
        syncCanvasModel()
    }

    private func handleStickerKey(_ key: CanvasKey, sticker: CanvasStickerElement, canvasSize: CGSize) -> Bool {
        switch key {
        case .delete:
            deleteSticker(sticker)
        case .return:
            replacingSticker = true
        case .duplicate:
            duplicate(sticker)
        case .escape:
            selectedStickerID = nil
            replacingSticker = false
        case let .arrow(dx, dy):
            let zoom = editor.viewportTransform.scale
            var moved = sticker
            moved.center = DrawingPoint(
                x: min(1, max(0, sticker.center.x + dx / (canvasSize.width * zoom))),
                y: min(1, max(0, sticker.center.y + dy / (canvasSize.height * zoom)))
            )
            submit(editor.update(.sticker(moved), in: syncController.drawing))
        case .undo, .redo, .clearCanvas, .tool:
            return false
        }
        return true
    }

    /// Where a text sits on screen. Text with nothing typed yet takes the
    /// height of one line at its wrap width.
    private func textGeometry(_ text: CanvasTextElement, canvasSize: CGSize) -> ElementFrameGeometry {
        let layout = elementRenderer.layout(for: text, canvasWidth: canvasSize.width)
        let zoom = editor.viewportTransform.scale
        let offset = editor.viewportTransform.offset
        let scale = CGFloat(text.scale) * zoom
        var body = layout.bodyRect(pill: text.backgroundPillEnabled)
        if layout.tightBounds.isEmpty {
            let height = CanvasTextStyle.baseSize * 1.3
            body = CGRect(x: -layout.size.width / 2, y: -height / 2, width: layout.size.width, height: height)
        }
        return ElementFrameGeometry(
            center: CGPoint(x: text.center.x * canvasSize.width * zoom + offset.x, y: text.center.y * canvasSize.height * zoom + offset.y),
            body: CGRect(x: body.minX * scale, y: body.minY * scale, width: body.width * scale, height: body.height * scale),
            angle: text.rotationRad
        )
    }

    /// The settled position. It moves only when the text would otherwise touch it, and then only
    /// as far as the text needs: style changes that keep clear of it never move it.
    private func anchoredFormatBar(natural: CGPoint, geometry: ElementFrameGeometry) -> CGPoint {
        guard let anchor = formatBarAnchor else { return natural }
        let bounds = geometry.bounds
        let half = formatBarSize.height / 2
        // Pushed only on real contact: the rotation zones beyond a selected text's corners, the
        // text itself while typing. The natural place leaves headroom for styling to grow into.
        let gap: CGFloat = textEdit == nil ? 30 : 6
        if anchor.y < bounds.minY {
            let highest = bounds.minY - gap - half
            // No room left above: take the natural place below.
            if highest - half < (layout == .window ? 60 : 8) { return natural }
            return CGPoint(x: anchor.x, y: min(anchor.y, highest))
        }
        return CGPoint(x: anchor.x, y: max(anchor.y, bounds.maxY + gap + half))
    }

    /// Above the text, clear of the rotation zones beyond its corners, or below it near the top of
    /// the window.
    private func formatBarPosition(_ geometry: ElementFrameGeometry, canvasSize: CGSize) -> CGPoint {
        let bounds = geometry.bounds
        let half = formatBarSize.width / 2, height = formatBarSize.height
        let x = formatBarSize.width + 16 > canvasSize.width
            ? bounds.midX
            : min(max(bounds.midX, half + 8), canvasSize.width - half - 8)
        let clearance: CGFloat = textEdit == nil ? 44 : 28
        let above = bounds.minY - clearance - height / 2
        // The window toolbar covers the canvas's top 60 points; the overlay has none.
        let top: CGFloat = layout == .window ? 60 : 8
        let y = above - height / 2 >= top ? above : bounds.maxY + clearance + height / 2
        return CGPoint(x: x, y: y)
    }

    private func beginTyping(_ text: CanvasTextElement) {
        editor.toolState.setActiveTool(.text)
        selectedTextID = text.id
        textEdit = CanvasElementSession(element: text, isNew: false)
        syncCanvasModel()
    }

    /// Esc keeps the text selected; clicking away deselects it. Empty text is not kept.
    private func finishTyping(keepSelection: Bool) {
        guard let edit = textEdit else { return }
        textEdit = nil
        let blank = edit.element.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        editor.toolState.textColorArgb = edit.element.colorArgb
        submit(editor.commit(.text(edit.element), isNew: edit.isNew, in: syncController.drawing))
        selectedTextID = keepSelection && !blank ? edit.element.id : nil
        focusRequest += 1
        syncCanvasModel()
    }

    /// While typing, style changes wait for the text to finish; on a selected text each is saved.
    private func applyFormat(_ updated: CanvasTextElement) {
        if textEdit != nil {
            textEdit?.element = updated
            typingFocus += 1
        } else {
            submit(editor.update(.text(updated), in: syncController.drawing))
            focusRequest += 1
        }
    }

    private func deleteText() {
        if let edit = textEdit {
            textEdit = nil
            if !edit.isNew { submit(editor.delete(elementID: edit.element.id, in: syncController.drawing)) }
        } else if let selectedTextID {
            submit(editor.delete(elementID: selectedTextID, in: syncController.drawing))
        }
        selectedTextID = nil
        focusRequest += 1
        syncCanvasModel()
    }

    /// Delete removes the selected text or sticker, Return edits the text's words or opens the
    /// sticker's Replace panel, Command-D duplicates a sticker, Esc deselects, and the arrow keys
    /// nudge by a point, or ten with Shift.
    private func handleKey(_ key: CanvasKey, canvasSize: CGSize) -> Bool {
        guard textEdit == nil else { return false }
        switch key {
        case .undo:
            if editor.canUndo { model.undo() }
            return true
        case .redo:
            if editor.canRedo { model.redo() }
            return true
        case let .tool(tool):
            model.select(tool)
            return true
        case .clearCanvas:
            // Asks first, as the toolbar's Clear canvas does.
            if !canvasIsEmpty { model.engine.requestClearCanvas() }
            return true
        case .delete, .return, .escape, .arrow, .duplicate:
            break
        }
        if let sticker = selectedSticker {
            return handleStickerKey(key, sticker: sticker, canvasSize: canvasSize)
        }
        guard let selected = selectedText else { return false }
        switch key {
        case .delete:
            deleteText()
        case .return, .duplicate:
            guard key == .return else { return false }
            beginTyping(selected)
        case .escape:
            selectedTextID = nil
        case let .arrow(dx, dy):
            let zoom = editor.viewportTransform.scale
            var moved = selected
            moved.center = DrawingPoint(
                x: min(1, max(0, selected.center.x + dx / (canvasSize.width * zoom))),
                y: min(1, max(0, selected.center.y + dy / (canvasSize.height * zoom)))
            )
            submit(editor.update(.text(moved), in: syncController.drawing))
        case .undo, .redo, .clearCanvas, .tool:
            break
        }
        return true
    }

    private func syncCanvasModel() {
        var state = syncController.drawing
        if let drag, drag.moved {
            try? state.apply(.update(drag.current))
        }
        if let textPreview {
            try? state.apply(.update(.text(textPreview)))
        }
        if let stickerPreview {
            try? state.apply(.update(.sticker(stickerPreview)))
        }
        // Text being typed shows itself.
        if let editing = textEdit.map({ CanvasElement.text($0.element) }),
           state.canvasElements.contains(where: { $0.id == editing.id }) {
            try? state.apply(.delete(editing))
        }
        canvasModel.state = state
        canvasModel.stickerImages = stickerLoader.images
        canvasModel.stickerTimeline = stickerLoader.timeline
        canvasModel.strokeRenderMode = CanvasStrokeRenderMode(
            rawBackendValue: appStateController.loadState.bootstrap?.canvasStrokeRenderMode
        )
    }
}

private struct CanvasInputOverlayView: NSViewRepresentable {
    var activeTool: CanvasEditingTool
    var editingIsEnabled: Bool
    var focusRequest: Int
    var topInset: CGFloat
    var cursor: (CGPoint) -> NSCursor
    var onDrawStart: (CGPoint) -> Void
    var onDrawMove: (CGPoint) -> Void
    var onDrawEnd: () -> Void
    var onEraseDown: (CGPoint) -> Void
    var onEraseMove: (CGPoint) -> Void
    var onEraseUp: (CGPoint) -> Void
    var onElementDown: (CGPoint) -> Void
    var onElementDrag: (CGPoint) -> Void
    var onElementUp: () -> Void
    var onDoubleClick: (CGPoint) -> Bool
    var onKey: (CanvasKey) -> Bool
    var onEyedropper: (CGPoint) -> Void
    var onEyedropperHover: (CGPoint?) -> Void
    var onCancel: () -> Void

    func makeNSView(context: Context) -> InputView {
        let view = InputView()
        view.configuration = configuration
        return view
    }

    func updateNSView(_ nsView: InputView, context: Context) {
        nsView.configuration = configuration
        // A new tool, width or selection changes the pointer without the pointer moving.
        nsView.refreshCursor()
    }

    private var configuration: InputView.Configuration {
        InputView.Configuration(
            activeTool: activeTool,
            editingIsEnabled: editingIsEnabled,
            focusRequest: focusRequest,
            topInset: topInset,
            cursor: cursor,
            onDrawStart: onDrawStart,
            onDrawMove: onDrawMove,
            onDrawEnd: onDrawEnd,
            onEraseDown: onEraseDown,
            onEraseMove: onEraseMove,
            onEraseUp: onEraseUp,
            onElementDown: onElementDown,
            onElementDrag: onElementDrag,
            onElementUp: onElementUp,
            onDoubleClick: onDoubleClick,
            onKey: onKey,
            onEyedropper: onEyedropper,
            onEyedropperHover: onEyedropperHover,
            onCancel: onCancel
        )
    }

    final class InputView: NSView {
        struct Configuration {
            var activeTool: CanvasEditingTool = .draw
            var editingIsEnabled: Bool = false
            var focusRequest = 0
            /// How far below the canvas's top this view starts: the title bar's height.
            var topInset: CGFloat = 0
            /// The pointer for a whole-canvas point.
            var cursor: (CGPoint) -> NSCursor = { _ in .arrow }
            var onDrawStart: (CGPoint) -> Void = { _ in }
            var onDrawMove: (CGPoint) -> Void = { _ in }
            var onDrawEnd: () -> Void = {}
            var onEraseDown: (CGPoint) -> Void = { _ in }
            var onEraseMove: (CGPoint) -> Void = { _ in }
            var onEraseUp: (CGPoint) -> Void = { _ in }
            var onElementDown: (CGPoint) -> Void = { _ in }
            var onElementDrag: (CGPoint) -> Void = { _ in }
            var onElementUp: () -> Void = {}
            var onDoubleClick: (CGPoint) -> Bool = { _ in false }
            var onKey: (CanvasKey) -> Bool = { _ in false }
            var onEyedropper: (CGPoint) -> Void = { _ in }
            /// The pointer over the canvas with the eyedropper, or nil once it leaves.
            var onEyedropperHover: (CGPoint?) -> Void = { _ in }
            var onCancel: () -> Void = {}
        }

        var configuration = Configuration() {
            didSet {
                if configuration.focusRequest != oldValue.focusRequest {
                    window?.makeFirstResponder(self)
                }
            }
        }

        private var isDrawing = false
        private var isMovingElement = false
        private var isErasing = false

        override var acceptsFirstResponder: Bool { true }

        /// The canvas never moves the window; the title bar above it does.
        override var mouseDownCanMoveWindow: Bool { false }

        /// This view starts below the title bar band; callers work in whole-canvas coordinates.
        private func canvasPoint(_ event: NSEvent) -> CGPoint {
            let local = convert(event.locationInWindow, from: nil)
            return CGPoint(x: local.x, y: local.y + configuration.topInset)
        }

        override var isFlipped: Bool { true }

        // MARK: Pointer

        // The canvas sets its tool's pointer with AppKit's cursor updates and mouse moves, while it
        // is the view under the pointer: the window's hit test says whether the title bar or a
        // control above the canvas is in the way. SwiftUI's pointer styles only reapply when the
        // style changes, so leaving for the title bar and coming back left the wrong pointer.

        /// The pointer this view last set, so it only resets a pointer that is still its own.
        private var appliedCursor: NSCursor?

        override func updateTrackingAreas() {
            super.updateTrackingAreas()
            if trackingAreas.isEmpty {
                addTrackingArea(NSTrackingArea(
                    rect: .zero,
                    options: [.cursorUpdate, .mouseMoved, .mouseEnteredAndExited, .activeInKeyWindow, .inVisibleRect],
                    owner: self
                ))
            }
        }

        override func cursorUpdate(with event: NSEvent) {
            updateCursor(at: event.locationInWindow)
        }

        override func mouseMoved(with event: NSEvent) {
            updateCursor(at: event.locationInWindow)
            guard configuration.activeTool == .eyedropper, configuration.editingIsEnabled else { return }
            configuration.onEyedropperHover(appliedCursor != nil ? canvasPoint(event) : nil)
        }

        override func mouseExited(with event: NSEvent) {
            releaseCursor()
            configuration.onEyedropperHover(nil)
        }

        /// A new tool, width or selection changes the pointer without the pointer moving.
        func refreshCursor() {
            guard let window, window.isKeyWindow else { return }
            updateCursor(at: window.mouseLocationOutsideOfEventStream)
        }

        private func updateCursor(at locationInWindow: CGPoint) {
            guard let frameView = window?.contentView?.superview,
                  frameView.hitTest(locationInWindow) === self else {
                // Over a control above the canvas, which shows its own pointer or the arrow.
                return releaseCursor()
            }
            let local = convert(locationInWindow, from: nil)
            let cursor = configuration.cursor(CGPoint(x: local.x, y: local.y + configuration.topInset))
            if NSCursor.current != cursor { cursor.set() }
            appliedCursor = cursor
        }

        private func releaseCursor() {
            guard let appliedCursor else { return }
            self.appliedCursor = nil
            // A control that set its own pointer first keeps it.
            if NSCursor.current == appliedCursor { NSCursor.arrow.set() }
        }

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            window?.makeFirstResponder(self)
        }

        override func mouseDown(with event: NSEvent) {
            let point = canvasPoint(event)
            window?.makeFirstResponder(self)
            guard configuration.editingIsEnabled else { return }
            if event.clickCount == 2, configuration.onDoubleClick(point) {
                return
            }
            switch configuration.activeTool {
            case .draw:
                isDrawing = true
                configuration.onDrawStart(point)
            case .eyedropper:
                configuration.onEyedropper(point)
            case .erase:
                isErasing = true
                configuration.onEraseDown(point)
            case .text, .sticker:
                isMovingElement = true
                configuration.onElementDown(point)
            }
        }

        override func mouseDragged(with event: NSEvent) {
            guard configuration.editingIsEnabled else { return }
            let point = canvasPoint(event)
            if configuration.activeTool == .draw, isDrawing {
                configuration.onDrawMove(point)
            }
            if isMovingElement {
                configuration.onElementDrag(point)
            }
            if isErasing {
                configuration.onEraseMove(point)
            }
        }

        override func mouseUp(with event: NSEvent) {
            guard configuration.editingIsEnabled else {
                isDrawing = false
                isMovingElement = false
                isErasing = false
                return
            }
            if isMovingElement {
                isMovingElement = false
                configuration.onElementUp()
            }
            let point = canvasPoint(event)
            switch configuration.activeTool {
            case .draw:
                if isDrawing {
                    configuration.onDrawEnd()
                }
            case .erase:
                if isErasing {
                    configuration.onEraseUp(point)
                }
            default:
                break
            }
            isDrawing = false
            isErasing = false
        }

        override func keyDown(with event: NSEvent) {
            if let key = CanvasKey(event), configuration.onKey(key) {
                return
            }
            if event.keyCode == 53 {
                configuration.onCancel()
                return
            }
            super.keyDown(with: event)
        }
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}

/// Files shipped in Resources: in the app bundle, or in the source tree for `swift run`.
enum AppResources {
    static func url(_ path: String) -> URL? {
        [
            Bundle.main.resourceURL?.appendingPathComponent(path),
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent("Resources/\(path)"),
            URL(fileURLWithPath: #filePath)
                .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
                .appendingPathComponent("Resources/\(path)")
        ]
        .compactMap { $0 }
        .first { FileManager.default.fileExists(atPath: $0.path) }
    }
}

/// The toolbar drawings from the iOS app, shipped in Resources/Toolbar.
@MainActor
enum ToolbarArtwork {
    private static var cache: [String: NSImage] = [:]

    /// The drawing redrawn at `height` points, keeping its proportions.
    static func image(_ name: String, height: CGFloat) -> NSImage? {
        guard let source = image(name), source.size.height > 0 else { return nil }
        let size = NSSize(width: source.size.width * height / source.size.height, height: height)
        let scaled = NSImage(size: size, flipped: false) { rect in
            source.draw(in: rect)
            return true
        }
        scaled.isTemplate = source.isTemplate
        return scaled
    }

    static func image(_ name: String) -> NSImage? {
        if let cached = cache[name] { return cached }
        guard let url = AppResources.url("Toolbar/\(name)"), let image = NSImage(contentsOf: url) else { return nil }
        // The text glyph is a single-colour SVG, tinted like a symbol; the others are illustrations.
        image.isTemplate = url.pathExtension == "svg"
        cache[name] = image
        return image
    }
}

/// The canvas's keyboard, the same in the window and in Quick Draw. The canvas only has the
/// keyboard when no text is being typed, so single letters never fire mid-sentence.
enum CanvasKey: Equatable {
    case delete, `return`, escape
    case arrow(dx: CGFloat, dy: CGFloat)
    case undo, redo, clearCanvas, duplicate
    case tool(CanvasEditingTool)

    init?(_ event: NSEvent) {
        let modifiers = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        let step: CGFloat = modifiers.contains(.shift) ? 10 : 1
        if modifiers.contains(.command) {
            if event.keyCode == 51, modifiers.subtracting([.command, .function]).isEmpty {
                self = .clearCanvas
                return
            }
            if event.charactersIgnoringModifiers?.lowercased() == "d", modifiers.subtracting([.command]).isEmpty {
                self = .duplicate
                return
            }
            guard event.charactersIgnoringModifiers?.lowercased() == "z" else { return nil }
            self = modifiers.contains(.shift) ? .redo : .undo
            return
        }
        guard modifiers.subtracting([.shift, .function, .numericPad]).isEmpty else { return nil }
        switch event.keyCode {
        case 51, 117: self = .delete
        case 36, 76: self = .return
        case 53: self = .escape
        case 123: self = .arrow(dx: -step, dy: 0)
        case 124: self = .arrow(dx: step, dy: 0)
        case 125: self = .arrow(dx: 0, dy: step)
        case 126: self = .arrow(dx: 0, dy: -step)
        default:
            guard let tool = CanvasToolSlot.allCases.first(where: { $0.shortcut == event.charactersIgnoringModifiers?.lowercased() })?.tool else {
                return nil
            }
            self = .tool(tool)
        }
    }
}

/// The canvas's pointers.
@MainActor
enum CanvasCursors {
    private static var tips: [Int: NSCursor] = [:]

    /// A brush or eraser tip, outlined dark inside light so it reads on any colour, as in
    /// Photoshop. Tips too small to aim by show a crosshair instead.
    static func tip(diameter: CGFloat) -> NSCursor {
        let key = Int(diameter.rounded())
        if let cached = tips[key] { return cached }
        let crosshair = diameter < 6
        let side = crosshair ? 15 : ceil(diameter) + 4
        let image = NSImage(size: NSSize(width: side, height: side), flipped: false) { rect in
            let centre = CGPoint(x: rect.midX, y: rect.midY)
            func outline(_ path: NSBezierPath) {
                NSColor.white.withAlphaComponent(0.9).setStroke()
                path.lineWidth = 3
                path.stroke()
                NSColor.black.withAlphaComponent(0.85).setStroke()
                path.lineWidth = 1
                path.stroke()
            }
            if crosshair {
                let path = NSBezierPath()
                path.move(to: CGPoint(x: centre.x, y: 2)); path.line(to: CGPoint(x: centre.x, y: side - 2))
                path.move(to: CGPoint(x: 2, y: centre.y)); path.line(to: CGPoint(x: side - 2, y: centre.y))
                outline(path)
            } else {
                outline(NSBezierPath(ovalIn: CGRect(x: 2, y: 2, width: side - 4, height: side - 4)))
            }
            return true
        }
        let cursor = NSCursor(image: image, hotSpot: CGPoint(x: side / 2, y: side / 2))
        tips[key] = cursor
        return cursor
    }

    /// An eyedropper whose tip is the hotspot.
    static let eyedropper: NSCursor = {
        let side: CGFloat = 22
        let configuration = NSImage.SymbolConfiguration(pointSize: 15, weight: .medium)
        let symbol = NSImage(systemSymbolName: "eyedropper", accessibilityDescription: nil)?.withSymbolConfiguration(configuration)
        let image = NSImage(size: NSSize(width: side, height: side), flipped: false) { rect in
            guard let symbol else { return false }
            let drawn = CGRect(x: 1, y: 1, width: symbol.size.width, height: symbol.size.height)
            // A light halo, then the dark glyph, so it reads on any colour.
            for (dx, dy) in [(-1, 0), (1, 0), (0, -1), (0, 1)] {
                tinted(symbol, .white).draw(in: drawn.offsetBy(dx: CGFloat(dx), dy: CGFloat(dy)))
            }
            tinted(symbol, .black).draw(in: drawn)
            return true
        }
        // The symbol's tip is its lower-left corner; hot spots count from the top left.
        return NSCursor(image: image, hotSpot: CGPoint(x: 2, y: side - 2))
    }()

    private static func tinted(_ image: NSImage, _ colour: NSColor) -> NSImage {
        NSImage(size: image.size, flipped: false) { rect in
            image.draw(in: rect)
            colour.set()
            rect.fill(using: .sourceAtop)
            return true
        }
    }
}

/// The canvas drawn once for the eyedropper, kept until the drawing, size or artwork changes.
@MainActor
final class ColourSamplerCache {
    struct Key: Equatable {
        let state: DrawingState
        let size: CGSize
        let mode: CanvasStrokeRenderMode
        let stickers: Int
    }

    var key: Key?
    var sampler: CanvasColourSampler?
}

/// The colour under the eyedropper, in a ringed circle beside it, as the iPhone's loupe.
private struct EyedropperLoupe: View {
    let colour: UInt32

    var body: some View {
        Circle()
            .fill(Color(argb: colour))
            .frame(width: 40, height: 40)
            .overlay(Circle().strokeBorder(.white, lineWidth: 3))
            .overlay(Circle().strokeBorder(.black.opacity(0.15), lineWidth: 0.5).padding(-0.5))
            .shadow(color: .black.opacity(0.3), radius: 6, y: 2)
            .animation(.easeOut(duration: 0.08), value: colour)
            .accessibilityHidden(true)
    }
}
