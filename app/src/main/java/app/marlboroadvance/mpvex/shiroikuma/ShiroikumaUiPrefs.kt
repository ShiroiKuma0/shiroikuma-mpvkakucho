package app.marlboroadvance.mpvex.shiroikuma

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File

/**
 * 白い熊 mpv拡張 UI — every attribute of the look, in one persisted record.
 *
 * The defaults ARE the fork's signature look: black background, pure-yellow (#FFFF00) text, accent
 * and borders, so a fresh install is black-and-yellow with no user action and every single one of
 * those values is settable from the UI page.
 *
 * Colours are packed ARGB ints (alpha included — the picker has a real A channel).
 * [fontFileName] selects a font: "" = follow the app default, [ShiroikumaUiStore.MONOSPACE] =
 * monospace, otherwise a .ttf/.otf imported into the app's private fonts directory.
 *
 * Sizes are plain Int dp/sp/percent so the page can drive every one of them from a slider, and all
 * the border/thickness knobs bottom out at 0 (= no border at all).
 */
data class ShiroikumaUiPrefs(
    // ---- Colours ---------------------------------------------------------------------------
    val background: Int = BLACK,
    val surface: Int = NEAR_BLACK,
    val text: Int = YELLOW,
    val textSecondary: Int = YELLOW_DIM,
    val accent: Int = YELLOW,
    val border: Int = YELLOW,
    val divider: Int = YELLOW_FAINT,
    val selection: Int = YELLOW_SELECT,
    val errorColor: Int = ERROR_RED,

    // ---- Player ----------------------------------------------------------------------------
    val playerControlTint: Int = YELLOW,
    val playerTextColor: Int = YELLOW,
    val seekbarPlayed: Int = YELLOW,
    val seekbarBuffered: Int = YELLOW_SELECT,
    val seekbarTrack: Int = YELLOW_FAINT,
    val playerButtonSizeDp: Int = 45,
    val playerButtonGapDp: Int = 4,
    val seekbarHeightDp: Int = 12,
    /** Half-gap punched out of the bar at each chapter boundary; 0 = no chapter markers. */
    val seekbarChapterMarkerDp: Int = 4,
    val overlayDimPct: Int = 80,

    // ---- Borders & shape -------------------------------------------------------------------
    val borderWidthDp: Int = 1,
    val cornerRadiusDp: Int = 12,
    val dividerThicknessDp: Int = 1,
    val cardBorderWidthDp: Int = 1,

    // ---- Typography ------------------------------------------------------------------------
    val fontFileName: String = "",
    val fontWeight: Int = 0, // 0 = leave each text style's own weight; else 100..900
    val fontScalePct: Int = 100,
    val titleSizeSp: Int = 16,
    val bodySizeSp: Int = 14,
    val labelSizeSp: Int = 12,

    // ---- File browser ----------------------------------------------------------------------
    val rowVPadDp: Int = 4,
    val rowGapDp: Int = 2,
    val thumbCornerDp: Int = 8,
    val thumbSizeDp: Int = 64,

    // ---- Settings pages --------------------------------------------------------------------
    val sectionTitleSizeSp: Int = 20,
    val sectionUnderlineDp: Int = 2,
    val indentStepDp: Int = 16,
    val settingsRowVPadDp: Int = 6,

    // ---- One-click colour swatches (most recently applied, newest first) --------------------
    val recentColors: List<Int> = DEFAULT_SWATCHES,
) {
    companion object {
        const val BLACK = 0xFF000000.toInt()
        const val NEAR_BLACK = 0xFF0D0D0D.toInt() // card/surface, subtly above the background
        const val YELLOW = 0xFFFFFF00.toInt() // pure yellow, NOT material amber #FFEB3B
        const val YELLOW_DIM = 0xCCFFFF00.toInt() // secondary text (~80%)
        const val YELLOW_FAINT = 0x66FFFF00 // dividers / seekbar track (~40%)
        const val YELLOW_SELECT = 0x33FFFF00 // selection fill (~20%)
        const val ERROR_RED = 0xFFFF5252.toInt() // the Kōjiki warn red

        /** Prefilled one-click choices in the colour picker — the house palette, ready to tap. */
        val DEFAULT_SWATCHES = listOf(
            YELLOW, BLACK, NEAR_BLACK, YELLOW_DIM, YELLOW_FAINT, YELLOW_SELECT,
            ERROR_RED, 0xFFFFFFFF.toInt(), 0xFF7FB4FF.toInt(), 0xFF00E676.toInt(),
        )

        const val MAX_RECENT_COLORS = 12

        // Slider ceilings — everything a slider drives has an explicit, documented range.
        const val BORDER_WIDTH_MAX = 8
        const val CORNER_RADIUS_MAX = 32
        const val DIVIDER_MAX = 6
        const val FONT_SCALE_MIN = 70
        const val FONT_SCALE_MAX = 160
        const val TEXT_SIZE_MIN = 8
        const val TEXT_SIZE_MAX = 34
        const val PAD_MAX = 32
        const val THUMB_SIZE_MIN = 32
        const val THUMB_SIZE_MAX = 160
        const val BUTTON_SIZE_MIN = 24
        const val BUTTON_SIZE_MAX = 96
        const val SEEKBAR_HEIGHT_MAX = 40
        const val CHAPTER_MARKER_MAX = 16
        const val INDENT_STEP_MAX = 40
        const val SECTION_TITLE_MIN = 12
        const val SECTION_TITLE_MAX = 34
    }
}

/** One option in the font picker. [fileName] is "", [ShiroikumaUiStore.MONOSPACE], or a file name. */
data class ShiroikumaFontOption(val displayName: String, val fileName: String)

/**
 * The persisted store behind [ShiroikumaUiPrefs].
 *
 * Backed by the `shiroikuma_ui_theme` SharedPreferences file — the same family name the sister
 * forks use, and the file the export's `appearance` category carries.
 */
object ShiroikumaUiStore {

    const val PREFS_FILE = "shiroikuma_ui_theme"
    const val MONOSPACE = "@monospace"

    private lateinit var appContext: Context

    private val _prefs = MutableStateFlow(ShiroikumaUiPrefs())
    val prefs: StateFlow<ShiroikumaUiPrefs> = _prefs.asStateFlow()

    private val fontFamilyCache = mutableMapOf<String, FontFamily?>()

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        _prefs.value = load()
    }

    private fun store() = appContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun update(transform: (ShiroikumaUiPrefs) -> ShiroikumaUiPrefs) {
        val next = transform(_prefs.value)
        _prefs.value = next
        save(next)
    }

    /** Restore every attribute to the signature black-yellow default. */
    fun resetToDefaults() {
        update { ShiroikumaUiPrefs() }
    }

    /** Remember a colour the user applied, so the picker can offer it as a one-click choice. */
    fun rememberColor(argb: Int) {
        update { current ->
            val next = (listOf(argb) + current.recentColors.filter { it != argb })
                .take(ShiroikumaUiPrefs.MAX_RECENT_COLORS)
            current.copy(recentColors = next)
        }
    }

    // ---- persistence -------------------------------------------------------------------------

    private fun load(): ShiroikumaUiPrefs {
        val p = store()
        val d = ShiroikumaUiPrefs()
        fun i(key: String, def: Int) = p.getInt(key, def)
        fun s(key: String, def: String) = p.getString(key, def) ?: def
        return ShiroikumaUiPrefs(
            background = i("background", d.background),
            surface = i("surface", d.surface),
            text = i("text", d.text),
            textSecondary = i("textSecondary", d.textSecondary),
            accent = i("accent", d.accent),
            border = i("border", d.border),
            divider = i("divider", d.divider),
            selection = i("selection", d.selection),
            errorColor = i("errorColor", d.errorColor),
            playerControlTint = i("playerControlTint", d.playerControlTint),
            playerTextColor = i("playerTextColor", d.playerTextColor),
            seekbarPlayed = i("seekbarPlayed", d.seekbarPlayed),
            seekbarBuffered = i("seekbarBuffered", d.seekbarBuffered),
            seekbarTrack = i("seekbarTrack", d.seekbarTrack),
            playerButtonSizeDp = i("playerButtonSizeDp", d.playerButtonSizeDp),
            playerButtonGapDp = i("playerButtonGapDp", d.playerButtonGapDp),
            seekbarHeightDp = i("seekbarHeightDp", d.seekbarHeightDp),
            seekbarChapterMarkerDp = i("seekbarChapterMarkerDp", d.seekbarChapterMarkerDp),
            overlayDimPct = i("overlayDimPct", d.overlayDimPct),
            borderWidthDp = i("borderWidthDp", d.borderWidthDp),
            cornerRadiusDp = i("cornerRadiusDp", d.cornerRadiusDp),
            dividerThicknessDp = i("dividerThicknessDp", d.dividerThicknessDp),
            cardBorderWidthDp = i("cardBorderWidthDp", d.cardBorderWidthDp),
            fontFileName = s("fontFileName", d.fontFileName),
            fontWeight = i("fontWeight", d.fontWeight),
            fontScalePct = i("fontScalePct", d.fontScalePct),
            titleSizeSp = i("titleSizeSp", d.titleSizeSp),
            bodySizeSp = i("bodySizeSp", d.bodySizeSp),
            labelSizeSp = i("labelSizeSp", d.labelSizeSp),
            rowVPadDp = i("rowVPadDp", d.rowVPadDp),
            rowGapDp = i("rowGapDp", d.rowGapDp),
            thumbCornerDp = i("thumbCornerDp", d.thumbCornerDp),
            thumbSizeDp = i("thumbSizeDp", d.thumbSizeDp),
            sectionTitleSizeSp = i("sectionTitleSizeSp", d.sectionTitleSizeSp),
            sectionUnderlineDp = i("sectionUnderlineDp", d.sectionUnderlineDp),
            indentStepDp = i("indentStepDp", d.indentStepDp),
            settingsRowVPadDp = i("settingsRowVPadDp", d.settingsRowVPadDp),
            recentColors = decodeColors(p.getString("recentColors", null)) ?: d.recentColors,
        )
    }

    private fun save(v: ShiroikumaUiPrefs) {
        store().edit().apply {
            putInt("background", v.background)
            putInt("surface", v.surface)
            putInt("text", v.text)
            putInt("textSecondary", v.textSecondary)
            putInt("accent", v.accent)
            putInt("border", v.border)
            putInt("divider", v.divider)
            putInt("selection", v.selection)
            putInt("errorColor", v.errorColor)
            putInt("playerControlTint", v.playerControlTint)
            putInt("playerTextColor", v.playerTextColor)
            putInt("seekbarPlayed", v.seekbarPlayed)
            putInt("seekbarBuffered", v.seekbarBuffered)
            putInt("seekbarTrack", v.seekbarTrack)
            putInt("playerButtonSizeDp", v.playerButtonSizeDp)
            putInt("playerButtonGapDp", v.playerButtonGapDp)
            putInt("seekbarHeightDp", v.seekbarHeightDp)
            putInt("seekbarChapterMarkerDp", v.seekbarChapterMarkerDp)
            putInt("overlayDimPct", v.overlayDimPct)
            putInt("borderWidthDp", v.borderWidthDp)
            putInt("cornerRadiusDp", v.cornerRadiusDp)
            putInt("dividerThicknessDp", v.dividerThicknessDp)
            putInt("cardBorderWidthDp", v.cardBorderWidthDp)
            putString("fontFileName", v.fontFileName)
            putInt("fontWeight", v.fontWeight)
            putInt("fontScalePct", v.fontScalePct)
            putInt("titleSizeSp", v.titleSizeSp)
            putInt("bodySizeSp", v.bodySizeSp)
            putInt("labelSizeSp", v.labelSizeSp)
            putInt("rowVPadDp", v.rowVPadDp)
            putInt("rowGapDp", v.rowGapDp)
            putInt("thumbCornerDp", v.thumbCornerDp)
            putInt("thumbSizeDp", v.thumbSizeDp)
            putInt("sectionTitleSizeSp", v.sectionTitleSizeSp)
            putInt("sectionUnderlineDp", v.sectionUnderlineDp)
            putInt("indentStepDp", v.indentStepDp)
            putInt("settingsRowVPadDp", v.settingsRowVPadDp)
            putString("recentColors", encodeColors(v.recentColors))
        }.apply()
    }

    /** Re-read from disk — used after an import replaces the prefs file underneath us. */
    fun reload() {
        if (::appContext.isInitialized) _prefs.value = load()
    }

    private fun encodeColors(colors: List<Int>): String =
        JSONArray().apply { colors.forEach { put(it) } }.toString()

    private fun decodeColors(raw: String?): List<Int>? = raw?.let {
        runCatching {
            val arr = JSONArray(it)
            (0 until arr.length()).map { idx -> arr.getInt(idx) }
        }.getOrNull()
    }

    // ---- external fonts ----------------------------------------------------------------------

    fun fontsDir(): File = File(appContext.filesDir, "fonts").apply { mkdirs() }

    fun availableFonts(): List<ShiroikumaFontOption> {
        val options = mutableListOf(
            ShiroikumaFontOption("App default", ""),
            ShiroikumaFontOption("Monospace", MONOSPACE),
        )
        fontsDir().listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in FONT_EXTENSIONS }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { options.add(ShiroikumaFontOption(it.nameWithoutExtension, it.name)) }
        return options
    }

    fun displayNameFor(fileName: String): String = when {
        fileName.isEmpty() -> "App default"
        fileName == MONOSPACE -> "Monospace"
        else -> File(fileName).nameWithoutExtension
    }

    /** null = use the caller's own default family (system, or whatever the text style carries). */
    fun fontFamily(fileName: String): FontFamily? = when {
        fileName.isEmpty() -> null
        fileName == MONOSPACE -> FontFamily.Monospace
        else -> fontFamilyCache.getOrPut(fileName) {
            runCatching {
                val file = File(fontsDir(), fileName)
                if (file.isFile) FontFamily(Font(file)) else null
            }.getOrNull()
        }
    }

    /** Copy a user-picked .ttf/.otf into the private fonts directory. Returns the stored name. */
    fun importFont(uri: Uri): String? = runCatching {
        val rawName = queryDisplayName(uri) ?: "font-${System.currentTimeMillis()}.ttf"
        val dest = File(fontsDir(), sanitize(rawName))
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        fontFamilyCache.remove(dest.name)
        dest.name
    }.getOrNull()

    fun deleteFont(fileName: String): Boolean {
        if (fileName.isEmpty() || fileName == MONOSPACE) return false
        val deleted = runCatching { File(fontsDir(), fileName).delete() }.getOrDefault(false)
        fontFamilyCache.remove(fileName)
        // Anything still pointing at the deleted file falls back to the app default.
        update { current ->
            if (current.fontFileName == fileName) current.copy(fontFileName = "") else current
        }
        return deleted
    }

    private fun queryDisplayName(uri: Uri): String? =
        appContext.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }

    private fun sanitize(name: String): String {
        val cleaned = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (cleaned.substringAfterLast('.', "").lowercase() in FONT_EXTENSIONS) {
            cleaned
        } else {
            "$cleaned.ttf"
        }
    }

    private val FONT_EXTENSIONS = setOf("ttf", "otf")
}
