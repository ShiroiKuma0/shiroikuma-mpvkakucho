package app.marlboroadvance.mpvex.shiroikuma

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.documentfile.provider.DocumentFile
import app.marlboroadvance.mpvex.BuildConfig
import app.marlboroadvance.mpvex.database.MpvExDatabase
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/** Kōjiki warn red — the directory-unset / failure colour, red until a directory is chosen. */
private val WarnColor = Color(0xFFFF5252)

/** Import cap for the settings ZIP (fonts ride along, so above a JSON-only cap). */
private const val IMPORT_MAX_BYTES = 128 * 1024 * 1024

/**
 * "白い熊 mpv拡張 UI" — the appearance-customization page, in the sister-fork house style.
 *
 * kxkb page look: each section is a 20sp accent heading underlined only as wide as its own text,
 * preceded by a thin full-width hairline; every item is indented one step under its heading and
 * each sub-level one step further, so the level you are on is obvious at a glance. Row padding is
 * deliberately tight — the only real breathing space is between top-level sections.
 *
 * Everything on this page is live: moving a slider or applying a colour repaints the app at once,
 * and each group carries a preview of exactly what it controls.
 */
@Serializable
object ShiroikumaUiScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val backstack = LocalBackStack.current
        ShiroikumaUiPage(onBack = { backstack.removeLastOrNull() })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShiroikumaUiPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by ShiroikumaUiStore.prefs.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var colorTarget by remember { mutableStateOf<ColorTarget?>(null) }
    var fontPickerOpen by remember { mutableStateOf(false) }
    var fontsRefresh by remember { mutableIntStateOf(0) }
    var showEximPanel by remember { mutableStateOf(false) }
    var eximRefresh by remember { mutableIntStateOf(0) }
    var showResetConfirm by remember { mutableStateOf(false) }

    var automationEnabled by remember { mutableStateOf(AutomationAuth.enabled(context)) }
    var automationToken by remember { mutableStateOf(AutomationAuth.token(context)) }

    // Queried on opening the page (and after any change) — "latest export in the chosen directory".
    val eximStatus by produceState<Pair<String?, ShiroikumaBackup.LatestExport?>>(
        initialValue = null to null,
        eximRefresh,
    ) {
        value = withContext(Dispatchers.IO) {
            ShiroikumaBackup.dirLabel(context) to ShiroikumaBackup.latestExport(context)
        }
    }

    val dirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ShiroikumaBackup.setDirUri(context, uri)
            eximRefresh++
        }
    }
    val fontImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val stored = ShiroikumaUiStore.importFont(uri)
            fontsRefresh++
            scope.launch {
                snackbarHostState.showSnackbar(
                    if (stored != null) "Font added" else "Could not read that font file",
                )
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "白い熊 mpv拡張 UI",
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            // ---- Export / Import — the first separated section (Kōjiki UI-page flow) ------------
            item { SectionHeader("Export / Import", prefs, first = true) }
            item {
                RowScaffold(1, prefs, onClick = { dirPicker.launch(ShiroikumaBackup.dirUri(context)) }) {
                    Column(Modifier.weight(1f)) {
                        Text("Export directory (tap to choose)", style = MaterialTheme.typography.bodyLarge)
                        val dirName = eximStatus.first
                        Text(
                            dirName ?: "Not set — tap to choose a directory",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (dirName == null) WarnColor else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            item {
                val (dirName, latest) = eximStatus
                val (message, warn) = when {
                    dirName == null -> "No directory set yet — pick one to enable one-tap export." to true
                    latest == null -> "No export in this directory yet." to false
                    else -> "Last export: ${latest.timestampText} (${ShiroikumaBackup.humanSize(latest.sizeBytes)})" to false
                }
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (warn) WarnColor else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = indent(1, prefs), end = 16.dp, bottom = 6.dp),
                )
            }
            item {
                RowScaffold(1, prefs, onClick = { showEximPanel = true }) {
                    Column(Modifier.weight(1f)) {
                        Text("Export / Import…", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Back up or restore everything settable — this UI, all app settings, playlists, history and network connections — as one ZIP.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // Automation lives INSIDE this section, right below the export rows (family contract).
            item {
                SwitchRow(
                    level = 1,
                    prefs = prefs,
                    label = "Automation export",
                    description = "Let sister-app tasks trigger this app's export via the token-gated EXPORT_STATE intent.",
                    checked = automationEnabled,
                ) {
                    automationEnabled = it
                    AutomationAuth.setEnabled(context, it)
                }
            }
            item {
                val clipboard = LocalClipboardManager.current
                RowScaffold(1, prefs, onClick = {
                    clipboard.setText(AnnotatedString(automationToken))
                    scope.launch { snackbarHostState.showSnackbar("Automation token copied") }
                }) {
                    Column(Modifier.weight(1f)) {
                        Text("Automation token (tap to copy)", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${automationToken.take(8)}…${automationToken.takeLast(8)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        "Regenerate",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = WarnColor,
                        modifier = Modifier.clickable {
                            automationToken = AutomationAuth.regenerateToken(context)
                            scope.launch {
                                snackbarHostState.showSnackbar("Token regenerated — update pasted copies")
                            }
                        },
                    )
                }
            }

            // ---- Colours -----------------------------------------------------------------------
            item { SectionHeader("Colours", prefs) }
            item { PreviewCard(1, prefs) { ColourPreview(prefs) } }
            item { SubHeader("Base", 1, prefs) }
            item { ColorRow(2, prefs, "Background", prefs.background, ColorTarget.Background) { colorTarget = it } }
            item { ColorRow(2, prefs, "Surface / cards", prefs.surface, ColorTarget.Surface) { colorTarget = it } }
            item { ColorRow(2, prefs, "Accent", prefs.accent, ColorTarget.Accent) { colorTarget = it } }
            item { SubHeader("Text", 1, prefs) }
            item { ColorRow(2, prefs, "Primary text", prefs.text, ColorTarget.Text) { colorTarget = it } }
            item { ColorRow(2, prefs, "Secondary text", prefs.textSecondary, ColorTarget.TextSecondary) { colorTarget = it } }
            item { SubHeader("Lines & fills", 1, prefs) }
            item { ColorRow(2, prefs, "Border", prefs.border, ColorTarget.Border) { colorTarget = it } }
            item { ColorRow(2, prefs, "Divider", prefs.divider, ColorTarget.Divider) { colorTarget = it } }
            item { ColorRow(2, prefs, "Selection fill", prefs.selection, ColorTarget.Selection) { colorTarget = it } }
            item { ColorRow(2, prefs, "Error / warning", prefs.errorColor, ColorTarget.Error) { colorTarget = it } }

            // ---- Typography --------------------------------------------------------------------
            item { SectionHeader("Typography", prefs) }
            item { PreviewCard(1, prefs) { TypographyPreview(prefs) } }
            item {
                RowScaffold(1, prefs, onClick = { fontPickerOpen = true }) {
                    Text("Font", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        ShiroikumaUiStore.displayNameFor(prefs.fontFileName),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // Render the current choice in its OWN glyphs.
                        fontFamily = ShiroikumaUiStore.fontFamily(prefs.fontFileName) ?: FontFamily.Default,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item {
                SliderRow(
                    1, prefs, "Weight",
                    prefs.fontWeight, if (prefs.fontWeight == 0) "Per style" else "${prefs.fontWeight}",
                    0f..900f, steps = 9,
                ) { v -> ShiroikumaUiStore.update { it.copy(fontWeight = (v / 100) * 100) } }
            }
            item {
                SliderRow(
                    1, prefs, "Text scale", prefs.fontScalePct, "${prefs.fontScalePct} %",
                    ShiroikumaUiPrefs.FONT_SCALE_MIN.toFloat()..ShiroikumaUiPrefs.FONT_SCALE_MAX.toFloat(),
                ) { v -> ShiroikumaUiStore.update { it.copy(fontScalePct = v) } }
            }
            item { SubHeader("Sizes", 1, prefs) }
            item {
                SliderRow(
                    2, prefs, "Title", prefs.titleSizeSp, "${prefs.titleSizeSp} sp",
                    ShiroikumaUiPrefs.TEXT_SIZE_MIN.toFloat()..ShiroikumaUiPrefs.TEXT_SIZE_MAX.toFloat(),
                ) { v -> ShiroikumaUiStore.update { it.copy(titleSizeSp = v) } }
            }
            item {
                SliderRow(
                    2, prefs, "Body", prefs.bodySizeSp, "${prefs.bodySizeSp} sp",
                    ShiroikumaUiPrefs.TEXT_SIZE_MIN.toFloat()..ShiroikumaUiPrefs.TEXT_SIZE_MAX.toFloat(),
                ) { v -> ShiroikumaUiStore.update { it.copy(bodySizeSp = v) } }
            }
            item {
                SliderRow(
                    2, prefs, "Label", prefs.labelSizeSp, "${prefs.labelSizeSp} sp",
                    ShiroikumaUiPrefs.TEXT_SIZE_MIN.toFloat()..ShiroikumaUiPrefs.TEXT_SIZE_MAX.toFloat(),
                ) { v -> ShiroikumaUiStore.update { it.copy(labelSizeSp = v) } }
            }

            // ---- Borders & shape ---------------------------------------------------------------
            item { SectionHeader("Borders & shape", prefs) }
            item { PreviewCard(1, prefs) { ShapePreview(prefs) } }
            item {
                SliderRow(
                    1, prefs, "Border width", prefs.borderWidthDp, dpLabel(prefs.borderWidthDp),
                    0f..ShiroikumaUiPrefs.BORDER_WIDTH_MAX.toFloat(),
                ) { v -> ShiroikumaUiStore.update { it.copy(borderWidthDp = v) } }
            }
            item {
                SliderRow(
                    1, prefs, "Card border width", prefs.cardBorderWidthDp, dpLabel(prefs.cardBorderWidthDp),
                    0f..ShiroikumaUiPrefs.BORDER_WIDTH_MAX.toFloat(),
                ) { v -> ShiroikumaUiStore.update { it.copy(cardBorderWidthDp = v) } }
            }
            item {
                SliderRow(
                    1, prefs, "Corner roundness", prefs.cornerRadiusDp, dpLabel(prefs.cornerRadiusDp),
                    0f..ShiroikumaUiPrefs.CORNER_RADIUS_MAX.toFloat(),
                ) { v -> ShiroikumaUiStore.update { it.copy(cornerRadiusDp = v) } }
            }
            item {
                SliderRow(
                    1, prefs, "Divider thickness", prefs.dividerThicknessDp, dpLabel(prefs.dividerThicknessDp),
                    0f..ShiroikumaUiPrefs.DIVIDER_MAX.toFloat(),
                ) { v -> ShiroikumaUiStore.update { it.copy(dividerThicknessDp = v) } }
            }

            // ---- Player ------------------------------------------------------------------------
            item { SectionHeader("Player", prefs) }
            item { PreviewCard(1, prefs) { PlayerPreview(prefs) } }
            item { SubHeader("Colours", 1, prefs) }
            item { ColorRow(2, prefs, "Control tint", prefs.playerControlTint, ColorTarget.PlayerControlTint) { colorTarget = it } }
            item { ColorRow(2, prefs, "Overlay text", prefs.playerTextColor, ColorTarget.PlayerText) { colorTarget = it } }
            item { SubHeader("Seekbar", 1, prefs) }
            item { ColorRow(2, prefs, "Played", prefs.seekbarPlayed, ColorTarget.SeekbarPlayed) { colorTarget = it } }
            item { ColorRow(2, prefs, "Buffered", prefs.seekbarBuffered, ColorTarget.SeekbarBuffered) { colorTarget = it } }
            item { ColorRow(2, prefs, "Track", prefs.seekbarTrack, ColorTarget.SeekbarTrack) { colorTarget = it } }
            item {
                SliderRow(
                    2, prefs, "Height", prefs.seekbarHeightDp, dpLabel(prefs.seekbarHeightDp),
                    0f..ShiroikumaUiPrefs.SEEKBAR_HEIGHT_MAX.toFloat(),
                ) { v -> ShiroikumaUiStore.update { it.copy(seekbarHeightDp = v) } }
            }
            item {
                SliderRow(
                    2, prefs, "Chapter marker width", prefs.seekbarChapterMarkerDp,
                    if (prefs.seekbarChapterMarkerDp == 0) "Off — no markers" else "${prefs.seekbarChapterMarkerDp} dp",
                    0f..ShiroikumaUiPrefs.CHAPTER_MARKER_MAX.toFloat(),
                ) { v -> ShiroikumaUiStore.update { it.copy(seekbarChapterMarkerDp = v) } }
            }
            item { SubHeader("Controls", 1, prefs) }
            item {
                SliderRow(
                    2, prefs, "Button size", prefs.playerButtonSizeDp, dpLabel(prefs.playerButtonSizeDp),
                    ShiroikumaUiPrefs.BUTTON_SIZE_MIN.toFloat()..ShiroikumaUiPrefs.BUTTON_SIZE_MAX.toFloat(),
                ) { v -> ShiroikumaUiStore.update { it.copy(playerButtonSizeDp = v) } }
            }
            item {
                SliderRow(
                    2, prefs, "Gap between buttons", prefs.playerButtonGapDp, dpLabel(prefs.playerButtonGapDp),
                    0f..ShiroikumaUiPrefs.PAD_MAX.toFloat(),
                ) { v -> ShiroikumaUiStore.update { it.copy(playerButtonGapDp = v) } }
            }
            item {
                SliderRow(
                    2, prefs, "Overlay dim", prefs.overlayDimPct, "${prefs.overlayDimPct} %", 0f..100f,
                ) { v -> ShiroikumaUiStore.update { it.copy(overlayDimPct = v) } }
            }

            // ---- File browser ------------------------------------------------------------------
            item { SectionHeader("File browser", prefs) }
            item { PreviewCard(1, prefs) { BrowserPreview(prefs) } }
            item {
                SliderRow(
                    1, prefs, "Row padding", prefs.rowVPadDp, dpLabel(prefs.rowVPadDp),
                    0f..ShiroikumaUiPrefs.PAD_MAX.toFloat(),
                ) { v -> ShiroikumaUiStore.update { it.copy(rowVPadDp = v) } }
            }
            item {
                SliderRow(
                    1, prefs, "Gap between rows", prefs.rowGapDp, dpLabel(prefs.rowGapDp),
                    0f..ShiroikumaUiPrefs.PAD_MAX.toFloat(),
                ) { v -> ShiroikumaUiStore.update { it.copy(rowGapDp = v) } }
            }
            item {
                SliderRow(
                    1, prefs, "Thumbnail size", prefs.thumbSizeDp, dpLabel(prefs.thumbSizeDp),
                    ShiroikumaUiPrefs.THUMB_SIZE_MIN.toFloat()..ShiroikumaUiPrefs.THUMB_SIZE_MAX.toFloat(),
                ) { v -> ShiroikumaUiStore.update { it.copy(thumbSizeDp = v) } }
            }
            item {
                SliderRow(
                    1, prefs, "Thumbnail roundness", prefs.thumbCornerDp, dpLabel(prefs.thumbCornerDp),
                    0f..ShiroikumaUiPrefs.CORNER_RADIUS_MAX.toFloat(),
                ) { v -> ShiroikumaUiStore.update { it.copy(thumbCornerDp = v) } }
            }

            // ---- Settings pages ----------------------------------------------------------------
            item { SectionHeader("Settings pages", prefs) }
            item {
                Text(
                    "These shape this very page — the heading size, its underline, how far each level indents, and how tight the rows sit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = indent(1, prefs), end = 16.dp, bottom = 6.dp),
                )
            }
            item {
                SliderRow(
                    1, prefs, "Heading size", prefs.sectionTitleSizeSp, "${prefs.sectionTitleSizeSp} sp",
                    ShiroikumaUiPrefs.SECTION_TITLE_MIN.toFloat()..ShiroikumaUiPrefs.SECTION_TITLE_MAX.toFloat(),
                ) { v -> ShiroikumaUiStore.update { it.copy(sectionTitleSizeSp = v) } }
            }
            item {
                SliderRow(
                    1, prefs, "Heading underline", prefs.sectionUnderlineDp, dpLabel(prefs.sectionUnderlineDp),
                    0f..ShiroikumaUiPrefs.DIVIDER_MAX.toFloat(),
                ) { v -> ShiroikumaUiStore.update { it.copy(sectionUnderlineDp = v) } }
            }
            item {
                SliderRow(
                    1, prefs, "Indent per level", prefs.indentStepDp, dpLabel(prefs.indentStepDp),
                    0f..ShiroikumaUiPrefs.INDENT_STEP_MAX.toFloat(),
                ) { v -> ShiroikumaUiStore.update { it.copy(indentStepDp = v) } }
            }
            item {
                SliderRow(
                    1, prefs, "Row padding", prefs.settingsRowVPadDp, dpLabel(prefs.settingsRowVPadDp),
                    0f..ShiroikumaUiPrefs.PAD_MAX.toFloat(),
                ) { v -> ShiroikumaUiStore.update { it.copy(settingsRowVPadDp = v) } }
            }

            // ---- Reset -------------------------------------------------------------------------
            item { SectionHeader("Reset", prefs) }
            item {
                RowScaffold(1, prefs, onClick = { showResetConfirm = true }) {
                    Column(Modifier.weight(1f)) {
                        Text("Restore the black-yellow defaults", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Puts every colour, font and size on this page back to the signature look.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    // ---- dialogs ---------------------------------------------------------------------------

    colorTarget?.let { target ->
        ColorPickerDialog(
            title = target.label,
            initial = target.get(prefs),
            swatches = prefs.recentColors,
            onDismiss = { colorTarget = null },
            onConfirm = { argb ->
                ShiroikumaUiStore.update { target.set(it, argb) }
                ShiroikumaUiStore.rememberColor(argb)
                colorTarget = null
            },
        )
    }

    if (fontPickerOpen) {
        val fonts = remember(fontsRefresh) { ShiroikumaUiStore.availableFonts() }
        FontPickerDialog(
            current = prefs.fontFileName,
            fonts = fonts,
            onDismiss = { fontPickerOpen = false },
            onPick = { fileName ->
                ShiroikumaUiStore.update { it.copy(fontFileName = fileName) }
                fontPickerOpen = false
            },
            onAddFont = { fontImportLauncher.launch(arrayOf("*/*")) },
            onDelete = { fileName ->
                ShiroikumaUiStore.deleteFont(fileName)
                fontsRefresh++
            },
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Restore defaults?") },
            text = { Text("Every colour, font and size on this page returns to the black-yellow default.") },
            confirmButton = {
                TextButton(onClick = {
                    ShiroikumaUiStore.resetToDefaults()
                    showResetConfirm = false
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") } },
        )
    }

    if (showEximPanel) {
        ExportImportPanel(
            onDismiss = { showEximPanel = false },
            onCloseChain = {
                // Success acknowledged: close the panel AND the UI page beneath it.
                showEximPanel = false
                onBack()
            },
            onDirChanged = { eximRefresh++ },
        )
    }
}

// ---- structure -------------------------------------------------------------------------------

/** Indent cascade: level 0 sits flush at 16dp, each level steps by the settable indent unit. */
private fun indent(level: Int, p: ShiroikumaUiPrefs) = (16 + level * p.indentStepDp).dp

private fun dpLabel(v: Int) = if (v == 0) "Off (0 dp)" else "$v dp"

/**
 * kxkb-style section heading: a big bold accent title underlined only as wide as the text
 * (IntrinsicSize.Min sizes the column to its single line), each section preceded by a thin
 * full-width hairline. The gap above a heading is the only generous padding on the page.
 */
@Composable
private fun SectionHeader(title: String, p: ShiroikumaUiPrefs, first: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        if (!first) {
            HorizontalDivider(
                modifier = Modifier.padding(top = 18.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            )
        }
        Column(
            Modifier
                .padding(start = 16.dp, top = if (first) 6.dp else 16.dp, end = 16.dp, bottom = 2.dp)
                .width(IntrinsicSize.Min),
        ) {
            Text(
                title,
                fontSize = p.sectionTitleSizeSp.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
            )
            if (p.sectionUnderlineDp > 0) {
                Spacer(Modifier.height(3.dp))
                HorizontalDivider(
                    thickness = p.sectionUnderlineDp.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** A sub-level heading inside a section — indented, underlined only as wide as its own text. */
@Composable
private fun SubHeader(title: String, level: Int, p: ShiroikumaUiPrefs) {
    Column(
        Modifier
            .padding(start = indent(level, p), top = 8.dp, end = 16.dp, bottom = 2.dp)
            .width(IntrinsicSize.Min),
    ) {
        Text(
            title,
            fontSize = (p.sectionTitleSizeSp - 5).coerceAtLeast(10).sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            softWrap = false,
        )
        if (p.sectionUnderlineDp > 0) {
            Spacer(Modifier.height(2.dp))
            HorizontalDivider(
                thickness = (p.sectionUnderlineDp - 1).coerceAtLeast(1).dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun RowScaffold(
    level: Int,
    p: ShiroikumaUiPrefs,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val base = Modifier.fillMaxWidth()
    Row(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base).padding(
            start = indent(level, p),
            end = 16.dp,
            top = p.settingsRowVPadDp.dp,
            bottom = p.settingsRowVPadDp.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun ColorRow(
    level: Int,
    p: ShiroikumaUiPrefs,
    label: String,
    value: Int,
    target: ColorTarget,
    onPick: (ColorTarget) -> Unit,
) {
    RowScaffold(level, p, onClick = { onPick(target) }) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (value == target.default) "Default — ${hex8(value)}" else hex8(value),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Swatch on a checker-ish base so alpha is visible at a glance.
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.15f))
                .background(Color(value))
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
    }
}

@Composable
private fun SliderRow(
    level: Int,
    p: ShiroikumaUiPrefs,
    label: String,
    value: Int,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Int) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = indent(level, p), end = 16.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(
                valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.toFloat().coerceIn(range.start, range.endInclusive),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
    }
}

@Composable
private fun SwitchRow(
    level: Int,
    prefs: ShiroikumaUiPrefs,
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    RowScaffold(level, prefs, onClick = { onCheckedChange(!checked) }) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** A bordered box holding a live preview of whatever the section controls. */
@Composable
private fun PreviewCard(level: Int, p: ShiroikumaUiPrefs, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = indent(level, p), end = 16.dp, top = 4.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(p.cornerRadiusDp.dp))
            .background(Color(p.surface))
            .then(
                if (p.cardBorderWidthDp > 0) {
                    Modifier.border(p.cardBorderWidthDp.dp, Color(p.border), RoundedCornerShape(p.cornerRadiusDp.dp))
                } else {
                    Modifier
                },
            )
            .padding(10.dp),
    ) { content() }
}

// ---- previews ---------------------------------------------------------------------------------

@Composable
private fun ColourPreview(p: ShiroikumaUiPrefs) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Primary text", color = Color(p.text), fontSize = p.bodySizeSp.sp, fontFamily = p.family())
        Text("Secondary text", color = Color(p.textSecondary), fontSize = p.labelSizeSp.sp, fontFamily = p.family())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(Color(p.accent)))
            Box(
                Modifier
                    .weight(1f)
                    .height(18.dp)
                    .clip(RoundedCornerShape(p.cornerRadiusDp.dp))
                    .background(Color(p.selection)),
            )
            Text("!", color = Color(p.errorColor), fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(thickness = p.dividerThicknessDp.coerceAtLeast(1).dp, color = Color(p.divider))
    }
}

@Composable
private fun TypographyPreview(p: ShiroikumaUiPrefs) {
    val family = p.family()
    val weight = p.fontWeight.takeIf { it in 100..900 }?.let(::FontWeight)
    val scale = p.fontScalePct / 100f
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Title — 白い熊 mpv拡張",
            color = Color(p.text),
            fontSize = (p.titleSizeSp * scale).sp,
            fontFamily = family,
            fontWeight = weight ?: FontWeight.Bold,
        )
        Text(
            "Body — the quick brown fox 0123456789",
            color = Color(p.text),
            fontSize = (p.bodySizeSp * scale).sp,
            fontFamily = family,
            fontWeight = weight,
        )
        Text(
            "Label — 日本語のサンプル",
            color = Color(p.textSecondary),
            fontSize = (p.labelSizeSp * scale).sp,
            fontFamily = family,
            fontWeight = weight,
        )
    }
}

@Composable
private fun ShapePreview(p: ShiroikumaUiPrefs) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(p.cornerRadiusDp.dp))
                .background(Color(p.background))
                .then(
                    if (p.borderWidthDp > 0) {
                        Modifier.border(p.borderWidthDp.dp, Color(p.border), RoundedCornerShape(p.cornerRadiusDp.dp))
                    } else {
                        Modifier
                    },
                ),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (p.borderWidthDp == 0) "No border" else "Border ${p.borderWidthDp} dp",
                color = Color(p.textSecondary),
                fontSize = p.labelSizeSp.sp,
                fontFamily = p.family(),
            )
            HorizontalDivider(
                thickness = p.dividerThicknessDp.coerceAtLeast(1).dp,
                color = Color(p.divider),
            )
        }
    }
}

@Composable
private fun PlayerPreview(p: ShiroikumaUiPrefs) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The overlay gradient sits on video; approximate it with a dimmed strip.
        Box(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(p.cornerRadiusDp.dp))
                .background(Color.Gray)
                .background(Color.Black.copy(alpha = p.overlayDimPct / 100f)),
        ) {
            Row(
                Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(p.playerButtonGapDp.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(3) {
                    Box(
                        Modifier
                            .size((p.playerButtonSizeDp / 2).coerceAtLeast(10).dp)
                            .clip(CircleShape)
                            .background(Color(p.playerControlTint).copy(alpha = 0.25f))
                            .border(1.dp, Color(p.playerControlTint), CircleShape),
                    )
                }
            }
        }
        Text(
            "Overlay text",
            color = Color(p.playerTextColor),
            fontSize = p.labelSizeSp.sp,
            fontFamily = p.family(),
        )
        // Seekbar: three mock chapters, so the chapter-marker slider has a visible effect.
        // Weights stand in for chapter lengths; the gap between them IS the marker.
        val h = p.seekbarHeightDp.coerceAtLeast(2).dp
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(p.seekbarChapterMarkerDp.dp),
        ) {
            // played · buffered · track — one mock chapter each.
            listOf(
                0.35f to p.seekbarPlayed,
                0.3f to p.seekbarBuffered,
                0.35f to p.seekbarTrack,
            ).forEach { (weight, color) ->
                Box(
                    Modifier
                        .weight(weight)
                        .height(h)
                        .clip(RoundedCornerShape(p.seekbarHeightDp.dp))
                        .background(Color(color)),
                )
            }
        }
    }
}

@Composable
private fun BrowserPreview(p: ShiroikumaUiPrefs) {
    Column(verticalArrangement = Arrangement.spacedBy(p.rowGapDp.dp)) {
        repeat(2) { i ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(p.cornerRadiusDp.dp))
                    .background(Color(p.background))
                    .padding(vertical = p.rowVPadDp.dp, horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size((p.thumbSizeDp / 2).coerceAtLeast(16).dp)
                        .clip(RoundedCornerShape(p.thumbCornerDp.dp))
                        .background(Color(p.selection))
                        .then(
                            if (p.borderWidthDp > 0) {
                                Modifier.border(
                                    p.borderWidthDp.dp,
                                    Color(p.border),
                                    RoundedCornerShape(p.thumbCornerDp.dp),
                                )
                            } else {
                                Modifier
                            },
                        ),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        if (i == 0) "Some video.mkv" else "Another clip.mp4",
                        color = Color(p.text),
                        fontSize = p.bodySizeSp.sp,
                        fontFamily = p.family(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "1080p · 42:17",
                        color = Color(p.textSecondary),
                        fontSize = p.labelSizeSp.sp,
                        fontFamily = p.family(),
                    )
                }
            }
        }
    }
}

// ---- colour targets ---------------------------------------------------------------------------

private fun hex8(argb: Int): String = "#%08X".format(argb)

private enum class ColorTarget(
    val label: String,
    val default: Int,
    val get: (ShiroikumaUiPrefs) -> Int,
    val set: (ShiroikumaUiPrefs, Int) -> ShiroikumaUiPrefs,
) {
    Background("Background", ShiroikumaUiPrefs.BLACK, { it.background }, { p, v -> p.copy(background = v) }),
    Surface("Surface / cards", ShiroikumaUiPrefs.NEAR_BLACK, { it.surface }, { p, v -> p.copy(surface = v) }),
    Accent("Accent", ShiroikumaUiPrefs.YELLOW, { it.accent }, { p, v -> p.copy(accent = v) }),
    Text("Primary text", ShiroikumaUiPrefs.YELLOW, { it.text }, { p, v -> p.copy(text = v) }),
    TextSecondary("Secondary text", ShiroikumaUiPrefs.YELLOW_DIM, { it.textSecondary }, { p, v -> p.copy(textSecondary = v) }),
    Border("Border", ShiroikumaUiPrefs.YELLOW, { it.border }, { p, v -> p.copy(border = v) }),
    Divider("Divider", ShiroikumaUiPrefs.YELLOW_FAINT, { it.divider }, { p, v -> p.copy(divider = v) }),
    Selection("Selection fill", ShiroikumaUiPrefs.YELLOW_SELECT, { it.selection }, { p, v -> p.copy(selection = v) }),
    Error("Error / warning", ShiroikumaUiPrefs.ERROR_RED, { it.errorColor }, { p, v -> p.copy(errorColor = v) }),
    PlayerControlTint("Player control tint", ShiroikumaUiPrefs.YELLOW, { it.playerControlTint }, { p, v -> p.copy(playerControlTint = v) }),
    PlayerText("Player overlay text", ShiroikumaUiPrefs.YELLOW, { it.playerTextColor }, { p, v -> p.copy(playerTextColor = v) }),
    SeekbarPlayed("Seekbar — played", ShiroikumaUiPrefs.YELLOW, { it.seekbarPlayed }, { p, v -> p.copy(seekbarPlayed = v) }),
    SeekbarBuffered("Seekbar — buffered", ShiroikumaUiPrefs.YELLOW_SELECT, { it.seekbarBuffered }, { p, v -> p.copy(seekbarBuffered = v) }),
    SeekbarTrack("Seekbar — track", ShiroikumaUiPrefs.YELLOW_FAINT, { it.seekbarTrack }, { p, v -> p.copy(seekbarTrack = v) }),
}

// ---- colour picker ----------------------------------------------------------------------------

/**
 * RGBA picker: four channel sliders over a live preview, with one-click choice boxes above them
 * prefilled with the colours already in use / previously applied.
 */
@Composable
private fun ColorPickerDialog(
    title: String,
    initial: Int,
    swatches: List<Int>,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var a by remember { mutableIntStateOf((initial ushr 24) and 0xFF) }
    var r by remember { mutableIntStateOf((initial ushr 16) and 0xFF) }
    var g by remember { mutableIntStateOf((initial ushr 8) and 0xFF) }
    var b by remember { mutableIntStateOf(initial and 0xFF) }
    val argb = (a shl 24) or (r shl 16) or (g shl 8) or b

    fun apply(color: Int) {
        a = (color ushr 24) and 0xFF
        r = (color ushr 16) and 0xFF
        g = (color ushr 8) and 0xFF
        b = color and 0xFF
    }

    AlertDialog(
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // One-click choices, prefilled with previously selected colours.
                Text(
                    "Quick choices",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    swatches.forEach { swatch ->
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.15f))
                                .background(Color(swatch))
                                .border(
                                    if (swatch == argb) 2.dp else 1.dp,
                                    if (swatch == argb) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(6.dp),
                                )
                                .clickable { apply(swatch) },
                        )
                    }
                }
                // Live preview of the exact ARGB being built.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.15f))
                        .background(Color(argb))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
                )
                Text(
                    hex8(argb),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChannelSlider("R", r) { r = it }
                ChannelSlider("G", g) { g = it }
                ChannelSlider("B", b) { b = it }
                ChannelSlider("A", a) { a = it }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(argb) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChannelSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(14.dp), style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f).height(26.dp),
        )
        Text(
            value.toString(),
            Modifier.width(30.dp),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.End,
        )
    }
}

// ---- font picker ------------------------------------------------------------------------------

@Composable
private fun FontPickerDialog(
    current: String,
    fonts: List<ShiroikumaFontOption>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onAddFont: () -> Unit,
    onDelete: (String) -> Unit,
) {
    AlertDialog(
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text("Font") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                fonts.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(option.fileName) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            option.displayName,
                            modifier = Modifier.weight(1f),
                            // Every option renders in its OWN glyphs.
                            fontFamily = ShiroikumaUiStore.fontFamily(option.fileName) ?: FontFamily.Default,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (option.fileName == current) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (option.fileName.isNotEmpty() && option.fileName != ShiroikumaUiStore.MONOSPACE) {
                            IconButton(onClick = { onDelete(option.fileName) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete font",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onAddFont)
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        "Add font…",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// ---- Export / Import panel --------------------------------------------------------------------

private sealed interface EximInfo {
    data class ExportDone(val message: String) : EximInfo
    data class ImportDone(val lines: List<String>) : EximInfo
    data class Failure(val message: String) : EximInfo
}

@Composable
private fun ExportImportPanel(
    onDismiss: () -> Unit,
    onCloseChain: () -> Unit,
    onDirChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = koinInject<MpvExDatabase>()
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<EximInfo?>(null) }

    // Seeded from the same `defaultOn` flag that LIST_CATEGORIES reports, so the in-app sheet and
    // an automation picker start from one answer instead of two guesses.
    val selected = remember {
        mutableStateMapOf<ShiroikumaBackup.Cat, Boolean>().apply {
            ShiroikumaBackup.Cat.entries.forEach { put(it, it.defaultOn) }
        }
    }

    val status by produceState<Pair<String?, ShiroikumaBackup.LatestExport?>>(
        initialValue = null to null,
        refresh,
    ) {
        value = withContext(Dispatchers.IO) {
            ShiroikumaBackup.dirLabel(context) to ShiroikumaBackup.latestExport(context)
        }
    }

    fun selectedCats(): Set<ShiroikumaBackup.Cat> = selected.filterValues { it }.keys

    /**
     * [discard] removes the half-written destination — the in-app export unwinds through the same
     * path as an automation `CANCEL_EXPORT`, so a failed or cancelled run never leaves a short
     * archive behind. It also registers with [ShiroikumaBackup.beginRun], so a cancel broadcast
     * arriving mid-export stops this one too.
     */
    fun runExport(displayName: String, discard: () -> Unit = {}, open: () -> java.io.OutputStream?) {
        scope.launch {
            busy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    ShiroikumaBackup.beginRun()
                    try {
                        val out = open() ?: error("Unable to open the export destination")
                        out.use {
                            ShiroikumaBackup.export(
                                context = context,
                                db = db,
                                appVersion = BuildConfig.VERSION_NAME,
                                cats = selectedCats(),
                                output = it,
                            )
                        }
                    } finally {
                        ShiroikumaBackup.endRun()
                    }
                }
            }
                .onSuccess { summary ->
                    refresh++
                    onDirChanged()
                    info = EximInfo.ExportDone("Exported $summary.\n\n$displayName")
                }
                .onFailure { failure ->
                    withContext(Dispatchers.IO) { runCatching { discard() } }
                    info = if (failure is ShiroikumaBackup.ExportCancelled) {
                        EximInfo.Failure("Export cancelled — nothing was written.")
                    } else {
                        EximInfo.Failure("Export failed: ${failure.message ?: "unknown error"}")
                    }
                }
            busy = false
        }
    }

    val dirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ShiroikumaBackup.setDirUri(context, uri)
            refresh++
            onDirChanged()
        }
    }
    // No directory set: fall back to a save-as picker so a one-off export still works.
    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            runExport(
                displayName = uri.lastPathSegment ?: "export",
                discard = { DocumentFile.fromSingleUri(context, uri)?.delete() },
            ) { context.contentResolver.openOutputStream(uri) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                runCatching {
                    withContext(Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                            input.readBytes().also {
                                require(it.size <= IMPORT_MAX_BYTES) { "archive too large" }
                            }
                        } ?: error("Unable to read that file")
                        ShiroikumaBackup.import(context, db, bytes, selectedCats())
                    }
                }
                    .onSuccess { info = EximInfo.ImportDone(it.summaryLines) }
                    .onFailure { info = EximInfo.Failure("Import failed: ${it.message ?: "unknown error"}") }
                busy = false
            }
        }
    }

    fun onExport() {
        if (selectedCats().isEmpty()) {
            info = EximInfo.Failure("No categories selected.")
            return
        }
        val dir = ShiroikumaBackup.exportDir(context)
        val name = ShiroikumaBackup.exportFileName()
        if (dir == null) {
            saveAsLauncher.launch(name)
        } else {
            // Created on the IO thread; `created` is what a failed or cancelled run deletes again.
            var created: DocumentFile? = null
            runExport(name, discard = { created?.delete() }) {
                val file = dir.createFile("application/zip", name)
                    ?: error("Unable to create a file in the export directory")
                created = file
                context.contentResolver.openOutputStream(file.uri)
            }
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 20.dp),
            ) {
                Text(
                    "Export / Import — 白い熊 mpv拡張",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Everything settable in the app, category by category, as one ZIP of plain JSON files.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                )

                val (dirName, latest) = status
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                        .clickable(enabled = !busy) { dirPicker.launch(ShiroikumaBackup.dirUri(context)) }
                        .padding(12.dp),
                ) {
                    Text(
                        "Export directory (tap to choose)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        dirName ?: "Not set — tap to choose a directory",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (dirName == null) WarnColor else MaterialTheme.colorScheme.onSurface,
                    )
                }
                val (statusMessage, statusWarn) = when {
                    dirName == null -> "No directory set yet — pick one to enable one-tap export." to true
                    latest == null -> "No export in this directory yet." to false
                    else -> "Last export: ${latest.timestampText}" to false
                }
                Text(
                    statusMessage,
                    fontSize = 13.sp,
                    color = if (statusWarn) WarnColor else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                )

                val allSelected = ShiroikumaBackup.Cat.entries.all { selected[it] == true }
                EximCheckRow("Select all", allSelected, bold = true, indentLevel = 0) { checked ->
                    ShiroikumaBackup.Cat.entries.forEach { selected[it] = checked }
                }
                ShiroikumaBackup.Cat.topLevel.forEach { cat ->
                    EximCheckRow(cat.label, selected[cat] == true, indentLevel = 0) { checked ->
                        selected[cat] = checked
                        // Sub-options follow their parent's toggle.
                        ShiroikumaBackup.Cat.childrenOf(cat).forEach { selected[it] = checked }
                    }
                    ShiroikumaBackup.Cat.childrenOf(cat).forEach { child ->
                        EximCheckRow(child.label, selected[child] == true, indentLevel = 1) {
                            selected[child] = it
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                )
                // ArcaneChat button line: Cancel alone on the left, Import + Export on the right.
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EximPill("Cancel", enabled = !busy, onClick = onDismiss)
                    Spacer(Modifier.weight(1f))
                    EximPill("Import", enabled = !busy) {
                        importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    }
                    Spacer(Modifier.width(8.dp))
                    EximPill("Export", enabled = !busy, onClick = ::onExport)
                }
            }
        }
    }

    when (val current = info) {
        is EximInfo.ExportDone -> EximInfoDialog(
            title = "✓ Export finished",
            body = current.message,
            buttons = {
                // OK closes the info dialog, the panel beneath it, AND the UI page.
                EximPill("OK") {
                    info = null
                    onCloseChain()
                }
            },
        )

        is EximInfo.ImportDone -> EximInfoDialog(
            title = "✓ Import finished",
            body = "Restored:\n\n${current.lines.joinToString("\n")}\n\nRestart to apply everything.",
            buttons = {
                EximPill("Later") {
                    info = null
                    onCloseChain()
                }
                Spacer(Modifier.width(8.dp))
                EximPill("Restart now") { restartApp(context) }
            },
        )

        // Failure leaves the panel open underneath — only the info dialog closes.
        is EximInfo.Failure -> EximInfoDialog(
            title = "Export / Import",
            body = current.message,
            warn = true,
            buttons = { EximPill("OK") { info = null } },
        )

        null -> Unit
    }
}

/** Black surface, yellow border, right-aligned pill buttons — the finished/failed info dialog. */
@Composable
private fun EximInfoDialog(
    title: String,
    body: String,
    warn: Boolean = false,
    buttons: @Composable RowScope.() -> Unit,
) {
    Dialog(onDismissRequest = {}) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    title,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (warn) WarnColor else MaterialTheme.colorScheme.primary,
                )
                Text(
                    body,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp, bottom = 16.dp),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    content = buttons,
                )
            }
        }
    }
}

/** ArcaneChat-style pill: black fill, accent stroke, accent text, fully rounded. */
@Composable
private fun EximPill(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        border = BorderStroke(
            1.5.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.4f),
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Text(label)
    }
}

@Composable
private fun EximCheckRow(
    label: String,
    checked: Boolean,
    bold: Boolean = false,
    indentLevel: Int = 0,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChecked(!checked) }
            .padding(start = (indentLevel * 20).dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChecked,
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary,
                uncheckedColor = MaterialTheme.colorScheme.primary,
                checkmarkColor = MaterialTheme.colorScheme.background,
            ),
        )
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Kōjiki's restart: relaunch the launcher activity as a fresh task, then exit this process. */
private fun restartApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    context.startActivity(Intent.makeRestartActivityTask(launch.component))
    Runtime.getRuntime().exit(0)
}
