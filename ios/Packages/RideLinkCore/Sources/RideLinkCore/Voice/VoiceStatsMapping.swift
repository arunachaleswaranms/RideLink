import Foundation

/// Turns a flattened WebRTC statistics report into `VoiceEngineDiagnostics`.
///
/// Both platforms flatten their own report type — `RTCStatsReport` on Android, `RTCStatisticsReport`
/// on Apple — into `[statsId: [memberName: stringValue]]` **at the callback boundary**, then call
/// this. Two independent reasons make that the right shape:
///
/// 1. `RTCStatisticsReport` is not `Sendable`, so under Swift 6 strict concurrency the report cannot
///    leave its callback at all; only primitives can (ADR-020). This is not a style choice — the
///    compiler refuses the alternative.
/// 2. Reducing to a string map at the boundary means the fields PROTOCOL §7.7 forbids are simply never
///    carried around. A report object passed further in is an invitation to log all of it.
///
/// The names below are the W3C `webrtc-stats` member names, which both stacks use, so this function is
/// the same on both platforms and is unit-tested from the same expectations.
///
/// **Absent means absent.** A member the stack did not report leaves its field nil rather than becoming
/// zero. A zero packet count and "the platform has not told us yet" are different facts and the
/// diagnostics screen must not conflate them.
public enum VoiceStatsMapping {
    public static let typeKey = "__type"

    public static func merge(
        base: VoiceEngineDiagnostics,
        flattened: [String: [String: String]]
    ) -> VoiceEngineDiagnostics {
        func byType(_ type: String) -> [[String: String]] {
            flattened.values.filter { $0[typeKey] == type }
        }

        let transport = byType("transport").first
        let candidatePairs = byType("candidate-pair")
        let selectedPair =
            candidatePairs.first { $0["state"] == "succeeded" && $0["nominated"] != "false" }
                ?? candidatePairs.first { $0["state"] == "succeeded" }
        let localCandidates = byType("local-candidate")
        let remoteCandidates = byType("remote-candidate")
        let outbound = byType("outbound-rtp").first { $0["kind"] == "audio" || $0["mediaType"] == "audio" }
        let inbound = byType("inbound-rtp").first { $0["kind"] == "audio" || $0["mediaType"] == "audio" }
        let codec = byType("codec").first { ($0["mimeType"] ?? "").hasPrefix("audio/") }

        var next = base
        next.dtlsState = transport?["dtlsState"] ?? base.dtlsState
        next.srtpCipher = transport?["srtpCipher"] ?? base.srtpCipher
        next.dtlsCipher = transport?["dtlsCipher"] ?? base.dtlsCipher
        next.selectedLocalType =
            candidateType(in: localCandidates, id: selectedPair?["localCandidateId"], flattened: flattened)
                ?? base.selectedLocalType
        next.selectedRemoteType =
            candidateType(in: remoteCandidates, id: selectedPair?["remoteCandidateId"], flattened: flattened)
                ?? base.selectedRemoteType
        next.observedCandidateTypes = base.observedCandidateTypes.union(
            (localCandidates + remoteCandidates).compactMap { $0["candidateType"].map(candidateTypeFromStat) }
        )
        next.negotiatedCodec = codec?["mimeType"] ?? base.negotiatedCodec
        next.negotiatedClockRateHz = codec?["clockRate"].flatMap { Int($0) } ?? base.negotiatedClockRateHz
        next.negotiatedChannels = codec?["channels"].flatMap { Int($0) } ?? base.negotiatedChannels
        next.packetsSent = outbound?["packetsSent"].flatMap(int64Value) ?? base.packetsSent
        next.packetsReceived = inbound?["packetsReceived"].flatMap(int64Value) ?? base.packetsReceived
        next.packetsLost = inbound?["packetsLost"].flatMap(int64Value) ?? base.packetsLost
        // `jitter` and `roundTripTime` are seconds in webrtc-stats; the diagnostics show milliseconds,
        // which is the unit every other RideLink latency figure uses.
        next.jitterMs = inbound?["jitter"].flatMap { Double($0) }.map { $0 * msPerSecond } ?? base.jitterMs
        next.roundTripTimeMs =
            selectedPair?["currentRoundTripTime"].flatMap { Double($0) }.map { $0 * msPerSecond }
                ?? base.roundTripTimeMs
        return next
    }

    /// Resolves a candidate id to its type, preferring the stats entry the id actually names and
    /// falling back to the single candidate of that kind when the stack did not link them.
    private static func candidateType(
        in candidates: [[String: String]],
        id: String?,
        flattened: [String: [String: String]]
    ) -> IceCandidateType? {
        if let id, let type = flattened[id]?["candidateType"] {
            return candidateTypeFromStat(type)
        }
        guard candidates.count == 1, let type = candidates[0]["candidateType"] else { return nil }
        return candidateTypeFromStat(type)
    }

    /// webrtc-stats spells these `host`, `srflx`, `prflx`, `relay` — the same tokens as an SDP line.
    private static func candidateTypeFromStat(_ value: String) -> IceCandidateType {
        IceCandidateType.allCases.first { $0.rawValue == value.lowercased() } ?? .unknown
    }

    /// Counters arrive as integral values but some stacks stringify them as `1.0`.
    private static func int64Value(_ s: String) -> Int64? {
        Int64(s) ?? Double(s).flatMap { Int64(exactly: $0.rounded(.towardZero)) }
    }

    private static let msPerSecond = 1_000.0
}
