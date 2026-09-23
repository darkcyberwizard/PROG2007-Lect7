package com.example.lect7arcv

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.view.MotionEvent
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ArrayBlockingQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArRenderer(
    private val onAnchorPlaced: (Anchor) -> Unit,
    private val onNoFloorHit: () -> Unit,
    private val onTrackingUpdate: (TrackingState, Boolean) -> Unit // (trackingState, floorVisible)
) : GLSurfaceView.Renderer {

    @Volatile
    var session: Session? = null

    @Volatile
    private var placedAnchor: Anchor? = null

    fun onTap(event: MotionEvent) {
        tapQueue.offer(event)
    }

    fun clearAnchor() {
        placedAnchor?.detach()
        placedAnchor = null
    }

    private val tapQueue = ArrayBlockingQueue<MotionEvent>(4)
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var frameCount = 0

    // --- Camera passthrough background ---
    private var cameraTextureId = -1
    private var textureBound = false
    private var bgProgram = 0
    private var bgPositionHandle = 0
    private var bgTexCoordHandle = 0
    private val quadCoords = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private lateinit var quadCoordsBuffer: FloatBuffer
    private val quadTexCoordsTransformed = FloatArray(8)
    private lateinit var quadTexCoordsTransformedBuffer: FloatBuffer

    private val bgVertexShaderCode = """
        attribute vec4 a_Position;
        attribute vec2 a_TexCoord;
        varying vec2 v_TexCoord;
        void main() {
            gl_Position = a_Position;
            v_TexCoord = a_TexCoord;
        }
    """.trimIndent()

    private val bgFragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 v_TexCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, v_TexCoord);
        }
    """.trimIndent()

    // --- Package box ---
    private var boxProgram = 0
    private var boxPositionHandle = 0
    private var boxColorHandle = 0
    private var boxMvpHandle = 0
    private lateinit var boxVertexBuffer: FloatBuffer
    private val boxVertexCount = 36
    private val boxStrideBytes = 7 * 4

    private val boxVertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 vPosition;
        attribute vec4 vColor;
        varying vec4 fColor;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
            fColor = vColor;
        }
    """.trimIndent()

    private val boxFragmentShaderCode = """
        precision mediump float;
        varying vec4 fColor;
        void main() {
            gl_FragColor = fColor;
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        cameraTextureId = createExternalTexture()
        textureBound = false

        quadCoordsBuffer = toFloatBuffer(quadCoords)
        quadTexCoordsTransformedBuffer = toFloatBuffer(quadTexCoordsTransformed)

        bgProgram = buildProgram(bgVertexShaderCode, bgFragmentShaderCode)
        bgPositionHandle = GLES20.glGetAttribLocation(bgProgram, "a_Position")
        bgTexCoordHandle = GLES20.glGetAttribLocation(bgProgram, "a_TexCoord")

        boxProgram = buildProgram(boxVertexShaderCode, boxFragmentShaderCode)
        boxPositionHandle = GLES20.glGetAttribLocation(boxProgram, "vPosition")
        boxColorHandle = GLES20.glGetAttribLocation(boxProgram, "vColor")
        boxMvpHandle = GLES20.glGetUniformLocation(boxProgram, "uMVPMatrix")

        boxVertexBuffer = toFloatBuffer(cubeVertices)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        surfaceWidth = width
        surfaceHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        val currentSession = session
        if (currentSession == null) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            return
        }

        // Assumes the app is portrait-locked (rotation 0). If you ever allow
        // rotation, this needs the real display rotation from WindowManager.
        currentSession.setDisplayGeometry(0, surfaceWidth, surfaceHeight)

        if (!textureBound) {
            currentSession.setCameraTextureName(cameraTextureId)
            textureBound = true
        }

        val frame: Frame = try {
            currentSession.update()
        } catch (e: Exception) {
            // Covers CameraNotAvailableException, SessionPausedException, and anything
            // else ARCore can throw when paused/closing while this GL thread is mid-frame.
            Log.w("ArRenderer", "session.update() skipped: ${e.javaClass.simpleName}: ${e.message}")
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            return
        }

        if (frame.hasDisplayGeometryChanged() || frameCount == 0) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadCoords,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexCoordsTransformed
            )
            quadTexCoordsTransformedBuffer = toFloatBuffer(quadTexCoordsTransformed)
        }
        frameCount++

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        drawCameraBackground()

        val camera = frame.camera
        val trackingState = camera.trackingState

        var floorVisible = false
        if (trackingState == TrackingState.TRACKING) {
            for (plane in currentSession.getAllTrackables(Plane::class.java)) {
                if (plane.trackingState == TrackingState.TRACKING &&
                    plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING
                ) {
                    floorVisible = true
                    break
                }
            }
        }
        onTrackingUpdate(trackingState, floorVisible)

        val tap = tapQueue.poll()
        if (tap != null && trackingState == TrackingState.TRACKING) {
            // Only accept the tap if it landed on an upward-facing horizontal plane —
            // i.e. a floor or tabletop, never a wall or ceiling.
            val hit = frame.hitTest(tap).firstOrNull { result ->
                val trackable = result.trackable
                trackable is Plane &&
                        trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                        trackable.isPoseInPolygon(result.hitPose)
            }
            if (hit != null) {
                placedAnchor?.detach()
                val anchor = hit.createAnchor()
                placedAnchor = anchor
                onAnchorPlaced(anchor)
            } else {
                onNoFloorHit()
            }
        }

        val anchor = placedAnchor
        if (anchor != null && anchor.trackingState == TrackingState.TRACKING) {
            drawBoxAt(anchor, camera)
        }
    }

    private fun drawCameraBackground() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        GLES20.glUseProgram(bgProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)

        quadCoordsBuffer.position(0)
        GLES20.glVertexAttribPointer(bgPositionHandle, 2, GLES20.GL_FLOAT, false, 0, quadCoordsBuffer)
        GLES20.glEnableVertexAttribArray(bgPositionHandle)

        quadTexCoordsTransformedBuffer.position(0)
        GLES20.glVertexAttribPointer(bgTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, quadTexCoordsTransformedBuffer)
        GLES20.glEnableVertexAttribArray(bgTexCoordHandle)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(bgPositionHandle)
        GLES20.glDisableVertexAttribArray(bgTexCoordHandle)

        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun drawBoxAt(anchor: Anchor, camera: Camera) {
        val viewMatrix = FloatArray(16)
        val projMatrix = FloatArray(16)
        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100f)

        val anchorMatrix = FloatArray(16)
        anchor.pose.toMatrix(anchorMatrix, 0)

        // Lift it by half the (scaled) box height so it sits ON the floor rather
        // than being centered on/through it, then shrink it to a reasonable size.
        val model = FloatArray(16)
        Matrix.translateM(model, 0, anchorMatrix, 0, 0f, 0.125f, 0f)
        Matrix.scaleM(model, 0, model, 0, 0.25f, 0.25f, 0.25f)

        val mvMatrix = FloatArray(16)
        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, model, 0)
        val mvpMatrix = FloatArray(16)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, mvMatrix, 0)

        GLES20.glUseProgram(boxProgram)

        boxVertexBuffer.position(0)
        GLES20.glVertexAttribPointer(boxPositionHandle, 3, GLES20.GL_FLOAT, false, boxStrideBytes, boxVertexBuffer)
        GLES20.glEnableVertexAttribArray(boxPositionHandle)

        boxVertexBuffer.position(3)
        GLES20.glVertexAttribPointer(boxColorHandle, 4, GLES20.GL_FLOAT, false, boxStrideBytes, boxVertexBuffer)
        GLES20.glEnableVertexAttribArray(boxColorHandle)

        GLES20.glUniformMatrix4fv(boxMvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, boxVertexCount)

        GLES20.glDisableVertexAttribArray(boxPositionHandle)
        GLES20.glDisableVertexAttribArray(boxColorHandle)
    }

    private fun buildProgram(vertexCode: String, fragmentCode: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val id = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id
    }

    private fun toFloatBuffer(array: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(array.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(array)
                position(0)
            }
        }
    }

    companion object {
        // 6 faces * 6 vertices (2 triangles each) * 7 floats (x,y,z, r,g,b,a) — opaque.
        private val cubeVertices = floatArrayOf(
            -0.5f, -0.5f,  0.5f,  0.80f, 0.60f, 0.36f, 1f,
            0.5f, -0.5f,  0.5f,  0.80f, 0.60f, 0.36f, 1f,
            0.5f,  0.5f,  0.5f,  0.80f, 0.60f, 0.36f, 1f,
            -0.5f, -0.5f,  0.5f,  0.80f, 0.60f, 0.36f, 1f,
            0.5f,  0.5f,  0.5f,  0.80f, 0.60f, 0.36f, 1f,
            -0.5f,  0.5f,  0.5f,  0.80f, 0.60f, 0.36f, 1f,

            0.5f, -0.5f, -0.5f,  0.55f, 0.40f, 0.22f, 1f,
            -0.5f, -0.5f, -0.5f,  0.55f, 0.40f, 0.22f, 1f,
            -0.5f,  0.5f, -0.5f,  0.55f, 0.40f, 0.22f, 1f,
            0.5f, -0.5f, -0.5f,  0.55f, 0.40f, 0.22f, 1f,
            -0.5f,  0.5f, -0.5f,  0.55f, 0.40f, 0.22f, 1f,
            0.5f,  0.5f, -0.5f,  0.55f, 0.40f, 0.22f, 1f,

            -0.5f, -0.5f, -0.5f,  0.68f, 0.50f, 0.30f, 1f,
            -0.5f, -0.5f,  0.5f,  0.68f, 0.50f, 0.30f, 1f,
            -0.5f,  0.5f,  0.5f,  0.68f, 0.50f, 0.30f, 1f,
            -0.5f, -0.5f, -0.5f,  0.68f, 0.50f, 0.30f, 1f,
            -0.5f,  0.5f,  0.5f,  0.68f, 0.50f, 0.30f, 1f,
            -0.5f,  0.5f, -0.5f,  0.68f, 0.50f, 0.30f, 1f,

            0.5f, -0.5f,  0.5f,  0.62f, 0.45f, 0.26f, 1f,
            0.5f, -0.5f, -0.5f,  0.62f, 0.45f, 0.26f, 1f,
            0.5f,  0.5f, -0.5f,  0.62f, 0.45f, 0.26f, 1f,
            0.5f, -0.5f,  0.5f,  0.62f, 0.45f, 0.26f, 1f,
            0.5f,  0.5f, -0.5f,  0.62f, 0.45f, 0.26f, 1f,
            0.5f,  0.5f,  0.5f,  0.62f, 0.45f, 0.26f, 1f,

            -0.5f,  0.5f,  0.5f,  0.85f, 0.68f, 0.45f, 1f,
            0.5f,  0.5f,  0.5f,  0.85f, 0.68f, 0.45f, 1f,
            0.5f,  0.5f, -0.5f,  0.85f, 0.68f, 0.45f, 1f,
            -0.5f,  0.5f,  0.5f,  0.85f, 0.68f, 0.45f, 1f,
            0.5f,  0.5f, -0.5f,  0.85f, 0.68f, 0.45f, 1f,
            -0.5f,  0.5f, -0.5f,  0.85f, 0.68f, 0.45f, 1f,

            -0.5f, -0.5f, -0.5f,  0.45f, 0.32f, 0.18f, 1f,
            0.5f, -0.5f, -0.5f,  0.45f, 0.32f, 0.18f, 1f,
            0.5f, -0.5f,  0.5f,  0.45f, 0.32f, 0.18f, 1f,
            -0.5f, -0.5f, -0.5f,  0.45f, 0.32f, 0.18f, 1f,
            0.5f, -0.5f,  0.5f,  0.45f, 0.32f, 0.18f, 1f,
            -0.5f, -0.5f,  0.5f,  0.45f, 0.32f, 0.18f, 1f
        )
    }
}
