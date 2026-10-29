package com.wmods.wppenhacer.xposed.features.others

import android.content.SharedPreferences
import android.widget.Toast
import com.wmods.wppenhacer.xposed.core.Feature
import com.wmods.wppenhacer.xposed.features.providers.ContextMenuActionProvider
import com.wmods.wppenhacer.xposed.utils.Utils
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kirim ke Bridge — Feature WaEnhancer (fork hozin-king/WaEnhancer).
 *
 * Cara pakai: aktifkan toggle "bridge_send" di pengaturan WaEnhancer, isi
 * "bridge_token" dengan token agen gateway dari Bridge (jangan dibagikan),
 * lalu di WhatsApp: tekan lama PESAN SENDIRI -> pilih "Kirim ke Bridge".
 * Pesan itu dikirim sebagai task baru ke Bridge API (POST /api/tasks).
 *
 * Batasan keamanan yang disengaja (fail-closed):
 * - HANYA pesan sendiri (isFromMe) yang menampilkan tombol ini. Pesan orang
 *   lain tidak pernah dikirim, tidak ada penerusan otomatis, tidak ada
 *   pemantauan chat di latar belakang.
 * - Tidak ada pengiriman otomatis saat pesan masuk/keluar — hanya ketukan
 *   eksplisit pengguna pada pesannya sendiri.
 * - Token hanya dibaca dari xprefs modul (pengaturan WaEnhancer), tidak
 *   di-hardcode, tidak di-commit.
 * - Ini BUKAN chat real-time dengan Muse utama: Bridge adalah papan task
 *   asinkron (lihat muse-agent-bridge). Jawaban task dibaca dari papan
 *   Bridge, bukan dibalas langsung di WhatsApp.
 */
class BridgeSend(classLoader: ClassLoader, preferences: SharedPreferences) :
    Feature(classLoader, preferences) {

    override fun doHook() {
        if (!xprefs.getBoolean("bridge_send", false)) return

        ContextMenuActionProvider.register { activity, _, wpp ->
            // Fail-closed: kalau tidak bisa dipastikan ini pesan sendiri, jangan tampilkan tombol.
            val fromMe = runCatching { wpp.key.isFromMe }.getOrDefault(false)
            if (!fromMe) return@register null
            val messageText = wpp.messageStr?.trim().orEmpty()
            if (messageText.isEmpty()) return@register null

            ContextMenuActionProvider.ContextMenuAction(
                title = "Kirim ke Bridge"
            ) {
                val bridgeUrl = (xprefs.getString("bridge_url", "https://bridge.hozinking.my.id") ?: "https://bridge.hozinking.my.id").trimEnd('/')
                val token = (xprefs.getString("bridge_token", "") ?: "").trim()
                if (token.isEmpty()) {
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Isi token Bridge dulu di pengaturan WaEnhancer", Toast.LENGTH_SHORT).show()
                    }
                    return@ContextMenuAction
                }
                val title = "Dari WhatsApp: " + messageText.take(60)
                Utils.executor.execute {
                    val ok = runCatching { postTask(bridgeUrl, token, title, messageText) }.getOrDefault(false)
                    activity.runOnUiThread {
                        Toast.makeText(
                            activity,
                            if (ok) "Terkirim ke Bridge" else "Gagal kirim ke Bridge",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun postTask(bridgeUrl: String, token: String, title: String, body: String): Boolean {
        val conn = (URL("$bridgeUrl/api/tasks").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        val json = "{\"title\":\"${escJson(title)}\",\"body\":\"${escJson(body)}\"}"
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(json) }
        val code = conn.responseCode
        runCatching { conn.disconnect() }
        return code in 200..299
    }

    private fun escJson(s: String): String {
        val out = StringBuilder()
        for (c in s) {
            when (c) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (c.code < 0x20) out.append(String.format("\\u%04x", c.code)) else out.append(c)
                }
            }
        }
        return out.toString()
    }

    override fun getPluginName(): String = "Bridge Send"
}
