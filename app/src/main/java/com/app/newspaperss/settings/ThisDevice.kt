package com.app.newspaperss.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** What the phone, or e-reader, the app is running on is. */
object ThisDevice {
    /** Makers of e-ink devices only, some of them phones that can make calls (Bigme, Mudita). */
    private val E_INK_MAKERS = setOf("onyx", "bigme", "mudita", "meebook", "boyue")

    /**
     * Whether the app runs on an e-reader rather than a phone: one from an e-ink maker (a Boox is
     * made by Onyx), or any other device that can't make calls, as Android e-readers can't. A Boox
     * reader may run the app on their phone instead and send editions over with BooxDrop.
     *
     * A guess: an e-ink phone from a maker of ordinary phones too (Hisense) counts as a phone, and
     * a tablet without calls as an e-reader.
     */
    fun isEReader(context: Context): Boolean =
        Build.MANUFACTURER.lowercase() in E_INK_MAKERS ||
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
}
