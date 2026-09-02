package com.ridelink.core.voice

/**
 * Turns a flattened WebRTC statistics report into [VoiceEngineDiagnostics].
 *
 * Both platforms flatten their own report type — `RTCStatsReport` on Android,
 * `RTCStatisticsReport` on Apple — into `Map<statsId, Map<memberName, stringValue>>` **at the
 * callback boundary**, then call this. Two independent reasons make that the right shape:
 *
 * 1. On iOS `RTCStatisticsReport` is not `Sendable`, so under Swift 6 strict concurrency the report
 *    cannot leave its callback at all; only primitives can (ADR-020).
 * 2. Reducing to a string map at the boundary means the fields PROTOCOL §7.7 forbids are simply
 *    never carried around. A report object passed further in is an invitation to log all of it.
 *
 * The names below are the W3C `webrtc-stats` member names, which both stacks use, so this function
 * is the same on both platforms and is unit-tested from the same expectations.
 *
 * **Absent means absent.** A member the stack did not report leaves its field null rather than
 * becoming zero. A zero packet count and "the platform has not told us yet" are different facts and
 * the diagnostics screen must not conflate them.
 */
object VoiceStatsMapping {
    const val TYPE_KEY = "__type"

    fun merge(
        base: VoiceEngineDiagnostics,
        flattened: Map<String, Map<String, String>>,
    ): VoiceEngineDiagnostics {
        val byType = { type: String -> flattened.values.filter { it[TYPE_KEY] == type } }

        val transport = byType("transport").firstOrNull()
        val selectedPair =
            byType("candidate-pair").firstOrNull { it["state"] == "succeeded" && it["nominated"] != "false" }
                ?: byType("candidate-pair").firstOrNull { it["state"] == "succeeded" }
        val localCandidates = byType("local-candidate")
        val remoteCandidates = byType("remote-candidate")
        val outbound = byType("outbound-rtp").firstOrNull { it["kind"] == "audio" || it["mediaType"] == "audio" }
        val inbound = byType("inbound-rtp").firstOrNull { it["kind"] == "audio" || it["mediaType"] == "audio" }
        val codec = byType("codec").firstOrNull { it["mimeType"]?.startsWith("audio/") == true }

        val selectedLocalId = selectedPair?.get("localCandidateId")
        val selectedRemoteId = selectedPair?.get("remoteCandidateId")

        val observed =
            base.observedCandidateTypes +
                (localCandidates + remoteCandidates).mapNotNull { it["candidateType"]?.let(::candidateTypeFromStat) }

        return base.copy(
            dtlsState = transport?.get("dtlsState") ?: base.dtlsState,
            srtpCipher = transport?.get("srtpCipher") ?: base.srtpCipher,
            dtlsCipher = transport?.get("dtlsCipher") ?: base.dtlsCipher,
            selectedLocalType =
                candidateTypeById(localCandidates, selectedLocalId, flattened) ?: base.selectedLocalType,
            selectedRemoteType =
                candidateTypeById(remoteCandidates, selectedRemoteId, flattened) ?: base.selectedRemoteType,
            observedCandidateTypes = observed,
            negotiatedCodec = codec?.get("mimeType") ?: base.negotiatedCodec,
            negotiatedClockRateHz = codec?.get("clockRate")?.toIntOrNull() ?: base.negotiatedClockRateHz,
            negotiatedChannels = codec?.get("channels")?.toIntOrNull() ?: base.negotiatedChannels,
            packetsSent = outbound?.get("packetsSent")?.toLongValue() ?: base.packetsSent,
            packetsReceived = inbound?.get("packetsReceived")?.toLongValue() ?: base.packetsReceived,
            packetsLost = inbound?.get("packetsLost")?.toLongValue() ?: base.packetsLost,
            // `jitter` and `roundTripTime` are seconds in webrtc-stats; the diagnostics show
            // milliseconds, which is the unit every other RideLink latency figure uses.
            jitterMs = inbound?.get("jitter")?.toDoubleOrNull()?.times(MS_PER_SECOND) ?: base.jitterMs,
            roundTripTimeMs =
                selectedPair?.get("currentRoundTripTime")?.toDoubleOrNull()?.times(MS_PER_SECOND)
                    ?: base.roundTripTimeMs,
        )
    }

    /**
     * Resolves a candidate id to its type, preferring the stats entry the id actually names and
     * falling back to the single candidate of that kind when the stack did not link them.
     */
    private fun candidateTypeById(
        candidates: List<Map<String, String>>,
        id: String?,
        flattened: Map<String, Map<String, String>>,
    ): IceCandidateType? {
        if (id != null) {
            flattened[id]?.get("candidateType")?.let { return candidateTypeFromStat(it) }
        }
        return candidates.singleOrNull()?.get("candidateType")?.let(::candidateTypeFromStat)
    }

    /** webrtc-stats spells these `host`, `srflx`, `prflx`, `relay` — the same tokens as an SDP line. */
    private fun candidateTypeFromStat(value: String): IceCandidateType =
        when (value.lowercase()) {
            "host" -> IceCandidateType.HOST
            "srflx" -> IceCandidateType.SRFLX
            "prflx" -> IceCandidateType.PRFLX
            "relay" -> IceCandidateType.RELAY
            else -> IceCandidateType.UNKNOWN
        }

    /** Counters arrive as integral values but some stacks stringify them as `1.0`. */
    private fun String.toLongValue(): Long? = toLongOrNull() ?: toDoubleOrNull()?.toLong()

    private const val MS_PER_SECOND = 1_000.0
}
