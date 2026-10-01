package com.app.newspaperss.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.app.newspaperss.delivery.MailApp
import com.app.newspaperss.delivery.MailApps
import com.app.newspaperss.settings.KindleAddress

internal const val ASK_EACH_TIME = "Ask each time"

/**
 * The Kindle's address and the mail app Send opens, for emailing editions to a Kindle. Shared by
 * onboarding and Settings, which word the field's label differently.
 *
 * @param hint shown under the address when there's nothing wrong with it.
 * @param required the reader can't go on without a valid address, so until there is one the
 *   hint says so: a disabled Next doesn't say why.
 */
@Composable
fun KindleEmailFields(
    address: String,
    onAddress: (String) -> Unit,
    mailApp: String?,
    onMailApp: (String?) -> Unit,
    label: String,
    hint: String? = null,
    required: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val apps = remember { MailApps.installed(context) }
    // Not judged mid-typing, from the first letter: only once the reader leaves the field, or
    // what's typed looks finished (a dot after the @). An address already there counts as left.
    var left by rememberSaveable { mutableStateOf(address.isNotBlank()) }
    var focused by remember { mutableStateOf(false) }
    val valid = KindleAddress.isValid(address)
    val looksFinished = address.substringAfter('@', "").contains('.')
    val error = "That isn't a whole email address yet.".takeIf { address.isNotBlank() && !valid && (left || looksFinished) }
    val message = error
        ?: "Kindle addresses end in @kindle.com. Check it's the one Amazon shows for your Kindle.".takeIf { valid && !KindleAddress.looksLikeKindle(address) }
        ?: listOfNotNull("Needed to go on.".takeIf { required && !valid }, hint).joinToString(" ").ifEmpty { null }
    Column(modifier) {
        OutlinedTextField(
            value = address,
            onValueChange = onAddress,
            label = { Text(label) },
            placeholder = { Text("name_abc123@kindle.com") },
            singleLine = true,
            // A non-Kindle domain is only a warning: it isn't certainly wrong.
            isError = error != null,
            // A live region, so TalkBack reads out a problem as it appears, not only on focus.
            supportingText = message?.let { { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth()
                .onFocusChanged {
                    if (focused && !it.isFocused) left = true
                    focused = it.isFocused
                }
                .semantics { if (error != null) error(error) },
        )
        MailAppPicker(mailApp, onMailApp, apps, Modifier.padding(top = 8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MailAppPicker(selected: String?, onSelect: (String?) -> Unit, apps: List<MailApp>, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    // A chosen app that's since been uninstalled is still named by its package: Send falls back
    // to asking, and the reader can see why.
    val current = selected?.let { pkg -> apps.firstOrNull { it.packageName == pkg }?.label ?: pkg } ?: ASK_EACH_TIME
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Send with") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (listOf<MailApp?>(null) + apps).forEach { app ->
                DropdownMenuItem(
                    text = { Text(app?.label ?: ASK_EACH_TIME) },
                    onClick = {
                        onSelect(app?.packageName)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}
