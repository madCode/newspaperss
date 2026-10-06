package com.app.newspaperss.ui.settings

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.app.newspaperss.delivery.FolderDelivery
import com.app.newspaperss.settings.DeliveryMethod
import com.app.newspaperss.settings.Device
import com.app.newspaperss.settings.Settings as AppSettings
import com.app.newspaperss.ui.components.FOLDER_GRANT
import com.app.newspaperss.ui.components.KindleEmailFields
import com.app.newspaperss.ui.components.keepFolderAccess
import com.app.newspaperss.ui.onboarding.DeviceTips

/** The e-reader chosen in onboarding, changeable later: it decides Send or Open, the delivery choices and the tips shown. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderPicker(s: AppSettings, vm: SettingsViewModel) {
    var expanded by remember { mutableStateOf(false) }
    // A dropdown rather than six radio rows, so the page fits one screen with the Kindle email fields open.
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = s.device?.label ?: "Not chosen",
            onValueChange = {},
            readOnly = true,
            // Wraps rather than scrolling sideways: "Boox or another Android e-reader" is cut off at large text.
            singleLine = false,
            label = { Text("Your e-reader") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Device.entries.forEach { device ->
                DropdownMenuItem(
                    text = { Text(device.label) },
                    onClick = {
                        vm.setDevice(device)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    modifier = Modifier.semantics { selected = device == s.device },
                )
            }
        }
    }
    Text(
        "Decides how editions can reach it, and the tips you see.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
internal fun DeliverySection(s: AppSettings, vm: SettingsViewModel) {
    val context = LocalContext.current
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null && keepFolderAccess(context, uri)) {
            release(context, s.folderUri, keep = setOf(uri.toString(), s.notesFolderUri))
            vm.useFolder(uri.toString(), FolderDelivery.displayName(context.contentResolver, uri))
        }
    }
    SubHeading("How it gets there", Modifier.padding(top = 24.dp, bottom = 4.dp))
    if (s.device == Device.KINDLE || s.delivery == DeliveryMethod.KINDLE_EMAIL) {
        DeliveryOption(
            selected = s.delivery == DeliveryMethod.KINDLE_EMAIL,
            title = "Email it to your Kindle",
            detail = "Arrives on your Kindle by itself.",
            onClick = vm::useKindleEmail,
        )
        if (s.delivery == DeliveryMethod.KINDLE_EMAIL) {
            // Held here and saved as it changes: fed back from the store, each save would move the cursor.
            var address by rememberSaveable { mutableStateOf(s.kindleEmail.orEmpty()) }
            KindleEmailFields(
                address = address,
                onAddress = { address = it; vm.setKindleEmail(it) },
                mailApp = s.mailApp,
                onMailApp = vm::setMailApp,
                label = "Kindle's email address",
                modifier = Modifier.padding(start = OPTION_INDENT),
            )
            // The saved address, not the typed one: Send goes by what's saved.
            if (s.kindleEmailTarget == null) {
                Text(
                    "Add your Kindle's email address; until then Send opens the share sheet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = OPTION_INDENT, top = 4.dp),
                )
            }
            Text(
                "The address you send from must be on Amazon's approved list.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = OPTION_INDENT, top = 4.dp, bottom = 8.dp),
            )
        }
    }
    DeliveryOption(
        selected = s.delivery == DeliveryMethod.SHARE,
        title = "Send it myself",
        detail = "Tap Send on each edition and choose an app: the Kindle app, Dropbox, email or any other.",
        onClick = vm::useShare,
    )
    // How to send to this e-reader with the share sheet: under the choice, where the reader is
    // looking when they pick it, and only then (a folder or email reader doesn't wait for a Send).
    if (s.delivery == DeliveryMethod.SHARE) {
        s.device?.let {
            Text(
                DeviceTips.tip(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = OPTION_INDENT, bottom = 8.dp),
            )
        }
    }
    val folderReachable = rememberReachable(s.folderUri)
    DeliveryOption(
        selected = s.delivery == DeliveryMethod.FOLDER,
        title = "Save to a folder",
        detail = when {
            s.folderUri == null -> "Fully automatic, for KOReader and other readers that sync a folder on this phone (for example with Syncthing)."
            !folderReachable -> "Can't reach ${s.folderName ?: "your folder"}. Tap to choose it again."
            else -> "Saved automatically to ${s.folderName ?: "your folder"}."
        },
        problem = s.folderUri != null && !folderReachable,
        onClick = { if (s.folderUri != null && folderReachable) vm.useFolder(s.folderUri, s.folderName ?: "your folder") else pickFolder.launch(null) },
    )
    if (s.delivery == DeliveryMethod.FOLDER) {
        OutlinedButton(onClick = { pickFolder.launch(null) }, Modifier.padding(start = OPTION_INDENT)) { Text("Choose another delivery folder") }
    }
}

/** How far a setting's own controls sit in, under the option or switch they belong to. */
private val OPTION_INDENT = 48.dp

/**
 * Whether the app still holds its grant to the folder at [uri], checked again whenever the screen
 * comes back: the grant goes if the folder's app is uninstalled or its access is revoked, and
 * then every save to it fails.
 */
@Composable
internal fun rememberReachable(uri: String?): Boolean {
    val context = LocalContext.current
    fun check() = uri == null || context.contentResolver.persistedUriPermissions.any { it.uri.toString() == uri && it.isWritePermission }
    var reachable by remember(uri) { mutableStateOf(check()) }
    LifecycleResumeEffect(uri) {
        reachable = check()
        onPauseOrDispose {}
    }
    return reachable
}

/**
 * Lets go of a folder no setting uses any more: grants are capped per app. The delivery and
 * notes folders can be the same one, so neither lets go of a folder the other still holds.
 */
private fun release(context: android.content.Context, old: String?, keep: Set<String?>) {
    if (old == null || old in keep) return
    runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(old), FOLDER_GRANT) }
}

@Composable
internal fun NotesSection(s: AppSettings, vm: SettingsViewModel) {
    val context = LocalContext.current
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null && keepFolderAccess(context, uri)) {
            release(context, s.notesFolderUri, keep = setOf(uri.toString(), s.folderUri))
            vm.setNotesFolder(uri.toString(), FolderDelivery.displayName(context.contentResolver, uri))
        }
    }
    val saving = s.notesFolderUri != null
    val reachable = rememberReachable(s.notesFolderUri)
    fun turnOff() {
        release(context, s.notesFolderUri, keep = setOf(s.folderUri))
        vm.setNotesFolder(null, null)
    }
    // A folder that can't be reached is what the row offers to fix, so tapping it picks one again
    // rather than turning notes off under a line that says "Tap to choose it again".
    val tap = if (saving && !reachable) {
        Modifier.clickable(onClickLabel = "Choose the notes folder again") { pickFolder.launch(null) }
    } else {
        Modifier.toggleable(saving, role = Role.Switch) { on -> if (on) pickFolder.launch(null) else turnOff() }
    }
    Row(
        Modifier.fillMaxWidth().then(tap).heightIn(min = 48.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Save notes for each edition", style = MaterialTheme.typography.bodyLarge)
            val name = s.notesFolderName ?: "your folder"
            Text(
                when {
                    !saving -> "A Markdown file for each edition, for Obsidian or any notes app. You'll pick the folder."
                    !reachable -> "Can't reach $name. Tap to choose it again."
                    else -> "Saved to $name when an edition is delivered."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (reachable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        Switch(checked = saving, onCheckedChange = null)
    }
    if (saving) {
        Row(Modifier.padding(start = OPTION_INDENT), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Choose another notes folder") }
            // The row no longer turns notes off while the folder is unreachable, so this does.
            if (!reachable) TextButton(onClick = ::turnOff, modifier = Modifier.padding(start = 8.dp)) { Text("Turn off") }
        }
    }
}

@Composable
private fun DeliveryOption(selected: Boolean, title: String, detail: String, problem: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 8.dp)) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Without notifications a timed edition is made but nobody hears about it: a shared or emailed
 * one's Send is in its notification, and a folder delivery that fails says so only there.
 */
@Composable
internal fun NotificationsOffWarning(delivery: DeliveryMethod) {
    val context = LocalContext.current
    if (rememberNotificationsEnabled()) return
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(
            if (delivery == DeliveryMethod.FOLDER) "Notifications are off, so you won't hear if an edition fails to arrive."
            else "Notifications are off, so you won't hear when an edition is ready to send.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = {
            // Some e-readers' firmware leaves out the notification settings screen: the app's own page instead.
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: ActivityNotFoundException) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }) { Text("Turn on notifications") }
    }
}

/** Whether this app may post notifications, checked again whenever the screen comes back from Android's settings. */
@Composable
internal fun rememberNotificationsEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleResumeEffect(Unit) {
        enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose {}
    }
    return enabled
}
