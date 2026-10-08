# Audit fitur referensi dan perbaikan multi-capability

Audit ini membandingkan source project gabungan dengan tiga arsip referensi:

- `YAMFsquared-main.zip`
- `reYAMF-main.zip`
- `freeform_update-main.zip`

## Batasan penting

Build ini sekarang menyediakan tiga jalur kemampuan. Kode hook Xposed/LSPosed dimuat hanya bila framework hook mengaktifkan module ini; pada instalasi biasa kode tersebut tidak dijalankan.

- **Dengan Shizuku/Sui:** virtual display, freeform window, task/display management, input injection, dan pembukaan multi-window melalui service aplikasi.
- **Dengan LSPosed/Xposed:** framework display bridge, system display-rotation workaround, input routing, app termination, recents action “Open with Mi-Freeform”, dan binder `user.mifreeform` untuk freeform tanpa Shizuku. Jalur ini sensitif terhadap versi Android/OEM.
- **Tanpa keduanya:** accessibility/overlay taskbar/sidebar tetap dapat menampilkan daftar aplikasi, membuka aplikasi secara normal fullscreen, menjalankan action notifikasi secara normal, dan memakai explicit launch API sebagai launcher biasa. Ini bukan freeform window sistem.

## Matriks fitur

| Fitur referensi | Fungsi | Status build ini | Keputusan |
|---|---|---|---|
| Multiple windows | Membuka beberapa aplikasi dalam virtual display terpisah | Ada | Dipertahankan dengan batas 1–10 window dan deteksi duplikat per-user |
| Minimize/freeform kecil | Mengubah window menjadi floating/minimized view | Ada | Dipertahankan melalui sidebar dan kontrol window |
| Resize | Mengubah ukuran window | Ada | Resize bar dan pinch-to-resize tersedia |
| App rotation | Menyesuaikan orientasi virtual display | Ada | Dipertahankan melalui rotation watcher dan task listener |
| FLAG_SECURE | Mencegah konten muncul di screenshot/screen recording | Ditambahkan | Setting `secure_virtual_display` dan `VIRTUAL_DISPLAY_FLAG_SECURE` |
| Custom DPI | Mengubah density/DPI freeform | Ada | Nilai divalidasi 50–500 |
| Default window size | Mengatur ukuran awal | Ada | Setting portrait/landscape dipertahankan |
| Remember position | Menyimpan posisi window | Ada | Reset dan penyimpanan posisi diperbaiki |
| Remember size | Menyimpan ukuran terakhir | Diperbaiki | Sekarang tersimpan lintas restart service, bukan hanya selama object window hidup |
| Sidebar/favorite apps | Membuka aplikasi favorit dari sisi layar | Ada | Dipertahankan melalui service sidebar dan app picker |
| App list/app picker | Memilih aplikasi untuk dibuka | Ada | Dipertahankan melalui `FloatingActivity` |
| Quick Settings: new window | Membuka app picker dari Quick Settings | Ada | Dipertahankan |
| Quick Settings: reset all | Menutup semua window aktif | Ditambahkan | Tile baru dan action Settings baru |
| Notification launch | Membuka aplikasi dari notifikasi | Ada | Shizuku memakai freeform; Xposed memakai binder bridge; tanpa keduanya mengirim PendingIntent normal |
| Explicit launch API | Aplikasi lain meminta target package/activity dibuka | Ditambahkan/diperbaiki | Broadcast tervalidasi; Shizuku freeform, Xposed bridge, atau fullscreen normal |
| Launch current foreground app | Membuka aplikasi yang sedang terlihat sekarang | Sebagian | Xposed dapat menyediakan jalur launcher/recents; API explicit tetap membutuhkan target package/activity |
| Recents app icon | Membuka dari ikon aplikasi di recents | Ditambahkan untuk Xposed | `HookLauncher` menambahkan action “Open with Mi-Freeform” pada Launcher3/Quickstep |
| Taskbar integration | Membuka dari taskbar | Ada | Sidebar/taskbar internal selalu tersedia; launcher taskbar OEM tetap bergantung pada API/OEM hook |
| Home long press/Assistant | Membuka current app melalui tombol Home/Assistant | Sebagian | Tidak mengganti Assistant global; recents action dan explicit API tersedia |
| App icon long press | Menu launcher untuk membuka freeform | Sebagian | Jalur OEM launcher sensitif versi; app picker/sidebar internal tetap tersedia |
| SurfaceView mode | Alternatif TextureView/SurfaceView | Tidak dipindahkan | Membutuhkan perubahan rendering dan input besar; TextureView dipertahankan agar input Shizuku stabil |
| Show IME in window | Memaksa keyboard tampil pada virtual display | Belum | Referensi memakai `setDisplayImePolicy` dan flag display khusus; perlu API hidden berbeda per Android/OEM sehingga tidak dipasang secara palsu |
| Launch method move/start/hybrid | Cara memindahkan task atau memulai activity | Belum | Mode referensi bergantung pada task/launcher hook; build ini memakai jalur activity start melalui Shizuku yang lebih aman |
| Rounded corner | Mengatur radius window | Ada | Setting `corner_radius`, nilai divalidasi |
| Sidebar transparency | Mengatur transparansi sidebar | Ada | Setting `floating_alpha` |
| Standalone taskbar fallback | Membuka app dari sidebar tanpa Shizuku | Ditambahkan | App picker/sidebar dan notification API membuka aplikasi fullscreen normal |
| Animation speed | Mengatur kecepatan animasi window/sidebar | Ditambahkan | Setting `animation_speed` 50–200%; memengaruhi durasi animasi utama |
| Sidebar startup | Menyalakan sidebar saat boot | Sebagian ada | Boot receiver dan keep-alive service tetap dipertahankan; accessibility service tetap mengikuti lifecycle Android |
| Auto-close task | Menutup window ketika task hilang | Ada | `TaskStackListener` menghancurkan view setelah task terakhir hilang |

## Bug yang ditemukan dan diperbaiki

### 1. Build Kotlin gagal pada `Log`

`FreeformView.kt` memakai `Log.w(...)` tanpa import `android.util.Log`.

Perbaikan: import ditambahkan.

### 2. VirtualDisplay dilepas dua kali saat activity gagal dibuka

Sebelumnya:

1. `freeformView.destroy()` sudah me-release virtual display.
2. Kode kemudian memanggil `virtualDisplay.release()` sekali lagi.

Pada beberapa Android, release kedua dapat memicu exception.

Perbaikan: release kedua dihapus.

### 3. VirtualDisplay bocor ketika inisialisasi FreeformView gagal

Jika `initSystemService()`, `initConfig()`, atau `initView()` gagal karena hidden API/ROM, display sudah dibuat tetapi tidak dilepas.

Perbaikan: inisialisasi dibungkus cleanup; display dilepas saat initialization gagal.

### 4. Daftar FreeformView menyimpan object yang sudah dihancurkan

Object dengan `isDestroy == true` bisa tertinggal di `mFreeformViews` sehingga list membesar dan perhitungan window menjadi tidak bersih.

Perbaikan: pruning dilakukan sebelum dan sesudah command start/destroy.

### 5. Reset hanya menutup satu window

Referensi reYAMF memiliki reset-all tile, tetapi build sebelumnya hanya punya action untuk menutup window terakhir.

Perbaikan:

- action `ACTION_DESTROY_ALL_FREEFORM`;
- Settings action `Reset all windows`;
- Quick Settings tile `ResetAllWindowsTileService`.

### 6. Remember size tidak bertahan setelah service restart

Nilai hanya berada di field object `FreeformView`, sehingga hilang ketika service dibuat ulang.

Perbaikan: ukuran portrait/landscape sekarang disimpan ke SharedPreferences dan dipulihkan dengan clamp sesuai ukuran layar.

### 7. Reset overlay tidak benar-benar mereset semua nilai

Reset sebelumnya menulis angka `0` atau ukuran baru ke beberapa key. Ini dapat membuat posisi/ukuran tersimpan dalam kondisi tidak konsisten.

Perbaikan: key posisi dan ukuran tersimpan sekarang dihapus saat reset.

### 8. Dimming, DPI, dan ukuran default tidak langsung diterapkan

Slider `freeform_dimming_amount`, DPI, dan ukuran portrait/landscape sebelumnya terutama mengubah konfigurasi untuk window berikutnya. Reset DPI juga menulis nilai `0` walaupun slider memiliki batas minimum 50.

Perbaikan:

- dimming aktif diperbarui pada window yang sedang tampil;
- perubahan DPI langsung me-resize virtual display;
- perubahan ukuran langsung memperbarui ukuran/display aktif;
- reset DPI memakai nilai valid 50 sebagai mode default.

### 9. Opacity dapat tertimpa menjadi 100%

Inisialisasi view mengatur opacity sesuai preference, kemudian menulis `alpha = 1f` sehingga nilai preference hilang pada tahap awal.

Perbaikan: opacity selalu memakai nilai yang sudah divalidasi.

### 10. Nilai preference di luar range dapat merusak ukuran/window

DPI, dimming, ukuran freeform, ukuran floating view, opacity, dan radius dapat tersimpan sebagai nilai invalid melalui data lama atau perubahan programatik.

Perbaikan: semua nilai dibatasi sebelum dipakai.

### 11. Selector service mode dapat menampilkan mode yang salah

UI selalu memilih Accessibility jika accessibility aktif, walaupun preference sebenarnya memilih Foreground Service.

Perbaikan: selector mengikuti mode tersimpan, bukan hanya status accessibility saat ini.

### 12. Shake minimize pada device tanpa accelerometer

Sebelumnya sensor null dapat diteruskan ke `registerListener`.

Perbaikan: fitur berhenti aman jika sensor tidak tersedia dan mencegah registrasi ganda.

### 13. Restart task kehilangan user dan component

Saat task keluar ke display utama dan dibuka kembali, request restart tidak membawa `userId` dan `componentName`. Ini dapat membuka user yang salah atau membuat duplicate detection tidak bekerja.

Perbaikan: restart sekarang meneruskan `EXTRA_USER`, `EXTRA_COMPONENT_NAME`, dan intent asli.

### 14. API launch menerima target invalid

API baru memvalidasi package/activity melalui PackageManager sebelum membuat virtual display. Request invalid ditolak tanpa membuat window rusak.

## Fitur yang masih dibatasi

Fitur berikut tetap dibatasi karena sangat bergantung pada versi launcher/OEM dan tidak dapat dijamin hanya melalui source static:

- Hook taskbar OEM dan hook long press ikon launcher pada semua launcher.
- Hook Home/Assistant untuk mengambil current foreground task tanpa mengganti Assistant global.
- Penggantian Assistant App secara otomatis.
- Mode SurfaceView/IME khusus sebelum diuji pada Android/OEM target.

Jalur Xposed/LSPosed yang dimasukkan berada di `app/src/main/java/com/sunshine/freeform/hook/`, terdaftar melalui `assets/xposed_init`, dan memakai binder `user.mifreeform`. Jika hook tidak aktif atau gagal karena ROM, adapter otomatis turun ke mode fullscreen normal.
