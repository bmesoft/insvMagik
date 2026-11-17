package eu.bmesoft.insvmagik.media

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import eu.bmesoft.insvmagik.view.Mat3
import eu.bmesoft.insvmagik.view.Omnidirectional
import eu.bmesoft.insvmagik.view.Rig
import eu.bmesoft.insvmagik.view.ViewParams
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.tan

/**
 * The export's GPU half: the decoders draw each lens into a [SurfaceTexture], and one fragment
 * shader renders the flat view onto the encoder's input surface. The shader is the same maths as
 * [eu.bmesoft.insvmagik.view.Reframer] (which the unit tests check), for two lenses with the
 * omnidirectional model. Every call must come from the thread that created this object (EGL).
 */
class GlReframer(output: Surface, private val width: Int, private val height: Int, lensCount: Int) : AutoCloseable {
    private val display: EGLDisplay
    private val context: EGLContext
    private val surface: EGLSurface
    private val program: Int
    private val textures = IntArray(lensCount)
    private val frameThread = HandlerThread("lens-frames").apply { start() }
    private val arrived = BooleanArray(lensCount)
    private val lock = Object()

    /** One per lens: give `Surface(surfaceTextures[i])` to lens i's decoder. */
    val surfaceTextures: List<SurfaceTexture>

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "no EGL config" }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        surface = EGL14.eglCreateWindowSurface(display, configs[0], output, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }

        program = link(VERTEX, FRAGMENT)
        GLES20.glGenTextures(lensCount, textures, 0)
        val handler = Handler(frameThread.looper)
        surfaceTextures = textures.mapIndexed { i, tex ->
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            SurfaceTexture(tex).apply {
                setOnFrameAvailableListener({
                    synchronized(lock) {
                        arrived[i] = true
                        lock.notifyAll()
                    }
                }, handler)
            }
        }
    }

    /**
     * After lens [i]'s decoder released a frame to its surface: waits for it and latches it into
     * the texture. False on timeout.
     */
    fun latch(i: Int, timeoutMs: Long = 2_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (!arrived[i]) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return false
                lock.wait(left)
            }
            arrived[i] = false
        }
        surfaceTextures[i].updateTexImage()
        return true
    }

    /**
     * Renders the view. [lensSizes] are the decoded pictures' width × height (the rig's pixel
     * units); [present] says which lenses have a stream at all.
     */
    fun draw(rig: Rig, view: ViewParams, lensSizes: List<Pair<Int, Int>>, present: List<Boolean>) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        val viewRot = view.rotation()
        val st = FloatArray(16)
        for (i in 0 until 2) {
            val lens = rig.lenses.getOrNull(i)
            val on = lens != null && present.getOrElse(i) { false } && i < textures.size
            GLES20.glUniform1f(loc("uOn$i"), if (on) 1f else 0f)
            if (!on) {
                // Still bind something valid to the sampler.
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
                GLES20.glUniform1i(loc("uTex$i"), i)
                continue
            }
            val m = lens!!.model as? Omnidirectional ?: error("the export needs the omnidirectional lens model")
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[i])
            GLES20.glUniform1i(loc("uTex$i"), i)
            surfaceTextures[i].getTransformMatrix(st)
            GLES20.glUniformMatrix4fv(loc("uSt$i"), 1, false, st, 0)
            GLES20.glUniformMatrix3fv(loc("uRot$i"), 1, false, columnMajor(lens.toLens * viewRot), 0)
            GLES20.glUniform4f(loc("uIntr$i"), m.cx.toFloat(), m.cy.toFloat(), m.fx.toFloat(), m.fy.toFloat())
            GLES20.glUniform4f(loc("uDist$i"), m.k1.toFloat(), m.k2.toFloat(), m.k3.toFloat(), m.xi.toFloat())
            GLES20.glUniform2f(loc("uTan$i"), m.p1.toFloat(), m.p2.toFloat())
            GLES20.glUniform1f(loc("uMax$i"), m.maxAngle.toFloat())
            val (w, h) = lensSizes[i]
            GLES20.glUniform2f(loc("uSize$i"), w.toFloat(), h.toFloat())
        }
        GLES20.glUniform2f(loc("uOut"), width.toFloat(), height.toFloat())
        GLES20.glUniform1f(loc("uFocal"), (width / 2.0 / tan(Math.toRadians(view.fov.coerceIn(1.0, 179.0)) / 2)).toFloat())
        GLES20.glUniform1f(loc("uBlend"), Math.toRadians(max(view.blendDeg, 0.01)).toFloat())
        val pos = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, QUAD)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
        val err = GLES20.glGetError()
        check(err == GLES20.GL_NO_ERROR) { "GL error 0x%x".format(err) }
    }

    /** Hands the drawn frame to the encoder with its timestamp. */
    fun swap(ptsUs: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, ptsUs * 1000)
        check(EGL14.eglSwapBuffers(display, surface)) { "eglSwapBuffers failed" }
    }

    override fun close() {
        surfaceTextures.forEach { it.release() }
        GLES20.glDeleteTextures(textures.size, textures, 0)
        GLES20.glDeleteProgram(program)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
        frameThread.quitSafely()
    }

    private val locations = HashMap<String, Int>()
    private fun loc(name: String) = locations.getOrPut(name) { GLES20.glGetUniformLocation(program, name) }

    private fun columnMajor(m: Mat3) = FloatArray(9) { i -> m[i % 3, i / 3].toFloat() }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142

        val QUAD = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).apply { position(0) }

        const val VERTEX = """
attribute vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
"""

        /** Mirrors Reframer.render (FLAT) + Omnidirectional.project; see their comments. */
        const val FRAGMENT = """
#extension GL_OES_EGL_image_external : require
precision highp float;
uniform samplerExternalOES uTex0;
uniform samplerExternalOES uTex1;
uniform mat4 uSt0;
uniform mat4 uSt1;
uniform mat3 uRot0;
uniform mat3 uRot1;
uniform vec4 uIntr0;
uniform vec4 uIntr1;
uniform vec4 uDist0;
uniform vec4 uDist1;
uniform vec2 uTan0;
uniform vec2 uTan1;
uniform float uMax0;
uniform float uMax1;
uniform vec2 uSize0;
uniform vec2 uSize1;
uniform float uOn0;
uniform float uOn1;
uniform vec2 uOut;
uniform float uFocal;
uniform float uBlend;

vec2 project(vec3 d, vec4 intr, vec4 dist, vec2 tg) {
    vec2 m = d.xy / (d.z + dist.w);
    float r2 = dot(m, m);
    float radial = 1.0 + r2 * (dist.x + r2 * (dist.y + r2 * dist.z));
    vec2 dd = m * radial + vec2(
        2.0 * tg.x * m.x * m.y + tg.y * (r2 + 2.0 * m.x * m.x),
        tg.x * (r2 + 2.0 * m.y * m.y) + 2.0 * tg.y * m.x * m.y);
    return intr.zw * dd + intr.xy;
}

// Pixel (origin top-left) → texture coordinate through the SurfaceTexture's transform.
vec2 texCoord(vec2 px, vec2 size, mat4 st) {
    return (st * vec4(px.x / size.x, 1.0 - px.y / size.y, 0.0, 1.0)).xy;
}

void main() {
    vec2 p = vec2(gl_FragCoord.x, uOut.y - gl_FragCoord.y) - uOut * 0.5;
    vec3 dir = normalize(vec3(p, uFocal));
    vec3 d0 = uRot0 * dir;
    vec3 d1 = uRot1 * dir;
    float t0 = acos(clamp(d0.z, -1.0, 1.0));
    float t1 = acos(clamp(d1.z, -1.0, 1.0));
    bool in0 = uOn0 > 0.5 && t0 <= uMax0;
    bool in1 = uOn1 > 0.5 && t1 <= uMax1;
    if (!in0 && !in1) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
    float best = min(in0 ? t0 : 10.0, in1 ? t1 : 10.0);
    float w0 = in0 ? max(0.0, 1.0 - (t0 - best) / uBlend) * clamp((uMax0 - t0) / uBlend, 0.001, 1.0) : 0.0;
    float w1 = in1 ? max(0.0, 1.0 - (t1 - best) / uBlend) * clamp((uMax1 - t1) / uBlend, 0.001, 1.0) : 0.0;
    vec3 c = vec3(0.0);
    if (w0 > 0.0) c += w0 * texture2D(uTex0, texCoord(project(d0, uIntr0, uDist0, uTan0), uSize0, uSt0)).rgb;
    if (w1 > 0.0) c += w1 * texture2D(uTex1, texCoord(project(d1, uIntr1, uDist1, uTan1), uSize1, uSt1)).rgb;
    gl_FragColor = vec4(c / max(w0 + w1, 1e-6), 1.0);
}
"""
    }
}
