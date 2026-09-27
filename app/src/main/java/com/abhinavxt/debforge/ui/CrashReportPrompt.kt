package com.abhinavxt.debforge.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.diagnostics.CrashReporter
import com.abhinavxt.debforge.ui.settings.shareCrashReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Asked once per launch after a crash. Shows exactly what would be shared,
 * so the user can see nothing private is in it. Tapping outside = "later":
 * the report stays and is offered again next launch (and in Settings → About).
 */
@Composable
fun CrashReportPrompt() {
    val context = LocalContext.current
    var report by rememberSaveable { mutableStateOf<String?>(null) }
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!asked) {
            report = withContext(Dispatchers.IO) { CrashReporter.pending(context) }
            asked = true
        }
    }
    val text = report ?: return
    AlertDialog(
        onDismissRequest = { report = null },
        title = { Text(stringResource(R.string.crash_title)) },
        text = {
            Column {
                Text(stringResource(R.string.crash_body), modifier = Modifier.padding(bottom = 12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                shareCrashReport(context, text)
                CrashReporter.clear(context)
                report = null
            }) { Text(stringResource(R.string.crash_share)) }
        },
        dismissButton = {
            TextButton(onClick = {
                CrashReporter.clear(context)
                report = null
            }) { Text(stringResource(R.string.crash_delete)) }
        }
    )
}
