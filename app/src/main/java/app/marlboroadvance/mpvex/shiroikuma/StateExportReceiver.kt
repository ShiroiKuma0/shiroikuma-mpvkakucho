package app.marlboroadvance.mpvex.shiroikuma

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.marlboroadvance.mpvex.BuildConfig
import app.marlboroadvance.mpvex.database.MpvExDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The sister-app **state-export automation contract**, implemented for this app — the same wire
 * shape every 白い熊 app exposes so a 保存復元 task in 自由作業盤 can back them all up headlessly.
 *
 * - [ACTION_EXPORT_STATE]: run the full category-ZIP export ([ShiroikumaBackup]) with no UI.
 *   Extras (all String): `token` (required — [AutomationAuth]), `path` (optional absolute
 *   directory, which WINS over the configured SAF directory), `items` (optional comma list of
 *   [ShiroikumaBackup.Cat] ids; absent/empty = everything), `progress_action` (optional),
 *   plus the reply trio `reply_action` / `reply_package` / `reply_id`.
 * - [ACTION_LIST_CATEGORIES]: token-gated category enumeration for the caller's item picker,
 *   as `id<TAB>label` lines, with a third TAB field naming the parent on sub-options.
 *
 * Reply: a FRESH broadcast to `reply_package`, extras `reply_id` (echoed verbatim) + `result`.
 * Exactly one terminal reply, single-fire guarded by an [AtomicBoolean]. **No binders and no
 * reliance on the ordered-broadcast result** — EMUI severs both between third-party apps
 * (verified on 白い熊's Mate XT, 2026-07-23); a plain broadcast is the only channel that works.
 * [Intent.FLAG_INCLUDE_STOPPED_PACKAGES] so a backgrounded caller still hears the reply.
 *
 * Progress: while exporting, plain broadcasts with `text` carrying REAL COUNTS, never a percentage,
 * plus structured `current`/`total` (long) and `unit` (String), throttled to one per 500 ms.
 */
class StateExportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val action = intent.action ?: return
        val token = intent.getStringExtra(EXTRA_TOKEN)
        val replyAction = intent.getStringExtra(EXTRA_REPLY_ACTION)?.trim().orEmpty()
        val replyPackage = intent.getStringExtra(EXTRA_REPLY_PACKAGE)?.trim().orEmpty()
        val replyId = intent.getStringExtra(EXTRA_REPLY_ID)?.trim().orEmpty()
        val progressAction = intent.getStringExtra(EXTRA_PROGRESS_ACTION)?.trim().orEmpty()
        val pathOverride = intent.getStringExtra(EXTRA_PATH)?.trim().orEmpty()
        val items = intent.getStringExtra(EXTRA_ITEMS)?.trim().orEmpty()

        val replied = AtomicBoolean(false)
        fun reply(result: String) {
            if (replyAction.isEmpty() || replyPackage.isEmpty()) return
            if (!replied.compareAndSet(false, true)) return
            app.sendBroadcast(
                Intent(replyAction).apply {
                    setPackage(replyPackage)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    putExtra(EXTRA_REPLY_ID, replyId)
                    putExtra(EXTRA_RESULT, result)
                },
            )
        }

        // Gate first — "disabled" and "bad token" stay distinct because they debug differently.
        if (!AutomationAuth.enabled(app)) {
            reply("ERROR:automation disabled")
            return
        }
        if (!AutomationAuth.isTokenValid(app, token)) {
            reply("ERROR:bad token")
            return
        }

        when (action) {
            ACTION_LIST_CATEGORIES -> {
                reply(
                    "OK:" + ShiroikumaBackup.Cat.entries.joinToString("\n") { cat ->
                        if (cat.parent == null) "${cat.id}\t${cat.label}" else "${cat.id}\t${cat.label}\t${cat.parent}"
                    },
                )
            }

            ACTION_EXPORT_STATE -> {
                val cats: Set<ShiroikumaBackup.Cat> = if (items.isEmpty()) {
                    ShiroikumaBackup.Cat.entries.toSet()
                } else {
                    val ids = items.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val resolved = ids.mapNotNull { ShiroikumaBackup.Cat.byId(it) }
                    if (resolved.size != ids.size) {
                        reply("ERROR:unknown category in items: $items")
                        return
                    }
                    resolved.toSet()
                }

                val appLabel = runCatching {
                    app.packageManager.getApplicationLabel(app.applicationInfo).toString()
                }.getOrDefault("白い熊 mpv拡張")
                val fileName = ShiroikumaBackup.exportFileName()

                // Directory precedence: `path` extra -> configured export directory -> error.
                // The app holds MANAGE_EXTERNAL_STORAGE, so an absolute path is a plain File write.
                val target: Any? = when {
                    pathOverride.isNotEmpty() -> File(pathOverride).apply { mkdirs() }
                    else -> ShiroikumaBackup.exportDir(app)
                }
                if (target == null) {
                    reply("ERROR:no-directory")
                    return
                }

                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        var lastProgress = 0L
                        val progress = ShiroikumaBackup.Progress { current, total, unit, text ->
                            if (progressAction.isEmpty() || replyPackage.isEmpty()) return@Progress
                            val now = System.currentTimeMillis()
                            // Throttle to one every 500 ms, but never drop the terminal one.
                            if (current < total && now - lastProgress < 500) return@Progress
                            lastProgress = now
                            app.sendBroadcast(
                                Intent(progressAction).apply {
                                    setPackage(replyPackage)
                                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                                    putExtra(EXTRA_REPLY_ID, replyId)
                                    putExtra("app", appLabel)
                                    putExtra("text", text)
                                    putExtra("current", current)
                                    putExtra("total", total)
                                    putExtra("unit", unit)
                                },
                            )
                        }

                        val db = KoinJavaComponent.get<MpvExDatabase>(MpvExDatabase::class.java)
                        val summary: String
                        val absolutePath: String
                        val sizeBytes: Long

                        when (target) {
                            is File -> {
                                val out = File(target, fileName)
                                summary = out.outputStream().use { stream ->
                                    ShiroikumaBackup.export(app, db, BuildConfig.VERSION_NAME, cats, stream, progress)
                                }
                                absolutePath = out.absolutePath
                                sizeBytes = out.length()
                            }
                            else -> {
                                val dir = target as androidx.documentfile.provider.DocumentFile
                                val doc = dir.createFile("application/zip", fileName)
                                    ?: error("cannot create file in the export directory")
                                summary = app.contentResolver.openOutputStream(doc.uri).use { stream ->
                                    if (stream == null) error("cannot open the export destination")
                                    ShiroikumaBackup.export(app, db, BuildConfig.VERSION_NAME, cats, stream, progress)
                                }
                                absolutePath = doc.uri.toString()
                                sizeBytes = doc.length()
                            }
                        }

                        reply("OK:$absolutePath|$sizeBytes|${ShiroikumaBackup.humanSize(sizeBytes)}|$summary")
                    } catch (t: Throwable) {
                        reply("ERROR:${t.message ?: "export failed"}")
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_EXPORT_STATE = "shiroikuma.mpvkakucho.action.EXPORT_STATE"
        const val ACTION_LIST_CATEGORIES = "shiroikuma.mpvkakucho.action.LIST_CATEGORIES"

        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_ITEMS = "items"
        private const val EXTRA_PROGRESS_ACTION = "progress_action"
        private const val EXTRA_REPLY_ACTION = "reply_action"
        private const val EXTRA_REPLY_PACKAGE = "reply_package"
        private const val EXTRA_REPLY_ID = "reply_id"
        private const val EXTRA_RESULT = "result"
    }
}
