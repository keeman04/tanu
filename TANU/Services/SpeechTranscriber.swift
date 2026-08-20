import Foundation
import Speech

final class SpeechTranscriber: @unchecked Sendable {
    enum SpeechError: LocalizedError {
        case unavailable
        case permissionDenied
        case timeout

        var errorDescription: String? {
            switch self {
            case .unavailable: return "Speech recognition is unavailable on this iPhone."
            case .permissionDenied: return "Speech recognition permission was not granted."
            case .timeout: return "Speech recognition timed out for this audio chunk."
            }
        }
    }

    func requestAuthorization() async -> Bool {
        switch SFSpeechRecognizer.authorizationStatus() {
        case .authorized:
            return true
        case .denied, .restricted:
            return false
        case .notDetermined:
            return await withCheckedContinuation { continuation in
                SFSpeechRecognizer.requestAuthorization { status in
                    continuation.resume(returning: status == .authorized)
                }
            }
        @unknown default:
            return false
        }
    }

    func transcribe(fileURL: URL) async throws -> String {
        guard let recognizer = SFSpeechRecognizer(locale: Locale.current), recognizer.isAvailable else {
            throw SpeechError.unavailable
        }

        return try await withCheckedThrowingContinuation { continuation in
            let gate = SpeechResultGate(continuation)
            let taskBox = SpeechTaskBox()
            let request = SFSpeechURLRecognitionRequest(url: fileURL)
            request.shouldReportPartialResults = false
            request.taskHint = .dictation
            request.contextualStrings = ["TANU", "MOM", "minutes of meeting", "WhatsApp"]
            if recognizer.supportsOnDeviceRecognition {
                request.requiresOnDeviceRecognition = true
            }

            let task = recognizer.recognitionTask(with: request) { result, error in
                if let result, result.isFinal {
                    if gate.finish(.success(result.bestTranscription.formattedString)) {
                        taskBox.cancel()
                    }
                } else if let error {
                    if gate.finish(.failure(error)) {
                        taskBox.cancel()
                    }
                }
            }
            taskBox.set(task)

            Task.detached(priority: .utility) {
                try? await Task.sleep(nanoseconds: 60_000_000_000)
                if gate.finish(.failure(SpeechError.timeout)) {
                    taskBox.cancel()
                }
            }
        }
    }
}

private final class SpeechResultGate: @unchecked Sendable {
    private let lock = NSLock()
    private var finished = false
    private let continuation: CheckedContinuation<String, Error>

    init(_ continuation: CheckedContinuation<String, Error>) {
        self.continuation = continuation
    }

    @discardableResult
    func finish(_ result: Result<String, Error>) -> Bool {
        lock.lock()
        guard !finished else {
            lock.unlock()
            return false
        }
        finished = true
        lock.unlock()
        continuation.resume(with: result)
        return true
    }
}

private final class SpeechTaskBox: @unchecked Sendable {
    private let lock = NSLock()
    private var task: SFSpeechRecognitionTask?

    func set(_ task: SFSpeechRecognitionTask) {
        lock.lock()
        self.task = task
        lock.unlock()
    }

    func cancel() {
        lock.lock()
        let task = self.task
        lock.unlock()
        task?.cancel()
    }
}
