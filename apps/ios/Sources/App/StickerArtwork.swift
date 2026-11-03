import SwiftUI
import UIKit

/// The iPhone and iPad's sticker store and catalog, on the shared implementation.
extension StickerAssetStore {
    static let shared = StickerAssetStore(api: { try await MainActor.run { try AppRuntime.api() } })
    /// The canvas's artwork size: downsampled, enough for any screen.
    static let canvasPixels = 1024
}

extension StickerCatalog {
    convenience init(user: String?) {
        self.init(user: user, store: .shared,
                  directory: AppRuntime.directory.appendingPathComponent("StickerCatalog", isDirectory: true))
    }
}

/// A sticker or pack cover loaded from the asset store, with a quiet placeholder. Artwork
/// already in memory shows in the first frame, so revisiting a pack never flashes.
struct StickerArtwork: View {
    let sticker: StickerReference
    let user: String?
    let variant: StickerAssetVariant
    let hint: String?
    let maxPixelSize: Int
    @State private var image: UIImage?

    init(sticker: StickerReference, user: String?, variant: StickerAssetVariant = .thumbnail, hint: String? = nil,
         maxPixelSize: Int = StickerAssetStore.thumbnailPixels) {
        self.sticker = sticker; self.user = user; self.variant = variant; self.hint = hint; self.maxPixelSize = maxPixelSize
        _image = State(initialValue: Self.cached(sticker, user: user, variant: variant, maxPixelSize: maxPixelSize))
    }

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFit().transition(.opacity)
            } else {
                RoundedRectangle(cornerRadius: 12).fill(.white.opacity(0.08))
            }
        }
        .task(id: sticker) {
            if let cached = Self.cached(sticker, user: user, variant: variant, maxPixelSize: maxPixelSize) {
                image = cached
                return
            }
            guard let user else { return }
            let loaded = await StickerAssetStore.shared.image(sticker, variant: variant, user: user, hint: hint, maxPixelSize: maxPixelSize)
            withAnimation(.easeOut(duration: 0.15)) { image = loaded }
        }
    }

    private static func cached(_ sticker: StickerReference, user: String?, variant: StickerAssetVariant, maxPixelSize: Int) -> UIImage? {
        guard let user else { return nil }
        return StickerAssetStore.shared.cachedImage(sticker, variant: variant, user: user, maxPixelSize: maxPixelSize)
    }
}
