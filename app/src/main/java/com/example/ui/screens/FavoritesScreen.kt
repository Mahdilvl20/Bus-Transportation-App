package com.example.ui.screens

import android.os.Build

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.FavoritesStore
import com.example.ui.viewmodel.AppScreen
import com.example.ui.viewmodel.BusViewModel
import com.example.ui.viewmodel.FavoriteTile
import com.example.util.PersianUtils

/**
 * Landing screen: the stops the user saved, by the names they gave them.
 *
 * Deliberately network-silent — the API has no batch arrivals call, so live times
 * would mean one request per tile. Tapping a tile opens [ArrivalPopup], which is
 * the only thing here allowed to poll, and only for that one stop.
 */
@Composable
fun FavoritesScreen(
    viewModel: BusViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var renameTarget by remember { mutableStateOf<FavoriteTile?>(null) }

    // Back closes the popup; with nothing open the system leaves the app.
    BackHandler(enabled = uiState.popupStop != null) { viewModel.closeArrivalPopup() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("favorites_screen_root")
    ) {
        // RenderEffect blur needs API 31; below it the scrim alone still reads as
        // "behind", and asking for it there would be a crash waiting to happen.
        val blurred =
            uiState.popupStop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val content = Modifier
            .fillMaxSize()
            .then(if (blurred) Modifier.blur(18.dp) else Modifier)

        Column(
            modifier = content
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            FavoritesHeader(tileCount = uiState.favoriteTiles.size)

            // The bundled list loads a frame or two in; showing "nothing saved yet"
            // before it arrives would be a lie the user sees on every cold start.
            if (uiState.favoriteTiles.isEmpty() && uiState.stops.isNotEmpty()) {
                EmptyFavorites(modifier = Modifier.weight(1f))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("favorite_grid"),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.favoriteTiles, key = { it.stop.id }) { tile ->
                        FavoriteTileCard(
                            tile = tile,
                            onClick = { viewModel.openArrivalPopup(tile.stop) },
                            onRename = { renameTarget = tile }
                        )
                    }
                }

                Text(
                    text = stringResource(id = R.string.favorites_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                )
            }

            Button(
                onClick = { viewModel.setAppScreen(AppScreen.MAP) },
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("open_map_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Navigation,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(id = R.string.open_map),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        }

        renameTarget?.let { target ->
            RenameStopDialog(
                currentLabel = target.label,
                onConfirm = { label ->
                    val accepted = viewModel.renameFavorite(target.stop, label)
                    if (accepted) renameTarget = null
                    accepted
                },
                onDismiss = { renameTarget = null }
            )
        }

        uiState.popupStop?.let { stop ->
            ArrivalPopup(
                stop = stop,
                // The user's own name for it; the official one is the subtitle.
                label = uiState.favoriteTiles
                    .firstOrNull { it.stop.id == stop.id }
                    ?.label ?: stop.name,
                detail = uiState.popupStopDetail,
                arrivals = uiState.arrivals,
                isLoading = uiState.isArrivalsLoading,
                hasError = uiState.arrivalsError,
                secondsSinceUpdate = uiState.secondsSinceUpdate,
                onClose = viewModel::closeArrivalPopup,
                onShowOnMap = {
                    viewModel.setAppScreen(AppScreen.MAP)
                    viewModel.selectStop(stop, fromUserAction = true)
                }
            )
        }
    }
}

@Composable
private fun FavoritesHeader(tileCount: Int, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 16.dp)
    ) {
        Column {
            Text(
                text = stringResource(id = R.string.favorites_title),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 27.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    id = R.string.favorites_count,
                    PersianUtils.toPersianDigits(tileCount)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Surface(
            shape = RoundedCornerShape(17.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(width = 46.dp, height = 34.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(9.dp)
                    .size(16.dp)
            )
        }
    }
}

@Composable
private fun FavoriteTileCard(
    tile: FavoriteTile,
    onClick: () -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
        modifier = modifier
            .height(96.dp)
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("favorite_tile_${tile.stop.id}")
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = tile.label,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            // Trailing corner in RTL, so it never sits under the centred label.
            IconButton(
                onClick = onRename,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(40.dp)
                    .testTag("rename_${tile.stop.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(id = R.string.rename_stop),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyFavorites(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(132.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(36.dp)
                    .fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = stringResource(id = R.string.favorites_empty_title),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = stringResource(id = R.string.favorites_empty_body_1),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(id = R.string.favorites_empty_body_2),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Single-field rename. Validation runs here as well as in the store so the message
 * appears inline instead of the save button silently doing nothing.
 */
@Composable
private fun RenameStopDialog(
    currentLabel: String,
    onConfirm: (String) -> Boolean,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(currentLabel) }
    var errorRes by remember { mutableStateOf(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.width(320.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(id = R.string.rename_stop),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        errorRes = 0
                    },
                    singleLine = true,
                    isError = errorRes != 0,
                    supportingText = if (errorRes != 0) {
                        { Text(stringResource(id = errorRes)) }
                    } else {
                        null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rename_field")
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(id = R.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        val clean = text.trim()
                        errorRes = when {
                            clean.isEmpty() -> R.string.rename_blank
                            clean.length > FavoritesStore.MAX_LABEL_LENGTH -> R.string.rename_too_long
                            // The store can still refuse (unknown id); report it as blank.
                            else -> if (onConfirm(clean)) 0 else R.string.rename_blank
                        }
                    }) {
                        Text(stringResource(id = R.string.save))
                    }
                }
            }
        }
    }
}
