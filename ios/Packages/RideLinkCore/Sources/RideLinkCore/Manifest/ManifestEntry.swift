import Foundation

/// PROTOCOL §8.1's `MANIFEST_PAGE` entry shape — deliberately minimal (ARCHITECTURE §8.2:
/// "entries are deliberately minimal"). `filename` is a basename only, never a path
/// (REQUIREMENTS §11) — enforcement of that lives in whoever builds this from a library row, not
/// here.
///
/// `contentHash` is `nil` exactly when this phone's background hashing job (ADR-005) has not
/// finished this file yet — such an entry is displayable but not sync-eligible or transferable
/// (ARCHITECTURE §8.1). `workKey` is ARCHITECTURE §8.1's non-authoritative grouping key; it is
/// carried on the wire because PROTOCOL §8.1's example includes it, but nothing here or in
/// `ManifestPaging` ever reads it for identity.
///
/// The Kotlin mirror is `com.ridelink.core.manifest.ManifestEntry`.
public struct ManifestEntry: Sendable, Equatable {
    public var contentHash: ContentHash?
    public var quickId: QuickId
    public var workKey: String
    public var title: String
    public var artist: String
    public var album: String
    public var durationMs: Int64
    public var codec: String
    public var bitrateKbps: Int
    public var sizeBytes: Int64
    public var filename: String
    public var hasArtwork: Bool

    public init(
        contentHash: ContentHash?,
        quickId: QuickId,
        workKey: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Int64,
        codec: String,
        bitrateKbps: Int,
        sizeBytes: Int64,
        filename: String,
        hasArtwork: Bool
    ) {
        self.contentHash = contentHash
        self.quickId = quickId
        self.workKey = workKey
        self.title = title
        self.artist = artist
        self.album = album
        self.durationMs = durationMs
        self.codec = codec
        self.bitrateKbps = bitrateKbps
        self.sizeBytes = sizeBytes
        self.filename = filename
        self.hasArtwork = hasArtwork
    }

    public static let fieldContentHash = "content_hash"
    public static let fieldQuickId = "quick_id"
    public static let fieldWorkKey = "work_key"
    public static let fieldTitle = "title"
    public static let fieldArtist = "artist"
    public static let fieldAlbum = "album"
    public static let fieldDurationMs = "duration_ms"
    public static let fieldCodec = "codec"
    public static let fieldBitrateKbps = "bitrate_kbps"
    public static let fieldSizeBytes = "size_bytes"
    public static let fieldFilename = "filename"
    public static let fieldHasArtwork = "has_artwork"

    /// In spec order — the field list both the encoder and any "no extra fields" test read.
    public static let fields: [String] = [
        fieldContentHash, fieldQuickId, fieldWorkKey, fieldTitle, fieldArtist, fieldAlbum,
        fieldDurationMs, fieldCodec, fieldBitrateKbps, fieldSizeBytes, fieldFilename, fieldHasArtwork,
    ]

    /// The wire form as a `JSONValue` object, in no particular key order — `Dictionary` has none.
    /// Byte-accounting (`encodedByteLength`) uses `encodedJSONString` instead, precisely because
    /// this representation cannot be used for that.
    public func toJSONObject() -> [String: JSONValue] {
        [
            Self.fieldContentHash: contentHash.map { JSONValue.string($0.value) } ?? .null,
            Self.fieldQuickId: .string(quickId.value),
            Self.fieldWorkKey: .string(workKey),
            Self.fieldTitle: .string(title),
            Self.fieldArtist: .string(artist),
            Self.fieldAlbum: .string(album),
            Self.fieldDurationMs: .number(Double(durationMs)),
            Self.fieldCodec: .string(codec),
            Self.fieldBitrateKbps: .number(Double(bitrateKbps)),
            Self.fieldSizeBytes: .number(Double(sizeBytes)),
            Self.fieldFilename: .string(filename),
            Self.fieldHasArtwork: .bool(hasArtwork),
        ]
    }

    /// Encoded byte length as it would appear inside a page — see `ManifestPaging`'s page budget.
    ///
    /// This must agree, byte for byte, with the Kotlin mirror's
    /// `kotlinx.serialization.json.JsonObject.toString()` output, since `protocol/vectors/
    /// manifest-paging/` pins exact page boundaries computed from that byte count on the Kotlin
    /// side. A `Dictionary`-backed encoder (`JSONSerialization`, `JSONEncoder`) cannot give that
    /// guarantee — Swift dictionaries have no defined key order and neither encoder promises the
    /// same compact, non-ASCII-preserving escaping kotlinx.serialization uses — so this builds the
    /// compact `{"content_hash":…,…}` string directly, in `fields` order, with the same minimal
    /// JSON string escaping both the Kotlin serializer and `tools/generate_manifest_paging_vectors
    /// .py`'s `json.dumps(..., separators=(",", ":"), ensure_ascii=False)` produce.
    public func encodedByteLength() -> Int { encodedJSONString().utf8.count }

    func encodedJSONString() -> String {
        var out = "{"
        var first = true
        func field(_ key: String, _ valueJSON: String) {
            if !first { out += "," }
            first = false
            out += "\"\(key)\":\(valueJSON)"
        }
        field(Self.fieldContentHash, contentHash.map { jsonStringLiteral($0.value) } ?? "null")
        field(Self.fieldQuickId, jsonStringLiteral(quickId.value))
        field(Self.fieldWorkKey, jsonStringLiteral(workKey))
        field(Self.fieldTitle, jsonStringLiteral(title))
        field(Self.fieldArtist, jsonStringLiteral(artist))
        field(Self.fieldAlbum, jsonStringLiteral(album))
        field(Self.fieldDurationMs, String(durationMs))
        field(Self.fieldCodec, jsonStringLiteral(codec))
        field(Self.fieldBitrateKbps, String(bitrateKbps))
        field(Self.fieldSizeBytes, String(sizeBytes))
        field(Self.fieldFilename, jsonStringLiteral(filename))
        field(Self.fieldHasArtwork, hasArtwork ? "true" : "false")
        out += "}"
        return out
    }
}

/// Minimal JSON string escaping: quote, backslash, the named control-character shorthands, and a
/// `\u00XX` escape for any other C0 control character. Everything else — including non-ASCII text
/// — passes through unescaped, matching `ensure_ascii=False` on the Python generator and
/// kotlinx.serialization's default behaviour on the Kotlin side.
private func jsonStringLiteral(_ s: String) -> String {
    var out = "\""
    out.reserveCapacity(s.utf8.count + 2)
    for scalar in s.unicodeScalars {
        switch scalar {
        case "\"": out += "\\\""
        case "\\": out += "\\\\"
        case "\u{08}": out += "\\b"
        case "\u{0C}": out += "\\f"
        case "\n": out += "\\n"
        case "\r": out += "\\r"
        case "\t": out += "\\t"
        default:
            if scalar.value < 0x20 {
                out += String(format: "\\u%04x", scalar.value)
            } else {
                out.unicodeScalars.append(scalar)
            }
        }
    }
    out += "\""
    return out
}
