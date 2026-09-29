package com.app.newspaperss.ui.onboarding

import com.app.newspaperss.settings.Device

object DeviceTips {
    fun tip(device: Device): String = when (device) {
        Device.KINDLE ->
            "When an edition is ready, tap Send and choose the Kindle app (“Send to Kindle”). " +
                "If it isn't on your phone, install Kindle from the Play Store and sign in."
        Device.KOBO ->
            "Connect Dropbox on your Kobo first. When an edition is ready, tap Send, choose Dropbox and save " +
                "it in Apps \u203a Rakuten Kobo. It appears on your Kobo when it syncs."
        Device.BOOX ->
            "Install newspaperss on the Boox itself and tap Open to read each edition in its reader, " +
                "or send editions over with BooxDrop."
        Device.POCKETBOOK ->
            "Tap Send, choose your email app and send to your @pbsync.com address (Send-to-PocketBook). " +
                "Add the address you'll send from as a trusted sender in your PocketBook account first."
        Device.KOREADER ->
            "Pick a folder that syncs to your e-reader, for example with Syncthing, and new editions are " +
                "saved there automatically. Without one, tap Send to share each edition."
        Device.OTHER -> "Tap Send to share the EPUB with any app, or Open to read it on this phone."
    }
}
