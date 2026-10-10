import XCTest
@testable import JugglingTracker

final class RecordingStoreTests: XCTestCase {
    private var store: RecordingStore!
    private let jonas = Juggler(name: "Jonas Umlauft", hand: Juggler.left, firstThrow: Juggler.right)
    private let berlin = TimeZone(identifier: "Europe/Berlin")!

    override func setUp() {
        super.setUp()
        store = RecordingStore(directory: makeTempDirectory(self).appendingPathComponent("recordings"))
    }

    func testWritesTheAndroidFormat() throws {
        let url = try XCTUnwrap(store.saveRecording(
            balls: 3, catches: 12, detected: 11, sampleRate: 100, timestamp: 1_790_081_282,
            x: [1, 2], y: [3, 4], z: [5, 6], source: RecordingStore.sourcePhone, juggler: jonas
        ))
        let runId = RecordingStore.runId(timestamp: 1_790_081_282)
        XCTAssertEqual(url.lastPathComponent, "\(runId).csv")
        XCTAssertEqual(try String(contentsOf: url, encoding: .utf8), """
            # run=\(runId),timestamp=1790081282,balls=3,catches=12,sampleRate=100,units=milli_g,source=phone,countMode=watch_hand,detectedAtCapture=11,juggler=Jonas Umlauft,hand=left,firstThrow=right
            x,y,z
            1,3,5
            2,4,6

            """)
    }

    func testRunIdIsTheCaptureTimeInTheLocalZone() {
        XCTAssertEqual(RecordingStore.runId(timestamp: 1_790_081_282, timeZone: berlin), "20260922_144802")
    }

    func testListReadsBackWhatWasSaved() {
        store.saveRecording(balls: 5, catches: 30, detected: 28, sampleRate: 25, timestamp: 1_790_000_000,
                            x: [1, 2, 3], y: [1, 2, 3], z: [1, 2, 3], source: RecordingStore.sourceWatch)
        store.saveRecording(balls: 3, catches: 9, detected: 9, sampleRate: 100, timestamp: 1_790_000_100,
                            x: [1], y: [1], z: [1], source: RecordingStore.sourcePhone, juggler: jonas)

        // A second store reads the files rather than its cache.
        let list = RecordingStore(directory: store.directory).listRecordings()
        XCTAssertEqual(list.map(\.timestamp), [1_790_000_100, 1_790_000_000])
        XCTAssertEqual(list[0].juggler, jonas)
        XCTAssertFalse(list[0].fromWatch)
        XCTAssertEqual(list[1].samples, 3)
        XCTAssertEqual(list[1].durationSeconds, 0.12, accuracy: 0.0001)
        XCTAssertNil(list[1].juggler)
        XCTAssertEqual(store.listRecordings(), list)
    }

    func testHeaderSafeKeepsTheHeaderParseable() {
        XCTAssertEqual(RecordingStore.headerSafe(" Anna,  Maria=#x\n "), "Anna Maria x")
    }

    func testWithJugglerReplacesAnEarlierTag() {
        let header = "# run=1,balls=3,juggler=Old,hand=right,firstThrow=left,units=milli_g"
        XCTAssertEqual(
            RecordingStore.withJuggler(header, jonas),
            "# run=1,balls=3,units=milli_g,juggler=Jonas Umlauft,hand=left,firstThrow=right"
        )
    }

    func testExportTagsOnlyRunsWithoutAJugglerAndDropsExtraColumns() throws {
        try FileManager.default.createDirectory(at: store.directory, withIntermediateDirectories: true)
        // A run from the gyroscope experiment: six columns, no juggler.
        try Data("# run=20260601_100000,timestamp=1,balls=3,catches=5\nx,y,z,gx,gy,gz\n1,2,3,0,0,0\n".utf8)
            .write(to: store.directory.appendingPathComponent("20260601_100000.csv"))
        let tagged = try XCTUnwrap(store.saveRecording(
            balls: 3, catches: 5, detected: 5, sampleRate: 25, timestamp: 1_790_000_000,
            x: [7], y: [8], z: [9], source: RecordingStore.sourceWatch,
            juggler: Juggler(name: "Moritz", hand: Juggler.left, firstThrow: Juggler.right)
        ))

        let old = store.normalizedCSV(store.directory.appendingPathComponent("20260601_100000.csv"), fallback: jonas)
        XCTAssertEqual(old, "# run=20260601_100000,timestamp=1,balls=3,catches=5,juggler=Jonas Umlauft,hand=left,firstThrow=right\nx,y,z\n1,2,3\n")
        XCTAssertTrue(store.normalizedCSV(tagged, fallback: jonas).contains("juggler=Moritz"))
    }

    func testTheZipHoldsEveryRun() throws {
        store.saveRecording(balls: 3, catches: 1, detected: 1, sampleRate: 25, timestamp: 1_790_000_000,
                            x: [1], y: [2], z: [3], source: RecordingStore.sourceWatch)
        store.saveRecording(balls: 3, catches: 1, detected: 1, sampleRate: 25, timestamp: 1_790_000_100,
                            x: [1], y: [2], z: [3], source: RecordingStore.sourceWatch)
        let zip = store.exportZip(fallback: nil)
        // Two local headers, two central directory entries, one end record.
        XCTAssertEqual(zip.prefix(4), Data([0x50, 0x4B, 0x03, 0x04]))
        XCTAssertEqual(zip.suffix(22).prefix(4), Data([0x50, 0x4B, 0x05, 0x06]))
        XCTAssertEqual(zip.suffix(22)[zip.count - 22 + 10], 2)
    }

    func testClearAllRemovesEveryRun() {
        store.saveRecording(balls: 3, catches: 1, detected: 1, sampleRate: 25, timestamp: 1_790_000_000,
                            x: [1], y: [2], z: [3], source: RecordingStore.sourceWatch)
        store.clearAll()
        XCTAssertEqual(store.recordingCount, 0)
        XCTAssertTrue(store.listRecordings().isEmpty)
    }
}

final class ZipWriterTests: XCTestCase {
    func testCrc32MatchesTheStandardCheckValue() {
        XCTAssertEqual(ZipWriter.crc32(Data("123456789".utf8)), 0xCBF4_3926)
    }

    func testAStoredEntryHoldsItsBytesRightAfterItsHeaderAndName() {
        // Local header (30) + name + data + central entry (46) + name + end record (22).
        var writer = ZipWriter()
        writer.add(name: "a.csv", data: Data("hello".utf8))
        let zip = writer.finish()
        XCTAssertEqual(zip.subdata(in: 30..<35), Data("a.csv".utf8))
        XCTAssertEqual(zip.subdata(in: 35..<40), Data("hello".utf8))
        XCTAssertEqual(zip.count, 30 + 5 + 5 + 46 + 5 + 22)
    }
}
