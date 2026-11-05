import AppKit
import CanvasDrawing
import Foundation
import ProductCore
import StickerAssets
import SwiftUI

public enum StickersModule {
    public static let name = "Stickers"
}

/// Loads the artwork for the stickers on the canvas through the store the iPhone and iPad use,
/// so the overlay and canvas window draw real stickers, and the canvas's photos. Artwork already in memory shows at once;
/// the rest fades in as it decodes, with a soft placeholder only while a download runs long, or
/// while offline without it.
@MainActor
public final class StickerImageLoader: ObservableObject {
    @Published public private(set) var images: [StickerReference: CanvasImage] = [:]
    /// The canvas's photos and their cut-outs, decoded, by photo id.
    @Published public private(set) var photoImages: [String: CanvasImage] = [:]
    private var loadingPhotos: Set<String> = []
    private var unavailablePhotos: Set<String> = []
    /// Advances when an account's photos are removed, so a download or upload that started before
    /// never writes a photo back afterwards.
    private var photoGeneration = 0
    private let api: StickerBackend
    /// When each sticker's artwork was asked for and arrived, for the live canvases to draw.
    @Published public private(set) var timeline = StickerArtworkTimeline()
    /// Redraws the canvases while artwork fades in or a placeholder pulses.
    private var ticker: Timer?

    public let store: StickerAssetStore
    private var user: String?
    private var loading: Set<StickerReference> = []
    private var unavailable: Set<StickerReference> = []
    private var catalogs: [String: StickerCatalog] = [:]

    public init(api: @escaping StickerBackend) {
        self.api = api
        store = StickerAssetStore(api: api)
    }

    /// The pack list and pack details, cached per account as on iOS.
    public func catalog(user: String) -> StickerCatalog {
        if let existing = catalogs[user] { return existing }
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Mulberry/StickerCatalog", isDirectory: true)
        let catalog = StickerCatalog(user: user, store: store, directory: directory)
        catalogs[user] = catalog
        return catalog
    }

    /// Fetches artwork for any sticker in `state` not loaded yet. Changing account forgets
    /// the previous account's artwork.
    public func load(for state: DrawingState, user: String?) {
        if user != self.user {
            self.user = user
            images = [:]
            loading = []
            unavailable = []
            photoImages = [:]
            loadingPhotos = []
            unavailablePhotos = []
            timeline.reset()
        }
        guard let user else { return }
        loadPhotos(for: state, user: user)
        let needed = Set(state.canvasElements.compactMap(\.sticker?.sticker))
            .subtracting(images.keys).subtracting(loading).subtracting(unavailable)
        for sticker in needed {
            // Downsampled like the iOS editor: enough for a desktop canvas, cheap to keep.
            if let image = store.cachedImage(sticker, variant: .full, user: user, maxPixelSize: 1024) {
                images[sticker] = image
                continue
            }
            loading.insert(sticker)
            timeline.request(sticker)
            Task {
                let image = await store.image(sticker, variant: .full, user: user, maxPixelSize: 1024)
                guard self.user == user else { return }
                loading.remove(sticker)
                if let image {
                    images[sticker] = image
                    timeline.arrive(sticker)
                } else {
                    unavailable.insert(sticker)
                    timeline.fail(sticker)
                }
                animate()
            }
        }
        if !loading.isEmpty { animate() }
    }

    private func animate() {
        timeline.reduceMotion = NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
        guard ticker == nil else { return }
        ticker = Timer.scheduledTimer(withTimeInterval: 1.0 / 60, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.step() }
        }
    }

    private func step() {
        if timeline.isAnimating() {
            objectWillChange.send()
            return
        }
        timeline.settle()
        ticker?.invalidate()
        ticker = nil
    }

    /// Lets stickers and photos that failed while offline try again, such as after reconnecting.
    public func retryUnavailable() {
        unavailable = []
        unavailablePhotos = []
    }

    // MARK: Photos

    private func loadPhotos(for state: DrawingState, user: String) {
        let needed = Set(state.canvasElements.compactMap(\.photo).flatMap { [$0.photoId, $0.cutoutPhotoId].compactMap { $0 } })
            .subtracting(photoImages.keys).subtracting(loadingPhotos).subtracting(unavailablePhotos)
        for photoId in needed {
            loadingPhotos.insert(photoId)
            Task {
                let image = await photo(photoId, user: user)
                guard self.user == user else { return }
                loadingPhotos.remove(photoId)
                if let image { photoImages[photoId] = image } else { unavailablePhotos.insert(photoId) }
            }
        }
    }

    /// The photo from disk, or downloaded and kept first. Nil while offline, or if the account's
    /// photos were removed meanwhile.
    private func photo(_ photoId: String, user: String) async -> NSImage? {
        guard let file = CanvasPhotoFiles.file(photoId, user: user) else { return nil }
        let started = photoGeneration
        if !FileManager.default.fileExists(atPath: file.path) {
            guard let data = try? await api().canvasPhoto(photoId), !data.isEmpty, photoGeneration == started else { return nil }
            CanvasPhotoFiles.save(data, to: file)
        }
        let image = await Task.detached(priority: .userInitiated) { CanvasPhotoFiles.decode(file) }.value
        return photoGeneration == started ? image : nil
    }

    /// Removes the account's canvas photos from this Mac, on disk and in memory, once it signs
    /// out, is deleted, or its session is refused. They are private to the couple.
    public func forgetPhotos(user: String) {
        photoGeneration += 1
        if self.user == user {
            photoImages = [:]
            loadingPhotos = []
            unavailablePhotos = []
        }
        CanvasPhotoFiles.removeAll(user: user)
    }

    /// Uploads a photo taken or picked on this Mac and keeps it, so the canvas shows it at once.
    /// `image` is what `data` decodes to.
    public func uploadPhoto(_ data: Data, contentType: String, image: NSImage, user: String) async throws -> CanvasPhotoUpload {
        let started = photoGeneration
        let upload = try await api().uploadCanvasPhoto(data, contentType: contentType)
        guard photoGeneration == started else { return upload }
        if let file = CanvasPhotoFiles.file(upload.photoId, user: user) { CanvasPhotoFiles.save(data, to: file) }
        if self.user == user { photoImages[upload.photoId] = image }
        return upload
    }
}

/// A sticker or pack cover from the store, with a quiet placeholder while it loads. Artwork
/// already in memory shows in the first frame, so revisiting a pack never flashes.
public struct StickerThumbnail: View {
    let sticker: StickerReference
    let user: String
    let store: StickerAssetStore
    let variant: StickerAssetVariant
    let hint: String?
    let maxPixelSize: Int
    @State private var image: NSImage?

    public init(sticker: StickerReference, user: String, store: StickerAssetStore, variant: StickerAssetVariant = .thumbnail, hint: String? = nil,
                maxPixelSize: Int = StickerAssetStore.thumbnailPixels) {
        self.sticker = sticker
        self.user = user
        self.store = store
        self.variant = variant
        self.hint = hint
        self.maxPixelSize = maxPixelSize
        _image = State(initialValue: store.cachedImage(sticker, variant: variant, user: user, maxPixelSize: maxPixelSize))
    }

    public var body: some View {
        Group {
            if let image {
                Image(nsImage: image).resizable().scaledToFit().transition(.opacity)
            } else {
                RoundedRectangle(cornerRadius: 10).fill(.white.opacity(0.08))
            }
        }
        .task(id: sticker) {
            if let cached = store.cachedImage(sticker, variant: variant, user: user, maxPixelSize: maxPixelSize) {
                image = cached
                return
            }
            let loaded = await store.image(sticker, variant: variant, user: user, hint: hint, maxPixelSize: maxPixelSize)
            withAnimation(.easeOut(duration: 0.15)) { image = loaded }
        }
    }
}
