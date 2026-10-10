import Foundation
import XCTest

/// A fresh folder under the test run's temporary directory, removed when the test ends.
func makeTempDirectory(_ test: XCTestCase) -> URL {
    let url = FileManager.default.temporaryDirectory
        .appendingPathComponent("JugglingTrackerTests-\(UUID().uuidString)", isDirectory: true)
    try! FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    test.addTeardownBlock { try? FileManager.default.removeItem(at: url) }
    return url
}

/// The repository root, found by walking up from this file until
/// `connectiq/data` appears. The simulator reads the Mac's file system, so
/// the tests can replay the same recordings as the other test suites.
let repoRoot: URL = {
    var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
    while dir.path != "/" {
        if FileManager.default.fileExists(atPath: dir.appendingPathComponent("connectiq/data").path) {
            return dir
        }
        dir = dir.deletingLastPathComponent()
    }
    fatalError("connectiq/data not found above \(#filePath)")
}()

/// The x, y, z milli-g samples of one recording in connectiq/data.
func loadRun(_ runId: String) -> [(Int, Int, Int)] {
    let url = repoRoot.appendingPathComponent("connectiq/data/\(runId).csv")
    let text = try! String(contentsOf: url, encoding: .utf8)
    return text.components(separatedBy: .newlines)
        .map { $0.trimmingCharacters(in: .whitespaces) }
        .filter { !$0.isEmpty && !$0.hasPrefix("#") && !$0.hasPrefix("x,") }
        .compactMap { line in
            let parts = line.split(separator: ",").prefix(3).map { Int($0.trimmingCharacters(in: .whitespaces)) }
            guard parts.count == 3, let x = parts[0], let y = parts[1], let z = parts[2] else { return nil }
            return (x, y, z)
        }
}
