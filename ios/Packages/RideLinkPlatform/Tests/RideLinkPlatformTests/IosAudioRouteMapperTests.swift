import Foundation
import XCTest
@testable import RideLinkCore
@testable import RideLinkPlatform

/// The **only** place an Apple audio route becomes ADR-016 wire vocabulary, so it is the only place a
/// mapping error can hide — and it is pure over `AudioPortKind`, so it can be exhausted on a laptop.
///
/// That `AudioPortKind` exists rather than `AVAudioSession.Port` is why this runs at all: `AVAudioSession`
/// is iOS-only and this target also builds for macOS, which is the mechanism that lets `swift test` run
/// the shared vectors and the real WebRTC loopback (ADR-020).
///
/// **What this does not prove.** Every expectation below is about the *mapping*, not about the hardware:
/// whether the real TWS earbuds actually negotiate wideband rather than narrowband, and whether opening
/// their microphone really drags the output onto the duplex profile, are measurements nobody has taken.
/// `confidence` is asserted to be `.assumed` for exactly that reason — see `docs/PHASE0_RESULTS.md`.
///
/// The Kotlin mirror is `com.ridelink.audio.route.AndroidAudioRouteMapperTest`, asserting the same
/// semantics over Android's device types.
final class IosAudioRouteMapperTests: XCTestCase {
    func testBluetoothWithTheMicrophoneOpenIsDuplexWidebandAndReducedQuality() {
        let snapshot = IosAudioRouteMapper.map(
            outputPort: .bluetoothHandsFree,
            hasInput: true,
            microphoneOpen: true,
            duplexCategoryActive: true,
            sampleRateHz: 48_000,
            lastChangeReason: .categoryChange
        )
        XCTAssertEqual(snapshot.endpointClass, .bluetooth)
        XCTAssertEqual(snapshot.effectiveOutputProfile, .duplexWideband)
        XCTAssertEqual(snapshot.effectiveInputProfile, .duplexWideband)
        // ADR-016's central claim, and the product's highest risk, stated on the wire.
        XCTAssertEqual(snapshot.profileCoupling, .inputForcesOutput)
        // The honest answer: the pillion's music has collapsed to narrowband.
        XCTAssertEqual(snapshot.mediaQuality, .reduced)
        // The session says 48 kHz whatever the endpoint is doing; trusting it here would overstate the
        // quality by 3x.
        XCTAssertEqual(snapshot.effectiveOutputSampleRateHz, 16_000)
        XCTAssertEqual(snapshot.effectiveInputSampleRateHz, 16_000)
    }

    func testBluetoothWithTheMicrophoneClosedIsMediaQualityOutputOnly() {
        let snapshot = IosAudioRouteMapper.map(
            outputPort: .bluetoothMedia,
            hasInput: false,
            microphoneOpen: false,
            duplexCategoryActive: false,
            sampleRateHz: 48_000,
            lastChangeReason: .categoryChange
        )
        XCTAssertEqual(snapshot.effectiveOutputProfile, .mediaStereo)
        XCTAssertEqual(snapshot.effectiveInputProfile, .none)
        XCTAssertEqual(snapshot.mediaQuality, .full)
        XCTAssertEqual(snapshot.effectiveOutputSampleRateHz, 48_000)
        XCTAssertNil(snapshot.effectiveInputSampleRateHz)
    }

    /// The one Bluetooth-shaped route that does *not* degrade music: a wired headset carries
    /// media-quality output and usable input at once, so the two directions genuinely are independent.
    func testAWiredHeadsetIsDuplexWideStereoAndIndependent() {
        let snapshot = IosAudioRouteMapper.map(
            outputPort: .wiredHeadsetWithMic,
            hasInput: true,
            microphoneOpen: true,
            duplexCategoryActive: true,
            sampleRateHz: 48_000,
            lastChangeReason: .newDeviceAvailable
        )
        XCTAssertEqual(snapshot.endpointClass, .wired)
        XCTAssertEqual(snapshot.effectiveOutputProfile, .duplexWideStereo)
        XCTAssertEqual(snapshot.profileCoupling, .independent)
        XCTAssertEqual(snapshot.mediaQuality, .full)
        XCTAssertEqual(snapshot.effectiveOutputSampleRateHz, 48_000)
    }

    func testWiredOutputWithNoMicrophoneStaysOutputOnly() {
        let snapshot = IosAudioRouteMapper.map(
            outputPort: .wiredOutputOnly,
            hasInput: false,
            microphoneOpen: true,
            duplexCategoryActive: true,
            sampleRateHz: 48_000,
            lastChangeReason: .newDeviceAvailable
        )
        XCTAssertEqual(snapshot.effectiveOutputProfile, .mediaStereo)
        XCTAssertEqual(snapshot.mediaQuality, .full)
    }

    func testNothingAttachedIsTheBuiltinRoute() {
        let snapshot = IosAudioRouteMapper.map(
            outputPort: .builtInSpeaker,
            hasInput: true,
            microphoneOpen: true,
            duplexCategoryActive: true,
            sampleRateHz: 48_000,
            lastChangeReason: .noSuitableRoute
        )
        XCTAssertEqual(snapshot.endpointClass, .builtinSpeaker)
        XCTAssertEqual(snapshot.effectiveOutputProfile, .builtin)
        XCTAssertEqual(snapshot.effectiveInputProfile, .builtin)
        XCTAssertEqual(snapshot.profileCoupling, .independent)
        // ADR-016 Amendment A1: `builtin` is duplex but **not narrowed**, so it is `full`. The original
        // prose ("duplex and not duplex_wide_stereo") gave `reduced` here and contradicted ADR-016's own
        // representable-states table; this row is the corrected rule.
        XCTAssertEqual(snapshot.mediaQuality, .full)
    }

    /// ADR-016 choice 4: "the platform did not tell us" is a representable answer, not a guess.
    func testAnUnknownRouteIsReportedAsUnknownRatherThanGuessed() {
        let snapshot = IosAudioRouteMapper.map(
            outputPort: nil,
            hasInput: false,
            microphoneOpen: false,
            duplexCategoryActive: false,
            sampleRateHz: nil,
            lastChangeReason: .unknown
        )
        XCTAssertEqual(snapshot.endpointClass, .unknown)
        XCTAssertEqual(snapshot.effectiveOutputProfile, .unknown)
        XCTAssertEqual(snapshot.effectiveInputProfile, .unknown)
        XCTAssertEqual(snapshot.profileCoupling, .unknown)
        XCTAssertEqual(snapshot.mediaQuality, .unknown)
        XCTAssertNil(snapshot.effectiveOutputSampleRateHz)
    }

    /// Without the duplex category no duplex profile is in force whatever is attached. Reporting one here
    /// would be the inverse of the false reassurance ADR-016 removed: claiming the music is degraded when
    /// it is not.
    func testNoDuplexProfileIsClaimedWhenTheDuplexCategoryIsNotActive() {
        let snapshot = IosAudioRouteMapper.map(
            outputPort: .bluetoothHandsFree,
            hasInput: true,
            microphoneOpen: false,
            duplexCategoryActive: false,
            sampleRateHz: 48_000,
            lastChangeReason: .categoryChange
        )
        XCTAssertEqual(snapshot.effectiveOutputProfile, .mediaStereo)
        XCTAssertEqual(snapshot.mediaQuality, .full)
    }

    func testLowEnergyAudioIsNotYetClaimedToBeTheRouteThatKeepsMusicIntact() {
        let snapshot = IosAudioRouteMapper.map(
            outputPort: .bluetoothLowEnergy,
            hasInput: true,
            microphoneOpen: true,
            duplexCategoryActive: true,
            sampleRateHz: 48_000,
            lastChangeReason: .newDeviceAvailable
        )
        // LE Audio *can* carry media-quality output with usable input, which would make it the one
        // Bluetooth route that does not degrade music. Claiming that before measuring it is exactly the
        // false reassurance ADR-016 exists to prevent.
        XCTAssertEqual(snapshot.effectiveOutputProfile, .duplexWideband)
        XCTAssertEqual(snapshot.mediaQuality, .reduced)
    }

    /// ADR-016 choice 4, asserted rather than assumed: nothing this mapper produces may claim to be a
    /// measurement until `docs/PHASE0_RESULTS.md` is filled in. This test is what will fail — loudly, and
    /// in the right place — when someone edits the mapping without recording the measurement.
    func testEveryMappingReportsAssumedConfidenceUntilPhase0ResultsAreRecorded() {
        for port in [AudioPortKind?.none] + AudioPortKind.allCases.map(Optional.some) {
            for micOpen in [true, false] {
                for duplex in [true, false] {
                    for hasInput in [true, false] {
                        let snapshot = IosAudioRouteMapper.map(
                            outputPort: port,
                            hasInput: hasInput,
                            microphoneOpen: micOpen,
                            duplexCategoryActive: duplex,
                            sampleRateHz: 48_000,
                            lastChangeReason: .unknown
                        )
                        XCTAssertEqual(
                            snapshot.confidence, .assumed,
                            "port=\(String(describing: port)) mic=\(micOpen) duplex=\(duplex)"
                        )
                    }
                }
            }
        }
    }

    /// A privacy property, not a formatting one: ADR-016 rejected free-text route descriptions because a
    /// device *name* is personally identifying. Every field this mapper produces is an enum or an
    /// integer, so there is nothing for a name to travel in.
    func testTheSnapshotCarriesNoFreeTextPlatformStringAnywhere() {
        let snapshot = IosAudioRouteMapper.map(
            outputPort: .bluetoothHandsFree,
            hasInput: true,
            microphoneOpen: true,
            duplexCategoryActive: true,
            sampleRateHz: 48_000,
            lastChangeReason: .newDeviceAvailable
        )
        let rendered = String(describing: snapshot)
        for forbidden in ["A2DP", "HFP", "AVAudioSession", "bluetoothHFP", "Port("] {
            XCTAssertFalse(
                rendered.contains(forbidden),
                "the snapshot rendered a platform string (\(forbidden)), which must never reach the wire"
            )
        }
    }

    /// A route change is a first-class state (ADR-016), and ARCHITECTURE §7.3 suspends the drift ladder
    /// while either peer is transitioning — so the mapper has to be able to say so.
    func testTransitioningIsRepresentable() {
        let snapshot = IosAudioRouteMapper.map(
            outputPort: .bluetoothHandsFree,
            hasInput: true,
            microphoneOpen: true,
            duplexCategoryActive: true,
            sampleRateHz: 48_000,
            lastChangeReason: .categoryChange,
            routeState: .transitioning,
            interrupted: true
        )
        XCTAssertEqual(snapshot.routeState, .transitioning)
        // An interruption is an *audio route* fact, not a `VOICE_STATE` value (PROTOCOL §7.4).
        XCTAssertTrue(snapshot.interrupted)
    }
}
