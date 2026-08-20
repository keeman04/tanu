import Foundation

struct Participant: Identifiable, Codable, Hashable {
    var id: UUID = UUID()
    var name: String
    var whatsapp: String
    var email: String = ""
    var company: String = ""
}

enum MeetingStatus: String, Codable, Hashable {
    case recording
    case transcribing
    case ready
    case failed
}

struct TranscriptSegment: Identifiable, Codable, Hashable {
    var id: UUID = UUID()
    var createdAt: Date = Date()
    var text: String
}

struct MOM: Codable, Hashable {
    var summary: String
    var decisions: [String]
    var actions: [String]
    var followUps: [String]
    var generatedAt: Date = Date()
    var source: String = "device"
}

struct Meeting: Identifiable, Codable, Hashable {
    var id: UUID = UUID()
    var title: String
    var participantIDs: [UUID]
    var startedAt: Date = Date()
    var endedAt: Date?
    var status: MeetingStatus = .recording
    var transcript: [TranscriptSegment] = []
    var mom: MOM?
    var errorMessage: String?
}

struct PickedContact: Hashable {
    var name: String
    var phone: String
    var email: String
}
