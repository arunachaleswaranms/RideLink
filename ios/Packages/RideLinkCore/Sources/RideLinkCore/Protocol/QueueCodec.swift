import Foundation

/// PROTOCOL §3 Queue group (Phase 5).
public enum QueueMessageTypes {
    public static let add = "QUEUE_ADD"
    public static let remove = "QUEUE_REMOVE"
    public static let move = "QUEUE_MOVE"
    public static let snapshot = "QUEUE_SNAPSHOT"

    public static let all: Set<String> = [add, remove, move, snapshot]

    /// The mutations, which carry a `QueueCommandHeader`. `QUEUE_SNAPSHOT` is state, not a mutation.
    public static let mutations: Set<String> = [add, remove, move]
}

/// Why a `QUEUE_*` payload was refused. Recorded in diagnostics; never sent to the peer verbatim.
///
/// Raw values match the Kotlin enum constant names exactly, since `protocol/vectors/queue-messages/`
/// names them that way and both platforms read the same file.
public enum QueueMessageRejection: String, Sendable, Equatable {
    case unknownType = "UNKNOWN_TYPE"
    case missingField = "MISSING_FIELD"
    case wrongFieldType = "WRONG_FIELD_TYPE"
    case malformedContentHash = "MALFORMED_CONTENT_HASH"
    case malformedQueueItemId = "MALFORMED_QUEUE_ITEM_ID"
    case malformedPeerId = "MALFORMED_PEER_ID"
    case commandSeqOutOfRange = "COMMAND_SEQ_OUT_OF_RANGE"
    case revisionOutOfRange = "REVISION_OUT_OF_RANGE"
    case orderOutOfRange = "ORDER_OUT_OF_RANGE"
    case emptyItemList = "EMPTY_ITEM_LIST"
    case tooManyItems = "TOO_MANY_ITEMS"
    case duplicateQueueItemId = "DUPLICATE_QUEUE_ITEM_ID"
    case indexOutOfRange = "INDEX_OUT_OF_RANGE"
}

/// Parses, bounds-checks and encodes PROTOCOL §9's queue-replication messages. Total and
/// non-trapping, mirroring `PlaybackCodec` and every codec before it.
///
/// Mirrors Android `core.protocol.QueueCodec`; both run `protocol/vectors/queue-messages/`.
///
/// **`status` is deliberately absent from the wire shape.** PROTOCOL §9 listed it on
/// `QUEUE_SNAPSHOT` while stating in the same paragraph that it is "derived locally from presence,
/// never trusted from the peer" — a field that must never be trusted has no reason to be sent, and
/// sending it hands a peer a channel to influence what the local UI claims about local storage.
/// ADR-024 §6 removes it; availability comes from `RideLinkCore.Availability`, as it always did.
public enum QueueCodec {
    public enum Result: Sendable, Equatable {
        case parsed(QueueMessage)
        case rejected(QueueMessageRejection)
    }

    public static let fieldCommandSeq = "command_seq"
    public static let fieldQueueRevision = "queue_revision"
    public static let fieldItems = "items"
    public static let fieldQueueItemId = "queue_item_id"
    public static let fieldQueueItemIds = "queue_item_ids"
    public static let fieldTrackHash = "track_hash"
    public static let fieldAddedBy = "added_by"
    public static let fieldPosition = "position"
    public static let fieldOrder = "order"
    public static let fieldToIndex = "to_index"
    public static let fieldCurrentIndex = "current_index"

    public static func parse(type: String, payload: [String: JSONValue]) -> Result {
        switch type {
        case QueueMessageTypes.add: return parseAdd(payload)
        case QueueMessageTypes.remove: return parseRemove(payload)
        case QueueMessageTypes.move: return parseMove(payload)
        case QueueMessageTypes.snapshot: return parseSnapshot(payload)
        default: return .rejected(.unknownType)
        }
    }

    public static func wireType(_ message: QueueMessage) -> String {
        switch message {
        case .add: return QueueMessageTypes.add
        case .remove: return QueueMessageTypes.remove
        case .move: return QueueMessageTypes.move
        case .snapshot: return QueueMessageTypes.snapshot
        }
    }

    public static func encode(_ message: QueueMessage) -> [String: JSONValue] {
        switch message {
        case .add(let header, let items):
            return [
                fieldCommandSeq: .number(Double(header.commandSeq)),
                fieldQueueRevision: .number(Double(header.queueRevision)),
                fieldItems: .array(items.map { item in
                    .object([
                        fieldQueueItemId: .string(item.queueItemId),
                        fieldTrackHash: .string(item.trackHash.value),
                        fieldAddedBy: .string(item.addedBy.value),
                        fieldPosition: .string(item.position),
                    ])
                }),
            ]
        case .remove(let header, let queueItemIds):
            return [
                fieldCommandSeq: .number(Double(header.commandSeq)),
                fieldQueueRevision: .number(Double(header.queueRevision)),
                fieldQueueItemIds: .array(queueItemIds.map { .string($0) }),
            ]
        case .move(let header, let queueItemId, let toIndex):
            return [
                fieldCommandSeq: .number(Double(header.commandSeq)),
                fieldQueueRevision: .number(Double(header.queueRevision)),
                fieldQueueItemId: .string(queueItemId),
                fieldToIndex: .number(Double(toIndex)),
            ]
        case .snapshot(let queueRevision, let items, let currentIndex):
            return [
                fieldQueueRevision: .number(Double(queueRevision)),
                fieldItems: .array(items.map { item in
                    .object([
                        fieldQueueItemId: .string(item.queueItemId),
                        fieldTrackHash: .string(item.trackHash.value),
                        fieldAddedBy: .string(item.addedBy.value),
                        fieldOrder: .number(Double(item.order)),
                    ])
                }),
                fieldCurrentIndex: currentIndex.map { JSONValue.number(Double($0)) } ?? .null,
            ]
        }
    }

    private enum HeaderResult {
        case ok(QueueCommandHeader)
        case bad(Result)
    }

    private static func parseHeader(_ payload: [String: JSONValue]) -> HeaderResult {
        guard let commandSeq = phase5LongField(payload, fieldCommandSeq) else {
            return .bad(missingOrWrongType(payload, fieldCommandSeq))
        }
        if commandSeq < 0 || commandSeq > PlaybackBounds.maxWireInt { return .bad(.rejected(.commandSeqOutOfRange)) }
        guard let queueRevision = phase5LongField(payload, fieldQueueRevision) else {
            return .bad(missingOrWrongType(payload, fieldQueueRevision))
        }
        if queueRevision < 0 || queueRevision > PlaybackBounds.maxWireInt { return .bad(.rejected(.revisionOutOfRange)) }
        return .ok(QueueCommandHeader(commandSeq: commandSeq, queueRevision: queueRevision))
    }

    private static func parseAdd(_ payload: [String: JSONValue]) -> Result {
        let header: QueueCommandHeader
        switch parseHeader(payload) {
        case .bad(let rejected): return rejected
        case .ok(let parsed): header = parsed
        }
        guard case .array(let rawItems)? = payload[fieldItems] else { return missingOrWrongType(payload, fieldItems) }
        if rawItems.isEmpty { return .rejected(.emptyItemList) }
        if rawItems.count > PlaybackBounds.maxQueueItems { return .rejected(.tooManyItems) }
        var items: [QueueAddItem] = []
        var seen = Set<String>()
        for element in rawItems {
            guard case .object(let item) = element else { return .rejected(.wrongFieldType) }
            let parsed: QueueAddItem
            switch parseAddItem(item) {
            case .bad(let rejected): return rejected
            case .ok(let value): parsed = value
            }
            if !seen.insert(parsed.queueItemId).inserted { return .rejected(.duplicateQueueItemId) }
            items.append(parsed)
        }
        return .parsed(.add(header: header, items: items))
    }

    private enum AddItemResult {
        case ok(QueueAddItem)
        case bad(Result)
    }

    private static func parseAddItem(_ item: [String: JSONValue]) -> AddItemResult {
        guard let queueItemId = phase5StringField(item, fieldQueueItemId) else {
            return .bad(missingOrWrongType(item, fieldQueueItemId))
        }
        guard phase5IsUlid(queueItemId) else { return .bad(.rejected(.malformedQueueItemId)) }
        guard let hashRaw = phase5StringField(item, fieldTrackHash) else { return .bad(missingOrWrongType(item, fieldTrackHash)) }
        guard let trackHash = ContentHash.parse(hashRaw) else { return .bad(.rejected(.malformedContentHash)) }
        guard let addedByRaw = phase5StringField(item, fieldAddedBy) else { return .bad(missingOrWrongType(item, fieldAddedBy)) }
        guard let addedBy = phase5ParsePeerId(addedByRaw) else { return .bad(.rejected(.malformedPeerId)) }
        guard let positionRaw = phase5StringField(item, fieldPosition) else { return .bad(missingOrWrongType(item, fieldPosition)) }
        // PROTOCOL §2 rule 2's forward-compatibility posture applied to a value rather than a type:
        // an unrecognised position degrades to `end` rather than rejecting the whole frame.
        let position = PlaybackBounds.validQueuePositions.contains(positionRaw) ? positionRaw : PlaybackBounds.queuePositionEnd
        return .ok(QueueAddItem(queueItemId: queueItemId, trackHash: trackHash, addedBy: addedBy, position: position))
    }

    private static func parseRemove(_ payload: [String: JSONValue]) -> Result {
        let header: QueueCommandHeader
        switch parseHeader(payload) {
        case .bad(let rejected): return rejected
        case .ok(let parsed): header = parsed
        }
        guard case .array(let rawIds)? = payload[fieldQueueItemIds] else { return missingOrWrongType(payload, fieldQueueItemIds) }
        if rawIds.isEmpty { return .rejected(.emptyItemList) }
        if rawIds.count > PlaybackBounds.maxQueueItems { return .rejected(.tooManyItems) }
        var ids: [String] = []
        for element in rawIds {
            guard case .string(let value) = element else { return .rejected(.wrongFieldType) }
            guard phase5IsUlid(value) else { return .rejected(.malformedQueueItemId) }
            ids.append(value)
        }
        return .parsed(.remove(header: header, queueItemIds: ids))
    }

    private static func parseMove(_ payload: [String: JSONValue]) -> Result {
        let header: QueueCommandHeader
        switch parseHeader(payload) {
        case .bad(let rejected): return rejected
        case .ok(let parsed): header = parsed
        }
        guard let queueItemId = phase5StringField(payload, fieldQueueItemId) else {
            return missingOrWrongType(payload, fieldQueueItemId)
        }
        guard phase5IsUlid(queueItemId) else { return .rejected(.malformedQueueItemId) }
        guard let toIndex = phase5IntField(payload, fieldToIndex) else { return missingOrWrongType(payload, fieldToIndex) }
        guard toIndex >= 0, toIndex < PlaybackBounds.maxQueueItems else { return .rejected(.indexOutOfRange) }
        return .parsed(.move(header: header, queueItemId: queueItemId, toIndex: toIndex))
    }

    private static func parseSnapshot(_ payload: [String: JSONValue]) -> Result {
        guard let queueRevision = phase5LongField(payload, fieldQueueRevision) else {
            return missingOrWrongType(payload, fieldQueueRevision)
        }
        guard queueRevision >= 0, queueRevision <= PlaybackBounds.maxWireInt else { return .rejected(.revisionOutOfRange) }
        guard case .array(let rawItems)? = payload[fieldItems] else { return missingOrWrongType(payload, fieldItems) }
        // An empty snapshot is legitimate and must be representable: it is what "the other user
        // cleared the queue" looks like on the wire.
        if rawItems.count > PlaybackBounds.maxQueueItems { return .rejected(.tooManyItems) }
        var items: [SharedQueueItem] = []
        var seen = Set<String>()
        for element in rawItems {
            guard case .object(let item) = element else { return .rejected(.wrongFieldType) }
            let parsed: SharedQueueItem
            switch parseSnapshotItem(item) {
            case .bad(let rejected): return rejected
            case .ok(let value): parsed = value
            }
            if !seen.insert(parsed.queueItemId).inserted { return .rejected(.duplicateQueueItemId) }
            items.append(parsed)
        }
        // `current_index` is required but nullable: absent is a malformed frame, explicit null is the
        // representable "nothing selected" state, and anything else must be an in-range integer.
        guard let currentIndexEntry = payload[fieldCurrentIndex] else { return .rejected(.missingField) }
        var currentIndex: Int?
        if case .null = currentIndexEntry {
            currentIndex = nil
        } else {
            guard let value = phase5IntField(payload, fieldCurrentIndex) else { return .rejected(.wrongFieldType) }
            guard value >= 0, value < items.count else { return .rejected(.indexOutOfRange) }
            currentIndex = value
        }
        return .parsed(.snapshot(queueRevision: queueRevision, items: items, currentIndex: currentIndex))
    }

    private enum SnapshotItemResult {
        case ok(SharedQueueItem)
        case bad(Result)
    }

    private static func parseSnapshotItem(_ item: [String: JSONValue]) -> SnapshotItemResult {
        guard let queueItemId = phase5StringField(item, fieldQueueItemId) else {
            return .bad(missingOrWrongType(item, fieldQueueItemId))
        }
        guard phase5IsUlid(queueItemId) else { return .bad(.rejected(.malformedQueueItemId)) }
        guard let hashRaw = phase5StringField(item, fieldTrackHash) else { return .bad(missingOrWrongType(item, fieldTrackHash)) }
        guard let trackHash = ContentHash.parse(hashRaw) else { return .bad(.rejected(.malformedContentHash)) }
        guard let addedByRaw = phase5StringField(item, fieldAddedBy) else { return .bad(missingOrWrongType(item, fieldAddedBy)) }
        guard let addedBy = phase5ParsePeerId(addedByRaw) else { return .bad(.rejected(.malformedPeerId)) }
        guard let order = phase5LongField(item, fieldOrder) else { return .bad(missingOrWrongType(item, fieldOrder)) }
        guard order >= 0, order <= PlaybackBounds.maxWireInt else { return .bad(.rejected(.orderOutOfRange)) }
        return .ok(SharedQueueItem(queueItemId: queueItemId, trackHash: trackHash, addedBy: addedBy, order: order))
    }

    private static func missingOrWrongType(_ payload: [String: JSONValue], _ key: String) -> Result {
        payload[key] != nil ? .rejected(.wrongFieldType) : .rejected(.missingField)
    }
}
