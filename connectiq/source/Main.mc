import Toybox.Application;
import Toybox.Communications;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Sensor;
import Toybox.System;
import Toybox.Timer;
import Toybox.WatchUi;

const SEND_INTERVAL_MS = 250;

class MainView extends WatchUi.View {
    private var _statusText as String;

    public function initialize() {
        View.initialize();
        _statusText = "Waiting for phone connection...";
    }

    public function setStatus(status as String) as Void {
        _statusText = status;
        WatchUi.requestUpdate();
    }

    public function onUpdate(dc as Dc) as Void {
        dc.clear();
        dc.drawText(dc.getWidth() / 2, dc.getHeight() / 2, Graphics.FONT_MEDIUM, _statusText, Graphics.TEXT_JUSTIFY_CENTER);
    }
}

class MainApp extends Application.AppBase {
    private var _view as MainView;
    private var _sendTimer as Timer.Timer?;
    private var _sensorSamples as Array;

    public function initialize() {
        AppBase.initialize();
        _sensorSamples = [];
        _view = new $.MainView();
        _sendTimer = new Timer.Timer();
    }

    public function onStart(state as Dictionary?) as Void {
        AppBase.onStart(state);
        
        if (Communications has :registerForPhoneAppMessages) {
            Communications.registerForPhoneAppMessages(method(:onPhoneMessage));
        }
        
        var sensorOptions = {
            :period => 1,
            :accelerometer => {:enabled => true, :sampleRate => 20}
        };
        
        try {
            Sensor.registerSensorDataListener(method(:onSensorData), sensorOptions);
        } catch (e) {
            System.println("Sensor registration error: " + e.getErrorMessage());
            _view.setStatus("Sensor error");
            return;
        }
        
        _sendTimer.start(method(:onSendTimer), SEND_INTERVAL_MS, true);
        _view.setStatus("Streaming, waiting for samples...");
    }

    public function onStop(state as Dictionary?) as Void {
        if (_sendTimer != null) {
            _sendTimer.stop();
        }
        
        try {
            Sensor.unregisterSensorDataListener();
        } catch (e) {
            System.println("Sensor unregister error");
        }
        
        if (Communications has :registerForPhoneAppMessages) {
            Communications.registerForPhoneAppMessages(null);
        }
        
        AppBase.onStop(state);
    }

    private function onSensorData(sensorData as Sensor.SensorData) as Void {
        var accelData = sensorData.accelerometerData;
        if (accelData != null) {
            var x = accelData.x;
            var y = accelData.y;
            var z = accelData.z;
            
            _sensorSamples.add({
                :type => "accel",
                :x => x[0],
                :y => y[0],
                :z => z[0],
                :timestamp => System.getClockTime()
            });
        }
    }

    private function onSendTimer() as Void {
        if (_sensorSamples.size() == 0) {
            _view.setStatus("Waiting for samples...");
            return;
        }

        var message = "IMU samples: " + _sensorSamples.size().toString();
        _sensorSamples = [];
        _view.setStatus("Sending IMU batch");

        var listener = new CommListener(_view);
        Communications.transmit(message, null, listener);
    }

    private function onPhoneMessage(msg as Communications.PhoneAppMessage) as Void {
        var data = msg.data;
        if (data != null && data instanceof Dictionary) {
            if (data has :command) {
                var command = data[:command];
                if (command == "stop" && _sendTimer != null) {
                    _view.setStatus("Stop command received");
                    _sendTimer.stop();
                } else if (command == "start" && _sendTimer != null) {
                    _view.setStatus("Start command received");
                    _sendTimer.start(method(:onSendTimer), SEND_INTERVAL_MS, true);
                }
            }
        }
    }

    public function getInitialView() as [Views] or [Views, InputDelegates] {
        return [new $.MainView()];
    }
}

class CommListener extends Communications.ConnectionListener {
    private var _view as MainView;

    public function initialize(view as MainView) {
        Communications.ConnectionListener.initialize();
        _view = view;
    }

    public function onComplete() as Void {
        _view.setStatus("IMU batch sent");
    }

    public function onError() as Void {
        _view.setStatus("Send failed");
    }
}
