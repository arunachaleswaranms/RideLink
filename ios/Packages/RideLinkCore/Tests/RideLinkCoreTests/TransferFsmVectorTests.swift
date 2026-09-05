import XCTest

@testable import RideLinkCore

/// Runs `protocol/vectors/transfer-fsm/transfer_fsm_vectors.json` against `TransferReducer`.
///
/// The mirror is `com.ridelink.core.transfer.TransferFsmVectorTest`, running the **same file**.
final class TransferFsmVectorTests: XCTestCase {
    private let expectedRowCount = 47

    private func document() throws -> [String: Any] {
        // swiftlint:disable:next force_cast
        try Vectors.loadJSON("transfer-fsm/transfer_fsm_vectors.json") as! [String: Any]
    }

    func testEveryRowOfTheSharedFileHolds() throws {
        let doc = try document()
        var checked = 0
        for element in doc.array("rows") {
            guard let row = element as? [String: Any] else { return XCTFail("row is not an object") }
            let name = row.str("name")
            guard let status = TransferStatus(rawValue: row.str("status")) else {
                XCTFail("vector \(name): unknown status \(row.str("status"))")
                continue
            }
            let event = eventFrom(row.dict("event"))
            let expect = row.dict("expect")

            let transition = TransferReducer.apply(status: status, event: event)
            XCTAssertEqual(TransferStatus(rawValue: expect.str("status")), transition.status, "vector \(name): status")

            let expectedActionKinds = expect.array("actions").map { action -> String in
                actionKind(action as! [String: Any]) // swiftlint:disable:this force_cast
            }
            XCTAssertEqual(expectedActionKinds, transition.actions.map(actionKind), "vector \(name): actions")

            let expectedError = expect.strOpt("error")
            XCTAssertEqual(expectedError, transition.error?.rawValue, "vector \(name): error")
            checked += 1
        }
        XCTAssertEqual(expectedRowCount, checked, "expected \(expectedRowCount) rows")
    }

    // MARK: - vector decoding

    private func eventFrom(_ o: [String: Any]) -> TransferEvent {
        switch o.str("kind") {
        case "Enqueued": return .enqueued
        case "Dequeued": return .dequeued
        case "OfferReceived": return .offerReceived(sizeBytes: o.int64("size_bytes"), chunkCount: o.int("chunk_count"))
        case "OfferRejected": return .offerRejected(error: TransferError(rawValue: o.str("error"))!)
        case "BytesReceived": return .bytesReceived(bytes: o.int64("bytes"))
        case "SizeMismatchDetected": return .sizeMismatchDetected
        case "AllBytesReceived": return .allBytesReceived
        case "HashVerified": return .hashVerified(matches: o.boolVal("matches"))
        case "IoErrorDetected": return .ioErrorDetected
        case "DiskFullDetected": return .diskFullDetected
        case "ConnectionLost": return .connectionLost
        case "SessionInvalidated": return .sessionInvalidated
        case "Cancelled": return .cancelled
        default: fatalError("unknown event kind in vectors: \(o.str("kind"))")
        }
    }

    private func actionKind(_ o: [String: Any]) -> String { o.str("kind") }

    private func actionKind(_ action: TransferAction) -> String {
        switch action {
        case .sendTransferRequest: return "SendTransferRequest"
        case .openBulkConnection: return "OpenBulkConnection"
        case .writeChunkToPart: return "WriteChunkToPart"
        case .reportProgress: return "ReportProgress"
        case .computeHashFromDisk: return "ComputeHashFromDisk"
        case .promoteCacheEntry: return "PromoteCacheEntry"
        case .deletePartFile: return "DeletePartFile"
        case .closeBulkConnection: return "CloseBulkConnection"
        case .notifyUi: return "NotifyUi"
        }
    }
}
