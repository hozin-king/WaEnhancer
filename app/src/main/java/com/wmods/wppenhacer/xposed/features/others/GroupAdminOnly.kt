package com.wmods.wppenhacer.xposed.features.others

import android.content.Intent
import android.content.SharedPreferences
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.components.FMessageWpp
import com.wmods.wppenhacer.xposed.core.components.WaContactWpp
import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator
import com.wmods.wppenhacer.xposed.features.listeners.ContactItemListener
import com.wmods.wppenhacer.xposed.features.listeners.ConversationItemListener
import com.wmods.wppenhacer.xposed.utils.DesignUtils
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils
import com.wmods.wppenhacer.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Labels groups that are in "announcement" mode (only admins can send
 * messages) and adds a shortcut to WhatsApp's own group settings.
 *
 * The announcement state is a group attribute synced from the server
 * (chats column "announcement_group"), read through
 * [Unobfuscator.loadGroupAnnouncementField] from captured group data
 * instances. This feature only changes local UI: it never writes the
 * attribute and never tries to send messages as a non-admin, because both
 * are enforced by the WhatsApp server (see GROUP_ADMINONLY_DESIGN.md).
 *
 * Prefs:
 *  - "label_adminonly": "Admin Only" badge in the conversation name area
 *    (same insertion point as GroupAdmin) and in the chat list.
 *  - "show_adminonly_toggle": admin icon next to the group name, visible
 *    only when the user is an admin of the current group; tapping it opens
 *    WhatsApp's Group Info screen where the announcements-only setting
 *    lives (Info Grup -> Izin Grup -> Kirim pesan).
 */
class GroupAdminOnly(classLoader: ClassLoader, preferences: SharedPreferences) :
    Feature(classLoader, preferences) {

    private val groupInstances = ConcurrentHashMap<String, Any>()
    private val adminFieldCache = ConcurrentHashMap<Class<*>, Field>()

    @Volatile
    private var announcementField: Field? = null

    private var nameInGroupId: Int = -1
    private var nameInGroupTvId: Int = -1

    override fun doHook() {
        val showLabel = prefs.getBoolean("label_adminonly", false)
        val showShortcut = prefs.getBoolean("show_adminonly_toggle", false)
        if (!showLabel && !showShortcut) return

        nameInGroupId = Utils.getID("name_in_group", "id")
        nameInGroupTvId = Utils.getID("name_in_group_tv", "id")

        // Resolve the announcement attribute and capture live group data
        // instances (same capture pattern as FStatusWpp/LockedChatsEnhancer).
        try {
            announcementField = Unobfuscator.loadGroupAnnouncementField(classLoader)
            val groupClass = Unobfuscator.loadGroupAnnouncementClass(classLoader)
            val groupJidField =
                runCatching { Unobfuscator.loadGroupAnnouncementJidField(classLoader) }
                    .getOrNull()
            groupJidField?.isAccessible = true
            XposedBridge.hookAllConstructors(groupClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val instance = param.thisObject ?: return
                    val raw = groupRawJid(instance, groupJidField) ?: return
                    groupInstances[raw] = instance
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log(t)
            announcementField = null
        }

        val grpcheckAdmin =
            runCatching { Unobfuscator.loadGroupCheckAdminMethod(classLoader) }.getOrNull()
        val jidFactory =
            runCatching { Unobfuscator.loadJidFactory(classLoader) }.getOrNull()

        ConversationItemListener.conversationListeners.add(
            object : ConversationItemListener.OnConversationItemListener() {
                override fun onItemBind(
                    fMessage: FMessageWpp,
                    view: ViewGroup,
                    position: Int,
                    convertView: View?
                ) {
                    try {
                        val chatJid = WppCore.getCurrentUserJid()
                        if (chatJid == null || !chatJid.isGroup) {
                            hideTagged(view, TAG_LABEL)
                            hideTagged(view, TAG_SHORTCUT)
                            return
                        }
                        val groupRaw = chatJid.phoneRawString

                        if (showLabel) {
                            val badge = ensureLabelBadge(view)
                            badge?.visibility =
                                if (groupRaw != null && isAnnouncementGroup(groupRaw)) {
                                    View.VISIBLE
                                } else {
                                    View.GONE
                                }
                        }

                        if (showShortcut && grpcheckAdmin != null && jidFactory != null) {
                            val isAdmin = groupRaw != null && checkSelfAdmin(
                                view, grpcheckAdmin, jidFactory, groupRaw
                            )
                            val button = ensureShortcutButton(view)
                            if (button != null) {
                                button.visibility =
                                    if (isAdmin) View.VISIBLE else View.GONE
                                if (isAdmin && groupRaw != null) {
                                    button.setOnClickListener {
                                        openGroupInfo(groupRaw)
                                    }
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                    }
                }
            })

        if (showLabel) {
            ContactItemListener.contactListeners.add(
                object : ContactItemListener.OnContactItemListener() {
                    override fun onBind(waContact: WaContactWpp?, view: View?) {
                        try {
                            if (waContact == null || view == null) return
                            val userJid = waContact.userJid
                            val badge = ensureListBadge(view) ?: return
                            val groupRaw = userJid.phoneRawString
                            badge.visibility =
                                if (!userJid.isNull && userJid.isGroup && groupRaw != null &&
                                    isAnnouncementGroup(groupRaw)
                                ) {
                                    View.VISIBLE
                                } else {
                                    View.GONE
                                }
                        } catch (t: Throwable) {
                            XposedBridge.log(t)
                        }
                    }
                })
        }
    }

    // ------------------------------------------------------------------
    // Announcement state
    // ------------------------------------------------------------------

    private fun groupRawJid(instance: Any, jidField: Field?): String? {
        if (jidField == null) return null
        return try {
            val jidObj = jidField.get(instance) ?: return null
            val raw = XposedHelpers.callMethod(jidObj, "getRawString") as? String
                ?: return null
            raw.replaceFirst("\\.[\\d:]+@".toRegex(), "@")
        } catch (t: Throwable) {
            null
        }
    }

    private fun isAnnouncementGroup(groupRawJid: String): Boolean {
        val field = announcementField ?: return false
        val instance = groupInstances[groupRawJid] ?: return false
        return runCatching { field.getBoolean(instance) }.getOrDefault(false)
    }

    // ------------------------------------------------------------------
    // Self admin check (mirrors GroupAdmin's participants resolution)
    // ------------------------------------------------------------------

    private fun checkSelfAdmin(
        view: ViewGroup,
        grpcheckAdmin: Method,
        jidFactory: Method,
        groupRaw: String
    ): Boolean {
        val grpcheckAdminClass = grpcheckAdmin.declaringClass
        val viewClass = view.javaClass
        val field = adminFieldCache[viewClass] ?: ReflectionUtils.findFieldUsingFilter(
            viewClass
        ) { f ->
            f.type.isAssignableFrom(grpcheckAdminClass)
        }.also {
            it.isAccessible = true
            adminFieldCache[viewClass] = it
        }
        val grpParticipants = field.get(view) ?: return false
        val jidGrp = jidFactory.invoke(null, groupRaw)
        val myJid = WppCore.getMyUserJid() ?: return false
        val participantJid = resolveParticipantJid(myJid, grpcheckAdmin) ?: return false
        val result = grpcheckAdmin.invoke(grpParticipants, jidGrp, participantJid)
        return result != null && result as Boolean
    }

    private fun resolveParticipantJid(
        userJid: FMessageWpp.UserJid,
        grpcheckAdmin: Method
    ): Any? {
        val expectedType = grpcheckAdmin.parameterTypes[1]
        if (userJid.userJid != null && expectedType.isInstance(userJid.userJid)) {
            return userJid.userJid
        }
        if (userJid.phoneJid != null && expectedType.isInstance(userJid.phoneJid)) {
            return userJid.phoneJid
        }
        return userJid.userJid ?: userJid.phoneJid
    }

    private fun openGroupInfo(groupRaw: String) {
        try {
            val clazz = Unobfuscator.findFirstClassUsingName(
                classLoader,
                StringMatchType.EndsWith,
                "GroupChatInfoActivity"
            )
            val intent = Intent()
            intent.setClassName(Utils.application.packageName, clazz.name)
            intent.putExtra("gid", groupRaw)
            WppCore.getCurrentActivity()?.startActivity(intent)
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    // ------------------------------------------------------------------
    // Badge construction (insertion mechanics copied from GroupAdmin)
    // ------------------------------------------------------------------

    private fun hideTagged(view: View, tag: String) {
        view.findViewWithTag<View>(tag)?.visibility = View.GONE
    }

    private fun ensureLabelBadge(view: ViewGroup): TextView? {
        view.findViewWithTag<TextView>(TAG_LABEL)?.let { return it }
        val nameGroup = view.findViewById<ViewGroup>(nameInGroupId) ?: return null
        val nametv = nameTv(nameGroup) ?: return null

        val badge = TextView(view.context).apply {
            tag = TAG_LABEL
            text = Utils.getString(R.string.admin_only)
            textSize = 10f
            setPadding(
                Utils.dipToPixels(6),
                Utils.dipToPixels(1),
                Utils.dipToPixels(6),
                Utils.dipToPixels(1)
            )
            layoutParams = badgeLayoutParams(nametv)
            visibility = View.GONE
            runCatching { setTextColor(DesignUtils.getPrimaryTextColor()) }
        }
        insertAfter(nameGroup, nametv, badge)
        return badge
    }

    private fun ensureShortcutButton(view: ViewGroup): ImageView? {
        view.findViewWithTag<ImageView>(TAG_SHORTCUT)?.let { return it }
        val nameGroup = view.findViewById<ViewGroup>(nameInGroupId) ?: return null
        val nametv = nameTv(nameGroup) ?: return null

        val iconSize = Utils.dipToPixels(14)
        val button = ImageView(view.context).apply {
            tag = TAG_SHORTCUT
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.admin)
            layoutParams = (badgeLayoutParams(nametv) as? ViewGroup.MarginLayoutParams)?.apply {
                width = iconSize
                height = iconSize
            } ?: LinearLayout.LayoutParams(iconSize, iconSize)
            visibility = View.GONE
        }
        insertAfter(nameGroup, nametv, button)
        return button
    }

    /** Badge for a chat-list row: a small text label appended to the row. */
    private fun ensureListBadge(view: View): TextView? {
        view.findViewWithTag<TextView>(TAG_LABEL)?.let { return it }
        val root = view as? ViewGroup ?: return null
        val badge = TextView(view.context).apply {
            tag = TAG_LABEL
            text = Utils.getString(R.string.admin_only)
            textSize = 10f
            setPadding(
                Utils.dipToPixels(6),
                Utils.dipToPixels(1),
                Utils.dipToPixels(6),
                Utils.dipToPixels(1)
            )
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
            runCatching { setTextColor(DesignUtils.getPrimaryTextColor()) }
        }
        root.addView(badge)
        return badge
    }

    private fun nameTv(nameGroup: ViewGroup): View? {
        return (if (nameInGroupTvId != -1) nameGroup.findViewById(nameInGroupTvId) else null)
            ?: nameGroup.getChildAt(0)
    }

    private fun badgeLayoutParams(nametv: View): ViewGroup.LayoutParams {
        val iconSize = Utils.dipToPixels(14)
        val marginStart = Utils.dipToPixels(2)
        val marginEnd = Utils.dipToPixels(2)
        return when (val nameLp = nametv.layoutParams) {
            is LinearLayout.LayoutParams -> {
                LinearLayout.LayoutParams(nameLp).apply {
                    width = ViewGroup.LayoutParams.WRAP_CONTENT
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    weight = 0f
                    leftMargin = marginStart
                    rightMargin = marginEnd
                }
            }

            is ViewGroup.MarginLayoutParams -> {
                ViewGroup.MarginLayoutParams(nameLp).apply {
                    width = ViewGroup.LayoutParams.WRAP_CONTENT
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    leftMargin = marginStart
                    rightMargin = marginEnd
                }
            }

            else -> {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    leftMargin = marginStart
                    rightMargin = marginEnd
                }
            }
        }.also { it.height = maxOf(it.height, iconSize) }
    }

    private fun insertAfter(nameGroup: ViewGroup, anchor: View, child: View) {
        val index = nameGroup.indexOfChild(anchor)
        if (index != -1) {
            nameGroup.addView(child, index + 1)
        } else {
            nameGroup.addView(child)
        }
    }

    override fun getPluginName(): String {
        return "Group Admin Only"
    }

    private companion object {
        const val TAG_LABEL = "adminonly_badge"
        const val TAG_SHORTCUT = "adminonly_shortcut"
    }
}
