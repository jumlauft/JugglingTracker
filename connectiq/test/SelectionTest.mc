import Toybox.Lang;
import Toybox.Test;

// Executable tests for the startup selection screens, per REQUIREMENTS.md.
// These views hold plain state and can be driven directly; only their drawing
// needs a device context, which these tests never touch.

// ── APP: ball selection ────────────────────────────────────────────────

// APP-3: the picker opens on the smallest supported count, so the common case
// needs no adjustment at all.
(:test)
function app3_ballSelectionStartsAtThree(logger as Logger) as Boolean {
    var v = new BallSelectView(:juggle);
    Test.assertEqualMessage(v.ballCount, 3, "ball selection opens on 3");
    Test.assertEqualMessage(BallSelectView.MIN_BALLS, 3, "3 is the minimum");
    Test.assertEqualMessage(BallSelectView.MAX_BALLS, 9, "9 is the maximum");
    return true;
}

// APP-3: the count covers 3 to 9 and wraps at the top, so reaching 3 again
// never needs seven presses in the other direction.
(:test)
function app3_incrementWalksUpAndWrapsAtNine(logger as Logger) as Boolean {
    var v = new BallSelectView(:juggle);
    for (var expected = 4; expected <= 9; expected++) {
        v.increment();
        Test.assertEqualMessage(v.ballCount, expected, "increment walks up one at a time");
    }
    v.increment();
    Test.assertEqualMessage(v.ballCount, 3, "increment wraps from 9 back to 3");
    return true;
}

// APP-3: and wraps the other way at the bottom.
(:test)
function app3_decrementWrapsAtThree(logger as Logger) as Boolean {
    var v = new BallSelectView(:juggle);
    v.decrement();
    Test.assertEqualMessage(v.ballCount, 9, "decrement wraps from 3 round to 9");
    v.decrement();
    Test.assertEqualMessage(v.ballCount, 8, "and then walks back down");
    return true;
}

// APP-4: the picker carries the chosen mode through to the screen it opens,
// so Record mode cannot silently start a normal juggling session.
(:test)
function app4_ballSelectionRemembersTheChosenMode(logger as Logger) as Boolean {
    Test.assertEqualMessage(new BallSelectView(:juggle).mode, :juggle, "juggle mode is carried");
    Test.assertEqualMessage(new BallSelectView(:record).mode, :record, "record mode is carried");
    return true;
}

// ── APP: mode selection ────────────────────────────────────────────────

// APP-1: the mode screen opens on Juggle, so the normal path is the default
// and Record is only ever reached deliberately.
(:test)
function app1_modeSelectionDefaultsToJuggle(logger as Logger) as Boolean {
    var v = new ModeSelectView();
    Test.assertMessage(!v.isRecordMode, "mode selection opens on Juggle");
    return true;
}

// APP-2: up and down both just flip between the two modes.
(:test)
function app2_toggleFlipsBetweenTheTwoModes(logger as Logger) as Boolean {
    var v = new ModeSelectView();
    v.toggle();
    Test.assertMessage(v.isRecordMode, "toggling from Juggle selects Record");
    v.toggle();
    Test.assertMessage(!v.isRecordMode, "toggling again returns to Juggle");
    return true;
}
