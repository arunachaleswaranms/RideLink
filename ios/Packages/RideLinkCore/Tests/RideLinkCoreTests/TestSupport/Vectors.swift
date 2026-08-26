import Foundation

/// Loads shared golden vectors from `protocol/vectors/` at the repo root. The Swift tests must
/// execute these files directly rather than duplicating vector data as Swift literals
/// (CLAUDE.md "Shared protocol vectors — not optional").
enum Vectors {
    enum VectorsError: Error {
        case repoRootNotFound
    }

    /// Walks upward from this source file's own location (captured at compile time via
    /// `#filePath`) until it finds a directory containing `protocol/vectors` — robust against
    /// exactly where in the package tree the test file lives, with no build-tool wiring needed
    /// (unlike the Android side, which passes the path via a Gradle system property).
    private static func repoRoot(from callerFilePath: String = #filePath) throws -> URL {
        var dir = URL(fileURLWithPath: callerFilePath).deletingLastPathComponent()
        let fileManager = FileManager.default
        while true {
            let candidate = dir.appendingPathComponent("protocol/vectors")
            if fileManager.fileExists(atPath: candidate.path) {
                return dir
            }
            let parent = dir.deletingLastPathComponent()
            if parent.path == dir.path {
                throw VectorsError.repoRootNotFound
            }
            dir = parent
        }
    }

    static func loadJSON(_ relativePath: String) throws -> Any {
        let root = try repoRoot()
        let url = root.appendingPathComponent("protocol/vectors").appendingPathComponent(relativePath)
        let data = try Data(contentsOf: url)
        return try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
    }
}
