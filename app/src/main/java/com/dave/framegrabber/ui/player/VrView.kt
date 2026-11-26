package com.dave.framegrabber.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.WindowManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * VR video: the picture is wrapped around you on the inside of a sphere (360°) or a half-sphere
 * (180°); turn the phone (gyroscope) or drag to look around, pinch to zoom.
 *
 * Layouts: «2 картинки рядом» (SBS, the usual 18+ VR: one picture per eye — we show the left one,
 * or both in «очки» mode for a cardboard headset), «верх-низ» (TB), or a single picture (mono).
 */
class VrView(ctx: Context) : GLSurfaceView(ctx), SensorEventListener {

    enum class Fov { F180, F360 }
    enum class Layout { MONO, SBS, TB }

    @Volatile var fov = Fov.F180
    @Volatile var layout = Layout.SBS
    /** Cardboard: two views side by side, one per eye (true 3D for SBS / TB videos). */
    @Volatile var stereo = false
    /** Use the gyroscope (off = only drag). */
    @Volatile var gyro = true

    /** Called on the main thread with the Surface to give to the MediaPlayer. */
    var onSurface: ((Surface) -> Unit)? = null
    /** A single tap (show / hide the controls). */
    var onTap: (() -> Unit)? = null

    private val renderer = R()
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotSensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val main = Handler(Looper.getMainLooper())

    /** Eye rotation from the sensor (column-major, ready for GL), relative to the "centre" pose. */
    private val sensorM = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    @Volatile private var hasSensor = false
    /** Drag offsets (degrees). */
    @Volatile private var yaw = 0f
    @Volatile private var pitch = 0f
    @Volatile private var zoomFov = 90f

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun start() {
        onResume()
        if (gyro) rotSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sm.unregisterListener(this)
        onPause()
    }

    fun setGyroEnabled(on: Boolean) {
        gyro = on
        sm.unregisterListener(this)
        hasSensor = false
        if (on) { needRef = true; rotSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    }

    /** Where the phone points now becomes the middle of the video. */
    fun recenter() {
        yaw = 0f; pitch = 0f
        needRef = true
    }

    val hasGyro get() = rotSensor != null

    // ================================================================ sensor
    //
    // The phone's rotation now relative to the moment VR was switched on (or «По центру»):
    // that pose looks straight at the middle of the video, every turn of the phone turns the view.
    // Works the same on every phone (no compass, no assumptions about how the phone is held).

    private val now = FloatArray(9)
    private val ref = FloatArray(9)
    @Volatile private var needRef = true

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(now, e.values)   // device -> world, row-major
        if (needRef) { System.arraycopy(now, 0, ref, 0, 9); needRef = false }
        // rel = now^T * ref : reference-device coordinates -> current-device coordinates
        val rel = FloatArray(9)
        for (r in 0..2) for (c in 0..2) {
            var sum = 0f
            for (k in 0..2) sum += now[k * 3 + r] * ref[k * 3 + c]
            rel[r * 3 + c] = sum
        }
        // device axes -> screen axes (x right, y up, z towards you) for the current rotation
        @Suppress("DEPRECATION")
        val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        val cm = when (rotation) {
            Surface.ROTATION_90 -> floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            Surface.ROTATION_270 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
            Surface.ROTATION_180 -> floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f)
            else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        }
        // view = C * rel * C^T
        val t = mul(mul(cm, rel), transpose(cm))
        synchronized(sensorM) {
            Matrix.setIdentityM(sensorM, 0)
            for (r in 0..2) for (c in 0..2) sensorM[c * 4 + r] = t[r * 3 + c]   // column-major for GL
        }
        hasSensor = true
    }

    private fun mul(a: FloatArray, b: FloatArray) = FloatArray(9).also { o ->
        for (r in 0..2) for (c in 0..2) { var s = 0f; for (k in 0..2) s += a[r * 3 + k] * b[k * 3 + c]; o[r * 3 + c] = s }
    }
    private fun transpose(a: FloatArray) = FloatArray(9) { a[(it % 3) * 3 + it / 3] }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    // ================================================================ touch: drag to look, pinch to zoom

    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomFov = (zoomFov / d.scaleFactor).coerceIn(40f, 120f)
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; moved = false }
            MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && !scaler.isInProgress) {
                val dx = e.x - lastX; val dy = e.y - lastY
                if (Math.abs(dx) + Math.abs(dy) > 3) moved = true
                val k = zoomFov / width.coerceAtLeast(1)
                yaw -= dx * k
                pitch = (pitch - dy * k).coerceIn(-85f, 85f)
                lastX = e.x; lastY = e.y
            } else moved = true
            MotionEvent.ACTION_UP -> if (!moved) onTap?.invoke()
        }
        return true
    }

    // ================================================================ GL

    private inner class R : Renderer, SurfaceTexture.OnFrameAvailableListener {
        private var program = 0
        private var tex = 0
        private var st: SurfaceTexture? = null
        private val stM = FloatArray(16)
        private val proj = FloatArray(16)
        private val view = FloatArray(16)
        private val mvp = FloatArray(16)
        private val tmp = FloatArray(16)
        private var vb: FloatBuffer? = null
        private var ib: ShortBuffer? = null
        private var indexCount = 0
        private var meshFov: Fov? = null
        private var w = 1; private var h = 1

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = link(VS, FS)
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            tex = t[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val s = SurfaceTexture(tex)
            s.setOnFrameAvailableListener(this)
            st = s
            val surface = Surface(s)
            main.post { onSurface?.invoke(surface) }
            Matrix.setIdentityM(stM, 0)
        }

        override fun onFrameAvailable(s: SurfaceTexture?) {}

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { w = width; h = height }

        override fun onDrawFrame(gl: GL10?) {
            st?.let { runCatching { it.updateTexImage(); it.getTransformMatrix(stM) } }
            if (meshFov != fov) buildMesh(fov)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)

            // the eye: sensor (if any) + drag, minus the recenter yaw
            val base = FloatArray(16)
            if (hasSensor && gyro) synchronized(sensorM) { System.arraycopy(sensorM, 0, base, 0, 16) } else Matrix.setIdentityM(base, 0)
            Matrix.setIdentityM(view, 0)
            Matrix.rotateM(view, 0, pitch, 1f, 0f, 0f)
            Matrix.multiplyMM(tmp, 0, view, 0, base, 0)
            Matrix.rotateM(tmp, 0, yaw, 0f, 1f, 0f)
            System.arraycopy(tmp, 0, view, 0, 16)

            if (stereo) {
                drawEye(0, 0, w / 2, h, eye = 0)
                drawEye(w / 2, 0, w - w / 2, h, eye = 1)
            } else drawEye(0, 0, w, h, eye = 0)
        }

        private fun drawEye(x: Int, y: Int, vw: Int, vh: Int, eye: Int) {
            GLES20.glViewport(x, y, vw, vh)
            Matrix.perspectiveM(proj, 0, zoomFov, vw.toFloat() / vh.coerceAtLeast(1), 0.1f, 100f)
            Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
            // which part of the frame this eye sees
            val (uOff, uScale, vOff, vScale) = when (layout) {
                Layout.MONO -> listOf(0f, 1f, 0f, 1f)
                Layout.SBS -> listOf(if (eye == 0) 0f else 0.5f, 0.5f, 0f, 1f)
                Layout.TB -> listOf(0f, 1f, if (eye == 0) 0f else 0.5f, 0.5f)
            }
            val p = program
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(p, "uMvp"), 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(p, "uSt"), 1, false, stM, 0)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(p, "uCrop"), uOff, uScale, vOff, vScale)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uTex"), 0)
            val aPos = GLES20.glGetAttribLocation(p, "aPos")
            val aUv = GLES20.glGetAttribLocation(p, "aUv")
            val v = vb ?: return
            v.position(0)
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 20, v)
            GLES20.glEnableVertexAttribArray(aPos)
            v.position(3)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 20, v)
            GLES20.glEnableVertexAttribArray(aUv)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, ib)
        }

        /** Inside of a sphere (360°) or its front half (180°); u,v = 0…1 over that part of the frame. */
        private fun buildMesh(f: Fov) {
            val rows = 48; val cols = 96
            val lonSpan = if (f == Fov.F360) 2 * Math.PI else Math.PI
            val verts = FloatArray((rows + 1) * (cols + 1) * 5)
            var k = 0
            for (r in 0..rows) {
                val v = r.toFloat() / rows
                val lat = Math.PI / 2 - v * Math.PI          // top -> bottom
                for (c in 0..cols) {
                    val u = c.toFloat() / cols
                    val lon = -lonSpan / 2 + u * lonSpan       // left -> right, 0 = straight ahead
                    verts[k++] = (50 * cos(lat) * sin(lon)).toFloat()
                    verts[k++] = (50 * sin(lat)).toFloat()
                    verts[k++] = (-50 * cos(lat) * cos(lon)).toFloat()
                    verts[k++] = u
                    verts[k++] = v
                }
            }
            val idx = ShortArray(rows * cols * 6)
            k = 0
            for (r in 0 until rows) for (c in 0 until cols) {
                val a = (r * (cols + 1) + c).toShort()
                val b = (a + cols + 1).toShort()
                idx[k++] = a; idx[k++] = b; idx[k++] = (a + 1).toShort()
                idx[k++] = (a + 1).toShort(); idx[k++] = b; idx[k++] = (b + 1).toShort()
            }
            vb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(verts); position(0) }
            ib = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(idx); position(0) }
            indexCount = idx.size
            meshFov = f
        }

        private fun link(vs: String, fs: String): Int {
            fun sh(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, sh(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, sh(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            return p
        }
    }

    companion object {
        private const val VS = """
            uniform mat4 uMvp;
            uniform mat4 uSt;
            uniform vec4 uCrop;   // uOffset, uScale, vOffset, vScale (v from the top of the frame)
            attribute vec4 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                gl_Position = uMvp * aPos;
                float u = uCrop.x + aUv.x * uCrop.y;
                float v = uCrop.z + aUv.y * uCrop.w;
                // SurfaceTexture coordinates start at the bottom
                vUv = (uSt * vec4(u, 1.0 - v, 0.0, 1.0)).xy;
            }
        """
        private const val FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uTex, vUv); }
        """
    }
}
