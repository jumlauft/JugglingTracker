import Toybox.Lang;
import Toybox.Test;

// Executable tests for the Record mode sample buffer, per REQUIREMENTS.md.

// REC-2: samples come back exactly as stored, including negative values and
// the 16-bit limits, and the buffer refuses samples past its capacity.
(:test)
function rec2_sampleBufferRoundTripsAndStopsAtCapacity(logger as Logger) as Boolean {
    var buf = new SampleBuffer(3);
    Test.assertEqualMessage(buf.size(), 0, "starts empty");
    Test.assert(buf.add(12, -980, 1003));
    Test.assert(buf.add(-32768, 32767, 0));
    Test.assert(buf.add(-1, 1, -4096));
    Test.assertMessage(buf.isFull(), "full at capacity");
    Test.assertMessage(!buf.add(5, 5, 5), "a fourth sample is refused");
    Test.assertEqualMessage(buf.size(), 3, "refused sample not counted");

    Test.assertEqual(buf.get(0, 0), 12);
    Test.assertEqual(buf.get(0, 1), -980);
    Test.assertEqual(buf.get(0, 2), 1003);
    Test.assertEqual(buf.get(1, 0), -32768);
    Test.assertEqual(buf.get(1, 1), 32767);
    Test.assertEqual(buf.get(1, 2), 0);
    Test.assertEqual(buf.get(2, 0), -1);
    Test.assertEqual(buf.get(2, 1), 1);
    Test.assertEqual(buf.get(2, 2), -4096);
    return true;
}

// REC-2: out-of-range values clamp instead of wrapping, and clearing reuses
// the same block from the start.
(:test)
function rec2_sampleBufferClampsAndClears(logger as Logger) as Boolean {
    var buf = new SampleBuffer(2);
    buf.add(40000, -40000, 7);
    Test.assertEqual(buf.get(0, 0), 32767);
    Test.assertEqual(buf.get(0, 1), -32768);
    buf.clear();
    Test.assertEqualMessage(buf.size(), 0, "clear empties it");
    Test.assertMessage(!buf.isFull(), "and makes room again");
    buf.add(-5, 6, -7);
    Test.assertEqual(buf.get(0, 0), -5);
    Test.assertEqual(buf.get(0, 2), -7);
    return true;
}
