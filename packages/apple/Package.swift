// swift-tools-version: 6.0
import PackageDescription

// Product logic shared by the iPhone, iPad and Mac apps: the backend client, the canvas
// model and its sync, streaks and reactions. Nothing here draws or depends on UIKit or AppKit.
let package = Package(
    name: "MulberryApple",
    platforms: [.macOS(.v14), .iOS("27.0")],
    products: [
        .library(name: "ProductCore", targets: ["ProductCore"])
    ],
    targets: [
        .target(name: "ProductCore", path: "Sources/ProductCore"),
        .testTarget(name: "ProductCoreTests", dependencies: ["ProductCore"], path: "Tests/ProductCoreTests", resources: [.copy("Fixtures")])
    ]
)
