# Mi-FreeForm merged / fixed build

This project uses `Mi-FreeFormn-flyme` as the base. The other three uploaded projects were used as reference implementations; they were not copied wholesale because the YAMF and reYAMF projects use a different Android 13+ architecture and package/API surface.

## Merged feature areas

- Flyme-style freeform UI, Shizuku/Sui activation, accessibility/foreground-service modes, notification launching, QS tile, and sidebar from the base project.
- Sidebar, QS tile, notification actions, the in-app app picker, the reset-all-windows tile, and the explicit package/activity launch API remain supported launch paths.
- Multiple-window limit with per-user duplicate detection, safer virtual-display cleanup, and secure virtual-display option.
- Shizuku/Sui is the preferred Android 8–15 virtual-display path. The optional LSPosed/Xposed path now includes the framework display bridge, system display-rotation workaround, input routing, app termination, and an AIDL bridge exposed as `user.mifreeform`.
- When neither Shizuku nor LSPosed/Xposed is available, the accessibility/overlay sidebar remains a normal taskbar and launches apps fullscreen.
- Safer input injection, pinch resizing, swipe home/forward on pre-Q, phone/screen receiver registration on Android 13+, and idempotent cleanup.

## Important build/runtime notes

- This project supports Shizuku/Sui, optional LSPosed/Xposed, and standalone taskbar mode. The Xposed display bridge is ROM/API-sensitive and needs real-device validation.
- The source was statically checked and the Gradle task was attempted. The sandbox could not complete Gradle dependency resolution because the Android Gradle Plugin/artifacts were unavailable in the offline environment; this is not a successful APK build result.
- Install and test on a spare device first. Authorize Shizuku/Sui, enable the required accessibility/overlay permissions, then start the app. Android/OEM framework internals differ, so the Shizuku API path may still vary by ROM.
- The release key and prebuilt APKs from the uploaded reference archives are intentionally not included. Sign the APK with your own key.

## Main fixes in this merge

1. Prevent `ConcurrentModificationException` in `StackSet.clean()`.
2. Remove unsafe component-name force unwraps and repeated service/virtual-display cleanup crashes.
3. Validate DPI input and protect input-event arrays/injection from null or invalid state.
4. Add Android 13+ dynamic receiver flags and safe receiver unregistration.
5. Add an optional `secure_virtual_display` setting and clamp the maximum-window setting to 1–10.
6. Fix settings behavior: persist remembered window size across service restarts, apply dimming/DPI/size/opacity changes safely, clamp invalid preference values, make reset actually clear saved overlay state, keep the service-mode selector consistent, and fail safely when no accelerometer exists.
7. Add a Shizuku-compatible explicit package/activity launch API, a reset-all-windows Settings action plus Quick Settings tile, and an animation-speed setting; validate API targets before creating a virtual display.
8. Remove duplicate virtual-display release, clean destroyed view references, and release virtual displays when freeform initialization fails.

## Audit pengaturan (putaran lanjutan)

1. Bug: listener preferensi di `FreeformView` jatuh ke `else -> initConfig()`. Setiap kali posisi/ukuran
   jendela disimpan (`freeform_remember_*`), ukuran jendela aktif ikut ter-reset. Sekarang tiap key ditangani
   eksplisit (`remember_freeform_position`, `use_sui_refuse_to_fullscreen`, `manual_adjust_freeform_rotation`).
2. Nilai yang sebelumnya hardcode kini bisa diatur: `shake_threshold`, `gesture_edge_width`,
   `gesture_min_distance`, `focus_timer_minutes` (dibaca saat init dan diterapkan langsung saat diubah).
3. 28 judul + 20 ringkasan di `settings.xml` yang hardcode Inggris dipindah ke `strings.xml`
   (default + zh-rCN); toast "DPI must be..." juga dipindah ke `dpi_invalid`.
4. Semua 41 key lama diverifikasi terhubung ke kode; tidak ada key kode yang "yatim" di luar state internal.

## Audit pengaturan (putaran 2)

- Pengaturan baru: `float_trigger_ratio` (default 90%), `full_trigger_ratio` (105%), `perf_overlay_interval` (2 dtk).
  Sebelumnya hardcode di `FreeformView`.
- Teks "Done! 🎉" dan "RAM: x/yMB" dipindah ke `focus_timer_done` / `perf_overlay_ram_format`.
- Ditambah terjemahan Indonesia: `values-in/strings.xml` dan `values-in/arrays.xml`
  (nama merek/non-translatable sengaja jatuh ke default).

## Audit LSPosed/Xposed + pemilih backend (putaran 3)

1. **Keamanan:** `MiFreeFormService.startWithMiFreeForm` (berjalan di system_server) sebelumnya menjalankan string `command`
   dari pemanggil lewat `sh` tanpa pemeriksaan, sehingga aplikasi apa pun yang bisa mengambil binder `user.mifreeform`
   dapat menjalankan perintah dengan hak sistem. Kini: hanya UID aplikasi Mi-Freeform yang diterima, perintah harus cocok
   regex `am start -n pkg/cls --user N --display`, paket dan user harus sama, lalu perintah disusun ulang.
2. **Build:** `de.robv.android.xposed:api:82` tidak ada di Google/MavenCentral/JitPack; ditambah repo `https://api.xposed.info/`
   (dibatasi grup itu saja) di `settings.gradle`.
3. **Release:** `minifyEnabled true` tanpa keep rule akan merusak modul (kelas di `xposed_init` dan metode yang di-hook
   lewat nama diganti nama oleh R8). Ditambah keep rule `hook.**` dan `de.robv.android.xposed.**`.
4. **Pilihan backend:** pengaturan baru `backend_mode` (Otomatis / hanya Shizuku / hanya LSPosed / tanpa freeform) lewat
   `BackendSelector`; semua titik peluncuran (sidebar, picker, notifikasi, API) dan kartu status memakainya.
5. Perbaikan putaran 1-2 (listener, 7 pengaturan baru, string, `values-in`) diterapkan ulang di atas zip ini.

## Perbaikan error build CI (putaran 4)

- `HookLauncher.kt`: `param.classLoader` dipanggil di dalam callback hook, tempat `param` adalah `MethodHookParam`
  (tidak punya `classLoader`). Class loader paket kini disimpan di `packageClassLoader` di luar callback.
- `FreeFormHookWindow.kt`: `R.id.texture_view` belum terdefinisi; ditambahkan ke `res/values/ids.xml`.

## Mode freeform bawaan ROM, cara Taskbar (putaran 5)

Terinspirasi farmerbb/Taskbar (Apache-2.0): tanpa Shizuku/LSPosed, aplikasi dibuka lewat
`ActivityOptions.makeBasic()` + `setLaunchWindowingMode(5)` (freeform, refleksi; Android 7.x memakai
`setLaunchStackId(2)`) + `setLaunchBounds(Rect)`. Jendela digambar sistem Android.

- `utils/StandaloneFreeform.kt`: deteksi dukungan (`FEATURE_FREEFORM_WINDOW_MANAGEMENT` atau
  `enable_freeform_support`), peluncuran, dan 5 preset ukuran (standar, besar, setengah kiri/atas,
  setengah kanan/bawah, maksimal).
- `BackendSelector`: mode baru `ROM_FREEFORM` (4) dan `launchFallback()` = LSPosed -> freeform ROM -> fullscreen.
  Mode Otomatis kini: Shizuku -> LSPosed -> freeform ROM -> fullscreen.
- Pengaturan baru: `standalone_window_size` dan tombol "Buka Opsi pengembang" (menampilkan apakah freeform ROM aktif).
- Syarat di perangkat: Opsi Pengembang -> "Enable freeform windows" (sebagian ROM juga
  "Force activities to be resizable"). Profil kerja/klon (userId != 0) tetap jatuh ke fullscreen.
- Belum ada bilah taskbar overlay (start menu / aplikasi terbaru); itu tahap B.

## Taskbar overlay + resize sudut, hapus fitur tak berfungsi (putaran 6)

- Dihapus: cubit-untuk-ubah-ukuran, catatan cepat, timer fokus, overlay RAM (kode, pengaturan, string).
- Resize: tarik sudut kiri/kanan bawah jendela freeform (handle `leftScale`/`rightScale` di
  `view_freeform_flyme.xml`) untuk memperbesar/memperkecil; sudut berlawanan tetap di tempat.
  Memakai jalur `handleToFloatScale` yang sudah ada, ukuran disimpan bila "ingat ukuran" aktif.
- Taskbar (B): `service/TaskbarService.kt` (bilah overlay: tombol start, aplikasi terbaru, jam; start menu
  grid semua aplikasi) + `utils/AppLauncher.kt` (membuka lewat broadcast ke API peluncur, jadi mengikuti
  backend yang dipilih). Pengaturan: `enable_taskbar`, `taskbar_position`, `taskbar_max_recents`,
  akses penggunaan. Aplikasi terbaru: UsageStats bila diizinkan + riwayat peluncuran dari taskbar.

## Resize disamakan dengan freeform_update (putaran 7)

- `hook/view/FreeFormHookWindow.kt` di proyek ini identik 100% dengan versi di `freeform_update-main`
  (diff 0 baris), jadi jalur LSPosed sudah sama.
- Resize sudut di `FreeformView` (jalur Shizuku) kini memakai rumus `resizeFreeForm()` versi itu:
  tarik keluar membesar / ke dalam mengecil, rasio dikunci, batas 30%-90% layar, jendela berubah
  ukuran mengelilingi titik tengah, handle 64dp. Resolusi virtual display tidak diubah saat resize
  (isi hanya diskalakan, tanpa reflow aplikasi).
- Tidak diambil: sisa kode `freeform_update` (versi 2.0.5, lebih lama dari basis ini).
