import Foundation

enum MomEngine {
    static func generate(from transcript: String) -> MOM {
        let sentences = transcript
            .components(separatedBy: CharacterSet(charactersIn: ".!?\n"))
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }

        let summaryParts = Array(unique(sentences).prefix(4))
        let summary = summaryParts.isEmpty
            ? "TANU captured the meeting, but there was not enough recognized speech to create a detailed summary."
            : summaryParts.joined(separator: ". ") + "."

        let decisions = matches(sentences, terms: ["decided", "agreed", "approved", "confirmed", "finalized", "will go with"])
        let actions = matches(sentences, terms: ["need to", "will send", "will prepare", "will complete", "action", "follow up", "follow-up", "please send", "please prepare", "todo", "to do"])
        let followUps = matches(sentences, terms: ["next meeting", "follow up", "follow-up", "review", "tomorrow", "next week", "next month", "deadline", "due"])

        return MOM(
            summary: summary,
            decisions: Array(decisions.prefix(8)),
            actions: Array(actions.prefix(10)),
            followUps: Array(followUps.prefix(8)),
            generatedAt: Date(),
            source: "device"
        )
    }

    private static func matches(_ sentences: [String], terms: [String]) -> [String] {
        unique(sentences.filter { sentence in
            let lower = sentence.lowercased()
            return terms.contains { lower.contains($0) }
        })
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0.lowercased()).inserted }
    }
}
