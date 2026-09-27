package com.abhinavxt.debforge.ui.update

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.text.font.FontWeight
import com.abhinavxt.debforge.ui.components.BannerCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.abhinavxt.debforge.R

/** "DebForge 1.5.0 is available  [Update] [x]" at the top of the Library. */
@Composable
fun UpdateBanner(viewModel: UpdateViewModel = hiltViewModel()) {
    val info by viewModel.banner.collectAsStateWithLifecycle()
    val update = info ?: return
    val uriHandler = LocalUriHandler.current
    val onContainer = MaterialTheme.colorScheme.onTertiaryContainer
    BannerCard(
        icon = Icons.Rounded.SystemUpdate,
        text = stringResource(R.string.update_banner, update.version),
        container = MaterialTheme.colorScheme.tertiaryContainer,
        onContainer = onContainer
    ) {
        TextButton(
            onClick = { uriHandler.openUri(update.apkUrl ?: update.pageUrl) },
            colors = ButtonDefaults.textButtonColors(contentColor = onContainer)
        ) {
            Text(stringResource(R.string.action_update), fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = { viewModel.dismiss(update) }) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_dismiss))
        }
    }
}
