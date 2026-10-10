package com.sunshine.freeform.ui.freeform

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.*
import android.view.animation.*
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.animation.addListener
import com.sunshine.freeform.R
import com.sunshine.freeform.app.MiFreeform
import com.sunshine.freeform.databinding.ViewFreeformFlymeBinding
import com.sunshine.freeform.utils.ServiceUtils.windowManager
import com.sunshine.freeform.utils.ServiceUtils.displayManager
import com.sunshine.freeform.utils.ServiceUtils.activityTaskManager
import com.sunshine.freeform.utils.ServiceUtils.inputManager
import com.sunshine.freeform.utils.ServiceUtils.iWindowManager
import kotlinx.android.synthetic.main.view_bar.view.*
import kotlinx.android.synthetic.main.view_bar_flyme.view.*
import kotlinx.android.synthetic.main.view_floating_button.view.*
import kotlinx.android.synthetic.main.view_freeform.view.*
import kotlinx.android.synthetic.main.view_freeform.view.root
import kotlinx.android.synthetic.main.view_freeform_flyme.view.*
import kotlinx.coroutines.*
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class FreeformView(
    override var config: FreeformConfig,
    private val context: Context,
    private var virtualDisplay: VirtualDisplay,
    var screenListener: ScreenListener,
) : FreeformViewAbs(config), View.OnTouchListener, ScreenListener.ScreenStateListener {

    // Getter publik untuk akses displayId dari luar tanpa expose virtualDisplay langsung
    val displayId: Int
        get() = virtualDisplay.display.displayId

    //ViewModel
    private val viewModel = FreeformViewModel(context)

    private val scope = MainScope()

    //默认屏幕，用于获取横竖屏状态
    private val defaultDisplay: Display = requireNotNull(
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)
    ) { "Default display is unavailable" }

    //界面binding
    private lateinit var binding: ViewFreeformFlymeBinding

    private lateinit var backgroundView: View

    //该小窗是否已经销毁
    var isDestroy = false

    //是否处于隐藏状态，当打开米窗的正在运行小窗界面时，应当隐藏所有小窗
    var isHidden = false

    //小窗中应用的taskId
    private var taskList = ArrayList<Int>()

    //叠加层Params
    private val windowLayoutParams = WindowManager.LayoutParams()

    private val backgroundViewLayoutParams = WindowManager.LayoutParams()

    //物理屏幕方向
    private var screenRotation = defaultDisplay.rotation
    //虚拟屏幕方向，1 竖屏， 0 横屏
    private var virtualDisplayRotation = VIRTUAL_DISPLAY_ROTATION_PORTRAIT

    private val iRotationWatcher = object : IRotationWatcher.Stub() {
        override fun onRotationChanged(rotation: Int) {
            if (rotation != screenRotation) {
                screenRotation = rotation
                scope.launch(Dispatchers.Main) {
                    onScreenOrientationChanged()
                }
            }
        }
    }

    //触摸监听
    private val touchListener = TouchListener()
    private val touchListenerPreQ = TouchListenerPreQ()

    //屏幕宽高，不保证大小
    private var realScreenWidth = 0
        get() {
            var tmpWidth = context.resources.displayMetrics.widthPixels
            var tmpHeight = context.resources.displayMetrics.heightPixels

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val rect = windowManager.currentWindowMetrics.bounds
                tmpWidth = rect.width()
                tmpHeight = rect.height()
            }
            return if (screenRotation == Surface.ROTATION_0 || screenRotation == Surface.ROTATION_180)
                        min(tmpWidth, tmpHeight)
                   else
                        max(tmpWidth, tmpHeight)
        }
    private var realScreenHeight = 0
        get() {
            var tmpWidth = context.resources.displayMetrics.widthPixels
            var tmpHeight = context.resources.displayMetrics.heightPixels

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val rect = windowManager.currentWindowMetrics.bounds
                tmpWidth = rect.width()
                tmpHeight = rect.height()
            }

            return if (screenRotation == Surface.ROTATION_0 || screenRotation == Surface.ROTATION_180)
                        max(tmpWidth, tmpHeight)
                   else
                        min(tmpWidth, tmpHeight)
        }

    //小窗的"尺寸"，该尺寸只在小窗内屏幕方向改变时变化
    private var freeformScreenHeight = 0
    private var freeformScreenWidth = 0

    //小窗界面的宽高，该宽高不随着屏幕、小窗方向改变而改变，即h>w恒成立。该尺寸只在物理屏幕方向变化时变化
    private var freeformHeight = 0
    private var freeformWidth = 0

    private var minFreeformHeight = 0
    private var minFreeformWidth = 0

    private var maxFreeformHeight = 0
    private var maxFreeformWidth = 0

    // 挂起后与边缘的 Padding
    private var screenPaddingX: Int = context.resources.getDimension(R.dimen.freeform_screen_width_padding).roundToInt()
    private var screenPaddingY: Int = context.resources.getDimension(R.dimen.freeform_screen_height_padding).roundToInt()

    // Margins
    private var barHeight: Float = context.resources.getDimension(R.dimen.bottom_bar_height_flyme)
    private var freeformShadow: Float = context.resources.getDimension(R.dimen.freeform_shadow)
    private var cardHeightMargin: Float = 0f
        get() {
            return if (FreeformHelper.screenIsPortrait(screenRotation)) (barHeight + freeformShadow) else 0f
        }
    private var cardWidthMargin: Float = 0f
        get() {
            return if (FreeformHelper.screenIsPortrait(screenRotation)) 0f else barHeight
        }

    // 存储上一次的悬浮位置
    private var lastFloatViewLocation: IntArray = intArrayOf(-1, -1)

    // 小窗大小
    private var hangUpViewHeight = 0
    private var hangUpViewWidth = 0

    // root
    private var rootHeight = 0
        get() {
            var tmp = if (FreeformHelper.screenIsPortrait(screenRotation)) realScreenHeight else realScreenWidth
            if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
                tmp = ((rootWidth * config.widthHeightRatio) + cardHeightMargin).roundToInt()
                if (!FreeformHelper.screenIsPortrait(screenRotation)) {
                    tmp = realScreenHeight
                }
            }
            return tmp
        }
    private var rootWidth = 0
        get() {
            var tmp = if (FreeformHelper.screenIsPortrait(screenRotation)) realScreenWidth else realScreenHeight
            if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
                tmp = realScreenWidth
            }
            return tmp
        }

    // 小窗缩放比例
    private var mScaleX = 1f
        set(value) {
            if (value > 1f) return
            field = value
            binding.freeformRoot.scaleX = value
        }
    private var mScaleY = 1f
        set(value) {
            if (value > 1f) return
            field = value
            binding.freeformRoot.scaleY = value
        }

    // 触发互动的比例
    private var goFloatScale = 0.9f
    private var goFullScale = 1.05f

    //缩放比例
    private var scaleX: Float = 1f
    private var scaleY: Float = 1f

    //新增 手动调整小窗方向 q220904.7
    private val middleGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (config.manualAdjustFreeformRotation) {
                virtualDisplayRotation = if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_PORTRAIT) {
                    VIRTUAL_DISPLAY_ROTATION_LANDSCAPE
                } else {
                    VIRTUAL_DISPLAY_ROTATION_PORTRAIT
                }
                onFreeFormRotationChanged()
            } else {
                // Double tap pada bar bawah → suspend/mini mode
                if (enableSuspendMode) toSuspendMode()
            }
            return false
        }
    })

    private val backgroundGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!isFloating) {
                // Hanya close kalau setting tap_outside_to_close aktif
                if (viewModel.getBooleanSp("tap_outside_to_close", false)) {
                    destroy()
                }
            }
            return true
        }
    })

    private val sharedPreferencesChangeListener =
        OnSharedPreferenceChangeListener { sharedPreferences, key ->
            when (key) {
                "freeform_scale" -> {
                    val dpi = sharedPreferences.getInt(key, 50).coerceIn(50, 500)
                    config.freeformDpi = if (dpi > 50) dpi else FreeformHelper.getScreenDpi(context)
                    runCatching { resizeVirtualDisplay() }
                }
                "freeform_size", "freeform_size_land" -> {
                    // A changed default size should take effect on the active
                    // window instead of waiting for the next launch. Do not
                    // let an older remembered size override the new default.
                    val prefs = context.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
                    val currentKey = if (key == "freeform_size") REMEMBER_HEIGHT else REMEMBER_LAND_HEIGHT
                    prefs.edit().remove(currentKey).apply()
                    config.freeformSize = sharedPreferences.getInt("freeform_size", 75).coerceIn(10, 100) / 100f
                    config.freeformSizeLand = sharedPreferences.getInt("freeform_size_land", 90).coerceIn(10, 100) / 100f
                    if (::binding.isInitialized) {
                        refreshFreeformSize()
                        freeformScreenWidth = (freeformWidth - cardWidthMargin).roundToInt().coerceAtLeast(1)
                        freeformScreenHeight = (freeformHeight - cardHeightMargin).roundToInt().coerceAtLeast(1)
                        runCatching { resizeVirtualDisplay() }
                        resetScale()
                    }
                }
                "freeform_dimming_amount" -> {
                    config.dimAmount = sharedPreferences.getInt(key, 20).coerceIn(0, 100) / 100f
                    if (::backgroundView.isInitialized) {
                        backgroundViewLayoutParams.dimAmount = config.dimAmount
                        runCatching { windowManager.updateViewLayout(backgroundView, backgroundViewLayoutParams) }
                    }
                }
                "freeform_float_view_size" -> {
                    config.floatViewSize = (sharedPreferences.getInt(key, 20).coerceIn(10, 50)) / 100.toFloat()
                    initFloatViewSize()
                    if (isFloating) {
                        if (isHidden) {
                            hiddenViewToFloatView(false)
                        }

                        binding.cardRoot.radius = context.resources.getDimension(R.dimen.card_corner_radius) * (hangUpViewWidth / rootWidth)

                        val windowCoordinate = intArrayOf(
                            windowLayoutParams.x,
                            windowLayoutParams.y,
                        )

                        val location = genFloatViewLocation()
                        lastFloatViewLocation[0] = location[0]

                        AnimatorSet().apply {
                            playTogether(
                                ValueAnimator.ofInt(windowLayoutParams.width, hangUpViewWidth)
                                    .apply {
                                        addUpdateListener {
                                            windowManager.updateViewLayout(
                                                binding.root,
                                                windowLayoutParams.apply {
                                                    width = it.animatedValue as Int
                                                })
                                        }
                                    },
                                ValueAnimator.ofInt(windowLayoutParams.height, hangUpViewHeight)
                                    .apply {
                                        addUpdateListener {
                                            windowManager.updateViewLayout(
                                                binding.root,
                                                windowLayoutParams.apply {
                                                    height = it.animatedValue as Int
                                                })
                                        }
                                    },
                                moveViewAnim(windowCoordinate, lastFloatViewLocation)
                            )
                            duration = animationDuration(200L)
                            start()
                        }
                    }
                }
                "window_opacity" -> {
                    windowOpacity = sharedPreferences.getInt(key, 100).coerceIn(20, 100)
                    if (::binding.isInitialized) {
                        binding.freeformRoot.alpha = windowOpacity / 100f
                    }
                }
                "corner_radius" -> {
                    cornerRadiusValue = sharedPreferences.getInt(key, 0).coerceIn(0, 50).toFloat()
                    if (::binding.isInitialized) {
                        if (cornerRadiusValue > 0) {
                            binding.cardRoot.radius = cornerRadiusValue
                        } else {
                            binding.cardRoot.radius = context.resources.getDimension(R.dimen.card_corner_radius)
                        }
                    }
                }
                "lock_window_position" -> {
                    isWindowLocked = sharedPreferences.getBoolean(key, false)
                }
                "auto_close_screen_off" -> {
                    autoCloseScreenOff = sharedPreferences.getBoolean(key, false)
                }
                "auto_minimize_on_call" -> {
                    autoMinimizeOnCall = sharedPreferences.getBoolean(key, false)
                    if (autoMinimizeOnCall) {
                        registerPhoneCallReceiver()
                    } else {
                        unregisterPhoneCallReceiver()
                    }
                }
                "enable_shake_minimize" -> {
                    enableShakeMinimize = sharedPreferences.getBoolean(key, false)
                    if (enableShakeMinimize) {
                        registerShakeListener()
                    } else {
                        unregisterShakeListener()
                    }
                }
                "enable_swipe_back" -> {
                    enableSwipeBack = sharedPreferences.getBoolean(key, true)
                }
                "enable_swipe_home" -> {
                    enableSwipeHome = sharedPreferences.getBoolean(key, false)
                }
                "enable_swipe_forward" -> {
                    enableSwipeForward = sharedPreferences.getBoolean(key, false)
                }
                "remember_freeform_size" -> {
                    rememberFreeformSize = sharedPreferences.getBoolean(key, true)
                }
                "enable_suspend_mode" -> {
                    enableSuspendMode = sharedPreferences.getBoolean(key, true)
                }
                "enable_destroy_anim" -> {
                    enableDestroyAnim = sharedPreferences.getBoolean(key, true)
                }
                "snap_to_edge" -> {
                    // Snap to edge diatur saat move selesai
                }
                "show_top_bar" -> {
                    applyTopBarVisibility()
                }
                "remember_freeform_position" -> {
                    config.rememberPosition = sharedPreferences.getBoolean(key, false)
                }
                "use_sui_refuse_to_fullscreen" -> {
                    config.useSuiRefuseToFullScreen = sharedPreferences.getBoolean(key, false)
                }
                "manual_adjust_freeform_rotation" -> {
                    config.manualAdjustFreeformRotation = sharedPreferences.getBoolean(key, false)
                }
                "float_trigger_ratio", "full_trigger_ratio" -> {
                    if (::binding.isInitialized && rootHeight > 0) refreshActionScale()
                }
                "shake_threshold" -> {
                    shakeThreshold = sharedPreferences.getInt(key, 12).coerceIn(6, 30).toFloat()
                }
                "gesture_edge_width" -> {
                    gestureEdgeDp = sharedPreferences.getInt(key, 60).coerceIn(20, 120).toFloat()
                }
                "gesture_min_distance" -> {
                    gestureMinDistanceDp = sharedPreferences.getInt(key, 100).coerceIn(50, 300).toFloat()
                }
                // Key lain (animation_speed, swipe_back_indicator_alpha, posisi/ukuran yang
                // diingat, dll.) dibaca saat dibutuhkan; JANGAN panggil initConfig() di sini
                // karena akan mereset ukuran jendela aktif setiap kali posisi disimpan.
                else -> Unit
            }
        }

    //是否处于挂起状态
    var isFloating = false
    //挂起位置，0：是否在左，1：是否在上
    private val hangUpPosition = booleanArrayOf(false, true)

    @RequiresApi(Build.VERSION_CODES.Q)
    private val taskStackListener = MTaskStackListener()

    fun initSystemService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setDisplayIdMethod = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activityTaskManager.registerTaskStackListener(taskStackListener)
        }
    }

    fun initConfig() {
        initFloatViewSize()

        config.freeformDpi = FreeformHelper.getScreenDpi(context)
        val tmpDpi = viewModel.getIntSp("freeform_scale", 50).coerceIn(50, 500)
        if (tmpDpi > 50) {
            config.freeformDpi = tmpDpi
        }

        freeformScreenHeight = (min(realScreenHeight, realScreenWidth) / config.widthHeightRatio).roundToInt()
        freeformScreenWidth = (freeformScreenHeight * config.widthHeightRatio).roundToInt()

        config.rememberPosition = viewModel.getBooleanSp("remember_freeform_position", false)
        if (config.rememberPosition) {
            lastFloatViewLocation[0] = if (FreeformHelper.screenIsPortrait(screenRotation)) {
                viewModel.getIntSp(REMEMBER_X, -1)
            } else {
                viewModel.getIntSp(REMEMBER_LAND_X, -1)
            }
            lastFloatViewLocation[1] = if (FreeformHelper.screenIsPortrait(screenRotation)) {
                viewModel.getIntSp(REMEMBER_Y, -1)
            } else {
                viewModel.getIntSp(REMEMBER_LAND_Y, -1)
            }
        }
        config.floatViewSize = viewModel.getIntSp("freeform_float_view_size", 20).coerceIn(10, 50) / 100f
        config.freeformSize = viewModel.getIntSp("freeform_size", 75).coerceIn(10, 100) / 100f
        config.freeformSizeLand = viewModel.getIntSp("freeform_size_land", 90).coerceIn(10, 100) / 100f
        config.dimAmount = viewModel.getIntSp("freeform_dimming_amount", 20).coerceIn(0, 100) / 100f

        viewModel.registerOnSharedPreferenceChangeListener(sharedPreferencesChangeListener)

        config.useSuiRefuseToFullScreen = viewModel.getBooleanSp("use_sui_refuse_to_fullscreen", false)
        config.manualAdjustFreeformRotation = viewModel.getBooleanSp("manual_adjust_freeform_rotation", false)

        // Baca setting baru
        enableSwipeBack = viewModel.getBooleanSp("enable_swipe_back", true)
        enableSuspendMode = viewModel.getBooleanSp("enable_suspend_mode", true)
        enableDestroyAnim = viewModel.getBooleanSp("enable_destroy_anim", true)
        rememberFreeformSize = viewModel.getBooleanSp("remember_freeform_size", true)

        // Gesture tambahan
        enableSwipeHome = viewModel.getBooleanSp("enable_swipe_home", false)
        enableSwipeForward = viewModel.getBooleanSp("enable_swipe_forward", false)
        enableShakeMinimize = viewModel.getBooleanSp("enable_shake_minimize", false)

        // Tampilan
        windowOpacity = viewModel.getIntSp("window_opacity", 100).coerceIn(20, 100)
        cornerRadiusValue = viewModel.getIntSp("corner_radius", 0).coerceIn(0, 50).toFloat()

        // Performa
        autoCloseScreenOff = viewModel.getBooleanSp("auto_close_screen_off", false)
        autoMinimizeOnCall = viewModel.getBooleanSp("auto_minimize_on_call", false)
        isWindowLocked = viewModel.getBooleanSp("lock_window_position", false)

        // Nilai yang sebelumnya hardcode
        shakeThreshold = viewModel.getIntSp("shake_threshold", 12).coerceIn(6, 30).toFloat()
        gestureEdgeDp = viewModel.getIntSp("gesture_edge_width", 60).coerceIn(20, 120).toFloat()
        gestureMinDistanceDp = viewModel.getIntSp("gesture_min_distance", 100).coerceIn(50, 300).toFloat()
    }

    /**
     * Inisialisasi ukuran float view berdasarkan config.floatViewSize
     */
    private fun initFloatViewSize() {
        hangUpViewHeight = (rootHeight * config.floatViewSize).roundToInt()
        hangUpViewWidth = (hangUpViewHeight * config.widthHeightRatio).roundToInt()
        if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
            hangUpViewWidth = (realScreenHeight * config.floatViewSize).roundToInt()
            hangUpViewHeight = (hangUpViewWidth * config.widthHeightRatio).roundToInt()
            if (!FreeformHelper.screenIsPortrait(screenRotation)) {
                hangUpViewWidth = (realScreenWidth * config.floatViewSize).roundToInt()
                hangUpViewHeight = (hangUpViewWidth * config.widthHeightRatio).roundToInt()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun initView() {
        binding = ViewFreeformFlymeBinding.bind(LayoutInflater.from(context).inflate(R.layout.view_freeform_flyme, null, false))

        backgroundView = View(context)
        backgroundView.setBackgroundColor(Color.TRANSPARENT)
        backgroundView.setOnTouchListener(this@FreeformView)
        backgroundView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
                performBackKey()
            }
            true
        }
        backgroundView.id = View.generateViewId()

        binding.root.setOnTouchListener(this)
        binding.bottomBar.middleView.setOnTouchListener(this@FreeformView)
        binding.bottomBar.sideView.setOnTouchListener(this@FreeformView)
        setupCornerResizeHandles()

        val topBarTouchListener = TopBarTouchListener()
        binding.topBar.root.setOnTouchListener(topBarTouchListener)
        binding.topBar.leftView.setOnTouchListener(topBarTouchListener)
        binding.topBar.middleView.setOnTouchListener(topBarTouchListener)
        binding.topBar.rightView.setOnTouchListener(topBarTouchListener)
        applyTopBarVisibility()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            binding.textureView.setOnTouchListener(touchListener)
        } else {
            binding.textureView.setOnTouchListener(touchListenerPreQ)
        }

        if (!FreeformHelper.screenIsPortrait(screenRotation)) {
            hangUpPosition[0] = true
            binding.apply {
                (cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                    topMargin = 0
                    bottomMargin = 0
                    rightMargin = barHeight.roundToInt()
                }
            }
        }

        refreshFreeformSize()

        initFloatBar()

        resetScale()

        // Apply tampilan awal
        binding.freeformRoot.alpha = windowOpacity / 100f
        if (cornerRadiusValue > 0) {
            binding.cardRoot.radius = cornerRadiusValue
        }
        binding.textureView.alpha = 0f
    }

    private fun animationDuration(baseMillis: Long): Long {
        val speed = viewModel.getIntSp("animation_speed", 100).coerceIn(50, 200)
        return (baseMillis * 100L / speed).coerceIn(50L, 2000L)
    }

    private fun applyTopBarVisibility() {
        if (!::binding.isInitialized) return
        val show = viewModel.getBooleanSp("show_top_bar", true)
        binding.topBar.root.visibility = if (show) View.VISIBLE else View.GONE
    }

    private inner class TopBarTouchListener : View.OnTouchListener {
        private var moveStartX = 0f
        private var moveStartY = 0f
        private var isMoved = false
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    moveStartX = event.rawX
                    moveStartY = event.rawY
                    isMoved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - moveStartX
                    val dy = event.rawY - moveStartY
                    if (!isMoved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        isMoved = true
                    }
                    if (isMoved && !isWindowLocked && !isDestroy) {
                        runCatching {
                            windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                                x += dx.toInt()
                                y += dy.toInt()
                            })
                        }
                        moveStartX = event.rawX
                        moveStartY = event.rawY
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isMoved) {
                        if (viewModel.getBooleanSp("snap_to_edge", true)) {
                            snapToEdge()
                        }
                    } else {
                        when (v.id) {
                            R.id.leftView -> performBackKey()
                            R.id.rightView -> destroyWithAnim()
                        }
                    }
                    isMoved = false
                }
                MotionEvent.ACTION_CANCEL -> isMoved = false
            }
            return true
        }
    }

    private fun performBackKey() {
        val downEvent = KeyEvent(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_BACK,
            0
        )
        val upEvent = KeyEvent(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_BACK,
            0
        )

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setDisplayIdMethod?.invoke(downEvent, virtualDisplay.display.displayId)
                inputManager.injectInputEvent(downEvent, 0)

                setDisplayIdMethod?.invoke(upEvent, virtualDisplay.display.displayId)
                inputManager.injectInputEvent(upEvent, 0)
            } else {
                inputManager.injectInputEvent(downEvent, virtualDisplay.display.displayId)
                inputManager.injectInputEvent(upEvent, virtualDisplay.display.displayId)
            }
        }
    }

    private fun initFloatBar() {
        if (FreeformHelper.screenIsPortrait(screenRotation)) {
            binding.bottomBar.apply {
                root.layoutParams = ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.MATCH_PARENT,
                    barHeight.roundToInt(),
                ).apply {
                    topToBottom = R.id.cardRoot
                    startToEnd = ConstraintLayout.LayoutParams.UNSET
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                }
                middleView.visibility = View.VISIBLE
                sideView.visibility = View.GONE
            }
        } else {
            binding.bottomBar.apply {
                root.layoutParams = ConstraintLayout.LayoutParams(
                    barHeight.roundToInt(),
                    ConstraintLayout.LayoutParams.MATCH_PARENT,
                ).apply {
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    startToEnd = R.id.cardRoot
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                }
                middleView.visibility = View.GONE
                sideView.visibility = View.VISIBLE
            }
        }
    }

    private fun initDisplay() {
        virtualDisplay.resize(freeformScreenWidth, freeformScreenHeight, config.freeformDpi)
        screenListener.addScreenStateListener(this@FreeformView)
    }

    override fun onScreenOn() {
    }

    override fun onScreenOff() {
        if (autoCloseScreenOff) {
            scope.launch(Dispatchers.Main) { destroyWithAnim() }
            return
        }
        if (!isHidden) {
            windowLayoutParams.flags =
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            windowManager.updateViewLayout(binding.root, windowLayoutParams)
        }
    }

    override fun onUserPresent() {
        if (!isHidden) {
            windowLayoutParams.flags =
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM

            windowManager.updateViewLayout(binding.root, windowLayoutParams)
        }
    }

    private fun initOrientationChangedListener() {
        iWindowManager.watchRotation(iRotationWatcher, Display.DEFAULT_DISPLAY)
    }

    private fun initTextureViewListener() {
        var updateFrameCount = 0
        var initFinish = false

        binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                surface.setDefaultBufferSize(freeformScreenWidth, freeformScreenHeight)
                virtualDisplay.surface = Surface(surface)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                surface.setDefaultBufferSize(freeformScreenWidth, freeformScreenHeight)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                if (!initFinish) {
                    ++updateFrameCount
                    if (updateFrameCount > 2) {
                        binding.lottieView.cancelAnimation()
                        binding.lottieView.animate().alpha(0f).setDuration(animationDuration(200L)).start()
                        binding.textureView.animate().alpha(1f).setDuration(animationDuration(200L)).start()
                        initFinish = true
                    }
                }
            }
        }
    }

    fun showWindow() {
        initDisplay()
        initOrientationChangedListener()
        initTextureViewListener()

        // Apply the saved appearance before the window is displayed.
        binding.freeformRoot.alpha = windowOpacity / 100f

        if (cornerRadiusValue > 0) {
            binding.cardRoot.radius = cornerRadiusValue
        }

        // Setup shake sensor
        if (enableShakeMinimize) {
            registerShakeListener()
        }

        // Setup auto minimize on call
        if (autoMinimizeOnCall) {
            registerPhoneCallReceiver()
        }

        // Baca setting tap outside to close
        val tapOutsideToClose = viewModel.getBooleanSp("tap_outside_to_close", false)

        windowLayoutParams.apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags =
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                        if (tapOutsideToClose) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        else WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                             WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            format = PixelFormat.RGBA_8888
            windowAnimations = android.R.style.Animation_Dialog
        }

        setWindowNoUpdateAnimation()

        windowLayoutParams.apply {
            width = rootWidth
            height = rootHeight
        }

        if (screenRotation == Surface.ROTATION_90 || screenRotation == Surface.ROTATION_270) {
            windowLayoutParams.apply {
                x = genCenterLocation()[0]
                y = genCenterLocation()[1]
            }
        }

        backgroundViewLayoutParams.apply {
            dimAmount = config.dimAmount
            format = PixelFormat.RGBA_8888
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            flags = windowLayoutParams.flags or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }

        runCatching {
            windowManager.addView(backgroundView, backgroundViewLayoutParams)
            windowManager.addView(binding.root, windowLayoutParams)
        }.onFailure {
            runCatching {
                windowManager.removeViewImmediate(backgroundView)
                hideResizePreview()
                windowManager.removeViewImmediate(binding.root)
            }

            if (Settings.canDrawOverlays(context)) {
                windowManager.addView(backgroundView, backgroundViewLayoutParams.apply {
                    type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                })
                windowManager.addView(binding.root, windowLayoutParams.apply {
                    type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                })
            } else {
                destroy()
                runCatching {
                    Toast.makeText(context, context.getString(R.string.request_overlay_permission), Toast.LENGTH_LONG).show()
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }.onFailure {
                    Toast.makeText(context, context.getString(R.string.request_overlay_permission_fail), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setWindowNoUpdateAnimation() {
        val classname = "android.view.WindowManager\$LayoutParams"
        runCatching {
            val layoutParamsClass: Class<*> = Class.forName(classname)
            val privateFlags: Field = layoutParamsClass.getField("privateFlags")
            val noAnim: Field = layoutParamsClass.getField("PRIVATE_FLAG_NO_MOVE_ANIMATION")
            var privateFlagsValue: Int = privateFlags.getInt(windowLayoutParams)
            val noAnimFlag: Int = noAnim.getInt(windowLayoutParams)
            privateFlagsValue = privateFlagsValue or noAnimFlag
            privateFlags.setInt(windowLayoutParams, privateFlagsValue)
        }
    }

    private fun setWindowEnableUpdateAnimation() {
        val classname = "android.view.WindowManager\$LayoutParams"
        runCatching {
            val layoutParamsClass: Class<*> = Class.forName(classname)
            val privateFlags: Field = layoutParamsClass.getField("privateFlags")
            val noAnim: Field = layoutParamsClass.getField("PRIVATE_FLAG_NO_MOVE_ANIMATION")
            var privateFlagsValue: Int = privateFlags.getInt(windowLayoutParams)
            val noAnimFlag: Int = noAnim.getInt(windowLayoutParams)
            privateFlagsValue = privateFlagsValue and noAnimFlag.inv()
            privateFlags.setInt(windowLayoutParams, privateFlagsValue)
        }
    }

    private fun onFreeFormRotationChanged() {
        val tempHeight = max(freeformScreenHeight, freeformScreenWidth)
        val tempWidth = min(freeformScreenHeight, freeformScreenWidth)

        initFloatViewSize()
        if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_PORTRAIT) {
            freeformScreenHeight = tempHeight
            freeformScreenWidth = tempWidth
        } else {
            freeformScreenHeight = tempWidth
            freeformScreenWidth = tempHeight
        }
        refreshFreeformSize()
        resetScale()
        resizeVirtualDisplay()
        windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
            width = rootWidth
            height = rootHeight
            x = genCenterLocation()[0]
            y = genCenterLocation()[1]
        })
    }

    private fun onScreenOrientationChanged() {
        initFloatViewSize()

        refreshFreeformSize()

        // Restore ukuran yang disimpan per orientasi
        if (FreeformHelper.screenIsPortrait(screenRotation)) {
            if (savedWidthPortrait > 0) {
                freeformWidth = savedWidthPortrait
                freeformHeight = savedHeightPortrait
            }
        } else {
            if (savedWidthLandscape > 0) {
                freeformWidth = savedWidthLandscape
                freeformHeight = savedHeightLandscape
            }
        }

        initFloatBar()

        val location = genFloatViewLocation()
        lastFloatViewLocation = location

        refreshTouchScale()
        refreshActionScale()

        if (isFloating && !isHidden) {
            moveFloatViewLocation(location, true)
        } else if (isHidden) {
            moveHiddenViewLocation(location)
        } else {
            windowLayoutParams.apply {
                height = rootHeight
                width = rootWidth
            }
            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                topMargin = freeformShadow.roundToInt()
                bottomMargin = barHeight.roundToInt()
                rightMargin = 0
            }
            windowLayoutParams.apply {
                x = genCenterLocation()[0]
                y = genCenterLocation()[1]
            }
            if(!FreeformHelper.screenIsPortrait(screenRotation)) {
                binding.apply {
                    (cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                        topMargin = 0
                        bottomMargin = 0
                        rightMargin = barHeight.roundToInt()
                    }
                }
            }
            resetScale()
            windowManager.updateViewLayout(binding.root, windowLayoutParams)
        }
    }

    private fun genCenterLocation(): IntArray {
        val center = intArrayOf(0, 0)
        if (!FreeformHelper.screenIsPortrait(screenRotation)) {
            center[0] = (freeformWidth - rootHeight + screenPaddingX) / 2
            if (!hangUpPosition[0])
                center[0] = (freeformWidth - rootHeight + screenPaddingX) / -2
            if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
                center[0] = (freeformWidth - realScreenWidth + screenPaddingX) / 2
                if (!hangUpPosition[0])
                    center[0] = (freeformWidth - realScreenWidth + screenPaddingX) / -2
            }
        }
        return center
    }

    private fun resizeVirtualDisplay() {
        // Buffer SurfaceTexture harus ikut diubah; jika tidak, isi virtual display yang lebih kecil
        // hanya menempati pojok kiri-atas buffer lama (isi aplikasi tampak menciut di dalam kartu).
        binding.textureView.surfaceTexture?.setDefaultBufferSize(freeformScreenWidth, freeformScreenHeight)
        virtualDisplay.resize(
            freeformScreenWidth,
            freeformScreenHeight,
            config.freeformDpi
        )
    }

    override fun toScreenCenter() {
        if (isFloating) return
        windowLayoutParams.x = 0
        windowLayoutParams.y = 0
    }

    override fun moveToFirst() {
        if (isFloating) {
            if (isHidden) {
                hiddenViewToFloatView(true)
            } else {
                floatViewToMiniView()
            }
        }
    }

    private fun rememberedSizeHeight(): Int {
        val key = if (FreeformHelper.screenIsPortrait(screenRotation)) REMEMBER_HEIGHT else REMEMBER_LAND_HEIGHT
        return context.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
            .getInt(key, -1)
    }

    private fun rememberCurrentSize() {
        if (!rememberFreeformSize || freeformHeight <= 0) return
        val key = if (FreeformHelper.screenIsPortrait(screenRotation)) REMEMBER_HEIGHT else REMEMBER_LAND_HEIGHT
        context.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(key, freeformHeight).apply()
    }

    private fun refreshFreeformSize() {
        freeformHeight = if (FreeformHelper.screenIsPortrait(screenRotation)) (rootWidth / config.widthHeightRatio * config.freeformSize).roundToInt() else (rootWidth * config.freeformSizeLand).roundToInt()
        freeformHeight += cardHeightMargin.roundToInt()
        freeformWidth = ((freeformHeight + cardWidthMargin) * config.widthHeightRatio).roundToInt()
        if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
            if (freeformHeight > rootWidth) {
                freeformWidth = (rootWidth - (rootWidth * 0.05)).roundToInt()
                freeformHeight = ((freeformWidth + cardHeightMargin) * config.widthHeightRatio) .roundToInt()
            }
            if (!FreeformHelper.screenIsPortrait(screenRotation)) {
                freeformWidth = (realScreenWidth / 2 + cardWidthMargin).roundToInt()
                freeformHeight = (freeformWidth * config.widthHeightRatio).roundToInt()
            }
        }

        // Restore the last resized dimensions across freeform service restarts.
        if (rememberFreeformSize) {
            val rememberedHeight = rememberedSizeHeight()
            if (rememberedHeight > 0) {
                val minHeight = (rootHeight * 0.6f).roundToInt()
                val maxHeight = (rootHeight * 0.95f).roundToInt()
                freeformHeight = rememberedHeight.coerceIn(minHeight, maxHeight)
                freeformWidth = ((freeformHeight + cardWidthMargin) * config.widthHeightRatio).roundToInt()
            }
        }

        minFreeformHeight = (freeformHeight * 0.6f).roundToInt()
        minFreeformWidth = (freeformWidth * 0.6f).roundToInt()
        maxFreeformHeight = (rootHeight * 0.95f).roundToInt()
        maxFreeformWidth = (rootWidth * 0.95f).roundToInt()
    }

    private fun refreshScale() {
        mScaleX = freeformWidth / rootWidth.toFloat()
        mScaleY = freeformHeight / rootHeight.toFloat()
    }

    private fun refreshTouchScale() {
        scaleX = (rootWidth - cardWidthMargin) / freeformScreenWidth.toFloat()
        scaleY = (rootHeight - cardHeightMargin) / freeformScreenHeight.toFloat()
    }

    private fun refreshActionScale() {
        goFloatScale = (freeformHeight * (viewModel.getIntSp("float_trigger_ratio", 90).coerceIn(50, 95) / 100f)) / rootHeight
        goFullScale = (freeformHeight * (viewModel.getIntSp("full_trigger_ratio", 105).coerceIn(100, 130) / 100f)) / rootHeight
    }

    private fun resetScale() {
        refreshTouchScale()
        refreshScale()
        refreshActionScale()
    }

    private var lastX = -1f
    private var lastY = -1f
    private var touchId = -1

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> handleDownEvent(v, event)
            MotionEvent.ACTION_MOVE -> handleMoveEvent(v, event)
            MotionEvent.ACTION_UP -> handleUpEvent(v, event)
        }
        return true
    }

    private fun handleDownEvent(v: View, event: MotionEvent) {
        if (touchId == -1) touchId = v.id
        lastX = event.rawX
        lastY = event.rawY
        when(v.id) {
            R.id.root, backgroundView.id -> backgroundGestureDetector.onTouchEvent(event)
            R.id.middleView -> middleGestureDetector.onTouchEvent(event)
            R.id.sideView -> middleGestureDetector.onTouchEvent(event)
        }
    }

    private fun handleMoveEvent(v: View, event: MotionEvent) {
        when(v.id) {
            R.id.root, backgroundView.id -> backgroundGestureDetector.onTouchEvent(event)
            R.id.middleView -> {
                if (touchId == R.id.middleView) {
                    val dy = event.rawY - lastY
                    handleToFloatScale(0f, dy)
                    lastX = event.rawX
                    lastY = event.rawY
                    middleGestureDetector.onTouchEvent(event)
                }
            }
            R.id.sideView -> {
                if (touchId == R.id.sideView) {
                    val dx = event.rawX - lastX
                    handleToFloatScale(dx, 0f)
                    lastX = event.rawX
                    lastY = event.rawY
                }
            }
        }
    }

    private fun handleUpEvent(v: View, event: MotionEvent) {
        when (v.id) {
            R.id.root, backgroundView.id -> backgroundGestureDetector.onTouchEvent(event)
            R.id.middleView -> {
                middleGestureDetector.onTouchEvent(event)
                notifyToFloat()
                if (isZoomOut) {
                    // Update virtualDisplay sesuai ukuran baru
                    freeformScreenWidth = (freeformWidth - cardWidthMargin).roundToInt()
                    freeformScreenHeight = (freeformHeight - cardHeightMargin).roundToInt()
                    resizeVirtualDisplay()
                    scaleX = (rootWidth - cardWidthMargin) / freeformScreenWidth.toFloat()
                    scaleY = (rootHeight - cardHeightMargin) / freeformScreenHeight.toFloat()
                    // Simpan ukuran untuk remember size
                    if (rememberFreeformSize) {
                        if (FreeformHelper.screenIsPortrait(screenRotation)) {
                            savedWidthPortrait = freeformWidth
                            savedHeightPortrait = freeformHeight
                        } else {
                            savedWidthLandscape = freeformWidth
                            savedHeightLandscape = freeformHeight
                        }
                        rememberCurrentSize()
                    }
                    isZoomOut = false
                }
            }
            R.id.sideView -> {
                notifyToFloat()
                middleGestureDetector.onTouchEvent(event)
                if (isZoomOut) {
                    freeformScreenWidth = (freeformWidth - cardWidthMargin).roundToInt()
                    freeformScreenHeight = (freeformHeight - cardHeightMargin).roundToInt()
                    resizeVirtualDisplay()
                    scaleX = (rootWidth - cardWidthMargin) / freeformScreenWidth.toFloat()
                    scaleY = (rootHeight - cardHeightMargin) / freeformScreenHeight.toFloat()
                    if (rememberFreeformSize) {
                        if (FreeformHelper.screenIsPortrait(screenRotation)) {
                            savedWidthPortrait = freeformWidth
                            savedHeightPortrait = freeformHeight
                        } else {
                            savedWidthLandscape = freeformWidth
                            savedHeightLandscape = freeformHeight
                        }
                        rememberCurrentSize()
                    }
                    isZoomOut = false
                }
            }
        }
        touchId = -1
    }

    private var setDisplayIdMethod: Method? = null

    private fun genFloatViewLocation(): IntArray {
        return intArrayOf(
            (if (hangUpPosition[0]) ((realScreenWidth - hangUpViewWidth - screenPaddingX) / -2)
                else (realScreenWidth - hangUpViewWidth - screenPaddingX) / 2),
            (if (hangUpPosition[1]) (hangUpViewHeight - realScreenHeight + screenPaddingY) / 2
                else (realScreenHeight - hangUpViewHeight - screenPaddingY) / 2),
        )
    }

    private fun getRestoreFreeformScale(): FloatArray {
        refreshFreeformSize()
        return floatArrayOf(
            freeformWidth / rootWidth.toFloat(),
            freeformHeight / rootHeight.toFloat(),
        )
    }

    private fun cardViewMarginAnim(topStartMargin: Int, bottomStartMargin: Int, rightStartMargin: Int, topEndMargin: Int, bottomEndMargin: Int, rightEndMargin: Int): Animator {
        return AnimatorSet().apply {
            playTogether(
                ValueAnimator.ofInt(topStartMargin, topEndMargin).apply {
                    addUpdateListener {
                        binding.cardRoot.layoutParams = (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                            topMargin = it.animatedValue as Int
                        }
                    }
                },
                ValueAnimator.ofInt(bottomStartMargin, bottomEndMargin).apply {
                    addUpdateListener {
                        binding.cardRoot.layoutParams = (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                            bottomMargin = it.animatedValue as Int
                        }
                    }
                },
                ValueAnimator.ofInt(rightStartMargin, rightEndMargin).apply {
                    addUpdateListener {
                        binding.cardRoot.layoutParams = (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).apply {
                            rightMargin = it.animatedValue as Int
                        }
                    }
                },
            )
        }
    }

    private fun moveViewAnim(startCoordinate: IntArray, endCoordinate: IntArray): Animator {
        val moveAnim = AnimatorSet()
        if (endCoordinate[0] != -1) {
            moveAnim.play(
                ValueAnimator.ofInt(startCoordinate[0], endCoordinate[0]).apply {
                    addUpdateListener {
                        windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                            x = it.animatedValue as Int
                        })
                    }
                },
            )
        }
        if (endCoordinate[1] != -1) {
            moveAnim.play(
                ValueAnimator.ofInt(startCoordinate[1], endCoordinate[1]).apply {
                    addUpdateListener {
                        windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                            y = it.animatedValue as Int
                        })
                    }
                },
            )
        }
        return moveAnim
    }

    private var isZoomOut = false

    private fun handleToFloatScale(dx: Float, dy: Float) {
        if (isFloating) return

        if (dy != 0f) {
            val tempHeight = freeformHeight + dy
            val tempWidth = (tempHeight * config.widthHeightRatio).roundToInt()
            if (tempHeight >= minFreeformHeight && tempWidth <= maxFreeformWidth) {
                freeformHeight += dy.roundToInt()
                freeformWidth = (freeformHeight * config.widthHeightRatio).roundToInt()
                if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
                    freeformWidth = ((freeformHeight - (cardHeightMargin * config.widthHeightRatio)) / config.widthHeightRatio).roundToInt()
                }
                mScaleX = freeformWidth / rootWidth.toFloat()
                mScaleY = freeformHeight / rootHeight.toFloat()
                isZoomOut = true
            }
        } else if (dx != 0f) {
            val tempWidth = freeformWidth + dx
            val tempHeight = (tempWidth / config.widthHeightRatio).roundToInt()
            if (tempWidth >= minFreeformWidth && tempHeight <= maxFreeformHeight) {
                freeformWidth += dx.roundToInt()
                freeformHeight = ((freeformWidth / config.widthHeightRatio) - cardWidthMargin).roundToInt()
                if (virtualDisplayRotation == VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) {
                    freeformHeight = ((freeformWidth + cardHeightMargin) * config.widthHeightRatio).roundToInt()
                }
                mScaleX = freeformWidth / rootWidth.toFloat()
                mScaleY = freeformHeight / rootHeight.toFloat()
                isZoomOut = true
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun notifyToFloat() {
        if (isZoomOut) {
            val scaleX: Float = hangUpViewWidth / rootWidth.toFloat()
            val scaleY: Float = hangUpViewHeight / rootHeight.toFloat()

            if (mScaleY <= goFloatScale) {
                val windowCoordinate = intArrayOf(windowLayoutParams.x, windowLayoutParams.y)
                var location = genFloatViewLocation()
                if (lastFloatViewLocation[0] != -1) location = lastFloatViewLocation

                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_X, mScaleX, scaleX),
                        ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_Y, mScaleY, scaleY),
                        ObjectAnimator.ofFloat(binding.bottomBar.root, View.ALPHA, 0f),
                        cardViewMarginAnim(
                            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).topMargin,
                            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).bottomMargin,
                            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).rightMargin,
                            0, 0, 0,
                        ),
                        moveViewAnim(windowCoordinate, location),
                    )
                    addListener(
                        onStart = {
                            AnimatorSet().apply {
                                playTogether(
                                    ValueAnimator.ofFloat(config.dimAmount, 0f).apply {
                                        addUpdateListener {
                                            windowManager.updateViewLayout(backgroundView, backgroundViewLayoutParams.apply {
                                                dimAmount = it.animatedValue as Float
                                            })
                                        }
                                    },
                                )
                                startDelay = 125
                                duration = animationDuration(600L)
                                addListener(
                                    onStart = {
                                        backgroundView.visibility = View.GONE
                                        binding.textureView.setOnTouchListener(null)
                                        isFloating = true
                                    },
                                    onEnd = {
                                        binding.textureView.setOnTouchListener(FloatViewTouchListener())
                                        setWindowEnableUpdateAnimation()
                                    },
                                )
                                start()
                            }
                        },
                        onEnd = {
                            mScaleX = scaleX
                            mScaleY = scaleY
                            binding.cardRoot.radius = context.resources.getDimension(R.dimen.card_corner_radius) * scaleX
                            windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                                height = (rootHeight * scaleY).roundToInt()
                                width = (rootWidth * scaleX).roundToInt()
                            })
                            binding.freeformRoot.scaleY = 1f
                            binding.freeformRoot.scaleX = 1f
                        }
                    )
                    duration = animationDuration(400L)
                    start()
                }
            } else if (mScaleY >= goFullScale) {
                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_X, mScaleX, 1f),
                        ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_Y, mScaleY, 1f),
                        ObjectAnimator.ofFloat(binding.bottomBar.root, View.ALPHA, 0f),
                        cardViewMarginAnim(
                            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).topMargin,
                            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).bottomMargin,
                            (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).rightMargin,
                            0, 0, 0,
                        ),
                    )
                    addListener(
                        onEnd = {
                            context.startService(
                                Intent(context, FreeformService::class.java)
                                    .setAction(FreeformService.ACTION_CALL_INTENT)
                                    .putExtra(Intent.EXTRA_INTENT, config.intent)
                                    .putExtra(FreeformService.EXTRA_DISPLAY_ID, defaultDisplay.displayId)
                            )
                            destroy()
                        }
                    )
                    duration = animationDuration(400L)
                    start()
                }
            } else {
                val restoreScale = getRestoreFreeformScale()
                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_X, mScaleX, restoreScale[0]),
                        ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_Y, mScaleY, restoreScale[1]),
                    )
                    duration = animationDuration(300L)
                    interpolator = OvershootInterpolator(1.5f)
                    start()
                }
            }
            isZoomOut = false
        }
    }

    private fun moveFloatViewLocation(location: IntArray, reset: Boolean) {
        val windowCoordinate = intArrayOf(windowLayoutParams.x, windowLayoutParams.y)
        AnimatorSet().apply {
            playTogether(moveViewAnim(windowCoordinate, location))
            addListener(
                onStart = {
                    if (reset) {
                        binding.freeformRoot.scaleY = 1f
                        binding.freeformRoot.scaleX = 1f
                        windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                            height = hangUpViewHeight
                            width = hangUpViewWidth
                        })
                    }
                }
            )
            duration = animationDuration(600L)
            interpolator = OvershootInterpolator(2f)
            start()
        }
    }

    private fun moveHiddenViewLocation(location: IntArray) {
        val layoutParams = hiddenView.layoutParams as WindowManager.LayoutParams
        val windowCoordinate = intArrayOf(layoutParams.x, layoutParams.y)

        var position = 0
        if (layoutParams.x > 0) {
            location[0] += (hangUpViewWidth + screenPaddingX)
            position = 1
        } else {
            location[0] -= (hangUpViewWidth + screenPaddingX)
            position = -1
        }

        val floatingButtonWidth = context.resources.getDimension(R.dimen.floating_button_width).toInt()

        AnimatorSet().apply {
            playTogether(
                ValueAnimator.ofInt(windowCoordinate[0], (realScreenWidth - floatingButtonWidth) / 2 * position).apply {
                    addUpdateListener {
                        windowManager.updateViewLayout(hiddenView, layoutParams.apply { x = it.animatedValue as Int })
                    }
                },
                ValueAnimator.ofInt(windowCoordinate[1], location[1]).apply {
                    addUpdateListener {
                        windowManager.updateViewLayout(hiddenView, layoutParams.apply { y = it.animatedValue as Int })
                    }
                },
                moveViewAnim(
                    intArrayOf(windowLayoutParams.x, windowLayoutParams.y),
                    intArrayOf(location[0], location[1])
                )
            )
            duration = animationDuration(600L)
            interpolator = OvershootInterpolator(2f)
            start()
        }
    }

    private lateinit var hiddenView: View

    private inner class FloatViewTouchListener : View.OnTouchListener {
        var moveStartX: Float = -1f
        var moveStartY: Float = -1f
        var movedX: Float = -1f
        var movedY: Float = -1f
        var isMoved: Boolean = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            if (v?.id == R.id.root) {
                hideGestureDetector.onTouchEvent(event)
                return true
            }
            when(event.action) {
                MotionEvent.ACTION_DOWN -> {
                    moveStartX = event.rawX
                    moveStartY = event.rawY
                    hangUpGestureDetector.onTouchEvent(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isWindowLocked) {
                        movedX = event.rawX - moveStartX
                        movedY = event.rawY - moveStartY
                        isMoved = true
                        windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                            x += movedX.toInt()
                            y += movedY.toInt()
                        })
                        moveStartX = event.rawX
                        moveStartY = event.rawY
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isMoved) {
                        val nowX = event.rawX
                        val nowY = event.rawY
                        val windowCoordinate = intArrayOf(windowLayoutParams.x, windowLayoutParams.y)
                        // Snap to edge
                        if (viewModel.getBooleanSp("snap_to_edge", true)) {
                            snapToEdge()
                        }

                        if (windowCoordinate[1] >= (realScreenHeight - screenPaddingY) / 2) {
                            destroy()
                            isMoved = false
                            return true
                        }

                        hangUpPosition[0] = windowCoordinate[0] <= 0
                        hangUpPosition[1] = windowCoordinate[1] <= 0

                        val location = genFloatViewLocation()
                        location[1] = windowLayoutParams.y

                        if (nowY < (realScreenHeight * 0.1f)) {
                            location[1] = (hangUpViewHeight - realScreenHeight + screenPaddingY) / 2
                        }
                        if (nowY > (realScreenHeight - (realScreenHeight * 0.1f))) {
                            location[1] = (realScreenHeight - hangUpViewHeight - screenPaddingY) / 2
                        }

                        var position = 0
                        if (windowCoordinate[0] <= (realScreenWidth - (screenPaddingX / 2)) / -2) {
                            location[0] -= (hangUpViewWidth + screenPaddingX)
                            position = -1
                        } else if (windowCoordinate[0] >= (realScreenWidth - (screenPaddingX / 2)) / 2) {
                            location[0] += (hangUpViewWidth + screenPaddingX)
                            position = 1
                        }

                        AnimatorSet().apply {
                            playTogether(moveViewAnim(windowCoordinate, location))
                            addListener(
                                onStart = {
                                    if (position != 0) {
                                        isHidden = true
                                        hiddenView = LayoutInflater.from(context).inflate(R.layout.view_floating_button, null, false)
                                        hiddenView.root.apply { setOnTouchListener(this@FloatViewTouchListener) }
                                        if (position == 1)
                                            hiddenView.backgroundView.background = context.getDrawable(R.drawable.floating_button_bg_right)

                                        val floatingButtonWidth = context.resources.getDimension(R.dimen.floating_button_width).toInt()
                                        val floatingButtonHeight = context.resources.getDimension(R.dimen.floating_button_height).toInt()

                                        windowManager.addView(hiddenView, WindowManager.LayoutParams().apply {
                                            x = (realScreenWidth - floatingButtonWidth) / 2 * position
                                            y = location[1]
                                            width = floatingButtonWidth
                                            height = floatingButtonHeight
                                            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                            format = PixelFormat.TRANSLUCENT
                                            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                                                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                        })
                                    }
                                },
                                onEnd = {
                                    if (!isHidden) lastFloatViewLocation = location
                                    isMoved = false
                                }
                            )
                            duration = animationDuration(400L)
                            interpolator = OvershootInterpolator(2f)
                            start()
                        }
                    } else {
                        hangUpGestureDetector.onTouchEvent(event)
                    }
                }
            }
            return true
        }
    }

    private val hangUpGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        @SuppressLint("ClickableViewAccessibility")
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            floatViewToMiniView()
            return true
        }
        override fun onLongPress(e: MotionEvent) {}
    })

    @SuppressLint("ClickableViewAccessibility")
    private fun floatViewToMiniView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            binding.textureView.setOnTouchListener(touchListener)
        } else {
            binding.textureView.setOnTouchListener(touchListenerPreQ)
        }

        val windowCoordinate = intArrayOf(windowLayoutParams.x, windowLayoutParams.y)
        val restoreScale = getRestoreFreeformScale()
        val center: IntArray = genCenterLocation()

        setWindowNoUpdateAnimation()

        AnimatorSet().apply {
            playTogether(
                ValueAnimator.ofFloat(0f, config.dimAmount).apply {
                    addUpdateListener {
                        windowManager.updateViewLayout(backgroundView, backgroundViewLayoutParams.apply {
                            dimAmount = it.animatedValue as Float
                        })
                    }
                },
            )
            addListener(
                onStart = {
                    windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                        height = rootHeight
                        width = rootWidth
                    })
                    binding.freeformRoot.scaleX = mScaleX
                    binding.freeformRoot.scaleY = mScaleY
                    binding.cardRoot.radius = context.resources.getDimension(R.dimen.card_corner_radius)

                    var topMargin = 0f
                    var bottomMargin = 0f
                    if (FreeformHelper.screenIsPortrait(screenRotation)) {
                        topMargin = freeformShadow
                        bottomMargin = barHeight
                    }

                    AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(binding.bottomBar.root, View.ALPHA, 1f),
                            ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_X, mScaleX, restoreScale[0]),
                            ObjectAnimator.ofFloat(binding.freeformRoot, View.SCALE_Y, mScaleY, restoreScale[1]),
                            cardViewMarginAnim(
                                (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).topMargin,
                                (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).bottomMargin,
                                (binding.cardRoot.layoutParams as ConstraintLayout.LayoutParams).rightMargin,
                                topMargin.roundToInt(),
                                bottomMargin.roundToInt(),
                                cardWidthMargin.roundToInt(),
                            ),
                            moveViewAnim(windowCoordinate, center),
                        )
                        duration = animationDuration(400L)
                        startDelay = 150
                        start()
                    }
                },
                onEnd = { backgroundView.visibility = View.VISIBLE }
            )
            duration = animationDuration(400L)
            start()
        }

        isFloating = false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun hiddenViewToFloatView(goMiniView: Boolean) {
        val windowCoordinate = intArrayOf(windowLayoutParams.x, windowLayoutParams.y)
        hangUpPosition[0] = windowCoordinate[0] <= 0
        hangUpPosition[1] = windowCoordinate[1] <= 0

        val location: IntArray = intArrayOf(
            (if (hangUpPosition[0]) ((realScreenWidth - hangUpViewWidth - screenPaddingX) / -2)
            else ((realScreenWidth - hangUpViewWidth - screenPaddingX) / 2)),
            -1,
        )

        AnimatorSet().apply {
            playTogether(moveViewAnim(windowCoordinate, location))
            addListener(
                onStart = {
                    hiddenView.root.setOnTouchListener(null)
                    windowManager.removeView(hiddenView)
                    isHidden = false
                },
                onEnd = {
                    if (!isHidden) {
                        lastFloatViewLocation = intArrayOf(location[0], windowCoordinate[1])
                    }
                    if (goMiniView) floatViewToMiniView()
                }
            )
            duration = animationDuration(400L)
            interpolator = OvershootInterpolator(2f)
            start()
        }
    }

    private val hideGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            hiddenViewToFloatView(false)
            return true
        }
    })

    // ---- Fitur dari eswd04 ----

    // Setting baru dari preferences
    private var enableSwipeBack = true
    private var enableSuspendMode = true
    private var enableDestroyAnim = true
    private var rememberFreeformSize = true

    // Gesture tambahan
    private var enableSwipeHome = false
    private var enableSwipeForward = false
    private var enableShakeMinimize = false

    // Tampilan
    private var windowOpacity = 100
    private var cornerRadiusValue = 0f

    // Performa
    private var autoCloseScreenOff = false
    private var autoMinimizeOnCall = false
    private var isWindowLocked = false

    // Shake to minimize
    private var sensorManager: android.hardware.SensorManager? = null
    private var accelerometer: android.hardware.Sensor? = null
    private var lastShakeTime = 0L
    private var shakeThreshold = 12f
    private val SHAKE_INTERVAL = 1000L

    private val shakeListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(event: android.hardware.SensorEvent) {
            if (!enableShakeMinimize) return
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val acceleration = kotlin.math.sqrt((x*x + y*y + z*z).toDouble()).toFloat() - android.hardware.SensorManager.GRAVITY_EARTH
            if (acceleration > shakeThreshold) {
                val now = System.currentTimeMillis()
                if (now - lastShakeTime > SHAKE_INTERVAL) {
                    lastShakeTime = now
                    scope.launch(Dispatchers.Main) {
                        if (!isFloating) floatViewToMiniView()
                    }
                }
            }
        }
        override fun onAccuracyChanged(sensor: android.hardware.Sensor, accuracy: Int) {}
    }

    private fun registerShakeListener() {
        if (sensorManager != null) return
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
            ?: return
        val sensor = manager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
            ?: return
        sensorManager = manager
        accelerometer = sensor
        runCatching { manager.registerListener(shakeListener, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL) }
            .onFailure {
                sensorManager = null
                accelerometer = null
            }
    }

    private fun unregisterShakeListener() {
        runCatching { sensorManager?.unregisterListener(shakeListener) }
        sensorManager = null
        accelerometer = null
    }

    // Phone call receiver untuk auto minimize
    private var phoneCallReceiver: android.content.BroadcastReceiver? = null
    // Untuk Android 12+
    private var telephonyCallback: android.telephony.TelephonyCallback? = null

    private fun registerPhoneCallReceiver() {
        unregisterPhoneCallReceiver() // Cleanup dulu

        // q-fix: broadcast maupun callback sama-sama butuh READ_PHONE_STATE.
        // Tanpa permission ini fitur auto minimize on call tidak akan pernah berfungsi.
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_PHONE_STATE
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ - pakai TelephonyCallback
            registerPhoneStateCallback()
        } else {
            // Android < 12 - pakai BroadcastReceiver
            registerPhoneStateReceiver()
        }
    }

    // Untuk Android < 12
    private fun registerPhoneStateReceiver() {
        phoneCallReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, intent: Intent) {
                val state = intent.getStringExtra(android.telephony.TelephonyManager.EXTRA_STATE)
                handlePhoneState(state)
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            priority = android.content.IntentFilter.SYSTEM_HIGH_PRIORITY
        }
        val receiver = phoneCallReceiver ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    receiver,
                    filter,
                    android.content.Context.RECEIVER_EXPORTED
                )
            } else {
                context.registerReceiver(receiver, filter)
            }
        }
    }

    // Untuk Android 12+
    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerPhoneStateCallback() {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        val callback = object : android.telephony.TelephonyCallback(),
            android.telephony.TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                val stateStr = when (state) {
                    android.telephony.TelephonyManager.CALL_STATE_RINGING ->
                        android.telephony.TelephonyManager.EXTRA_STATE_RINGING
                    android.telephony.TelephonyManager.CALL_STATE_OFFHOOK ->
                        android.telephony.TelephonyManager.EXTRA_STATE_OFFHOOK
                    android.telephony.TelephonyManager.CALL_STATE_IDLE ->
                        android.telephony.TelephonyManager.EXTRA_STATE_IDLE
                    else -> null
                }
                stateStr?.let { handlePhoneState(it) }
            }
        }
        telephonyCallback = callback
        runCatching {
            telephonyManager.registerTelephonyCallback(
                context.mainExecutor,
                callback
            )
        }
    }

    // Handler yang sama untuk keduanya
    private fun handlePhoneState(state: String?) {
        when (state) {
            android.telephony.TelephonyManager.EXTRA_STATE_RINGING,
            android.telephony.TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                scope.launch(Dispatchers.Main) {
                    if (!isFloating && !isDestroy) floatViewToMiniView()
                }
            }
            android.telephony.TelephonyManager.EXTRA_STATE_IDLE -> {
                // Telepon selesai → restore floating window
                scope.launch(Dispatchers.Main) {
                    if (isFloating && !isDestroy) {
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (isFloating && !isDestroy) moveToFirst()
                        }, 1000)
                    }
                }
            }
        }
    }

    private fun unregisterPhoneCallReceiver() {
        // Unregister BroadcastReceiver (Android < 12)
        runCatching { phoneCallReceiver?.let { context.unregisterReceiver(it) } }
        phoneCallReceiver = null

        // Unregister TelephonyCallback (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                telephonyCallback?.let {
                    val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                    telephonyManager.unregisterTelephonyCallback(it)
                }
            }
            telephonyCallback = null
        }
    }

    // Remember size per orientasi
    private var savedWidthPortrait = -1
    private var savedHeightPortrait = -1
    private var savedWidthLandscape = -1
    private var savedHeightLandscape = -1

    // Suspend/mini mode
    private var isSuspend = false
    private var suspendTempWidth = -1
    private var suspendTempHeight = -1
    private val SUSPEND_HEIGHT = 384 // 192 * 2
    private val SUSPEND_DISTANCE = 50

    // Simpan ukuran sebelum suspend
    private fun saveSizeBeforeSuspend() {
        if (FreeformHelper.screenIsPortrait(screenRotation)) {
            savedWidthPortrait = freeformWidth
            savedHeightPortrait = freeformHeight
        } else {
            savedWidthLandscape = freeformWidth
            savedHeightLandscape = freeformHeight
        }
        suspendTempWidth = freeformWidth
        suspendTempHeight = freeformHeight
    }

    // Restore ukuran setelah suspend
    private fun restoreSizeAfterSuspend() {
        if (FreeformHelper.screenIsPortrait(screenRotation)) {
            if (savedWidthPortrait > 0) {
                freeformWidth = savedWidthPortrait
                freeformHeight = savedHeightPortrait
            }
        } else {
            if (savedWidthLandscape > 0) {
                freeformWidth = savedWidthLandscape
                freeformHeight = savedHeightLandscape
            }
        }
    }

    // Suspend ke pojok kanan atas
    private fun toSuspendMode() {
        if (isSuspend) {
            // Sudah suspend → restore
            isSuspend = false
            restoreSizeAfterSuspend()
            mScaleX = freeformWidth / rootWidth.toFloat()
            mScaleY = freeformHeight / rootHeight.toFloat()
            windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                width = rootWidth
                height = rootHeight
            })
            return
        }
        isSuspend = true
        saveSizeBeforeSuspend()

        val isLandscape = freeformWidth > freeformHeight
        val suspendW: Int
        val suspendH: Int
        if (isLandscape) {
            suspendW = SUSPEND_HEIGHT
            suspendH = (suspendW * 9 / 16)
        } else {
            suspendH = SUSPEND_HEIGHT
            suspendW = (suspendH * 9 / 16)
        }

        freeformWidth = suspendW
        freeformHeight = suspendH
        mScaleX = freeformWidth / rootWidth.toFloat()
        mScaleY = freeformHeight / rootHeight.toFloat()

        // Geser ke pojok kanan atas
        val targetX = (realScreenWidth - suspendW) / 2 - SUSPEND_DISTANCE
        val targetY = (suspendH - realScreenHeight) / 2 + SUSPEND_DISTANCE

        windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
            width = rootWidth
            height = rootHeight
            x = targetX
            y = targetY
        })
    }

    // Destroy dengan animasi fade out
    fun destroyWithAnim() {
        if (isDestroy) return
        if (!enableDestroyAnim) {
            destroy()
            return
        }
        scope.launch(Dispatchers.Main) {
            binding.root.animate()
                .alpha(0f)
                .setDuration(animationDuration(150L))
                .withEndAction { destroy() }
                .start()
        }
    }

    override fun destroy() {
        //q-fix: destroy bisa dipanggil berkali-kali (screen off + tap luar + service onDestroy)
        if (isDestroy) return

        if (viewModel.getBooleanSp("remember_freeform_position", false)) {
            val sp = context.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
            if (screenRotation == Surface.ROTATION_90 || screenRotation == Surface.ROTATION_270) {
                sp.edit().putInt(REMEMBER_LAND_X, lastFloatViewLocation[0]).putInt(REMEMBER_LAND_Y, lastFloatViewLocation[1]).apply()
            } else {
                sp.edit().putInt(REMEMBER_X, lastFloatViewLocation[0]).putInt(REMEMBER_Y, lastFloatViewLocation[1]).apply()
            }
        }

        if (isHidden) {
            runCatching { windowManager.removeView(hiddenView) }
        }
        if (isFloating) {
            windowLayoutParams.x = 0
            windowLayoutParams.y = 0
        }

        isDestroy = true
        isHidden = false
        isFloating = false
        pendingTaskDisplayJob?.cancel()
        pendingTaskDisplayJob = null
        swipeIndicatorView?.let { runCatching { windowManager.removeView(it) } }
        swipeIndicatorView = null

        // Cleanup sensor shake
        unregisterShakeListener()

        // Cleanup phone call receiver
        unregisterPhoneCallReceiver()

        runCatching {
            hideResizePreview()
            windowManager.removeViewImmediate(binding.root)
            windowManager.removeViewImmediate(backgroundView)
        }
        if (virtualDisplay.surface != null) {
            virtualDisplay.surface.release()
            virtualDisplay.surface = null
        }

        //q-fix: VirtualDisplay harus dilepas agar tidak bocor di SystemServer
        runCatching { virtualDisplay.release() }

        runCatching { iWindowManager.removeRotationWatcher(iRotationWatcher) }

        screenListener.removeScreenStateListener(this@FreeformView)
        viewModel.unregisterOnSharedPreferenceChangeListener(sharedPreferencesChangeListener)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { activityTaskManager.unregisterTaskStackListener(taskStackListener) }
        }
        scope.cancel()
    }

     // Anti-spam untuk onTaskDisplayChanged
    private val TASK_DISPLAY_DEBOUNCE_MS = 1500L
    private var pendingTaskDisplayJob: kotlinx.coroutines.Job? = null

    // Swipe back gesture dengan visual indicator
    private var swipeBackStartX = 0f
    private var swipeBackStartY = 0f
    private var isSwipeBackTracking = false
    private var swipeIndicatorView: android.widget.ImageView? = null
    private var gestureEdgeDp = 60f
    private var gestureMinDistanceDp = 100f
    private val SWIPE_BACK_EDGE_WIDTH: Float get() = gestureEdgeDp
    private val SWIPE_BACK_MIN_DISTANCE: Float get() = gestureMinDistanceDp
    private val SWIPE_BACK_MAX_VERTICAL = 80f

    private fun showSwipeIndicator(fromLeft: Boolean) {
        if (swipeIndicatorView != null) return
        val iv = android.widget.ImageView(context)

        // Garis vertikal hitam seperti sistem Android
        val barWidth = (4 * context.resources.displayMetrics.density).toInt()
        val barHeight = (48 * context.resources.displayMetrics.density).toInt()

        val bg = android.graphics.drawable.GradientDrawable()
        bg.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        bg.cornerRadius = barWidth / 2f

        // Baca transparansi dari setting (0-100), default 80
        val alpha = viewModel.getIntSp("swipe_back_indicator_alpha", 80)
        val alphaInt = (alpha * 2.55f).toInt().coerceIn(0, 255)
        bg.setColor(android.graphics.Color.argb(alphaInt, 0, 0, 0))
        iv.background = bg
        iv.alpha = 0f

        val size = barWidth
        val lp = WindowManager.LayoutParams().apply {
            width = size + (16 * context.resources.displayMetrics.density).toInt()
            height = barHeight
            type = if (Settings.canDrawOverlays(context))
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            x = if (fromLeft) windowLayoutParams.x - (size / 2) else windowLayoutParams.x + windowLayoutParams.width - (size / 2)
            y = windowLayoutParams.y
        }
        runCatching {
            windowManager.addView(iv, lp)
            iv.animate().alpha(1f).setDuration(animationDuration(150L)).start()
            swipeIndicatorView = iv
        }
    }

    private fun updateSwipeIndicator(progress: Float) {
        swipeIndicatorView?.let { iv ->
            val lp = iv.layoutParams as WindowManager.LayoutParams
            lp.x = (windowLayoutParams.x - (40 * context.resources.displayMetrics.density) + 
                    (progress * 30 * context.resources.displayMetrics.density)).toInt()
            runCatching { windowManager.updateViewLayout(iv, lp) }
            iv.scaleX = 0.8f + (progress * 0.4f)
            iv.scaleY = 0.8f + (progress * 0.4f)
        }
    }

    private fun hideSwipeIndicator(triggered: Boolean) {
        swipeIndicatorView?.let { iv ->
            iv.animate()
                .alpha(0f)
                .scaleX(if (triggered) 1.5f else 0.5f)
                .scaleY(if (triggered) 1.5f else 0.5f)
                .setDuration(animationDuration(200L))
                .withEndAction {
                    runCatching { windowManager.removeView(iv) }
                    swipeIndicatorView = null
                }
                .start()
        }
    }

    private fun handleSwipeBackGesture(event: MotionEvent): Boolean {
        if (!enableSwipeBack) return false
        val edgeWidth = SWIPE_BACK_EDGE_WIDTH * context.resources.displayMetrics.density
        val minDistance = SWIPE_BACK_MIN_DISTANCE * context.resources.displayMetrics.density
        val maxVertical = SWIPE_BACK_MAX_VERTICAL * context.resources.displayMetrics.density

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (event.x <= edgeWidth) {
                    swipeBackStartX = event.x
                    swipeBackStartY = event.y
                    isSwipeBackTracking = true
                    showSwipeIndicator(fromLeft = true)
                } else {
                    isSwipeBackTracking = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isSwipeBackTracking) {
                    val dx = event.x - swipeBackStartX
                    val progress = (dx / minDistance).coerceIn(0f, 1f)
                    updateSwipeIndicator(progress)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isSwipeBackTracking) {
                    val dx = event.x - swipeBackStartX
                    val dy = kotlin.math.abs(event.y - swipeBackStartY)
                    if (dx >= minDistance && dy <= maxVertical) {
                        isSwipeBackTracking = false
                        hideSwipeIndicator(triggered = true)
                        performBackKey()
                        return true
                    }
                    hideSwipeIndicator(triggered = false)
                }
                isSwipeBackTracking = false
            }
            MotionEvent.ACTION_CANCEL -> {
                hideSwipeIndicator(triggered = false)
                isSwipeBackTracking = false
            }
        }
        return false
    }

    // ===== SNAP TO EDGE =====
    private fun snapToEdge() {
        if (!viewModel.getBooleanSp("snap_to_edge", true)) return
        val targetX = if (windowLayoutParams.x < 0) {
            (realScreenWidth / -2) + (windowLayoutParams.width / 2) - screenPaddingX
        } else {
            (realScreenWidth / 2) - (windowLayoutParams.width / 2) + screenPaddingX
        }
        ValueAnimator.ofInt(windowLayoutParams.x, targetX).apply {
            duration = animationDuration(200L)
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                runCatching {
                    windowManager.updateViewLayout(binding.root, windowLayoutParams.apply {
                        x = it.animatedValue as Int
                    })
                }
            }
            start()
        }
    }

    // Swipe dari bawah → home
    private var swipeHomeStartX = 0f
    private var swipeHomeStartY = 0f
    private var isSwipeHomeTracking = false
    private val SWIPE_HOME_EDGE_HEIGHT: Float get() = gestureEdgeDp
    private val SWIPE_HOME_MIN_DISTANCE: Float get() = gestureMinDistanceDp

    private fun handleSwipeHomeGesture(event: MotionEvent): Boolean {
        if (!enableSwipeHome) return false
        val edgeHeight = SWIPE_HOME_EDGE_HEIGHT * context.resources.displayMetrics.density
        val minDistance = SWIPE_HOME_MIN_DISTANCE * context.resources.displayMetrics.density
        val viewHeight = binding.textureView.height.toFloat()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (event.y >= viewHeight - edgeHeight) {
                    swipeHomeStartX = event.x
                    swipeHomeStartY = event.y
                    isSwipeHomeTracking = true
                } else isSwipeHomeTracking = false
            }
            MotionEvent.ACTION_UP -> {
                if (isSwipeHomeTracking) {
                    val dy = swipeHomeStartY - event.y
                    val dx = kotlin.math.abs(event.x - swipeHomeStartX)
                    if (dy >= minDistance && dx <= 80f * context.resources.displayMetrics.density) {
                        isSwipeHomeTracking = false
                        performHomeKey()
                        return true
                    }
                }
                isSwipeHomeTracking = false
            }
            MotionEvent.ACTION_CANCEL -> isSwipeHomeTracking = false
        }
        return false
    }

    private fun performHomeKey() {
        runCatching {
            val downEvent = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME, 0)
            val upEvent = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setDisplayIdMethod?.invoke(downEvent, virtualDisplay.display.displayId)
                inputManager.injectInputEvent(downEvent, 0)
                setDisplayIdMethod?.invoke(upEvent, virtualDisplay.display.displayId)
                inputManager.injectInputEvent(upEvent, 0)
            } else {
                inputManager.injectInputEvent(downEvent, virtualDisplay.display.displayId)
                inputManager.injectInputEvent(upEvent, virtualDisplay.display.displayId)
            }
        }
    }

    // Swipe dari kanan → forward
    private var swipeForwardStartX = 0f
    private var swipeForwardStartY = 0f
    private var isSwipeForwardTracking = false
    private val SWIPE_FORWARD_EDGE_WIDTH: Float get() = gestureEdgeDp
    private val SWIPE_FORWARD_MIN_DISTANCE: Float get() = gestureMinDistanceDp

    private fun handleSwipeForwardGesture(event: MotionEvent): Boolean {
        if (!enableSwipeForward) return false
        val edgeWidth = SWIPE_FORWARD_EDGE_WIDTH * context.resources.displayMetrics.density
        val minDistance = SWIPE_FORWARD_MIN_DISTANCE * context.resources.displayMetrics.density
        val viewWidth = binding.textureView.width.toFloat()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (event.x >= viewWidth - edgeWidth) {
                    swipeForwardStartX = event.x
                    swipeForwardStartY = event.y
                    isSwipeForwardTracking = true
                } else isSwipeForwardTracking = false
            }
            MotionEvent.ACTION_UP -> {
                if (isSwipeForwardTracking) {
                    val dx = swipeForwardStartX - event.x
                    val dy = kotlin.math.abs(event.y - swipeForwardStartY)
                    if (dx >= minDistance && dy <= 80f * context.resources.displayMetrics.density) {
                        isSwipeForwardTracking = false
                        performForwardKey()
                        return true
                    }
                }
                isSwipeForwardTracking = false
            }
            MotionEvent.ACTION_CANCEL -> isSwipeForwardTracking = false
        }
        return false
    }

    private fun performForwardKey() {
        runCatching {
            val downEvent = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD, 0)
            val upEvent = KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP, KeyEvent.KEYCODE_FORWARD, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setDisplayIdMethod?.invoke(downEvent, virtualDisplay.display.displayId)
                inputManager.injectInputEvent(downEvent, 0)
                setDisplayIdMethod?.invoke(upEvent, virtualDisplay.display.displayId)
                inputManager.injectInputEvent(upEvent, 0)
            } else {
                inputManager.injectInputEvent(downEvent, virtualDisplay.display.displayId)
                inputManager.injectInputEvent(upEvent, virtualDisplay.display.displayId)
            }
        }
    }

    // ===== RESIZE: tarik sudut kiri/kanan bawah. Ada bayangan target, diterapkan saat dilepas =====
    private var resizePreview: View? = null
    private val resizeStartRect = android.graphics.Rect()
    private val resizeRect = android.graphics.Rect()

    private fun setupCornerResizeHandles() {
        val density = context.resources.displayMetrics.density
        fun pill(): android.graphics.drawable.Drawable {
            val shape = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 8f * density
                setColor(0x99FFFFFF.toInt())
            }
            return android.graphics.drawable.InsetDrawable(shape, (22 * density).toInt(), (16 * density).toInt(), (22 * density).toInt(), (16 * density).toInt())
        }
        binding.leftScale.background = pill()
        binding.rightScale.background = pill()
        binding.leftScale.setOnTouchListener(ScaleHandleTouchListener(false))
        binding.rightScale.setOnTouchListener(ScaleHandleTouchListener(true))
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): IntArray {
        val dm = android.util.DisplayMetrics()
        defaultDisplay.getRealMetrics(dm)
        return intArrayOf(dm.widthPixels, dm.heightPixels)
    }

    /** Kotak jendela yang tampak sekarang (pusat = pusat layar + offset window). */
    private fun currentVisualRect(): android.graphics.Rect {
        val s = screenSize()
        val cx = s[0] / 2 + windowLayoutParams.x
        val cy = s[1] / 2 + windowLayoutParams.y
        return android.graphics.Rect(
            cx - freeformWidth / 2, cy - freeformHeight / 2,
            cx + freeformWidth / 2, cy + freeformHeight / 2
        )
    }

    private fun showResizePreview() {
        hideResizePreview()
        val density = context.resources.displayMetrics.density
        val v = View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x335C6BFF)
                setStroke((2 * density).toInt(), 0xFF5C5CE0.toInt())
                cornerRadius = 12f * density
            }
        }
        val lp = WindowManager.LayoutParams(
            1, 1, windowLayoutParams.type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START }
        runCatching {
            windowManager.addView(v, lp)
            resizePreview = v
        }
    }

    private fun updateResizePreview(r: android.graphics.Rect) {
        val v = resizePreview ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        lp.x = r.left
        lp.y = r.top
        lp.width = r.width()
        lp.height = r.height()
        runCatching { windowManager.updateViewLayout(v, lp) }
    }

    private fun hideResizePreview() {
        resizePreview?.let { runCatching { windowManager.removeViewImmediate(it) } }
        resizePreview = null
    }

    private fun startResize() {
        resizeStartRect.set(currentVisualRect())
        resizeRect.set(resizeStartRect)
        showResizePreview()
        updateResizePreview(resizeRect)
    }

    /** Bentuk bebas (lebar dan tinggi sendiri-sendiri) sampai selebar/setinggi layar. */
    private fun updateResize(totalDx: Float, totalDy: Float, rightSide: Boolean) {
        val s = screenSize()
        val sw = s[0]
        val sh = s[1]
        val start = resizeStartRect
        val density = context.resources.displayMetrics.density
        val minW = (110 * density).toInt()
        val minH = (110 * density).toInt()
        val startW = start.width()
        val startH = start.height()
        if (startW <= 0 || startH <= 0) return
        val maxW = kotlin.math.max(sw, startW)
        val maxH = kotlin.math.max(sh, startH)

        var w = (if (rightSide) startW + totalDx else startW - totalDx).toInt()
        var h = (startH + totalDy).toInt()
        if (viewModel.getBooleanSp("lock_resize_ratio", false)) {
            val keep = startW.toFloat() / startH.toFloat()
            if (w / h.coerceAtLeast(1).toFloat() > keep) h = (w / keep).toInt() else w = (h * keep).toInt()
            val sMin = kotlin.math.max(minW / startW.toFloat(), minH / startH.toFloat())
            val sMax = kotlin.math.min(maxW / startW.toFloat(), maxH / startH.toFloat())
            val scale = (w / startW.toFloat()).coerceIn(sMin, kotlin.math.max(sMin, sMax))
            w = (startW * scale).toInt()
            h = (startH * scale).toInt()
        } else {
            w = w.coerceIn(minW, maxW)
            h = h.coerceIn(minH, maxH)
        }

        // Jangkar: kiri-atas (handle kanan) atau kanan-atas (handle kiri)
        val left = if (rightSide) start.left else start.right - w
        resizeRect.set(left, start.top, left + w, start.top + h)
        // Tetap di dalam layar
        var dx = 0
        var dy = 0
        if (resizeRect.right > sw) dx = sw - resizeRect.right
        if (resizeRect.left + dx < 0) dx = -resizeRect.left
        if (resizeRect.bottom > sh) dy = sh - resizeRect.bottom
        if (resizeRect.top + dy < 0) dy = -resizeRect.top
        resizeRect.offset(dx, dy)
        updateResizePreview(resizeRect)
    }

    private fun commitResize() {
        hideResizePreview()
        val w = resizeRect.width()
        val h = resizeRect.height()
        if (w <= 0 || h <= 0 || resizeRect == resizeStartRect) return
        val s = screenSize()
        freeformWidth = kotlin.math.min(w, rootWidth)
        freeformHeight = kotlin.math.min(h, rootHeight)
        windowLayoutParams.x = resizeRect.centerX() - s[0] / 2
        windowLayoutParams.y = resizeRect.centerY() - s[1] / 2
        mScaleX = freeformWidth / rootWidth.toFloat()
        mScaleY = freeformHeight / rootHeight.toFloat()
        if (!viewModel.getBooleanSp("lock_resize_ratio", false)) {
            // Bentuk bebas: virtual display mengikuti bentuk jendela agar isi aplikasi menyesuaikan
            freeformScreenWidth = (freeformWidth - cardWidthMargin).roundToInt().coerceAtLeast(1)
            freeformScreenHeight = (freeformHeight - cardHeightMargin).roundToInt().coerceAtLeast(1)
            resizeVirtualDisplay()
            refreshTouchScale()
        }
        runCatching { windowManager.updateViewLayout(binding.root, windowLayoutParams) }
        refreshActionScale()
        if (rememberFreeformSize) {
            if (FreeformHelper.screenIsPortrait(screenRotation)) {
                savedWidthPortrait = freeformWidth
                savedHeightPortrait = freeformHeight
            } else {
                savedWidthLandscape = freeformWidth
                savedHeightLandscape = freeformHeight
            }
            rememberCurrentSize()
        }
        isZoomOut = false
    }

    private inner class ScaleHandleTouchListener(private val rightSide: Boolean) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var active = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (isFloating || isHidden || isSuspend) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    active = true
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    startResize()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!active) return false
                    updateResize(event.rawX - downX, event.rawY - downY, rightSide)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (active) {
                        active = false
                        if (event.actionMasked == MotionEvent.ACTION_UP) commitResize() else hideResizePreview()
                    }
                    return true
                }
            }
            return false
        }
    }

    private inner class TouchListener : View.OnTouchListener {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (handleSwipeBackGesture(event)) return true
            if (handleSwipeHomeGesture(event)) return true
            if (handleSwipeForwardGesture(event)) return true
            handleTouch(event)
            when(event.action) {
                MotionEvent.ACTION_DOWN -> touchId = R.id.textureView
                MotionEvent.ACTION_UP -> touchId = -1
            }
            return true
        }

        private fun handleTouch(event: MotionEvent) {
            // MotionEvent.obtain expects non-null pointer arrays.  Nullable
            // arrays work on some Kotlin/Android toolchains but fail to compile
            // or crash when the platform method is resolved strictly.
            val pointerCoords = Array(event.pointerCount) { MotionEvent.PointerCoords() }
            val pointerProperties = Array(event.pointerCount) { MotionEvent.PointerProperties() }
            for (i in 0 until event.pointerCount) {
                val oldCoords = MotionEvent.PointerCoords()
                event.getPointerCoords(i, oldCoords)
                event.getPointerProperties(i, pointerProperties[i])
                pointerCoords[i].apply {
                    x = oldCoords.x / scaleX
                    y = oldCoords.y / scaleY
                    pressure = oldCoords.pressure
                    size = oldCoords.size
                    toolMajor = oldCoords.toolMajor
                    toolMinor = oldCoords.toolMinor
                    touchMajor = oldCoords.touchMajor
                    touchMinor = oldCoords.touchMinor
                    orientation = oldCoords.orientation
                }
            }
            val newEvent = MotionEvent.obtain(
                event.downTime, event.eventTime, event.action, event.pointerCount,
                pointerProperties, pointerCoords, event.metaState, event.buttonState,
                event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags,
                event.source, event.flags
            )
            runCatching {
                setDisplayIdMethod?.invoke(newEvent, virtualDisplay.display.displayId)
                inputManager.injectInputEvent(newEvent, 0)
            }.onFailure {
                Log.w(TAG, "Failed to inject touch event", it)
            }
            newEvent.recycle()
        }
    }

    private inner class TouchListenerPreQ : View.OnTouchListener {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (handleSwipeBackGesture(event)) return true
            if (handleSwipeHomeGesture(event)) return true
            if (handleSwipeForwardGesture(event)) return true
            handleTouch(event)
            when(event.action) {
                MotionEvent.ACTION_DOWN -> touchId = R.id.textureView
                MotionEvent.ACTION_UP -> touchId = -1
            }
            return true
        }

        private fun handleTouch(event: MotionEvent) {
            // MotionEvent.obtain expects non-null pointer arrays.  Nullable
            // arrays work on some Kotlin/Android toolchains but fail to compile
            // or crash when the platform method is resolved strictly.
            val pointerCoords = Array(event.pointerCount) { MotionEvent.PointerCoords() }
            val pointerProperties = Array(event.pointerCount) { MotionEvent.PointerProperties() }
            for (i in 0 until event.pointerCount) {
                val oldCoords = MotionEvent.PointerCoords()
                event.getPointerCoords(i, oldCoords)
                event.getPointerProperties(i, pointerProperties[i])
                pointerCoords[i].apply {
                    x = oldCoords.x / scaleX
                    y = oldCoords.y / scaleY
                    pressure = oldCoords.pressure
                    size = oldCoords.size
                    toolMajor = oldCoords.toolMajor
                    toolMinor = oldCoords.toolMinor
                    touchMajor = oldCoords.touchMajor
                    touchMinor = oldCoords.touchMinor
                    orientation = oldCoords.orientation
                }
            }
            val newEvent = MotionEvent.obtain(
                event.downTime, event.eventTime, event.action, event.pointerCount,
                pointerProperties, pointerCoords, event.metaState, event.buttonState,
                event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags,
                event.source, event.flags
            )
            runCatching {
                inputManager.injectInputEvent(newEvent, virtualDisplay.display.displayId)
            }.onFailure {
                Log.w(TAG, "Failed to inject pre-Q touch event", it)
            }
            newEvent.recycle()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private inner class MTaskStackListener : TaskStackListener() {
        override fun onTaskCreated(tId: Int, componentName: ComponentName?) {}
        override fun onTaskRemoved(taskId: Int) {
            if (isDestroy) return
            taskList.remove(taskId)
            if (taskList.isEmpty()) {
                scope.launch(Dispatchers.Main) {
                    kotlinx.coroutines.delay(500)
                    if (!isDestroy && taskList.isEmpty()) destroy()
                }
            }
        }
        override fun onTaskRemovalStarted(taskInfo: ActivityManager.RunningTaskInfo) {
            if (isDestroy) return
            taskList.remove(taskInfo.taskId)
        }
        override fun onTaskDisplayChanged(tId: Int, newDisplayId: Int) {
            if (isDestroy) return
            if (newDisplayId == virtualDisplay.display.displayId) {
                if (!taskList.contains(tId)) taskList.add(tId)
                return
            }
            if (!taskList.contains(tId)) return
            if (newDisplayId == Display.DEFAULT_DISPLAY) {
                if (isFloating) {
                    context.startService(
                        Intent(context, FreeformService::class.java)
                            .setAction(FreeformService.ACTION_START_INTENT)
                            .putExtra(Intent.EXTRA_INTENT, config.intent)
                            .putExtra(Intent.EXTRA_COMPONENT_NAME, config.componentName)
                            .putExtra(Intent.EXTRA_USER, config.userId)
                    )
                    return
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (config.useSuiRefuseToFullScreen) {
                        runCatching {
                            activityTaskManager.moveRootTaskToDisplay(tId, virtualDisplay.display.displayId)
                        }
                    } else {
                        context.startService(
                            Intent(context, FreeformService::class.java)
                                .setAction(FreeformService.ACTION_CALL_INTENT)
                                .putExtra(Intent.EXTRA_INTENT, config.intent)
                                .putExtra(FreeformService.EXTRA_DISPLAY_ID, virtualDisplay.display.displayId)
                        )
                    }
                }
            }
        }
        override fun onTaskRequestedOrientationChanged(tId: Int, requestedOrientation: Int) {
            var tempRotation = requestedOrientation
            if (tempRotation != VIRTUAL_DISPLAY_ROTATION_PORTRAIT && tempRotation != VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) tempRotation = VIRTUAL_DISPLAY_ROTATION_PORTRAIT
            if (taskList.contains(tId) && tempRotation != virtualDisplayRotation) {
                virtualDisplayRotation = tempRotation
                scope.launch(Dispatchers.Main) { onFreeFormRotationChanged() }
            }
        }
        override fun onActivityRequestedOrientationChanged(tId: Int, requestedOrientation: Int) {
            var tempRotation = requestedOrientation
            if (tempRotation != VIRTUAL_DISPLAY_ROTATION_PORTRAIT && tempRotation != VIRTUAL_DISPLAY_ROTATION_LANDSCAPE) tempRotation = VIRTUAL_DISPLAY_ROTATION_PORTRAIT
            if (taskList.contains(tId) && tempRotation != virtualDisplayRotation) {
                virtualDisplayRotation = tempRotation
                scope.launch(Dispatchers.Main) { onFreeFormRotationChanged() }
            }
        }
    }

    companion object {
        private const val TAG = "FreeformView"
        const val REMEMBER_X = "freeform_remember_x"
        const val REMEMBER_Y = "freeform_remember_y"
        const val REMEMBER_LAND_X = "freeform_remember_land_x"
        const val REMEMBER_LAND_Y = "freeform_remember_land_y"
        const val REMEMBER_HEIGHT = "freeform_remember_height"
        const val REMEMBER_LAND_HEIGHT = "freeform_remember_land_height"
        private const val VIRTUAL_DISPLAY_ROTATION_PORTRAIT = 1
        private const val VIRTUAL_DISPLAY_ROTATION_LANDSCAPE = 0
    }
}
