package io.github.yushinjung.picklewhip

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 핵심 서비스.
 *  1) 지금 화면에 떠 있는 앱이 '혼낼 앱'인지 추적한다.
 *  2) 맞으면 가속도 센서를 켜고, 세게 흔들면 그 앱 위에 채찍을 그린다.
 *  3) 게이지가 다 차면 설정한 문구를 그 앱 입력창에 넣는다(옵션: 전송까지).
 */
class WhipService : AccessibilityService(), SensorEventListener {

    companion object {
        @Volatile
        var instance: WhipService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var sensors: SensorManager
    private var sound: CrackSound? = null
    private var overlay: WhipView? = null
    private var icon: ImageView? = null
    private var inTarget = false      // 혼낼 앱이 맨 앞에 있는가
    private val idle = Runnable { onIdle() }

    private var listening = false
    private var usingLinear = false
    private val grav = FloatArray(3)
    private var gravInit = false

    private var lastCrack = 0L
    private var lastHit = 0L
    private var combo = 0
    private var gauge = 0f
    private var cooling = false       // 문구를 보낸 직후 잠깐 쉬는 구간

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = stopWhip()
    }
    private var receiverOn = false

    // ---------- 수명 주기 ----------

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sound = CrackSound()
        val f = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOff, f)
        receiverOn = true
        instance = this
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        instance = null
        stopWhip()
        if (receiverOn) {
            try { unregisterReceiver(screenOff) } catch (e: Exception) { }
            receiverOn = false
        }
        sound?.release(); sound = null
    }

    // ---------- 1) 어떤 앱이 떠 있는지 추적 ----------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return

        val target = Prefs.targetPkg(this)
        if (target.isEmpty()) { stopWhip(); return }

        // 이벤트를 보낸 쪽이 아니라 '지금 맨 앞의 앱 창'이 누구인지로 판단한다.
        // (키보드가 뜨고 내려가는 것을 앱 전환으로 착각하지 않기 위해)
        var front = frontAppPackage()
        if (front == null && type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && pkg != packageName && !isTransient(pkg)) front = pkg
        }
        when {
            front == null -> return
            front == target -> startWhip()
            isTransient(front) -> return          // 알림창·키보드: 상태 유지
            else -> stopWhip()
        }
    }

    /** 맨 앞에 있는 '앱' 창의 패키지. 키보드나 시스템 창은 건너뛴다. */
    private fun frontAppPackage(): String? {
        try {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION && (w.isActive || w.isFocused)) {
                    val p = w.root?.packageName?.toString()
                    if (p != null) return p
                }
            }
        } catch (e: Exception) { }
        return rootInActiveWindow?.packageName?.toString()
    }

    /** 대상 앱 창의 화면 정보. 키보드가 떠 있어도 대상 앱 창을 직접 찾는다. */
    private fun targetRoot(): AccessibilityNodeInfo? {
        val target = Prefs.targetPkg(this)
        try {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val r = w.root
                    if (r != null && r.packageName?.toString() == target) return r
                }
            }
        } catch (e: Exception) { }
        val r = rootInActiveWindow
        return if (r != null && r.packageName?.toString() == target) r else null
    }

    private fun isTransient(pkg: String): Boolean =
        pkg == "com.android.systemui" || pkg in keyboardPackages()

    private var kbCache: Set<String> = emptySet()
    private var kbCacheAt = 0L

    private fun keyboardPackages(): Set<String> {
        val now = SystemClock.uptimeMillis()
        if (kbCacheAt != 0L && now - kbCacheAt < 60_000) return kbCache
        kbCache = try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.enabledInputMethodList.map { it.packageName }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
        kbCacheAt = now
        return kbCache
    }

    /** 혼낼 앱이 맨 앞으로 왔다. 처음 한 번만 채찍을 켠다. */
    private fun startWhip() {
        if (inTarget) return              // 이미 켜짐 또는 아이콘 상태. 키보드가 오르내려도 그대로 둔다.
        inTarget = true
        arm()
    }

    /** 혼낼 앱을 떠났다. 채찍도 아이콘도 모두 치운다. */
    private fun stopWhip() {
        inTarget = false
        main.removeCallbacks(idle)
        if (listening) {
            sensors.unregisterListener(this)
            listening = false
        }
        removeOverlay()
        removeIcon()
        gauge = 0f; combo = 0; cooling = false
    }

    /** 채찍 켜기: 센서를 켜고 늘어뜨린 채찍을 보여 준다. */
    private fun arm() {
        removeIcon()
        if (!listening) {
            val linear = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            val s = linear ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            usingLinear = linear != null
            gravInit = false
            sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
            listening = true
        }
        ensureOverlay()
        overlay?.setArmed(true)
        overlay?.showNote("채찍 준비됨 — 휘두르세요")
        main.removeCallbacks(idle)
        main.postDelayed(idle, idleMs())
    }

    /** 설정한 시간 동안 휘두르지 않으면 채찍을 접는다. */
    private fun idleMs(): Long = Prefs.idleSec(this) * 1000L

    /** 한동안 휘두르지 않았다. 채찍을 접고 작은 아이콘만 남긴다. */
    private fun onIdle() {
        if (!inTarget || !listening) return
        if (cooling) { main.postDelayed(idle, 3000); return }
        sensors.unregisterListener(this)
        listening = false
        removeOverlay()
        gauge = 0f; combo = 0
        showIcon()
    }

    /** 화면 오른쪽 위의 작은 아이콘. 누르면 채찍이 돌아온다. */
    private fun showIcon() {
        if (icon != null) return
        val d = resources.displayMetrics.density
        val v = ImageView(this)
        v.setImageResource(R.drawable.ic_whip_fg)
        v.scaleType = ImageView.ScaleType.CENTER_CROP
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xE6221C18.toInt())
            setStroke((1 * d).toInt(), 0x66F4ECE2)
        }
        v.contentDescription = "AI 채찍 다시 켜기"
        v.setOnClickListener { if (inTarget) arm() }
        val size = (44 * d).toInt()
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.END
        lp.x = (12 * d).toInt()
        lp.y = (96 * d).toInt()
        try {
            wm.addView(v, lp)
            icon = v
        } catch (e: Exception) {
            icon = null
        }
    }

    private fun removeIcon() {
        val v = icon ?: return
        try { wm.removeView(v) } catch (e: Exception) { }
        icon = null
    }

    private fun ensureOverlay() {
        if (overlay != null) return
        val v = WhipView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(v, lp)
            overlay = v
        } catch (e: Exception) {
            overlay = null
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        try { wm.removeView(v) } catch (e: Exception) { }
        overlay = null
    }

    // ---------- 2) 흔들기 감지 ----------

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        var ax = e.values[0]
        var ay = e.values[1]
        var az = e.values[2]
        if (!usingLinear) {
            // 선형가속도 센서가 없는 기기: 저역통과로 중력을 추정해 뺀다.
            if (!gravInit) { grav[0] = ax; grav[1] = ay; grav[2] = az; gravInit = true }
            val k = 0.9f
            grav[0] = k * grav[0] + (1 - k) * ax
            grav[1] = k * grav[1] + (1 - k) * ay
            grav[2] = k * grav[2] + (1 - k) * az
            ax -= grav[0]; ay -= grav[1]; az -= grav[2]
        }
        val mag = sqrt(ax * ax + ay * ay + az * az)
        val threshold = 16f * Prefs.sens(this)          // m/s^2
        if (mag > threshold) {
            crack(min(1f, (mag - threshold) / threshold), deliver = true)
        }
    }

    // ---------- 타격 ----------

    private fun crack(strength: Float, deliver: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (now - lastCrack < 350 || cooling) return

        if (deliver) {
            // 화면이 꺼졌거나 대상 앱이 아니면 아무것도 하지 않는다(주머니 속 오작동 방지).
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) { stopWhip(); return }
            val front = frontAppPackage()
            if (front != null && front != Prefs.targetPkg(this) && !isTransient(front)) { stopWhip(); return }
        }
        lastCrack = now
        if (deliver) {
            // 휘두를 때마다 '접기' 시간을 다시 잰다.
            main.removeCallbacks(idle)
            main.postDelayed(idle, idleMs())
        }

        ensureOverlay()
        val view = overlay ?: return

        combo = if (now - lastHit < 1500) combo + 1 else 1
        lastHit = now
        val line = Lines.pick(gauge)
        // 설정한 횟수만큼 치면 정확히 100%가 된다.
        gauge = min(100f, gauge + 100f / Prefs.hits(this) + 0.01f)
        val name = Prefs.targetLabel(this).ifEmpty { "AI" }

        view.hit(strength, line, name, Lines.stageName(gauge), gauge, combo)
        sound?.play(0.5f + 0.5f * strength)
        buzz((30 + 60 * strength).toLong())

        if (gauge >= 100f) {
            cooling = true
            view.setGauge(100f, "정신 차림")
            view.showBanner("$name, 정신 차려!")
            buzz(180)
            if (deliver) main.postDelayed({ deliverMessage() }, 500)
            main.postDelayed({
                gauge = 0f; combo = 0; cooling = false
                overlay?.setGauge(0f, Lines.stageName(0f))
            }, 3000)
        }
    }

    private fun buzz(ms: Long) {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) { }
    }

    /** 설정 화면의 테스트 버튼용. 문구는 보내지 않는다. */
    fun testCrack() {
        ensureOverlay()
        crack(0.7f, deliver = false)
        main.postDelayed({ if (!listening) removeOverlay() }, 4500)
    }

    // ---------- 3) 문구 전달 ----------

    private fun deliverMessage() {
        val msg = Prefs.message(this).trim()
        if (msg.isEmpty()) return
        val root = targetRoot() ?: return

        val input = findInput(root)
        var typed = false
        if (input != null) {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, msg)
            typed = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        if (!typed || input == null) {
            copyToClipboard(msg)
            overlay?.showNote("입력창을 못 찾아 문구를 복사했습니다. 붙여넣어 주세요")
            return
        }
        if (!Prefs.autoSend(this)) {
            overlay?.showNote("입력창에 문구를 넣었습니다")
            return
        }
        // 글자가 들어가야 전송 버튼이 켜지는 앱이 많아 잠깐 기다린다.
        main.postDelayed({
            val pressed = pressSend(input)
            overlay?.showNote(
                if (pressed) "문구를 넣고 전송을 눌렀습니다"
                else "문구를 넣었습니다. 전송은 직접 눌러 주세요"
            )
        }, 300)
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("AI 채찍", text))
        } catch (e: Exception) { }
    }

    /** 화면에서 글자를 넣을 수 있는 칸을 찾는다. 포커스된 칸을 우선한다. */
    private fun findInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused
        var found: AccessibilityNodeInfo? = null
        walk(root) { n ->
            if (found == null && n.isEditable && n.isVisibleToUser && n.isEnabled) found = n
        }
        return found
    }

    /**
     * 전송 버튼 누르기.
     *  - 이름에 '보내/전송/send'가 들어간 누를 수 있는 요소 중 입력창과 가장 가까운 것을 누른다.
     *  - 없으면 키보드의 엔터(전송) 동작을 시도한다(안드로이드 11 이상).
     */
    private fun pressSend(input: AccessibilityNodeInfo): Boolean {
        val root = targetRoot() ?: return false
        val inBox = Rect()
        input.getBoundsInScreen(inBox)
        val limit = (220 * resources.displayMetrics.density).toInt()
        val words = arrayOf("send", "보내", "전송", "submit")

        var best: AccessibilityNodeInfo? = null
        var bestDist = Int.MAX_VALUE
        val box = Rect()
        walk(root) { n ->
            if (n.isClickable && n.isEnabled && n.isVisibleToUser && !n.isEditable) {
                val label = listOf(n.text, n.contentDescription, n.viewIdResourceName)
                    .joinToString(" ") { it?.toString() ?: "" }
                    .lowercase()
                if (words.any { label.contains(it) }) {
                    n.getBoundsInScreen(box)
                    val dist = abs(box.centerY() - inBox.centerY())
                    if (dist < limit && dist < bestDist) { best = n; bestDist = dist }
                }
            }
        }
        best?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true }

        if (Build.VERSION.SDK_INT >= 30) {
            return input.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return false
    }

    private fun walk(node: AccessibilityNodeInfo?, depth: Int = 0, visit: (AccessibilityNodeInfo) -> Unit) {
        if (node == null || depth > 60) return
        visit(node)
        for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1, visit)
    }
}
