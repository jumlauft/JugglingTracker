package com.juggling.tracker

import android.content.SharedPreferences

/**
 * Simple in-memory SharedPreferences for unit tests.
 * Avoids Robolectric dependency (which requires runtime SDK downloads).
 */
class FakeSharedPreferences : SharedPreferences {
    private val store = mutableMapOf<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = store.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? =
        store[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (store[key] as? MutableSet<String> ?: defValues)
    override fun getInt(key: String?, defValue: Int): Int =
        store[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long =
        store[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float =
        store[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        store[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = store.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) { listener?.let { listeners.add(it) } }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) { listener?.let { listeners.remove(it) } }

    private inner class FakeEditor : SharedPreferences.Editor {
        private val edits = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var doClear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            key?.let { edits[it] = value }; return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            key?.let { edits[it] = values }; return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            key?.let { edits[it] = value }; return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            key?.let { edits[it] = value }; return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            key?.let { edits[it] = value }; return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            key?.let { edits[it] = value }; return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            key?.let { removals.add(it) }; return this
        }
        override fun clear(): SharedPreferences.Editor {
            doClear = true; return this
        }
        override fun commit(): Boolean { applyEdits(); return true }
        override fun apply() { applyEdits() }

        private fun applyEdits() {
            if (doClear) store.clear()
            removals.forEach { store.remove(it) }
            store.putAll(edits)
        }
    }
}
