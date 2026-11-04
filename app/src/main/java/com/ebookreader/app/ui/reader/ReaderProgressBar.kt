package com.ebookreader.app.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ebookreader.app.domain.model.ReadingProgressCalculator
import com.ebookreader.app.ui.theme.ReaderPalette
import kotlin.math.roundToInt

/**
 * Subtle always-visible ambient progress strip anchored to the bottom of the screen while reading.
 */
@Composable
fun AmbientReadingProgressStrip(
    currentPage: Int,
    totalPages: Int,
    progress: Float,
    chapterTitle: String,
    palette: ReaderPalette,
    modifier: Modifier = Modifier
) {
    val percent = (progress.coerceIn(0f, 1f) * 100f).roundToInt()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        if (totalPages > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = chapterTitle,
                    fontSize = 11.sp,
                    color = palette.subtleText.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "${currentPage + 1} / $totalPages  ·  $percent%",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.subtleText.copy(alpha = 0.9f)
                )
            }
        }

        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            color = palette.accent.copy(alpha = 0.78f),
            trackColor = palette.subtleText.copy(alpha = 0.16f),
            strokeCap = StrokeCap.Butt,
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
        )
    }
}

/**
 * Interactive HUD scrubber revealed when tapping the center of a page.
 */
@Composable
fun InteractiveProgressScrubberHud(
    visible: Boolean,
    currentPage: Int,
    totalPages: Int,
    chapterTitleForPage: (Int) -> String,
    returnPageBeforeJump: Int?,
    palette: ReaderPalette,
    onJumpToPage: (Int) -> Unit,
    onJumpBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragPageFloat by remember { mutableFloatStateOf(currentPage.toFloat()) }
    var isEditingPage by remember { mutableStateOf(false) }
    var pageInputText by remember { mutableStateOf("") }

    LaunchedEffect(visible) {
        if (!visible) isEditingPage = false
    }

    LaunchedEffect(currentPage, isDragging) {
        if (!isDragging) {
            dragPageFloat = currentPage.toFloat()
        }
    }

    val previewPage = if (isDragging) {
        dragPageFloat.roundToInt().coerceIn(0, (totalPages - 1).coerceAtLeast(0))
    } else {
        currentPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
    }

    val previewProgress = ReadingProgressCalculator.computeProgress(previewPage, totalPages)
    val previewPercent = (previewProgress * 100f).roundToInt()
    val previewTitle = remember(previewPage, totalPages) {
        chapterTitleForPage(previewPage)
    }

    AnimatedVisibility(
        visible = visible && totalPages > 0,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (returnPageBeforeJump != null) {
                AssistChip(
                    onClick = onJumpBack,
                    label = { Text("Return to page ${returnPageBeforeJump + 1}") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = palette.hudContainer,
                        labelColor = palette.onHudContainer,
                        leadingIconContentColor = palette.accent
                    ),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Surface(
                shape = RoundedCornerShape(22.dp),
                color = palette.hudContainer,
                contentColor = palette.onHudContainer,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = previewTitle.ifBlank { "Page ${previewPage + 1}" },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (palette.isDark) palette.onHudContainer.copy(alpha = 0.8f) else Color.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(12.dp))
                        Surface(
                            onClick = {
                                pageInputText = (currentPage + 1).toString()
                                isEditingPage = !isEditingPage
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = palette.accent.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = "Page ${previewPage + 1} of $totalPages · $previewPercent% ✎",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = palette.accent,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    AnimatedVisibility(visible = isEditingPage) {
                        val parsedTarget = ReadingProgressCalculator.parseTargetPage(pageInputText, totalPages)
                        val pageInputTextColor = if (palette.isDark) palette.onHudContainer else Color.Black
                        val commitJump = {
                            if (parsedTarget != null) {
                                isEditingPage = false
                                if (parsedTarget != currentPage) {
                                    onJumpToPage(parsedTarget)
                                }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 4.dp)
                        ) {
                            OutlinedTextField(
                                value = pageInputText,
                                onValueChange = { value ->
                                    pageInputText = value.filter { it.isDigit() }
                                },
                                label = {
                                    Text(
                                        text = "Go to page (1–$totalPages)",
                                        color = pageInputTextColor
                                    )
                                },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = pageInputTextColor,
                                    unfocusedTextColor = pageInputTextColor,
                                    focusedLabelColor = pageInputTextColor,
                                    unfocusedLabelColor = pageInputTextColor,
                                    cursorColor = pageInputTextColor,
                                    focusedBorderColor = palette.accent,
                                    unfocusedBorderColor = pageInputTextColor.copy(alpha = 0.5f)
                                ),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Go
                                ),
                                keyboardActions = KeyboardActions(onGo = { commitJump() }),
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = commitJump,
                                enabled = parsedTarget != null,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = palette.accent,
                                    contentColor = if (palette.isDark) Color.Black else Color.White
                                )
                            ) {
                                Text("Go")
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(
                            onClick = {
                                if (currentPage > 0) onJumpToPage(currentPage - 1)
                            },
                            enabled = currentPage > 0,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "Previous page"
                            )
                        }

                        if (totalPages > 1) {
                            Slider(
                                value = dragPageFloat.coerceIn(0f, (totalPages - 1).toFloat()),
                                onValueChange = { value ->
                                    isDragging = true
                                    dragPageFloat = value
                                },
                                onValueChangeFinished = {
                                    val target = dragPageFloat.roundToInt()
                                        .coerceIn(0, totalPages - 1)
                                    isDragging = false
                                    if (target != currentPage) {
                                        onJumpToPage(target)
                                    }
                                },
                                valueRange = 0f..(totalPages - 1).toFloat(),
                                colors = SliderDefaults.colors(
                                    thumbColor = palette.accent,
                                    activeTrackColor = palette.accent,
                                    inactiveTrackColor = palette.onHudContainer.copy(alpha = 0.18f)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 4.dp)
                            )
                        } else {
                            LinearProgressIndicator(
                                progress = { 1f },
                                color = palette.accent,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp)
                                    .height(4.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                if (currentPage < totalPages - 1) onJumpToPage(currentPage + 1)
                            },
                            enabled = currentPage < totalPages - 1,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "Next page"
                            )
                        }
                    }
                }
            }
        }
    }
}
