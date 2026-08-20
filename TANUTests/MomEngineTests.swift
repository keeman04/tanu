import XCTest
@testable import TANU

final class MomEngineTests: XCTestCase {
    func testAlwaysCreatesSummaryWhenTranscriptExists() {
        let mom = MomEngine.generate(from: "We reviewed the quotation. We agreed to send the revised quote tomorrow. Ravi will prepare the pricing sheet.")
        XCTAssertFalse(mom.summary.isEmpty)
        XCTAssertFalse(mom.decisions.isEmpty)
        XCTAssertFalse(mom.actions.isEmpty)
    }

    func testDeviceFallbackDoesNotRequireOpenAI() {
        let mom = MomEngine.generate(from: "Customer asked for delivery next week. We need to review stock.")
        XCTAssertEqual(mom.source, "device")
        XCTAssertFalse(mom.summary.isEmpty)
    }
}
