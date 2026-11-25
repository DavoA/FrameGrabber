package com.dave.framegrabber

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animated synthwave background:
 * sunset sky + twinkling stars + striped sun + a green low-poly "digital" triangle landscape
 * that waves and rolls toward the viewer + swaying palm tree silhouettes.
 * Drawn every frame with drawVertices/drawLines; pauses when the activity is paused.
 */
class SynthwaveView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    // ---- palette (green is the main color; sunset tones only near the horizon)
    private val skyTop = Color.parseColor("#04120B")
    private val skyMid = Color.parseColor("#0B3A2A")
    private val skyLow = Color.parseColor("#8A1F6A")
    private val skyHorizon = Color.parseColor("#FF7A3D")
    private val neonGreen = Color.parseColor("#5CF5AE")
    private val palmColor = Color.parseColor("#03100A")

    // ---- terrain mesh
    private val cols = 25            // vertices per row (odd -> a center line)
    private val rows = 18            // rows from horizon to bottom
    private val verts = FloatArray(cols * (rows + 1) * 2)
    private val vColors = IntArray(cols * (rows + 1))
    private val indices: ShortArray
    private val lines: FloatArray

    private val skyPaint = Paint()
    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sunGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val meshPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val groundPaint = Paint().apply { color = Color.parseColor("#05150E") }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val palmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palmColor }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#55FF2E97")
    }

    private class Star(val x: Float, val y: Float, val r: Float, val speed: Float, val phase: Float)
    private var stars = emptyList<Star>()

    private class Palm(val xFrac: Float, val hFrac: Float, val lean: Float, val phase: Float)
    private val palms = listOf(
        Palm(0.04f, 0.42f, 0.22f, 0f),
        Palm(0.19f, 0.27f, 0.12f, 1.7f),
        Palm(0.93f, 0.46f, -0.24f, 0.9f),
        Palm(0.80f, 0.24f, -0.10f, 2.6f),
    )
    private val trunk = Path()
    private val leaf = Path()

    private var horizon = 0f
    /** Animation clock: only advances while animating, so pausing/resuming doesn't jump. */
    private var animTime = 0f
    private var lastFrameNs = 0L

    /** False while the screen is paused. */
    var running = true
        set(v) { field = v; lastFrameNs = 0L; if (v) postInvalidateOnAnimation() }

    /**
     * True while a list on top is scrolling: the background freezes so all the GPU time goes to the
     * scroll (the frozen picture is just re-composited, which is nearly free).
     */
    var scrolling = false
        set(v) {
            if (field == v) return
            field = v; lastFrameNs = 0L
            if (!v) postInvalidateOnAnimation()
        }

    private val animating get() = running && !scrolling && android.animation.ValueAnimator.areAnimatorsEnabled()

    init {
        // triangle indices: two triangles per quad
        val idx = ArrayList<Short>()
        for (j in 0 until rows) for (i in 0 until cols - 1) {
            val a = j * cols + i; val b = a + 1; val c = a + cols; val d = c + 1
            idx += a.toShort(); idx += c.toShort(); idx += b.toShort()
            idx += b.toShort(); idx += c.toShort(); idx += d.toShort()
        }
        indices = idx.toShortArray()
        // horizontal + vertical + diagonal segments
        val n = (rows + 1) * (cols - 1) + rows * cols + rows * (cols - 1)
        lines = FloatArray(n * 4)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        horizon = h * 0.60f
        skyPaint.shader = LinearGradient(0f, 0f, 0f, horizon,
            intArrayOf(skyTop, skyMid, skyLow, skyHorizon), floatArrayOf(0f, 0.45f, 0.82f, 1f), Shader.TileMode.CLAMP)
        val sunR = min(w, h) * 0.24f
        val sunCy = horizon - sunR * 0.30f
        sunPaint.shader = LinearGradient(0f, sunCy - sunR, 0f, sunCy + sunR,
            Color.parseColor("#FFE66D"), Color.parseColor("#FF2E97"), Shader.TileMode.CLAMP)
        sunGlowPaint.shader = RadialGradient(w / 2f, sunCy, sunR * 2.2f,
            Color.parseColor("#66FF4FA0"), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        horizonPaint.shader = LinearGradient(0f, horizon - 6f, 0f, horizon + 30f,
            Color.parseColor("#CCFF4FA0"), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        // grid lines fade into the haze near the horizon
        linePaint.shader = LinearGradient(0f, horizon, 0f, h.toFloat(),
            Color.argb(40, 92, 245, 174), neonGreen, Shader.TileMode.CLAMP)
        glowPaint.shader = LinearGradient(0f, horizon, 0f, h.toFloat(),
            Color.TRANSPARENT, Color.argb(70, 92, 245, 174), Shader.TileMode.CLAMP)
        val d = resources.displayMetrics.density
        linePaint.strokeWidth = 1.2f * d
        glowPaint.strokeWidth = 4.5f * d
        rimPaint.strokeWidth = 1.2f * d

        val rnd = Random(7)
        stars = List(70) {
            Star(rnd.nextFloat() * w, rnd.nextFloat() * horizon * 0.8f, (0.6f + rnd.nextFloat() * 1.4f) * d,
                0.6f + rnd.nextFloat() * 2f, rnd.nextFloat() * 6.28f)
        }
    }

    /** Deterministic pseudo-random value in 0..1 for a grid point (makes the low-poly "facets"). */
    private fun hash(i: Int, j: Int): Float {
        var h = i * 374761393 + j * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFF) / 65535f
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w == 0f) return
        val now = System.nanoTime()
        if (animating && lastFrameNs != 0L) animTime += ((now - lastFrameNs) / 1e9f).coerceAtMost(0.05f)
        lastFrameNs = now
        val t = animTime

        // ---- sky
        c.drawRect(0f, 0f, w, horizon, skyPaint)
        for (s in stars) {
            starPaint.alpha = (120 + 135 * sin(t * s.speed + s.phase)).toInt().coerceIn(20, 255)
            c.drawCircle(s.x, s.y, s.r, starPaint)
        }

        // ---- sun with scrolling stripes cut out (stripes painted with the sky gradient)
        val sunR = min(w, h) * 0.24f
        val sunCx = w / 2f
        val sunCy = horizon - sunR * 0.30f
        c.drawCircle(sunCx, sunCy, sunR * 2.2f, sunGlowPaint)
        c.save()
        c.clipRect(0f, 0f, w, horizon)
        c.drawCircle(sunCx, sunCy, sunR, sunPaint)
        val band = sunR * 0.16f
        val scroll = (t * 10f) % band
        var y = sunCy + scroll
        var k = 0
        while (y < sunCy + sunR) {
            val thick = 1.5f + k * 1.6f * resources.displayMetrics.density
            c.drawRect(sunCx - sunR, y, sunCx + sunR, y + thick, skyPaint)
            y += band; k++
        }
        c.restore()

        // ---- ground base + horizon glow
        c.drawRect(0f, horizon, w, h, groundPaint)

        // ---- terrain mesh (rolls toward the viewer, waves over time)
        val roll = t * 0.55f
        val f = roll - floor(roll)
        val base = floor(roll).toInt()
        val half = (cols - 1) / 2
        for (j in 0..rows) {
            val p = ((j + f) / rows).coerceIn(0f, 1.05f)       // 0 = horizon, 1 = bottom
            val zi = j - base
            val py = horizon + (h - horizon) * p * p * 1.08f
            val spread = w * (0.05f + 1.25f * p)
            for (i in 0 until cols) {
                val xi = i - half
                val side = abs(xi) / half.toFloat()               // 0 center .. 1 edges
                val amp = (h - horizon) * 0.35f * p * (0.08f + side * side * 0.95f)
                val wave = 0.5f + 0.5f * sin(xi * 0.9f + zi * 0.7f + t * 1.1f) * cos(zi * 0.45f - t * 0.6f)
                val hh = amp * (0.35f * wave + 0.65f * hash(xi, zi))
                val v = j * cols + i
                verts[v * 2] = w / 2f + xi * spread / half
                verts[v * 2 + 1] = py - hh
                // facet shading: higher points glow more
                val lit = (hh / ((h - horizon) * 0.35f + 1f)).coerceIn(0f, 1f)
                val fade = p.coerceIn(0f, 1f)
                vColors[v] = Color.argb(
                    (180 + 60 * fade).toInt(),
                    (4 + 18 * lit).toInt(), (22 + 70 * lit * fade).toInt(), (14 + 34 * lit).toInt()
                )
            }
        }
        c.drawVertices(Canvas.VertexMode.TRIANGLES, verts.size, verts, 0, null, 0,
            vColors, 0, indices, 0, indices.size, meshPaint)

        var n = 0
        fun seg(a: Int, b: Int) {
            lines[n++] = verts[a * 2]; lines[n++] = verts[a * 2 + 1]
            lines[n++] = verts[b * 2]; lines[n++] = verts[b * 2 + 1]
        }
        for (j in 0..rows) for (i in 0 until cols - 1) seg(j * cols + i, j * cols + i + 1)
        for (j in 0 until rows) for (i in 0 until cols) seg(j * cols + i, (j + 1) * cols + i)
        for (j in 0 until rows) for (i in 0 until cols - 1) seg(j * cols + i + 1, (j + 1) * cols + i)
        c.drawLines(lines, 0, n, glowPaint)
        c.drawLines(lines, 0, n, linePaint)
        c.drawRect(0f, horizon - 6f, w, horizon + 30f, horizonPaint)

        // ---- palms
        for (p in palms) drawPalm(c, w * p.xFrac, h, h * p.hFrac, p.lean, t + p.phase)

        // Respect the system "remove animations" / battery saver setting: then it's a still picture.
        if (animating) postInvalidateOnAnimation()
    }

    /** Curved trunk + drooping fronds that sway a little. */
    private fun drawPalm(c: Canvas, x: Float, baseY: Float, height: Float, lean: Float, t: Float) {
        val topX = x + lean * height
        val topY = baseY - height
        val bw = height * 0.045f
        val tw = height * 0.022f
        trunk.reset()
        trunk.moveTo(x - bw, baseY)
        trunk.quadTo(x + lean * height * 0.15f - bw, baseY - height * 0.55f, topX - tw, topY)
        trunk.lineTo(topX + tw, topY)
        trunk.quadTo(x + lean * height * 0.15f + bw, baseY - height * 0.55f, x + bw, baseY)
        trunk.close()
        c.drawPath(trunk, palmPaint)
        c.drawPath(trunk, rimPaint)

        val sway = sin(t * 0.9f) * 4f
        val len = height * 0.42f
        c.save()
        c.translate(topX, topY)
        c.rotate(sway)
        val angles = floatArrayOf(-170f, -140f, -112f, -80f, -50f, -20f, 10f)
        for ((k, a) in angles.withIndex()) {
            val rad = Math.toRadians(a.toDouble())
            val droop = len * (0.25f + 0.12f * (k % 2))
            val tipX = (cos(rad) * len).toFloat()
            val tipY = (sin(rad) * len * 0.55f).toFloat() + droop
            val cx = (cos(rad) * len * 0.5f).toFloat()
            val cy = (sin(rad) * len * 0.5f).toFloat() - len * 0.12f
            val wdt = len * 0.09f
            leaf.reset()
            leaf.moveTo(0f, 0f)
            leaf.quadTo(cx, cy - wdt, tipX, tipY)
            leaf.quadTo(cx, cy + wdt, 0f, 0f)
            leaf.close()
            c.drawPath(leaf, palmPaint)
            c.drawPath(leaf, rimPaint)
        }
        c.drawCircle(-len * 0.03f, len * 0.05f, len * 0.05f, palmPaint)
        c.drawCircle(len * 0.05f, len * 0.07f, len * 0.045f, palmPaint)
        c.restore()
    }
}
