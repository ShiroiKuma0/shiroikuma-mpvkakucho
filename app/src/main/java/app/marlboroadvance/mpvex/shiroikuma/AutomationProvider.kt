package app.marlboroadvance.mpvex.shiroikuma

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject

/**
 * The data door: export this app's own state, and put it back, for a caller we can identify.
 *
 * ## Why a provider and not the broadcast receiver next to it
 *
 * Two reasons, and the first is the whole point of the redesign.
 *
 * **A broadcast cannot tell you who sent it.** The old contract's answer to that was a shared
 * secret, which cannot survive the wipe that this feature exists to recover from. A provider gets
 * the caller's identity from the framework for free — see [AutomationCallers] for what is actually
 * checked and why a package-name prefix would have been worse than the token it replaced.
 *
 * **A list needs a synchronous answer.** 応用管理 draws a row per installed app before any export
 * exists; a broadcast round trip per app to fill a list is the wrong shape entirely.
 *
 * ## What does NOT happen here
 *
 * The payload. `call()` validates, starts a foreground service and returns — tens of megabytes over
 * minutes inside a binder call would block the caller, report no progress, refuse cancellation and
 * die silently if this process were killed. The bytes go through a file descriptor the caller
 * opened, and the terminal answer comes back on the broadcast the family already proved on EMUI.
 *
 * ## Why a descriptor and not a path
 *
 * Because a backup is not a stable directory while it is being assembled. 応用管理 writes into a
 * temporary path and renames on commit; it encrypts and checksums **per file it knows about**. A
 * file this app dropped into that directory itself would be renamed out from under it, would sit in
 * plaintext inside an encrypted backup, and would be unverified rather than verified-and-failing
 * (応用管理, 2026-09-04). A descriptor is also a capability that **expires when it is closed** —
 * precisely the property a URI grant failed to give us on the 地図 contract, where the revoke
 * needed a five-minute floor because 地図 might not read for three minutes.
 *
 * It also means this app no longer needs `MANAGE_EXTERNAL_STORAGE` to be backed up. That permission
 * was only ever required because the old contract handed apps an absolute path.
 *
 * ## `import` lives ONLY here
 *
 * It never gets a broadcast action. An import overwrites this app's data, and [StateExportReceiver]
 * is `exported="true"` with no permission — an import there would let any app on the phone wipe
 * every watch position and playlist in this one.
 */
class AutomationProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /**
     * Every method answers a [Bundle] with [KEY_RESULT] — `OK…` or `ERROR:…`, the same vocabulary
     * the broadcast contract uses, so a caller has one grammar to parse rather than two.
     *
     * A refusal is returned, never thrown: an exception across a binder reaches the caller as a
     * `RuntimeException` with our stack trace in it, which tells 白い熊 nothing and tells a
     * misbehaving caller rather more than it should.
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context?.applicationContext ?: return fail("ERROR:not ready")

        // WHO, before WHAT. A caller we cannot identify gets the same answer whatever it asked for.
        when (val verdict = AutomationCallers.verify(ctx, callingPackage)) {
            is AutomationCallers.Verdict.Refused -> return fail(verdict.why)
            AutomationCallers.Verdict.Allowed -> Unit
        }
        // Then the app's own switches — a token is ignored unless this app asks for one.
        AutomationAuth.refuse(ctx, extras?.getString(KEY_TOKEN))?.let { return fail(it) }

        return when (method) {
            METHOD_DESCRIBE -> ok(describe(ctx))
            METHOD_EXPORT -> start(ctx, extras, importing = false)
            METHOD_IMPORT -> start(ctx, extras, importing = true)
            METHOD_CANCEL -> {
                AutomationJobs.cancel(extras?.getString(KEY_JOB_ID))
                ok("OK:cancelled")
            }
            else -> fail("ERROR:unknown method: $method")
        }
    }

    /**
     * What this app would export, answered without exporting anything.
     *
     * Returned from the call rather than written into the archive, deliberately: 応用管理 must draw
     * a row before an export exists, and at restore must judge compatibility **before** streaming
     * tens of megabytes into an app that would reject them — which it cannot do if the header is
     * buried inside an encrypted archive (応用管理, 2026-09-04).
     *
     * ## Keep this off the DI graph
     *
     * The package manager and a plain enum, and nothing else — **never Koin**. A provider's
     * `onCreate` runs before `Application.onCreate`, so a `call()` can land while Koin is still
     * starting up. That is not a corner case: it is exactly the clean-phone case this door exists
     * for, where the provider call is itself what starts the process. The database is reached only
     * from [AutomationDataService], which runs well after the application is up.
     *
     * ## `contains` says what a player's data actually is
     *
     * This app's state is watch positions, playlists, network connections and its settings — a few
     * hundred kilobytes. **The video files it plays are not its data**, and the archive never
     * carries one: a playlist entry is a path, not a copy. The last line spells that out because
     * 応用管理 sizes a backup from this list, and an entry reading "Playlists" alone invites it to
     * budget for a media library it is never going to be handed (白い熊 mpv拡張, 2026-09-04).
     */
    private fun describe(ctx: Context): String {
        val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val contains = ShiroikumaBackup.Cat.entries
            .filter { it.defaultOn }
            .map { it.briefLabel } + ShiroikumaBackup.CONTAINS_NO_MEDIA
        // Built through JSONObject rather than string interpolation so a version name or a label
        // can never break the header it is quoted into.
        val header = JSONObject().apply {
            put("app_id", ctx.packageName)
            put("version_code", @Suppress("DEPRECATION") pkg.versionCode)
            put("version_name", pkg.versionName.orEmpty())
            put("format", FORMAT)
            put("min_format_readable", MIN_FORMAT_READABLE)
            // This app writes no first-run defaults that an import would have to merge against, so
            // it accepts a restore straight after install — which is the order 応用管理 wants.
            put("requires_launch_first", false)
            put("contains", JSONArray().apply { contains.forEach { put(it) } })
        }
        return "OK:$header"
    }

    /**
     * Hand the descriptor to a foreground service and get out of the way.
     *
     * The descriptor is **duplicated** before it leaves this method. The one in [extras] belongs to
     * the binder transaction and is closed when `call()` returns; a service reading it afterwards
     * would find it shut. That is a bug you only see under load, so it is not left to the service
     * to remember.
     *
     * The job id is registered with [AutomationJobs] *here*, before the service is even started —
     * a caller that cancels the instant it is handed the id would otherwise be signalling a job
     * that had not begun, and the export would run to completion with the cancel dropped.
     */
    private fun start(ctx: Context, extras: Bundle?, importing: Boolean): Bundle {
        @Suppress("DEPRECATION")
        val fd = extras?.getParcelable<ParcelFileDescriptor>(KEY_FD)
            ?: return fail("ERROR:no descriptor")
        val dup = runCatching { fd.dup() }.getOrNull() ?: return fail("ERROR:descriptor unusable")
        val jobId = AutomationJobs.begin()
        runCatching { AutomationDataService.start(ctx, jobId, dup, importing, extras) }
            .onFailure {
                // Nothing will ever close it now, and a leaked descriptor holds the caller's file
                // open — which is exactly what stops it checksumming or encrypting that file.
                runCatching { dup.close() }
                AutomationJobs.finish(jobId)
                return fail("ERROR:${it.message ?: it.javaClass.simpleName}")
            }
        return ok("OK:$jobId")
    }

    private fun ok(result: String) = Bundle().apply { putString(KEY_RESULT, result) }
    private fun fail(why: String) = Bundle().apply { putString(KEY_RESULT, why) }

    // A provider that is only ever `call()`ed still has to answer these. Refusing loudly beats
    // returning an empty cursor, which reads downstream as "there is no data" rather than "wrong
    // door".
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? =
        throw UnsupportedOperationException("automation is call() only")
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("automation is call() only")
    override fun delete(u: Uri, s: String?, a: Array<String>?): Int =
        throw UnsupportedOperationException("automation is call() only")
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int =
        throw UnsupportedOperationException("automation is call() only")

    companion object {
        const val METHOD_DESCRIBE = "describe"
        const val METHOD_EXPORT = "export"
        const val METHOD_IMPORT = "import"
        const val METHOD_CANCEL = "cancel"

        const val KEY_RESULT = "result"
        const val KEY_FD = "fd"
        const val KEY_TOKEN = "token"
        const val KEY_JOB_ID = "job_id"
        const val KEY_ITEMS = "items"
        const val KEY_REPLY_ACTION = "reply_action"
        const val KEY_REPLY_PACKAGE = "reply_package"
        const val KEY_PROGRESS_ACTION = "progress_action"

        /**
         * This app's archive format. Bumped when an older build could no longer read what we write.
         *
         * Read from [ShiroikumaBackup.VERSION] rather than restated, so the number in the header
         * and the number in the archive's own `manifest.json` cannot drift apart.
         */
        const val FORMAT = ShiroikumaBackup.VERSION

        /**
         * The oldest archive this build can still read.
         *
         * Version skew has a direction: old data into a newer app is normally fine, because an app
         * migrates its own storage; newer data into an older app is not. This field is what lets a
         * caller refuse the second case at discovery time, before anything is streamed.
         */
        const val MIN_FORMAT_READABLE = 1
    }
}
