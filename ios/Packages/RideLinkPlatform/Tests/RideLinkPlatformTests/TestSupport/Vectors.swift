import Foundation

/// Loads shared golden vectors from `protocol/vectors/` at the repo root.
///
/// A deliberate twin of `RideLinkCoreTests`' loader: both are a few lines of test plumbing, and one
/// package's test target cannot import another's. CLAUDE.md's "Shared protocol vectors — not
/// optional" is about the *vector files* being shared, which they are; this is the reader.
enum Vectors {
    enum VectorsError: Error {
        case repoRootNotFound
    }

    /// Walks upward from this source file's own location (captured at compile time via `#filePath`)
    /// until it finds a directory containing `protocol/vectors`.
    private static func repoRoot(from callerFilePath: String = #filePath) throws -> URL {
        var dir = URL(fileURLWithPath: callerFilePath).deletingLastPathComponent()
        let fileManager = FileManager.default
        while true {
            let candidate = dir.appendingPathComponent("protocol/vectors")
            if fileManager.fileExists(atPath: candidate.path) { return dir }
            let parent = dir.deletingLastPathComponent()
            if parent.path == dir.path { throw VectorsError.repoRootNotFound }
            dir = parent
        }
    }

    static func loadJSON(_ relativePath: String) throws -> Any {
        let root = try repoRoot()
        let url = root.appendingPathComponent("protocol/vectors").appendingPathComponent(relativePath)
        return try JSONSerialization.jsonObject(with: Data(contentsOf: url), options: [.fragmentsAllowed])
    }
}
