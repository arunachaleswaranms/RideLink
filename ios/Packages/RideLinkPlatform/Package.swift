// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "RideLinkPlatform",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        .library(name: "RideLinkPlatform", targets: ["RideLinkPlatform"]),
    ],
    dependencies: [
        .package(path: "../RideLinkCore"),
    ],
    targets: [
        // Apple-framework integrations (ARCHITECTURE §9.2). Unlike RideLinkCore this may import
        // Network, AVFoundation etc. — it is the one place allowed to know Apple platform APIs
        // exist.
        .target(
            name: "RideLinkPlatform",
            dependencies: ["RideLinkCore"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        .testTarget(
            name: "RideLinkPlatformTests",
            dependencies: ["RideLinkPlatform"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
    ]
)
