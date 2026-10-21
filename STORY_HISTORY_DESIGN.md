# Rancangan: Story History (kedaluwarsa 2 hari) — WaEnhancer fork hozin-king

Permintaan user (21 Okt 2026): riwayat story/status WA yang otomatis hilang
setelah 2 hari, dibangun di modul LSPosed WaEnhancer (fork user).

## Temuan struktur (dari kode fork, paket com.wmods.wppenhacer)
- Fitur = class extends Feature(loader, preferences), doHook(), didaftarkan di
  xposed/core/FeatureLoader.kt, diaktifkan pref boolean (pola: "downloadstatus").
- Akses status berjalan lewat xposed/features/providers/MenuStatusProvider —
  StatusData.currentItem: StatusItemWpp (fMessage, isFromMe, isMediaFile, file media).
  Lihat xposed/features/media/StatusDownload.kt sebagai pola terdekat.
- Pola riwayat yang sudah ada: xposed/core/db/MessageHistoryDatabase.kt +
  MessageHistoryStore.kt (basis pola simpan-riwayat di modul ini).
- Pref UI: app/src/main/res/xml/fragment_media.xml (+ strings).

## Desain fitur "StoryHistory"
1. Feature baru xposed/features/media/StoryHistory.kt, pref "storyhistory".
2. Saat status media (bukan milik sendiri) DILIHAT, salin file medianya ke
   penyimpanan modul: <penyimpanan>/WaEnhancer/StoryHistory/<jid>/<ts>_<nama>
   + catat metadata (kontak, waktu lihat, jenis media) — pola store meniru
   MessageHistoryStore atau indeks JSON sederhana.
3. Pembersihan otomatis: tiap modul aktif (dan sebelum menampilkan riwayat),
   hapus entri berumur > 48 jam (file + metadata).
4. Penampil riwayat: daftar berisi thumbnail + nama kontak + sisa waktu,
   ketuk untuk membuka media (Intent VIEW). Tempat tampil: menu di layar
   Status WA (MenuStatusProvider) dan/atau layar di pengaturan modul.
5. Batas: hanya status yang benar-benar dibuka user (file-nya sudah terunduh
   WA) yang bisa disalin — konsisten dengan cara StatusDownload bekerja.

## Status
Rancangan selesai 21 Okt 2026. Implementasi menunggu jalur Hozin Tools v1.4
beres (push + proteksi native + CI + APK), lalu dibangun sebagai commit
tersendiri di repo ini.

## Catatan implementasi (fase 1, 21 Okt 2026 — lokal, belum di-push)

Status: SUDAH DIIMPLEMENTASIKAN mengikuti rancangan di atas.

File baru:
- app/src/main/java/com/wmods/wppenhacer/xposed/features/media/StoryHistory.kt
  (Feature, pref "storyhistory"; perekam via MenuStatusProvider.Provider yang
  mengembalikan null + menu "Story History" untuk membuka penampil; penampil =
  bottom dialog pola ShowEditMessage: thumbnail + nama kontak + sisa waktu,
  ketuk = Intent ACTION_VIEW ke file media).
- app/src/main/java/com/wmods/wppenhacer/xposed/core/db/StoryHistoryStore.kt
  (indeks JSON sederhana sesuai opsi di rancangan — BUKAN tabel Room baru,
  supaya tidak mengubah skema MessageHistoryDatabase; salinan file lewat
  Utils.copyFile/bridge seperti StatusDownload; folder tujuan
  <download>/WhatsApp/WaEnhancer/StoryHistory/<jid>/<viewedAt>_<nama file>;
  dedup per (jid, nama file sumber); cleanup entri >48 jam dipanggil saat
  modul aktif, sebelum record, dan sebelum menampilkan daftar).

File diubah:
- xposed/core/FeatureLoader.kt — impor + registrasi StoryHistory.
- res/xml/fragment_media.xml — switch "storyhistory" di kategori Download.
- res/values/strings.xml — story_history, story_history_sum,
  story_history_empty, story_history_expired.
- utils/FeatureCatalog.kt — entri pencarian "storyhistory".

Verifikasi: cek sintaks Kotlin (kotlin-compiler 1.9.22 + android.jar) — tidak
ada error sintaks; sisa diagnostik hanya keluarga noise dependensi tak
ter-resolve (unresolved reference dsb.) yang idiomnya identik dengan file
eksisting. Konsistensi referensi (MenuStatusProvider.Provider,
StatusItemWpp.isFromMe/isMediaFile/getMediaFile/senderJid, Utils.*,
WppCore.*) dibuktikan via grep. BELUM terverifikasi: perilaku runtime di HP
(dasar "dilihat" = menu status dibangun WA untuk status yang tampil — asumsi
yang sama dengan yang dipakai StatusDownload; frekuensi persisnya per versi
WA belum teruji), dan tidak ada build Gradle penuh di fase ini.
