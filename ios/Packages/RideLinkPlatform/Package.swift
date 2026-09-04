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
        // Phase 3's local music database (ADR-014's iOS mirror of Android's Room usage). Reviewed
        // per this phase's brief §5 before adding, the first dependency here besides the pinned
        // WebRTC pod: groue/GRDB.swift, MIT licence, a thin Swift wrapper over the platform's own
        // SQLite (its `GRDBSQLite` target links `sqlite3`, no bundled/vendored database engine and
        // no binaryTarget the way WebRTC is). Its default `Package.swift` dependency list is empty —
        // the only two conditional additions (`swift-docc-plugin` for documentation builds,
        // `SQLCipher.swift` if manually uncommented) are both inert for a plain
        // `.product(name: "GRDB", package: "GRDB")` consumer, so this pulls in zero transitive
        // packages. `Sources/` has no `URLSession`/`Network`/`CFNetwork` import anywhere — verified
        // by inspection, not assumed from the README — so there is no network path to audit.
        // `SQLITE_ENABLE_FTS5` is defined unconditionally in its `Package.swift` (FTS5 has been on
        // by default since GRDB 6.7.0), which is why this phase can use real FTS5 rather than the
        // FTS4 Room ships on Android — a deliberate, documented per-platform difference (the plan's
        // own note), not a defect. Exact pin, never a range, matching the WebRTC precedent above.
        .package(url: "https://github.com/groue/GRDB.swift.git", exact: "7.11.1"),
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
                .product(name: "GRDB", package: "GRDB.swift"),
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
