// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "RideLinkCore",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        .library(name: "RideLinkCore", targets: ["RideLinkCore"]),
    ],
    targets: [
        // Import allowlist is Foundation + CryptoKit only (ARCHITECTURE §9.2). Building and
        // testing for macOS is what makes an accidental UIKit/SwiftUI/AVFoundation/Network
        // import fail on a laptop instead of only surfacing on an iOS build.
        .target(
            name: "RideLinkCore",
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        .testTarget(
            name: "RideLinkCoreTests",
            dependencies: ["RideLinkCore"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
    ]
)
