import XCTest
@testable import TANU

final class ParticipantRulesTests: XCTestCase {
    func testNameAndWhatsAppAreRequired() {
        XCTAssertFalse(ParticipantRules.canSave(name: "", whatsapp: "+91 98765 43210", email: ""))
        XCTAssertFalse(ParticipantRules.canSave(name: "Tanu", whatsapp: "123", email: ""))
        XCTAssertTrue(ParticipantRules.canSave(name: "Tanu", whatsapp: "+91 98765 43210", email: ""))
    }

    func testEmailIsOptionalButValidatedWhenPresent() {
        XCTAssertTrue(ParticipantRules.canSave(name: "Tanu", whatsapp: "9876543210", email: ""))
        XCTAssertFalse(ParticipantRules.canSave(name: "Tanu", whatsapp: "9876543210", email: "bad-email"))
        XCTAssertTrue(ParticipantRules.canSave(name: "Tanu", whatsapp: "9876543210", email: "tanu@example.com"))
    }

    func testPhoneNormalization() {
        XCTAssertEqual(ParticipantRules.normalizeWhatsApp("+91 98765-43210"), "+919876543210")
    }
}
