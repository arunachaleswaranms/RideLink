import Foundation

/// PROTOCOL §3 Catalogue group.
public enum ManifestMessageTypes {
    public static let request = "MANIFEST_REQUEST"
    public static let begin = "MANIFEST_BEGIN"
    public static let page = "MANIFEST_PAGE"
    public static let end = "MANIFEST_END"
    public static let abort = "MANIFEST_ABORT"

    public static let all: Set<String> = [request, begin, page, end, abort]
}

/// One decoded, bounds-checked `MANIFEST_*` message (PROTOCOL §8.1).
public enum ManifestMessage: Sendable, Equatable {
    case request(sinceRevision: Int64?, maxPageBytes: Int)
    case begin(
        manifestId: ManifestId,
        kind: ManifestKind,
        manifestRevision: Int64,
        baseRevision: Int64?,
        totalEntries: Int,
        totalRemoved: Int,
        pageCount: Int?,
        digestAlg: String
    )
    case page(manifestId: ManifestId, manifestRevision: Int64, pageIndex: Int, entries: [ManifestEntry], removed: [ContentHash])
    case end(manifestId: ManifestId, manifestRevision: Int64, pageCount: Int, totalEntries: Int, totalRemoved: Int, digest: String)
    /// A closed vocabulary tolerated as `"unknown"` for a value this build does not recognise (PROTOCOL §8.1).
    case abort(manifestId: ManifestId, reason: String)
}

/// Why a `MANIFEST_*` payload was refused. Recorded in diagnostics; never sent to the peer verbatim.
///
/// Raw values match the Kotlin enum constant names exactly, since `protocol/vectors/
/// manifest-messages/` names them that way and both platforms read the same file.
public enum ManifestMessageRejection: String, Sendable, Equatable {
    case unknownType = "UNKNOWN_TYPE"
    case missingField = "MISSING_FIELD"
    case wrongFieldType = "WRONG_FIELD_TYPE"
    case malformedManifestId = "MALFORMED_MANIFEST_ID"
    case invalidRevision = "INVALID_REVISION"
    case invalidManifestKind = "INVALID_MANIFEST_KIND"
    case invalidRequest = "INVALID_REQUEST"
    case malformedDigest = "MALFORMED_DIGEST"
    /// Any structural problem inside one manifest entry — missing key, wrong type, malformed
    /// hash. Rejects the whole page (ADR-013: nothing partial).
    case entryFieldInvalid = "ENTRY_FIELD_INVALID"
    /// A display field's defensive parse-time bound — distinct from the 512-scalar build-time
    /// clamp (ADR-013), which is a sender rule, not a receiver rejection.
    case entryFieldTooLarge = "ENTRY_FIELD_TOO_LARGE"
    case tooManyEntries = "TOO_MANY_ENTRIES"
}

/// Parses and bounds-checks a `MANIFEST_*` payload. Total and non-trapping, mirroring
/// `AudioStateCodec`/`VoiceSignalCodec`: a malformed frame is dropped and the control connection
/// survives (PROTOCOL §2 rule 2, applied here the same way §7.4 applies it to `VOICE_*`).
///
/// The Kotlin mirror is `com.ridelink.core.protocol.ManifestCodec`, and both run
/// `protocol/vectors/manifest-messages/`.
public enum ManifestCodec {
    private static let validAbortReasons: Set<String> = ["library_changed", "page_oversize", "cancelled", "internal"]
    private static let maxEntriesPerPage = ManifestPaging.maxEntriesPerPage
    private static let maxDisplayFieldBytes = 4096

    public enum Result: Sendable, Equatable {
        case parsed(ManifestMessage)
        case rejected(ManifestMessageRejection)
    }

    public static let fieldManifestId = "manifest_id"
    public static let fieldKind = "kind"
    public static let fieldManifestRevision = "manifest_revision"
    public static let fieldBaseRevision = "base_revision"
    public static let fieldTotalEntries = "total_entries"
    public static let fieldTotalRemoved = "total_removed"
    public static let fieldPageCount = "page_count"
    public static let fieldDigestAlg = "digest_alg"
    public static let fieldSinceRevision = "since_revision"
    public static let fieldMaxPageBytes = "max_page_bytes"
    public static let fieldPageIndex = "page_index"
    public static let fieldEntries = "entries"
    public static let fieldRemoved = "removed"
    public static let fieldDigest = "digest"
    public static let fieldReason = "reason"

    public static func parse(type: String, payload: [String: JSONValue]) -> Result {
        switch type {
        case ManifestMessageTypes.request: return parseRequest(payload)
        case ManifestMessageTypes.begin: return parseBegin(payload)
        case ManifestMessageTypes.page: return parsePage(payload)
        case ManifestMessageTypes.end: return parseEnd(payload)
        case ManifestMessageTypes.abort: return parseAbort(payload)
        default: return .rejected(.unknownType)
        }
    }

    /// The `MANIFEST_*` type a given `ManifestMessage` wire-encodes as.
    public static func wireType(_ message: ManifestMessage) -> String {
        switch message {
        case .request: return ManifestMessageTypes.request
        case .begin: return ManifestMessageTypes.begin
        case .page: return ManifestMessageTypes.page
        case .end: return ManifestMessageTypes.end
        case .abort: return ManifestMessageTypes.abort
        }
    }

    /// The outbound side of `parse` — the shape lives here, once, shared by both directions.
    public static func encode(_ message: ManifestMessage) -> [String: JSONValue] {
        switch message {
        case .request(let sinceRevision, let maxPageBytes):
            return [
                fieldSinceRevision: sinceRevision.map { JSONValue.number(Double($0)) } ?? .null,
                fieldMaxPageBytes: .number(Double(maxPageBytes)),
            ]
        case .begin(let manifestId, let kind, let manifestRevision, let baseRevision, let totalEntries, let totalRemoved, let pageCount, let digestAlg):
            return [
                fieldManifestId: .string(manifestId.value),
                fieldKind: .string(kind.wire),
                fieldManifestRevision: .number(Double(manifestRevision)),
                fieldBaseRevision: baseRevision.map { JSONValue.number(Double($0)) } ?? .null,
                fieldTotalEntries: .number(Double(totalEntries)),
                fieldTotalRemoved: .number(Double(totalRemoved)),
                fieldPageCount: pageCount.map { JSONValue.number(Double($0)) } ?? .null,
                fieldDigestAlg: .string(digestAlg),
            ]
        case .page(let manifestId, let manifestRevision, let pageIndex, let entries, let removed):
            return [
                fieldManifestId: .string(manifestId.value),
                fieldManifestRevision: .number(Double(manifestRevision)),
                fieldPageIndex: .number(Double(pageIndex)),
                fieldEntries: .array(entries.map { JSONValue.object($0.toJSONObject()) }),
                fieldRemoved: .array(removed.map { JSONValue.string($0.value) }),
            ]
        case .end(let manifestId, let manifestRevision, let pageCount, let totalEntries, let totalRemoved, let digest):
            return [
                fieldManifestId: .string(manifestId.value),
                fieldManifestRevision: .number(Double(manifestRevision)),
                fieldPageCount: .number(Double(pageCount)),
                fieldTotalEntries: .number(Double(totalEntries)),
                fieldTotalRemoved: .number(Double(totalRemoved)),
                fieldDigest: .string(digest),
            ]
        case .abort(let manifestId, let reason):
            return [
                fieldManifestId: .string(manifestId.value),
                fieldReason: .string(reason),
            ]
        }
    }

    private static func parseRequest(_ payload: [String: JSONValue]) -> Result {
        guard let sinceOutcome = nullableLong(payload, fieldSinceRevision) else {
            return missingOrWrongType(payload, fieldSinceRevision)
        }
        if case .rejected(let reason) = sinceOutcome { return .rejected(reason) }
        guard let maxPageBytes = manifestIntField(payload, fieldMaxPageBytes) else {
            return missingOrWrongType(payload, fieldMaxPageBytes)
        }
        guard case .accepted(let sinceRevision) = sinceOutcome else { return .rejected(.wrongFieldType) }
        if let value = sinceRevision, value < 0 { return .rejected(.invalidRevision) }
        return .parsed(.request(sinceRevision: sinceRevision, maxPageBytes: maxPageBytes))
    }

    // swiftlint:disable:next cyclomatic_complexity function_body_length
    private static func parseBegin(_ payload: [String: JSONValue]) -> Result {
        guard let idRaw = manifestStringField(payload, fieldManifestId) else { return missingOrWrongType(payload, fieldManifestId) }
        guard let id = ManifestId.parse(idRaw) else { return .rejected(.malformedManifestId) }
        guard let kindRaw = manifestStringField(payload, fieldKind) else { return missingOrWrongType(payload, fieldKind) }
        guard let kind = ManifestKind.parse(kindRaw) else { return .rejected(.invalidManifestKind) }
        guard let revision = manifestLongField(payload, fieldManifestRevision) else {
            return missingOrWrongType(payload, fieldManifestRevision)
        }
        if revision < 0 { return .rejected(.invalidRevision) }
        guard let baseRevisionOutcome = nullableLong(payload, fieldBaseRevision) else {
            return missingOrWrongType(payload, fieldBaseRevision)
        }
        if case .rejected(let reason) = baseRevisionOutcome { return .rejected(reason) }
        guard let totalEntries = manifestIntField(payload, fieldTotalEntries) else {
            return missingOrWrongType(payload, fieldTotalEntries)
        }
        if totalEntries < 0 { return .rejected(.invalidRequest) }
        guard let totalRemoved = manifestIntField(payload, fieldTotalRemoved) else {
            return missingOrWrongType(payload, fieldTotalRemoved)
        }
        if totalRemoved < 0 { return .rejected(.invalidRequest) }
        guard let pageCountOutcome = nullableInt(payload, fieldPageCount) else {
            return missingOrWrongType(payload, fieldPageCount)
        }
        if case .rejected(let reason) = pageCountOutcome { return .rejected(reason) }
        guard let digestAlg = manifestStringField(payload, fieldDigestAlg) else {
            return missingOrWrongType(payload, fieldDigestAlg)
        }
        guard case .accepted(let baseRevision) = baseRevisionOutcome,
              case .accepted(let pageCount) = pageCountOutcome
        else { return .rejected(.wrongFieldType) }
        return .parsed(
            .begin(
                manifestId: id,
                kind: kind,
                manifestRevision: revision,
                baseRevision: baseRevision,
                totalEntries: totalEntries,
                totalRemoved: totalRemoved,
                pageCount: pageCount,
                digestAlg: digestAlg
            )
        )
    }

    private static func parsePage(_ payload: [String: JSONValue]) -> Result {
        guard let idRaw = manifestStringField(payload, fieldManifestId) else { return missingOrWrongType(payload, fieldManifestId) }
        guard let id = ManifestId.parse(idRaw) else { return .rejected(.malformedManifestId) }
        guard let revision = manifestLongField(payload, fieldManifestRevision) else {
            return missingOrWrongType(payload, fieldManifestRevision)
        }
        guard let pageIndex = manifestIntField(payload, fieldPageIndex) else { return missingOrWrongType(payload, fieldPageIndex) }
        if pageIndex < 0 { return .rejected(.invalidRequest) }
        guard case .array(let entriesRaw)? = payload[fieldEntries] else { return missingOrWrongType(payload, fieldEntries) }
        if entriesRaw.count > maxEntriesPerPage { return .rejected(.tooManyEntries) }
        var entries: [ManifestEntry] = []
        for raw in entriesRaw {
            guard case .object(let obj) = raw else { return .rejected(.entryFieldInvalid) }
            switch parseEntry(obj) {
            case .ok(let entry): entries.append(entry)
            case .invalid: return .rejected(.entryFieldInvalid)
            case .tooLarge: return .rejected(.entryFieldTooLarge)
            }
        }
        guard case .array(let removedRaw)? = payload[fieldRemoved] else { return missingOrWrongType(payload, fieldRemoved) }
        var removed: [ContentHash] = []
        for raw in removedRaw {
            guard case .string(let s) = raw else { return .rejected(.entryFieldInvalid) }
            guard let hash = ContentHash.parse(s) else { return .rejected(.entryFieldInvalid) }
            removed.append(hash)
        }
        return .parsed(.page(manifestId: id, manifestRevision: revision, pageIndex: pageIndex, entries: entries, removed: removed))
    }

    private static func parseEnd(_ payload: [String: JSONValue]) -> Result {
        guard let idRaw = manifestStringField(payload, fieldManifestId) else { return missingOrWrongType(payload, fieldManifestId) }
        guard let id = ManifestId.parse(idRaw) else { return .rejected(.malformedManifestId) }
        guard let revision = manifestLongField(payload, fieldManifestRevision) else {
            return missingOrWrongType(payload, fieldManifestRevision)
        }
        guard let pageCount = manifestIntField(payload, fieldPageCount) else { return missingOrWrongType(payload, fieldPageCount) }
        if pageCount < 0 { return .rejected(.invalidRequest) }
        guard let totalEntries = manifestIntField(payload, fieldTotalEntries) else {
            return missingOrWrongType(payload, fieldTotalEntries)
        }
        guard let totalRemoved = manifestIntField(payload, fieldTotalRemoved) else {
            return missingOrWrongType(payload, fieldTotalRemoved)
        }
        guard let digest = manifestStringField(payload, fieldDigest) else { return missingOrWrongType(payload, fieldDigest) }
        guard matchesDigestFormat(digest) else { return .rejected(.malformedDigest) }
        return .parsed(
            .end(
                manifestId: id,
                manifestRevision: revision,
                pageCount: pageCount,
                totalEntries: totalEntries,
                totalRemoved: totalRemoved,
                digest: digest
            )
        )
    }

    private static func parseAbort(_ payload: [String: JSONValue]) -> Result {
        guard let idRaw = manifestStringField(payload, fieldManifestId) else { return missingOrWrongType(payload, fieldManifestId) }
        guard let id = ManifestId.parse(idRaw) else { return .rejected(.malformedManifestId) }
        guard let reasonRaw = manifestStringField(payload, fieldReason) else { return missingOrWrongType(payload, fieldReason) }
        let reason = validAbortReasons.contains(reasonRaw) ? reasonRaw : "unknown"
        return .parsed(.abort(manifestId: id, reason: reason))
    }

    // MARK: - entry parsing

    private enum EntryResult {
        case ok(ManifestEntry)
        case invalid
        case tooLarge
    }

    // swiftlint:disable:next cyclomatic_complexity function_body_length
    private static func parseEntry(_ o: [String: JSONValue]) -> EntryResult {
        var contentHash: ContentHash?
        if let entry = o[ManifestEntry.fieldContentHash], entry != .null {
            guard case .string(let s) = entry, let parsed = ContentHash.parse(s) else { return .invalid }
            contentHash = parsed
        }
        guard case .string(let quickIdRaw)? = o[ManifestEntry.fieldQuickId], let quickId = QuickId.parse(quickIdRaw) else {
            return .invalid
        }
        guard case .string(let workKey)? = o[ManifestEntry.fieldWorkKey] else { return .invalid }
        guard let title = boundedString(o, ManifestEntry.fieldTitle) else { return .invalid }
        if title.isTooLarge { return .tooLarge }
        guard let artist = boundedString(o, ManifestEntry.fieldArtist) else { return .invalid }
        if artist.isTooLarge { return .tooLarge }
        guard let album = boundedString(o, ManifestEntry.fieldAlbum) else { return .invalid }
        if album.isTooLarge { return .tooLarge }
        guard case .number(let durationRaw)? = o[ManifestEntry.fieldDurationMs], let durationMs = Int64(exactly: durationRaw) else {
            return .invalid
        }
        guard case .string(let codec)? = o[ManifestEntry.fieldCodec] else { return .invalid }
        guard case .number(let bitrateRaw)? = o[ManifestEntry.fieldBitrateKbps], let bitrateKbps = Int(exactly: bitrateRaw) else {
            return .invalid
        }
        guard case .number(let sizeRaw)? = o[ManifestEntry.fieldSizeBytes], let sizeBytes = Int64(exactly: sizeRaw) else {
            return .invalid
        }
        guard let filename = boundedString(o, ManifestEntry.fieldFilename) else { return .invalid }
        if filename.isTooLarge { return .tooLarge }
        guard case .bool(let hasArtwork)? = o[ManifestEntry.fieldHasArtwork] else { return .invalid }
        return .ok(
            ManifestEntry(
                contentHash: contentHash,
                quickId: quickId,
                workKey: workKey,
                title: title.value,
                artist: artist.value,
                album: album.value,
                durationMs: durationMs,
                codec: codec,
                bitrateKbps: bitrateKbps,
                sizeBytes: sizeBytes,
                filename: filename.value,
                hasArtwork: hasArtwork
            )
        )
    }

    private struct BoundedString {
        let value: String
        let isTooLarge: Bool
    }

    private static func boundedString(_ o: [String: JSONValue], _ key: String) -> BoundedString? {
        guard case .string(let s)? = o[key] else { return nil }
        return BoundedString(value: s, isTooLarge: s.utf8.count > maxDisplayFieldBytes)
    }

    // MARK: - nullable numeric fields

    private enum LongOutcome {
        case accepted(Int64?)
        case rejected(ManifestMessageRejection)
    }

    private static func nullableLong(_ payload: [String: JSONValue], _ key: String) -> LongOutcome? {
        guard let entry = payload[key] else { return nil }
        if entry == .null { return .accepted(nil) }
        guard case .number(let value) = entry, let exact = Int64(exactly: value) else { return .rejected(.wrongFieldType) }
        return .accepted(exact)
    }

    private enum IntOutcome {
        case accepted(Int?)
        case rejected(ManifestMessageRejection)
    }

    private static func nullableInt(_ payload: [String: JSONValue], _ key: String) -> IntOutcome? {
        guard let entry = payload[key] else { return nil }
        if entry == .null { return .accepted(nil) }
        guard case .number(let value) = entry, let exact = Int(exactly: value) else { return .rejected(.wrongFieldType) }
        return .accepted(exact)
    }

    private static func missingOrWrongType(_ payload: [String: JSONValue], _ key: String) -> Result {
        payload[key] != nil ? .rejected(.wrongFieldType) : .rejected(.missingField)
    }

    private static func matchesDigestFormat(_ s: String) -> Bool {
        guard s.hasPrefix("sha256:") else { return false }
        let hex = s.dropFirst("sha256:".count)
        return hex.count == 64 && hex.allSatisfy { $0.isHexDigit && !$0.isUppercase }
    }
}

// The field readers below are file-private free functions with distinct names for the same reason
// `VoiceSignalCodec`'s/`AudioStateCodec`'s are: `RideLinkPlatform` carries `stringValue`/
// `int64Value`/`boolValue` extensions of its own, and a second set with the same names visible in
// both modules would be an ambiguity at every use site in the platform layer.

private func manifestStringField(_ payload: [String: JSONValue], _ key: String) -> String? {
    if case .string(let value)? = payload[key] { return value }
    return nil
}

private func manifestLongField(_ payload: [String: JSONValue], _ key: String) -> Int64? {
    guard case .number(let value)? = payload[key] else { return nil }
    return Int64(exactly: value)
}

private func manifestIntField(_ payload: [String: JSONValue], _ key: String) -> Int? {
    guard case .number(let value)? = payload[key] else { return nil }
    return Int(exactly: value)
}
