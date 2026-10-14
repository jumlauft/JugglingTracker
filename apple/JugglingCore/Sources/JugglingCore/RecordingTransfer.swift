/// Puts one recorded run back together on the phone. The Garmin cannot send
/// a dictionary holding hundreds of numbers, so every watch sends a run as
/// `rec_start`, a series of `rec_chunk` parts, then `rec_end`. Only a
/// complete run comes out of `finish`; the caller writes it and only then
/// acks `rec_end`. A retry from the watch starts again with `rec_start`.
///
/// Same rules as `RecordingTransfer.kt` in the Kotlin `shared/` module.
public struct RecordingTransfer {
    /// A complete run: its id (capture time, epoch seconds) and samples in milli-g.
    public struct Recording: Equatable {
        public let id: Int64
        public let balls: Int
        public let catches: Int
        public let detected: Int
        public let sampleRate: Int
        public let x: [Int]
        public let y: [Int]
        public let z: [Int]
    }

    /// What `append` did with a chunk.
    public enum Chunk: Equatable {
        /// The next chunk of the run; its samples were added.
        case added
        /// A chunk already added, delivered again; the transport may repeat a message.
        case repeated
        /// Not for the run in progress, or not readable.
        case ignored
        /// A chunk was skipped, so the run would be corrupt and was dropped.
        case gap
    }

    private struct Incoming {
        let id: Int64
        let balls: Int
        let catches: Int
        let detected: Int
        let sampleRate: Int
        let totalSamples: Int
        let totalChunks: Int
        var x: [Int] = []
        var y: [Int] = []
        var z: [Int] = []
        var nextChunk = 0
    }

    private var incoming: Incoming?

    public init() {}

    /// The id of the run being received, if any.
    public var runId: Int64? { incoming?.id }

    /// Starts receiving the run a `rec_start` payload announces, in place of any earlier one.
    public mutating func start(_ payload: [String: Any]) {
        guard let id = WatchProtocol.int64(payload["id"]),
              let balls = WatchProtocol.int(payload["balls"]),
              let catches = WatchProtocol.int(payload["catches"])
        else { return }
        incoming = Incoming(
            id: id,
            balls: balls,
            catches: catches,
            detected: WatchProtocol.int(payload["detected"]) ?? 0,
            sampleRate: WatchProtocol.int(payload["sampleRate"]) ?? 25,
            totalSamples: WatchProtocol.int(payload["samples"]) ?? 0,
            totalChunks: WatchProtocol.int(payload["chunks"]) ?? 0
        )
    }

    /// Adds one `rec_chunk` payload to the run in progress.
    @discardableResult
    public mutating func append(_ payload: [String: Any]) -> Chunk {
        guard var run = incoming, WatchProtocol.int64(payload["id"]) == run.id,
              let index = WatchProtocol.int(payload["i"])
        else { return .ignored }
        if index < run.nextChunk { return .repeated }
        if index > run.nextChunk {
            // Dropped rather than written as bad training data: rec_end then
            // goes unacked and the watch offers to send the run again.
            incoming = nil
            return .gap
        }
        guard let xs = WatchProtocol.intList(payload["x"]),
              let ys = WatchProtocol.intList(payload["y"]),
              let zs = WatchProtocol.intList(payload["z"])
        else { return .ignored }
        run.x += xs
        run.y += ys
        run.z += zs
        run.nextChunk = index + 1
        incoming = run
        return .added
    }

    /// Ends the run a `rec_end` payload closes. Returns it when every chunk
    /// and sample arrived, else nil; either way the transfer is over.
    public mutating func finish(_ payload: [String: Any]) -> Recording? {
        guard let run = incoming else { return nil }
        incoming = nil
        guard WatchProtocol.int64(payload["id"]) == run.id,
              run.nextChunk == run.totalChunks,
              run.x.count == run.totalSamples
        else { return nil }
        return Recording(
            id: run.id, balls: run.balls, catches: run.catches, detected: run.detected,
            sampleRate: run.sampleRate, x: run.x, y: run.y, z: run.z
        )
    }
}
