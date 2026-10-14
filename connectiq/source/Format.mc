import Toybox.Lang;

// Display strings of the Juggle and Record screens, kept out of the views so
// tests can check them. The Wear OS and Apple Watch apps have the same
// functions in their Format.kt and Format.swift.
module Format {
    // "m:ss", or "h:mm:ss" from one hour on.
    function elapsed(totalSeconds as Number) as String {
        var hours = totalSeconds / 3600;
        var minutes = (totalSeconds / 60) % 60;
        var seconds = totalSeconds % 60;
        if (hours > 0) {
            return hours.toString() + ":" + twoDigits(minutes) + ":" + twoDigits(seconds);
        }
        return minutes.toString() + ":" + twoDigits(seconds);
    }

    // Stats show "-" until there is a value.
    function countOrDash(value as Number) as String {
        return value == 0 ? "-" : value.toString();
    }

    function averageOrDash(value as Float) as String {
        return value == 0.0f ? "-" : value.format("%.1f");
    }

    // Shape consistency: "-" until a run has been scored (-1), else "83%".
    function percentOrDash(value as Number) as String {
        return value < 0 ? "-" : value.toString() + "%";
    }

    function runState(active as Boolean) as String {
        return active ? "RUN ACTIVE" : "WAITING";
    }

    function syncing(dots as Number) as String {
        var text = "Sync to phone";
        for (var i = 0; i < dots; i++) {
            text += ".";
        }
        return text;
    }

    // While chunks are going over, the record screen shows progress instead.
    function recordingSyncText(session as RecordingSession) as String {
        if (session.headerSent() && !session.transferDone() && session.totalChunks() > 0) {
            return session.chunkIndex().toString() + "/" + session.totalChunks().toString();
        }
        return syncing(session.syncDots());
    }

    function twoDigits(value as Number) as String {
        return value < 10 ? "0" + value.toString() : value.toString();
    }
}
