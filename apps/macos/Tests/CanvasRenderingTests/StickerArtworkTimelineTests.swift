import CanvasDrawing
import ProductCore
import Testing

private let sticker = StickerReference(packKey: "cute", packVersion: 1, stickerId: "bear")

@Test func artworkDecodedQuicklyFadesInWithoutAPlaceholder() {
    var timeline = StickerArtworkTimeline()
    timeline.request(sticker, at: 10)
    #expect(timeline.appearance(sticker, hasArtwork: false, at: 10.1) == StickerAppearance(artwork: 0, placeholder: 0))
    timeline.arrive(sticker, at: 10.1)
    let fading = timeline.appearance(sticker, hasArtwork: true, at: 10.15)
    #expect(fading.artwork > 0 && fading.artwork < 1)
    #expect(fading.placeholder == 0)
    #expect(timeline.appearance(sticker, hasArtwork: true, at: 10.4) == StickerAppearance(artwork: 1, placeholder: 0))
    #expect(!timeline.isAnimating(at: 10.4))
}

@Test func slowDownloadShowsThePlaceholderAfterTheGracePeriodThenCrossfades() {
    var timeline = StickerArtworkTimeline()
    timeline.request(sticker, at: 0)
    #expect(timeline.appearance(sticker, hasArtwork: false, at: 0.3).placeholder == 0)
    #expect(timeline.appearance(sticker, hasArtwork: false, at: 1).placeholder > 0)
    #expect(timeline.isAnimating(at: 1))
    timeline.arrive(sticker, at: 2)
    let crossfade = timeline.appearance(sticker, hasArtwork: true, at: 2.09)
    #expect(crossfade.artwork > 0 && crossfade.placeholder > 0)
    #expect(timeline.appearance(sticker, hasArtwork: true, at: 2.2).placeholder == 0)
}

@Test func artworkAlreadyInMemoryShowsAtOnceAndMissingArtworkKeepsAStillPlaceholder() {
    var timeline = StickerArtworkTimeline()
    #expect(timeline.appearance(sticker, hasArtwork: true, at: 5) == StickerAppearance(artwork: 1, placeholder: 0))
    timeline.request(sticker, at: 5)
    timeline.fail(sticker)
    #expect(timeline.appearance(sticker, hasArtwork: false, at: 6) == StickerAppearance(artwork: 0, placeholder: 1))
    #expect(!timeline.isAnimating(at: 6))
}
