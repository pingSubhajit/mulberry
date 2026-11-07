import Foundation
import Testing
@testable import ProductCore
@testable import ShareFrames

func fixture(_ path: String) throws -> URL {
    try #require(Bundle.module.url(forResource: "Fixtures/\(path)", withExtension: nil))
}

func layout(_ key: String) throws -> ShareFrameLayout {
    try #require(ShareFrameLayout.decodeIfSupported(Data(contentsOf: fixture("frames/\(key)/layout.json"))))
}

private func frame(_ key: String, version: Int = 1, sortOrder: Int = 0, from: String? = nil, until: String? = nil) throws -> ShareFrame {
    var base = try layout("plain")
    base.key = key
    return ShareFrame(key: key, version: version, title: key, sortOrder: sortOrder, availableFrom: from, availableUntil: until,
                      layout: base, imageURL: URL(string: "https://example.invalid/\(key)/\(version).webp")!)
}

// MARK: Layout

@Test func decodesTheShippedLayout() throws {
    let plain = try layout("plain")
    #expect(plain.layoutVersion == 1)
    #expect(plain.key == "plain")
    #expect(plain.canvas == .init(width: 1080, height: 1920))
    #expect(plain.content.kind == "phone")
    #expect(abs(plain.content.aspectRatio - 1179.0 / 2556.0) < 1e-12)
    #expect(plain.content.quad.topLeft == .init(x: 300.94236, y: 329.60267))
    #expect(plain.content.quad.bottomLeft == .init(x: 215.95412, y: 1544.99106))
    #expect(plain.caption.anchor == .bottomRight)
    #expect(plain.caption.rotationDegrees == -7)
    #expect(plain.caption.align == .right)
    #expect(plain.caption.maxWidth == 580 && plain.caption.maxLines == 3)
    #expect(plain.caption.maxFontSize == 64 && plain.caption.minFontSize == 40)
    #expect(plain.caption.backing?.color == "#FFF4DF")
    #expect(plain.caption.backing?.cornerRadius == nil)
    #expect(plain.brand.anchor == .center)
    #expect(plain.brand.rotationDegrees == 0)
    #expect(plain.brand.backing?.cornerRadius == 15)
    #expect(try layout("polaroid").content.kind == "polaroid")
}

@Test func skipsLayoutsItCannotDraw() throws {
    let original = try JSONSerialization.jsonObject(with: Data(contentsOf: fixture("frames/plain/layout.json"))) as! [String: Any]
    func data(_ change: (inout [String: Any]) -> Void) throws -> Data {
        var copy = original
        change(&copy)
        return try JSONSerialization.data(withJSONObject: copy)
    }
    #expect(ShareFrameLayout.decodeIfSupported(try data { _ in }) != nil)
    #expect(ShareFrameLayout.decodeIfSupported(try data { $0["layoutVersion"] = 2 }) == nil)
    #expect(ShareFrameLayout.decodeIfSupported(try data { $0["caption"] = nil }) == nil)
    #expect(ShareFrameLayout.decodeIfSupported(try data {
        var caption = $0["caption"] as! [String: Any]; caption["inkColor"] = "red"; $0["caption"] = caption
    }) == nil)
    // A null backing is allowed for either block.
    let unbacked = try #require(ShareFrameLayout.decodeIfSupported(try data {
        var caption = $0["caption"] as! [String: Any]; caption["backing"] = NSNull(); $0["caption"] = caption
        var brand = $0["brand"] as! [String: Any]; brand["backing"] = NSNull(); $0["brand"] = brand
    }))
    #expect(unbacked.caption.backing == nil && unbacked.brand.backing == nil)
}

@Test func decodesTheCatalogSkippingWhatItCannotDraw() throws {
    let layoutJSON = try String(contentsOf: fixture("frames/plain/layout.json"), encoding: .utf8)
    let future = layoutJSON.replacingOccurrences(of: "\"layoutVersion\": 1", with: "\"layoutVersion\": 2")
    let json = """
    {"items": [
      {"key": "plain", "version": 1, "title": "Plain", "sortOrder": 20, "availableFrom": null, "availableUntil": null,
       "frameUrl": "https://cdn.example/plain/full.webp", "thumbnailUrl": "https://cdn.example/plain/thumb.webp",
       "width": 1080, "height": 1920, "layout": \(layoutJSON)},
      {"key": "future", "version": 3, "title": "Future", "sortOrder": 5,
       "frameUrl": "/dev/share-frame-assets/future.webp", "thumbnailUrl": null, "width": 1080, "height": 1920, "layout": \(future)},
      {"key": "broken", "title": "Missing its version"}
    ]}
    """
    let list = try JSONDecoder().decode(ShareFrameCatalogList.self, from: Data(json.utf8))
    #expect(list.items.map(\.key) == ["plain", "future"])
    #expect(list.items[0].layout?.key == "plain")
    #expect(list.items[1].layout == nil)
    let base = URL(string: "http://localhost:8080/")!
    let frames = list.items.compactMap { ShareFrame($0, baseURL: base) }
    #expect(frames.map(\.key) == ["plain"])
    #expect(frames[0].imageURL.absoluteString == "https://cdn.example/plain/full.webp")
    #expect(frames[0].thumbnailURL?.absoluteString == "https://cdn.example/plain/thumb.webp")
}

@Test func resolvesRelativeAssetURLsAgainstTheAPI() throws {
    let layoutJSON = try String(contentsOf: fixture("frames/plain/layout.json"), encoding: .utf8)
    let json = """
    {"key": "plain", "version": 2, "title": "Plain", "sortOrder": 1, "frameUrl": "/dev/share-frame-assets/a.webp",
     "thumbnailUrl": "/dev/share-frame-assets/b.webp", "layout": \(layoutJSON)}
    """
    let item = try JSONDecoder().decode(ShareFrameCatalogItem.self, from: Data(json.utf8))
    let frame = try #require(ShareFrame(item, baseURL: URL(string: "http://localhost:8080/")!))
    #expect(frame.imageURL.absoluteString == "http://localhost:8080/dev/share-frame-assets/a.webp")
    #expect(frame.thumbnailURL?.absoluteString == "http://localhost:8080/dev/share-frame-assets/b.webp")
    #expect(frame.version == 2 && !frame.bundled)
}

// MARK: Selection and order

@Test func seasonalFramesComeFirstAndArePreselectedInTheirWindow() throws {
    let frames = [
        try frame("plain", sortOrder: 20),
        try frame("polaroid", sortOrder: 10),
        try frame("valentines", sortOrder: 30, from: "2027-02-01", until: "2027-02-15"),
        try frame("halloween", sortOrder: 5, from: "2026-10-20", until: "2026-11-01"),
        try frame("lavender-black", sortOrder: 40)
    ]
    let february = ShareFrameRules.lineup(frames, on: "2027-02-10")
    #expect(february.frames.map(\.key) == ["valentines", "polaroid", "plain", "lavender-black"])
    #expect(february.preselected?.key == "valentines")

    // Out of every window: no seasonal frame, and Polaroid is preselected.
    let march = ShareFrameRules.lineup(frames, on: "2027-03-01")
    #expect(march.frames.map(\.key) == ["polaroid", "plain", "lavender-black"])
    #expect(march.preselected?.key == "polaroid")

    // Both window ends are inclusive.
    #expect(ShareFrameRules.lineup(frames, on: "2027-02-01").frames.first?.key == "valentines")
    #expect(ShareFrameRules.lineup(frames, on: "2027-02-15").frames.first?.key == "valentines")
    #expect(ShareFrameRules.lineup(frames, on: "2027-02-16").frames.first?.key == "polaroid")
    #expect(ShareFrameRules.lineup(frames, on: "2027-01-31").frames.first?.key == "polaroid")
}

@Test func severalSeasonalFramesFollowTheirSortOrder() throws {
    let frames = [
        try frame("polaroid", sortOrder: 10),
        try frame("christmas", sortOrder: 50, from: "2026-12-01", until: "2026-12-31"),
        try frame("winter", sortOrder: 3, from: "2026-12-20"),
        try frame("open-ended", sortOrder: 99, until: "2026-12-24")
    ]
    let lineup = ShareFrameRules.lineup(frames, on: "2026-12-22")
    #expect(lineup.frames.map(\.key) == ["winter", "christmas", "open-ended", "polaroid"])
    #expect(lineup.preselected?.key == "winter")
    // A one-sided window counts as seasonal, and closes on its own side.
    #expect(ShareFrameRules.lineup(frames, on: "2026-12-25").frames.map(\.key) == ["winter", "christmas", "polaroid"])
    #expect(ShareFrameRules.lineup(frames, on: "2026-11-30").frames.map(\.key) == ["open-ended", "polaroid"])
}

@Test func preselectsTheFirstFrameWithoutPolaroid() throws {
    let lineup = ShareFrameRules.lineup([try frame("plain", sortOrder: 20), try frame("diwali", sortOrder: 4)], on: "2026-06-01")
    #expect(lineup.frames.map(\.key) == ["diwali", "plain"])
    #expect(lineup.preselected?.key == "diwali")
    #expect(ShareFrameRules.lineup([], on: "2026-06-01").preselected == nil)
}

@Test func usesTheHighestVersionOfEachKey() throws {
    let frames = [
        try frame("polaroid", version: 1, sortOrder: 10),
        try frame("polaroid", version: 3, sortOrder: 10),
        try frame("polaroid", version: 2, sortOrder: 10),
        try frame("plain", version: 1, sortOrder: 20)
    ]
    let lineup = ShareFrameRules.lineup(frames, on: "2026-06-01")
    #expect(lineup.frames.map(\.key) == ["polaroid", "plain"])
    #expect(lineup.frames.first?.version == 3)
    #expect(lineup.preselected?.version == 3)
}

@Test func aMalformedWindowHidesTheFrame() throws {
    let lineup = ShareFrameRules.lineup([try frame("polaroid"), try frame("odd", from: "1 Feb")], on: "2026-06-01")
    #expect(lineup.frames.map(\.key) == ["polaroid"])
}

@Test func keysAreLowercaseAndHyphenated() {
    for key in ["plain", "spring-kittens", "valentines-2027"] { #expect(ShareFrameRules.isValidKey(key)) }
    for key in ["", "Plain", "-plain", "plain-", "a--b", "../etc", "a b", "a/b"] { #expect(!ShareFrameRules.isValidKey(key)) }
}

@Test func theCatalogReplacesTheBundledFramesAndFallsBackToThem() async throws {
    let bundledDirectory = try fixture("frames")
    // Only plain has its image in the fixtures, so only plain is bundled here.
    #expect(ShareFrameStore.bundledFrames(in: bundledDirectory).map(\.key) == ["plain"])

    let offline = ShareFrameStore(catalog: { throw URLError(.notConnectedToInternet) }, bundledDirectory: bundledDirectory,
                                  root: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))
    let fallback = await offline.lineup(on: "2026-06-01")
    #expect(fallback.frames.map(\.key) == ["plain"])
    #expect(fallback.frames.first?.bundled == true)
    #expect(fallback.preselected?.key == "plain")

    let layoutJSON = try String(contentsOf: fixture("frames/plain/layout.json"), encoding: .utf8)
    let polaroidJSON = try String(contentsOf: fixture("frames/polaroid/layout.json"), encoding: .utf8)
    let items = try JSONDecoder().decode(ShareFrameCatalogList.self, from: Data("""
    {"items": [
      {"key": "plain", "version": 1, "title": "Plain", "sortOrder": 20, "frameUrl": "https://cdn.example/p1.webp", "layout": \(layoutJSON)},
      {"key": "polaroid", "version": 2, "title": "Polaroid", "sortOrder": 10, "frameUrl": "https://cdn.example/q2.webp", "layout": \(polaroidJSON)}
    ]}
    """.utf8)).items
    let online = ShareFrameStore(catalog: { items }, bundledDirectory: bundledDirectory,
                                 root: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))
    let lineup = await online.lineup(on: "2026-06-01")
    #expect(lineup.frames.map(\.key) == ["polaroid", "plain"])
    #expect(lineup.preselected?.key == "polaroid")
    // The same published version as the bundled copy is drawn from the app; anything else downloads.
    #expect(lineup.frames[1].bundled && lineup.frames[1].imageURL.isFileURL)
    #expect(!lineup.frames[0].bundled && lineup.frames[0].imageURL.absoluteString == "https://cdn.example/q2.webp")
}

@Test func theCatalogsLastReplyIsKeptForTheNextOpening() async throws {
    let bundledDirectory = try fixture("frames")
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    let polaroidJSON = try String(contentsOf: fixture("frames/polaroid/layout.json"), encoding: .utf8)
    let items = try JSONDecoder().decode(ShareFrameCatalogList.self, from: Data("""
    {"items": [
      {"key": "polaroid", "version": 2, "title": "Polaroid", "sortOrder": 10, "availableFrom": "2026-06-01", "frameUrl": "https://cdn.example/q2.webp", "layout": \(polaroidJSON)}
    ]}
    """.utf8)).items

    // Nothing kept before the catalog has ever answered.
    let online = ShareFrameStore(catalog: { items }, bundledDirectory: bundledDirectory, root: root)
    #expect(online.cachedLineup(on: "2026-06-01") == nil)
    let fresh = try #require(await online.catalogLineup(on: "2026-06-01"))

    // A later opening, offline, starts on the same frames, windows and all.
    let offline = ShareFrameStore(catalog: { throw URLError(.notConnectedToInternet) }, bundledDirectory: bundledDirectory, root: root)
    #expect(offline.cachedLineup(on: "2026-06-01") == fresh)
    #expect(offline.cachedLineup(on: "2026-05-31")?.frames.isEmpty == true)
    // An unreachable catalog says so rather than standing the bundled frames in.
    #expect(await offline.catalogLineup(on: "2026-06-01") == nil)
    #expect(offline.cachedLineup(on: "2026-06-01") == fresh)
}

// MARK: Caption text

@Test func theDefaultCaptionNamesThePartner() {
    #expect(ShareCaption.defaultText(partnerName: "Aarav") == "Look what Aarav drew on my lock screen")
    #expect(ShareCaption.defaultText(partnerName: "  ") == "Look what my love drew on my lock screen")
    #expect(ShareCaption.defaultText(partnerName: nil) == "Look what my love drew on my lock screen")
    // A name too long for the 60-character caption falls back too.
    #expect(ShareCaption.defaultText(partnerName: String(repeating: "x", count: 40)) == "Look what my love drew on my lock screen")
    #expect(ShareCaption.limited(String(repeating: "a", count: 70)).count == 60)
}

// MARK: Analytics

@Test func shareEventsCarryTheirProperties() {
    #expect(AnalyticsEvent.shareOpened.name.rawValue == "mulberry_share_opened")
    #expect(AnalyticsEvent.shareOpened.properties.isEmpty)
    #expect(AnalyticsEvent.shareFrameSelected(frameKey: "polaroid").properties == ["frame_key": "polaroid"])
    #expect(AnalyticsEvent.shareCompleted(frameKey: "plain", usedRealWallpaper: true, captionEdited: false,
                                          activityType: "com.apple.UIKit.activity.SaveToCameraRoll").properties == [
        "frame_key": "plain", "used_real_wallpaper": "true", "caption_edited": "false",
        "activity_type": "com.apple.UIKit.activity.SaveToCameraRoll"
    ])
    #expect(AnalyticsEvent.shareCompleted(frameKey: "plain", usedRealWallpaper: false, captionEdited: true).properties == [
        "frame_key": "plain", "used_real_wallpaper": "false", "caption_edited": "true"
    ])
}
