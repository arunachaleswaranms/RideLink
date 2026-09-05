import Foundation

/// PROTOCOL §3 Transfer group.
public enum TransferMessageTypes {
    public static let request = "TRANSFER_REQUEST"
    public static let offer = "TRANSFER_OFFER"
    public static let progress = "TRANSFER_PROGRESS"
    public static let result = "TRANSFER_RESULT"
    public static let cancel = "TRANSFER_CANCEL"

    public static let all: Set<String> = [request, offer, progress, result, cancel]
}

/// PROTOCOL §8.2 bounds, fixed in V1 (ADR-023).
public enum TransferBounds {
    public static let chunkSize = 65_536
    public static let maxTransferSizeBytes: Int64 = 2_147_483_648 // 2 GiB defensive cap
    public static let validCancelReasons: Set<String> = ["user_cancelled", "disconnected", "superseded", "error"]
}

/// One decoded, bounds-checked `TRANSFER_*` message (PROTOCOL §8.2).
public enum TransferMessage: Sendable, Equatable {
    case request(contentHash: ContentHash, transferId: TransferId)
    case offer(transferId: TransferId, sizeBytes: Int64, chunkSize: Int, chunkCount: Int, bulkPort: Int, bulkToken: String)
    case progress(transferId: TransferId, bytes: Int64, pct: Int)
    case result(transferId: TransferId, ok: Bool, sha256: ContentHash?)
    /// A closed vocabulary tolerated as `"unknown"` for a value this build does not recognise.
    case cancel(transferId: TransferId, reason: String)
}

/// Why a `TRANSFER_*` payload was refused. Recorded in diagnostics; never sent to the peer verbatim.
///
/// Raw values match the Kotlin enum constant names exactly, since `protocol/vectors/
/// transfer-messages/` names them that way and both platforms read the same file.
public enum TransferMessageRejection: String, Sendable, Equatable {
    case unknownType = "UNKNOWN_TYPE"
    case missingField = "MISSING_FIELD"
    case wrongFieldType = "WRONG_FIELD_TYPE"
    case malformedContentHash = "MALFORMED_CONTENT_HASH"
    case malformedTransferId = "MALFORMED_TRANSFER_ID"
    case invalidSize = "INVALID_SIZE"
    case sizeTooLarge = "SIZE_TOO_LARGE"
    case unsupportedChunkSize = "UNSUPPORTED_CHUNK_SIZE"
    case chunkCountMismatch = "CHUNK_COUNT_MISMATCH"
    case portOutOfRange = "PORT_OUT_OF_RANGE"
    case malformedBulkToken = "MALFORMED_BULK_TOKEN"
    case progressOutOfRange = "PROGRESS_OUT_OF_RANGE"
}

/// Parses and bounds-checks a `TRANSFER_*` payload. Total and non-trapping, mirroring
/// `AudioStateCodec`/`VoiceSignalCodec` — a malformed frame is dropped and the control connection
/// survives.
///
/// The Kotlin mirror is `com.ridelink.core.protocol.TransferCodec`, and both run
/// `protocol/vectors/transfer-messages/`.
public enum TransferCodec {
    public enum Result: Sendable, Equatable {
        case parsed(TransferMessage)
        case rejected(TransferMessageRejection)
    }

    public static let fieldContentHash = "content_hash"
    public static let fieldTransferId = "transfer_id"
    public static let fieldSizeBytes = "size_bytes"
    public static let fieldChunkSize = "chunk_size"
    public static let fieldChunkCount = "chunk_count"
    public static let fieldBulkPort = "bulk_port"
    public static let fieldBulkToken = "bulk_token"
    public static let fieldBytes = "bytes"
    public static let fieldPct = "pct"
    public static let fieldOk = "ok"
    public static let fieldSha256 = "sha256"
    public static let fieldReason = "reason"

    private static let minPort = 1
    private static let maxPort = 65_535
    private static let minPct = 0
    private static let maxPct = 100

    public static func parse(type: String, payload: [String: JSONValue]) -> Result {
        switch type {
        case TransferMessageTypes.request: return parseRequest(payload)
        case TransferMessageTypes.offer: return parseOffer(payload)
        case TransferMessageTypes.progress: return parseProgress(payload)
        case TransferMessageTypes.result: return parseResult(payload)
        case TransferMessageTypes.cancel: return parseCancel(payload)
        default: return .rejected(.unknownType)
        }
    }

    /// The `TRANSFER_*` type a given `TransferMessage` wire-encodes as.
    public static func wireType(_ message: TransferMessage) -> String {
        switch message {
        case .request: return TransferMessageTypes.request
        case .offer: return TransferMessageTypes.offer
        case .progress: return TransferMessageTypes.progress
        case .result: return TransferMessageTypes.result
        case .cancel: return TransferMessageTypes.cancel
        }
    }

    /// The outbound side of `parse` — the shape lives here, once, shared by both directions.
    public static func encode(_ message: TransferMessage) -> [String: JSONValue] {
        switch message {
        case .request(let contentHash, let transferId):
            return [
                fieldContentHash: .string(contentHash.value),
                fieldTransferId: .string(transferId.value),
            ]
        case .offer(let transferId, let sizeBytes, let chunkSize, let chunkCount, let bulkPort, let bulkToken):
            return [
                fieldTransferId: .string(transferId.value),
                fieldSizeBytes: .number(Double(sizeBytes)),
                fieldChunkSize: .number(Double(chunkSize)),
                fieldChunkCount: .number(Double(chunkCount)),
                fieldBulkPort: .number(Double(bulkPort)),
                fieldBulkToken: .string(bulkToken),
            ]
        case .progress(let transferId, let bytes, let pct):
            return [
                fieldTransferId: .string(transferId.value),
                fieldBytes: .number(Double(bytes)),
                fieldPct: .number(Double(pct)),
            ]
        case .result(let transferId, let ok, let sha256):
            return [
                fieldTransferId: .string(transferId.value),
                fieldOk: .bool(ok),
                fieldSha256: sha256.map { JSONValue.string($0.value) } ?? .null,
            ]
        case .cancel(let transferId, let reason):
            return [
                fieldTransferId: .string(transferId.value),
                fieldReason: .string(reason),
            ]
        }
    }

    private static func parseRequest(_ payload: [String: JSONValue]) -> Result {
        guard let contentHash = requiredContentHash(payload, fieldContentHash) else {
            return rejectContentHash(payload, fieldContentHash)
        }
        guard let transferId = requiredTransferId(payload, fieldTransferId) else {
            return rejectTransferId(payload, fieldTransferId)
        }
        return .parsed(.request(contentHash: contentHash, transferId: transferId))
    }

    // swiftlint:disable:next function_body_length
    private static func parseOffer(_ payload: [String: JSONValue]) -> Result {
        guard let transferId = requiredTransferId(payload, fieldTransferId) else {
            return rejectTransferId(payload, fieldTransferId)
        }
        guard let sizeBytes = transferLongField(payload, fieldSizeBytes) else { return missingOrWrongType(payload, fieldSizeBytes) }
        if sizeBytes <= 0 { return .rejected(.invalidSize) }
        if sizeBytes > TransferBounds.maxTransferSizeBytes { return .rejected(.sizeTooLarge) }
        guard let chunkSize = transferIntField(payload, fieldChunkSize) else { return missingOrWrongType(payload, fieldChunkSize) }
        if chunkSize != TransferBounds.chunkSize { return .rejected(.unsupportedChunkSize) }
        guard let chunkCount = transferIntField(payload, fieldChunkCount) else { return missingOrWrongType(payload, fieldChunkCount) }
        let expectedChunkCount = (sizeBytes + Int64(chunkSize) - 1) / Int64(chunkSize)
        if Int64(chunkCount) != expectedChunkCount { return .rejected(.chunkCountMismatch) }
        guard let bulkPort = transferIntField(payload, fieldBulkPort) else { return missingOrWrongType(payload, fieldBulkPort) }
        if bulkPort < minPort || bulkPort > maxPort { return .rejected(.portOutOfRange) }
        guard let bulkToken = transferStringField(payload, fieldBulkToken) else { return missingOrWrongType(payload, fieldBulkToken) }
        guard matchesBulkTokenFormat(bulkToken) else { return .rejected(.malformedBulkToken) }
        return .parsed(
            .offer(
                transferId: transferId,
                sizeBytes: sizeBytes,
                chunkSize: chunkSize,
                chunkCount: chunkCount,
                bulkPort: bulkPort,
                bulkToken: bulkToken
            )
        )
    }

    private static func parseProgress(_ payload: [String: JSONValue]) -> Result {
        guard let transferId = requiredTransferId(payload, fieldTransferId) else {
            return rejectTransferId(payload, fieldTransferId)
        }
        guard let bytes = transferLongField(payload, fieldBytes) else { return missingOrWrongType(payload, fieldBytes) }
        if bytes < 0 { return .rejected(.invalidSize) }
        guard let pct = transferIntField(payload, fieldPct) else { return missingOrWrongType(payload, fieldPct) }
        if pct < minPct || pct > maxPct { return .rejected(.progressOutOfRange) }
        return .parsed(.progress(transferId: transferId, bytes: bytes, pct: pct))
    }

    private static func parseResult(_ payload: [String: JSONValue]) -> Result {
        guard let transferId = requiredTransferId(payload, fieldTransferId) else {
            return rejectTransferId(payload, fieldTransferId)
        }
        guard let ok = transferBoolField(payload, fieldOk) else { return missingOrWrongType(payload, fieldOk) }
        var sha256: ContentHash?
        if let entry = payload[fieldSha256], entry != .null {
            guard case .string(let s) = entry else { return .rejected(.wrongFieldType) }
            guard let parsed = ContentHash.parse(s) else { return .rejected(.malformedContentHash) }
            sha256 = parsed
        } else if ok {
            return .rejected(.missingField)
        }
        return .parsed(.result(transferId: transferId, ok: ok, sha256: sha256))
    }

    private static func parseCancel(_ payload: [String: JSONValue]) -> Result {
        guard let transferId = requiredTransferId(payload, fieldTransferId) else {
            return rejectTransferId(payload, fieldTransferId)
        }
        guard let reasonRaw = transferStringField(payload, fieldReason) else { return missingOrWrongType(payload, fieldReason) }
        let reason = TransferBounds.validCancelReasons.contains(reasonRaw) ? reasonRaw : "unknown"
        return .parsed(.cancel(transferId: transferId, reason: reason))
    }

    private static func requiredContentHash(_ payload: [String: JSONValue], _ key: String) -> ContentHash? {
        transferStringField(payload, key).flatMap(ContentHash.parse)
    }

    private static func rejectContentHash(_ payload: [String: JSONValue], _ key: String) -> Result {
        transferStringField(payload, key) != nil ? .rejected(.malformedContentHash) : missingOrWrongType(payload, key)
    }

    private static func requiredTransferId(_ payload: [String: JSONValue], _ key: String) -> TransferId? {
        transferStringField(payload, key).flatMap(TransferId.parse)
    }

    private static func rejectTransferId(_ payload: [String: JSONValue], _ key: String) -> Result {
        transferStringField(payload, key) != nil ? .rejected(.malformedTransferId) : missingOrWrongType(payload, key)
    }

    private static func missingOrWrongType(_ payload: [String: JSONValue], _ key: String) -> Result {
        payload[key] != nil ? .rejected(.wrongFieldType) : .rejected(.missingField)
    }

    private static func matchesBulkTokenFormat(_ s: String) -> Bool {
        s.count == 64 && s.allSatisfy { $0.isHexDigit && !$0.isUppercase }
    }
}

// The field readers below are file-private free functions with distinct names for the same reason
// `VoiceSignalCodec`'s/`AudioStateCodec`'s are — see `ManifestCodec`'s identical comment.

private func transferStringField(_ payload: [String: JSONValue], _ key: String) -> String? {
    if case .string(let value)? = payload[key] { return value }
    return nil
}

private func transferLongField(_ payload: [String: JSONValue], _ key: String) -> Int64? {
    guard case .number(let value)? = payload[key] else { return nil }
    return Int64(exactly: value)
}

private func transferIntField(_ payload: [String: JSONValue], _ key: String) -> Int? {
    guard case .number(let value)? = payload[key] else { return nil }
    return Int(exactly: value)
}

private func transferBoolField(_ payload: [String: JSONValue], _ key: String) -> Bool? {
    if case .bool(let value)? = payload[key] { return value }
    return nil
}
