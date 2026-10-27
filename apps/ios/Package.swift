// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "MulberryDelivery",
    platforms: [.macOS(.v14), .iOS("27.0")],
    products: [
        .library(name: "DeliveryCore", targets: ["DeliveryCore"]),
        .library(name: "ProductCore", targets: ["ProductCore"])
    ],
    targets: [
        .target(name: "DeliveryCore", path: "Sources/DeliveryCore"),
        .testTarget(name: "DeliveryCoreTests", dependencies: ["DeliveryCore"], path: "Tests/DeliveryCoreTests"),
        .target(name: "ProductCore", path: "Sources/ProductCore"),
        .testTarget(name: "ProductCoreTests", dependencies: ["ProductCore"], path: "Tests/ProductCoreTests", resources: [.copy("Fixtures")])
    ]
)
