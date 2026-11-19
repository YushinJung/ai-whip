package io.github.yushinjung.picklewhip

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 다른 앱 위에 겹쳐 그리는 채찍 화면.
 * 터치를 받지 않으며, 타격이 있을 때만 몇 초간 그리고 다시 투명해진다.
 */
class WhipView(context: Context) : View(context) {

    private val dp = resources.displayMetrics.density
    private val n = 22                               // 채찍 마디 수
    private val x = FloatArray(n)
    private val y = FloatArray(n)
    private val px = FloatArray(n)
    private val py = FloatArray(n)
    private var seg = 10f
    private var restX = 0f
    private var restY = 0f
    private var hx = 0f
    private var hy = 0f
    private var hvx = 0f
    private var hvy = 0f
    private var lashAt = 0L
    private var ready = false
    private var armed = false

    private var activeUntil = 0L
    private var lastFrame = 0L
    private var acc = 0f

    private class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float)
    private class Pop(val x: Float, val y: Float, var life: Float, val rot: Float, val size: Float)

    private val sparks = ArrayList<Spark>()
    private val pops = ArrayList<Pop>()
    private var flash = 0f
    private var shake = 0f

    private var bubble: StaticLayout? = null
    private var bubbleName = ""
    private var bubbleX = 0f
    private var bubbleY = 0f
    private var bubbleW = 0f
    private var bubbleLife = 0f

    private var banner = ""
    private var bannerLife = 0f
    private var note = ""
    private var noteLife = 0f

    private var gauge = 0f
    private var combo = 0
    private var who = "AI"
    private var stage = ""

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
    }
    private val bubbleText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(27, 21, 18)
        textSize = 15f * dp
    }
    private val rect = RectF()

    private val cInk = Color.rgb(244, 236, 226)
    private val cAccent = Color.rgb(255, 90, 54)
    private val cDark = Color.rgb(20, 17, 15)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        restX = w * 0.86f
        restY = h * 0.80f
        seg = h * 0.55f / n
        resetRope()
        ready = w > 0 && h > 0
    }

    private fun resetRope() {
        hx = restX; hy = restY; hvx = 0f; hvy = 0f
        for (i in 0 until n) {
            x[i] = restX; y[i] = restY + i * seg
            px[i] = x[i]; py[i] = y[i]
        }
    }

    // ---------- 서비스가 부르는 함수 ----------

    /** 타격 한 번. s는 세기(0~1). */
    fun hit(s: Float, line: String, name: String, stageName: String, gaugeNow: Float, comboNow: Int) {
        if (!ready) return
        val now = SystemClock.uptimeMillis()
        if (now > activeUntil) { resetRope(); sparks.clear(); pops.clear(); lastFrame = 0L; acc = 0f }
        activeUntil = now + 3600
        lashAt = now
        who = name; stage = stageName; gauge = gaugeNow; combo = comboNow

        val w = width.toFloat()
        val h = height.toFloat()
        val u = min(w, h)

        // 타격 지점: 화면 가운데 위쪽 어딘가
        val tx = w * (0.25f + Random.nextFloat() * 0.35f)
        val ty = h * (0.34f + Random.nextFloat() * 0.20f)

        // 손잡이에서 타격 지점까지 위로 휜 곡선 모양으로 줄을 놓고,
        // 직전 위치를 뒤로 밀어 '날아와 꽂히는' 속도를 준다.
        val cx = (hx + tx) / 2f + u * 0.10f
        val cy = min(hy, ty) - h * 0.16f
        val kick = u * (0.035f + 0.045f * s)
        for (i in 0 until n) {
            val t = i / (n - 1f)
            val a = (1 - t) * (1 - t)
            val b = 2 * (1 - t) * t
            val c = t * t
            x[i] = a * hx + b * cx + c * tx
            y[i] = a * hy + b * cy + c * ty
            px[i] = x[i] + kick * t          // 오른쪽 위에서 왼쪽 아래로 내려친 것처럼
            py[i] = y[i] - kick * t * 1.4f
        }

        flash = 0.30f + 0.35f * s
        shake = (5f + 14f * s) * dp
        val count = 10 + (14 * s).toInt()
        for (i in 0 until count) {
            val ang = Random.nextFloat() * 6.2832f
            val v = (2f + Random.nextFloat() * 7f) * (0.6f + s) * dp
            sparks.add(Spark(tx, ty, cos(ang) * v, sin(ang) * v, 1f))
        }
        pops.add(Pop(tx, ty, 1f, (Random.nextFloat() - 0.5f) * 28f, (34f + 30f * s) * dp))

        // 말풍선
        val maxW = min(w * 0.72f, 300f * dp).toInt()
        val layout = StaticLayout.Builder.obtain(line, 0, line.length, bubbleText, maxW).build()
        var lw = 0f
        for (i in 0 until layout.lineCount) lw = max(lw, layout.getLineWidth(i))
        bubble = layout
        bubbleName = name
        bubbleW = max(lw, 60f * dp)
        val bw = bubbleW + 28f * dp
        val bh = layout.height + 40f * dp
        bubbleX = (tx - bw / 2f).coerceIn(12f * dp, w - bw - 12f * dp)
        bubbleY = (ty - bh - 30f * dp).coerceIn(110f * dp, h - bh - 90f * dp)
        bubbleLife = 1f

        postInvalidateOnAnimation()
    }

    fun showBanner(msg: String) {
        banner = msg; bannerLife = 1f
        activeUntil = max(activeUntil, SystemClock.uptimeMillis() + 3200)
        postInvalidateOnAnimation()
    }

    fun showNote(msg: String) {
        note = msg; noteLife = 1f
        activeUntil = max(activeUntil, SystemClock.uptimeMillis() + 3600)
        postInvalidateOnAnimation()
    }

    fun setGauge(g: Float, stageName: String) { gauge = g; stage = stageName }

    /** 채찍이 켜져 있는 동안 화면 구석에 늘어뜨린 채찍을 보여 준다. */
    fun setArmed(on: Boolean) {
        armed = on
        postInvalidateOnAnimation()
    }

    // ---------- 물리 (고정 1/60초 스텝) ----------

    private fun step(now: Long) {
        // 손잡이: 내려친 직후 잠깐 앞으로 나갔다가 제자리로
        var tx = restX
        var ty = restY
        if (now - lashAt < 130) { tx -= width * 0.10f; ty -= height * 0.06f }
        hvx = (hvx + (tx - hx) * 0.28f) * 0.62f
        hvy = (hvy + (ty - hy) * 0.28f) * 0.62f
        hx += hvx; hy += hvy

        val g = seg * 0.05f
        for (i in 1 until n) {
            val vx = (x[i] - px[i]) * 0.985f
            val vy = (y[i] - py[i]) * 0.985f
            px[i] = x[i]; py[i] = y[i]
            x[i] += vx; y[i] += vy + g
        }
        for (k in 0 until 8) {
            x[0] = hx; y[0] = hy
            for (i in 0 until n - 1) {
                val dx = x[i + 1] - x[i]
                val dy = y[i + 1] - y[i]
                val d = max(hypot(dx, dy), 0.0001f)
                val diff = (d - seg) / d
                if (i == 0) {
                    x[1] -= dx * diff; y[1] -= dy * diff
                } else {
                    x[i] += dx * diff * 0.5f; y[i] += dy * diff * 0.5f
                    x[i + 1] -= dx * diff * 0.5f; y[i + 1] -= dy * diff * 0.5f
                }
            }
        }
        px[0] = x[0]; py[0] = y[0]

        var i = sparks.size - 1
        while (i >= 0) {
            val s = sparks[i]
            s.x += s.vx; s.y += s.vy; s.vy += 0.25f * dp; s.vx *= 0.96f; s.life -= 0.035f
            if (s.life <= 0f) sparks.removeAt(i)
            i--
        }
        i = pops.size - 1
        while (i >= 0) {
            pops[i].life -= 0.03f
            if (pops[i].life <= 0f) pops.removeAt(i)
            i--
        }
        flash *= 0.84f
        shake *= 0.86f
        if (bubbleLife > 0f) bubbleLife -= 1f / 160f
        if (bannerLife > 0f) bannerLife -= 1f / 150f
        if (noteLife > 0f) noteLife -= 1f / 200f
    }

    // ---------- 그리기 ----------

    override fun onDraw(canvas: Canvas) {
        if (!ready) return
        val now = SystemClock.uptimeMillis()
        if (now > activeUntil) {
            // 타격이 없을 때: 채찍이 켜져 있으면 늘어뜨린 채찍만 그린다. 꺼져 있으면 투명.
            if (armed) { resetRope(); drawWhip(canvas, 215) }
            return
        }

        if (lastFrame == 0L) lastFrame = now
        acc += min(100L, now - lastFrame).toFloat()
        lastFrame = now
        var steps = 0
        while (acc >= 16.667f && steps < 5) { step(now); acc -= 16.667f; steps++ }

        val fade = ((activeUntil - now) / 400f).coerceIn(0f, 1f)
        val alphaAll = (255 * fade).toInt()
        val w = width.toFloat()
        val h = height.toFloat()
        val u = min(w, h)

        canvas.save()
        if (shake > 0.5f) {
            canvas.translate((Random.nextFloat() - 0.5f) * shake, (Random.nextFloat() - 0.5f) * shake)
        }

        drawWhip(canvas, alphaAll)

        // 불꽃
        for (s in sparks) {
            fill.color = if (s.life > 0.5f) Color.rgb(255, 233, 168) else Color.rgb(255, 122, 60)
            fill.alpha = (255 * s.life.coerceIn(0f, 1f) * fade).toInt()
            canvas.drawCircle(s.x, s.y, (2f + 3f * s.life) * dp, fill)
        }

        // '짝!'
        for (p in pops) {
            val k = 1f - p.life
            val sc = 0.6f + min(1f, k * 6f) * 0.6f
            canvas.save()
            canvas.translate(p.x, p.y - k * 30f * dp)
            canvas.rotate(p.rot)
            canvas.scale(sc, sc)
            val a = (255 * (p.life * 2f).coerceIn(0f, 1f) * fade).toInt()
            text.textSize = p.size
            text.textAlign = Paint.Align.CENTER
            text.style = Paint.Style.STROKE
            text.strokeWidth = 8f * dp
            text.strokeJoin = Paint.Join.ROUND
            text.color = cDark; text.alpha = a
            canvas.drawText("짝!", 0f, p.size * 0.35f, text)
            text.style = Paint.Style.FILL
            text.color = cAccent; text.alpha = a
            canvas.drawText("짝!", 0f, p.size * 0.35f, text)
            canvas.restore()
        }
        canvas.restore()

        // 번쩍임
        if (flash > 0.02f) {
            fill.color = Color.rgb(255, 240, 220)
            fill.alpha = (255 * min(0.5f, flash) * fade).toInt()
            canvas.drawRect(0f, 0f, w, h, fill)
        }

        drawHud(canvas, w, alphaAll)
        drawBubble(canvas, fade)
        drawBanner(canvas, w, h, fade)
        drawNote(canvas, w, h, fade)

        postInvalidateOnAnimation()
    }

    /** 손잡이와 줄을 그린다. 타격 중에도, 대기 중에도 쓴다. */
    private fun drawWhip(canvas: Canvas, alphaAll: Int) {
        val u = min(width, height).toFloat()
        // 손잡이 (첫 마디 반대 방향)
        var dx = x[0] - x[1]
        var dy = y[0] - y[1]
        val dl = max(hypot(dx, dy), 0.0001f)
        dx /= dl; dy /= dl
        val hl = u * 0.2f
        stroke.color = Color.rgb(59, 36, 20); stroke.alpha = alphaAll; stroke.strokeWidth = 17f * dp
        canvas.drawLine(x[0], y[0], x[0] + dx * hl, y[0] + dy * hl, stroke)
        stroke.color = Color.rgb(107, 68, 36); stroke.alpha = alphaAll; stroke.strokeWidth = 11f * dp
        canvas.drawLine(x[0], y[0], x[0] + dx * hl, y[0] + dy * hl, stroke)

        // 줄: 어두운 테두리 → 가죽색, 끝으로 갈수록 가늘게
        for (pass in 0..1) {
            for (i in 0 until n - 1) {
                val t = i / (n - 1f)
                val wd = (9f - 7f * t) * dp
                if (pass == 0) {
                    stroke.color = Color.rgb(42, 26, 14); stroke.strokeWidth = wd + 2f * dp
                } else {
                    stroke.color = if (t > 0.9f) Color.rgb(232, 213, 176) else Color.rgb(154, 102, 50)
                    stroke.strokeWidth = wd
                }
                stroke.alpha = alphaAll
                canvas.drawLine(x[i], y[i], x[i + 1], y[i + 1], stroke)
            }
        }
    }

    private fun drawHud(canvas: Canvas, w: Float, alpha: Int) {
        val left = 16f * dp
        val top = 56f * dp
        val barW = w - 32f * dp - 84f * dp
        val barH = 12f * dp

        // 뒤 화면과 구분되도록 어두운 판
        fill.color = cDark; fill.alpha = (alpha * 0.78f).toInt()
        rect.set(left - 8f * dp, top - 26f * dp, w - 8f * dp, top + barH + 12f * dp)
        canvas.drawRoundRect(rect, 14f * dp, 14f * dp, fill)

        text.style = Paint.Style.FILL
        text.textAlign = Paint.Align.LEFT
        text.textSize = 13f * dp
        text.color = cInk; text.alpha = alpha
        canvas.drawText("$who 상태: $stage", left, top - 8f * dp, text)

        fill.color = Color.rgb(58, 48, 42); fill.alpha = alpha
        rect.set(left, top, left + barW, top + barH)
        canvas.drawRoundRect(rect, barH / 2, barH / 2, fill)
        fill.color = cAccent; fill.alpha = alpha
        rect.set(left, top, left + barW * (gauge / 100f).coerceIn(0f, 1f), top + barH)
        canvas.drawRoundRect(rect, barH / 2, barH / 2, fill)

        if (combo >= 2) {
            text.textAlign = Paint.Align.RIGHT
            text.textSize = 26f * dp
            text.color = cAccent; text.alpha = alpha
            canvas.drawText("x$combo", w - 20f * dp, top + barH, text)
        }
    }

    private fun drawBubble(canvas: Canvas, fade: Float) {
        val layout = bubble ?: return
        if (bubbleLife <= 0f) return
        val a = (255 * min(1f, bubbleLife * 4f) * fade).toInt()
        val rise = (1f - bubbleLife) * 30f * dp
        val bw = bubbleW + 28f * dp
        val bh = layout.height + 40f * dp
        val top = bubbleY - rise

        fill.color = cInk; fill.alpha = a
        rect.set(bubbleX, top, bubbleX + bw, top + bh)
        canvas.drawRoundRect(rect, 16f * dp, 16f * dp, fill)

        text.style = Paint.Style.FILL
        text.textAlign = Paint.Align.LEFT
        text.textSize = 11f * dp
        text.color = Color.rgb(138, 90, 43); text.alpha = a
        canvas.drawText(bubbleName, bubbleX + 14f * dp, top + 20f * dp, text)

        bubbleText.alpha = a
        canvas.save()
        canvas.translate(bubbleX + 14f * dp, top + 26f * dp)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawBanner(canvas: Canvas, w: Float, h: Float, fade: Float) {
        if (bannerLife <= 0f) return
        val a = (255 * min(1f, bannerLife * 3f) * fade).toInt()
        val grow = 0.7f + min(1f, (1f - bannerLife) * 8f) * 0.3f
        text.textAlign = Paint.Align.CENTER
        text.textSize = 30f * dp * grow
        text.style = Paint.Style.STROKE
        text.strokeWidth = 8f * dp
        text.color = cDark; text.alpha = a
        canvas.drawText(banner, w / 2f, h * 0.42f, text)
        text.style = Paint.Style.FILL
        text.color = cInk; text.alpha = a
        canvas.drawText(banner, w / 2f, h * 0.42f, text)
    }

    private fun drawNote(canvas: Canvas, w: Float, h: Float, fade: Float) {
        if (noteLife <= 0f) return
        val a = (255 * min(1f, noteLife * 4f) * fade).toInt()
        text.style = Paint.Style.FILL
        text.textAlign = Paint.Align.CENTER
        text.textSize = 13f * dp
        val tw = text.measureText(note)
        val cy = h - 120f * dp
        fill.color = cDark; fill.alpha = (a * 0.9f).toInt()
        rect.set(w / 2f - tw / 2f - 14f * dp, cy - 20f * dp, w / 2f + tw / 2f + 14f * dp, cy + 10f * dp)
        canvas.drawRoundRect(rect, 15f * dp, 15f * dp, fill)
        text.color = cInk; text.alpha = a
        canvas.drawText(note, w / 2f, cy, text)
    }
}
