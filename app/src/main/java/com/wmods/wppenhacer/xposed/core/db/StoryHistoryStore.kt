package com.wmods.wppenhacer.xposed.core.db

import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.components.StatusItemWpp
import com.wmods.wppenhacer.xposed.utils.MimeTypeUtils
import com.wmods.wppenhacer.xposed.utils.Utils
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Store for the Story History feature.
 *
 * Every viewed status media (not from me) is copied to:
 *   <download folder>/WhatsApp/WaEnhancer/StoryHistory/<sender jid>/<viewedAt>_<file name>
 *
 * Metadata is kept in a simple JSON index (index.json) inside the StoryHistory
 * root folder, following the "simple JSON index" option from the design doc
 * (STORY_HISTORY_DESIGN.md) instead of extending the Room database.
 *
 * Entries expire 48 hours after they were viewed: [cleanup] removes the file
 * and the index entry. Cleanup runs when the module loads, before a new entry
 * is recorded and before the history is listed.
 */
class StoryHistoryStore private constructor() {

    data class StoryEntry(
        @JvmField val jid: String,
        @JvmField val contactName: String,
        @JvmField val viewedAt: Long,
        @JvmField val mediaType: String,
        @JvmField val sourceName: String,
        @JvmField val fileName: String
    ) {
        /** Absolute path of the copied media file. */
        fun file(root: File): File = File(File(root, jid), fileName)

        /** Milliseconds left before this entry expires (48h after [viewedAt]). */
        fun remainingMillis(now: Long): Long = viewedAt + EXPIRY_MS - now
    }

    private val lock = Any()
    private var cachedEntries: ArrayList<StoryEntry>? = null

    companion object {
        const val EXPIRY_MS: Long = 48L * 60L * 60L * 1000L
        private const val ROOT_FOLDER = "WaEnhancer/StoryHistory"
        private const val INDEX_NAME = "index.json"

        @Volatile
        private var mInstance: StoryHistoryStore? = null

        @JvmStatic
        fun getInstance(): StoryHistoryStore {
            return mInstance ?: synchronized(this) {
                mInstance ?: StoryHistoryStore().also { mInstance = it }
            }
        }
    }

    /** Root folder of the history; also creates the folder through the bridge. */
    fun rootDir(): File = File(Utils.getDestination(ROOT_FOLDER))

    private fun contactDir(jid: String): File =
        File(Utils.getDestination("$ROOT_FOLDER/$jid"))

    private fun indexFile(): File = File(rootDir(), INDEX_NAME)

    /**
     * Records a viewed status item. Must be called from a background thread:
     * it copies the media file and resolves the contact name.
     * Own statuses and non-media statuses are ignored, and re-viewing the
     * same status file from the same sender does not create duplicates.
     */
    fun record(item: StatusItemWpp) {
        try {
            if (item.isFromMe || !item.isMediaFile) return
            val mediaFile = item.getMediaFile() ?: return
            if (!mediaFile.exists()) return
            val senderJid = item.senderJid ?: return
            val jidRaw = senderJid.phoneRawString ?: return

            synchronized(lock) {
                val entries = loadEntriesLocked()
                if (entries.any { it.jid == jidRaw && it.sourceName == mediaFile.name }) {
                    return
                }
            }

            val viewedAt = System.currentTimeMillis()
            val destName = "${viewedAt}_${mediaFile.name}"
            val destFolder = contactDir(jidRaw)
            val error = Utils.copyFile(mediaFile, destFolder.absolutePath, destName)
            if (!error.isNullOrEmpty()) {
                XposedBridge.log("[StoryHistory] copy failed: $error")
                return
            }

            val mimeType = MimeTypeUtils.getMimeTypeFromExtension(mediaFile.name)
            val mediaType = when {
                mimeType.contains("video") -> "video"
                mimeType.contains("image") -> "image"
                mimeType.contains("audio") -> "audio"
                else -> "other"
            }
            val contactName = try {
                WppCore.getContactName(senderJid)
            } catch (t: Throwable) {
                senderJid.phoneNumber ?: jidRaw
            }

            synchronized(lock) {
                val entries = loadEntriesLocked()
                entries.add(
                    StoryEntry(
                        jid = jidRaw,
                        contactName = contactName,
                        viewedAt = viewedAt,
                        mediaType = mediaType,
                        sourceName = mediaFile.name,
                        fileName = destName
                    )
                )
                saveEntriesLocked(entries)
            }
            cleanup()
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    /** All non-expired entries, newest first. Runs [cleanup] before listing. */
    fun getEntries(): List<StoryEntry> {
        cleanup()
        synchronized(lock) {
            return loadEntriesLocked().sortedByDescending { it.viewedAt }
        }
    }

    /** Deletes files + index entries whose viewed time is older than 48 hours. */
    fun cleanup() {
        try {
            val now = System.currentTimeMillis()
            synchronized(lock) {
                val entries = loadEntriesLocked()
                val root = rootDir()
                val iterator = entries.iterator()
                var changed = false
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (now - entry.viewedAt > EXPIRY_MS) {
                        try {
                            entry.file(root).delete()
                        } catch (_: Throwable) {
                        }
                        iterator.remove()
                        changed = true
                    }
                }
                if (changed) saveEntriesLocked(entries)
            }
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun loadEntriesLocked(): ArrayList<StoryEntry> {
        cachedEntries?.let { return it }
        val entries = ArrayList<StoryEntry>()
        try {
            val file = indexFile()
            if (file.exists()) {
                val array = JSONArray(file.readText())
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    entries.add(
                        StoryEntry(
                            jid = obj.optString("jid"),
                            contactName = obj.optString("contactName"),
                            viewedAt = obj.optLong("viewedAt"),
                            mediaType = obj.optString("mediaType"),
                            sourceName = obj.optString("sourceName"),
                            fileName = obj.optString("fileName")
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
        cachedEntries = entries
        return entries
    }

    private fun saveEntriesLocked(entries: ArrayList<StoryEntry>) {
        try {
            val array = JSONArray()
            for (entry in entries) {
                array.put(
                    JSONObject()
                        .put("jid", entry.jid)
                        .put("contactName", entry.contactName)
                        .put("viewedAt", entry.viewedAt)
                        .put("mediaType", entry.mediaType)
                        .put("sourceName", entry.sourceName)
                        .put("fileName", entry.fileName)
                )
            }
            // Write through the module bridge, the same storage path
            // Utils.copyFile uses for downloaded status media.
            val bytes = array.toString().toByteArray(Charsets.UTF_8)
            val error = Utils.copyFile(
                java.io.ByteArrayInputStream(bytes),
                rootDir().absolutePath,
                INDEX_NAME
            )
            if (!error.isNullOrEmpty()) {
                XposedBridge.log("[StoryHistory] index save failed: $error")
            }
            cachedEntries = entries
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }
}
