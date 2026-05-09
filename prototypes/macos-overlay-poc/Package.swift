// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "MulberryMacPOC",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .executable(
            name: "MulberryMacPOC",
            targets: ["MulberryMacPOC"]
        )
    ],
    targets: [
        .executableTarget(
            name: "MulberryMacPOC"
        )
    ]
)
