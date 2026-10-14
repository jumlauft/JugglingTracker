import XCTest
@testable import JugglingCore

/// The `shared/` `RecordingHeaderTest` cases.
final class RecordingHeaderTests: XCTestCase {
    private let ada = Juggler(name: "Ada", hand: Juggler.left, firstThrow: Juggler.right)

    func testTheHeaderLineCarriesEveryFieldThenTheJuggler() {
        XCTAssertEqual(
            RecordingHeader.line(
                runId: "20260922_144802", timestamp: 1_790_081_282, balls: 3, catches: 89, sampleRate: 25,
                source: "watch", detected: 88, juggler: ada
            ),
            "# run=20260922_144802,timestamp=1790081282,balls=3,catches=89,sampleRate=25,units=milli_g," +
                "source=watch,countMode=watch_hand,detectedAtCapture=88,juggler=Ada,hand=left,firstThrow=right"
        )
    }

    func testTheRunIdIsTheCaptureTimeInTheGivenTimeZone() {
        let berlin = TimeZone(identifier: "Europe/Berlin")!
        XCTAssertEqual(RecordingHeader.runId(timestamp: 1_790_081_282, timeZone: berlin), "20260922_144802")
    }

    func testAHeaderReadsBackItsJuggler() {
        let line = RecordingHeader.line(
            runId: "r", timestamp: 1, balls: 3, catches: 1, sampleRate: 25, source: "phone", detected: 1, juggler: ada
        )
        let fields = RecordingHeader.fields(line)

        XCTAssertEqual(fields["balls"], "3")
        XCTAssertEqual(RecordingHeader.juggler(of: fields), ada)
    }

    func testAHeaderWithoutAllThreeJugglerFieldsNamesNoJuggler() {
        XCTAssertNil(RecordingHeader.juggler(of: RecordingHeader.fields("# run=r,juggler=Ada,hand=left")))
        XCTAssertNil(RecordingHeader.juggler(of: RecordingHeader.fields("# run=r,juggler=Ada,hand=up,firstThrow=left")))
    }

    func testWithJugglerReplacesAnEarlierTag() {
        let header = "# run=1,balls=3,juggler=Old,hand=right,firstThrow=left,units=milli_g"
        XCTAssertEqual(
            RecordingHeader.withJuggler(header, ada),
            "# run=1,balls=3,units=milli_g,juggler=Ada,hand=left,firstThrow=right"
        )
    }

    func testANameCannotBreakTheHeaderFormat() {
        XCTAssertEqual(RecordingHeader.headerSafe("Doe, John"), "Doe John")
        XCTAssertEqual(RecordingHeader.headerSafe(" a=b#\nc "), "a b c")
    }
}
