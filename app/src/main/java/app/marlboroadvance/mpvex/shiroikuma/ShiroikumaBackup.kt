package app.marlboroadvance.mpvex.shiroikuma

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import androidx.sqlite.db.SupportSQLiteDatabase
import app.marlboroadvance.mpvex.database.MpvExDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Full app-state export/import — the Kōjiki-style category ZIP (白い熊 family contract).
 *
 * The archive is a ZIP of plain JSON, one entry per category, plus imported font files under
 * `fonts/`. `manifest.json` records format/version/app/appVersion/createdTs/categories. Every
 * category is independent: import iterates what the archive actually contains, skips absent
 * categories, and MERGES (never wipes), so an old export stays importable forever.
 *
 * **ONE ZIP per app, always.** Whatever is selected, a single request writes exactly one `.zip` —
 * never a companion file, never a split by category.
 *
 * The export core is headless-callable — [export] takes an [OutputStream] and a progress callback,
 * so the UI panel and [StateExportReceiver] are two thin callers over the same code.
 */
object ShiroikumaBackup {

    const val FORMAT = "mpvkakucho-export"
    const val VERSION = 1

    /**
     * Family naming convention (白い熊, 2026-07-25): the app's English dash-separated name plus a
     * timestamp, no version and no decoration, so every sister app's backups sort and read alike.
     */
    const val EXPORT_PREFIX = "shiroikuma-mpvkakucho_"

    /**
     * The last line of [AutomationProvider]'s `contains` header, and the thing a caller most needs
     * told about a *player's* backup.
     *
     * This app's data is a few hundred kilobytes of watch positions, playlists, connections and
     * settings. The videos are not its data and never enter the archive — a playlist entry is a
     * path, not a copy. Said plainly here because 応用管理 sizes a backup from that list, and would
     * otherwise budget for a media library it is never going to be handed.
     */
    const val CONTAINS_NO_MEDIA = "Video files are NOT included — app state only"

    private const val MANIFEST = "manifest.json"
    private const val FONTS_DIR = "fonts"
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    /** Device-local; deliberately NOT exported, so the automation token never rides in a ZIP. */
    private const val EXIM_PREFS = "mpvkakucho_eximport"
    private const val KEY_DIR_URI = "dir_uri"

    /**
     * A selectable export/import category. [id] is the ZIP entry name (`<id>.json`) and the id the
     * automation contract accepts in `items`. [parent] is set on sub-options.
     *
     * [defaultOn] is the app's own answer to "does this item start ticked?" — the fourth field of a
     * `LIST_CATEGORIES` line, and the seed of the in-app Export/Import picker, so both start from
     * the same statement. It is `off` only for something large, derived AND re-creatable (a
     * regenerable thumbnail cache, downloaded tiles); this app has none, so everything is `on`.
     */
    enum class Cat(
        val id: String,
        val label: String,
        val parent: String? = null,
        val defaultOn: Boolean = true,
        val briefLabel: String = label,
    ) {
        UI("ui", "白い熊 UI (colours · fonts · sizes · layout)", briefLabel = "白い熊 UI theme"),
        UI_FONTS("ui.fonts", "Imported font files", parent = "ui", briefLabel = "Imported fonts"),
        SETTINGS(
            "settings",
            "App settings (player · gestures · decoder · subtitles · audio · advanced)",
            briefLabel = "App settings",
        ),
        PLAYLISTS("playlists", "Playlists", briefLabel = "Playlists (file paths only)"),
        HISTORY("history", "Playback history & resume positions", briefLabel = "Watch positions & history"),
        NETWORK("network", "Network connections (SMB · FTP · WebDAV)", briefLabel = "Network connections");

        companion object {
            fun byId(id: String): Cat? = entries.firstOrNull { it.id == id }

            /** Top-level categories, in page order (sub-options are rendered under their parent). */
            val topLevel: List<Cat> get() = entries.filter { it.parent == null }

            fun childrenOf(cat: Cat): List<Cat> = entries.filter { it.parent == cat.id }

            /** What "no selection given" means on the export side — exactly the `on` categories. */
            val defaultSelection: Set<Cat> get() = entries.filter { it.defaultOn }.toSet()
        }
    }

    /** SharedPreferences file backing each prefs-based category. */
    private fun prefsFileFor(context: Context, cat: Cat): String? = when (cat) {
        Cat.UI -> ShiroikumaUiStore.PREFS_FILE
        Cat.SETTINGS -> "${context.packageName}_preferences"
        else -> null
    }

    /** Room tables backing each DB-based category. `video_metadata_cache` is a rebuildable cache. */
    private fun tablesFor(cat: Cat): List<String> = when (cat) {
        Cat.PLAYLISTS -> listOf("PlaylistEntity", "PlaylistItemEntity")
        Cat.HISTORY -> listOf("RecentlyPlayedEntity", "PlaybackStateEntity")
        Cat.NETWORK -> listOf("network_connections")
        else -> emptyList()
    }

    fun exportFileName(): String =
        EXPORT_PREFIX + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date()) + ".zip"

    // ---- export directory + latest-export query ------------------------------------------------

    fun dirUri(context: Context): Uri? =
        context.getSharedPreferences(EXIM_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DIR_URI, null)?.let(Uri::parse)

    fun setDirUri(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        context.getSharedPreferences(EXIM_PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DIR_URI, uri.toString()).apply()
    }

    fun exportDir(context: Context): DocumentFile? =
        dirUri(context)?.let { DocumentFile.fromTreeUri(context, it) }?.takeIf { it.canWrite() }

    /** Human label for the configured directory, or null when none is set / it is unreadable. */
    fun dirLabel(context: Context): String? = exportDir(context)?.let { doc ->
        Uri.decode(dirUri(context)?.lastPathSegment ?: doc.name ?: "") .ifEmpty { doc.name }
    }

    data class LatestExport(val name: String, val timestampText: String, val sizeBytes: Long)

    /** Newest `EXPORT_PREFIX*.zip` in the configured directory — the page queries this on open. */
    fun latestExport(context: Context): LatestExport? {
        val dir = exportDir(context) ?: return null
        val newest = dir.listFiles()
            .filter { it.isFile && (it.name ?: "").startsWith(EXPORT_PREFIX) && (it.name ?: "").endsWith(".zip") }
            .maxByOrNull { it.lastModified() } ?: return null
        val stamp = (newest.name ?: "").removePrefix(EXPORT_PREFIX).removeSuffix(".zip")
        return LatestExport(
            name = newest.name ?: "",
            timestampText = stamp.replace('_', ' ').replace('-', ':').let { t ->
                // yyyy:MM:dd HH:mm:ss -> yyyy-MM-dd HH:mm:ss (only the date half uses dashes)
                val parts = t.split(' ')
                if (parts.size == 2) "${parts[0].replace(':', '-')} ${parts[1]}" else stamp
            },
            sizeBytes = newest.length(),
        )
    }

    fun humanSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format(Locale.ROOT, "%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024L -> String.format(Locale.ROOT, "%.1f kB", bytes / 1024.0)
        else -> "$bytes B"
    }

    // ---- cancellation --------------------------------------------------------------------------

    /**
     * Thrown out of [export] when a cancel arrived. Every caller answers it the same way: delete
     * the half-written destination, then report `cancelled` — a cancelled export must leave the
     * backup directory **exactly as it found it**, with no short archive left behind.
     */
    class ExportCancelled : Exception("cancelled")

    // One export at a time is the contract, so a pair of flags is the whole registry. The write
    // loop reads [cancelRequested] between entries and unwinds at the next boundary — never a
    // thread interrupt, never a mid-write() abort.
    @Volatile private var running = false
    @Volatile private var runningId: String? = null
    @Volatile private var cancelRequested = false

    /** Register the run that is about to start; [replyId] is the request a cancel may name. */
    @Synchronized
    fun beginRun(replyId: String? = null) {
        running = true
        runningId = replyId?.trim()?.takeIf { it.isNotEmpty() }
        cancelRequested = false
    }

    @Synchronized
    fun endRun() {
        running = false
        runningId = null
        cancelRequested = false
    }

    /**
     * Flag the running export for cancellation. An empty/absent [replyId] means "whatever is
     * running", which is unambiguous because two at once are forbidden. Returns false when nothing
     * matched — arriving before, after or between runs is a silent no-op, not an error.
     */
    @Synchronized
    fun requestCancel(replyId: String? = null): Boolean {
        if (!running) return false
        val wanted = replyId?.trim().orEmpty()
        if (wanted.isNotEmpty() && runningId != null && wanted != runningId) return false
        cancelRequested = true
        return true
    }


    // ---- export --------------------------------------------------------------------------------

    /** Progress callback: (current, total, unit, human text). Throttling is the caller's job. */
    fun interface Progress {
        fun report(current: Long, total: Long, unit: String, text: String)
    }

    /**
     * Write the selected [cats] to [output] as one ZIP. Returns a short summary for the UI
     * ("3 categories"). The stream is NOT closed here — the caller owns it.
     *
     * [isCancelled] is the **second** way to stop a run, for callers that do not own the
     * [beginRun]/[requestCancel] registry — the data door of [AutomationDataService], whose jobs
     * are identified by a `job_id` the provider handed out and which may be cancelled before this
     * function is even entered. The two are independent on purpose: a data-door export and a
     * broadcast export must not be able to cancel each other by clobbering one shared flag.
     */
    fun export(
        context: Context,
        db: MpvExDatabase,
        appVersion: String,
        cats: Set<Cat>,
        output: OutputStream,
        onProgress: Progress? = null,
        isCancelled: (() -> Boolean)? = null,
    ): String {
        // Polled at entry boundaries only — never mid-write, so a cancelled archive is never half
        // a file. The caller deletes the partial destination on the way out.
        fun checkCancelled() {
            if (cancelRequested || isCancelled?.invoke() == true) throw ExportCancelled()
        }

        val selected = cats.ifEmpty { Cat.defaultSelection }
        // A sub-option implies its parent's presence in the archive listing.
        val tops = Cat.topLevel.filter { top ->
            top in selected || Cat.childrenOf(top).any { it in selected }
        }
        val total = tops.size.toLong()
        var done = 0L

        ZipOutputStream(output.buffered()).use { zip ->
            val written = mutableListOf<String>()

            tops.forEach { cat ->
                checkCancelled()
                onProgress?.report(done, total, "区分", "区分 ${done + 1}/$total — ${cat.label}")
                when (cat) {
                    Cat.UI -> {
                        if (Cat.UI in selected) {
                            zip.writeJson("${cat.id}.json", prefsJson(context, ShiroikumaUiStore.PREFS_FILE))
                            written += cat.id
                        }
                        if (Cat.UI_FONTS in selected) {
                            val fonts = ShiroikumaUiStore.fontsDir().listFiles()?.filter { it.isFile }.orEmpty()
                            fonts.forEach { f ->
                                checkCancelled()
                                zip.putNextEntry(ZipEntry("$FONTS_DIR/${f.name}"))
                                f.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                            if (fonts.isNotEmpty()) written += Cat.UI_FONTS.id
                        }
                    }

                    Cat.SETTINGS -> {
                        zip.writeJson("${cat.id}.json", prefsJson(context, prefsFileFor(context, cat)!!))
                        written += cat.id
                    }

                    else -> {
                        val tables = tablesFor(cat)
                        if (tables.isNotEmpty()) {
                            val obj = JSONObject()
                            val helper = db.openHelper.readableDatabase
                            tables.forEach { t -> obj.put(t, dumpTable(helper, t)) }
                            zip.writeJson("${cat.id}.json", obj)
                            written += cat.id
                        }
                    }
                }
                done++
                onProgress?.report(done, total, "区分", "区分 $done/$total — ${cat.label}")
            }

            checkCancelled()
            val manifest = JSONObject().apply {
                put("format", FORMAT)
                put("version", VERSION)
                put("app", "shiroikuma-mpvkakucho")
                put("appVersion", appVersion)
                put("createdTs", System.currentTimeMillis())
                put("categories", JSONArray().apply { written.forEach { put(it) } })
            }
            zip.writeJson(MANIFEST, manifest)
            zip.finish()
        }
        return "${tops.size} categories"
    }

    private fun ZipOutputStream.writeJson(name: String, obj: JSONObject) {
        putNextEntry(ZipEntry(name))
        write(obj.toString(2).toByteArray())
        closeEntry()
    }

    /** Type-tagged prefs dump, so Int/Long/Float/Boolean/String/StringSet survive the round trip. */
    private fun prefsJson(context: Context, fileName: String): JSONObject {
        val sp = if (fileName == "${context.packageName}_preferences") {
            PreferenceManager.getDefaultSharedPreferences(context)
        } else {
            context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
        }
        val out = JSONObject()
        sp.all.forEach { (key, value) ->
            val entry = JSONObject()
            when (value) {
                is Boolean -> { entry.put("t", "b"); entry.put("v", value) }
                is Int -> { entry.put("t", "i"); entry.put("v", value) }
                is Long -> { entry.put("t", "l"); entry.put("v", value) }
                is Float -> { entry.put("t", "f"); entry.put("v", value.toDouble()) }
                is String -> { entry.put("t", "s"); entry.put("v", value) }
                is Set<*> -> {
                    entry.put("t", "ss")
                    entry.put("v", JSONArray().apply { value.forEach { put(it.toString()) } })
                }
                else -> return@forEach
            }
            out.put(key, entry)
        }
        return out
    }

    private fun dumpTable(db: SupportSQLiteDatabase, table: String): JSONArray {
        val rows = JSONArray()
        runCatching {
            db.query("SELECT * FROM `$table`").use { c ->
                while (c.moveToNext()) {
                    val row = JSONObject()
                    for (i in 0 until c.columnCount) {
                        val name = c.getColumnName(i)
                        when (c.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> row.put(name, JSONObject.NULL)
                            Cursor.FIELD_TYPE_INTEGER -> row.put(name, c.getLong(i))
                            Cursor.FIELD_TYPE_FLOAT -> row.put(name, c.getDouble(i))
                            else -> row.put(name, c.getString(i))
                        }
                    }
                    rows.put(row)
                }
            }
        }
        return rows
    }

    // ---- import --------------------------------------------------------------------------------

    data class ImportResult(val summaryLines: List<String>)

    /**
     * The categories an archive actually carries — read from its entries, not from its manifest.
     *
     * The data door restores what it was handed rather than what it knows how to restore: asking
     * for a category the archive lacks is how a restore ends up reporting success over nothing.
     * Entry names are the authority because they are what [import] itself walks, so the two can
     * never disagree about what is in the file.
     */
    fun categoriesIn(bytes: ByteArray): Set<Cat> = categoriesIn { ByteArrayInputStream(bytes) }

    /**
     * [categoriesIn] over an archive that is not in memory — [open] is called once and must hand
     * back a fresh stream over the whole file. The data door spools a restore to disk rather than
     * reading it into a byte array, so its archive is a file, not a `ByteArray`.
     */
    fun categoriesIn(open: () -> InputStream): Set<Cat> {
        val found = mutableSetOf<Cat>()
        runCatching {
            ZipInputStream(open()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        name.startsWith("$FONTS_DIR/") && !entry.isDirectory -> found += Cat.UI_FONTS
                        name.endsWith(".json") && name != MANIFEST ->
                            Cat.byId(name.removeSuffix(".json"))?.let { found += it }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return found
    }

    /**
     * Merge the archive into the app. Absent categories are skipped; prefs are merged key by key
     * (never cleared) and DB rows are inserted with REPLACE, so importing an old backup never
     * destroys newer state that the archive simply does not mention.
     */
    fun import(context: Context, db: MpvExDatabase, bytes: ByteArray, cats: Set<Cat>): ImportResult =
        import(context, db, { ByteArrayInputStream(bytes) }, cats)

    /**
     * [import] over an archive that is not in memory — [open] is called once and must hand back a
     * fresh stream over the whole file.
     *
     * This is the shape the data door uses: a restore arrives on a descriptor whose size the app
     * does not choose, and reading an arbitrarily large one into a `ByteArray` first is how a
     * restore dies of `OutOfMemory` on the phone it was meant to rescue.
     */
    fun import(
        context: Context,
        db: MpvExDatabase,
        open: () -> InputStream,
        cats: Set<Cat>,
    ): ImportResult {
        val selected = cats.ifEmpty { Cat.entries.toSet() }
        val lines = mutableListOf<String>()
        var totalRead = 0L
        var fontsRestored = 0

        ZipInputStream(open()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && !name.contains("..")) {
                    val data = zip.readBounded(MAX_ENTRY_BYTES)
                    totalRead += data.size
                    require(totalRead <= MAX_TOTAL_BYTES) { "archive too large" }

                    when {
                        name == MANIFEST -> Unit

                        name.startsWith("$FONTS_DIR/") -> {
                            if (Cat.UI_FONTS in selected) {
                                val fileName = name.substringAfterLast('/')
                                if (fileName.isNotEmpty()) {
                                    File(ShiroikumaUiStore.fontsDir(), fileName).writeBytes(data)
                                    fontsRestored++
                                }
                            }
                        }

                        name.endsWith(".json") -> {
                            val cat = Cat.byId(name.removeSuffix(".json"))
                            if (cat != null && cat in selected) {
                                val obj = JSONObject(String(data))
                                when (cat) {
                                    Cat.UI, Cat.SETTINGS -> {
                                        restorePrefs(context, prefsFileFor(context, cat)!!, obj)
                                        lines += cat.label
                                    }
                                    else -> {
                                        var rows = 0
                                        tablesFor(cat).forEach { t ->
                                            obj.optJSONArray(t)?.let { rows += restoreTable(db, t, it) }
                                        }
                                        lines += "${cat.label} — $rows rows"
                                    }
                                }
                            }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        if (fontsRestored > 0) lines += "${Cat.UI_FONTS.label} — $fontsRestored files"
        // The UI prefs file was rewritten underneath the in-memory store.
        ShiroikumaUiStore.reload()
        if (lines.isEmpty()) lines += "Nothing matched the selected categories."
        return ImportResult(lines)
    }

    private fun restorePrefs(context: Context, fileName: String, obj: JSONObject) {
        val sp = if (fileName == "${context.packageName}_preferences") {
            PreferenceManager.getDefaultSharedPreferences(context)
        } else {
            context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
        }
        val editor = sp.edit()
        obj.keys().forEach { key ->
            val e = obj.optJSONObject(key) ?: return@forEach
            when (e.optString("t")) {
                "b" -> editor.putBoolean(key, e.optBoolean("v"))
                "i" -> editor.putInt(key, e.optInt("v"))
                "l" -> editor.putLong(key, e.optLong("v"))
                "f" -> editor.putFloat(key, e.optDouble("v").toFloat())
                "s" -> editor.putString(key, e.optString("v"))
                "ss" -> {
                    val arr = e.optJSONArray("v") ?: JSONArray()
                    editor.putStringSet(key, (0 until arr.length()).map { arr.getString(it) }.toSet())
                }
            }
        }
        // commit(), not apply(): on the restore path 応用管理 force-stops this app the instant the
        // import replies success — deliberately, because a running process writes its cached
        // SharedPreferences back out at orderly shutdown and would silently undo the import. An
        // apply() that had not yet reached disk when the kill arrived would be lost with it. Both
        // callers already run the import off the main thread, so the synchronous write costs
        // nothing (白い熊 mpv拡張, 2026-09-04).
        editor.commit()
    }

    private fun restoreTable(db: MpvExDatabase, table: String, rows: JSONArray): Int {
        val helper = db.openHelper.writableDatabase
        var written = 0
        runCatching {
            helper.beginTransaction()
            try {
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val values = android.content.ContentValues()
                    row.keys().forEach { col ->
                        when (val v = row.get(col)) {
                            JSONObject.NULL -> values.putNull(col)
                            is Int -> values.put(col, v.toLong())
                            is Long -> values.put(col, v)
                            is Double -> values.put(col, v)
                            is Boolean -> values.put(col, if (v) 1L else 0L)
                            else -> values.put(col, v.toString())
                        }
                    }
                    helper.insert(table, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, values)
                    written++
                }
                helper.setTransactionSuccessful()
            } finally {
                helper.endTransaction()
            }
        }
        return written
    }

    private fun InputStream.readBounded(max: Long): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = read(chunk)
            if (n <= 0) break
            total += n
            require(total <= max) { "entry too large" }
            buffer.write(chunk, 0, n)
        }
        return buffer.toByteArray()
    }
}
