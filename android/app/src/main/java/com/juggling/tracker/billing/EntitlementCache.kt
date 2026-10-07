package com.juggling.tracker.billing

import android.content.Context

/** The last answer Play gave about the subscription, so the app opens offline for subscribers. */
interface EntitlementCache {
    var lastKnownActive: Boolean?

    /** Set once the tester code has been entered on this phone. */
    var isTester: Boolean
}

class PrefsEntitlementCache(context: Context) : EntitlementCache {
    private val prefs = context.getSharedPreferences("subscription", Context.MODE_PRIVATE)

    override var lastKnownActive: Boolean?
        get() = if (prefs.contains(KEY_ACTIVE)) prefs.getBoolean(KEY_ACTIVE, false) else null
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY_ACTIVE) else putBoolean(KEY_ACTIVE, value) }.apply()
        }

    override var isTester: Boolean
        get() = prefs.getBoolean(KEY_TESTER, false)
        set(value) {
            prefs.edit().putBoolean(KEY_TESTER, value).apply()
        }

    private companion object {
        const val KEY_ACTIVE = "last_known_active"
        const val KEY_TESTER = "tester"
    }
}
