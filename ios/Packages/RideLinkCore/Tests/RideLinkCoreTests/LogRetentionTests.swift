import XCTest
@testable import RideLinkCore

final class LogRetentionTests: XCTestCase {
    func testProductionDiagnosticsRetainOnlyTheMostRecent1024Events() {
        let sink = InMemoryLogSink()
        for index in 0 ..< 100_000 {
            sink.emit(LogEvent(monotonicTimestampUs: Int64(index), level: .info, tag: "stress", message: "\(index)"))
        }
        let events = sink.events
        XCTAssertEqual(events.count, 1_024)
        XCTAssertEqual(events.map(\.monotonicTimestampUs), Array(98_976 ... 99_999))
        sink.emit(LogEvent(monotonicTimestampUs: 100_000, level: .info, tag: "stress", message: "next"))
        XCTAssertEqual(events.last?.monotonicTimestampUs, 99_999)
        XCTAssertEqual(sink.events.last?.monotonicTimestampUs, 100_000)
    }
}
