# Rancangan: Label + Toggle "Admin Only" untuk Grup — WaEnhancer fork hozin-king

Permintaan user (21 Okt 2026): (1) ada tombol untuk mengaktifkan mode grup
"hanya admin yang bisa kirim pesan", (2) grup yang sudah admin-only diberi
label khusus. Dokumen ini hasil RISET saja (belum ada kode diubah), berbasis
bukti dari dump string APK WA user (`~/workspace/wa-sticker/dex_strings.txt`)
dan kode fork (`~/workspace/waenhancer-study`).

## 1. Bukti di dex WA: mode ini bernama "announcement group"

String persis yang ketemu:

- Kolom data grup (daftar kolom chat grup di DB WA): `restrict_mode`,
  `announcement_group`, `announcement_version` — jadi status admin-only
  adalah ATRIBUT GRUP yang tersimpan, bukan status lokal.
- Jalur ubah pengaturan (XMPP, client → server):
  `GroupAdminSettingsXmpp/set-announcements-only; announcementsEnabled=`
  dan saudaranya `GroupAdminSettingsXmpp/set-restrict-mode; restrictModeEnabled=`,
  `GroupAdminSettingsXmpp/set-ephemeral-setting`, `GroupAdminSettingsXmpp/send`.
- Perubahan diterima dari server sebagai notifikasi:
  `groupmgr/onGroupAnnouncementsToggled` (+ `/changed`, `/new group`,
  `did not change`, `stanzaMetadata is null`),
  `groupmgr/onGroupRestrictModeToggled` (pola sama),
  `GroupNotificationHandler/handleAnnouncement/...`,
  `UPDATE_GROUP_NOTIFICATION_ANNOUNCEMENT_VERSION`.
- Cek client sebelum kirim (khusus grup pengumuman komunitas/CAG):
  `SendMessageMethods/only admins can send message to CAGs`.
- Istilah lain yang relevan: `announcementsOnly=`, `isDefaultAnnouncementGroup`,
  `DefaultAnnouncementGroup`, `GroupForbidden` (pada daftar hasil GroupInfo).
- TIDAK ketemu: string `only_admin` / `admin_only` / `who_can_send` — WA
  tidak memakai nama itu; nama resminya "announcements only".

Dua mode yang berkerabat tapi beda (jangan dicampur):
- `announcement_group` = hanya admin kirim PESAN (yang user maksud).
- `restrict_mode` = hanya admin ubah INFO grup (nama/foto/deskripsi).

## 2. Kandidat ABProps grup — penilaian

Dari daftar class AbProp di dex:

- `GroupAbProps.kt`, `GroupAbPropsClient.kt`, `GroupAbPropsStore.kt`,
  `GroupAbPropsData.kt`, `GroupAbPropsAsyncInit.kt`,
  `GroupAbPropsExposureKeysPulse.kt`, `ConversationGroupAbPropsDelegateImpl.kt`
  = infrastruktur pengambilan AB-prop PER GRUP (fetch/store), plausibel
  mengatur fitur grup eksperimental secara umum.
- `GroupStatusFutureProofAbPropObserver.kt` = gerbang fitur status grup
  (fitur baru), bukan kirim-pesan.
- `XFamilyGroupsAbPropObserver.kt` = grup lintas aplikasi Meta.

Penilaian: TIDAK ada bukti ABProp mengatur announcement/admin-only —
fitur itu sudah rilis lama dan digerakkan atribut grup + stanza server
(bagian 1), bukan gerbang eksperimen. Jadi fitur modul ini TIDAK perlu
jalur ABProps; jalurnya atribut grup.

## 3. Pola di fork yang bisa dipakai

- **Pola label/badge grup (persis)**: `xposed/features/others/GroupAdmin.kt`
  — gate pref `admin_grp` (UI: `res/xml/fragment_customization.xml` baris
  ±139, katalog: `utils/FeatureCatalog.kt`), terdaftar di
  `xposed/core/FeatureLoader.kt`. Ia memasang ikon `R.drawable.admin` di
  samping nama grup pada header percakapan lewat
  `ConversationItemListener.onItemBind` (view `name_in_group` /
  `name_in_group_tv`) dan cek admin via
  `Unobfuscator.loadGroupCheckAdminMethod` (Unobfuscator.kt:1664).
  Bedanya: GroupAdmin menandai "pengirim ini admin"; fitur baru menandai
  "grup ini admin-only" — mekanisme tempel ikon/listener yang sama.
- **Titik label di daftar chat**: `xposed/features/listeners/ContactItemListener.kt`
  (`OnContactItemListener.onBind(waContact, view)`) — pola yang sama bisa
  dipakai menampilkan badge "Admin Only" pada item grup di daftar chat.
- **Yang BELUM ada di fork**: referensi apa pun ke announcement/restrict
  (grep `announcement|restrict_mode|restrictMode` di kode modul = kosong),
  dan belum ada Unobfuscator loader untuk objek data grup (GroupChat) —
  loader baru perlu dibuat untuk membaca atribut `announcement_group`
  dari objek grup WA (nama class/field ter-obfuscate per versi WA).

## 4. Rencana implementasi yang diusulkan

Fitur baru `xposed/features/others/GroupAdminOnly.kt`, dua pref boolean
di `fragment_customization.xml` (kelompok yang sama dengan `admin_grp`):

1. `label_adminonly` — label/badge:
   - Unobfuscator loader baru: temukan class data grup + field/metode
     pembaca atribut announcement (padanan kolom `announcement_group`).
   - Di header percakapan: ikon/teks kecil "Admin Only" meniru persis
     pola sisip-view GroupAdmin.kt pada `name_in_group`.
   - Di daftar chat: badge lewat ContactItemListener untuk item ber-JID
     grup yang atributnya announcement.
2. `show_adminonly_toggle` — tombol aktifkan:
   - Yang bisa dilakukan client secara sah: MEMUNCULKAN/ memudahkan akses
     ke pengaturan bawaan WA (menu Info Grup → Izin Grup →
     "Kirim pesan: Hanya admin" / stanza `set-announcements-only` yang
     memang dikirim WA sendiri) — hanya tampil bila user adalah admin
     grup itu (cek via loadGroupCheckAdminMethod yang sudah ada).
   - Modul TIDAK mengirim stanza sendiri dan TIDAK mengubah atribut
     lokal secara paksa (lihat batas server di bagian 5).

## 5. Batas tegas: client vs server

BISA dipaksa dari client (aman, tampilan):
- Label/badge "Admin Only" di header percakapan dan daftar chat.
- Memunculkan pintasan/tombol menuju pengaturan admin-only bawaan WA.
- Menyembunyikan/menampilkan elemen UI lokal lain seputar mode ini.

TIDAK bisa dipaksa dari client (ditegakkan server WA) — bukti:
- Atribut `announcement_group` datang dari data grup yang disinkronkan
  server dan perubahan resminya lewat stanza XMPP
  `GroupAdminSettingsXmpp/set-announcements-only` (bagian 1): hanya
  admin grup yang stanza-nya diterima server.
- Pesan non-admin di grup announcement ditolak di sisi server; menipu
  tampilan client (mis. memalsukan atribut lokal supaya kolom kirim
  terlihat aktif) hanya menghasilkan pesan gagal terkirim, bukan izin
  beneran. Modul tidak akan melakukan itu.
- Mengaktifkan mode untuk grup yang user BUKAN adminnya: tidak mungkin
  dari client — server menolak stanza dari non-admin.

## Status

Riset selesai 21 Okt 2026. Implementasi mengikuti antrean WaEnhancer
(Story History + ABProps) sebagai commit tersendiri bila user menyuruh.

## Catatan implementasi (fase 1, 21 Okt 2026 — lokal, belum di-push)

Status: SUDAH DIIMPLEMENTASIKAN mengikuti rencana bagian 4 (tanpa jalur
ABProps, tanpa memalsukan atribut / memaksa kirim — batas bagian 5 dipatuhi).

File baru:
- app/src/main/java/com/wmods/wppenhacer/xposed/features/others/GroupAdminOnly.kt
  (Feature dengan 2 pref: "label_adminonly" dan "show_adminonly_toggle").
  - Label: badge teks "Admin Only" di area name_in_group percakapan (mekanika
    sisip-view persis GroupAdmin.kt, tag "adminonly_badge") + badge pada item
    grup di daftar chat via ContactItemListener.onBind.
  - Pintasan: ikon R.drawable.admin (tag "adminonly_shortcut") hanya tampil
    bila user admin grup yang sedang dibuka (cek loadGroupCheckAdminMethod +
    WppCore.getMyUserJid, resolusi partisipan meniru GroupAdmin); ketuk =
    buka GroupChatInfoActivity dengan extra "gid" (pola CustomPrivacyAdapter),
    tempat pengaturan announcements-only bawaan WA berada.
  - Status announcement dibaca dari instance data grup yang ditangkap via
    hookAllConstructors (pola FStatusWpp/LockedChatsEnhancer), diindeks per
    raw JID grup lewat field JID-nya.

File diubah:
- xposed/core/devkit/Unobfuscator.kt — 3 loader baru mengikuti pola yang ada:
  loadGroupAnnouncementClass (jangkar string "announcement_group", fallback
  "announcementsOnly="), loadGroupAnnouncementField (field boolean non-statis
  TUNGGAL di class itu; bila >1 boolean -> throw, fitur nonaktif diam-diam —
  penolakan menebak disengaja), loadGroupAnnouncementJidField
  (getFieldByExtendType ke jid.Jid).
- xposed/core/FeatureLoader.kt — impor + registrasi GroupAdminOnly.
- res/xml/fragment_customization.xml — switch "label_adminonly" dan
  "show_adminonly_toggle" di kelompok yang sama dengan "admin_grp".
- res/values/strings.xml — admin_only, show_admin_only_label(+_sum),
  show_admin_only_shortcut(+_sum).
- utils/FeatureCatalog.kt — 2 entri pencarian baru.

Verifikasi: cek sintaks Kotlin lolos (hanya noise dependensi tak ter-resolve,
idiom identik GroupAdmin/ShowEditMessage); referensi silang dibuktikan via
grep. BELUM terverifikasi (butuh build + HP + WA versi user): (1) apakah
jangkar string DexKit menemukan class data grup yang benar; (2) heuristik
"boolean tunggal" untuk field announcement_group — bila class-nya punya
>1 field boolean, loader menolak dan label tidak tampil (aman, tapi fitur
mati sampai loader disesuaikan per versi WA); (3) penempatan badge di daftar
chat (badge ditambahkan sebagai anak terakhir root baris — posisi visual
persisnya tergantung layout baris WA); (4) apakah instance data grup cukup
sering dibuat WA sebelum badge dibutuhkan (dibuat saat info grup dimuat).
