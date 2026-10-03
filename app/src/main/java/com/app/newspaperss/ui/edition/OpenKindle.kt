package com.app.newspaperss.ui.edition

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.app.newspaperss.delivery.EditionIntents

/**
 * Starts the Kindle app, where a sent edition turns up, for a Kindle reader whose phone has it.
 * Nothing at all without the app; checked again whenever the screen comes back, e.g. from
 * installing or removing it.
 */
@Composable
fun OpenKindleButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var launch by remember { mutableStateOf(EditionIntents.openKindle(context)) }
    LifecycleResumeEffect(Unit) {
        launch = EditionIntents.openKindle(context)
        onPauseOrDispose {}
    }
    val intent = launch ?: return
    OutlinedButton(
        onClick = {
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                // Removed since the screen last checked.
                launch = null
                Toast.makeText(context, "The Kindle app isn't on this phone any more.", Toast.LENGTH_LONG).show()
            }
        },
        modifier = modifier,
    ) { Text("Open Kindle") }
}
