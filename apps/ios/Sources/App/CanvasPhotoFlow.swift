import AVFoundation
import PhotosUI
import SwiftUI

/// A photo on its way to the canvas, or one already there being restyled.
struct CanvasPhotoSession: Identifiable, Equatable {
    let id: String
    /// The photo on the canvas being edited; nil for a new one, which starts at the camera.
    let element: CanvasPhotoElement?
    /// Where the canvas is on screen, so the photo is edited exactly where it will land. It moves
    /// when the iPad turns.
    var canvasFrame: CGRect
    /// On iPad the camera skips the editor: the photo goes straight onto the canvas, Simple at the
    /// default size in the middle, to be styled there in place.
    var placesDirectly = false

    var isNew: Bool { element == nil }
}

/// The Camera tool: a camera with the library a tap away, then the photo shrinking into place
/// over the dimmed canvas, where it takes a frame, a polaroid's paper or a sticker's outline.
struct CanvasPhotoFlow: View {
    let session: CanvasPhotoSession
    let user: String?
    let onDone: (CanvasPhotoElement) -> Void
    let onDelete: () -> Void
    let onCancel: () -> Void

    /// `placing` is iPad's: the photo waits in its place on the canvas while it uploads.
    private enum Phase { case camera, editing, placing }
    private enum CutoutState: Equatable { case idle, working, ready, missing }

    @StateObject private var camera = CanvasCamera()
    @State private var phase: Phase
    @State private var photo: UIImage?
    @State private var cutout: UIImage?
    @State private var cutoutState: CutoutState = .idle
    @State private var style: CanvasPhotoStyle
    /// Where the bare photo sits before it shrinks into place: the camera's preview, or the
    /// library button it was picked from.
    @State private var origin: CGRect = .zero
    @State private var center: CGPoint
    @State private var long: CGFloat
    @State private var rotation: Angle
    @State private var upload: Task<CanvasPhotoUpload, Error>?
    @State private var uploadedPhotoID: String?
    @State private var uploadedCutoutID: String?
    @State private var saving = false
    @State private var failed = false
    @State private var flash = false
    @State private var pickerItem: PhotosPickerItem?
    @State private var loadingPick = false
    @State private var closing = false
    @State private var panel: Panel?
    @State private var appeared = false
    /// The canvas has the photo now: the editor's copy goes at once, so nothing rides the
    /// editor's own exit.
    @State private var handedOff = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(session: CanvasPhotoSession, user: String?, onDone: @escaping (CanvasPhotoElement) -> Void,
         onDelete: @escaping () -> Void, onCancel: @escaping () -> Void) {
        self.session = session; self.user = user
        self.onDone = onDone; self.onDelete = onDelete; self.onCancel = onCancel
        let canvas = session.canvasFrame
        if let element = session.element {
            _phase = State(initialValue: .editing)
            _style = State(initialValue: element.style)
            // It starts exactly where the canvas draws it, then rises to the middle to be edited.
            _center = State(initialValue: Self.home(element, canvas: canvas))
            // Already in memory from the canvas, so the first frame shows the photo as it is.
            let photo = user.flatMap { CanvasPhotoStore.shared.cachedImage(element.photoId, user: $0) }
            let cutout = user.flatMap { user in element.cutoutPhotoId.flatMap { CanvasPhotoStore.shared.cachedImage($0, user: user) } }
            _photo = State(initialValue: photo)
            _cutout = State(initialValue: cutout)
            _cutoutState = State(initialValue: cutout == nil ? .idle : .ready)
            _long = State(initialValue: CGFloat(element.renderScale) * canvas.width)
            _rotation = State(initialValue: .radians(element.rotationRad))
            _uploadedPhotoID = State(initialValue: element.photoId)
            _uploadedCutoutID = State(initialValue: element.cutoutPhotoId)
        } else {
            _phase = State(initialValue: .camera)
            _style = State(initialValue: .frame)
            _center = State(initialValue: CGPoint(x: canvas.midX, y: canvas.midY))
            _long = State(initialValue: session.placesDirectly
                ? CGFloat(CanvasPhotoElement.defaultScale) * canvas.width
                : min(canvas.width * 0.64, canvas.height * 0.46))
            _rotation = State(initialValue: .zero)
        }
    }

    private var motion: Animation? { reduceMotion ? .easeInOut(duration: 0.2) : .spring(duration: 0.6, bounce: 0.2) }

    var body: some View {
        GeometryReader { geometry in
            // The reader sits inside the safe area; the screen it lays out on runs edge to edge.
            let insets = geometry.safeAreaInsets
            let layout = CameraLayout(size: CGSize(width: geometry.size.width + insets.leading + insets.trailing,
                                                   height: geometry.size.height + insets.top + insets.bottom),
                                      insets: insets)
            ZStack {
                // One screen over the dimmed canvas, from the camera to the finished photo.
                CanvasEditorBackdrop(shown: appeared && !closing) { if phase == .editing, photo != nil { done() } }
                cameraLayer(layout)
                // A new photo is already where the canvas will draw it; an edited one fades back to its place.
                if let photo, !handedOff { card(photo) }
                switch phase {
                case .camera: cameraControls(layout).opacity(appeared && !closing ? 1 : 0)
                case .editing: editorControls.opacity(closing ? 0 : 1)
                case .placing: placingProgress(layout).opacity(closing ? 0 : 1)
                }
            }
            .ignoresSafeArea()
        }
        .environment(\.colorScheme, .dark)
        .presentationBackground(.clear)
        .onAppear {
            withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) { appeared = true }
            if !session.isNew {
                withAnimation(motion) { center = CGPoint(x: session.canvasFrame.midX, y: session.canvasFrame.midY) }
            }
        }
        .task {
            if let element = session.element { await loadExisting(element) } else { await camera.start() }
        }
        .onDisappear { camera.stop() }
        .onChange(of: pickerItem) { _, item in if let item { Task { await pick(item) } } }
        // Turned: the photo keeps its place and size on the canvas, wherever the canvas now is.
        .onChange(of: session.canvasFrame) { old, new in
            guard old.width > 0, old.height > 0, new.width > 0, new.height > 0 else { return }
            center = CGPoint(x: new.minX + (center.x - old.minX) / old.width * new.width,
                             y: new.minY + (center.y - old.minY) / old.height * new.height)
            long *= new.width / old.width
        }
    }

    // MARK: Camera

    /// The camera's rounded 3:4 window, as photos are, centred over the dimmed canvas, with the
    /// shutter row low on the screen where camera apps keep it.
    private struct CameraLayout {
        let size: CGSize
        let insets: EdgeInsets
        /// Where the shutter row's middle is.
        var controlsY: CGFloat { size.height - insets.bottom - 12 - 39 }
        var preview: CGRect {
            let top = insets.top + 8 + 44 + 12
            let bottom = controlsY - 39 - 16
            var width = size.width - 32
            var height = width * 4 / 3
            if height > bottom - top { height = max(0, bottom - top); width = height * 3 / 4 }
            let y = min(max((size.height - height) / 2, top), bottom - height)
            return CGRect(x: (size.width - width) / 2, y: y, width: width, height: height)
        }
        var library: CGRect { CGRect(x: 32, y: controlsY - 22, width: 44, height: 44) }
    }

    private func cameraLayer(_ layout: CameraLayout) -> some View {
        ZStack(alignment: .topLeading) {
            CanvasCameraPreview(session: camera.session)
                .opacity(camera.running ? 1 : 0)
                .background(.black)
                .overlay { cameraNotice }
            .frame(width: layout.preview.width, height: layout.preview.height)
            .overlay { Color.white.opacity(flash ? 0.6 : 0).allowsHitTesting(false) }
            .clipShape(.rect(cornerRadius: 22))
            .shadow(color: .black.opacity(0.35), radius: 24, y: 8)
            .scaleEffect(appeared || reduceMotion ? 1 : 0.94)
            .offset(x: layout.preview.minX, y: layout.preview.minY)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .opacity(phase == .camera && appeared && !closing ? 1 : 0)
        .allowsHitTesting(phase == .camera)
        .animation(.easeOut(duration: 0.25), value: camera.running)
    }

    /// Why there is no picture: access not given, or the camera taken away while open.
    @ViewBuilder private var cameraNotice: some View {
        if camera.access == .denied {
            ContentUnavailableView {
                Label("Camera access is off", systemImage: "camera")
            } description: {
                Text("Allow it to snap photos for your canvas.")
            } actions: {
                Button("Allow Access") {
                    if camera.canAsk {
                        Task { await camera.start() }
                    } else if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
                .prominentButtonStyle()
            }
            .background(Color(white: 0.08))
        } else if let reason = camera.interruption {
            ContentUnavailableView {
                Label("Camera unavailable", systemImage: "video.slash")
            } description: {
                Text(Self.interruptionMessage(reason))
            }
            .background(Color(white: 0.08))
        }
    }

    private static func interruptionMessage(_ reason: AVCaptureSession.InterruptionReason) -> String {
        switch reason {
        case .videoDeviceInUseByAnotherClient: "Another app is using the camera."
        case .videoDeviceNotAvailableWithMultipleForegroundApps: "The camera pauses while other apps share the screen."
        case .videoDeviceNotAvailableDueToSystemPressure: "Your iPhone is too warm to use the camera right now."
        default: "Try again in a moment."
        }
    }

    private func cameraControls(_ layout: CameraLayout) -> some View {
        ZStack(alignment: .topLeading) {
            HStack {
                roundButton("Close", symbol: "xmark", action: cancel)
                Spacer()
                if camera.hasFlash {
                    roundButton(camera.flashOn ? "Flash on" : "Flash off", symbol: camera.flashOn ? "bolt.fill" : "bolt.slash.fill",
                                tint: camera.flashOn ? .yellow : .white) { camera.flashOn.toggle() }
                }
                Spacer()
                Color.clear.frame(width: 44, height: 44)
            }
            .padding(.horizontal, 16)
            .frame(width: layout.size.width)
            .offset(y: layout.insets.top + 8)

            HStack {
                PhotosPicker(selection: $pickerItem, matching: .images, preferredItemEncoding: .current) {
                    ZStack {
                        if loadingPick { ProgressView().tint(.white) }
                        else { Image(systemName: "photo.on.rectangle").font(.system(size: 18, weight: .medium)) }
                    }
                    .foregroundStyle(.white)
                    .frame(width: 44, height: 44)
                    .glassEffect(.regular.interactive(), in: .rect(cornerRadius: 12))
                }
                .accessibilityLabel("Photo library")
                .disabled(loadingPick)
                Spacer()
                shutter
                Spacer()
                roundButton("Switch camera", symbol: "arrow.triangle.2.circlepath") { camera.flip() }
                    .disabled(!camera.running)
            }
            .padding(.horizontal, 32)
            .frame(width: layout.size.width)
            .offset(y: layout.controlsY - 39)

            // On iPad, a photo that couldn't be added leaves the camera open to try again.
            if session.placesDirectly, failed {
                Text("Couldn’t add the photo. Try again.")
                    .font(.subheadline).foregroundStyle(.white)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .glassEffect(.regular, in: .capsule)
                    .frame(width: layout.size.width)
                    .offset(y: layout.preview.maxY - 52)
                    .transition(.opacity)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .animation(.easeOut(duration: 0.2), value: failed)
        .onAppear { origin = layout.preview; libraryRect = layout.library }
        .onChange(of: layout.preview) { _, preview in origin = preview; libraryRect = layout.library }
        .transition(.opacity)
    }

    private var shutter: some View {
        Button { Task { await snap() } } label: {
            ZStack {
                Circle().strokeBorder(.white, lineWidth: 5).frame(width: 78, height: 78)
                Circle().fill(.white).frame(width: 64, height: 64)
            }
            .contentShape(Circle())
        }
        .buttonStyle(ShutterStyle())
        .disabled(!camera.running)
        .accessibilityLabel("Take photo")
    }

    private struct ShutterStyle: ButtonStyle {
        func makeBody(configuration: Configuration) -> some View {
            configuration.label.scaleEffect(configuration.isPressed ? 0.9 : 1)
                .animation(.spring(duration: 0.2, bounce: 0.4), value: configuration.isPressed)
        }
    }

    private func roundButton(_ title: String, symbol: String, tint: Color = .white, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 18, weight: .medium))
                .foregroundStyle(tint)
                .contentTransition(.symbolEffect(.replace))
                .frame(width: 44, height: 44)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .glassEffect(.regular.interactive(), in: .circle)
        .accessibilityLabel(title)
    }

    private func snap() async {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        withAnimation(.easeOut(duration: 0.06)) { flash = true }
        guard let image = await camera.capture() else { flash = false; return }
        let prepared = await Task.detached(priority: .userInitiated) { CanvasPhotoProcessing.prepared(image) }.value
        flash = false
        camera.stop()
        arrive(prepared)
    }

    private func pick(_ item: PhotosPickerItem) async {
        loadingPick = true
        defer { loadingPick = false; pickerItem = nil }
        guard let data = try? await item.loadTransferable(type: Data.self),
              let prepared = await Task.detached(priority: .userInitiated, operation: { CanvasPhotoProcessing.prepared(data: data) }).value
        else { return }
        camera.stop()
        // The picked photo grows out of the library button.
        arrive(prepared, from: libraryRect)
    }

    /// Where the library button is on screen, for a picked photo to grow out of.
    @State private var libraryRect: CGRect = .zero

    /// The photo is in: it freezes where it was seen, then shrinks onto the dimmed canvas.
    private func arrive(_ image: UIImage, from start: CGRect? = nil) {
        var instant = Transaction(); instant.disablesAnimations = true
        withTransaction(instant) {
            if let start, start != .zero { origin = start }
            photo = image
            failed = false
        }
        startUpload(image)
        if session.placesDirectly {
            UIImpactFeedbackGenerator(style: .soft).impactOccurred()
            DispatchQueue.main.async {
                withAnimation(motion) { phase = .placing }
                place(image)
            }
            return
        }
        startCutout(image)
        UIImpactFeedbackGenerator(style: .soft).impactOccurred()
        // A frame later, so the bare photo is on screen before it moves.
        DispatchQueue.main.async { withAnimation(motion) { phase = .editing } }
    }

    /// iPad: once the photo is uploaded it goes onto the canvas, Simple, in the middle, at the
    /// default size. If it can't be, the camera opens again with a message.
    private func place(_ image: UIImage) {
        let upload = upload
        Task {
            do {
                guard let user, let upload else { throw CancellationError() }
                let photoID = try await upload.value.photoId
                CanvasPhotoStore.shared.remember(image, photoId: photoID, user: user)
                guard photo === image, !closing else { return }
                uploadedPhotoID = photoID
                finish(photoID: photoID, cutoutID: nil, image: image)
            } catch {
                guard photo === image, !closing else { return }
                UINotificationFeedbackGenerator().notificationOccurred(.error)
                self.upload = nil
                withAnimation(.easeInOut(duration: 0.25)) { phase = .camera; photo = nil; failed = true }
                await camera.start()
            }
        }
    }

    private func placingProgress(_ layout: CameraLayout) -> some View {
        ZStack(alignment: .topLeading) {
            ProgressView()
                .controlSize(.large)
                .tint(.white)
                .frame(width: layout.size.width, height: 78)
                .offset(y: layout.controlsY - 39)
                .accessibilityLabel("Adding photo")
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .allowsHitTesting(false)
        .transition(.opacity)
    }

    // MARK: The photo

    private func card(_ image: UIImage) -> some View {
        let editing = phase != .camera
        return CanvasPhotoCard(photo: image, cutout: cutoutState == .ready ? cutout : nil, style: style, long: long,
                               framed: editing, bare: origin.size, bareCornerRadius: origin.width < 80 ? 12 : 22)
            .rotationEffect(editing ? rotation : .zero)
            .position(editing ? center : CGPoint(x: origin.midX, y: origin.midY))
            .allowsHitTesting(false)
            .accessibilityElement()
            .accessibilityLabel("Photo")
    }

    // MARK: Editing

    private var editorControls: some View {
        VStack(spacing: 0) {
            HStack {
                // A new photo can go back to the camera; Delete and Done work as in the other editors.
                if session.isNew {
                    roundButton("Retake", symbol: "arrow.counterclockwise", action: retake)
                }
                Spacer()
                Button(action: done) {
                    ZStack {
                        if saving { ProgressView().tint(.white) }
                        else { Image(systemName: "checkmark").font(.system(size: 19, weight: .semibold)) }
                    }
                    .frame(width: 24, height: 24)
                }
                .prominentButtonStyle()
                .buttonBorderShape(.circle)
                .controlSize(.large)
                .disabled(saving || photo == nil)
                .accessibilityLabel("Done")
                .accessibilityIdentifier("photoEditorDone")
            }
            .padding(.horizontal, 16)
            .padding(.top, safeTop + 8)
            Spacer()
            if failed {
                Text("Couldn’t add the photo. Try again.")
                    .font(.subheadline).foregroundStyle(.white.opacity(0.85))
                    .padding(.bottom, 10)
                    .transition(.opacity)
            }
            VStack(spacing: 10) {
                if let panel {
                    Group {
                        switch panel {
                        case .size: sizePanel
                        case .rotation: CanvasEditorRotationPanel(degrees: rotationDegrees)
                        }
                    }
                    .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
                }
                HStack(spacing: 10) {
                    styleToggle
                    GlassEffectContainer(spacing: 6) {
                        HStack(spacing: 6) {
                            panelButton(.size, title: "Size", symbol: "arrow.up.left.and.arrow.down.right")
                            panelButton(.rotation, title: "Rotation", symbol: "rotate.right")
                        }
                    }
                    Spacer(minLength: 0)
                    CanvasEditorButton.delete(action: delete)
                }
            }
            .animation(reduceMotion ? nil : .snappy(duration: 0.25), value: panel)
            .padding(.horizontal, 12)
            .padding(.bottom, safeBottom + 8)
        }
        .disabled(saving)
        .transition(.opacity)
        .animation(.easeOut(duration: 0.2), value: failed)
    }

    private var safeTop: CGFloat { window?.safeAreaInsets.top ?? 54 }
    private var safeBottom: CGFloat { window?.safeAreaInsets.bottom ?? 30 }
    private var window: UIWindow? { UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first }

    /// The three styles as one native segmented toggle, as icons.
    private var styleToggle: some View {
        Picker("Style", selection: Binding(get: { style }, set: { option in
            withAnimation(reduceMotion ? .easeInOut(duration: 0.15) : .spring(duration: 0.4, bounce: 0.25)) { style = option }
        })) {
            ForEach(CanvasPhotoStyle.allCases, id: \.self) { option in
                Image(systemName: Self.symbol(option))
                    .accessibilityLabel(Self.title(option))
                    .tag(option)
            }
        }
        .pickerStyle(.segmented)
        .controlSize(.large)
        .frame(width: 168)
        .sensoryFeedback(.selection, trigger: style)
    }

    static func symbol(_ style: CanvasPhotoStyle) -> String {
        switch style {
        case .frame: "photo.artframe"
        case .polaroid: "rectangle.portrait.tophalf.inset.filled"
        case .sticker: "person.and.background.dotted"
        }
    }

    private enum Panel { case size, rotation }

    /// Choosing the open panel again closes it, as in the text editor.
    private func panelButton(_ target: Panel, title: String, symbol: String) -> some View {
        CanvasEditorButton(title, symbol: symbol, selected: panel == target) { panel = panel == target ? nil : target }
    }

    private var sizePanel: some View {
        let width = max(1, session.canvasFrame.width)
        let range = CanvasPhotoElement.scaleRange
        return CanvasEditorSliderPanel {
            Image(systemName: "photo").font(.system(size: 12)).accessibilityHidden(true)
            Slider(value: Binding(get: { Double(long / width) }, set: { long = CGFloat($0) * width }), in: range)
                .accessibilityLabel("Size")
                .accessibilityValue("\(Int((Double(long / width) / CanvasPhotoElement.defaultScale * 100).rounded())) percent")
            Image(systemName: "photo").font(.system(size: 20)).accessibilityHidden(true)
        }
    }

    private var rotationDegrees: Binding<Double> {
        Binding(get: { CanvasEditorRotationPanel.degrees(fromRadians: rotation.radians) }, set: { rotation = .degrees($0) })
    }

    static func title(_ style: CanvasPhotoStyle) -> String {
        switch style {
        case .frame: "Simple"
        case .polaroid: "Polaroid"
        case .sticker: "Sticker"
        }
    }

    private func retake() {
        upload?.cancel()
        upload = nil
        withAnimation(.easeInOut(duration: 0.25)) {
            phase = .camera
            photo = nil
            cutout = nil
            cutoutState = .idle
            style = .frame
            rotation = .zero
            center = CGPoint(x: session.canvasFrame.midX, y: session.canvasFrame.midY)
            long = min(session.canvasFrame.width * 0.64, session.canvasFrame.height * 0.46)
        }
        Task { await camera.start() }
    }

    // MARK: Saving

    private func startUpload(_ image: UIImage) {
        guard let user else { return }
        upload = Task.detached(priority: .userInitiated) {
            guard let data = CanvasPhotoProcessing.jpeg(image) else { throw CancellationError() }
            return try await CanvasPhotoStore.shared.upload(data, contentType: "image/jpeg", user: user)
        }
    }

    private func startCutout(_ image: UIImage) {
        cutoutState = .working
        Task {
            let result = await Task.detached(priority: .userInitiated) { CanvasPhotoProcessing.cutout(image) }.value
            guard photo === image else { return }
            cutout = result
            withAnimation(.spring(duration: 0.4, bounce: 0.2)) { cutoutState = result == nil ? .missing : .ready }
        }
    }

    /// Loads what was not in memory: the photo, then its cut-out, or makes one for the sticker style.
    private func loadExisting(_ element: CanvasPhotoElement) async {
        guard let user else { return }
        if photo == nil { photo = await CanvasPhotoStore.shared.image(element.photoId, user: user) }
        guard let image = photo, cutoutState != .ready else { return }
        if let cutoutID = element.cutoutPhotoId, let stored = await CanvasPhotoStore.shared.image(cutoutID, user: user) {
            cutout = stored
            cutoutState = .ready
        } else {
            startCutout(image)
        }
    }

    /// Where the canvas draws the photo, on screen.
    private static func home(_ element: CanvasPhotoElement, canvas: CGRect) -> CGPoint {
        CGPoint(x: canvas.minX + element.center.x * canvas.width, y: canvas.minY + element.center.y * canvas.height)
    }

    private func done() {
        guard !saving, let photo, let user else { return }
        saving = true
        failed = false
        Task {
            do {
                let photoID: String
                if let uploadedPhotoID { photoID = uploadedPhotoID }
                else {
                    guard let upload else { throw CancellationError() }
                    photoID = try await upload.value.photoId
                    uploadedPhotoID = photoID
                    CanvasPhotoStore.shared.remember(photo, photoId: photoID, user: user)
                }
                var cutoutID = uploadedCutoutID
                if style == .sticker, cutoutID == nil, cutoutState == .ready, let cutout, let data = CanvasPhotoProcessing.png(cutout) {
                    let result = try await CanvasPhotoStore.shared.upload(data, contentType: "image/png", user: user)
                    cutoutID = result.photoId
                    uploadedCutoutID = result.photoId
                    CanvasPhotoStore.shared.remember(cutout, photoId: result.photoId, user: user)
                }
                finish(photoID: photoID, cutoutID: cutoutID, image: photo)
            } catch {
                saving = false
                failed = true
                UINotificationFeedbackGenerator().notificationOccurred(.error)
            }
        }
    }

    private func finish(photoID: String, cutoutID: String?, image: UIImage) {
        let canvas = session.canvasFrame
        let width = max(1, canvas.width), height = max(1, canvas.height)
        var element = session.element ?? CanvasPhotoElement(center: .init(x: 0.5, y: 0.5), photoId: photoID,
                                                            aspectRatio: Double(image.size.width / max(1, image.size.height)))
        element.photoId = photoID
        element.cutoutPhotoId = cutoutID
        element.style = style
        if session.isNew {
            element.center = DrawingPoint(x: min(1, max(0, (center.x - canvas.minX) / width)), y: min(1, max(0, (center.y - canvas.minY) / height)))
        }
        element.scale = Double(long / width)
        element.rotationRad = rotation.radians
        if session.isNew {
            // Already where the canvas will draw it: only the dimming lifts away.
            withAnimation(.easeOut(duration: 0.18)) { closing = true } completion: { handOff { onDone(element) } }
        } else {
            // Settles back into its place on the canvas.
            withAnimation(motion) { center = Self.home(element, canvas: canvas); closing = true } completion: { handOff { onDone(element) } }
        }
    }

    /// Hides the editor's photo in the same moment the canvas shows its own.
    private func handOff(_ action: () -> Void) {
        var instant = Transaction()
        instant.disablesAnimations = true
        withTransaction(instant) { handedOff = true }
        action()
    }

    /// Deletes a photo on the canvas, or discards a new one.
    private func delete() {
        guard !session.isNew else { return cancel() }
        guard !closing else { return }
        withAnimation(.easeIn(duration: 0.15)) { closing = true; photo = nil } completion: { onDelete() }
    }

    private func cancel() {
        guard !closing else { return }
        upload?.cancel()
        camera.stop()
        if let original = session.element, photo != nil {
            // Unchanged, it settles back into its place.
            withAnimation(motion) {
                style = original.style
                long = CGFloat(original.renderScale) * session.canvasFrame.width
                rotation = .radians(original.rotationRad)
                center = Self.home(original, canvas: session.canvasFrame)
                closing = true
            } completion: { handOff(onCancel) }
            return
        }
        withAnimation(.easeIn(duration: 0.15)) { closing = true; photo = nil } completion: { onCancel() }
    }
}

/// A photo as the canvas draws it, in SwiftUI, so editing it looks exactly like the result. Before
/// it is framed it is the bare photo filling `bare`, as the camera saw it.
struct CanvasPhotoCard: View {
    let photo: UIImage
    let cutout: UIImage?
    let style: CanvasPhotoStyle
    let long: CGFloat
    let framed: Bool
    let bare: CGSize
    let bareCornerRadius: CGFloat

    var body: some View {
        let aspect = Double(photo.size.width / max(1, photo.size.height))
        let layout = CanvasPhotoGeometry.layout(style: style, aspectRatio: aspect, long: long)
        let showsCutout = framed && style == .sticker && cutout != nil
        let cutoutSize = cutout.map { fit($0.size, long: long) } ?? layout.size
        let outer = !framed ? bare : showsCutout ? cutoutSize : layout.size
        let picture = !framed ? CGRect(origin: .zero, size: bare)
            : showsCutout ? CGRect(x: (outer.width - layout.photo.width * 0.9) / 2, y: (outer.height - layout.photo.height * 0.9) / 2,
                                   width: layout.photo.width * 0.9, height: layout.photo.height * 0.9)
            : layout.photo
        let shadow = CanvasPhotoGeometry.shadow(long: long)
        ZStack(alignment: .topLeading) {
            RoundedRectangle(cornerRadius: framed ? layout.cornerRadius : bareCornerRadius)
                .fill(Color(red: 252 / 255, green: 250 / 255, blue: 246 / 255))
                .opacity(framed && !showsCutout ? 1 : 0)
            Image(uiImage: photo)
                .resizable()
                .scaledToFill()
                .frame(width: picture.width, height: picture.height)
                .clipShape(.rect(cornerRadius: framed ? layout.photoCornerRadius : bareCornerRadius))
                .offset(x: picture.minX, y: picture.minY)
                .opacity(showsCutout ? 0 : 1)
            if let cutout {
                Image(uiImage: cutout)
                    .resizable()
                    .scaledToFit()
                    .frame(width: outer.width, height: outer.height)
                    .opacity(showsCutout ? 1 : 0)
            }
        }
        .frame(width: outer.width, height: outer.height, alignment: .topLeading)
        .compositingGroup()
        .shadow(color: .black.opacity(framed ? shadow.alpha : 0), radius: shadow.blur / 2, y: shadow.offset.height)
    }

    private func fit(_ size: CGSize, long: CGFloat) -> CGSize {
        guard size.width > 0, size.height > 0 else { return CGSize(width: long, height: long) }
        let aspect = size.width / size.height
        return aspect >= 1 ? CGSize(width: long, height: long / aspect) : CGSize(width: long * aspect, height: long)
    }
}
