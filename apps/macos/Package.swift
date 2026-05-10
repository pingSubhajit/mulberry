// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "MulberryMac",
    defaultLocalization: "en",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .executable(
            name: "MulberryMac",
            targets: ["MulberryMacApp"]
        ),
        .library(name: "AppShell", targets: ["AppShell"]),
        .library(name: "Auth", targets: ["Auth"]),
        .library(name: "Networking", targets: ["Networking"]),
        .library(name: "Sync", targets: ["Sync"]),
        .library(name: "CanvasCore", targets: ["CanvasCore"]),
        .library(name: "CanvasRendering", targets: ["CanvasRendering"]),
        .library(name: "Persistence", targets: ["Persistence"]),
        .library(name: "Overlay", targets: ["Overlay"]),
        .library(name: "QuickDraw", targets: ["QuickDraw"]),
        .library(name: "Stickers", targets: ["Stickers"]),
        .library(name: "Reactions", targets: ["Reactions"]),
        .library(name: "Notifications", targets: ["Notifications"]),
        .library(name: "Settings", targets: ["Settings"]),
        .library(name: "Diagnostics", targets: ["Diagnostics"])
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
                "CanvasCore",
                "CanvasRendering",
                "Persistence",
                "Overlay",
                "QuickDraw",
                "Stickers",
                "Reactions",
                "Notifications",
                "Settings",
                "Diagnostics"
            ]
        ),
        .target(name: "Auth", dependencies: ["Networking"]),
        .target(name: "Networking"),
        .target(name: "Sync", dependencies: ["CanvasCore", "Networking", "Persistence"]),
        .target(name: "CanvasCore"),
        .target(name: "CanvasRendering", dependencies: ["CanvasCore"]),
        .target(name: "Persistence", dependencies: ["CanvasCore"]),
        .target(name: "Overlay"),
        .target(name: "QuickDraw", dependencies: ["Overlay", "CanvasCore"]),
        .target(name: "Stickers", dependencies: ["Networking", "Persistence"]),
        .target(name: "Reactions", dependencies: ["Networking"]),
        .target(name: "Notifications"),
        .target(name: "Settings", dependencies: ["Persistence"]),
        .target(name: "Diagnostics")
    ]
)
