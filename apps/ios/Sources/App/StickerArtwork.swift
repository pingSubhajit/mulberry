import SwiftUI
import UIKit

/// The iPhone and iPad's sticker store and catalog, on the shared implementation.
extension StickerAssetStore {
    static let shared = StickerAssetStore(api: { try await MainActor.run { try AppRuntime.api() } })
}

extension StickerCatalog {
    convenience init(user: String?) {
        self.init(user: user, api: { try await MainActor.run { try AppRuntime.api() } }, store: .shared,
                  directory: AppRuntime.directory.appendingPathComponent("StickerCatalog", isDirectory: true))
    }
}

/// A sticker or pack cover loaded from the asset store, with a quiet placeholder.
struct StickerArtwork: View {
    let sticker: StickerReference
    let user: String?
    var variant: StickerAssetVariant = .thumbnail
    var hint: String?
    var maxPixelSize = 384
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFit().transition(.opacity)
            } else {
                RoundedRectangle(cornerRadius: 12).fill(.white.opacity(0.08))
            }
        }
        .task(id: sticker) {
            guard let user else { return }
            let loaded = await StickerAssetStore.shared.image(sticker, variant: variant, user: user, hint: hint, maxPixelSize: maxPixelSize)
            withAnimation(.easeOut(duration: 0.15)) { image = loaded }
        }
    }
}
