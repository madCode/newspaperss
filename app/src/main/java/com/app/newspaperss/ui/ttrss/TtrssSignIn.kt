package com.app.newspaperss.ui.ttrss

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** The tt-rss sign-in form, in onboarding and in Settings. */
data class TtrssForm(
    val address: String = "",
    val user: String = "",
    val password: String = "",
    val testing: Boolean = false,
    /** Why the last try failed, in tt-rss's own terms: one message per cause. */
    val error: String? = null,
) {
    val canSubmit get() = !testing && address.isNotBlank()
}

/**
 * The address, username and password, then what's happening: signing in, or why it didn't
 * work. What was typed stays, so a typo in the path is a one-character fix.
 */
@Composable
fun TtrssSignInFields(form: TtrssForm, onEdit: (TtrssForm) -> Unit, onSubmit: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        OutlinedTextField(
            value = form.address,
            onValueChange = { onEdit(form.copy(address = it)) },
            label = { Text("Server address") },
            placeholder = { Text("rss.example.com/tt-rss") },
            singleLine = true,
            enabled = !form.testing,
            supportingText = if (form.address.trim().startsWith("http://", ignoreCase = true)) {
                { Text("This address isn't encrypted: your password would be sent in the clear. Use https:// if your server supports it.") }
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.user,
            onValueChange = { onEdit(form.copy(user = it)) },
            label = { Text("Username") },
            singleLine = true,
            enabled = !form.testing,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.password,
            onValueChange = { onEdit(form.copy(password = it)) },
            label = { Text("Password") },
            singleLine = true,
            enabled = !form.testing,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (form.canSubmit) onSubmit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "In tt-rss, turn on Preferences › Enable API first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        // Always composed, so TalkBack announces the change from "Signing in…" to the error.
        val status = when {
            form.testing -> "Signing in…"
            else -> form.error.orEmpty()
        }
        Text(
            status,
            color = if (form.error != null && !form.testing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
