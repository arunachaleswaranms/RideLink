import Foundation

/// PROTOCOL §8.2 — the RLB1 bulk-frame header: `magic(4) | chunk_index(uint32 BE) |
/// byte_length(uint32 BE) | payload(<= 64 KiB)`. Pure byte-array parsing, no socket I/O — the
/// network layer streams bytes in and this decides what a complete buffer contains, per
/// `protocol/vectors/bulk-framing/`.
///
/// Deliberately in `RideLinkCore`, not `RideLinkPlatform`: nothing here is platform-specific, and
/// a pure parser is what the vectors can pin without any platform harness. The one thing this type
/// does not attempt is deciding what bytes arrive when — arbitrary read-boundary splitting is a
/// transport concern the caller handles by feeding `parseAll` a growing buffer and keeping
/// `ParseResult`'s leftover for the next read.
///
/// `chunkIndex` and the parsed length are native `UInt32` here rather than the Kotlin mirror's
/// `Long`: Kotlin's `Int` is signed 32-bit, so reading an unsigned 32-bit value needs a wider type
/// to avoid a negative misread. Swift already has an unsigned 32-bit integer, so there is no
/// equivalent workaround to carry over — this is exactly the "don't copy a Kotlin idiom that
/// doesn't fit" case the port calls for, while the *rule* it exists to enforce (never a signed
/// misread of an unsigned wire value) is identical on both platforms.
///
/// The Kotlin mirror is `com.ridelink.core.transfer.BulkFraming`.
public enum BulkFraming {
    private static let magic: [UInt8] = Array("RLB1".utf8)
    public static let headerLen = 12
    public static let maxChunkPayloadBytes = 65_536

    public struct Frame: Sendable, Equatable {
        public let chunkIndex: UInt32
        public let payload: [UInt8]

        public init(chunkIndex: UInt32, payload: [UInt8]) {
            self.chunkIndex = chunkIndex
            self.payload = payload
        }
    }

    public enum ParseResult: Sendable, Equatable {
        /// At least zero complete frames, plus any bytes not yet consumed (the next frame's
        /// partial header/payload).
        case parsed(frames: [Frame], leftover: [UInt8])
        /// Not enough bytes yet to extract even one frame. Wait for more; never a parse error.
        case incomplete
        /// A fatal header violation — the connection must be closed, not merely this frame dropped.
        case invalid(reason: String)
    }

    /// Parses as many complete frames as `buffer` contains. Returns `.incomplete` only when
    /// **zero** frames could be extracted and more bytes are needed; a buffer that yields one or
    /// more complete frames is always `.parsed`, even if what remains after them is itself an
    /// incomplete header or payload — that remainder becomes `leftover` for the next call.
    ///
    /// The `byte_length` bound is checked **before** any allocation sized by it — no unchecked
    /// allocation based directly on a remote length field. Both an implausibly large unsigned
    /// value (`0xFFFFFFFF`) and one whose top bit would look negative under a signed 32-bit read
    /// (`0x80000000`) are simply "too large": reading unsigned never produces a negative value in
    /// the first place, so there is no separate "negative length" case to handle.
    public static func parseAll(_ buffer: [UInt8]) -> ParseResult {
        guard !buffer.isEmpty else { return .incomplete }
        var frames: [Frame] = []
        var offset = 0
        while offset < buffer.count {
            let remaining = buffer.count - offset
            if remaining < headerLen {
                return frames.isEmpty ? .incomplete : .parsed(frames: frames, leftover: Array(buffer[offset...]))
            }
            guard magicMatches(buffer, at: offset) else { return .invalid(reason: "bad_magic") }
            let chunkIndex = readU32(buffer, at: offset + 4)
            let byteLength = readU32(buffer, at: offset + 8)
            if byteLength > UInt32(maxChunkPayloadBytes) { return .invalid(reason: "chunk_too_large") }
            let frameTotal = headerLen + Int(byteLength)
            if buffer.count - offset < frameTotal {
                return frames.isEmpty ? .incomplete : .parsed(frames: frames, leftover: Array(buffer[offset...]))
            }
            let payloadStart = offset + headerLen
            let payloadEnd = payloadStart + Int(byteLength)
            frames.append(Frame(chunkIndex: chunkIndex, payload: Array(buffer[payloadStart..<payloadEnd])))
            offset = payloadEnd
        }
        return .parsed(frames: frames, leftover: [])
    }

    private static func magicMatches(_ buffer: [UInt8], at offset: Int) -> Bool {
        for i in magic.indices where buffer[offset + i] != magic[i] { return false }
        return true
    }

    /// Big-endian uint32 as a native `UInt32` — never a signed 32-bit misread.
    private static func readU32(_ buffer: [UInt8], at offset: Int) -> UInt32 {
        var result: UInt32 = 0
        for i in 0..<4 {
            result = (result << 8) | UInt32(buffer[offset + i])
        }
        return result
    }

    /// Encodes one outgoing frame. `payload.count` must already be within `maxChunkPayloadBytes`.
    public static func encodeFrame(chunkIndex: UInt32, payload: [UInt8]) -> [UInt8] {
        precondition(payload.count <= maxChunkPayloadBytes, "payload exceeds maxChunkPayloadBytes")
        var out = magic
        writeU32(&out, chunkIndex)
        writeU32(&out, UInt32(payload.count))
        out.append(contentsOf: payload)
        return out
    }

    private static func writeU32(_ out: inout [UInt8], _ value: UInt32) {
        out.append(UInt8((value >> 24) & 0xFF))
        out.append(UInt8((value >> 16) & 0xFF))
        out.append(UInt8((value >> 8) & 0xFF))
        out.append(UInt8(value & 0xFF))
    }
}
