package com.salmansaadiq.dheu.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.salmansaadiq.dheu.data.REPORT_DETAILS_MAX
import com.salmansaadiq.dheu.data.REPORT_REASONS
import com.salmansaadiq.dheu.data.ReportTarget
import com.salmansaadiq.dheu.data.Reports
import com.salmansaadiq.dheu.data.friendly
import com.salmansaadiq.dheu.ui.LocalAppState
import com.salmansaadiq.dheu.ui.icons.Lucide
import com.salmansaadiq.dheu.ui.theme.Dheu
import kotlinx.coroutines.launch

/**
 * "Report post / story / comment / account": a reason, an optional note and a
 * thank-you, as on the website. Only the admin reads reports; the person
 * isn't told who sent one.
 */
@Composable
fun ReportSheet(target: ReportTarget, id: String, onDismiss: () -> Unit) {
    val c = Dheu.colors
    val me = LocalAppState.current.userId
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun send() {
        val chosen = reason ?: return
        sending = true
        error = null
        scope.launch {
            try {
                done = if (Reports.report(me, target, id, chosen, details)) "Thanks for telling us. The admin will review it."
                else "You've already reported this. The admin will look at it."
            } catch (e: Exception) {
                error = e.friendly("Couldn't send your report. Please try again.")
            } finally {
                sending = false
            }
        }
    }

    DheuSheet("Report ${target.noun}", onDismiss = onDismiss) {
        val thanks = done
        if (thanks != null) {
            Text(thanks, modifier = Modifier.padding(bottom = 16.dp))
            PrimaryButton("Done", onDismiss, Modifier.fillMaxWidth())
        } else Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Why are you reporting this? Only the admin sees reports, and the person isn't told who sent one.",
                color = c.inkMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            REPORT_REASONS.forEach { r ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .selectable(selected = reason == r.value, role = Role.RadioButton, onClick = { reason = r.value })
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = reason == r.value, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = c.brand))
                    Spacer(Modifier.width(8.dp))
                    Text(r.label)
                }
            }
            DheuTextField(
                details,
                { details = it },
                "Anything the admin should know? (optional)",
                singleLine = false,
                minLines = 2,
                maxLength = REPORT_DETAILS_MAX,
                modifier = Modifier.padding(top = 8.dp),
            )
            error?.let { Text(it, color = c.danger, modifier = Modifier.padding(top = 8.dp)) }
            SecondaryButton(
                "Send report",
                ::send,
                Modifier.fillMaxWidth().padding(top = 12.dp),
                enabled = reason != null,
                loading = sending,
                icon = Lucide.Flag,
                danger = true,
            )
        }
    }
}
