import Foundation
import XCTest
@testable import RideLinkPlatform

/// ADR-023 Amendment A4 Finding T — the provider's framing must match the `chunk_size`/`chunk_count`
/// its own `TRANSFER_OFFER` already declared (PROTOCOL §8.2). The Kotlin mirror is
/// `com.ridelink.network.transfer.InputStreamChunkSourceTest`.
///
/// **A real divergence from the Kotlin mirror, stated rather than papered over.** Android's version
/// can inject a deliberately short-reading `InputStream`, because that is what
/// `ContentResolver.openInputStream` over a `content://` document actually does and what made this
/// bug bite there. iOS's provider always reads a plain file inside this app's own container
/// (ADR-009 — the library is an import-copy, and the transfer cache is a promoted `.part`), and
/// `FileHandle` over a regular file does not short-read in practice, so no equivalent fake exists
/// to inject. What these cases pin is therefore the property the wire contract actually needs — the
/// frame count and sizes a given file length must produce — over real files, at the boundaries
/// where an off-by-one would show. `read(upToCount:)`'s contract still only promises *up to* that
/// many bytes, which is why the fill loop is there regardless of today's observed behaviour.
final class FileChunkSourceTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
            .appendingPathComponent("ridelink-chunk-source-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private func drain(bytes: Int, chunkSize: Int) async throws -> [[UInt8]] {
        let url = directory.appendingPathComponent("payload.bin")
        let payload = Data((0..<bytes).map { UInt8($0 % 251) })
        try payload.write(to: url)
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }

        let source = FileChunkSource(handle: handle, chunkSize: chunkSize)
        var frames: [[UInt8]] = []
        while let frame = await source.nextChunk() { frames.append(frame) }
        // Whatever the framing, the bytes must round-trip exactly — the same property the
        // requester's whole-file SHA-256 ultimately checks.
        XCTAssertEqual([UInt8](payload), frames.flatMap { $0 })
        return frames
    }

    func testAnExactMultipleOfTheChunkSizeYieldsExactlyThatManyFullFrames() async throws {
        let frames = try await drain(bytes: 1024 * 10, chunkSize: 1024)

        XCTAssertEqual(10, frames.count, "chunk_count = ceil(size / chunk_size) — the count the offer already promised")
        XCTAssertTrue(frames.allSatisfy { $0.count == 1024 })
    }

    func testTheFinalFrameIsTheRemainderRatherThanPaddedToChunkSize() async throws {
        let frames = try await drain(bytes: 1024 * 2 + 7, chunkSize: 1024)

        XCTAssertEqual(3, frames.count)
        XCTAssertEqual(1024, frames[0].count)
        XCTAssertEqual(1024, frames[1].count)
        XCTAssertEqual(7, frames[2].count)
    }

    func testAFileSmallerThanOneChunkIsASingleShortFrame() async throws {
        let frames = try await drain(bytes: 5, chunkSize: 1024)

        XCTAssertEqual(1, frames.count)
        XCTAssertEqual(5, frames[0].count)
    }

    func testAnEmptyFileYieldsNoFramesAtAll() async throws {
        let url = directory.appendingPathComponent("empty.bin")
        try Data().write(to: url)
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }

        let frame = await FileChunkSource(handle: handle, chunkSize: 1024).nextChunk()

        XCTAssertNil(frame, "a trailing empty frame would be one frame past the declared chunk_count")
    }

    func testFramingHoldsAtTheRealWireChunkSize() async throws {
        // The production chunk size, and a length that is deliberately not a multiple of it.
        let frames = try await drain(bytes: 65_536 * 2 + 1, chunkSize: 65_536)

        XCTAssertEqual(3, frames.count)
        XCTAssertEqual(1, frames[2].count)
    }
}
