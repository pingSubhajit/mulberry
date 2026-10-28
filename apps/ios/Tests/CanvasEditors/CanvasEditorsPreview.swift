import SwiftUI

// Isolated visual harness for the canvas text and sticker tools. It never signs
// in or syncs; sticker artwork comes from the local backend's seeded catalog.
// Launch arguments: "sticker" (else text), a panel (font, size, rotation, colour),
// "pill", "edit", or "canvas" to show only the canvas.
private let previewUser = "00000000-0000-4000-8000-00000000c0de"
private struct PreviewTokens: SessionTokenStore {
    func load() throws -> SessionTokens? { .init(accessToken: "preview", refreshToken: "preview", userId: previewUser) }
    func save(_ tokens: SessionTokens) throws {}
    func clear() throws {}
}
@MainActor enum AppRuntime {
    static let directory = FileManager.default.temporaryDirectory.appendingPathComponent("CanvasEditorsPreview")
    static let drawings = DrawingStore(directory: directory.appendingPathComponent("Drawings"))
    static let backend = try! BackendClient(baseURL: URL(string: "http://127.0.0.1:8080/")!, tokenStore: PreviewTokens(),
                                            transport: URLSessionTransport(session: .shared), shortTransport: URLSessionTransport(session: .shared))
    static func api() throws -> BackendClient { backend }
}
@MainActor enum GoogleAuthentication {
    static func signIn() async throws -> String { throw BackendError.signedOut }
    static func signOut() {}
    static func isCancellation(_ error: Error) -> Bool { false }
}
@MainActor final class PushRegistration {
    static let shared = PushRegistration()
    static let deviceId = "canvas-preview"
    func signOut() async {}
}
@MainActor final class HomeQuickActions {
    static let shared = HomeQuickActions()
    var pending: Int?
}

@main struct CanvasEditorsPreview: App {
    var body: some Scene { WindowGroup { CanvasEditorsPreviewRoot() } }
}

private struct PreviewCanvas: UIViewRepresentable {
    let strokes: [DrawingStroke]
    let elements: [CanvasElement]
    let hidden: String?
    func makeUIView(context: Context) -> DoodleTouchView { DoodleTouchView() }
    func updateUIView(_ view: DoodleTouchView, context: Context) {
        view.strokes = strokes; view.dry = true
        view.elementLayer.user = previewUser
        view.elementLayer.update(elements: elements, hidden: hidden)
        view.backgroundColor = UIColor(MulberryDesign.canvas)
        view.setNeedsDisplay()
    }
}

private struct CanvasEditorsPreviewRoot: View {
    private let arguments = ProcessInfo.processInfo.arguments
    @StateObject private var catalog = StickerCatalog(user: previewUser)
    @State private var textSession: CanvasTextSession?
    @State private var stickerSession: CanvasStickerSession?
    @State private var elements: [CanvasElement] = [
        .text(.init(id: "hello", text: "Good morning, love", center: .init(x: 0.5, y: 0.2), rotationRad: -0.12, scale: 1.1,
                    colorArgb: 0xFFB31329, font: .permanentMarker)),
        .sticker(.init(id: "k1", center: .init(x: 0.3, y: 0.62), rotationRad: -0.2, scale: 0.42,
                       sticker: .init(packKey: "spring-kittens", packVersion: 1, stickerId: "3"))),
        .sticker(.init(id: "k2", center: .init(x: 0.72, y: 0.74), rotationRad: 0.25, scale: 0.34,
                       sticker: .init(packKey: "spring-kittens", packVersion: 1, stickerId: "8"))),
        .text(.init(id: "pill", text: "see you at 7", center: .init(x: 0.5, y: 0.9), rotationRad: 0.05, scale: 0.8,
                    colorArgb: 0xFFFFE66D, backgroundPillEnabled: true, font: .kalam))
    ]
    private var strokes: [DrawingStroke] {
        [DrawingStroke(id: "heart", colorArgb: 0xFFE85072, width: 12 / 1080,
                       points: (0...60).map { i in
                           let t = Double(i) / 60 * 2 * .pi
                           return .init(x: 0.5 + 0.012 * 16 * pow(sin(t), 3),
                                        y: 0.4 - 0.008 * (13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t)))
                       }, finished: true)]
    }

    var body: some View {
        NavigationStack {
            PreviewCanvas(strokes: strokes, elements: elements, hidden: textSession?.id ?? stickerSession?.id)
                .padding(8)
                .background(MulberryDesign.canvas)
                .clipShape(RoundedRectangle(cornerRadius: 24))
                .overlay(RoundedRectangle(cornerRadius: 24).strokeBorder(MulberryDesign.accent.opacity(0.3), lineWidth: 2))
                .padding(.horizontal, 16).padding(.vertical, 12)
                .background(MulberryDesign.background)
                .navigationTitle("Canvas preview").navigationBarTitleDisplayMode(.inline)
        }
        .fullScreenCover(item: $textSession) { session in
            CanvasTextEditorView(session: session, palette: DoodleEditor.palette, panel: textPanel) { element in
                replace(.text(element)); textSession = nil
            } onDelete: { textSession = nil }
        }
        .background {
            Color.clear.fullScreenCover(item: $stickerSession) { session in
                CanvasStickerEditorView(session: session, catalog: catalog, panel: stickerPanel) { element in
                    replace(.sticker(element)); stickerSession = nil
                } onDelete: { stickerSession = nil }
            }
        }
        .task {
            guard !arguments.contains("canvas") else { return }
            try? await Task.sleep(for: .milliseconds(600))
            var transaction = Transaction(); transaction.disablesAnimations = true
            let edit = arguments.contains("edit")
            if arguments.contains("sticker") {
                catalog.load()
                let element = edit ? elements[1].sticker! : CanvasStickerElement(center: .init(x: 0.5, y: 0.5),
                    sticker: catalog.defaultSticker ?? .init(packKey: "", packVersion: 0, stickerId: ""))
                withTransaction(transaction) { stickerSession = .init(element: element, isNew: !edit) }
            } else {
                var element = edit ? elements[3].text! : CanvasTextElement(center: .init(x: 0.5, y: 0.5), colorArgb: 0xFFB31329)
                if arguments.contains("pill") { element.backgroundPillEnabled = true }
                withTransaction(transaction) { textSession = .init(element: element, isNew: !edit) }
            }
        }
    }

    private func replace(_ element: CanvasElement) {
        if let index = elements.firstIndex(where: { $0.id == element.id }) { elements[index] = element } else { elements.append(element) }
    }

    private var textPanel: CanvasTextEditorView.Panel? {
        if arguments.contains("font") { return .font }
        if arguments.contains("size") { return .size }
        if arguments.contains("rotation") { return .rotation }
        if arguments.contains("colour") { return .colour }
        return nil
    }

    private var stickerPanel: CanvasStickerEditorView.Panel {
        if arguments.contains("size") { return .size }
        if arguments.contains("rotation") { return .rotation }
        return .picker
    }
}
