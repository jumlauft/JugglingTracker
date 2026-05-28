import Toybox.Lang as Lang
import Toybox.System as System
import Toybox.Timer as Timer
import Toybox.WatchUi as WatchUi
import Toybox.Communications as Communications
import Toybox.Sensors as Sensors

const SEND_INTERVAL_MS = 250
const SAMPLE_RATE_HZ = 20

class MainApp extends WatchUi.Application {
    hidden var sendTimer
    hidden var accelSamples = []
    hidden var gyroSamples = []
    hidden var statusText = "Waiting for phone connection..."

    function initialize() {
        WatchUi.Application.initialize()
    }

    function onStart() {
        WatchUi.Application.onStart()
        Communications.addAppMessageListener(method(:onPhoneMessage))
        if (Sensors.isAccelerometerAvailable()) {
            Sensors.registerAccelerometerListener(method(:onAccelerometer), SAMPLE_RATE_HZ)
        }
        if (Sensors.isGyroscopeAvailable()) {
            Sensors.registerGyroscopeListener(method(:onGyroscope), SAMPLE_RATE_HZ)
        }
        sendTimer = Timer.Timer.create(method(:onSendTimer), SEND_INTERVAL_MS)
        sendTimer.start()
    }

    function onStop() {
        if (sendTimer != null) {
            sendTimer.stop()
        }
        Sensors.unregisterAccelerometerListener(method(:onAccelerometer))
        Sensors.unregisterGyroscopeListener(method(:onGyroscope))
        Communications.removeAppMessageListener(method(:onPhoneMessage))
        WatchUi.Application.onStop()
    }

    function onAccelerometer(sample) {
        accelSamples += [{
            "t": sample.timestamp,
            "x": sample.x,
            "y": sample.y,
            "z": sample.z
        }]
    }

    function onGyroscope(sample) {
        gyroSamples += [{
            "t": sample.timestamp,
            "x": sample.x,
            "y": sample.y,
            "z": sample.z
        }]
    }

    function onSendTimer() {
        if (!Communications.isPhoneConnected()) {
            statusText = "Phone disconnected"
            return
        }

        if (accelSamples.size() == 0 && gyroSamples.size() == 0) {
            statusText = "Streaming, waiting for samples..."
            return
        }

        var payload = {
            "type": "imu",
            "accel": accelSamples,
            "gyro": gyroSamples,
            "timestamp": System.getClockTime()
        }

        accelSamples = []
        gyroSamples = []
        statusText = "Sending IMU batch"

        Communications.sendAppMessage(payload, method(:onAppMessageSent), method(:onAppMessageFailed))
    }

    function onAppMessageSent() {
        statusText = "IMU batch sent"
    }

    function onAppMessageFailed(error) {
        statusText = "Send failed: " + error
    }

    function onPhoneMessage(message) {
        if (message.containsKey("command")) {
            var command = message.get("command")
            if (command == "stop") {
                statusText = "Stop command received"
                if (sendTimer != null) sendTimer.stop()
            } else if (command == "start") {
                statusText = "Start command received"
                if (sendTimer != null) sendTimer.start()
            }
        }
    }

    function onUpdate(dc) {
        var bounds = dc.getBounds()
        dc.clear()
        dc.drawText(bounds.getCenter(), statusText, Graphics.TEXT_JUSTIFY_CENTER)
    }
}
