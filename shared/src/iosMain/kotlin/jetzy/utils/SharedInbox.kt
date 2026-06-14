package jetzy.utils

import io.github.vinceglb.filekit.PlatformFile
import jetzy.models.JetzyElement
import jetzy.viewmodel.JetzyViewmodel
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL

/**
 * Picks up files handed to Jetzy by the iOS **Share Extension** (a separate Xcode target — see
 * `iosApp/SHARE_EXTENSION_SETUP.md`). The extension writes shared items into the shared App Group
 * container's `Inbox/` folder; the main app drains that folder on launch and on every return to
 * foreground, staging the files into the send tray — the iOS equivalent of Android's share-sheet
 * intake.
 *
 * Each drained file is copied into the app's own temp dir (so it stays readable for the whole
 * session and the container copy can be removed to avoid re-import), then staged. Entirely no-op
 * and harmless until the App Group is configured, so this compiles and ships safely *before* the
 * extension target exists.
 */
object SharedInbox {
    /** Must match the App Group id configured on BOTH the app and the extension targets. */
    const val APP_GROUP = "group.com.yuroyami.jetzy"
    private const val INBOX = "Inbox"

    /** Move any pending shared files out of the App Group inbox and into the viewmodel's tray. */
    fun drainInto(viewmodel: JetzyViewmodel) {
        val fm = NSFileManager.defaultManager
        val container = fm.containerURLForSecurityApplicationGroupIdentifier(APP_GROUP) ?: return
        val inbox = container.URLByAppendingPathComponent(INBOX) ?: return

        @Suppress("UNCHECKED_CAST")
        val entries = (fm.contentsOfDirectoryAtURL(inbox, null, 0uL, null) as? List<NSURL>).orEmpty()
        if (entries.isEmpty()) return

        val staged = entries.mapNotNull { src -> adopt(fm, src)?.let { JetzyElement.File(PlatformFile(it)) } }
        if (staged.isNotEmpty()) viewmodel.elementsToSend.addAll(staged)
    }

    /** Copy [src] into the app temp dir and remove the original; returns the owned copy's URL. */
    private fun adopt(fm: NSFileManager, src: NSURL): NSURL? {
        val name = src.lastPathComponent ?: return null
        val dest = NSURL.fileURLWithPath(NSTemporaryDirectory() + name)
        fm.removeItemAtURL(dest, null) // clear any stale copy so copyItemAtURL won't fail on exists
        val copied = fm.copyItemAtURL(src, dest, null)
        if (!copied) return null
        fm.removeItemAtURL(src, null) // consumed — don't re-import on the next foreground
        return dest
    }
}
