import XCTest
@testable import JugglingCore

/// The `shared/` `RecordingTransferTest` cases.
final class RecordingTransferTests: XCTestCase {
    private var transfer = RecordingTransfer()

    private func start(id: Int64 = 7, samples: Int = 4, chunks: Int = 2) {
        transfer.start([
            "type": "rec_start", "id": id, "balls": 3, "catches": 20, "detected": 19,
            "sampleRate": 25, "samples": samples, "chunks": chunks,
        ])
    }

    @discardableResult
    private func chunk(_ i: Int, id: Int64 = 7) -> RecordingTransfer.Chunk {
        transfer.append([
            "type": "rec_chunk", "id": id, "i": i,
            "x": [i * 10, i * 10 + 1], "y": [0, 0], "z": [1000, 1000],
        ])
    }

    private func end(id: Int64 = 7) -> RecordingTransfer.Recording? {
        transfer.finish(["type": "rec_end", "id": id])
    }

    func testACompleteRunComesOutWhole() {
        start()
        XCTAssertEqual(chunk(0), .added)
        XCTAssertEqual(chunk(1), .added)

        XCTAssertNotNil(end())
        // The transfer is over once rec_end is handled.
        XCTAssertNil(end())
    }

    func testTheFinishedRunCarriesItsHeaderValuesAndSamplesInOrder() throws {
        start()
        chunk(0)
        chunk(1)

        let run = try XCTUnwrap(end())
        XCTAssertEqual(run.id, 7)
        XCTAssertEqual(run.balls, 3)
        XCTAssertEqual(run.catches, 20)
        XCTAssertEqual(run.detected, 19)
        XCTAssertEqual(run.sampleRate, 25)
        XCTAssertEqual(run.x, [0, 1, 10, 11])
    }

    func testARepeatedChunkIsNotAddedTwice() {
        start()
        chunk(0)
        XCTAssertEqual(chunk(0), .repeated)
        chunk(1)

        XCTAssertEqual(end()?.x.count, 4)
    }

    func testALostChunkDropsTheRun() {
        start()
        chunk(0)

        XCTAssertEqual(chunk(2), .gap)
        XCTAssertNil(transfer.runId)
        XCTAssertNil(end())
    }

    func testAChunkOfAnotherRunIsIgnored() {
        start()
        XCTAssertEqual(chunk(0, id: 8), .ignored)
    }

    func testARunMissingChunksOrSamplesDoesNotComeOut() {
        start(samples: 4, chunks: 3)
        chunk(0)
        chunk(1)
        XCTAssertNil(end())

        start(samples: 5, chunks: 2)
        chunk(0)
        chunk(1)
        XCTAssertNil(end())
    }

    func testRecEndForAnotherRunEndsTheTransferWithoutARun() {
        start()
        chunk(0)
        chunk(1)

        XCTAssertNil(end(id: 8))
        XCTAssertNil(end())
    }
}
