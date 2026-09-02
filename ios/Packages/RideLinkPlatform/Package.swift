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
        // ADR-020: exact pin, never a range. `stasel/WebRTC` is a binaryTarget whose SHA-256 is
        // recorded in its own manifest, so the bytes are verified at resolve time.
        //
        // **Was `151.0.0` until 2 Sep 2026, when upstream deleted that release** and CI began
        // failing with a hard 404 on the binary — see ADR-020 Amendment A1. Integrity was never in
        // question (the checksum still guards that); *availability* was, because an SPM binaryTarget
        // resolves a GitHub release asset that upstream can remove. `151.0.1` is not a usable
        // replacement: its manifest still points at the deleted `151.0.0` URL.
        .package(url: "https://github.com/stasel/WebRTC.git", exact: "152.0.0"),
    ],
    targets: [
        // Apple-framework integrations (ARCHITECTURE §9.2). Unlike RideLinkCore this may import
        // Network, AVFoundation etc. — it is the one place allowed to know Apple platform APIs
        // exist.
        .target(
            name: "RideLinkPlatform",
            dependencies: [
                "RideLinkCore",
                .product(name: "WebRTC", package: "WebRTC"),
            ],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        .testTarget(
            name: "RideLinkPlatformTests",
            dependencies: ["RideLinkPlatform"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
    ]
)
