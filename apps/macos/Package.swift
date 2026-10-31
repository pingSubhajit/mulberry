// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "MulberryMac",
    defaultLocalization: "en",
    platforms: [
        // Liquid Glass.
        .macOS("26.0")
    ],
    products: [
        .executable(
            name: "Mulberry",
            targets: ["MulberryMacApp"]
        ),
        .library(name: "AppShell", targets: ["AppShell"]),
        .library(name: "Auth", targets: ["Auth"]),
        .library(name: "Networking", targets: ["Networking"]),
        .library(name: "Sync", targets: ["Sync"]),
        .library(name: "CanvasRendering", targets: ["CanvasRendering"]),
        .library(name: "CanvasEditing", targets: ["CanvasEditing"]),
        .library(name: "Persistence", targets: ["Persistence"]),
        .library(name: "Overlay", targets: ["Overlay"]),
        .library(name: "QuickDraw", targets: ["QuickDraw"]),
        .library(name: "Stickers", targets: ["Stickers"]),
        .executable(
            name: "OverlayRegressionCheck",
            targets: ["OverlayRegressionCheck"]
        )
    ],
    dependencies: [
        // The backend client, canvas model and sync shared with the iPhone and iPad app.
        .package(path: "../../packages/apple"),
        // Crash reports, beside the iPhone's and Android's, at the iPhone app's version.
        .package(url: "https://github.com/firebase/firebase-ios-sdk", exact: "12.19.3")
    ],
    targets: [
        .executableTarget(
            name: "MulberryMacApp",
            dependencies: [
                "AppShell"
            ]
        ),
        .target(
            name: "AppShell",
            dependencies: [
                "Auth",
                "Networking",
                "Sync",
                "CanvasRendering",
                "CanvasEditing",
                "Persistence",
                "Overlay",
                "QuickDraw",
                "Stickers",
                .product(name: "ProductCore", package: "apple"),
                .product(name: "CanvasDrawing", package: "apple"),
                .product(name: "StickerAssets", package: "apple"),
                .product(name: "ProductUI", package: "apple"),
                .product(name: "FirebaseCrashlytics", package: "firebase-ios-sdk")
            ]
        ),
        .target(name: "Auth", dependencies: ["Networking", "Persistence", .product(name: "ProductCore", package: "apple")]),
        .target(name: "Networking", dependencies: [.product(name: "ProductCore", package: "apple")]),
        .target(name: "Sync", dependencies: [.product(name: "ProductCore", package: "apple")]),
        .target(name: "CanvasRendering", dependencies: [.product(name: "ProductCore", package: "apple"), .product(name: "CanvasDrawing", package: "apple")]),
        .target(name: "CanvasEditing", dependencies: [.product(name: "ProductCore", package: "apple")]),
        .target(name: "Persistence", dependencies: [.product(name: "ProductCore", package: "apple")]),
        .target(name: "Overlay", dependencies: ["CanvasRendering", .product(name: "ProductCore", package: "apple"), .product(name: "CanvasDrawing", package: "apple")]),
        .target(name: "QuickDraw", dependencies: ["Overlay"]),
        .target(name: "Stickers", dependencies: [.product(name: "ProductCore", package: "apple"), .product(name: "CanvasDrawing", package: "apple"), .product(name: "StickerAssets", package: "apple")]),
        .executableTarget(name: "OverlayRegressionCheck", dependencies: ["Overlay"]),
        .testTarget(
            name: "CanvasRenderingTests",
            dependencies: ["CanvasRendering", .product(name: "ProductCore", package: "apple"), .product(name: "CanvasDrawing", package: "apple")]
        ),
        .testTarget(
            name: "CanvasEditingTests",
            dependencies: ["CanvasEditing", .product(name: "ProductCore", package: "apple")]
        ),
        .testTarget(
            name: "SyncTests",
            dependencies: ["Sync", .product(name: "ProductCore", package: "apple")]
        )
    ]
)
