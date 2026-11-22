package com.example.inbetween

import android.content.Context
import android.graphics.SurfaceTexture
import android.graphics.PixelFormat
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.exoplayer.ExoPlayer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * First GPU-rendered media stage. Media3/MediaCodec decodes into an external
 * OES texture and OpenGL draws that texture directly into a transparent
 * SurfaceView. Drawing remains in the existing Compose renderer for now.
 *
 * The GL thread owns the OES texture and SurfaceTexture. Player surface calls
 * are posted to the Media3 application (main) thread.
 */
class GlVideoSurfaceView(context: Context, private val player: ExoPlayer) : GLSurfaceView(context), GLSurfaceView.Renderer {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameAvailable = AtomicBoolean(false)
    @Volatile private var released = false
    private var oesTexture = 0
    private var decoderTexture: SurfaceTexture? = null
    private var decoderSurface: Surface? = null
    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var matrixHandle = 0
    private var samplerHandle = 0
    private var opacityHandle = 0
    private val textureMatrix = FloatArray(16)
    private lateinit var vertices: FloatBuffer
    private lateinit var texCoords: FloatBuffer
    @Volatile private var quadNdc = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    @Volatile private var videoOpacity = 1f

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        // Keep this media surface below the app window so Compose drawing,
        // handles and touch feedback remain above it.
        setZOrderMediaOverlay(true)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
        preserveEGLContextOnPause = true
        val v = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        // SurfaceTexture.getTransformMatrix() already supplies the producer's
        // texture-coordinate transform (including the usual vertical inversion
        // for decoder output). Keep these as canonical, unflipped UVs and apply
        // the SurfaceTexture matrix exactly once in the vertex shader.
        val t = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        vertices = directBuffer(v)
        texCoords = directBuffer(t)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        onResume()
    }

    override fun onDetachedFromWindow() {
        onPause()
        super.onDetachedFromWindow()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        if (released) return
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glColorMask(true, true, true, true)
        oesTexture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        matrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
        samplerHandle = GLES20.glGetUniformLocation(program, "uTexture")
        opacityHandle = GLES20.glGetUniformLocation(program, "uOpacity")

        val st = SurfaceTexture(oesTexture)
        decoderTexture = st
        st.setOnFrameAvailableListener {
            frameAvailable.set(true)
            if (!released) requestRender()
        }
        val surface = Surface(st)
        decoderSurface = surface
        mainHandler.post {
            if (!released) player.setVideoSurface(surface)
        }
    }

    /** Set the video quad in screen-space NDC. SurfaceView ignores parent graphicsLayer transforms,
     * so the complete canvas + layer transform is baked into these GPU vertex positions.
     */
    fun setScreenQuad(pointsNdc: FloatArray, opacity: Float = 1f) {
        if (pointsNdc.size != 8) return
        quadNdc = pointsNdc.copyOf()
        videoOpacity = opacity.coerceIn(0f, 1f)
        requestRender()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val st = decoderTexture ?: return
        if (frameAvailable.compareAndSet(true, false)) {
            try { st.updateTexImage() } catch (_: RuntimeException) { return }
        }
        st.getTransformMatrix(textureMatrix)
        GLES20.glUseProgram(program)
        vertices.clear()
        vertices.put(quadNdc)
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertices)
        texCoords.position(0)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, textureMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glUniform1i(samplerHandle, 0)
        GLES20.glUniform1f(opacityHandle, videoOpacity)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    fun releaseRenderer() {
        if (released) return
        released = true
        val surface = decoderSurface
        val clearOutput = { if (surface != null) runCatching { player.clearVideoSurface(surface) } }
        if (Looper.myLooper() == Looper.getMainLooper()) clearOutput() else mainHandler.post { clearOutput() }
        try {
            queueEvent {
                try { decoderTexture?.setOnFrameAvailableListener(null) } catch (_: Throwable) { }
                try { decoderTexture?.release() } catch (_: Throwable) { }
                try { decoderSurface?.release() } catch (_: Throwable) { }
                decoderTexture = null
                decoderSurface = null
                if (oesTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTexture), 0)
                if (program != 0) GLES20.glDeleteProgram(program)
                oesTexture = 0
                program = 0
            }
        } catch (_: IllegalStateException) { /* GL thread already detached */ }
    }

    private fun directBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val error = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Video compositor shader compilation failed: $error")
        }
        return shader
    }

    private fun createProgram(vertex: String, fragment: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val error = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw IllegalStateException("Video compositor program link failed: $error")
        }
        return p
    }

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform float uOpacity;
            varying vec2 vTexCoord;
            void main() {
                vec4 color = texture2D(uTexture, vTexCoord);
                gl_FragColor = vec4(color.rgb, color.a * uOpacity);
            }
        """
    }
}
