import Foundation

final class SecureFileStore {
    private let root: URL
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        var rootURL = support.appendingPathComponent("TANU", isDirectory: true)
        try? FileManager.default.createDirectory(at: rootURL, withIntermediateDirectories: true)
        try? FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
            ofItemAtPath: rootURL.path
        )
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? rootURL.setResourceValues(values)
        root = rootURL

        encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
    }

    func load<T: Decodable>(_ type: T.Type, fileName: String) -> T? {
        let url = root.appendingPathComponent(fileName)
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? decoder.decode(type, from: data)
    }

    func save<T: Encodable>(_ value: T, fileName: String) throws {
        let data = try encoder.encode(value)
        let url = root.appendingPathComponent(fileName)
        try data.write(to: url, options: .atomic)
        try FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
            ofItemAtPath: url.path
        )
    }

    func recordingDirectory(meetingID: String) throws -> URL {
        let directory = root.appendingPathComponent("recordings", isDirectory: true)
            .appendingPathComponent(meetingID, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.completeUnlessOpen],
            ofItemAtPath: directory.path
        )
        return directory
    }

    func protectRecording(at url: URL) {
        try? FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.completeUnlessOpen],
            ofItemAtPath: url.path
        )
    }

    func cleanupRecordings(meetingID: String) {
        let directory = root.appendingPathComponent("recordings", isDirectory: true)
            .appendingPathComponent(meetingID, isDirectory: true)
        try? FileManager.default.removeItem(at: directory)
    }
}
