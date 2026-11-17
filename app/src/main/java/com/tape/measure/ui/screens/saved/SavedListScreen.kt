package com.tape.measure.ui.screens.saved

import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tape.measure.data.db.MeasurementEntity
import com.tape.measure.data.db.ReadingsJson
import com.tape.measure.domain.model.AccuracyGrade
import com.tape.measure.domain.model.UnitSystem
import com.tape.measure.domain.shape.MeasureTool
import com.tape.measure.domain.shape.Quantity
import com.tape.measure.domain.shape.Reading
import com.tape.measure.domain.shape.formatTolerance
import com.tape.measure.domain.shape.formatValue
import com.tape.measure.ui.theme.ConfidenceHigh
import com.tape.measure.ui.theme.ConfidenceLow
import com.tape.measure.ui.theme.ConfidenceMedium

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SavedListScreen(
    onBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: SavedViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedEntity by remember { mutableStateOf<MeasurementEntity?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current

    // Action bottom sheet
    selectedEntity?.let { entity ->
        ModalBottomSheet(
            onDismissRequest = { selectedEntity = null },
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.navigationBarsPadding()) {
                Text(
                    text = entity.label ?: "Untitled",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                HorizontalDivider()
                SheetAction(
                    icon = Icons.Outlined.Edit,
                    label = "Rename",
                    onClick = {
                        showRenameDialog = true
                    },
                )
                SheetAction(
                    icon = Icons.Outlined.Share,
                    label = "Share",
                    onClick = {
                        val text = shareText(entity, uiState.unitSystem)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(intent, null))
                        selectedEntity = null
                    },
                )
                SheetAction(
                    icon = Icons.Outlined.Delete,
                    label = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = {
                        viewModel.delete(entity)
                        selectedEntity = null
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // Rename dialog
    if (showRenameDialog) {
        val entity = selectedEntity
        var labelText by remember(entity) { mutableStateOf(entity?.label ?: "") }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = labelText,
                    onValueChange = { labelText = it },
                    label = { Text("Label") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    entity?.let { viewModel.rename(it, labelText) }
                    showRenameDialog = false
                    selectedEntity = null
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "Saved",
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
            actions = {
                IconButton(onClick = onNavigateToSettings) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Settings",
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        AnimatedVisibility(
            visible = !uiState.isLoading && uiState.measurements.isEmpty(),
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(150)),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 32.dp),
                ) {
                    Text(
                        text = "No saved measurements yet.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Tap Save on the measure screen to keep a reading.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = uiState.measurements.isNotEmpty(),
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(150)),
        ) {
            LazyColumn {
                items(uiState.measurements, key = { it.id }) { entity ->
                    MeasurementCard(
                        entity = entity,
                        readings = remember(entity) { entity.readingsOrLength() },
                        unitSystem = uiState.unitSystem,
                        onLongPress = { selectedEntity = entity },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MeasurementCard(
    entity: MeasurementEntity,
    readings: List<Reading>,
    unitSystem: UnitSystem,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val relativeTime = remember(entity.createdAt) {
        DateUtils.getRelativeTimeSpanString(
            entity.createdAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    }
    // The headline figure goes in the chip; anything else the shape measured
    // is listed under the label.
    val headline = readings.first()
    val formattedTolerance = headline.formatTolerance(unitSystem)
    val tool = MeasureTool.parse(entity.kind)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onLongClick = onLongPress, onClick = {}),
        color = MaterialTheme.colorScheme.background,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entity.label ?: "Untitled",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (tool == MeasureTool.LINE) relativeTime else "${tool.label} · $relativeTime",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (readings.size > 1) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = readings.drop(1).joinToString(" · ") {
                            "${it.name} ${it.formatValue(unitSystem)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = headline.formatValue(unitSystem),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                // The tolerance travels with the saved value. A number without its
                // error bound is exactly what Tape refuses to show.
                if (formattedTolerance != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = formattedTolerance,
                        style = MaterialTheme.typography.bodySmall,
                        color = gradeTint(headline.grade),
                    )
                } else if (headline.grade == AccuracyGrade.UNKNOWN) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "accuracy not recorded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * What a saved row measured. Rows saved before shapes existed hold a single
 * length in their own columns; reading them the same way keeps one code path.
 */
private fun MeasurementEntity.readingsOrLength(): List<Reading> =
    ReadingsJson.decode(readings).ifEmpty {
        val grade = AccuracyGrade.parse(accuracyGrade)
        listOf(
            Reading(
                name = "Length",
                quantity = Quantity.LENGTH,
                value = distanceMeters,
                tolerance = toleranceMeters.takeIf { grade.hasTolerance },
                grade = grade,
            )
        )
    }

/**
 * Every figure goes out with its tolerance. A number sent to someone else
 * without its error bound is the same overclaim Tape exists to avoid — it
 * just travels further.
 */
private fun shareText(entity: MeasurementEntity, unitSystem: UnitSystem): String {
    val readings = entity.readingsOrLength()
    fun Reading.withTolerance() =
        listOfNotNull(formatValue(unitSystem), formatTolerance(unitSystem)).joinToString(" ")

    return buildString {
        if (readings.size == 1) {
            entity.label?.let { append(it).append(": ") }
            append(readings.single().withTolerance())
        } else {
            append(entity.label ?: MeasureTool.parse(entity.kind).label)
            readings.forEach { append("\n").append(it.name).append(" ").append(it.withTolerance()) }
        }
        append("\nMeasured with Tape")
    }
}

/** Maps a persisted accuracy grade to its badge colour. */
@Composable
private fun gradeTint(grade: AccuracyGrade): androidx.compose.ui.graphics.Color = when (grade) {
    AccuracyGrade.HIGH        -> ConfidenceHigh
    AccuracyGrade.FAIR        -> ConfidenceMedium
    AccuracyGrade.LOW         -> ConfidenceLow
    AccuracyGrade.UNAVAILABLE -> ConfidenceLow
    AccuracyGrade.UNKNOWN     -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth(),
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = tint,
            )
        }
    }
}
