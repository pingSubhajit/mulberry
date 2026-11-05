package com.subhajit.mulberry.streak

import android.content.ContentResolver
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import androidx.annotation.RawRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.subhajit.mulberry.R
import com.subhajit.mulberry.core.ui.MulberryHaptic
import com.subhajit.mulberry.core.ui.performMulberryHaptic
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** When the pieces split, from the film's start. */
const val StreakBreakCrackMs = 1280L

/** When the shards have flown enough for the words below to follow. */
const val StreakBreakRevealMs = 1650L

/**
 * The bolt's share of its frame in the broken artwork, against the whole bolt's: the broken bolt
 * scales up by this to stand as tall as the whole one did.
 */
const val StreakBreakBrokenScale = 1.13f

/**
 * The streak bolt glinting, then breaking. It rests on its first frame, the whole bolt, until
 * [start], and ends on the broken bolt the static artwork is cut from, so handing back to that
 * picture shows no change.
 */
@Composable
fun StreakBreakFilm(
    start: Boolean,
    onCrack: () -> Unit,
    onReveal: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val crack by rememberUpdatedState(onCrack)
    val reveal by rememberUpdatedState(onReveal)
    val finish by rememberUpdatedState(onFinish)
    AndroidView(
        factory = { context ->
            AlphaFilmView(context, R.raw.streak_bolt_break).apply {
                moments = AlphaFilmView.Moments(
                    crack = {
                        performMulberryHaptic(MulberryHaptic.Reject)
                        crack()
                    },
                    reveal = { reveal() },
                    finish = { finish() }
                )
            }
        },
        update = { view -> if (start) view.play() },
        onRelease = { view -> view.release() },
        modifier = modifier
    )
}

/**
 * Plays a film whose left half is premultiplied colour and right half the alpha as grey, as
 * MediaCodec has no alpha of its own, putting the two back together in a shader over a clear
 * [TextureView].
 */
private class AlphaFilmView(context: Context, @RawRes private val film: Int) :
    TextureView(context), TextureView.SurfaceTextureListener {

    class Moments(val crack: () -> Unit, val reveal: () -> Unit, val finish: () -> Unit)

    var moments: Moments? = null
    private val main = Handler(Looper.getMainLooper())
    private var renderer: FilmRenderer? = null
    private var playRequested = false
    private var played = false

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun play() {
        playRequested = true
        startIfReady()
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        renderer?.release()
        renderer = null
    }

    private fun startIfReady() {
        val renderer = renderer ?: return
        if (!playRequested || played) return
        played = true
        renderer.play()
        main.postDelayed({ moments?.crack?.invoke() }, StreakBreakCrackMs)
        main.postDelayed({ moments?.reveal?.invoke() }, StreakBreakRevealMs)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val uri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(context.packageName)
            .appendPath(film.toString())
            .build()
        renderer = FilmRenderer(context.applicationContext, uri, surface, width, height) {
            main.post { moments?.finish?.invoke() }
        }
        startIfReady()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        renderer?.resize(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}

/** Decodes the film into an external texture and draws it, on its own thread. */
private class FilmRenderer(
    private val context: Context,
    private val uri: Uri,
    private val output: SurfaceTexture,
    private var width: Int,
    private var height: Int,
    private val onComplete: () -> Unit
) {
    private val thread = HandlerThread("StreakBreakFilm").apply { start() }
    private val handler = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private var frames: SurfaceTexture? = null
    private var frameSurface: Surface? = null
    private var player: MediaPlayer? = null
    private var prepared = false
    private var playWhenPrepared = false
    private val transform = FloatArray(16)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(QUAD).position(0) }

    init {
        handler.post { setUp() }
    }

    fun play() = handler.post {
        if (prepared) player?.start() else playWhenPrepared = true
    }

    fun resize(width: Int, height: Int) = handler.post {
        this.width = width
        this.height = height
        draw()
    }

    fun release() {
        handler.post {
            runCatching { player?.stop() }
            player?.release()
            player = null
            frameSurface?.release()
            frames?.release()
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, eglSurface)
                EGL14.eglDestroyContext(display, eglContext)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
            thread.quitSafely()
        }
    }

    private fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(display, version, 0, version, 1)
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(
            display,
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE
            ),
            0, configs, 0, 1, count, 0
        )
        val config = configs[0] ?: return
        eglContext = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        eglSurface = EGL14.eglCreateWindowSurface(display, config, output, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)

        program = buildProgram()
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texture = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val frames = SurfaceTexture(texture).also { this.frames = it }
        frames.setOnFrameAvailableListener({ draw() }, handler)
        val frameSurface = Surface(frames).also { this.frameSurface = it }
        // Clear until the first frame arrives.
        draw(clearOnly = true)

        player = MediaPlayer().apply {
            setSurface(frameSurface)
            setDataSource(context, uri)
            setOnPreparedListener { prepared ->
                this@FilmRenderer.prepared = true
                // Puts the whole bolt, the first frame, on screen while it waits.
                prepared.seekTo(0)
                if (playWhenPrepared) prepared.start()
            }
            setOnCompletionListener { onComplete() }
            prepareAsync()
        }
    }

    private fun draw(clearOnly: Boolean = false) {
        if (display == EGL14.EGL_NO_DISPLAY) return
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val frames = frames
        if (!clearOnly && frames != null) {
            frames.updateTexImage()
            frames.getTransformMatrix(transform)
            // Fits the film's 540 x 840 picture, centred, as the static artwork fits its frame.
            val filmAspect = FILM_WIDTH / FILM_HEIGHT
            val viewAspect = width.toFloat() / height.coerceAtLeast(1)
            val (drawWidth, drawHeight) = if (viewAspect > filmAspect) {
                (height * filmAspect).toInt() to height
            } else {
                width to (width / filmAspect).toInt()
            }
            GLES20.glViewport((width - drawWidth) / 2, (height - drawHeight) / 2, drawWidth, drawHeight)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTransform"), 1, false, transform, 0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uFrame"), 0)
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            quad.position(0)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(position)
            val uv = GLES20.glGetAttribLocation(program, "aUv")
            quad.position(2)
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(uv)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    private fun buildProgram(): Int {
        fun shader(type: Int, source: String): Int = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, source)
            GLES20.glCompileShader(it)
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER))
            GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
            GLES20.glLinkProgram(it)
        }
    }

    private companion object {
        const val FILM_WIDTH = 540f
        const val FILM_HEIGHT = 840f

        /** x, y, u, v for a strip covering the viewport. */
        val QUAD = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f
        )

        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aUv;
            uniform mat4 uTransform;
            varying vec2 vColourUv;
            varying vec2 vAlphaUv;
            void main() {
                gl_Position = aPosition;
                vColourUv = (uTransform * vec4(aUv.x * 0.5, aUv.y, 0.0, 1.0)).xy;
                vAlphaUv = (uTransform * vec4(0.5 + aUv.x * 0.5, aUv.y, 0.0, 1.0)).xy;
            }
        """

        // The colour is premultiplied, as TextureView composites it; compression can leave it a
        // touch brighter than its alpha allows, which would glow, so it's held to the alpha.
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uFrame;
            varying vec2 vColourUv;
            varying vec2 vAlphaUv;
            void main() {
                float alpha = texture2D(uFrame, vAlphaUv).r;
                vec3 colour = min(texture2D(uFrame, vColourUv).rgb, vec3(alpha));
                gl_FragColor = vec4(colour, alpha);
            }
        """
    }
}
