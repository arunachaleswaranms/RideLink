import Foundation

/// Supplies chunk bytes to send, in order, starting at index 0. Returns nil when exhausted.
///
/// A disk-backed implementation (reading a real file's chunks) is the data-layer stage's job, not
/// this one — this protocol is the seam, mirroring Android's `fun interface ChunkSource`.
public protocol ChunkSource: Sendable {
    func nextChunk() async -> [UInt8]?
}

/// Consumes chunk bytes as they arrive, in order. Hashing/disk-writing is the caller's concern —
/// mirrors Android's `fun interface ChunkSink`.
public protocol ChunkSink: Sendable {
    func onChunk(index: Int64, bytes: [UInt8]) async
}

public enum BulkServeOutcome: Sendable, Equatable {
    case ok
    case notAuthorized
    case connectionLost
    case ioError
}

public enum BulkFetchOutcome: Sendable, Equatable {
    case ok
    case notAuthorized
    case connectionLost
    case ioError
    case protocolError
}

/// ADR-023 Amendment A5 — the one failure `TransferManager` raises itself, rather than passing a
/// transport error through. `listenerLifetimeEnded` means a `bind()` completed into a listener
/// lifetime that had already been torn down (`close()`, or a cancelled pending accept), so its
/// result was closed rather than published: the caller must treat the transfer as unservable, not
/// retry into a dead session.
public enum BulkTransportError: Error, Sendable, Equatable {
    case listenerLifetimeEnded
}
