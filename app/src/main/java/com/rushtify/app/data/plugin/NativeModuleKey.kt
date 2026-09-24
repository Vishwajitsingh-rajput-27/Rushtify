package com.rushtify.app.data.plugin

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JNI bridge to the provider-module key stored ONLY in native .so.
 *
 * No module key bytes exist in DEX/BuildConfig. CI generates
 * `SecretsBridge_generated.h` from PROVIDER_MODULE_KEY; public forks
 * build with empty header -> null -> addons rejected -> YouTube fallback.
 */
@Singleton
class NativeModuleKey @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun moduleKey(): ByteArray? {
        val raw = runCatching { nativeModuleKeyBytes(context) }.getOrNull()
            ?: return null
        if (raw.size != 32) {
            raw.fill(0)
            return null
        }
        // Defensive copy: JNI array is already a copy, return as-is.
        return raw
    }

    companion object {
        init {
            runCatching { System.loadLibrary("rushtify_audio") }
        }

        @JvmStatic
        private external fun nativeModuleKeyBytes(context: Context): ByteArray?
    }
}
