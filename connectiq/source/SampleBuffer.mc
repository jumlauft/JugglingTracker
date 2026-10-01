import Toybox.Lang;

// Fixed-size store for one recorded run of raw accelerometer samples.
//
// The whole buffer is allocated once, up front, as a ByteArray holding each
// sample as three signed 16-bit values (x, y, z in milli-g). That is 6 bytes a
// sample, 18 KB for the 3000-sample cap, in one block. The plain Number arrays
// it replaces cost several times that per entry and grew one add() at a time,
// so a long run churned and fragmented the heap until the low-memory guard
// ended it early. Clearing only resets the count; the block is reused.
class SampleBuffer {
    private const BYTES_PER_SAMPLE = 6;
    private const INT16_MIN = -32768;
    private const INT16_MAX = 32767;

    private var _bytes as ByteArray;
    private var _capacity as Number;
    private var _size as Number;

    public function initialize(capacity as Number) {
        _capacity = capacity;
        _bytes = new [capacity * BYTES_PER_SAMPLE]b;
        _size = 0;
    }

    public function size() as Number {
        return _size;
    }

    public function isFull() as Boolean {
        return _size >= _capacity;
    }

    public function clear() as Void {
        _size = 0;
    }

    // Appends one sample. Returns false, storing nothing, once full. Values
    // beyond the 16-bit range are clamped; the accelerometer never gets there.
    public function add(x as Number, y as Number, z as Number) as Boolean {
        if (_size >= _capacity) {
            return false;
        }
        var offset = _size * BYTES_PER_SAMPLE;
        put(offset, x);
        put(offset + 2, y);
        put(offset + 4, z);
        _size += 1;
        return true;
    }

    // Axis 0, 1 or 2 (x, y, z) of sample i.
    public function get(i as Number, axis as Number) as Number {
        return _bytes.decodeNumber(Lang.NUMBER_FORMAT_SINT16, {
            :offset => i * BYTES_PER_SAMPLE + 2 * axis,
            :endianness => Lang.ENDIAN_LITTLE
        }) as Number;
    }

    private function put(offset as Number, v as Number) as Void {
        if (v < INT16_MIN) {
            v = INT16_MIN;
        } else if (v > INT16_MAX) {
            v = INT16_MAX;
        }
        _bytes.encodeNumber(v, Lang.NUMBER_FORMAT_SINT16, {
            :offset => offset,
            :endianness => Lang.ENDIAN_LITTLE
        });
    }
}
