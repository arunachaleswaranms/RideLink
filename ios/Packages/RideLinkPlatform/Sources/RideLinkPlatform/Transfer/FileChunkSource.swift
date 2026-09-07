import Foundation

/// A `ChunkSource` over an already-open `FileHandle`, yielding frames of exactly `chunkSize` bytes
/// until the file ends (the last frame is whatever remains).
///
/// **Closure-audit Amendment A4 Finding T — why this fills the frame rather than doing one read.**
/// The `TRANSFER_OFFER` the provider has already sent declares both `chunk_size` and
/// `chunk_count = ceil(size_bytes / chunk_size)` (PROTOCOL §8.2), and the requester enforces both:
/// every frame's `chunk_index` must be the exact next expected value, and a frame past
/// `chunk_count` is a `PROTOCOL_ERROR`. `FileHandle.read(upToCount:)` is, as its own name says, only
/// obliged to return *up to* that many bytes — so one read per frame could emit more, smaller
/// frames than the count already promised on the wire, and the requester's (correct) index check
/// would reject the whole transfer. Filling each frame is what keeps the provider's framing
/// consistent with its own offer.
///
/// Lives here rather than in the app target both because `ChunkSource` and `TransferManager` do,
/// and because `ios/RideLink.xcodeproj` has no test target over `ios/RideLink/*.swift` (ADR-023
/// Amendment A3) — as a private type in the coordinator it could not be tested at all. Mirrors
/// Android's `InputStreamChunkSource`.
public struct FileChunkSource: ChunkSource {
    private let handle: FileHandle
    private let chunkSize: Int

    public init(handle: FileHandle, chunkSize: Int) {
        self.handle = handle
        self.chunkSize = chunkSize
    }

    public func nextChunk() async -> [UInt8]? {
        var frame = Data()
        while frame.count < chunkSize {
            guard let piece = try? handle.read(upToCount: chunkSize - frame.count), !piece.isEmpty else { break }
            frame.append(piece)
        }
        return frame.isEmpty ? nil : [UInt8](frame)
    }
}
