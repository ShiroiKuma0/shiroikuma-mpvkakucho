package app.marlboroadvance.mpvex.shiroikuma

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import app.marlboroadvance.mpvex.BuildConfig
import app.marlboroadvance.mpvex.database.MpvExDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where a data-door export or import actually runs — the payload half of [AutomationProvider].
 *
 * ## Why a foreground service and not the provider call
 *
 * The call returns in milliseconds; this can run for minutes. Two hard reasons it cannot be done
 * anywhere cheaper:
 *
 * - **A binder call holds the caller.** 応用管理 is drawing a list; a multi-minute synchronous call
 *   would freeze its UI, report no progress, and refuse cancellation.
 * - **A backgrounded app writing for minutes is frozen mid-stream on this phone**, which yields a
 *   truncated archive underneath a success reply — the worst possible failure, because it is
 *   indistinguishable from a good backup until the day it is restored (応用管理, 2026-09-04).
 *
 * ## The descriptor
 *
 * Already duplicated by [AutomationProvider] before it got here, because the original belongs to
 * the binder transaction and is closed the moment `call()` returns. This service owns the copy and
 * closes it in a `finally` — leaking one would hold the caller's file open indefinitely, and the
 * caller cannot checksum or encrypt a file that is still open.
 *
 * ## What this app hands over
 *
 * The same one ZIP [StateExportReceiver] writes, straight down the descriptor: this app's own
 * state — UI, settings, playlists, watch positions, network connections. **Not the videos.** A
 * playlist entry is a path; the media it points at belongs to the filesystem, and 応用管理 backs
 * that up as storage, not as this app's data.
 */
class AutomationDataService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val importing = intent?.getBooleanExtra(EXTRA_IMPORTING, false) == true

        // FIRST, before any early return below. Once `startForegroundService` has been called the
        // platform requires this promise to be kept whatever the service then decides, and enforces
        // it with ForegroundServiceDidNotStartInTimeException — so a caller retrying with a stale
        // job id must be quietly IGNORED, not crash the app it is retrying against. Within 5 s of
        // the service starting, for the same class of reason.
        val foregrounded = runCatching {
            startForeground(NOTIFICATION_ID, notification(importing))
        }.isSuccess

        val jobId = intent?.getStringExtra(EXTRA_JOB) ?: return stop(startId)
        val replyAction = intent.getStringExtra(AutomationProvider.KEY_REPLY_ACTION)
        val replyPackage = intent.getStringExtra(AutomationProvider.KEY_REPLY_PACKAGE)
        val progressAction = intent.getStringExtra(AutomationProvider.KEY_PROGRESS_ACTION)

        val replied = AtomicBoolean(false)
        // A `val` lambda rather than a local `fun`: a local function beside an anonymous object
        // that captures a local `var` has been seen to crash AGP's lint analysis ("FirDeclaration
        // was not found for class KtProperty") *after* Kotlin has compiled cleanly, which costs a
        // whole build to discover. Cheap to avoid, so avoided.
        val reply: (String) -> Unit = { result ->
            // Exactly one terminal answer per job, whatever path got here — a synchronous failure
            // and an asynchronous success must never both fire. The same guard the broadcast
            // contract has carried since the first sister app.
            if (replied.compareAndSet(false, true)) {
                AutomationJobs.finish(jobId)
                if (!replyAction.isNullOrEmpty() && !replyPackage.isNullOrEmpty()) {
                    sendBroadcast(
                        Intent(replyAction).apply {
                            setPackage(replyPackage)
                            // Without this a caller that has been backgrounded never hears the
                            // answer, and on a clean phone it may not have been launched at all.
                            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                            // The job id under BOTH names: it is this door's correlation id, and a
                            // caller that already parses §1's replies reads it as `reply_id`.
                            putExtra(AutomationProvider.KEY_JOB_ID, jobId)
                            putExtra(EXTRA_REPLY_ID, jobId)
                            putExtra(AutomationProvider.KEY_RESULT, result)
                        },
                    )
                }
            }
        }

        // A stale or duplicate job id — the descriptor belongs to the run already under way. A
        // silent no-op: a caller retrying is the normal race, not an error.
        val fd = HANDOVER.remove(jobId) ?: return stop(startId)

        // From here the descriptor is ours and exactly one thing can take it off our hands: the
        // coroutine below. ONE flag rather than a guard per failure — `startForeground` refusing is
        // only one way out of this window, and every other way leaks the caller's file handle
        // identically. A leaked descriptor holds that file open, and a caller cannot checksum or
        // encrypt a file that is still open.
        var handedOff = false
        try {
            if (!foregrounded) {
                reply("ERROR:foreground service refused")
                return stop(startId)
            }
            scope.launch {
                try {
                    fd.use { open ->
                        if (importing) {
                            runImport(open, reply)
                        } else {
                            runExport(
                                jobId = jobId,
                                fd = open,
                                items = intent.getStringExtra(AutomationProvider.KEY_ITEMS),
                                progressAction = progressAction,
                                replyPackage = replyPackage,
                                reply = reply,
                            )
                        }
                    }
                } catch (t: Throwable) {
                    reply("ERROR:${t.message ?: t.javaClass.simpleName}")
                } finally {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            handedOff = true
        } finally {
            if (!handedOff) {
                runCatching { fd.close() }
                AutomationJobs.finish(jobId)
            }
        }
        return START_NOT_STICKY
    }

    private fun runExport(
        jobId: String,
        fd: ParcelFileDescriptor,
        items: String?,
        progressAction: String?,
        replyPackage: String?,
        reply: (String) -> Unit,
    ) {
        val cats = resolve(items) ?: run { reply("ERROR:unknown category in items: $items"); return }
        val db = KoinJavaComponent.get<MpvExDatabase>(MpvExDatabase::class.java)
        var written = 0L

        ParcelFileDescriptor.AutoCloseOutputStream(fd).use { out ->
            // Counted as it goes rather than stat'ed afterwards: the caller owns the file and we
            // may not be able to see it at all — it can be an anonymous pipe or a descriptor into a
            // directory this app cannot list.
            val counting = CountingOutputStream(out)
            ShiroikumaBackup.export(
                context = this,
                db = db,
                appVersion = BuildConfig.VERSION_NAME,
                cats = cats,
                output = counting,
                onProgress = progress(jobId, progressAction, replyPackage),
                isCancelled = { AutomationJobs.isCancelled(jobId) },
            )
            written = counting.written
        }
        if (AutomationJobs.isCancelled(jobId)) reply("ERROR:cancelled")
        else reply("OK:$written|${ShiroikumaBackup.humanSize(written)}|${cats.size} categories")
    }

    /**
     * Spool the whole archive to disk, then read it — twice, from the file.
     *
     * **Spooled rather than held in memory** because a restore arrives on a descriptor whose size
     * this app does not choose: reading an arbitrarily large one into a `ByteArray` is how a
     * restore dies of `OutOfMemory` on the very phone it was meant to rescue. Landing it in the
     * cache directory also means the two passes below — what the archive carries, then the merge
     * itself — read the same bytes without the descriptor having to be rewindable.
     *
     * Read whole before touching anything, though, and that part is deliberate rather than
     * convenient: a partial read that failed halfway would import half an archive, and a
     * half-restored app is worse than one that refused.
     */
    private fun runImport(fd: ParcelFileDescriptor, reply: (String) -> Unit) {
        val spool = File.createTempFile("automation-import", ".zip", cacheDir)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                spool.outputStream().use { input.copyTo(it) }
            }
            if (spool.length() == 0L) { reply("ERROR:empty archive"); return }
            // Every category the archive actually carries, not every category we know about: asking
            // for one the archive lacks is how a restore ends up reporting success over nothing.
            val present = ShiroikumaBackup.categoriesIn { spool.inputStream() }
            if (present.isEmpty()) { reply("ERROR:archive carries no categories"); return }
            val db = KoinJavaComponent.get<MpvExDatabase>(MpvExDatabase::class.java)
            val result = ShiroikumaBackup.import(this, db, { spool.inputStream() }, present)
            // The caller force-stops us straight after this. That is deliberate and belongs on its
            // side: a running process writes its cached SharedPreferences back out at orderly
            // shutdown and silently undoes the import that just happened (応用管理 paid for this
            // one already). Our own half of the bargain is that the import commits synchronously
            // rather than with apply() — see ShiroikumaBackup.restorePrefs.
            reply("OK:${result.summaryLines.size} restored")
        } finally {
            // Before the reply, ideally — the caller may kill us the instant it hears one — but a
            // cache file is the one thing the system will clear for us if it does.
            spool.delete()
        }
    }

    /**
     * §3's progress broadcasts, in the shape the broadcast path already sends: real counts, never a
     * percentage, throttled to one every 500 ms with the terminal one never dropped.
     *
     * **§3 binds this door too.** A caller treats every progress broadcast as proof the app is
     * still alive and fails a slot that goes quiet for two minutes, so a data-door export that sent
     * none would be presumed dead on a slow phone. The correlation id is the `job_id` this door
     * handed out, sent under both names so a caller that already parses §1's progress reads it as
     * `reply_id` without a second code path.
     */
    private fun progress(
        jobId: String,
        progressAction: String?,
        replyPackage: String?,
    ): ShiroikumaBackup.Progress? {
        if (progressAction.isNullOrEmpty() || replyPackage.isNullOrEmpty()) return null
        val label = runCatching {
            packageManager.getApplicationLabel(applicationInfo).toString()
        }.getOrDefault("白い熊 mpv拡張")
        var last = 0L
        return ShiroikumaBackup.Progress { current, total, unit, text ->
            val now = System.currentTimeMillis()
            if (current < total && now - last < 500) return@Progress
            last = now
            sendBroadcast(
                Intent(progressAction).apply {
                    setPackage(replyPackage)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    putExtra(AutomationProvider.KEY_JOB_ID, jobId)
                    putExtra(EXTRA_REPLY_ID, jobId)
                    putExtra("app", label)
                    putExtra("text", text)
                    putExtra("current", current)
                    putExtra("total", total)
                    putExtra("unit", unit)
                },
            )
        }
    }

    private fun resolve(items: String?): Set<ShiroikumaBackup.Cat>? {
        if (items.isNullOrBlank()) return ShiroikumaBackup.Cat.defaultSelection
        val wanted = items.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val found = wanted.mapNotNull { ShiroikumaBackup.Cat.byId(it) }
        return if (found.size == wanted.size) found.toSet() else null
    }

    private fun notification(importing: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL, "自動化データ", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(if (importing) "データを戻しています" else "データを書き出しています")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    private fun stop(startId: Int): Int {
        // The promise made by startForegroundService() has already been kept by the time any caller
        // reaches here, so it has to be released too — a no-op when we never got foreground.
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    /**
     * Counts the bytes it forwards, and deliberately does **not** forward `close()`.
     *
     * The ZIP writer closes what it was handed; the descriptor must outlive that so the `use` in
     * [runExport] closes it exactly once, in the order that method controls.
     *
     * A named class rather than an anonymous `object` capturing a local `var`: the latter, beside a
     * local function in the same method, has been seen to crash AGP's lint analysis after Kotlin
     * has already compiled cleanly.
     */
    private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
        var written = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            written++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            written += len
        }

        override fun flush() = out.flush()

        override fun close() = out.flush()
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "automation_data"
        private const val NOTIFICATION_ID = 9714
        private const val EXTRA_JOB = "job"
        private const val EXTRA_IMPORTING = "importing"

        /** §1's correlation-id name, carried alongside `job_id` so callers need one parser. */
        private const val EXTRA_REPLY_ID = "reply_id"

        /**
         * The descriptor's way across, because an Intent is the wrong vehicle for one.
         *
         * A `ParcelFileDescriptor` in an Intent extra is duplicated by the system on delivery and
         * the copy's lifetime stops being ours to reason about. Handing it through a map keyed by
         * the job id keeps exactly one open descriptor with exactly one owner — the service, which
         * closes it in a `finally`.
         */
        private val HANDOVER = ConcurrentHashMap<String, ParcelFileDescriptor>()

        fun start(
            context: Context,
            jobId: String,
            fd: ParcelFileDescriptor,
            importing: Boolean,
            extras: Bundle?,
        ) {
            HANDOVER[jobId] = fd
            runCatching {
                context.startForegroundService(
                    Intent(context, AutomationDataService::class.java).apply {
                        putExtra(EXTRA_JOB, jobId)
                        putExtra(EXTRA_IMPORTING, importing)
                        putExtra(
                            AutomationProvider.KEY_ITEMS,
                            extras?.getString(AutomationProvider.KEY_ITEMS),
                        )
                        putExtra(
                            AutomationProvider.KEY_REPLY_ACTION,
                            extras?.getString(AutomationProvider.KEY_REPLY_ACTION),
                        )
                        putExtra(
                            AutomationProvider.KEY_REPLY_PACKAGE,
                            extras?.getString(AutomationProvider.KEY_REPLY_PACKAGE),
                        )
                        putExtra(
                            AutomationProvider.KEY_PROGRESS_ACTION,
                            extras?.getString(AutomationProvider.KEY_PROGRESS_ACTION),
                        )
                    },
                )
            }.onFailure {
                // The service will never run, so nothing would ever take the descriptor back out
                // of the handover map. Drop it here and let the provider report the refusal.
                HANDOVER.remove(jobId)
                throw it
            }
        }
    }
}
