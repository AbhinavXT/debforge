package com.abhinavxt.debforge.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinavxt.debforge.BuildConfig
import com.abhinavxt.debforge.R
import com.abhinavxt.debforge.data.update.UpdateStatus
import com.abhinavxt.debforge.diagnostics.CrashReporter
import com.abhinavxt.debforge.ui.update.UpdateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Version, update check, source link and (if one exists) the saved crash report. */
@Composable
fun AboutSection(viewModel: UpdateViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val autoCheck by viewModel.autoCheck.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    var crashReport by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { crashReport = withContext(Dispatchers.IO) { CrashReporter.pending(context) } }

    Column {
        SectionHeader(stringResource(R.string.about_title))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                stringResource(R.string.about_version, viewModel.currentVersion),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                stringResource(R.string.about_build, BuildConfig.VERSION_CODE, BuildConfig.BUILD_TYPE),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SwitchRow(
            label = stringResource(R.string.about_auto_update),
            detail = stringResource(R.string.about_auto_update_detail),
            checked = autoCheck,
            onChange = viewModel::setAutoCheck
        )
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            val line = when (val s = status) {
                UpdateStatus.Idle -> null
                UpdateStatus.Checking -> stringResource(R.string.about_checking)
                UpdateStatus.UpToDate -> stringResource(R.string.about_up_to_date)
                is UpdateStatus.Available -> stringResource(R.string.about_update_available, s.info.version)
                is UpdateStatus.Failed -> stringResource(R.string.about_check_failed, s.reason)
            }
            line?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status is UpdateStatus.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val available = (status as? UpdateStatus.Available)?.info
                if (available != null) {
                    Button(onClick = { uriHandler.openUri(available.apkUrl ?: available.pageUrl) }) {
                        Text(stringResource(R.string.about_download))
                    }
                    OutlinedButton(onClick = { uriHandler.openUri(available.pageUrl) }) {
                        Text(stringResource(R.string.about_release_notes))
                    }
                } else {
                    OutlinedButton(
                        onClick = { viewModel.checkNow() },
                        enabled = status != UpdateStatus.Checking
                    ) { Text(stringResource(R.string.about_check_now)) }
                }
            }
        }
        TextButton(
            onClick = { uriHandler.openUri(viewModel.repoUrl) },
            modifier = Modifier.padding(horizontal = 4.dp)
        ) { Text(stringResource(R.string.about_source)) }
        crashReport?.let { report ->
            TextButton(
                onClick = { shareCrashReport(context, report) },
                modifier = Modifier.padding(horizontal = 4.dp)
            ) { Text(stringResource(R.string.about_share_crash)) }
        }
    }
}

/** Hands the (already redacted) report to whatever app the user picks. */
fun shareCrashReport(context: Context, report: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.crash_share_subject))
        putExtra(Intent.EXTRA_TEXT, report)
    }
    context.startActivity(
        Intent.createChooser(send, context.getString(R.string.crash_share))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
