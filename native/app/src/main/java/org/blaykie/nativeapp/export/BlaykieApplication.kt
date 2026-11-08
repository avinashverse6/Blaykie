package org.blaykie.nativeapp.export

import android.app.Application

/** A new application process clears only the private share-cache folder. */
class BlaykieApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try { cleanExportCache(cacheDir) } catch (_: Exception) { /* Export reports cleanup failure. */ }
    }
}
