import Foundation

final class OpenAIService {
    enum APIError: LocalizedError {
        case invalidResponse
        case requestFailed(Int)
        case invalidMOM

        var errorDescription: String? {
            switch self {
            case .invalidResponse: return "OpenAI returned an invalid response."
            case .requestFailed(let code): return "OpenAI request failed with status \(code)."
            case .invalidMOM: return "OpenAI returned notes TANU could not parse."
            }
        }
    }

    func generateMOM(transcript: String, apiKey: String) async throws -> MOM {
        let boundedTranscript = String(transcript.prefix(120_000))
        let prompt = """
        You are TANU, a meeting minutes assistant. Convert the transcript below into concise minutes of meeting.
        Return ONLY valid JSON with exactly these keys:
        {"summary":"string","decisions":["string"],"actions":["string"],"followUps":["string"]}
        Do not invent names, dates, decisions, owners, or commitments that are not in the transcript.

        TRANSCRIPT:
        \(boundedTranscript)
        """

        var request = URLRequest(url: URL(string: "https://api.openai.com/v1/responses")!)
        request.httpMethod = "POST"
        request.timeoutInterval = 45
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONSerialization.data(withJSONObject: [
            "model": "gpt-5.6-luna",
            "input": prompt
        ])

        let configuration = URLSessionConfiguration.ephemeral
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.httpShouldSetCookies = false
        configuration.timeoutIntervalForRequest = 45
        configuration.timeoutIntervalForResource = 60
        let session = URLSession(configuration: configuration)
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw APIError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else { throw APIError.requestFailed(http.statusCode) }

        let envelope = try JSONDecoder().decode(ResponsesEnvelope.self, from: data)
        let outputText = envelope.output
            .flatMap { $0.content ?? [] }
            .first(where: { $0.type == "output_text" })?.text
        guard let outputText else { throw APIError.invalidResponse }

        let cleaned = stripCodeFences(outputText)
        guard let payloadData = cleaned.data(using: .utf8),
              let payload = try? JSONDecoder().decode(MOMPayload.self, from: payloadData),
              !payload.summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw APIError.invalidMOM
        }

        return MOM(
            summary: payload.summary,
            decisions: payload.decisions,
            actions: payload.actions,
            followUps: payload.followUps,
            generatedAt: Date(),
            source: "openai"
        )
    }

    private func stripCodeFences(_ text: String) -> String {
        var value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.hasPrefix("```") {
            value = value.replacingOccurrences(of: "```json", with: "")
            value = value.replacingOccurrences(of: "```JSON", with: "")
            value = value.replacingOccurrences(of: "```", with: "")
        }
        return value.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

private struct ResponsesEnvelope: Decodable {
    let output: [OutputItem]

    struct OutputItem: Decodable {
        let content: [ContentItem]?
    }

    struct ContentItem: Decodable {
        let type: String
        let text: String?
    }
}

private struct MOMPayload: Decodable {
    let summary: String
    let decisions: [String]
    let actions: [String]
    let followUps: [String]
}
