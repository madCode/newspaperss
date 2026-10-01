package com.app.newspaperss.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
 * @param hint shown under an empty or valid address, where a problem with it would go.
 */
@Composable
fun KindleEmailFields(
    address: String,
    onAddress: (String) -> Unit,
    mailApp: String?,
    onMailApp: (String?) -> Unit,
    label: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val apps = remember { MailApps.installed(context) }
    val problem = when {
        address.isBlank() -> null
        !KindleAddress.isValid(address) -> "That isn't a whole email address yet."
        !KindleAddress.looksLikeKindle(address) -> "Kindle addresses end in @kindle.com. Check it's the one Amazon shows for your Kindle."
        else -> null
    }
    Column(modifier) {
        OutlinedTextField(
            value = address,
            onValueChange = onAddress,
            label = { Text(label) },
            placeholder = { Text("name_abc123@kindle.com") },
            singleLine = true,
            // A non-Kindle domain is only a warning: it isn't certainly wrong.
            isError = address.isNotBlank() && !KindleAddress.isValid(address),
            supportingText = (problem ?: hint)?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
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
