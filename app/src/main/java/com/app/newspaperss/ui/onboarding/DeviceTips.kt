package com.app.newspaperss.ui.onboarding

import com.app.newspaperss.settings.Device

object DeviceTips {
    fun tip(device: Device): String = when (device) {
        Device.KINDLE ->
            "When an edition is ready, tap Send and choose the Kindle app (“Send to Kindle”). " +
                "In its form, turn off “Add to your library” and pick your Kindle to send it straight to the device " +
                "(it won't sync to your other devices); otherwise it only goes to your library. " +
                "Changing Author to NewspapeRSS keeps your editions together. " +
                "If Kindle isn't on your phone, install it from the Play Store and sign in."
        Device.KOBO ->
            "Once, on your Kobo: More \u203a Settings \u203a Dropbox \u203a Link, and sign in. Then, when an edition " +
                "is ready, tap Send, choose Dropbox and save it in Apps \u203a Rakuten Kobo. It appears on your Kobo " +
                "when it syncs."
        Device.BOOX ->
            "Install NewspapeRSS on the Boox itself and tap Read to read each edition in its reader, " +
                "or send editions over with BooxDrop."
        Device.POCKETBOOK ->
            "Tap Send, choose your email app and send to your @pbsync.com address (Send-to-PocketBook). " +
                "Add the address you'll send from as a trusted sender in your PocketBook account first."
        Device.KOREADER ->
            "Pick a folder that syncs to your e-reader, for example with Syncthing, and new editions are " +
                "saved there automatically. Without one, tap Send to share each edition."
        Device.OTHER -> "Tap Send to share the EPUB with any app. To read it on this phone instead, it's Open on this phone in the edition's ⋮ menu."
    }
}
