package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.repository.CachedStopDetail
import com.example.model.ArrivalItem
import com.example.model.Stop
import com.example.util.PersianUtils

/**
 * Arrivals for one favorite, opened from the home grid.
 *
 * This is the only thing on the home screen that may poll, and only for [stop] —
 * one request at a time, one every [com.example.ui.viewmodel.POLL_INTERVAL_SECONDS]
 * seconds, exactly like the stop panel on the map. The list itself is the shared
 * [ArrivalsList]; nothing about a row is re-implemented here.
 */
@Composable
fun ArrivalPopup(
    stop: Stop,
    label: String,
    detail: CachedStopDetail?,
    arrivals: List<ArrivalItem>,
    isLoading: Boolean,
    hasError: Boolean,
    secondsSinceUpdate: Int,
    onClose: () -> Unit,
    onShowOnMap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = modifier
                .width(341.dp)
                .testTag("arrival_popup")
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 21.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                        StopSubtitle(stop = stop, detail = detail)
                    }

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.testTag("arrival_popup_close")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(id = R.string.dismiss),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.size(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                Spacer(modifier = Modifier.size(8.dp))

                // Capped so a long list scrolls inside the dialog instead of
                // growing it off-screen; short lists stay content-sized.
                Column(
                    modifier = Modifier
                        .heightIn(max = 470.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    ArrivalsList(
                        arrivals = arrivals,
                        isLoading = isLoading,
                        hasError = hasError,
                        secondsSinceUpdate = secondsSinceUpdate
                    )
                }

                Spacer(modifier = Modifier.size(12.dp))

                // Hand the same stop back to the map, focused, for the full panel.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .clickable(onClick = onShowOnMap)
                            .testTag("arrival_popup_open_map")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Map,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(id = R.string.open_map),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Official bundled name, plus the display code once it has been fetched. The code is
 * a technical id, so it gets its own LTR run — the same isolation the stop panel uses.
 */
@Composable
private fun StopSubtitle(stop: Stop, detail: CachedStopDetail?) {
    val code = detail?.stationCode
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stop.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (code != null) {
            Text(
                text = " · " + stringResource(id = R.string.station_code_prefix),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = PersianUtils.toPersianDigits(code),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
