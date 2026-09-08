import Foundation

/// PROTOCOL §3 Playback group (Phase 5).
public enum PlaybackMessageTypes {
    public static let play = "PLAY"
    public static let pause = "PAUSE"
    public static let resume = "RESUME"
    public static let seek = "SEEK"
    public static let next = "NEXT"
    public static let previous = "PREVIOUS"
    public static let positionReport = "POSITION_REPORT"
    public static let playbackState = "PLAYBACK_STATE"

    public static let all: Set<String> = [play, pause, resume, seek, next, previous, positionReport, playbackState]

    /// The subset that carries a `PlaybackCommandHeader` and is therefore ordered by `command_seq`.
    public static let commands: Set<String> = [play, pause, resume, seek, next, previous]
}

/// Why a playback payload was refused. Recorded in diagnostics; never sent to the peer verbatim.
///
/// Raw values match the Kotlin enum constant names exactly, since
/// `protocol/vectors/playback-messages/` names them that way and both platforms read the same file.
public enum PlaybackMessageRejection: String, Sendable, Equatable {
    case unknownType = "UNKNOWN_TYPE"
    case missingField = "MISSING_FIELD"
    case wrongFieldType = "WRONG_FIELD_TYPE"
    case malformedContentHash = "MALFORMED_CONTENT_HASH"
    case malformedQueueItemId = "MALFORMED_QUEUE_ITEM_ID"
    case malformedPeerId = "MALFORMED_PEER_ID"
    case commandSeqOutOfRange = "COMMAND_SEQ_OUT_OF_RANGE"
    case revisionOutOfRange = "REVISION_OUT_OF_RANGE"
    case sessionTimeOutOfRange = "SESSION_TIME_OUT_OF_RANGE"
    case positionOutOfRange = "POSITION_OUT_OF_RANGE"
    case rateOutOfRange = "RATE_OUT_OF_RANGE"
}

/// Parses, bounds-checks and encodes PROTOCOL §5's playback messages. Total and non-trapping,
/// mirroring `TransferCodec`/`AudioStateCodec`/`VoiceSignalCodec` — a malformed frame is dropped and
/// the control connection survives, because the framing was intact and only this message's shape was
/// wrong.
///
/// Mirrors Android `core.protocol.PlaybackCodec`; both run `protocol/vectors/playback-messages/`.
public enum PlaybackCodec {
    public enum Result: Sendable, Equatable {
        case parsed(PlaybackMessage)
        case rejected(PlaybackMessageRejection)
    }

    public static let fieldCommandSeq = "command_seq"
    public static let fieldEffectiveAtSessionUs = "effective_at_session_us"
    public static let fieldIssuedBy = "issued_by"
    public static let fieldQueueRevision = "queue_revision"
    public static let fieldTrackHash = "track_hash"
    public static let fieldPositionMs = "position_ms"
    public static let fieldTargetPositionMs = "target_position_ms"
    public static let fieldQueueItemId = "queue_item_id"
    public static let fieldAtSessionUs = "at_session_us"
    public static let fieldPlaying = "playing"
    public static let fieldPlaybackRate = "playback_rate"

    public static func parse(type: String, payload: [String: JSONValue]) -> Result {
        switch type {
        case PlaybackMessageTypes.play: return parsePlay(payload)
        case PlaybackMessageTypes.pause: return parsePauseOrResume(payload, resume: false)
        case PlaybackMessageTypes.resume: return parsePauseOrResume(payload, resume: true)
        case PlaybackMessageTypes.seek: return parseSeek(payload)
        case PlaybackMessageTypes.next: return parseStep(payload, next: true)
        case PlaybackMessageTypes.previous: return parseStep(payload, next: false)
        case PlaybackMessageTypes.positionReport: return parsePositionReport(payload)
        case PlaybackMessageTypes.playbackState: return parsePlaybackState(payload)
        default: return .rejected(.unknownType)
        }
    }

    public static func wireType(_ message: PlaybackMessage) -> String {
        switch message {
        case .play: return PlaybackMessageTypes.play
        case .pause: return PlaybackMessageTypes.pause
        case .resume: return PlaybackMessageTypes.resume
        case .seek: return PlaybackMessageTypes.seek
        case .next: return PlaybackMessageTypes.next
        case .previous: return PlaybackMessageTypes.previous
        case .positionReport: return PlaybackMessageTypes.positionReport
        case .playbackState: return PlaybackMessageTypes.playbackState
        }
    }

    /// The outbound side of `parse` — the shape lives here, once, shared by both directions.
    public static func encode(_ message: PlaybackMessage) -> [String: JSONValue] {
        switch message {
        case .play(let header, let trackHash, let positionMs, let queueItemId):
            return encodeHeader(header)
                .merging([
                    fieldTrackHash: .string(trackHash.value),
                    fieldPositionMs: .number(Double(positionMs)),
                    fieldQueueItemId: .string(queueItemId),
                ]) { _, new in new }
        case .pause(let header, let positionMs), .resume(let header, let positionMs):
            return encodeHeader(header).merging([fieldPositionMs: .number(Double(positionMs))]) { _, new in new }
        case .seek(let header, let targetPositionMs):
            return encodeHeader(header).merging([fieldTargetPositionMs: .number(Double(targetPositionMs))]) { _, new in new }
        case .next(let header), .previous(let header):
            return encodeHeader(header)
        case .positionReport(let trackHash, let positionMs, let atSessionUs, let playing, let playbackRate):
            return [
                fieldTrackHash: .string(trackHash.value),
                fieldPositionMs: .number(Double(positionMs)),
                fieldAtSessionUs: .number(Double(atSessionUs)),
                fieldPlaying: .bool(playing),
                fieldPlaybackRate: .number(playbackRate),
            ]
        case .playbackState(
            let commandSeq, let queueRevision, let trackHash, let queueItemId, let positionMs, let playing, let atSessionUs
        ):
            return [
                fieldCommandSeq: .number(Double(commandSeq)),
                fieldQueueRevision: .number(Double(queueRevision)),
                fieldTrackHash: trackHash.map { JSONValue.string($0.value) } ?? .null,
                fieldQueueItemId: queueItemId.map { JSONValue.string($0) } ?? .null,
                fieldPositionMs: .number(Double(positionMs)),
                fieldPlaying: .bool(playing),
                fieldAtSessionUs: .number(Double(atSessionUs)),
            ]
        }
    }

    private static func encodeHeader(_ header: PlaybackCommandHeader) -> [String: JSONValue] {
        [
            fieldCommandSeq: .number(Double(header.commandSeq)),
            fieldEffectiveAtSessionUs: .number(Double(header.effectiveAtSessionUs)),
            fieldIssuedBy: .string(header.issuedBy.value),
            fieldQueueRevision: .number(Double(header.queueRevision)),
        ]
    }

    // MARK: - Headers

    private enum HeaderResult {
        case ok(PlaybackCommandHeader)
        case bad(Result)
    }

    private static func parseHeader(_ payload: [String: JSONValue]) -> HeaderResult {
        guard let commandSeq = phase5LongField(payload, fieldCommandSeq) else {
            return .bad(missingOrWrongType(payload, fieldCommandSeq))
        }
        if commandSeq < 0 || commandSeq > PlaybackBounds.maxWireInt { return .bad(.rejected(.commandSeqOutOfRange)) }
        guard let effectiveAt = phase5LongField(payload, fieldEffectiveAtSessionUs) else {
            return .bad(missingOrWrongType(payload, fieldEffectiveAtSessionUs))
        }
        if effectiveAt < 0 || effectiveAt > PlaybackBounds.maxWireInt { return .bad(.rejected(.sessionTimeOutOfRange)) }
        guard let issuedByRaw = phase5StringField(payload, fieldIssuedBy) else {
            return .bad(missingOrWrongType(payload, fieldIssuedBy))
        }
        guard let issuedBy = phase5ParsePeerId(issuedByRaw) else { return .bad(.rejected(.malformedPeerId)) }
        guard let queueRevision = phase5LongField(payload, fieldQueueRevision) else {
            return .bad(missingOrWrongType(payload, fieldQueueRevision))
        }
        if queueRevision < 0 || queueRevision > PlaybackBounds.maxWireInt { return .bad(.rejected(.revisionOutOfRange)) }
        return .ok(
            PlaybackCommandHeader(
                commandSeq: commandSeq, effectiveAtSessionUs: effectiveAt, issuedBy: issuedBy, queueRevision: queueRevision
            )
        )
    }

    // MARK: - Per-type parsers

    private static func parsePlay(_ payload: [String: JSONValue]) -> Result {
        let header: PlaybackCommandHeader
        switch parseHeader(payload) {
        case .bad(let rejected): return rejected
        case .ok(let parsed): header = parsed
        }
        guard let hashRaw = phase5StringField(payload, fieldTrackHash) else { return missingOrWrongType(payload, fieldTrackHash) }
        guard let trackHash = ContentHash.parse(hashRaw) else { return .rejected(.malformedContentHash) }
        guard let positionMs = phase5LongField(payload, fieldPositionMs) else { return missingOrWrongType(payload, fieldPositionMs) }
        guard isValidPosition(positionMs) else { return .rejected(.positionOutOfRange) }
        guard let queueItemId = phase5StringField(payload, fieldQueueItemId) else { return missingOrWrongType(payload, fieldQueueItemId) }
        guard phase5IsUlid(queueItemId) else { return .rejected(.malformedQueueItemId) }
        return .parsed(.play(header: header, trackHash: trackHash, positionMs: positionMs, queueItemId: queueItemId))
    }

    private static func parsePauseOrResume(_ payload: [String: JSONValue], resume: Bool) -> Result {
        let header: PlaybackCommandHeader
        switch parseHeader(payload) {
        case .bad(let rejected): return rejected
        case .ok(let parsed): header = parsed
        }
        guard let positionMs = phase5LongField(payload, fieldPositionMs) else { return missingOrWrongType(payload, fieldPositionMs) }
        guard isValidPosition(positionMs) else { return .rejected(.positionOutOfRange) }
        return .parsed(resume ? .resume(header: header, positionMs: positionMs) : .pause(header: header, positionMs: positionMs))
    }

    private static func parseSeek(_ payload: [String: JSONValue]) -> Result {
        let header: PlaybackCommandHeader
        switch parseHeader(payload) {
        case .bad(let rejected): return rejected
        case .ok(let parsed): header = parsed
        }
        guard let target = phase5LongField(payload, fieldTargetPositionMs) else {
            return missingOrWrongType(payload, fieldTargetPositionMs)
        }
        guard isValidPosition(target) else { return .rejected(.positionOutOfRange) }
        return .parsed(.seek(header: header, targetPositionMs: target))
    }

    private static func parseStep(_ payload: [String: JSONValue], next: Bool) -> Result {
        switch parseHeader(payload) {
        case .bad(let rejected): return rejected
        case .ok(let header): return .parsed(next ? .next(header: header) : .previous(header: header))
        }
    }

    private static func parsePositionReport(_ payload: [String: JSONValue]) -> Result {
        guard let hashRaw = phase5StringField(payload, fieldTrackHash) else { return missingOrWrongType(payload, fieldTrackHash) }
        guard let trackHash = ContentHash.parse(hashRaw) else { return .rejected(.malformedContentHash) }
        guard let positionMs = phase5LongField(payload, fieldPositionMs) else { return missingOrWrongType(payload, fieldPositionMs) }
        guard isValidPosition(positionMs) else { return .rejected(.positionOutOfRange) }
        guard let atSessionUs = phase5LongField(payload, fieldAtSessionUs) else { return missingOrWrongType(payload, fieldAtSessionUs) }
        guard atSessionUs >= 0, atSessionUs <= PlaybackBounds.maxWireInt else { return .rejected(.sessionTimeOutOfRange) }
        guard let playing = phase5BoolField(payload, fieldPlaying) else { return missingOrWrongType(payload, fieldPlaying) }
        guard let rate = phase5DoubleField(payload, fieldPlaybackRate) else { return missingOrWrongType(payload, fieldPlaybackRate) }
        guard rate >= PlaybackBounds.minPlaybackRate, rate <= PlaybackBounds.maxPlaybackRate else {
            return .rejected(.rateOutOfRange)
        }
        return .parsed(
            .positionReport(trackHash: trackHash, positionMs: positionMs, atSessionUs: atSessionUs, playing: playing, playbackRate: rate)
        )
    }

    private static func parsePlaybackState(_ payload: [String: JSONValue]) -> Result {
        guard let commandSeq = phase5LongField(payload, fieldCommandSeq) else { return missingOrWrongType(payload, fieldCommandSeq) }
        guard commandSeq >= 0, commandSeq <= PlaybackBounds.maxWireInt else { return .rejected(.commandSeqOutOfRange) }
        guard let queueRevision = phase5LongField(payload, fieldQueueRevision) else {
            return missingOrWrongType(payload, fieldQueueRevision)
        }
        guard queueRevision >= 0, queueRevision <= PlaybackBounds.maxWireInt else { return .rejected(.revisionOutOfRange) }
        var trackHash: ContentHash?
        switch phase5NullableStringField(payload, fieldTrackHash) {
        case .missing: return missingOrWrongType(payload, fieldTrackHash)
        case .explicitNull: trackHash = nil
        case .present(let raw):
            guard let parsed = ContentHash.parse(raw) else { return .rejected(.malformedContentHash) }
            trackHash = parsed
        }
        var queueItemId: String?
        switch phase5NullableStringField(payload, fieldQueueItemId) {
        case .missing: return missingOrWrongType(payload, fieldQueueItemId)
        case .explicitNull: queueItemId = nil
        case .present(let raw):
            guard phase5IsUlid(raw) else { return .rejected(.malformedQueueItemId) }
            queueItemId = raw
        }
        guard let positionMs = phase5LongField(payload, fieldPositionMs) else { return missingOrWrongType(payload, fieldPositionMs) }
        guard isValidPosition(positionMs) else { return .rejected(.positionOutOfRange) }
        guard let playing = phase5BoolField(payload, fieldPlaying) else { return missingOrWrongType(payload, fieldPlaying) }
        guard let atSessionUs = phase5LongField(payload, fieldAtSessionUs) else { return missingOrWrongType(payload, fieldAtSessionUs) }
        guard atSessionUs >= 0, atSessionUs <= PlaybackBounds.maxWireInt else { return .rejected(.sessionTimeOutOfRange) }
        return .parsed(
            .playbackState(
                commandSeq: commandSeq,
                queueRevision: queueRevision,
                trackHash: trackHash,
                queueItemId: queueItemId,
                positionMs: positionMs,
                playing: playing,
                atSessionUs: atSessionUs
            )
        )
    }

    private static func isValidPosition(_ positionMs: Int64) -> Bool {
        positionMs >= 0 && positionMs <= PlaybackBounds.maxPositionMs
    }

    private static func missingOrWrongType(_ payload: [String: JSONValue], _ key: String) -> Result {
        payload[key] != nil ? .rejected(.wrongFieldType) : .rejected(.missingField)
    }
}
