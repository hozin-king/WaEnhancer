# Rancangan Fase 2: Panel ABProps + Status HD + Anti Virtex/Crash + Anti Lag — WaEnhancer fork hozin-king

Permintaan user (21 Okt 2026): (1) panel toggle untuk memaksa-aktif fitur WA
yang belum rilis (ABProps), (2) hook story/status HD, (3) anti virtex /
anti crash, (4) anti lag. Dokumen ini RISET saja (belum ada kode diubah),
berbasis bukti dari kode fork (`~/workspace/waenhancer-study`) dan dump
string APK WA user (`~/workspace/wa-sticker/dex_strings.txt`, 659.732 string).

Konvensi tingkat keyakinan di dokumen ini:
- **Terbukti** = ada string persis di dex dan/atau pola kerja di kode fork.
- **Inferensi** = kesimpulan wajar dari bukti, belum dibuktikan langsung.
- **Belum terbukti** = tidak ada bukti di bahan yang tersedia; disebutkan
  uji yang bisa membuktikannya.

---

## 1. Panel toggle ABProps

### 1.1 Bagaimana WA membaca ABProps di client (bukti dex)

- Config ABProps datang dari server sebagai JSON dan disimpan lokal:
  string persis `ABProps.json`, `ABPropsManager/onABProps/ABPropsUpdateSaved`,
  `ABPropsManager/invalid format for config; configCode=`,
  `AbstractABProps/invalid json format for property; prefKey=`,
  `Error converting abProps to Json`, `NonDefaultAbPropsJsonGenerator.kt`,
  `ExperimentConfiguration(abProps=...)`.
  → Jadi tiap prop terikat pada **configCode** (kelompok config) dan
  dibaca client dari representasi JSON/file lokal — nilainya plaintext
  JSON sejauh bukti string menunjukkan (tidak ada string enkripsi di
  sekitar ABProps; bandingkan jalur fetch di bawah).
- Jalur ambil config: `PreChatdAbPropsFetcher/fetchPreChatdABProps/...`,
  `ABPropsManager/updatePreChatdABProps/update abprop configs failed`,
  `PrivateABPropsApi/ohaiResponse experimentation config json parse error`
  (OHAI = Oblivious HTTP; jalur "private assignment"),
  `PrivateABProps/performPrivateABAssignment rejected config: ...`
  (ada penolakan config tidak sah di jalur privat itu),
  `WamoABPropsConfigUtils: Unsupported ABProp type: ...`,
  `WamoABPropsConfigUtils: Failed to process field: ...`.
- Lapisan evaluasi bernama MobileConfig: string
  `MOBILECONFIG_WA_ABPROP_EVALUATION` dan `MOBILECONFIG_VERIFY_STORE`.
  Kata "VERIFY_STORE" menunjukkan ada verifikasi integritas di lapisan
  store MobileConfig — **belum terbukti** apakah itu tanda tangan
  kriptografis atas nilai ABProps atau sekadar cek format/konsistensi.
  Bukti yang ada hanya membuktikan: format salah → config ditolak
  (`invalid format for config`), config privat tidak sah → ditolak
  (`rejected config`). Uji pembuktian: ubah satu nilai di file
  `ABProps.json` pada perangkat uji lalu amati apakah nilai baru dipakai,
  ditolak, atau ditimpa fetch berikutnya (lihat strategi C di 1.2).
- Di sisi kode, tiap fitur punya class **Observer/Ext** yang membaca
  prop-nya sendiri. 45 class ketemu di dex, a.l.:
  `AccountSwitchingAbPropObserver.kt`, `FolderRedesignABPropsObserver.kt`
  (dari grep sesi sebelumnya), `LottieStickerAbPropsObserver.kt`,
  `StatusTextSpoilerReceiverAbPropObserver.kt`,
  `StatusReactionStickerReceiverAbPropObserver.kt`,
  `GroupStatusFutureProofAbPropObserver.kt`,
  `EventInviteFutureProofAbPropsObserver.kt`,
  `NewsletterWaitListAbPropObserver.kt`,
  `BotJidMigrationAbPropsObserver.kt`, `MetaAiFabPropertiesProvider.java`,
  `StatusInfraAbPropObserver.kt`, `AfterReadAbPropsObserver.kt`,
  `AddressBookReadAbPropsObserver.kt`, `SpoilerFutureProofAbPropsObserver.kt`,
  `DbiAbPropsObserver.kt`, `SubscriptionAbPropsImpl.kt` (**di luar scope
  panel** — gerbang langganan berbayar).
  Infrastruktur pusatnya: `ABPropsManager.kt`, `WamoAbPropsManager.kt`,
  `MetaOneAbPropsManager.kt`, `NovaAbPropsManager.kt`,
  `GroupAbPropsManager` + `GroupAbProps*.kt` (AB-prop per grup),
  `StanzaReceiver/getAbPropIntWithBound/Value`, `getAbProps`,
  `GetAbPropValue`, `WaBloksAbPropValueImpl/getAbPropValue`.

### 1.2 Temuan kunci: fork SUDAH punya hook getter pusatnya

ABProps di WA dibaca lewat getter MobileConfig berkunci **kode numerik**
(bukan nama string). Fork sudah meng-hook persis getter itu:

- `Unobfuscator.loadPropsBooleanMethod` — jangkar string
  `"Unknown BooleanField"`; `loadPropsIntegerMethod` — jangkar
  `"Unknown IntField"` (Unobfuscator.kt:762 dan :771).
- `Others.hookProps()` (features/general/Others.kt:712 dst.) meng-hook
  kedua metode itu dan menimpa hasil dari dua peta statis:
  `Others.propsBoolean = ConcurrentHashMap<Int, Boolean>` dan
  `Others.propsInteger = ConcurrentHashMap<Int, Int>` (Others.kt:58–60),
  dengan argumen pertama bertipe Integer = kode prop yang ditanyakan.
- Fitur-fitur fork sudah memakai pola ini sebagai "panel" tersebar:
  MediaQuality menulis ±20 kode, MediaPreview `propsBoolean[24205]`,
  CustomToolbar `propsBoolean[6481]`, HideSeen `propsBoolean[19148]`,
  Others sendiri `propsBoolean[18250]`, `propsInteger[3877]`, dst.

Implikasinya: **panel ABProps tidak butuh mekanisme baru** — cukup satu
Feature baru yang, untuk tiap switch aktif, menulis `kode -> true` ke
`Others.propsBoolean` (dan bila perlu nilai int ke `propsInteger`).
Strategi perbandingan:

- **A. Hook getter pusat (dipilih)** — Terbukti bekerja di fork (pola di
  atas), satu titik untuk semua prop, tahan terhadap perbedaan nama
  class observer antar versi WA (jangkar string getter relatif stabil;
  memang itu cara fork bertahan selama ini). Risiko: hook ini global —
  kode yang salah petakan akan menyalakan fitur yang tidak dimaksud
  (lihat risiko kode tak terpetakan di 1.3).
- **B. Hook per-Observer** — 45 class target, nama ter-obfuscate per
  versi, tiap observer membaca prop dengan cara berbeda; rapuh dan
  tidak ada pola loader per-observer di fork. Ditolak.
- **C. Edit file `ABProps.json` langsung** — mungkin (bukti file ada),
  tapi berisiko ditolak/ditimpa: `MOBILECONFIG_VERIFY_STORE`,
  `rejected config`, dan `ABPropsUpdateSaved` menunjukkan config
  dikelola aktif oleh WA. Ditolak sebagai jalur utama; berguna sebagai
  UJI pembuktian (1.1) dan jalur darurat riset kode (1.3).

### 1.3 Masalah sebenarnya: pemetaan fitur → kode numerik BELUM terbukti

Dari dump string saja kode numerik per fitur **tidak bisa diketahui**:
kode adalah konstanta int di badan metode observer (tidak muncul
sebagai string), dan nama snake_case yang ketemu di dex
(`account_switching`, dst.) adalah nama field analitik/log, bukan kunci
prop (terbukti dari konteksnya: `account_switching_latency_ms`,
`account_switching_num_accounts`, …). Jadi daftar di 1.4 adalah daftar
**fitur kandidat**; kode pasangannya harus dipetakan lewat salah satu uji:

1. **Logging runtime (paling realistis)**: varian DebugFeature
   (features/others/DebugFeature.kt sudah ada sebagai pola) yang
   mencatat semua kode yang ditanyakan ke getter boolean selama sesi
   WA normal, lalu korelasikan: kode yang HANYA dibaca saat membuka
   layar terkait (mis. layar status, daftar chat, info grup) adalah
   kandidat fitur layar itu. Diulang per versi WA.
2. **Decompile dex** (jadx pada base.apk user) untuk membaca konstanta
   di tiap Observer — di luar bahan riset ini (hanya strings yang
   didump), tapi jalur paling pasti bila dikerjakan.
3. **Diff ABProps.json** antar akun/region yang fiturnya beda (butuh
   dua perangkat/akun; tidak selalu tersedia).

Risiko tambahan yang harus ditulis apa adanya di UI panel:
- Observer bernama **FutureProof** (`GroupStatusFutureProof...`,
  `EventInviteFutureProof...`, `SpoilerFutureProof...`) mengisyaratkan
  kode fitur sudah ada tapi fiturnya mungkin juga butuh dukungan
  server/stanza — menyalakan prop client bisa menghasilkan UI kosong
  atau tidak berefek (inferensi dari penamaan; belum terbukti per fitur).
- Prop bertipe bukan-boolean (int/string) tidak akan terpengaruh
  switch boolean; panel fase pertama hanya boolean.
- Upgrade WA dapat menggeser kode → panel wajib punya daftar kode
  per versi dan gagal diam-diam (tidak crash) bila kode tak dikenal,
  mengikuti pola try/catch fork.

### 1.4 Daftar prop kandidat (fitur user-facing) + rancangan UI

| Fitur (nama tampil) | Class bukti di dex | Catatan keyakinan |
|---|---|---|
| Ganti / multi akun | `AccountSwitchingAbPropObserver.kt` (+ `showAccountSwitchingAvailableNotification`) | Fitur nyata, Terbukti ada; kode Belum terbukti |
| Folder chat (redesign) | `FolderRedesignABPropsObserver.kt` | Terbukti ada; kode Belum terbukti |
| Stiker Lottie | `LottieStickerAbPropsObserver.kt` | Terbukti ada; kode Belum terbukti |
| Teks spoiler di status (penerima) | `StatusTextSpoilerReceiverAbPropObserver.kt` | Sisi penerima; kode Belum terbukti |
| Reaksi stiker ke status (penerima) | `StatusReactionStickerReceiverAbPropObserver.kt` | Sisi penerima; kode Belum terbukti |
| Status grup | `GroupStatusFutureProofAbPropObserver.kt` | FutureProof → mungkin butuh server (Inferensi) |
| Undangan event | `EventInviteFutureProofAbPropsObserver.kt` | FutureProof → sama seperti di atas |
| Daftar tunggu newsletter | `NewsletterWaitListAbPropObserver.kt` | Terbukti ada; kode Belum terbukti |
| Dukungan JID bot (fondasi AI di grup) | `BotJidMigrationAbPropsObserver.kt` (+ `BotPlatformRegistrationSuccessFutureProofAbPropsObserver.kt`, `WabaiUtilsImpl/isFirstPartyBotAndAbPropEnabled`) | Hanya fondasi client; keanggotaan bot tetap divalidasi server (lihat desain grup) |
| Tombol Meta AI (FAB) | `MetaAiFabProperties.kt`, `MetaAiFabPropertiesProvider.java`, `BotContactPhotoMediator/maybeInvalidateMetaAiPhotoOnAbPropChange` | UI client; ketersediaan AI sendiri urusan server/region |
| Status infra (fondasi status baru) | `StatusInfraAbPropObserver.kt`, `StatusInfraFixStatusInfoAbPropObserver`, `StatusLidMigrationAbPropsObserver` | Infrastruktur; efek user-facing tidak langsung — kandidat lapis kedua |

UI yang diusulkan (mengikuti pola fork):
- Satu Feature baru `xposed/features/others/AbPropsPanel.kt`,
  terdaftar di `xposed/core/FeatureLoader.kt`, TANPA loader baru
  (memakai ulang hook Others.hookProps — pastikan Others selalu aktif
  sebelum panel menulis peta; urutan registrasi di FeatureLoader perlu
  dicek saat implementasi).
- Satu file pref baru `res/xml/fragment_abprops.xml` (atau sub-layar di
  fragment_general.xml): satu SwitchPreference per fitur di tabel 1.4,
  key mengikuti nama fitur, mis. `abprop_account_switching`,
  `abprop_folder_redesign`, `abprop_lottie_sticker`,
  `abprop_status_text_spoiler`, `abprop_status_reaction_sticker`,
  `abprop_group_status`, `abprop_event_invite`,
  `abprop_newsletter_waitlist`, `abprop_bot_jid`, `abprop_meta_ai_fab`.
- Di doHook: baca switch → cari kode dari tabel kode internal
  (Map prefKey → Int, awalnya KOSONG sampai pemetaan 1.3 dikerjakan;
  switch tanpa kode = tidak menulis apa-apa dan diberi summary
  "kode belum dipetakan untuk versi WA ini" — jangan mengarang kode).
- Strings di `res/values/strings.xml` + entri `utils/FeatureCatalog.kt`
  per pola fitur lain.

Keyakinan bagian 1: mekanisme hook **Terbukti**; jalur config JSON
**Terbukti**; tanda tangan/ enkripsi nilai **Belum terbukti** (uji:
edit ABProps.json); pemetaan kode per fitur **Belum terbukti**
(uji: logging runtime / decompile).

---

## 2. Status HD

### 2.1 Bukti pipeline di fork (sudah ada, sebagian menyentuh status)

Fitur `MediaQuality.kt` (pref `videoquality` / `imagequality` di
`res/xml/fragment_media.xml`, terdaftar di FeatureLoader) sudah
meng-hook pipeline kualitas media WA secara umum:

- Gambar: `Unobfuscator.loadProcessImageQualityClass` — jangkar string
  `"ProcessImageQuality("` (Unobfuscator.kt:2966). Constructor di-hook,
  field dari `getAllMapFields`: `maxKb` → 50×1024, `quality` → 100,
  `maxEdge` → 6000. Plus kode propsInteger kualitas (1577, 6030, 2656,
  15752, 15746 → maxKb; 1581, 1575, … → 100; 1576, 2654, 6032, 15748,
  3068 → maxEdge 3840) dan propsBoolean 6033/26289/22375 = true,
  9569 = false.
- Video: `loadProcessVideoQualityClass` — jangkar `"ProcessVideoQuality("`
  (Unobfuscator.kt:646). Field: `videoLimitMb` → max(90, pref
  `video_limit_size`), `videoMaxEdge` → 1920, `videoMaxBitrate` →
  10.000×1000, `mainHighBitRate` → null, `videoBitrateMode` → CBR.
  Ditambah `loadVideoTranscoderStartMethod` (mematikan boolean ke-3
  processor + `forceSingleTranscoding` = true pada
  MediaDataVideoConfiguration), `loadMediaTranscoderStart`, dan kode
  propsInteger bitrate/edge (3755–3758 → 10.000; 4686, 3654, 3183,
  4685 dan 594, 12852 → 1920).
- Khusus Stories/status: `MediaQuality.enableMediaQualityForStories()`
  SUDAH ada — dua jalur yang dicoba berurutan dan dicatat di pref
  modul `legacy_quality_selection`:
  1. `loadMediaQualitySelectionMethod` — jangkar string
     `"enable_media_quality_tool"`, return boolean → diganti konstant
     `true` (memunculkan pemilih kualitas media).
  2. `loadBottomBarConfigClass` — jangkar `"BottomBarConfig("`, field
     `supportsHdQuality` → `true` pada constructor.
  Juga `Others.propsBoolean[14447] = false` ("Disable manual calculation
  ProcessMediaQuality") dan `propsBoolean[5549] = true`.

### 2.2 Bukti pipeline di dex WA

- String transcode: `media_transcode_quality` (kolom
  `media_content.media_transcode_quality`), `UnifiedImageTranscoderAndroid.kt`,
  `VideoTranscoder/Transcode/Output`, `VideoTranscoder/transcode/cancel`,
  `MediaTranscode/failed validation`, `meditranscodequeue/...`,
  `TranscodeSuccess(outputDuration=...`, dan yang penting:
  **`forceTranscodeAbProp`** — ada ABProp yang memaksa transcode
  (kandidat kode boolean untuk dimatikan lewat mekanisme Bagian 1;
  kodenya Belum terbukti, jalur pemetaan sama dengan 1.3).
- Bukti status memakai jalur media yang sama: skema DB status merujuk
  tabel media bersama (`status_media_link.media_content_row_id` JOIN
  `media_content`, `status_thumbnail`) — **Inferensi kuat** bahwa
  ProcessImageQuality/ProcessVideoQuality yang di-hook MediaQuality
  juga memproses unggahan status, bukan hanya chat. Bukti pendukung:
  enableMediaQualityForStories memang ditulis pengarang fork untuk
  Stories dengan class yang sama-sama umum (BottomBarConfig adalah
  config bar kirim media, dipakai composer chat maupun status).
- Yang BELUM terbukti dari bahan ini: apakah ada class proses
  kualitas KHUSUS status yang melewati hook di atas pada versi WA
  user. Uji: kirim status foto/video dengan MediaQuality aktif lalu
  bandingkan ukuran/resolusi file yang diterima di perangkat kedua
  (atau dari log `media_transcode_quality` di DB bila bisa dibaca).

### 2.3 Usulan rancangan "Status HD"

Karena fondasi sudah ada, fase 2 BUKAN fitur baru dari nol, melainkan:

1. Pref baru eksplisit di `fragment_media.xml`: `statusquality`
   (switch induk, kelompok sama dengan downloadstatus/storyhistory)
   + strings + FeatureCatalog.
2. Feature tipis `xposed/features/media/StatusQuality.kt` ATAU cabang
   di MediaQuality (pilih cabang di MediaQuality — lebih sedikit
   duplikasi hook; Feature terpisah hanya bila ingin gate independen):
   - Pastikan jalur `enableMediaQualityForStories()` selalu jalan
     saat `statusquality` aktif, TANPA mensyaratkan `videoquality` /
     `imagequality` (saat ini ia dipanggil tanpa syarat di doHook —
     perilaku itu dipertahankan/didokumentasikan).
   - Saat aktif: tulis subset kode props yang sama dengan jalur
     gambar/video MediaQuality (daftar kode di 2.1) agar status
     mendapat maxKb/quality/maxEdge dan bitrate yang sama walau pref
     kualitas chat mati.
   - Kandidat tambahan: kode di balik `forceTranscodeAbProp` → false
     (menonaktifkan transcode paksa untuk status) — menunggu pemetaan
     kode (1.3); jangan ditulis sebelum terbukti.
3. Parameter yang diubah (sudah terbukti polanya): kualitas JPEG 100,
   maxEdge gambar 6000, maxKb 50 MB; video maxEdge 1920, bitrate
   10 Mbps CBR, limit ukuran pref `video_limit_size`,
   forceSingleTranscoding = true.

### 2.4 Batas server (tulis apa adanya)

- Hook hanya mengubah keputusan CLIENT sebelum upload. Server WA tetap
  dapat menerapkan batas sendiri (durasi status video, ukuran file,
  bitrate maksimum yang diterima, transcode ulang di server untuk
  penerima tertentu) — tidak ada bukti string yang mengukur batas
  angka server; **Belum terbukti** angka pastinya. Uji: bandingkan
  file status terkirim (perangkat pengirim) vs diterima (perangkat
  kedua) pada durasi/ukuran berbeda.
- Kualitas yang diterima kontak juga tergantung client penerima
  (pengaturan unduh/penghemat data penerima) — di luar kendali modul.

Keyakinan bagian 2: titik hook + parameter **Terbukti** (kode fork);
status memakai pipeline yang sama **Inferensi kuat** (skema DB +
fitur Stories yang sudah ada); tidak adanya jalur khusus status yang
lolos **Belum terbukti** (uji kirim-bandingkan); batas angka server
**Belum terbukti**.

---

## 3. Anti Virtex / Anti Crash

### 3.1 Titik render teks pesan (bukti fork)

- Baris percakapan diikat lewat pola listener fork:
  `xposed/features/listeners/ConversationItemListener.kt`
  (`onItemBind`, `isViewBoundToMessage(view, messageID)`), sudah
  dipakai banyak fitur (GroupAdmin memasang badge di header;
  CopySelectionMessage mencari TextView berview-id **`message_text`**
  di baris yang terikat ke pesan yang sama, CopySelectionMessage.kt:51).
- Teks pesan diakses seragam lewat wrapper `FMessageWpp`
  (mis. `wpp.messageStr`, `wpp.key.messageID` — pola
  CopySelectionMessage.kt:41–49; class loader-nya
  `Unobfuscator.loadFMessageClass`, Unobfuscator.kt:423), dan class
  baris percakapan via `Unobfuscator.loadConversationRowClass`
  (Unobfuscator.kt:866).
- Di dex: regex `\p{InCombiningDiacriticalMarks}+` ketemu — WA sendiri
  punya normalisasi combining-mark di suatu tempat (**Inferensi**:
  bukan bukti WA kebal virtex; lokasi/tujuannya tidak diketahui dari
  string saja).
- Modul sudah punya infrastruktur crash di sisi MODUL
  (`App.kt`, `activities/CrashReportActivity.kt`) — itu untuk crash
  modul sendiri, bukan penjaga WA; jangan dicampur.

### 3.2 Titik hook yang diusulkan

Feature baru `xposed/features/general/AntiVirtex.kt`, pref
`antivirtex` di `res/xml/fragment_general.xml`:

1. **Deteksi di titik ikat baris (utama)**: daftarkan listener
   ConversationItemListener (pola CopySelectionMessage/GroupAdmin);
   pada onItemBind, ambil `messageStr` pesan yang terikat; jalankan
   heuristik (3.3) pada SALINAN string. Murah karena hanya untuk
   pesan yang sedang ditampilkan.
2. **Netralisasi tampilan saja**: bila lolos ambang virtex, setelah
   WA selesai mengisi baris, ganti isi TextView `message_text` dengan
   potongan aman (mis. 500 karakter pertama, combining dibatasi) +
   sufiks penanda "teks dipotong oleh Anti Virtex". Data pesan asli
   di DB/objek FMessage **tidak diubah** — menerjemahkan, menyalin,
   dan membuka info pesan tetap memakai teks asli (pola akses
   CopySelectionMessage membuktikan teks asli tetap bisa diambil dari
   wrapper walau tampilan diganti).
3. **Cadangan untuk jalur non-baris**: kutipan (quoted), notifikasi,
   dan pratinjau daftar chat dirender di jalur berbeda (daftar chat
   lewat ContactItemListener) — fase pertama TIDAK menyentuhnya;
   dicatat sebagai keterbatasan, bukan dijanjikan.

### 3.3 Heuristik deteksi (semua ambang jadi konstanta, bisa dikalibrasi)

- Panjang ekstrem: `length > 4.000` karakter (pesan WA normal jauh di
  bawah ini; batas kirim WA sendiri ±65 ribu — ambang render dipilih
  konservatif agar pesan panjang sah hanya dipotong tampilannya,
  bukan ditolak).
- Kepadatan combining: rasio karakter kategori Mn/Me (Character.getType
  == COMBINING_SPACING_MARK / ENCLOSING_MARK / NON_SPACING_MARK)
  > 15% dari total, atau run combining berturutan > 30 (pola Zalgo —
  selaras regex `\p{InCombiningDiacriticalMarks}` yang ada di WA).
- RTL/bidi override: mengandung U+202A–U+202E atau U+2066–U+2069
  lebih dari 2 kali (di luar pemakaian wajar bahasa RTL).
- Pengulangan: blok 1–8 karakter yang sama berulang > 200 kali, atau
  rasio kompresi sederhana (panjang setelah RLE / panjang asli)
  sangat rendah pada string > 1.000 karakter.
- Skor gabungan: potong tampilan bila (panjang ekstrem) ATAU
  (dua heuristik lain terpenuhi) — mengurangi false positive pada
  teks Arab/Ibrani sah (RTL + combining wajar) yang hanya memicu
  satu heuristik.

### 3.4 Guard anti crash-loop

- Semua hook dibungkus try/catch dan gagal = lewati pesan itu
  (pola umum fork), heuristik dibatasi memindai maks. 8.000 karakter
  pertama agar penjaganya sendiri tidak jadi sumber lag.
- **Safe-mode hitungan start**: simpan di pref modul (lewat bridge
  pref seperti pola `UnobfuscatorCache.sPrefsCacheHooks` di
  MediaQuality) catatan waktu start proses WA; bila WA start ulang
  ≥ 5 kali dalam 10 menit saat `antivirtex` aktif, nonaktifkan hook
  virtex untuk sesi berikutnya dan beri tanda pref
  `antivirtex_safemode` (user bisa mengaktifkan ulang manual dari
  pengaturan). Ini memutus skenario: chat virtex dibuka → crash →
  WA restart → chat yang sama dirender lagi → crash lagi.
- Yang TIDAK dijanjikan: virtex yang menyerang jalur selain teks
  baris percakapan (nama grup/kontak ekstrem, stiker/animasi rusak,
  media malformed) — di luar titik hook ini; nama grup ekstrem bisa
  jadi kandidat fase berikut lewat titik header yang sama dengan
  pola GroupAdmin.

Keyakinan bagian 3: titik render + cara akses teks **Terbukti**
(pola fork); heuristik **Inferensi** (praktik umum anti teks-bom,
ambang belum dikalibrasi ke contoh virtex nyata — uji: kumpulkan
2–3 contoh virtex yang beredar dan pesan sah ekstrem — teks Arab
panjang, pesan berisi kode — lalu ukur false positive/negative);
efektivitas netralisasi per versi WA **Belum terbukti** sampai diuji
di perangkat.

---

## 4. Anti Lag

Prinsip bagian ini: hanya sumber lag yang titik hook-nya plausibel
dari bukti fork/dex. Tiga kandidat lolos saringan; sisanya ditolak
disebutkan alasannya.

### 4.1 Render pesan berat di daftar chat & percakapan — PLAUSIBEL

- Bukti: daftar chat diikat lewat `ContactItemListener.onBind`
  (dipakai GroupAdminOnly fase 1 untuk badge), percakapan lewat
  ConversationItemListener (Bagian 3). Pratinjau pesan di daftar
  chat merender teks yang sama dengan baris percakapan, jadi virtex
  dan pesan raksasa melambatkan DUA permukaan ini.
- Usulan: Anti Lag berbagi mesin heuristik Anti Virtex (Bagian 3)
  dengan ambang lebih longgar, diterapkan juga pada pratinjau
  ContactItemListener: potong pratinjau pesan ekstrem ke panjang
  wajar. Pref terpisah `antilag_preview` agar bisa dimatikan tanpa
  mematikan Anti Virtex.
- Keyakinan: titik hook **Terbukti**; besarnya sumbangan ke lag
  **Inferensi** (jelas untuk kasus ekstrem, kecil untuk chat normal).

### 4.2 Autoplay animasi (stiker animasi / GIF) — SEBAGIAN PLAUSIBEL

- Bukti dex: string `autoplay_animated_images_enabled` ada — ada
  prop/pengaturan autoplay gambar animasi di WA (kemungkinan kunci
  pengaturan internal atau prop berkode; bentuk pastinya **Belum
  terbukti**). String autoplay lain yang ketemu kebanyakan milik
  permukaan FB/feed (`collections_reels_autoplay_visible`,
  `MARKETPLACE_VIDEO_AUTOPLAY_TRIGGERED`, `TOP_OF_HOME_MOBILE_TRAY_AUTOPLAY`)
  — jangan dipakai sebagai bukti untuk chat.
- Bukti fork: fitur `features/others/Stickers.kt` sudah menyentuh
  jalur stiker (titik masuk riset), dan mekanisme props (Bagian 1)
  tersedia bila `autoplay_animated_images_enabled` ternyata prop
  berkode numerik.
- Usulan: pref `antilag_no_autoplay` — jalur implementasi ditentukan
  setelah membuktikan bentuk pengaturannya (uji: cari kode prop via
  logging Bagian 1.3, atau hook pemutar animasi di jalur Stickers).
  Sampai terbukti, fitur ini TIDAK ditulis sebagai switch aktif —
  dicatat sebagai kandidat riset, bukan janji.
- Keyakinan: keberadaan pengaturan **Inferensi** (satu string);
  titik hook **Belum terbukti**.

### 4.3 Pembuatan thumbnail berulang — TIDAK PLAUSIBEL sebagai hook lag

- Bukti dex hanya menunjukkan thumbnail adalah artefak tersimpan di
  DB: tabel `message_thumbnail`, `mms_thumbnail_metadata`, kolom
  `full_thumbnail`, `media_hash_thumbnail`, indeks
  `status_thumbnail_media_content_row_id_index`. Artinya thumbnail
  di-generate sekali lalu di-cache oleh WA sendiri; tidak ada bukti
  string tentang generasi berulang per tampil, dan fork tidak punya
  hook di jalur thumbnail.
- Kesimpulan: jangan bangun fitur di sini — tidak ada titik hook
  yang dibuktikan dan masalahnya sendiri tidak terbukti ada.
  (Berbeda dengan klaim generik "anti lag" di modul WA lain; di
  dokumen ini yang tidak terbukti tidak dijual sebagai fitur.)

### 4.4 Yang sengaja TIDAK diusulkan

- "Membersihkan cache/DB WA", "membatasi jumlah chat yang dimuat",
  "mematikan indeks/sinkron di latar" — tidak ada titik hook di fork
  dan/atau menyentuh data/sinkronisasi WA (risiko merusak data);
  di luar scope desain ini.
- Lag dari ukuran database atau jumlah chat itu sendiri adalah
  urusan WA; modul hanya memotong biaya render per item (4.1),
  bukan mengubah cara WA memuat daftar.

Pref ringkas bagian 4: `antilag_preview` (4.1, satu Feature bersama
Anti Virtex atau Feature sendiri `xposed/features/general/AntiLag.kt`
di fragment_general.xml); `antilag_no_autoplay` DITUNDA sampai
buktinya cukup (4.2).

---

## Ringkasan status & urutan kerja yang disarankan

1. **Panel ABProps** — mekanisme Terbukti (hook getter Others), yang
   kurang hanya pemetaan kode: kerjakan logging pemetaan lebih dulu
   (varian DebugFeature), baru UI panel diisi kode nyata per fitur.
2. **Status HD** — fondasi MediaQuality sudah ada dan sudah
   menyentuh Stories; pekerjaan fase 2 = pref `statusquality` +
   memastikan subset kode props untuk status + uji kirim-bandingkan
   antar perangkat. Risiko terkecil dari empat bagian.
3. **Anti Virtex/Crash** — titik hook Terbukti (ConversationItemListener
   + `message_text` + FMessageWpp.messageStr); pekerjaan utama =
   kalibrasi heuristik dengan contoh nyata + guard safe-mode.
4. **Anti Lag** — hanya 4.1 yang siap dibangun (berbagi mesin dengan
   Anti Virtex); 4.2 menunggu pembuktian bentuk pengaturan autoplay;
   4.3 ditolak berbasis bukti.

## Status

Riset fase 2 selesai 21 Okt 2026. Belum ada kode diubah oleh dokumen
ini. Implementasi menunggu perintah user, mengikuti antrean setelah
fase 1 (Story History + GroupAdminOnly — sudah diimplementasikan
lokal, belum di-push).
