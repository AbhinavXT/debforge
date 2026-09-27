package com.abhinavxt.debforge.ui.settings

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Icon
import com.abhinavxt.debforge.ui.components.RowDivider
import com.abhinavxt.debforge.ui.components.SectionCard
import com.abhinavxt.debforge.ui.components.SettingRow
import com.abhinavxt.debforge.ui.components.SwitchSettingRow
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

    SectionCard(title = stringResource(R.string.about_title)) {
        SettingRow(
            icon = Icons.Rounded.Info,
            title = stringResource(R.string.about_version, viewModel.currentVersion),
            subtitle = stringResource(R.string.about_build, BuildConfig.VERSION_CODE, BuildConfig.BUILD_TYPE)
        )
        RowDivider()
        SwitchSettingRow(
            icon = Icons.Rounded.Update,
            title = stringResource(R.string.about_auto_update),
            subtitle = stringResource(R.string.about_auto_update_detail),
            checked = autoCheck,
            onChange = viewModel::setAutoCheck
        )
        Column(Modifier.padding(start = 68.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)) {
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
        RowDivider()
        SettingRow(
            icon = Icons.Rounded.Code,
            title = stringResource(R.string.about_source),
            onClick = { uriHandler.openUri(viewModel.repoUrl) },
            trailing = {
                Icon(
                    Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        )
        crashReport?.let { report ->
            RowDivider()
            SettingRow(
                icon = Icons.Rounded.BugReport,
                title = stringResource(R.string.about_share_crash),
                onClick = { shareCrashReport(context, report) }
            )
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
