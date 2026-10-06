package com.app.newspaperss.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** What the phone, or e-reader, the app is running on is. */
object ThisDevice {
    /**
     * Whether the app runs on an e-reader rather than a phone: a Boox (made by Onyx), or any other
     * device that can't make calls, as Android e-readers can't. A Boox reader may run the app on
     * their phone instead and send editions over with BooxDrop.
     */
    fun isEReader(context: Context): Boolean =
        Build.MANUFACTURER.equals("ONYX", ignoreCase = true) ||
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
}
