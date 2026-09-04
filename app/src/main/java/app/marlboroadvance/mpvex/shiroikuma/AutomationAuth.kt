package app.marlboroadvance.mpvex.shiroikuma

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The gate in front of both automation doors — the broadcast contract ([StateExportReceiver]) and
 * the data door ([AutomationProvider]).
 *
 * ## What v2 changed, and why it had to
 *
 * v1 shipped this app **closed**: the master switch defaulted to false and every caller also had to
 * present a 48-character secret 白い熊 had pasted from this app's settings into the caller's.
 *
 * That is wrong for where this is going. **A pasted secret cannot survive a wipe**, and the case the
 * family now exists to serve is 応用管理 restoring apps *and their data* onto a clean phone, where
 * nothing has been configured and nobody has pasted anything. A gate that only works once the phone
 * is already set up is no gate for setting the phone up. So the switch defaults **ON** and the token
 * is **opt-in** ([requireToken]).
 *
 * The token losing its gatekeeping role does not leave the door open: the broadcast half only ever
 * *writes where it was told to* and reports what it did, and everything that moves data through a
 * caller-supplied descriptor sits behind [AutomationCallers], which checks who is actually calling.
 *
 * Device-local by design: this prefs file is NOT one of [ShiroikumaBackup]'s exported files, so the
 * token never travels in an export ZIP and never leaves the phone.
 */
object AutomationAuth {

    private const val PREFS_FILE = "mpvkakucho_automation"
    private const val KEY_ENABLED = "automation_enabled"
    private const val KEY_REQUIRE_TOKEN = "automation_require_token"
    private const val KEY_TOKEN = "automation_token"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** The master switch. **Default ON** — see the class note; it exists to close this app off. */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /** 「Use authorization token?」 — **default OFF**, so a fresh install answers the batch. */
    fun requireToken(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REQUIRE_TOKEN, false)

    fun setRequireToken(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_REQUIRE_TOKEN, value).apply()
    }

    /** The shared secret; generated lazily on first read so the settings row always shows a value. */
    fun token(context: Context): String =
        prefs(context).getString(KEY_TOKEN, null)?.takeIf { it.isNotEmpty() }
            ?: regenerateToken(context)

    fun regenerateToken(context: Context): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs(context).edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    /**
     * True when the caller's token matches the stored secret, compared in constant time.
     *
     * Only consulted when [requireToken] is on — see [refuse].
     */
    fun isTokenValid(context: Context, candidate: String?): Boolean {
        if (candidate.isNullOrEmpty()) return false
        return MessageDigest.isEqual(candidate.toByteArray(), token(context).toByteArray())
    }

    /**
     * The whole gate, in **one place**: null means proceed, otherwise the exact `ERROR:` line to
     * answer with. Two checks written out at each entry point is how "disabled" and "bad token"
     * drift apart across forty-two apps, so every door calls this and none re-derives it.
     *
     * ## A token sent to an app that does not require one is IGNORED, never refused
     *
     * This is required behaviour, not a nicety. Tokens live in task arguments and workspace
     * variables that outlive the setting they were pasted for, and a caller may still be sending
     * one because it was configured last year or because another app on the batch does want one.
     * Refusing it would turn "白い熊 turned a switch off" into "half the batch mysteriously fails" —
     * precisely the friction the switch exists to remove. Note that [candidate] is not even read
     * unless [requireToken] is on.
     *
     * "disabled" and "bad token" stay distinct because they debug differently.
     */
    fun refuse(context: Context, candidate: String?): String? = when {
        !enabled(context) -> "ERROR:automation disabled"
        requireToken(context) && !isTokenValid(context, candidate) -> "ERROR:bad token"
        else -> null
    }
}
