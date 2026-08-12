package me.kavishdevar.librepods.data

import androidx.core.content.edit
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.utils.XposedServiceHolder

class XposedRemotePrefImpl: XposedRemotePref {
    override fun isAvailable(): Boolean {
        return XposedServiceHolder.service != null
    }

    override fun getBoolean(key: String, def: Boolean): Boolean {
        val s = XposedServiceHolder.service ?: return def
        return s.getRemotePreferences(BuildConfig.APPLICATION_ID).getBoolean(key, def)
    }

    override fun putBoolean(key: String, value: Boolean) {
        val s = XposedServiceHolder.service ?: return
        s.getRemotePreferences(BuildConfig.APPLICATION_ID)
            .edit { putBoolean(key, value) }
    }
}
