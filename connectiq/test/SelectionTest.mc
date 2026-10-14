import Toybox.Lang;
import Toybox.Test;

// Executable tests for the start-up flow, per REQUIREMENTS.md. AppNavigator
// holds the mode and ball selection and routes the buttons; the views only
// draw it, so these drive the navigator directly with a fake host.

function newNavigator() as AppNavigator {
    return new AppNavigator(new FakeHost(1700000000), true);
}

// ── APP: ball selection ────────────────────────────────────────────────

// APP-3: the picker opens on the smallest supported count, so the common case
// needs no adjustment at all.
(:test)
function app3_ballSelectionStartsAtThree(logger as Logger) as Boolean {
    var nav = newNavigator();
    nav.onStart();
    Test.assertEqualMessage(nav.screen, AppNavigator.SCREEN_BALL_SELECT, "START opens ball selection");
    Test.assertEqualMessage(nav.ballCount, 3, "ball selection opens on 3");
    Test.assertEqualMessage(AppNavigator.MIN_BALLS, 3, "3 is the minimum");
    Test.assertEqualMessage(AppNavigator.MAX_BALLS, 9, "9 is the maximum");
    return true;
}

// APP-3: the count covers 3 to 9 and wraps at the top, so reaching 3 again
// never needs seven presses in the other direction. (The touch watches stop
// at the ends instead; see CLAUDE.md.)
(:test)
function app3_incrementWalksUpAndWrapsAtNine(logger as Logger) as Boolean {
    var nav = newNavigator();
    nav.onStart();
    for (var expected = 4; expected <= 9; expected++) {
        nav.onUp();
        Test.assertEqualMessage(nav.ballCount, expected, "UP walks up one at a time");
    }
    nav.onUp();
    Test.assertEqualMessage(nav.ballCount, 3, "UP wraps from 9 back to 3");
    return true;
}

// APP-3: and wraps the other way at the bottom.
(:test)
function app3_decrementWrapsAtThree(logger as Logger) as Boolean {
    var nav = newNavigator();
    nav.onStart();
    nav.onDown();
    Test.assertEqualMessage(nav.ballCount, 9, "DOWN wraps from 3 round to 9");
    nav.onDown();
    Test.assertEqualMessage(nav.ballCount, 8, "and then walks back down");
    return true;
}

// APP-4: the picker carries the chosen mode through to the screen it opens,
// so Record mode cannot silently start a normal juggling session.
(:test)
function app4_ballSelectionRemembersTheChosenMode(logger as Logger) as Boolean {
    var juggle = newNavigator();
    juggle.onStart();
    Test.assertEqualMessage(juggle.mode, :juggle, "juggle mode is carried");
    juggle.onStart();
    Test.assertEqualMessage(juggle.screen, AppNavigator.SCREEN_TRACKER, "Juggle opens the tracker");
    Test.assertMessage(juggle.tracker != null && juggle.recording == null, "with a tracker session");

    var record = newNavigator();
    record.onDown();
    record.onStart();
    Test.assertEqualMessage(record.mode, :record, "record mode is carried");
    record.onStart();
    Test.assertEqualMessage(record.screen, AppNavigator.SCREEN_RECORDING, "Record opens the record screen");
    Test.assertMessage(record.recording != null && record.tracker == null, "with a recording session");
    return true;
}

// APP-5: BACK on ball selection steps back to the mode screen, keeping the
// mode; when the app starts on ball selection (Record mode off), BACK is
// left to the system, which closes the app.
(:test)
function app5_ballSelectionStepsBackOnlyWhenReachedFromModeSelect(logger as Logger) as Boolean {
    var nav = newNavigator();
    nav.onDown();
    nav.onStart();
    Test.assertMessage(nav.onBack(), "BACK on ball selection is used");
    Test.assertEqualMessage(nav.screen, AppNavigator.SCREEN_MODE_SELECT, "it returns to the mode screen");
    Test.assertMessage(nav.isRecordMode, "with Record still chosen");
    Test.assertMessage(!nav.onBack(), "BACK on the mode screen closes the app");

    var direct = new AppNavigator(new FakeHost(1700000000), false);
    Test.assertEqualMessage(direct.screen, AppNavigator.SCREEN_BALL_SELECT, "without Record mode the app opens on ball selection");
    Test.assertEqualMessage(direct.mode, :juggle, "for Juggle mode");
    Test.assertMessage(!direct.onBack(), "a picker the app starts on has no screen to step back to");
    return true;
}

// ── APP: mode selection ────────────────────────────────────────────────

// APP-1: the app opens on the mode screen with Juggle chosen, so the normal
// path is the default and Record is only ever reached deliberately. Record
// mode ships enabled: it is how the labelled corpus grows.
(:test)
function app1_modeSelectionDefaultsToJuggle(logger as Logger) as Boolean {
    Test.assertMessage(AppNavigator.ENABLE_RECORDING_MODE, "Record mode ships enabled");
    var nav = newNavigator();
    Test.assertEqualMessage(nav.screen, AppNavigator.SCREEN_MODE_SELECT, "the app opens on the mode screen");
    Test.assertMessage(!nav.isRecordMode, "mode selection opens on Juggle");
    return true;
}

// APP-2: up and down both just flip between the two modes.
(:test)
function app2_toggleFlipsBetweenTheTwoModes(logger as Logger) as Boolean {
    var nav = newNavigator();
    nav.onUp();
    Test.assertMessage(nav.isRecordMode, "UP from Juggle selects Record");
    nav.onUp();
    Test.assertMessage(!nav.isRecordMode, "UP again returns to Juggle");
    nav.onDown();
    Test.assertMessage(nav.isRecordMode, "DOWN flips it too");
    return true;
}
