import Foundation

#if os(iOS)
import AVFoundation

/// The only writer of process-global AVAudioSession configuration. Music and voice report their
/// needs here; neither can overwrite the other's category or deactivate a resource the other owns.
public actor IosAudioSessionCoordinator {
    private let session: AVAudioSession
    private var musicActive = false
    private var voiceActive = false

    public init(session: AVAudioSession = .sharedInstance()) {
        self.session = session
    }

    public func activateMusic() throws {
        musicActive = true
        try applyConfiguration()
    }

    public func deactivateMusic() throws {
        musicActive = false
        try applyConfiguration()
    }

    public func activateVoice() throws {
        voiceActive = true
        do {
            try applyConfiguration()
        } catch {
            voiceActive = false
            throw error
        }
    }

    public func deactivateVoice() throws {
        voiceActive = false
        try applyConfiguration()
    }

    public func reactivateAfterInterruption() throws {
        try applyConfiguration()
    }

    public func resetAfterMediaServicesFailure() {
        voiceActive = false
        // Every other transition reasserts configuration through `applyConfiguration()`; a reset is
        // no different; music remains the sole surviving claim; errors are swallowed exactly as the
        // pre-Phase-6 unconditional reset did.
        try? applyConfiguration()
    }

    private func applyConfiguration() throws {
        if voiceActive {
            try session.setCategory(
                .playAndRecord,
                mode: .voiceChat,
                options: [.allowBluetoothHFP, .allowBluetoothA2DP, .mixWithOthers]
            )
            try session.setActive(true)
        } else if musicActive {
            try session.setCategory(.playback, mode: .default, options: [])
            try session.setActive(true)
        } else {
            try session.setActive(false, options: [.notifyOthersOnDeactivation])
        }
    }
}
#else
public actor IosAudioSessionCoordinator {
    public init() {}
    public func activateMusic() throws {}
    public func deactivateMusic() throws {}
    public func activateVoice() throws {}
    public func deactivateVoice() throws {}
    public func reactivateAfterInterruption() throws {}
    public func resetAfterMediaServicesFailure() {}
}
#endif
