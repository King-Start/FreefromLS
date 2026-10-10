package com.sunshine.freeform.service

import android.app.AppOpsManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import com.sunshine.freeform.app.MiFreeform
import com.sunshine.freeform.utils.AppLauncher
import java.util.concurrent.Executors

/**
 * Bilah taskbar overlay (terinspirasi farmerbb/Taskbar, Apache-2.0): tombol start menu,
 * aplikasi terbaru, dan jam. Aplikasi dibuka lewat [AppLauncher] sehingga mengikuti backend
 * yang dipilih di pengaturan.
 */
class TaskbarService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private data class RecentEntry(val pkg: String, val activity: String?)
    private data class AppItem(val label: String, val icon: Drawable?, val pkg: String, val activity: String)

    private lateinit var windowManager: WindowManager
    private lateinit var sp: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var barView: View? = null
    private var recentsRow: LinearLayout? = null
    private var menuView: View? = null
    private var menuAdapter: AppGridAdapter? = null
    private var appItems: List<AppItem> = emptyList()
    private var lastRecentKey = ""
    private var destroyed = false
    private var collapsed = false
    private var tabView: View? = null

    private val refreshTask = object : Runnable {
        override fun run() {
            refreshRecents()
            handler.postDelayed(this, 3000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        sp = getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        sp.registerOnSharedPreferenceChangeListener(this)
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        collapsed = getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_COLLAPSED, false)
        if (collapsed) showTab() else showBar()
        loadApps()
        handler.post(refreshTask)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        runCatching { sp.unregisterOnSharedPreferenceChangeListener(this) }
        hideMenu()
        removeBar()
        removeTab()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == KEY_POSITION || key == KEY_MAX_RECENTS || key == KEY_HIDE_SIDE) {
            handler.post {
                if (destroyed) return@post
                hideMenu()
                removeBar()
                removeTab()
                if (collapsed) showTab() else showBar()
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ===== BILAH =====
    private fun showBar() {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xEB1F2328.toInt())
            setPadding(dp(6), 0, dp(12), 0)
        }
        val start = TextView(this).apply {
            text = "\u229E"
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(BAR_HEIGHT_DP), dp(BAR_HEIGHT_DP))
            setOnClickListener { toggleMenu() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, dp(BAR_HEIGHT_DP), 1f)
            addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val clock = TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "h:mm a"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
        }
        val hideLeft = sp.getInt(KEY_HIDE_SIDE, 0) == 1
        val hide = TextView(this).apply {
            text = if (hideLeft) "\u2039" else "\u203A"
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(BAR_HEIGHT_DP))
            setOnClickListener { collapse() }
        }
        bar.addView(start)
        bar.addView(scroll)
        bar.addView(clock)
        bar.addView(hide)

        val top = sp.getInt(KEY_POSITION, 0) == 1
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(BAR_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = (if (top) Gravity.TOP else Gravity.BOTTOM) or Gravity.START
        }
        runCatching { windowManager.addView(bar, params) }.onFailure {
            stopSelf()
            return
        }
        barView = bar
        recentsRow = row
        lastRecentKey = ""
    }

    private fun removeBar() {
        barView?.let { runCatching { windowManager.removeView(it) } }
        barView = null
        recentsRow = null
        lastRecentKey = ""
    }

    // ===== SEMBUNYI KE TEPI LAYAR =====
    private fun collapse() {
        if (destroyed) return
        collapsed = true
        getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_COLLAPSED, true).apply()
        hideMenu()
        removeBar()
        showTab()
    }

    private fun expand() {
        if (destroyed) return
        collapsed = false
        getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_COLLAPSED, false).apply()
        removeTab()
        showBar()
        refreshRecents()
    }

    private fun showTab() {
        removeTab()
        val left = sp.getInt(KEY_HIDE_SIDE, 0) == 1
        val r = dp(14).toFloat()
        val tab = TextView(this).apply {
            text = if (left) "\u203A" else "\u2039"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xD91F2328.toInt())
                cornerRadii = if (left) {
                    floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                } else {
                    floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                }
            }
        }
        val screenH = resources.displayMetrics.heightPixels
        val tabH = dp(88)
        val state = getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        val savedY = state.getInt(KEY_TAB_Y, -1)
        val params = WindowManager.LayoutParams(
            dp(24),
            tabH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = (if (left) Gravity.START else Gravity.END) or Gravity.TOP
            x = 0
            y = if (savedY >= 0) savedY.coerceIn(0, screenH - tabH) else (screenH - tabH) / 2
        }
        var downY = 0f
        var startY = 0
        var moved = false
        tab.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downY
                    if (kotlin.math.abs(dy) > dp(8)) moved = true
                    if (moved) {
                        params.y = (startY + dy).toInt().coerceIn(0, screenH - tabH)
                        runCatching { windowManager.updateViewLayout(v, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        state.edit().putInt(KEY_TAB_Y, params.y).apply()
                    } else {
                        expand()
                    }
                    true
                }
                else -> false
            }
        }
        runCatching { windowManager.addView(tab, params) }.onSuccess { tabView = tab }
    }

    private fun removeTab() {
        tabView?.let { runCatching { windowManager.removeView(it) } }
        tabView = null
    }

    // ===== START MENU =====
    private fun toggleMenu() {
        if (menuView != null) hideMenu() else showMenu()
    }

    private fun showMenu() {
        val metrics = resources.displayMetrics
        val width = minOf(dp(340), metrics.widthPixels - dp(16))
        val height = minOf(dp(460), metrics.heightPixels - dp(BAR_HEIGHT_DP) - dp(24))
        val adapter = AppGridAdapter()
        adapter.submit(appItems)
        val grid = GridView(this).apply {
            numColumns = 4
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(8)
            setPadding(dp(8), dp(12), dp(8), dp(8))
            clipToPadding = false
            setSelector(android.R.color.transparent)
            this.adapter = adapter
            setOnItemClickListener { _, _, position, _ ->
                val item = adapter.getItem(position)
                launch(item.pkg, item.activity)
            }
        }
        val panel = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xF2202327.toInt())
            }
            addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    hideMenu()
                    true
                } else {
                    false
                }
            }
        }
        val top = sp.getInt(KEY_POSITION, 0) == 1
        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = (if (top) Gravity.TOP else Gravity.BOTTOM) or Gravity.START
            x = dp(8)
            y = dp(BAR_HEIGHT_DP) + dp(6)
        }
        runCatching { windowManager.addView(panel, params) }.onSuccess {
            menuView = panel
            menuAdapter = adapter
        }
    }

    private fun hideMenu() {
        menuView?.let { runCatching { windowManager.removeView(it) } }
        menuView = null
        menuAdapter = null
    }

    private inner class AppGridAdapter : BaseAdapter() {
        private var items: List<AppItem> = emptyList()

        fun submit(list: List<AppItem>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): AppItem = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = (convertView as? LinearLayout) ?: LinearLayout(this@TaskbarService).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(2), dp(4), dp(2), dp(4))
                addView(ImageView(this@TaskbarService).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                })
                addView(TextView(this@TaskbarService).apply {
                    textSize = 11f
                    setTextColor(0xFFFFFFFF.toInt())
                    maxLines = 2
                    gravity = Gravity.CENTER
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
            val item = items[position]
            (cell.getChildAt(0) as ImageView).setImageDrawable(item.icon)
            (cell.getChildAt(1) as TextView).text = item.label
            return cell
        }
    }

    private fun loadApps() {
        runCatching {
            executor.execute {
                val pm = packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val list = pm.queryIntentActivities(intent, 0)
                    .filter { it.activityInfo.packageName != packageName }
                    .map {
                        AppItem(
                            it.loadLabel(pm).toString(),
                            runCatching { it.loadIcon(pm) }.getOrNull(),
                            it.activityInfo.packageName,
                            it.activityInfo.name
                        )
                    }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                handler.post {
                    if (!destroyed) {
                        appItems = list
                        menuAdapter?.submit(list)
                    }
                }
            }
        }
    }

    // ===== APLIKASI TERBARU =====
    private fun refreshRecents() {
        val row = recentsRow ?: return
        val max = sp.getInt(KEY_MAX_RECENTS, 6).coerceIn(3, 10)
        runCatching {
            executor.execute {
                val entries = collectRecents(max)
                val key = entries.joinToString("|") { it.pkg + "/" + (it.activity ?: "") }
                if (key == lastRecentKey) return@execute
                val pm = packageManager
                val icons = entries.map { runCatching { pm.getApplicationIcon(it.pkg) }.getOrNull() }
                handler.post {
                    if (destroyed || recentsRow !== row) return@post
                    lastRecentKey = key
                    row.removeAllViews()
                    entries.forEachIndexed { index, entry ->
                        val icon = icons[index] ?: return@forEachIndexed
                        row.addView(ImageView(this).apply {
                            setImageDrawable(icon)
                            setPadding(dp(8), dp(8), dp(8), dp(8))
                            layoutParams = LinearLayout.LayoutParams(dp(BAR_HEIGHT_DP), dp(BAR_HEIGHT_DP))
                            setOnClickListener { launch(entry.pkg, entry.activity) }
                        })
                    }
                }
            }
        }
    }

    private fun collectRecents(max: Int): List<RecentEntry> {
        val result = LinkedHashMap<String, RecentEntry>()
        val exclude = hashSetOf(packageName, "com.android.systemui")
        homePackage()?.let { exclude.add(it) }

        if (hasUsageAccess()) {
            runCatching {
                val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val end = System.currentTimeMillis()
                val events = usm.queryEvents(end - 24L * 60L * 60L * 1000L, end)
                val event = UsageEvents.Event()
                val ordered = ArrayList<String>()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    @Suppress("DEPRECATION")
                    if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        ordered.remove(event.packageName)
                        ordered.add(event.packageName)
                    }
                }
                ordered.reversed().forEach { pkg ->
                    if (pkg !in exclude && packageManager.getLaunchIntentForPackage(pkg) != null) {
                        result.putIfAbsent(pkg, RecentEntry(pkg, null))
                    }
                }
            }
        }

        val own = getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).getString(KEY_RECENT_LIST, "") ?: ""
        own.split("\n").filter { it.isNotBlank() }.forEach {
            val pkg = it.substringBefore("/")
            val activity = it.substringAfter("/", "").ifEmpty { null }
            if (pkg !in exclude && packageManager.getLaunchIntentForPackage(pkg) != null) {
                result.putIfAbsent(pkg, RecentEntry(pkg, activity))
            }
        }
        return result.values.take(max)
    }

    private fun homePackage(): String? = packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
        PackageManager.MATCH_DEFAULT_ONLY
    )?.activityInfo?.packageName

    @Suppress("DEPRECATION")
    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun launch(pkg: String, activity: String?) {
        val target = activity
            ?: packageManager.getLaunchIntentForPackage(pkg)?.component?.className
            ?: return
        AppLauncher.launch(this, pkg, target, 0)
        recordLaunch(this, pkg, target)
        hideMenu()
        if (sp.getBoolean(KEY_AUTO_HIDE, true)) collapse()
        handler.postDelayed({
            lastRecentKey = ""
            refreshRecents()
        }, 800L)
    }

    companion object {
        const val KEY_ENABLED = "enable_taskbar"
        const val KEY_POSITION = "taskbar_position"
        const val KEY_MAX_RECENTS = "taskbar_max_recents"
        const val KEY_AUTO_HIDE = "taskbar_auto_hide"
        const val KEY_HIDE_SIDE = "taskbar_hide_side"
        private const val BAR_HEIGHT_DP = 48
        private const val STATE_PREFS = "taskbar_state"
        private const val KEY_RECENT_LIST = "recent_list"
        private const val KEY_COLLAPSED = "collapsed"
        private const val KEY_TAB_Y = "tab_y"

        fun setEnabled(context: Context, enabled: Boolean) {
            val intent = Intent(context, TaskbarService::class.java)
            if (enabled && Settings.canDrawOverlays(context)) {
                context.startService(intent)
            } else {
                context.stopService(intent)
            }
        }

        fun sync(context: Context) {
            val sp = context.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
            setEnabled(context, sp.getBoolean(KEY_ENABLED, false))
        }

        fun recordLaunch(context: Context, packageName: String, activityName: String?) {
            val prefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            val entry = packageName + "/" + (activityName ?: "")
            val list = (prefs.getString(KEY_RECENT_LIST, "") ?: "")
                .split("\n")
                .filter { it.isNotBlank() && !it.startsWith("$packageName/") }
                .toMutableList()
            list.add(0, entry)
            prefs.edit().putString(KEY_RECENT_LIST, list.take(20).joinToString("\n")).apply()
        }
    }
}
