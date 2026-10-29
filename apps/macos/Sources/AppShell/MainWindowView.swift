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

struct MainWindowView: View {
    @ObservedObject var router: AppRouter
    @ObservedObject var authController: AuthSessionController
    @ObservedObject var appStateController: AppStateController
    @ObservedObject var syncController: CanvasSyncController
    @ObservedObject var overlayController: OverlayController
    @ObservedObject var stickerLoader: StickerImageLoader

    var body: some View {
        HStack(spacing: 0) {
            sidebar

            Divider()

            NavigationStack(path: $router.path) {
                RoutePlaceholderView(
                    route: router.selectedRoute,
                    authController: authController,
                    appStateController: appStateController,
                    syncController: syncController,
                    overlayController: overlayController,
                    stickerLoader: stickerLoader,
                    onOpen: openRoute,
                    onPush: router.push
                )
                .navigationDestination(for: AppRoute.self) { route in
                    RoutePlaceholderView(
                        route: route,
                        authController: authController,
                        appStateController: appStateController,
                        syncController: syncController,
                        overlayController: overlayController,
                        stickerLoader: stickerLoader,
                        onOpen: openRoute,
                        onPush: router.push
                    )
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text(router.selectedRoute.title)
                    .font(.headline)
            }
        }
        .frame(minWidth: 920, minHeight: 640)
    }

    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Mulberry")
                .font(.headline)
                .padding(.bottom, 12)

            ForEach(sidebarRoutes) { route in
                Button {
                    openRoute(route)
                } label: {
                    Label(route.title, systemImage: iconName(for: route))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .background(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(router.selectedRoute == route ? Color.accentColor.opacity(0.18) : Color.clear)
                )
                .foregroundStyle(router.selectedRoute == route ? Color.primary : Color.secondary)
            }

            Spacer()
        }
        .padding(16)
        .frame(width: 220)
        .background(Color(nsColor: .controlBackgroundColor))
    }

    private var sidebarRoutes: [AppRoute] {
        authController.state.isSignedIn ? AppRoute.sidebarRoutes : [.authLanding, .settings]
    }

    private func openRoute(_ route: AppRoute) {
        if authController.state.isSignedIn || route == .settings || route == .authLanding {
            router.open(route)
        } else {
            router.open(.authLanding)
        }
    }

    private func iconName(for route: AppRoute) -> String {
        switch route {
        case .authLanding: "person.crop.circle"
        case .canvasHome: "scribble"
        case .overlayStatus: "rectangle.on.rectangle"
        case .pairingHub: "person.2"
        case .streak: "flame"
        case .settings: "gearshape"
        default: "circle"
        }
    }
}

private struct RoutePlaceholderView: View {
    let route: AppRoute
    @ObservedObject var authController: AuthSessionController
    @ObservedObject var appStateController: AppStateController
    @ObservedObject var syncController: CanvasSyncController
    @ObservedObject var overlayController: OverlayController
    @ObservedObject var stickerLoader: StickerImageLoader
    let onOpen: (AppRoute) -> Void
    let onPush: (AppRoute) -> Void

    var body: some View {
        Group {
            if route.isFullAppCanvasRoute {
                FullAppCanvasEditor(
                    authController: authController,
                    appStateController: appStateController,
                    syncController: syncController,
                    stickerLoader: stickerLoader
                )
                .padding(20)
            } else {
                VStack(alignment: .leading, spacing: 18) {
                    Text(route.title)
                        .font(.largeTitle.weight(.semibold))

                    Text(description)
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)

                    controls

                    Spacer()
                }
                .padding(32)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .navigationTitle(route.title)
    }

    @ViewBuilder
    private var controls: some View {
        switch route {
        case .canvasHome, .canvasSurface:
            EmptyView()
        case .authLanding:
            VStack(alignment: .leading, spacing: 12) {
                Text(authController.state.statusDetail)
                    .font(.body)
                    .foregroundStyle(.secondary)

                Button {
                    Task {
                        await authController.signIn()
                        if authController.state.isSignedIn {
                            onOpen(.canvasHome)
                        }
                    }
                } label: {
                    Label(authController.state.isBusy ? "Signing In..." : "Sign in with Google", systemImage: "person.crop.circle.badge.checkmark")
                }
                .disabled(authController.state.isBusy)

                if case let .failed(failure) = authController.state {
                    Text(failure.message)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        case .overlayStatus:
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Button(overlayController.isVisible ? "Hide Overlay" : "Show Overlay") {
                        overlayController.isVisible ? overlayController.hide() : overlayController.show()
                    }
                    Button("Reset Position", action: overlayController.resetPosition)
                }

                Divider()

                Text("Selected display: \(overlayController.selectedDisplayName)")
                    .font(.headline)
                let selectedDisplayID = overlayController.selectedDisplayID ?? overlayController.displays.first?.id
                ForEach(overlayController.displays) { display in
                    Button {
                        overlayController.selectDisplay(id: display.id)
                    } label: {
                        HStack {
                            Image(systemName: selectedDisplayID == display.id ? "checkmark.circle.fill" : "circle")
                            VStack(alignment: .leading) {
                                Text(display.name)
                                Text(display.frameDescription)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }

                Divider()

                Text("Frame: \(overlayController.currentFrameDescription)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text("Hotkey: \(overlayController.hotKeyStatus.message)")
                    .font(.caption)
                    .foregroundStyle(overlayController.hotKeyStatus.isRegistered ? Color.secondary : Color.orange)
            }
        case .pairingHub:
            VStack(alignment: .leading, spacing: 12) {
                if let bootstrap = appStateController.loadState.bootstrap {
                    Text(bootstrap.isPaired ? "Paired with \(bootstrap.partnerTitle)" : "Not paired")
                        .font(.headline)
                    if let pairSessionID = bootstrap.pairSessionId {
                        Text("Pair session: \(pairSessionID)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Button("Enter Invite Code") {
                    onPush(.inviteCodeEntry)
                }
            }
        case .streak:
            if let bootstrap = appStateController.loadState.bootstrap {
                Text(bootstrap.streakTitle)
                    .font(.title3.weight(.semibold))
            }
        case .settings:
            VStack(alignment: .leading, spacing: 12) {
                Text("Account: \(authController.state.statusTitle)")
                    .font(.headline)
                Text(authController.state.statusDetail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if let bootstrap = appStateController.loadState.bootstrap {
                    Text("Partner: \(bootstrap.partnerTitle)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                syncStatusBlock

                HStack {
                    Button("Overlay Settings") {
                        onPush(.overlayStatus)
                    }
                    Button("Recover Canvas Now") {
                        syncController.recoverNow()
                    }
                    .disabled(authController.state.isSignedIn == false)
                    if authController.state.isSignedIn {
                        Button("Sign Out") {
                            Task {
                                await authController.signOut()
                                onOpen(.authLanding)
                            }
                        }
                    }
                }
            }
        default:
            EmptyView()
        }
    }

    private var syncStatusBlock: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Sync: \(syncController.connectionState.title)")
                .font(.headline)
            Text("Mode: \(syncController.demand.title)")
                .font(.caption)
                .foregroundStyle(.secondary)
            Text("Revision: \(syncController.status.lastAppliedServerRevision)")
                .font(.caption)
                .foregroundStyle(.secondary)
            Text("Outbox: \(syncController.status.pendingCount) pending")
                .font(.caption)
                .foregroundStyle(.secondary)
            if let recoveredAt = syncController.status.lastSuccessfulRecoveryAt {
                Text("Last recovery: \(recoveredAt.formatted(date: .omitted, time: .standard))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if let lastError = syncController.status.lastError {
                Text(lastError)
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var description: String {
        switch route {
        case .bootstrap:
            "Placeholder for the startup resolver that will choose auth, onboarding, pairing, or canvas once real bootstrap services land."
        case .authLanding:
            "Use your Google account to connect this Mac to Mulberry."
        case .onboardingName:
            "Placeholder for name onboarding."
        case .onboardingDetails:
            "Placeholder for profile details onboarding."
        case .onboardingOverlay:
            "Placeholder for macOS overlay onboarding."
        case .pairingHub:
            "Placeholder for pairing status and invite controls."
        case .inviteCodeEntry:
            "Placeholder for entering a partner invite code."
        case .inviteAcceptance:
            "Placeholder for accepting an inbound invite."
        case .canvasHome:
            "Draw from this Mac using the same canvas operation model as Android."
        case .canvasSurface:
            "Full app drawing surface."
        case .overlayStatus:
            "Overlay is \(overlayController.isVisible ? "visible" : "hidden"). Passive mode is transparent, click-through, below normal windows, and pinned to the selected display across Spaces."
        case .overlayDisplay:
            "Placeholder for choosing the display that hosts the overlay."
        case .overlayHelp:
            "Placeholder for overlay troubleshooting and behavior notes."
        case .pairingHelp:
            "Placeholder for pairing help."
        case .streak:
            "Streak state is loaded from bootstrap and will expand into the richer mobile-style streak surface later."
        case .settings:
            "Placeholder for app settings inside the main Mulberry window."
        }
    }
}

private struct FullAppCanvasEditor: View {
    @ObservedObject var authController: AuthSessionController
    @ObservedObject var appStateController: AppStateController
    @ObservedObject var syncController: CanvasSyncController
    @ObservedObject var stickerLoader: StickerImageLoader

    @StateObject private var canvasModel = CanvasRenderModel()
    @State private var editor = CanvasStrokeEditingEngine()
    @State private var elementRenderer = CanvasElementRenderer()
    @State private var textSession: CanvasElementSession<CanvasTextElement>?
    @State private var stickerSession: CanvasElementSession<CanvasStickerElement>?
    @State private var drag: ElementDrag?

    /// An element being dragged to a new place; it saves as one update when released.
    private struct ElementDrag {
        let original: CanvasElement
        let start: CGPoint
        var current: CanvasElement
        var moved = false
    }

    private var editorOpen: Bool { textSession != nil || stickerSession != nil }
    private var userID: String? { appStateController.loadState.bootstrap?.userId }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            canvasStatusHeader

            HStack(alignment: .top, spacing: 18) {
                canvasSurface

                VStack(alignment: .leading, spacing: 12) {
                    toolTray
                    Divider()
                    syncSummary
                }
                .frame(width: 230)
                .disabled(editorOpen)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .overlay { elementEditor }
        .onAppear(perform: syncCanvasModel)
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

    private var canvasStatusHeader: some View {
        HStack(spacing: 12) {
            if let bootstrap = appStateController.loadState.bootstrap {
                Text(bootstrap.isPaired ? "Paired with \(bootstrap.partnerTitle)" : "Not paired")
                    .font(.headline)
            }
            Text("Sync: \(syncController.connectionState.title)")
                .font(.caption)
                .foregroundStyle(.secondary)
            if syncController.status.pendingCount > 0 {
                Text("\(syncController.status.pendingCount) pending")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }
        }
    }

    private var canvasSurface: some View {
        GeometryReader { proxy in
            ZStack(alignment: .topLeading) {
                CanvasRenderSurfaceView(
                    model: canvasModel,
                    surface: .fullApp,
                    showsEditingBackground: true
                )
                .scaleEffect(editor.viewportTransform.scale, anchor: .topLeading)
                .offset(
                    x: editor.viewportTransform.offset.x,
                    y: editor.viewportTransform.offset.y
                )

                CanvasInputOverlayView(
                    activeTool: editor.toolState.activeTool,
                    editingIsEnabled: editingIsEnabled,
                    onDrawStart: { point in
                        submit(editor.startStroke(at: point, surfaceSize: proxy.size))
                    },
                    onDrawMove: { point in
                        submit(editor.appendStrokePoint(at: point, surfaceSize: proxy.size))
                    },
                    onDrawEnd: {
                        submit(editor.finishStroke())
                    },
                    onErase: { point in
                        submit(editor.eraseStroke(at: point, in: syncController.drawing, surfaceSize: proxy.size))
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
                    onEyedropper: { point in
                        sampleEyedropper(at: point, surfaceSize: proxy.size)
                    },
                    onPan: { delta in
                        panViewport(by: delta, surfaceSize: proxy.size)
                    },
                    onZoom: { factor, anchor in
                        editor.viewportTransform = editor.viewportTransform.zoomed(by: factor, around: anchor)
                    },
                    onCancel: {
                        if editor.toolState.activeTool == .eyedropper {
                            editor.toolState.setActiveTool(editor.toolState.lastNonNoneTool)
                        }
                    }
                )

                if editingIsEnabled == false {
                    Text(editingDisabledMessage)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(.regularMaterial, in: Capsule())
                        .padding(14)
                        .allowsHitTesting(false)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .stroke(Color.secondary.opacity(0.24), lineWidth: 1)
            }
        }
        .aspectRatio(9.0 / 20.0, contentMode: .fit)
        .frame(maxHeight: .infinity)
    }

    private var toolTray: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 6) {
                toolButton("Brush", systemImage: "paintbrush.pointed", tool: .draw)
                toolButton("Eraser", systemImage: "eraser", tool: .erase)
                toolButton("Text", systemImage: "textformat", tool: .text)
                toolButton("Stickers", systemImage: "face.smiling", tool: .sticker)
                toolButton("Eyedropper", systemImage: "eyedropper", tool: .eyedropper)
            }

            HStack {
                Button {
                    submit(editor.undo())
                } label: {
                    Label("Undo", systemImage: "arrow.uturn.backward")
                }
                .disabled(editor.canUndo == false)
                .keyboardShortcut("z", modifiers: .command)

                Button {
                    submit(editor.redo())
                } label: {
                    Label("Redo", systemImage: "arrow.uturn.forward")
                }
                .disabled(editor.canRedo == false)
                .keyboardShortcut("Z", modifiers: [.command, .shift])
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Width")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Slider(
                    value: Binding(
                        get: { Double(editor.toolState.selectedBrushWidthPx) },
                        set: { editor.toolState.setBrushWidth(Float($0)) }
                    ),
                    in: Double(CanvasEditingDefaults.minBrushWidthPx)...Double(CanvasEditingDefaults.maxBrushWidthPx)
                )
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Color")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                LazyVGrid(columns: Array(repeating: GridItem(.fixed(24), spacing: 8), count: 5), spacing: 8) {
                    ForEach(CanvasEditingDefaults.palette, id: \.self) { color in
                        Button {
                            editor.toolState.setSelectedColor(color)
                        } label: {
                            Circle()
                                .fill(Color(argb: color))
                                .frame(width: 22, height: 22)
                                .overlay {
                                    Circle()
                                        .stroke(editor.toolState.selectedColorArgb == color ? Color.primary : Color.clear, lineWidth: 2)
                                }
                        }
                        .buttonStyle(.plain)
                    }
                }
            }

            HStack {
                Button {
                    editor.viewportTransform = editor.viewportTransform.zoomed(by: 1.2, around: CGPoint(x: 160, y: 280))
                } label: {
                    Label("Zoom In", systemImage: "plus.magnifyingglass")
                }
                .keyboardShortcut("=", modifiers: .command)

                Button {
                    editor.viewportTransform = editor.viewportTransform.zoomed(by: 1 / 1.2, around: CGPoint(x: 160, y: 280))
                } label: {
                    Label("Zoom Out", systemImage: "minus.magnifyingglass")
                }
                .keyboardShortcut("-", modifiers: .command)
            }

            Button {
                editor.viewportTransform = CanvasViewportTransform()
            } label: {
                Label("Reset Zoom", systemImage: "arrow.counterclockwise")
            }
            .keyboardShortcut("0", modifiers: .command)

            Button(role: .destructive) {
                editor.requestClearCanvas()
            } label: {
                Label("Clear", systemImage: "trash")
            }
        }
        .buttonStyle(.bordered)
        .controlSize(.small)
    }

    private var syncSummary: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Revision: \(syncController.status.lastAppliedServerRevision)")
            Text("Outbox: \(syncController.status.pendingCount) pending")
            Text("Diagnostics: \(canvasModel.diagnostics.count)")
                .foregroundStyle(canvasModel.diagnostics.isEmpty ? Color.secondary : Color.orange)
            if let lastError = syncController.status.lastError {
                Text(lastError)
                    .foregroundStyle(.orange)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .font(.caption)
        .foregroundStyle(.secondary)
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

    private func toolButton(_ title: String, systemImage: String, tool: CanvasEditingTool) -> some View {
        Button {
            editor.toolState.setActiveTool(tool)
        } label: {
            Image(systemName: systemImage)
                .frame(width: 18, height: 18)
        }
        .buttonStyle(.borderedProminent)
        .tint(editor.toolState.activeTool == tool ? .accentColor : .gray)
        .help(title)
        .accessibilityLabel(title)
        .accessibilityAddTraits(editor.toolState.activeTool == tool ? .isSelected : [])
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
        let contentPoint = editor.viewportTransform.contentPoint(fromRenderedPoint: renderedPoint)
        guard let color = CanvasOffscreenRenderer.sampleColor(
            input: CanvasRenderInput(
                state: syncController.drawing,
                viewport: CGRect(origin: .zero, size: surfaceSize),
                strokeRenderMode: canvasModel.strokeRenderMode,
                surface: .fullApp,
                showsEditingBackground: false,
                stickerImages: stickerLoader.images
            ),
            at: contentPoint
        ) else {
            return
        }
        editor.toolState.commitEyedropperColor(color)
    }

    // MARK: Text and stickers

    @ViewBuilder private var elementEditor: some View {
        if let session = textSession {
            CanvasTextEditorOverlay(
                session: session,
                palette: CanvasEditingDefaults.palette,
                onDone: { element in
                    textSession = nil
                    editor.toolState.textColorArgb = element.colorArgb
                    submit(editor.commit(.text(element), isNew: session.isNew, in: syncController.drawing))
                    syncCanvasModel()
                },
                onCancel: { textSession = nil; syncCanvasModel() },
                onDelete: {
                    textSession = nil
                    if !session.isNew { submit(editor.delete(elementID: session.element.id, in: syncController.drawing)) }
                    syncCanvasModel()
                }
            )
            .id(session.element.id)
        } else if let session = stickerSession, let userID {
            CanvasStickerEditorOverlay(
                session: session,
                catalog: stickerLoader.catalog(user: userID),
                user: userID,
                store: stickerLoader.store,
                onDone: { element in
                    stickerSession = nil
                    if !element.sticker.stickerId.isEmpty { stickerLoader.catalog(user: userID).use(element.sticker) }
                    submit(editor.commit(.sticker(element), isNew: session.isNew, in: syncController.drawing))
                    syncCanvasModel()
                },
                onCancel: { stickerSession = nil; syncCanvasModel() },
                onDelete: {
                    stickerSession = nil
                    if !session.isNew { submit(editor.delete(elementID: session.element.id, in: syncController.drawing)) }
                    syncCanvasModel()
                }
            )
            .id(session.element.id)
        }
    }

    private func normalized(_ renderedPoint: CGPoint, canvasSize: CGSize) -> DrawingPoint {
        editor.viewportTransform.normalizedPoint(fromRenderedPoint: renderedPoint, surfaceSize: canvasSize)
    }

    private func element(at renderedPoint: CGPoint, canvasSize: CGSize) -> CanvasElement? {
        elementRenderer.stickerImages = stickerLoader.images
        let point = editor.viewportTransform.contentPoint(fromRenderedPoint: renderedPoint)
        return elementRenderer.hit(syncController.drawing.canvasElements, at: point, canvasSize: canvasSize)
    }

    /// With the text or sticker tool, pressing an element starts moving it; pressing empty
    /// canvas starts a new one there.
    private func elementDown(at point: CGPoint, canvasSize: CGSize) {
        guard !editorOpen else { return }
        if let hit = element(at: point, canvasSize: canvasSize) {
            drag = ElementDrag(original: hit, start: point, current: hit)
            return
        }
        let center = normalized(point, canvasSize: canvasSize)
        switch editor.toolState.activeTool {
        case .text:
            textSession = CanvasElementSession(element: CanvasTextElement(center: center, colorArgb: editor.toolState.textColorArgb), isNew: true)
        case .sticker:
            guard let userID else { return }
            let sticker = stickerLoader.catalog(user: userID).defaultSticker ?? StickerReference(packKey: "", packVersion: 0, stickerId: "")
            stickerSession = CanvasElementSession(element: CanvasStickerElement(center: center, sticker: sticker), isNew: true)
        default:
            return
        }
        syncCanvasModel()
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
        syncCanvasModel()
    }

    /// A drag saves the new position; a click without moving opens the element's editor.
    private func elementReleased() {
        guard let drag else { return }
        self.drag = nil
        if drag.moved {
            submit(editor.update(drag.current, in: syncController.drawing))
        } else {
            open(drag.original)
        }
        syncCanvasModel()
    }

    /// Double-clicking an element edits it with any tool.
    private func editElement(at point: CGPoint, canvasSize: CGSize) -> Bool {
        guard !editorOpen, let hit = element(at: point, canvasSize: canvasSize) else { return false }
        open(hit)
        return true
    }

    private func open(_ element: CanvasElement) {
        switch element {
        case let .text(text):
            textSession = CanvasElementSession(element: text, isNew: false)
        case let .sticker(sticker):
            stickerSession = CanvasElementSession(element: sticker, isNew: false)
        }
        syncCanvasModel()
    }

    private func panViewport(by delta: CGSize, surfaceSize: CGSize) {
        guard editor.viewportTransform.scale > CanvasEditingDefaults.minViewportScale else { return }
        let scale = editor.viewportTransform.scale
        let minX = -surfaceSize.width * (scale - 1)
        let minY = -surfaceSize.height * (scale - 1)
        let nextOffset = CGPoint(
            x: (editor.viewportTransform.offset.x + delta.width).clamped(to: minX...0),
            y: (editor.viewportTransform.offset.y + delta.height).clamped(to: minY...0)
        )
        editor.viewportTransform = CanvasViewportTransform(scale: scale, offset: nextOffset)
    }

    private func syncCanvasModel() {
        var state = syncController.drawing
        if let drag, drag.moved {
            try? state.apply(.update(drag.current))
        }
        // The open editor shows the element, so the canvas behind it leaves it out.
        if let editing = textSession.map({ CanvasElement.text($0.element) }) ?? stickerSession.map({ CanvasElement.sticker($0.element) }),
           state.canvasElements.contains(where: { $0.id == editing.id }) {
            try? state.apply(.delete(editing))
        }
        canvasModel.state = state
        canvasModel.stickerImages = stickerLoader.images
        canvasModel.strokeRenderMode = CanvasStrokeRenderMode(
            rawBackendValue: appStateController.loadState.bootstrap?.canvasStrokeRenderMode
        )
    }
}

private struct CanvasInputOverlayView: NSViewRepresentable {
    var activeTool: CanvasEditingTool
    var editingIsEnabled: Bool
    var onDrawStart: (CGPoint) -> Void
    var onDrawMove: (CGPoint) -> Void
    var onDrawEnd: () -> Void
    var onErase: (CGPoint) -> Void
    var onElementDown: (CGPoint) -> Void
    var onElementDrag: (CGPoint) -> Void
    var onElementUp: () -> Void
    var onDoubleClick: (CGPoint) -> Bool
    var onEyedropper: (CGPoint) -> Void
    var onPan: (CGSize) -> Void
    var onZoom: (CGFloat, CGPoint) -> Void
    var onCancel: () -> Void

    func makeNSView(context: Context) -> InputView {
        let view = InputView()
        view.configuration = configuration
        return view
    }

    func updateNSView(_ nsView: InputView, context: Context) {
        nsView.configuration = configuration
    }

    private var configuration: InputView.Configuration {
        InputView.Configuration(
            activeTool: activeTool,
            editingIsEnabled: editingIsEnabled,
            onDrawStart: onDrawStart,
            onDrawMove: onDrawMove,
            onDrawEnd: onDrawEnd,
            onErase: onErase,
            onElementDown: onElementDown,
            onElementDrag: onElementDrag,
            onElementUp: onElementUp,
            onDoubleClick: onDoubleClick,
            onEyedropper: onEyedropper,
            onPan: onPan,
            onZoom: onZoom,
            onCancel: onCancel
        )
    }

    final class InputView: NSView {
        struct Configuration {
            var activeTool: CanvasEditingTool = .none
            var editingIsEnabled: Bool = false
            var onDrawStart: (CGPoint) -> Void = { _ in }
            var onDrawMove: (CGPoint) -> Void = { _ in }
            var onDrawEnd: () -> Void = {}
            var onErase: (CGPoint) -> Void = { _ in }
            var onElementDown: (CGPoint) -> Void = { _ in }
            var onElementDrag: (CGPoint) -> Void = { _ in }
            var onElementUp: () -> Void = {}
            var onDoubleClick: (CGPoint) -> Bool = { _ in false }
            var onEyedropper: (CGPoint) -> Void = { _ in }
            var onPan: (CGSize) -> Void = { _ in }
            var onZoom: (CGFloat, CGPoint) -> Void = { _, _ in }
            var onCancel: () -> Void = {}
        }

        var configuration = Configuration()
        private var isDrawing = false
        private var isMovingElement = false
        private var isSpaceDown = false
        private var lastPanPoint: CGPoint?

        override var acceptsFirstResponder: Bool { true }
        override var isFlipped: Bool { true }

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            window?.makeFirstResponder(self)
        }

        override func mouseDown(with event: NSEvent) {
            window?.makeFirstResponder(self)
            guard configuration.editingIsEnabled else { return }
            let point = convert(event.locationInWindow, from: nil)
            if isSpaceDown {
                lastPanPoint = point
                return
            }
            if event.clickCount == 2, configuration.onDoubleClick(point) {
                return
            }
            switch configuration.activeTool {
            case .draw:
                isDrawing = true
                configuration.onDrawStart(point)
            case .eyedropper:
                configuration.onEyedropper(point)
            case .text, .sticker:
                isMovingElement = true
                configuration.onElementDown(point)
            default:
                break
            }
        }

        override func mouseDragged(with event: NSEvent) {
            guard configuration.editingIsEnabled else { return }
            let point = convert(event.locationInWindow, from: nil)
            if isSpaceDown {
                if let lastPanPoint {
                    configuration.onPan(CGSize(width: point.x - lastPanPoint.x, height: point.y - lastPanPoint.y))
                }
                lastPanPoint = point
                return
            }
            if configuration.activeTool == .draw, isDrawing {
                configuration.onDrawMove(point)
            }
            if isMovingElement {
                configuration.onElementDrag(point)
            }
        }

        override func mouseUp(with event: NSEvent) {
            guard configuration.editingIsEnabled else {
                isDrawing = false
                isMovingElement = false
                lastPanPoint = nil
                return
            }
            if isMovingElement {
                isMovingElement = false
                configuration.onElementUp()
            }
            let point = convert(event.locationInWindow, from: nil)
            switch configuration.activeTool {
            case .draw:
                if isDrawing {
                    configuration.onDrawEnd()
                }
            case .erase:
                configuration.onErase(point)
            default:
                break
            }
            isDrawing = false
            lastPanPoint = nil
        }

        override func scrollWheel(with event: NSEvent) {
            if event.modifierFlags.contains(.command) {
                let factor = event.scrollingDeltaY > 0 ? 1.08 : 1 / 1.08
                configuration.onZoom(factor, convert(event.locationInWindow, from: nil))
            } else {
                configuration.onPan(CGSize(width: event.scrollingDeltaX, height: event.scrollingDeltaY))
            }
        }

        override func magnify(with event: NSEvent) {
            configuration.onZoom(1 + event.magnification, convert(event.locationInWindow, from: nil))
        }

        override func keyDown(with event: NSEvent) {
            if event.keyCode == 53 {
                configuration.onCancel()
                return
            }
            if event.keyCode == 49 {
                isSpaceDown = true
                return
            }
            super.keyDown(with: event)
        }

        override func keyUp(with event: NSEvent) {
            if event.keyCode == 49 {
                isSpaceDown = false
                lastPanPoint = nil
                return
            }
            super.keyUp(with: event)
        }
    }
}


private extension AppRoute {
    var isFullAppCanvasRoute: Bool {
        self == .canvasHome || self == .canvasSurface
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
