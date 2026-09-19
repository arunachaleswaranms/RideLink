import Foundation

/// PROTOCOL §3 Resync group (Phase 7).
public enum ResyncMessageTypes {
    public static let stateRequest = "STATE_REQUEST"
    public static let stateSnapshot = "STATE_SNAPSHOT"

    public static let all: Set<String> = [stateRequest, stateSnapshot]
}

/// Why a resync payload was refused. Recorded in diagnostics; never sent to the peer verbatim.
///
/// Raw values match the Kotlin enum constant names exactly, since `protocol/vectors/resync-messages/`
/// names them that way and both platforms read the same file.
public enum ResyncMessageRejection: String, Sendable, Equatable {
    case unknownType = "UNKNOWN_TYPE"
    case missingField = "MISSING_FIELD"
    case wrongFieldType = "WRONG_FIELD_TYPE"
    case malformedContentHash = "MALFORMED_CONTENT_HASH"
    case malformedQueueItemId = "MALFORMED_QUEUE_ITEM_ID"
    case malformedPeerId = "MALFORMED_PEER_ID"
    case malformedTransferId = "MALFORMED_TRANSFER_ID"
    case commandSeqOutOfRange = "COMMAND_SEQ_OUT_OF_RANGE"
    case revisionOutOfRange = "REVISION_OUT_OF_RANGE"
    case manifestRevisionOutOfRange = "MANIFEST_REVISION_OUT_OF_RANGE"
    case sessionTimeOutOfRange = "SESSION_TIME_OUT_OF_RANGE"
    case positionOutOfRange = "POSITION_OUT_OF_RANGE"
    case bytesDoneOutOfRange = "BYTES_DONE_OUT_OF_RANGE"
    case tooManyTransfers = "TOO_MANY_TRANSFERS"
    case malformedQueue = "MALFORMED_QUEUE"
    case malformedPlayback = "MALFORMED_PLAYBACK"
}

/// Parses, bounds-checks and encodes PROTOCOL §10's `STATE_REQUEST`/`STATE_SNAPSHOT`. Total and
/// non-trapping, mirroring `QueueCodec`/`PlaybackCodec`/`ManifestCodec`/`TransferCodec` — a
/// malformed frame is dropped and the control connection survives.
///
/// `STATE_SNAPSHOT.playback` and `.queue` are, by PROTOCOL §10's own words, "`QUEUE_SNAPSHOT`
/// shape" and (per §5's cross-reference) `PLAYBACK_STATE` minus its two ordering fields — so their
/// field validation reuses `QueueCodec.parse(type:payload:)` (its `parseSnapshot` is file-private,
/// so this goes through the public entry point rather than Android's public `parseSnapshotPayload`
/// — a platform access-level difference, not a behavioural one) and this file's own small playback
/// parser rather than a second, possibly-diverging copy of either (ADR-028).
///
/// Mirrors Android's `com.ridelink.core.protocol.ResyncCodec` exactly; both run
/// `protocol/vectors/resync-messages/`.
public enum ResyncCodec {
    public enum Result: Sendable, Equatable {
        case parsed(ResyncMessage)
        case rejected(ResyncMessageRejection)
    }

    public static let fieldLeaderPeerId = "leader_peer_id"
    public static let fieldCommandSeq = "command_seq"
    public static let fieldQueueRevision = "queue_revision"
    public static let fieldPlayback = "playback"
    public static let fieldQueue = "queue"
    public static let fieldManifestRevision = "manifest_revision"
    public static let fieldTransfersInFlight = "transfers_in_flight"

    public static let fieldTrackHash = "track_hash"
    public static let fieldQueueItemId = "queue_item_id"
    public static let fieldPositionMs = "position_ms"
    public static let fieldPlaying = "playing"
    public static let fieldAtSessionUs = "at_session_us"

    public static let fieldTransferId = "transfer_id"
    public static let fieldContentHash = "content_hash"
    public static let fieldBytesDone = "bytes_done"

    public static func parse(type: String, payload: [String: JSONValue]) -> Result {
        switch type {
        case ResyncMessageTypes.stateRequest: return .parsed(.stateRequest)
        case ResyncMessageTypes.stateSnapshot: return parseStateSnapshot(payload)
        default: return .rejected(.unknownType)
        }
    }

    public static func wireType(_ message: ResyncMessage) -> String {
        switch message {
        case .stateRequest: return ResyncMessageTypes.stateRequest
        case .stateSnapshot: return ResyncMessageTypes.stateSnapshot
        }
    }

    public static func encode(_ message: ResyncMessage) -> [String: JSONValue] {
        switch message {
        case .stateRequest:
            return [:]
        case .stateSnapshot(
            let leaderPeerId, let commandSeq, let queueRevision, let playback,
            let queueItems, let queueCurrentIndex, let manifestRevision, let transfersInFlight
        ):
            return [
                fieldLeaderPeerId: .string(leaderPeerId.value),
                fieldCommandSeq: .number(Double(commandSeq)),
                fieldQueueRevision: .number(Double(queueRevision)),
                fieldPlayback: playback.map(encodePlayback) ?? .null,
                fieldQueue: .object(QueueCodec.encode(.snapshot(
                    queueRevision: queueRevision, items: queueItems, currentIndex: queueCurrentIndex
                ))),
                fieldManifestRevision: .number(Double(manifestRevision)),
                fieldTransfersInFlight: .array(transfersInFlight.map { transfer in
                    .object([
                        fieldTransferId: .string(transfer.transferId.value),
                        fieldContentHash: .string(transfer.contentHash.value),
                        fieldBytesDone: .number(Double(transfer.bytesDone)),
                    ])
                }),
            ]
        }
    }

    private static func encodePlayback(_ playback: ResyncPlaybackSnapshot) -> JSONValue {
        .object([
            fieldTrackHash: playback.trackHash.map { JSONValue.string($0.value) } ?? .null,
            fieldQueueItemId: playback.queueItemId.map { JSONValue.string($0) } ?? .null,
            fieldPositionMs: .number(Double(playback.positionMs)),
            fieldPlaying: .bool(playback.playing),
            fieldAtSessionUs: .number(Double(playback.atSessionUs)),
        ])
    }

    private static func parseStateSnapshot(_ payload: [String: JSONValue]) -> Result {
        guard let leaderPeerIdRaw = phase5StringField(payload, fieldLeaderPeerId) else {
            return missingOrWrongType(payload, fieldLeaderPeerId)
        }
        guard let leaderPeerId = phase5ParsePeerId(leaderPeerIdRaw) else {
            return .rejected(.malformedPeerId)
        }
        guard let commandSeq = phase5LongField(payload, fieldCommandSeq) else {
            return missingOrWrongType(payload, fieldCommandSeq)
        }
        guard commandSeq >= 0, commandSeq <= PlaybackBounds.maxWireInt else {
            return .rejected(.commandSeqOutOfRange)
        }
        guard let queueRevision = phase5LongField(payload, fieldQueueRevision) else {
            return missingOrWrongType(payload, fieldQueueRevision)
        }
        guard queueRevision >= 0, queueRevision <= PlaybackBounds.maxWireInt else {
            return .rejected(.revisionOutOfRange)
        }
        let playback: ResyncPlaybackSnapshot?
        switch payload[fieldPlayback] {
        case .none: return .rejected(.missingField)
        case .null: playback = nil
        case .object(let entry)?:
            switch parsePlaybackField(entry) {
            case .bad(let rejected): return rejected
            case .ok(let value): playback = value
            }
        default: return .rejected(.malformedPlayback)
        }
        guard case .object(let queuePayload)? = payload[fieldQueue] else {
            return missingOrWrongType(payload, fieldQueue)
        }
        let queueItems: [SharedQueueItem]
        let queueCurrentIndex: Int?
        switch QueueCodec.parse(type: QueueMessageTypes.snapshot, payload: queuePayload) {
        case .rejected: return .rejected(.malformedQueue)
        case .parsed(.snapshot(_, let items, let currentIndex)):
            queueItems = items
            queueCurrentIndex = currentIndex
        case .parsed:
            return .rejected(.malformedQueue)
        }
        guard let manifestRevision = phase5LongField(payload, fieldManifestRevision) else {
            return missingOrWrongType(payload, fieldManifestRevision)
        }
        guard manifestRevision >= 0, manifestRevision <= PlaybackBounds.maxWireInt else {
            return .rejected(.manifestRevisionOutOfRange)
        }
        guard case .array(let transfersRaw)? = payload[fieldTransfersInFlight] else {
            return missingOrWrongType(payload, fieldTransfersInFlight)
        }
        if transfersRaw.count > ResyncBounds.maxTransfersInFlight { return .rejected(.tooManyTransfers) }
        var transfers: [ResyncTransferInFlight] = []
        transfers.reserveCapacity(transfersRaw.count)
        for element in transfersRaw {
            guard case .object(let item) = element else { return .rejected(.wrongFieldType) }
            switch parseTransferInFlight(item) {
            case .bad(let rejected): return rejected
            case .ok(let transfer): transfers.append(transfer)
            }
        }
        return .parsed(.stateSnapshot(
            leaderPeerId: leaderPeerId,
            commandSeq: commandSeq,
            queueRevision: queueRevision,
            playback: playback,
            queueItems: queueItems,
            queueCurrentIndex: queueCurrentIndex,
            manifestRevision: manifestRevision,
            transfersInFlight: transfers
        ))
    }

    private enum PlaybackFieldResult {
        case ok(ResyncPlaybackSnapshot)
        case bad(Result)
    }

    private static func parsePlaybackField(_ payload: [String: JSONValue]) -> PlaybackFieldResult {
        let trackHash: ContentHash?
        switch phase5NullableStringField(payload, fieldTrackHash) {
        case .missing: return .bad(missingOrWrongType(payload, fieldTrackHash))
        case .explicitNull: trackHash = nil
        case .present(let raw):
            guard let hash = ContentHash.parse(raw) else { return .bad(.rejected(.malformedContentHash)) }
            trackHash = hash
        }
        let queueItemId: String?
        switch phase5NullableStringField(payload, fieldQueueItemId) {
        case .missing: return .bad(missingOrWrongType(payload, fieldQueueItemId))
        case .explicitNull: queueItemId = nil
        case .present(let raw):
            guard phase5IsUlid(raw) else { return .bad(.rejected(.malformedQueueItemId)) }
            queueItemId = raw
        }
        guard let positionMs = phase5LongField(payload, fieldPositionMs) else {
            return .bad(missingOrWrongType(payload, fieldPositionMs))
        }
        guard positionMs >= 0, positionMs <= PlaybackBounds.maxPositionMs else {
            return .bad(.rejected(.positionOutOfRange))
        }
        guard let playing = phase5BoolField(payload, fieldPlaying) else {
            return .bad(missingOrWrongType(payload, fieldPlaying))
        }
        guard let atSessionUs = phase5LongField(payload, fieldAtSessionUs) else {
            return .bad(missingOrWrongType(payload, fieldAtSessionUs))
        }
        guard atSessionUs >= 0, atSessionUs <= PlaybackBounds.maxWireInt else {
            return .bad(.rejected(.sessionTimeOutOfRange))
        }
        return .ok(ResyncPlaybackSnapshot(
            trackHash: trackHash, queueItemId: queueItemId, positionMs: positionMs, playing: playing, atSessionUs: atSessionUs
        ))
    }

    private enum TransferFieldResult {
        case ok(ResyncTransferInFlight)
        case bad(Result)
    }

    private static func parseTransferInFlight(_ payload: [String: JSONValue]) -> TransferFieldResult {
        guard let transferIdRaw = phase5StringField(payload, fieldTransferId) else {
            return .bad(missingOrWrongType(payload, fieldTransferId))
        }
        guard let transferId = TransferId.parse(transferIdRaw) else {
            return .bad(.rejected(.malformedTransferId))
        }
        guard let hashRaw = phase5StringField(payload, fieldContentHash) else {
            return .bad(missingOrWrongType(payload, fieldContentHash))
        }
        guard let contentHash = ContentHash.parse(hashRaw) else {
            return .bad(.rejected(.malformedContentHash))
        }
        guard let bytesDone = phase5LongField(payload, fieldBytesDone) else {
            return .bad(missingOrWrongType(payload, fieldBytesDone))
        }
        guard bytesDone >= 0, bytesDone <= PlaybackBounds.maxWireInt else {
            return .bad(.rejected(.bytesDoneOutOfRange))
        }
        return .ok(ResyncTransferInFlight(transferId: transferId, contentHash: contentHash, bytesDone: bytesDone))
    }

    private static func missingOrWrongType(_ payload: [String: JSONValue], _ key: String) -> Result {
        payload[key] != nil ? .rejected(.wrongFieldType) : .rejected(.missingField)
    }
}
