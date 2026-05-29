import Toybox.Application;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;
import Toybox.Sensor;
import Toybox.Communications;

class MainView extends WatchUi.View {
    private const SAMPLE_RATE = 25; // Hz, supported by the FR245 accelerometer
    private const PERIOD_SECONDS = 1; // seconds of buffering per callback

    private var _sensorData as String;
    private var _listener as CommListener;
    private var _sending as Boolean;

    public function initialize() {
        WatchUi.View.initialize();
        _sensorData = "No data";
        _listener = new CommListener(self);
        _sending = false;

        try {
            var options = {
                :period => PERIOD_SECONDS,
                :accelerometer => {
                    :enabled => true,
                    :sampleRate => SAMPLE_RATE
                }
            };
            Sensor.registerSensorDataListener(self.method(:onSensor), options);
        } catch (ex) {
            _sensorData = "Sensor Error";
        }
    }

    public function onUpdate(dc as Dc) as Void {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        dc.drawText(
            dc.getWidth() / 2,
            dc.getHeight() / 2,
            Graphics.FONT_MEDIUM,
            _sensorData,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER
        );
    }

    public function onSensor(sensorData as Sensor.SensorData) as Void {
        var accel = sensorData.accelerometerData;
        if (accel == null) {
            return;
        }

        var xs = accel.x;
        var ys = accel.y;
        var zs = accel.z;
        if (xs == null || xs.size() == 0) {
            return;
        }

        var count = xs.size();
        var lastIndex = count - 1;
        _sensorData = Lang.format("Acc: $1$, $2$, $3$", [xs[lastIndex], ys[lastIndex], zs[lastIndex]]);
        WatchUi.requestUpdate();

        // Only one message may be in flight at a time. Skip this batch if the
        // previous transmit has not completed yet to avoid overflowing the
        // Connect IQ messaging channel.
        if (_sending) {
            return;
        }

        // Send the batched samples as a structured payload instead of a
        // preformatted string, so the phone can parse numeric values directly.
        var payload = {
            "rate" => SAMPLE_RATE,
            "x" => xs,
            "y" => ys,
            "z" => zs
        };

        try {
            _sending = true;
            Communications.transmit(payload, null, _listener);
        } catch (ex) {
            _sending = false;
        }
    }

    public function onTransmitDone() as Void {
        _sending = false;
    }

    public function onHide() as Void {
        Sensor.unregisterSensorDataListener();
    }
}

class MainDelegate extends WatchUi.InputDelegate {
    public function initialize() {
        WatchUi.InputDelegate.initialize();
    }
}

class CommListener extends Communications.ConnectionListener {
    private var _view as MainView;

    function initialize(view as MainView) {
        Communications.ConnectionListener.initialize();
        _view = view;
    }

    function onComplete() {
        _view.onTransmitDone();
    }

    function onError() {
        _view.onTransmitDone();
    }
}

class JugglingTrackerApp extends Application.AppBase {
    function initialize() {
        Application.AppBase.initialize();
    }

    function onStart(state as Dictionary?) as Void {
    }

    function onStop(state as Dictionary?) as Void {
    }

    function getInitialView() as [Views] or [Views, InputDelegates] {
        return [new MainView(), new MainDelegate()];
    }
}
