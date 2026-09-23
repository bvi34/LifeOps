package com.operations.securestore

import android.content.SharedPreferences
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** A SharedPreferences in memory, so the store can be exercised on the JVM. */
class FakePreferences : SharedPreferences {
    val values = linkedMapOf<String, Any?>()
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): Map<String, *> = LinkedHashMap(values)
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = defValue
    override fun getLong(key: String, defValue: Long): Long = defValue
    override fun getFloat(key: String, defValue: Float): Float = defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
    override fun contains(key: String): Boolean = key in values
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners += l
    }
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners -= l
    }

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val puts = linkedMapOf<String, Any?>()
        private val removes = mutableSetOf<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { puts[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { puts[key] = values }
        override fun putInt(key: String, value: Int) = apply { puts[key] = value }
        override fun putLong(key: String, value: Long) = apply { puts[key] = value }
        override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
        override fun remove(key: String) = apply { removes += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            removes.forEach { values.remove(it) }
            puts.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
            (puts.keys + removes).forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(this@FakePreferences, key) } }
            return true
        }
        override fun apply() {
            commit()
        }
    }
}

/** A software AES key standing in for the Keystore's. */
class SoftwareKey : KeySource {
    var key: SecretKey? = null
    var created = 0

    override fun existing(): SecretKey? = key

    override fun create(): SecretKey {
        created++
        return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
    }

    override fun delete() {
        key = null
    }
}
