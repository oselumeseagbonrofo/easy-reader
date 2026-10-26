package com.ebookreader.app.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ebookreader.app.domain.model.SearchMatch
import com.ebookreader.app.ui.theme.ReaderPalette

@Composable
fun InBookSearchOverlay(
    query: String,
    isSearching: Boolean,
    matches: List<SearchMatch>,
    activeMatchIndex: Int,
    isResultsExpanded: Boolean,
    palette: ReaderPalette,
    onQueryChange: (String) -> Unit,
    onSelectMatch: (Int) -> Unit,
    onStepMatch: (Int) -> Unit,
    onToggleResultsExpanded: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = palette.hudContainer,
            contentColor = palette.onHudContainer,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Exit search"
                        )
                    }

                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        placeholder = { Text("Search in book…") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = { focusManager.clearFocus() }
                        ),
                        trailingIcon = {
                            if (isSearching) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    color = palette.accent,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else if (query.isNotEmpty()) {
                                IconButton(onClick = { onQueryChange("") }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear query")
                                }
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = palette.onHudContainer,
                            unfocusedTextColor = palette.onHudContainer,
                            focusedBorderColor = palette.accent,
                            unfocusedBorderColor = palette.onHudContainer.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                    )

                    if (matches.isNotEmpty()) {
                        IconButton(onClick = { onToggleResultsExpanded(!isResultsExpanded) }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.List,
                                contentDescription = "Toggle match list",
                                tint = if (isResultsExpanded) palette.accent else palette.onHudContainer
                            )
                        }
                    }
                }

                // Match stepper bar
                if (query.trim().length >= 2 && !isSearching) {
                    HorizontalDivider(color = palette.onHudContainer.copy(alpha = 0.1f))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (matches.isEmpty()) {
                                "No matches found"
                            } else {
                                "Match ${(activeMatchIndex + 1).coerceAtLeast(1)} of ${matches.size}"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.accent
                        )

                        if (matches.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        focusManager.clearFocus()
                                        onStepMatch(-1)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = "Previous match"
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        focusManager.clearFocus()
                                        onStepMatch(1)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Next match"
                                    )
                                }
                            }
                        }
                    }
                }

                // Expandable results snippet list
                if (isResultsExpanded && matches.isNotEmpty()) {
                    HorizontalDivider(color = palette.onHudContainer.copy(alpha = 0.12f))
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                    ) {
                        itemsIndexed(matches) { idx, match ->
                            val isSelected = idx == activeMatchIndex
                            val styledSnippet = remember(match, palette) {
                                val b = AnnotatedString.Builder(match.snippet)
                                if (match.matchEndInSnippet > match.matchStartInSnippet &&
                                    match.matchEndInSnippet <= match.snippet.length
                                ) {
                                    b.addStyle(
                                        SpanStyle(
                                            fontWeight = FontWeight.Bold,
                                            background = palette.activeSearchHighlight
                                        ),
                                        match.matchStartInSnippet,
                                        match.matchEndInSnippet
                                    )
                                }
                                b.toAnnotatedString()
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        focusManager.clearFocus()
                                        onSelectMatch(idx)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = match.chapterTitle,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = palette.accent,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "p. ${match.pageIndex + 1}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = palette.onHudContainer.copy(alpha = 0.7f)
                                    )
                                }
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = styledSnippet,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.onHudContainer,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            HorizontalDivider(color = palette.onHudContainer.copy(alpha = 0.08f))
                        }
                    }
                }
            }
        }
    }
}
