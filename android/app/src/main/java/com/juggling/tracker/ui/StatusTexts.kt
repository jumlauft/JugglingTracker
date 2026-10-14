package com.juggling.tracker.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.juggling.tracker.R
import com.juggling.tracker.logic.PhoneSessionStatus

/** The strings.xml text for each phone session status. */
@StringRes
fun PhoneSessionStatus.textRes(): Int = when (this) {
    PhoneSessionStatus.READY -> R.string.phone_status_ready
    PhoneSessionStatus.MOUNT_PHONE -> R.string.phone_status_mount_phone
    PhoneSessionStatus.RUN_ACTIVE -> R.string.phone_status_run_active
    PhoneSessionStatus.WAITING_FOR_RUN -> R.string.phone_status_waiting_for_run
    PhoneSessionStatus.SAVED -> R.string.phone_status_saved
    PhoneSessionStatus.CANCELLED -> R.string.phone_status_cancelled
    PhoneSessionStatus.NO_RUNS -> R.string.phone_status_no_runs
    PhoneSessionStatus.SENSOR_UNAVAILABLE -> R.string.phone_status_sensor_unavailable
    PhoneSessionStatus.SENSOR_FROZEN -> R.string.phone_status_sensor_frozen
}

@Composable
fun PhoneSessionStatus.text(): String = stringResource(textRes())
