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
/// so the overlay and canvas window draw real stickers. Artwork already in memory shows at once;
/// the rest fades in as it decodes, with a soft placeholder only while a download runs long, or
/// while offline without it.
@MainActor
public final class StickerImageLoader: ObservableObject {
    @Published public private(set) var images: [StickerReference: CanvasImage] = [:]
    /// When each sticker's artwork was asked for and arrived, for the live canvases to draw.
    @Published public private(set) var timeline = StickerArtworkTimeline()
    /// Redraws the canvases while artwork fades in or a placeholder pulses.
    private var ticker: Timer?

    public let store: StickerAssetStore
    private let api: StickerBackend
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
        let catalog = StickerCatalog(user: user, api: api, store: store, directory: directory)
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
            timeline.reset()
        }
        guard let user else { return }
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

    /// Lets stickers that failed while offline try again, such as after reconnecting.
    public func retryUnavailable() {
        unavailable = []
    }
}

/// A sticker or pack cover from the store, with a quiet placeholder while it loads.
public struct StickerThumbnail: View {
    let sticker: StickerReference
    let user: String
    let store: StickerAssetStore
    var variant: StickerAssetVariant = .thumbnail
    var hint: String?
    var maxPixelSize = 256
    @State private var image: NSImage?

    public init(sticker: StickerReference, user: String, store: StickerAssetStore, variant: StickerAssetVariant = .thumbnail, hint: String? = nil, maxPixelSize: Int = 256) {
        self.sticker = sticker
        self.user = user
        self.store = store
        self.variant = variant
        self.hint = hint
        self.maxPixelSize = maxPixelSize
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
            let loaded = await store.image(sticker, variant: variant, user: user, hint: hint, maxPixelSize: maxPixelSize)
            withAnimation(.easeOut(duration: 0.15)) { image = loaded }
        }
    }
}
