import XCTest
@testable import WatchLogic

/// Start-up flow, `JugglingTrackerApp.mc`, `ModeSelectView.mc`, `BallSelectView.mc`.
final class AppNavigatorTests: XCTestCase {
    private let scheduler = FakeScheduler()
    private let effects = FakeEffects()
    private var trackerBalls: [Int] = []
    private var recordingBalls: [Int] = []

    private func navigator(enableRecordingMode: Bool = AppNavigator.enableRecordingMode) -> AppNavigator {
        AppNavigator(
            newTracker: { [unowned self] balls in
                self.trackerBalls.append(balls)
                return TrackerSession(
                    ballCount: balls, link: FakePhoneLink(), scheduler: self.scheduler, clock: self.scheduler,
                    effects: self.effects
                )
            },
            newRecording: { [unowned self] balls in
                self.recordingBalls.append(balls)
                return RecordingSession(ballCount: balls, link: FakePhoneLink(), scheduler: self.scheduler, effects: self.effects)
            },
            effects: effects,
            enableRecordingMode: enableRecordingMode
        )
    }

    private func ballCount(_ nav: AppNavigator) -> Int? {
        if case let .ballSelect(_, balls) = nav.screen { return balls }
        return nil
    }

    func testAPP1OpensOnTheModeScreenDefaultingToJuggle() {
        XCTAssertEqual(navigator().screen, .modeSelect(isRecordMode: false))
    }

    func testAPP1RecordModeShipsEnabledOnPurpose() {
        XCTAssertTrue(AppNavigator.enableRecordingMode)
    }

    func testAPP1WithRecordModeDisabledTheAppStartsOnBallSelection() {
        XCTAssertEqual(navigator(enableRecordingMode: false).screen, .ballSelect(mode: .juggle, ballCount: 3))
    }

    func testAPP2UpAndDownBothToggleTheModeAndStartConfirms() {
        let nav = navigator()
        nav.onUp()
        XCTAssertEqual(nav.screen, .modeSelect(isRecordMode: true))
        nav.onDown()
        XCTAssertEqual(nav.screen, .modeSelect(isRecordMode: false))
        nav.onDown()
        nav.onStart()
        XCTAssertEqual(nav.screen, .ballSelect(mode: .record, ballCount: 3))
    }

    func testAPP3BallSelectionStartsAtThreeAndWrapsBothWays() {
        let nav = navigator()
        nav.onStart()
        XCTAssertEqual(ballCount(nav), 3)
        nav.onDown()
        XCTAssertEqual(ballCount(nav), 9)
        nav.onUp()
        XCTAssertEqual(ballCount(nav), 3)
        for _ in 0..<6 { nav.onUp() }
        XCTAssertEqual(ballCount(nav), 9)
    }

    func testAPP4StartOpensTheTrackerForTheChosenModeWithTheChosenBalls() {
        let juggle = navigator()
        juggle.onStart()
        juggle.onUp()
        juggle.onUp()
        juggle.onStart()
        guard case .tracker = juggle.screen else { return XCTFail("expected the tracker") }
        XCTAssertEqual(trackerBalls, [5])

        let record = navigator()
        record.onUp()
        record.onStart()
        record.onStart()
        guard case .recording = record.screen else { return XCTFail("expected the record screen") }
        XCTAssertEqual(recordingBalls, [3])
    }

    func testBackOnTheModeScreenLeavesTheApp() {
        let nav = navigator()
        nav.onBack()
        XCTAssertEqual(effects.exits, 1)
    }

    func testAPP5BackOnBallSelectionReturnsToTheModeScreenWithTheModeKept() {
        let nav = navigator()
        nav.onUp()
        nav.onStart()
        nav.onBack()
        XCTAssertEqual(nav.screen, .modeSelect(isRecordMode: true))
        XCTAssertEqual(effects.exits, 0)
    }

    func testAPP5WithRecordModeDisabledBackOnBallSelectionLeavesTheApp() {
        let nav = navigator(enableRecordingMode: false)
        nav.onBack()
        XCTAssertEqual(effects.exits, 1)
    }

    func testREC11BackOnTheIdleRecordScreenReturnsToBallSelection() {
        let nav = navigator()
        nav.onUp()
        nav.onStart()
        nav.onUp()
        nav.onStart()
        XCTAssertTrue(nav.screen.isTracking)
        nav.onBack()
        XCTAssertEqual(nav.screen, .ballSelect(mode: .record, ballCount: 4))
        XCTAssertEqual(effects.exits, 0)
    }

    func testBackWhileRecordingStillAsksBeforeQuitting() {
        let nav = navigator()
        nav.onUp()
        nav.onStart()
        nav.onStart()
        nav.onStart() // start a run
        nav.onBack()
        guard case let .recording(session) = nav.screen else { return XCTFail("expected the record screen") }
        XCTAssertEqual(session.state.menu?.title, "Quit? Lose run")
        XCTAssertEqual(effects.exits, 0)
    }

    func testBackOnTheTrackerGoesToTheSessionNeverToTheSystem() {
        let nav = navigator()
        nav.onStart()
        nav.onStart()
        nav.onBack()
        XCTAssertEqual(effects.exits, 0)
        nav.onStart() // START/STOP on the tracker
        guard case let .tracker(tracker) = nav.screen else { return XCTFail("expected the tracker") }
        XCTAssertEqual(tracker.state.menu?.title, "End session?")
        nav.onBack() // backs out of the menu
        XCTAssertNil(tracker.state.menu)
        XCTAssertEqual(effects.exits, 0)
    }

    func testRestartReturnsToTheFirstScreen() {
        let nav = navigator()
        nav.onStart()
        nav.onStart()
        XCTAssertTrue(nav.screen.isTracking)
        nav.restart()
        XCTAssertEqual(nav.screen, .modeSelect(isRecordMode: false))
    }
}
