package com.wmods.wppenhacer.xposed.features.media

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.views.NoScrollListView
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.db.StoryHistoryStore
import com.wmods.wppenhacer.xposed.features.providers.MenuStatusProvider
import com.wmods.wppenhacer.xposed.utils.DesignUtils
import com.wmods.wppenhacer.xposed.utils.MimeTypeUtils
import com.wmods.wppenhacer.xposed.utils.Utils
import java.io.File

/**
 * Story History: automatically keeps a copy of every viewed status media
 * (image/video/audio, not from me) for 48 hours. Copies live in
 * WaEnhancer/StoryHistory/<jid>/ managed by [StoryHistoryStore]; entries
 * older than 48 hours are removed automatically.
 *
 * The "viewed" moment is detected through [MenuStatusProvider]: WhatsApp
 * (re)builds the status menu for the currently displayed status, which is
 * the same access point StatusDownload uses to reach StatusData.currentItem.
 * A "Story History" entry in that same menu opens the history viewer.
 */
class StoryHistory(loader: ClassLoader, preferences: SharedPreferences) :
    Feature(loader, preferences) {

    private val store: StoryHistoryStore by lazy { StoryHistoryStore.getInstance() }

    override fun doHook() {
        if (!xprefs.getBoolean("storyhistory", false)) return

        // Expiry cleanup as soon as the module is active.
        Utils.executor.execute { store.cleanup() }

        // Records the currently displayed status (no menu entry of its own).
        val recorder = object : MenuStatusProvider.Provider {
            override fun addMenu(
                menu: Menu,
                statusData: MenuStatusProvider.StatusData
            ): MenuItem? {
                runCatching {
                    val item = statusData.currentItem
                    if (!item.isFromMe && item.isMediaFile) {
                        Utils.executor.execute { store.record(item) }
                    }
                }
                return null
            }

            override fun onClick(item: MenuItem, statusData: MenuStatusProvider.StatusData) {
            }
        }
        MenuStatusProvider.register(recorder)

        // Opens the history viewer.
        val viewer = object : MenuStatusProvider.Provider {
            override fun addMenu(
                menu: Menu,
                statusData: MenuStatusProvider.StatusData
            ): MenuItem? {
                if (menu.findItem(R.string.story_history) != null) return null
                return menu.add(0, R.string.story_history, 0, R.string.story_history)
            }

            override fun onClick(item: MenuItem, statusData: MenuStatusProvider.StatusData) {
                showHistoryDialog()
            }
        }
        MenuStatusProvider.register(viewer)
    }

    @SuppressLint("SetTextI18n")
    private fun showHistoryDialog() {
        val activity = WppCore.getCurrentActivity() ?: return
        Utils.executor.execute {
            val entries = store.getEntries()
            val root = store.rootDir()
            activity.runOnUiThread {
                val ctx = activity
                val dialog = WppCore.createBottomDialog(ctx)

                val nestedScrollView = NestedScrollView(ctx, null)
                nestedScrollView.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                nestedScrollView.isFillViewport = true
                nestedScrollView.fitsSystemWindows = true

                val linearLayout = LinearLayout(ctx)
                linearLayout.orientation = LinearLayout.VERTICAL
                val layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                linearLayout.fitsSystemWindows = true
                linearLayout.minimumHeight =
                    (Utils.application.resources.displayMetrics.heightPixels / 4)
                        .also { layoutParams.height = it }
                linearLayout.layoutParams = layoutParams
                val dip = Utils.dipToPixels(20)
                linearLayout.setPadding(dip, dip, dip, dip)
                linearLayout.background = DesignUtils.createDrawable(
                    "rc_dialog_bg",
                    DesignUtils.getPrimarySurfaceColor()
                )

                val titleView = TextView(ctx)
                val titleParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                titleParams.setMargins(0, 0, 0, Utils.dipToPixels(10))
                titleView.layoutParams = titleParams
                titleView.textSize = 16.0f
                titleView.setTextColor(DesignUtils.getPrimaryTextColor())
                titleView.setTypeface(null, Typeface.BOLD)
                titleView.setText(R.string.story_history)
                linearLayout.addView(titleView)

                if (entries.isEmpty()) {
                    val emptyView = TextView(ctx)
                    emptyView.setTextColor(DesignUtils.getPrimaryTextColor())
                    emptyView.setText(R.string.story_history_empty)
                    linearLayout.addView(emptyView)
                } else {
                    val listView: ListView = NoScrollListView(ctx)
                    val listParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                    listParams.weight = 1.0f
                    listView.layoutParams = listParams
                    listView.adapter = StoryHistoryAdapter(entries, root)
                    listView.setOnItemClickListener { _, _, position, _ ->
                        openEntry(entries[position], root)
                    }
                    linearLayout.addView(listView)
                }

                nestedScrollView.addView(linearLayout)
                dialog.setContentView(nestedScrollView)
                dialog.setCanceledOnTouchOutside(true)
                dialog.showDialog()
            }
        }
    }

    private fun openEntry(entry: StoryHistoryStore.StoryEntry, root: File) {
        val activity = WppCore.getCurrentActivity() ?: return
        val file = entry.file(root)
        if (!file.exists()) {
            Utils.showToast(Utils.getString(R.string.download_not_available), Toast.LENGTH_SHORT)
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(
                Uri.fromFile(file),
                MimeTypeUtils.getMimeTypeFromExtension(file.name)
            )
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(intent)
        } catch (t: Throwable) {
            Utils.showToast(t.message, Toast.LENGTH_SHORT)
        }
    }

    private fun formatRemaining(entry: StoryHistoryStore.StoryEntry): String {
        val remaining = entry.remainingMillis(System.currentTimeMillis())
        if (remaining <= 0) return Utils.getString(R.string.story_history_expired)
        val totalMinutes = remaining / 60000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private inner class StoryHistoryAdapter(
        private val entries: List<StoryHistoryStore.StoryEntry>,
        private val root: File
    ) : BaseAdapter() {

        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): StoryHistoryStore.StoryEntry = entries[position]
        override fun getItemId(position: Int): Long = position.toLong()

        @SuppressLint("SetTextI18n")
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val ctx = parent.context
            val row = (convertView as? LinearLayout) ?: LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                val pad = Utils.dipToPixels(6)
                setPadding(pad, pad, pad, pad)
            }

            val entry = entries[position]
            val thumbSize = Utils.dipToPixels(56)

            val thumb = (row.getChildAt(0) as? ImageView) ?: ImageView(ctx).also {
                it.layoutParams = LinearLayout.LayoutParams(thumbSize, thumbSize)
                row.addView(it)
            }
            thumb.setImageBitmap(loadThumbnail(entry.file(root), thumbSize))

            val texts = (row.getChildAt(1) as? LinearLayout) ?: LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1.0f
                ).apply { leftMargin = Utils.dipToPixels(10) }
                addView(TextView(ctx))
                addView(TextView(ctx))
                row.addView(this)
            }
            val title = texts.getChildAt(0) as TextView
            val subtitle = texts.getChildAt(1) as TextView

            title.text = entry.contactName
            title.setTypeface(null, Typeface.BOLD)
            title.setTextColor(DesignUtils.getPrimaryTextColor())
            subtitle.text = "${Utils.getDateTimeFromMillis(entry.viewedAt)}" +
                    " - ${formatRemaining(entry)}" +
                    " - ${entry.mediaType}"
            subtitle.setTextColor(DesignUtils.getPrimaryTextColor())
            return row
        }

        private fun loadThumbnail(file: File, size: Int): Bitmap? {
            if (!file.exists()) return null
            return try {
                val mime = MimeTypeUtils.getMimeTypeFromExtension(file.name)
                when {
                    mime.contains("image") -> {
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = 4
                        }
                        BitmapFactory.decodeFile(file.absolutePath, options)
                    }

                    mime.contains("video") -> {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(file.absolutePath)
                            retriever.getFrameAtTime(0)
                        } finally {
                            retriever.release()
                        }
                    }

                    else -> null
                }
            } catch (_: Throwable) {
                null
            }
        }
    }

    override fun getPluginName(): String {
        return "Story History"
    }
}
