package com.ebookreader.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ebookreader.app.domain.model.PdfFitMode
import com.ebookreader.app.domain.model.ReaderFontFamily
import com.ebookreader.app.domain.model.ReaderSettings
import com.ebookreader.app.domain.model.ReaderTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.readerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "reader_preferences"
)

class ReaderPreferencesRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val FONT_SIZE_SP = floatPreferencesKey("font_size_sp")
        val LINE_HEIGHT = floatPreferencesKey("line_height_multiplier")
        val FONT_FAMILY = stringPreferencesKey("font_family")
        val PDF_INVERT_DARK = booleanPreferencesKey("pdf_invert_dark")
        val PDF_FIT_MODE = stringPreferencesKey("pdf_fit_mode")
        val PDF_TRIM_MARGINS = booleanPreferencesKey("pdf_trim_margins")
    }

    val settingsFlow: Flow<ReaderSettings> = context.readerDataStore.data.map { prefs ->
        val theme = prefs[Keys.THEME]
            ?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }
            ?: ReaderTheme.PAPER
        val fontFamily = prefs[Keys.FONT_FAMILY]
            ?.let { runCatching { ReaderFontFamily.valueOf(it) }.getOrNull() }
            ?: ReaderFontFamily.SERIF
        val pdfFitMode = prefs[Keys.PDF_FIT_MODE]
            ?.let { runCatching { PdfFitMode.valueOf(it) }.getOrNull() }
            ?: PdfFitMode.FIT_SCREEN
        ReaderSettings(
            theme = theme,
            fontSizeSp = (prefs[Keys.FONT_SIZE_SP] ?: 18f).coerceIn(14f, 28f),
            lineHeightMultiplier = (prefs[Keys.LINE_HEIGHT] ?: 1.5f).coerceIn(1.2f, 1.9f),
            fontFamily = fontFamily,
            pdfInvertInDark = prefs[Keys.PDF_INVERT_DARK] ?: false,
            pdfFitMode = pdfFitMode,
            pdfTrimMargins = prefs[Keys.PDF_TRIM_MARGINS] ?: true
        )
    }

    suspend fun updateTheme(theme: ReaderTheme) {
        context.readerDataStore.edit { it[Keys.THEME] = theme.name }
    }

    suspend fun updateFontSize(sizeSp: Float) {
        context.readerDataStore.edit { it[Keys.FONT_SIZE_SP] = sizeSp.coerceIn(14f, 28f) }
    }

    suspend fun updateLineHeight(multiplier: Float) {
        context.readerDataStore.edit { it[Keys.LINE_HEIGHT] = multiplier.coerceIn(1.2f, 1.9f) }
    }

    suspend fun updateFontFamily(family: ReaderFontFamily) {
        context.readerDataStore.edit { it[Keys.FONT_FAMILY] = family.name }
    }

    suspend fun updatePdfInvertInDark(invert: Boolean) {
        context.readerDataStore.edit { it[Keys.PDF_INVERT_DARK] = invert }
    }

    suspend fun updatePdfFitMode(mode: PdfFitMode) {
        context.readerDataStore.edit { it[Keys.PDF_FIT_MODE] = mode.name }
    }

    suspend fun updatePdfTrimMargins(trim: Boolean) {
        context.readerDataStore.edit { it[Keys.PDF_TRIM_MARGINS] = trim }
    }
}
