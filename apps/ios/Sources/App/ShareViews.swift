import LinkPresentation
import SwiftUI
import UIKit

extension ShareFrameStore {
    /// The app's frames: the catalog, with Plain and Polaroid bundled for when it can't be reached.
    static let app = ShareFrameStore(
        catalog: {
            let api = try await MainActor.run { try AppRuntime.api() }
            return try await api.shareFrames()
        },
        baseURL: (Bundle.main.object(forInfoDictionaryKey: "MulberryAPIBaseURL") as? String).flatMap(URL.init(string:)),
        bundledDirectory: Bundle.main.url(forResource: "ShareFrames", withExtension: nil))
}

/// The share screen's state: the frames, the caption, which wallpaper, and the image they make.
/// Drawing happens off the main thread; each change replaces the drawing still under way.
@MainActor
final class ShareModel: ObservableObject {
    @Published private(set) var frames: [ShareFrame] = []
    @Published private(set) var selected: ShareFrame?
    @Published var caption: String {
        didSet {
            if caption.count > ShareCaption.maxLength { caption = ShareCaption.limited(caption) }
            if caption != oldValue { render(after: .milliseconds(120)) }
        }
    }
    @Published var useRealWallpaper = true {
        didSet { if useRealWallpaper != oldValue { render(); renderThumbnails() } }
    }
    /// False when this device's lock screen image can't be drawn, as when its saved photo is gone.
    @Published private(set) var realWallpaperAvailable = true
    @Published private(set) var preview: UIImage?
    @Published private(set) var thumbnails: [String: UIImage] = [:]
    @Published private(set) var frameFailed = false
    @Published private(set) var preparing = false

    let defaultCaption: String
    private let store: ShareFrameStore
    private let fonts: ShareFrameFonts?
    /// The person chose a frame, so the catalog arriving doesn't move the selection.
    private var chosen = false
    private var started = false
    private var realWallpaper: Task<ShareWallpaper?, Never>?
    private var fallbackWallpaper: Task<ShareWallpaper?, Never>?
    private var contents: [ContentKey: CGImage] = [:]
    private var frameImages: [String: CGImage] = [:]
    private var frameThumbnails: [String: CGImage] = [:]
    private var renderTask: Task<Void, Never>?
    private var thumbnailTask: Task<Void, Never>?

    private struct ContentKey: Hashable {
        let real: Bool
        let clock: ShareClock
        let small: Bool
    }

    private static let thumbnailScale: CGFloat = 0.25

    init(partnerName: String?, store: ShareFrameStore = .app) {
        defaultCaption = ShareCaption.defaultText(partnerName: partnerName)
        caption = defaultCaption
        self.store = store
        fonts = try? ShareFrameFonts(bundle: .main)
    }

    var captionEdited: Bool { caption != defaultCaption }
    private var usesRealWallpaper: Bool { useRealWallpaper && realWallpaperAvailable }

    func start() {
        guard !started else { return }
        started = true
        AppAnalytics.track(.shareOpened)
        apply(store.bundledLineup(), draw: false)
        Task {
            // The lock screen image is drawn on the main thread, as for the Shortcut; let the
            // sheet finish rising first.
            try? await Task.sleep(for: .milliseconds(350))
            render()
            renderThumbnails()
            apply(await store.lineup())
        }
    }

    func select(_ frame: ShareFrame) {
        chosen = true
        // Tapping a frame that couldn't load tries it again.
        guard frame != selected || frameFailed else { return }
        if frame != selected { AppAnalytics.track(.shareFrameSelected(frameKey: frame.key)) }
        selected = frame
        frameFailed = false
        render()
    }

    /// Shows the catalog's frames, keeping the person's choice when it is still there.
    private func apply(_ lineup: ShareFrameLineup, draw: Bool = true) {
        guard !lineup.frames.isEmpty, lineup.frames != frames else { return }
        frames = lineup.frames
        let previous = selected
        if chosen, let key = previous?.key, let same = lineup.frames.first(where: { $0.key == key }) {
            selected = same
        } else {
            selected = lineup.preselected
        }
        guard draw else { return }
        if selected != previous { render() }
        renderThumbnails()
    }

    // MARK: Drawing

    private func render(after delay: Duration? = nil) {
        renderTask?.cancel()
        guard let frame = selected, let fonts else { return }
        let caption = caption, real = usesRealWallpaper
        renderTask = Task {
            if let delay { try? await Task.sleep(for: delay) }
            guard !Task.isCancelled else { return }
            if let image = await compose(frame: frame, caption: caption, real: real, fonts: fonts), !Task.isCancelled {
                preview = UIImage(cgImage: image)
            }
        }
    }

    /// The full share image for the current choices, with the clock showing the time now.
    private func compose(frame: ShareFrame, caption: String, real: Bool, fonts: ShareFrameFonts) async -> CGImage? {
        guard let content = await content(real: real, small: false, fonts: fonts) else { return nil }
        let frameImage: CGImage
        if let cached = frameImages[cacheKey(frame)] {
            frameImage = cached
        } else {
            do {
                frameImage = try await store.image(frame)
                frameImages[cacheKey(frame)] = frameImage
                frameFailed = false
            } catch {
                if selected == frame { frameFailed = true; preview = nil }
                return nil
            }
        }
        let layout = frame.layout
        return await Task.detached(priority: .userInitiated) {
            ShareFrameRenderer.compose(content: content, frame: frameImage, layout: layout, caption: caption, fonts: fonts)
        }.value
    }

    /// The lock screen, drawn once per wallpaper choice and minute.
    private func content(real: Bool, small: Bool, fonts: ShareFrameFonts) async -> CGImage? {
        let key = ContentKey(real: real, clock: ShareClock.at(.now), small: small)
        if let cached = contents[key] { return cached }
        guard let wallpaper = await wallpaper(real: real) else {
            // The real lock screen can't be drawn: the fallback stands in, and the switch turns off.
            if real {
                realWallpaperAvailable = false
                useRealWallpaper = false
            }
            return nil
        }
        let scale = small ? Self.thumbnailScale : 1
        let image = await Task.detached(priority: .userInitiated) {
            ShareFrameRenderer.content(wallpaper, clock: key.clock, fonts: fonts, scale: scale)
        }.value
        // Only the newest minute is worth keeping.
        contents = contents.filter { $0.key.clock == key.clock }
        contents[key] = image
        return image
    }

    private func wallpaper(real: Bool) async -> ShareWallpaper? {
        if real {
            let task = realWallpaper ?? Task {
                guard let image = try? await ProductWallpaperService.shared.currentWallpaperImage(), let cgImage = image.cgImage else { return nil }
                return ShareWallpaper(image: cgImage)
            }
            realWallpaper = task
            return await task.value
        }
        let task = fallbackWallpaper ?? Task {
            guard let background = UIImage(named: "AuthBackground").flatMap(Self.cgImage) else { return nil }
            let doodles = try? await ProductWallpaperService.shared.currentDoodleLayer()
            return ShareWallpaper(image: background, dimmed: true, overlay: doodles?.cgImage)
        }
        fallbackWallpaper = task
        return await task.value
    }

    /// Every frame drawn small with this lock screen, without the caption.
    private func renderThumbnails() {
        thumbnailTask?.cancel()
        guard let fonts, !frames.isEmpty else { return }
        let frames = frames, real = usesRealWallpaper, scale = Self.thumbnailScale
        thumbnailTask = Task {
            guard let content = await content(real: real, small: true, fonts: fonts) else { return }
            for frame in frames {
                guard !Task.isCancelled else { return }
                let key = cacheKey(frame)
                let image: CGImage
                if let cached = frameThumbnails[key] {
                    image = cached
                } else {
                    guard let loaded = try? await store.thumbnail(frame, maxPixelSize: 480) else { continue }
                    frameThumbnails[key] = loaded
                    image = loaded
                }
                let layout = frame.layout
                let thumbnail = await Task.detached(priority: .utility) {
                    ShareFrameRenderer.compose(content: content, frame: image, layout: layout, caption: "", fonts: fonts,
                                               scale: scale, text: false)
                }.value
                guard !Task.isCancelled, let thumbnail else { return }
                thumbnails[frame.key] = UIImage(cgImage: thumbnail)
            }
        }
    }

    private func cacheKey(_ frame: ShareFrame) -> String { "\(frame.key)/\(frame.version)" }

    private nonisolated static func cgImage(_ image: UIImage) -> CGImage? {
        if let cgImage = image.cgImage { return cgImage }
        let format = UIGraphicsImageRendererFormat(); format.scale = image.scale; format.opaque = true
        return UIGraphicsImageRenderer(size: image.size, format: format).image { _ in image.draw(at: .zero) }.cgImage
    }

    // MARK: Sharing

    /// Draws the image afresh, so the clock shows the time it is shared, and hands it to the
    /// system share sheet.
    func share() {
        guard !preparing, let frame = selected, let fonts else { return }
        preparing = true
        let caption = caption, real = usesRealWallpaper, edited = captionEdited
        Task {
            defer { preparing = false }
            guard let image = await compose(frame: frame, caption: caption, real: real, fonts: fonts),
                  let data = await Task.detached(priority: .userInitiated, operation: { ShareFrameRenderer.jpegData(image) }).value,
                  let shared = UIImage(data: data) else { return }
            present(shared) { activity in
                AppAnalytics.track(.shareCompleted(frameKey: frame.key, usedRealWallpaper: real, captionEdited: edited,
                                                   activityType: activity?.rawValue))
            }
        }
    }

    /// Uses UIKit's share sheet, as Wallpaper setup does, because ShareLink can't say whether the
    /// image was shared or where it went.
    private func present(_ image: UIImage, completed: @escaping @MainActor (UIActivity.ActivityType?) -> Void) {
        guard let root = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first?.rootViewController else { return }
        var top = root
        while let presented = top.presentedViewController { top = presented }
        let sheet = UIActivityViewController(activityItems: [ShareImageItem(image: image)], applicationActivities: nil)
        sheet.completionWithItemsHandler = { activity, done, _, _ in
            guard done else { return }
            Task { @MainActor in completed(activity) }
        }
        // iPad shows the share sheet as a popover, which needs an anchor: the Share button's corner.
        if let popover = sheet.popoverPresentationController {
            popover.sourceView = top.view
            popover.sourceRect = CGRect(x: top.view.bounds.maxX - 44, y: top.view.safeAreaInsets.top + 22, width: 0, height: 0)
            popover.permittedArrowDirections = .up
        }
        top.present(sheet, animated: true)
    }
}

/// The share screen, as a native sheet in the style of Apple's own apps: the image as it will be
/// shared, the themes to frame it with, then the caption and which wallpaper it shows.
struct ShareSheet: View {
    @StateObject private var model: ShareModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @FocusState private var captionFocused: Bool
    /// The preview's height: it grows into whatever the sheet has left below the settings, so
    /// they sit at the bottom on every screen size.
    @State private var previewHeight: CGFloat = 340

    init(partnerName: String?) {
        _model = StateObject(wrappedValue: ShareModel(partnerName: partnerName))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    preview
                }
                .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 8, trailing: 0))
                .listRowBackground(Color.clear)

                Section {
                    themes
                }
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)

                Section {
                    TextField("Caption", text: $model.caption, axis: .vertical)
                        .lineLimit(1...3)
                        .submitLabel(.done)
                        .focused($captionFocused)
                        .onChange(of: model.caption) { _, value in
                            // A return ends editing, as the Done key promises; captions are one paragraph.
                            if value.contains("\n") { model.caption = value.replacingOccurrences(of: "\n", with: ""); captionFocused = false }
                        }
                    Toggle("My wallpaper", isOn: $model.useRealWallpaper)
                        .disabled(!model.realWallpaperAvailable)
                }
            }
            .listSectionSpacing(.compact)
            .contentMargins(.top, 4, for: .scrollContent)
            .contentMargins(.bottom, 0, for: .scrollContent)
            // The space the content leaves empty at the bottom goes to the preview. While the
            // keyboard is up the sheet scrolls instead, so the preview doesn't jump.
            .onScrollGeometryChange(for: CGFloat.self) { geometry in
                geometry.containerSize.height - geometry.contentInsets.top - geometry.contentInsets.bottom - geometry.contentSize.height
            } action: { _, spare in
                guard !captionFocused, abs(spare - Self.bottomGap) > 1 else { return }
                previewHeight = max(Self.minimumPreviewHeight, previewHeight + spare - Self.bottomGap)
            }
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle("Share").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(role: .close) { dismiss() }.tint(.primary)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button { captionFocused = false; model.share() } label: {
                        if model.preparing {
                            ProgressView()
                        } else {
                            Label("Share", systemImage: "square.and.arrow.up")
                        }
                    }
                    .disabled(model.preview == nil || model.frameFailed || model.preparing)
                }
            }
        }
        .presentationDetents([.large])
        .onAppear { model.start() }
    }

    private static let bottomGap: CGFloat = 0
    private static let minimumPreviewHeight: CGFloat = 240

    // MARK: Preview

    private var preview: some View {
        Color.clear
            .aspectRatio(ShareFrameRenderer.outputSize.width / ShareFrameRenderer.outputSize.height, contentMode: .fit)
            .overlay {
                if let image = model.preview {
                    Image(uiImage: image).resizable().scaledToFit()
                        .transition(.opacity)
                } else if model.frameFailed {
                    Image(systemName: "exclamationmark.triangle").font(.title2).foregroundStyle(.secondary)
                } else {
                    ProgressView()
                }
            }
            .background(.fill.tertiary)
            .clipShape(.rect(cornerRadius: 18))
            .shadow(color: .black.opacity(0.12), radius: 14, y: 6)
            .frame(maxWidth: .infinity)
            .frame(height: previewHeight)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.15), value: model.preview == nil)
            .accessibilityElement()
            .accessibilityLabel("Share image preview")
            .accessibilityAddTraits(.isImage)
    }

    // MARK: Themes

    /// A row of themes, shown only as thumbnails; VoiceOver still reads each name.
    private var themes: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(model.frames) { frame in
                    let selected = model.selected?.key == frame.key
                    Button { model.select(frame) } label: {
                        Color.clear
                            .frame(width: 62, height: 110)
                            .overlay {
                                if let thumbnail = model.thumbnails[frame.key] {
                                    Image(uiImage: thumbnail).resizable().scaledToFill()
                                } else {
                                    Rectangle().fill(.fill.tertiary)
                                }
                            }
                            .clipShape(.rect(cornerRadius: 10))
                            .padding(3)
                            .overlay {
                                RoundedRectangle(cornerRadius: 13)
                                    .strokeBorder(selected ? MulberryDesign.accent : .clear, lineWidth: 2.5)
                            }
                            .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(frame.title)
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
            .padding(.vertical, 4)
        }
        .contentMargins(.horizontal, 20, for: .scrollContent)
        .sensoryFeedback(.selection, trigger: model.selected?.key)
    }
}

/// The image for the system share sheet, with metadata so the sheet's header shows a thumbnail of
/// it rather than a blank placeholder.
private final class ShareImageItem: NSObject, UIActivityItemSource {
    private let image: UIImage

    init(image: UIImage) { self.image = image }

    func activityViewControllerPlaceholderItem(_ activityViewController: UIActivityViewController) -> Any { image }

    func activityViewController(_ activityViewController: UIActivityViewController,
                                itemForActivityType activityType: UIActivity.ActivityType?) -> Any? { image }

    func activityViewControllerLinkMetadata(_ activityViewController: UIActivityViewController) -> LPLinkMetadata? {
        let metadata = LPLinkMetadata()
        metadata.title = "Mulberry"
        let provider = NSItemProvider(object: image)
        metadata.imageProvider = provider
        metadata.iconProvider = provider
        return metadata
    }
}
