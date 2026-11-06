// swift-tools-version: 6.0
import PackageDescription

// Code shared by the iPhone, iPad and Mac apps. ProductCore is the product logic: the backend
// client, the canvas model and its sync, streaks and reactions; it never draws. CanvasDrawing
// draws the canvas into a Core Graphics context, through UIKit or AppKit only where they differ.
// StickerAssets downloads, caches and lists sticker artwork. ProductUI holds the SwiftUI views
// both apps show alike: the reactions' artwork and playback, and the celebration confetti.
// ShareFrames draws the share image (lock screen, frame, caption, brand line) and keeps the frames.
let package = Package(
    name: "MulberryApple",
    platforms: [.macOS("26.0"), .iOS("27.0")],
    products: [
        .library(name: "ProductCore", targets: ["ProductCore"]),
        .library(name: "CanvasDrawing", targets: ["CanvasDrawing"]),
        .library(name: "StickerAssets", targets: ["StickerAssets"]),
        .library(name: "ProductUI", targets: ["ProductUI"]),
        .library(name: "ShareFrames", targets: ["ShareFrames"])
    ],
    targets: [
        .target(name: "ProductCore", path: "Sources/ProductCore"),
        .target(name: "CanvasDrawing", dependencies: ["ProductCore"], path: "Sources/CanvasDrawing"),
        .target(name: "StickerAssets", dependencies: ["ProductCore", "CanvasDrawing"], path: "Sources/StickerAssets"),
        .target(name: "ProductUI", dependencies: ["ProductCore"], path: "Sources/ProductUI"),
        .target(name: "ShareFrames", dependencies: ["ProductCore"], path: "Sources/ShareFrames"),
        .testTarget(name: "ProductCoreTests", dependencies: ["ProductCore"], path: "Tests/ProductCoreTests", resources: [.copy("Fixtures")]),
        .testTarget(name: "CanvasDrawingTests", dependencies: ["CanvasDrawing", "ProductCore"], path: "Tests/CanvasDrawingTests"),
        .testTarget(name: "StickerAssetsTests", dependencies: ["StickerAssets", "ProductCore"], path: "Tests/StickerAssetsTests"),
        .testTarget(name: "ShareFramesTests", dependencies: ["ShareFrames", "ProductCore"], path: "Tests/ShareFramesTests", resources: [.copy("Fixtures")])
    ]
)
