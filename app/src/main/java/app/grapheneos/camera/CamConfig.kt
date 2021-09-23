package app.grapheneos.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import app.grapheneos.camera.ui.MainActivity
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class CamConfig(private val mActivity: MainActivity) {

    var camera: Camera? = null

    private var cameraProvider: ProcessCameraProvider? = null

    var imageCapture: ImageCapture? = null
        private set

    var preview: Preview? = null
        private set

    private var cameraSelector = CameraSelector.LENS_FACING_BACK

    private val extensionsManager by lazy {
        ExtensionsManager.getInstance(mActivity).get()
    }

    var isVideoMode = false
        private set

    var videoCapture: VideoCapture? = null

    private var latestFile: File? = null

    private var flashMode: Int
        get() = if (imageCapture != null) imageCapture!!.flashMode else ImageCapture.FLASH_MODE_OFF
        set(flashMode) {
            imageCapture!!.flashMode = flashMode
        }
    val parentDirPath: String
        get() = parentDir!!.absolutePath

    private val parentDir: File?
        get() {
            val dirs = mActivity.externalMediaDirs
            var parentDir: File? = null
            for (dir in dirs) {
                if (dir != null) {
                    parentDir = dir
                    break
                }
            }
            if (parentDir != null) {
                parentDir = File(
                    parentDir.absolutePath,
                    mActivity.resources.getString(R.string.app_name)
                )
                if (parentDir.mkdirs()) {
                    Log.i(TAG, "Parent directory was successfully created")
                }
            }
            return parentDir
        }

    val latestPreview: Bitmap?
        get() {
            val lastModifiedFile = latestMediaFile ?: return null
            return if (getExtension(lastModifiedFile) == "mp4") {
                try {
                    getVideoThumbnail(lastModifiedFile.absolutePath)
                } catch (throwable: Throwable) {
                    throwable.printStackTrace()
                    null
                }
            } else BitmapFactory.decodeFile(lastModifiedFile.absolutePath)
        }

    fun setLatestFile(latestFile: File?) {
        this.latestFile = latestFile
    }

    val latestMediaFile: File?
        get() {
            if (latestFile != null) return latestFile
            val dir = parentDir
            val files = dir!!.listFiles { file: File ->
                if (!file.isFile) return@listFiles false
                val ext = getExtension(file)
                ext == "jpg" || ext == "png" || ext == "mp4"
            }
            if (files == null || files.isEmpty()) return null
            var lastModifiedFile = files[0]
            for (file in files) {
                if (lastModifiedFile.lastModified() < file.lastModified()) lastModifiedFile = file
            }
            return lastModifiedFile
        }

    fun switchCameraMode() {
        isVideoMode = !isVideoMode
        startCamera(true)
    }

    // Tells whether flash is available for the current mode
    private val isFlashAvailable: Boolean
        get() = camera!!.cameraInfo.hasFlashUnit()

    fun toggleFlashMode() {
        if (camera!!.cameraInfo.hasFlashUnit()) {
            flashMode =
                if (flashMode == ImageCapture.FLASH_MODE_OFF) ImageCapture.FLASH_MODE_AUTO else imageCapture!!.flashMode + 1
            mActivity.flashPager.currentItem = flashMode
        } else {
            Toast.makeText(
                mActivity, "Flash is unavailable" +
                        " for the current mode.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun toggleCameraSelector() {
        cameraSelector =
            if (cameraSelector == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        startCamera(true)
    }

    fun initializeCamera() {
        if (cameraProvider != null) {
            startCamera()
            return
        }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(
            mActivity
        )
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                startCamera()
            } catch (e: ExecutionException) {
                // No errors need to be handled for this Future.
                // This should never be reached.
            } catch (e: InterruptedException) {
            }
        }, ContextCompat.getMainExecutor(mActivity))
    }

    // Start the camera with latest hard configuration
    @JvmOverloads
    fun startCamera(forced: Boolean = false) {
        if (!forced && camera != null) return

        preview = Preview.Builder()
            .setTargetRotation(mActivity.windowManager.defaultDisplay.rotation)
            .build()

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(cameraSelector)
            .build()

        val builder = ImageCapture.Builder()

        if (isVideoMode) videoCapture = VideoCapture.Builder().build()

        imageCapture = builder
            .setTargetRotation(mActivity.windowManager.defaultDisplay.rotation)
            .setFlashMode(flashMode)
            .build()

        preview!!.setSurfaceProvider(mActivity.previewView.surfaceProvider)
        mActivity.updateLastFrame()

        // Unbind/close all other camera(s) [if any]
        cameraProvider!!.unbindAll()

        // Get a camera instance bound to the lifecycle of this activity
        camera = if (isVideoMode) {
            cameraProvider!!.bindToLifecycle(
                mActivity, cameraSelector,
                preview, imageCapture, videoCapture
            )
        } else {
            cameraProvider!!.bindToLifecycle(
                mActivity, cameraSelector,
                preview, imageCapture
            )
        }

        // Focus camera on touch/tap
        mActivity.previewView.setOnTouchListener(mActivity)
        startAutoFocus()
        if (!isFlashAvailable) {
            mActivity.flashPager.currentItem = ImageCapture.FLASH_MODE_OFF
            flashMode = ImageCapture.FLASH_MODE_OFF
        }
    }

    private fun startAutoFocus() {
        val autoFocusPoint = SurfaceOrientedMeteringPointFactory(1f, 1f)
            .createPoint(.5f, .5f)
        val autoFocusAction = FocusMeteringAction.Builder(
            autoFocusPoint,
            FocusMeteringAction.FLAG_AF
        ).setAutoCancelDuration(AUTO_FOCUS_INTERVAL_IN_SECONDS.toLong(), TimeUnit.SECONDS).build()
        camera!!.cameraControl.startFocusAndMetering(autoFocusAction).addListener(
            { Log.i(TAG, "Auto-focusing every $AUTO_FOCUS_INTERVAL_IN_SECONDS seconds...") },
            ContextCompat.getMainExecutor(mActivity)
        )
    }

    companion object {
        private const val TAG = "CamConfig"
        const val AUTO_FOCUS_INTERVAL_IN_SECONDS = 2

        @JvmStatic
        @Throws(Throwable::class)
        fun getVideoThumbnail(p_videoPath: String?): Bitmap {

            val mBitmap: Bitmap
            var mMediaMetadataRetriever: MediaMetadataRetriever? = null

            try {
                mMediaMetadataRetriever = MediaMetadataRetriever()
                mMediaMetadataRetriever.setDataSource(p_videoPath)
                mBitmap = mMediaMetadataRetriever.frameAtTime!!
            } catch (m_e: Exception) {
                throw Throwable(
                    "Exception in retrieveVideoFrameFromVideo(String p_videoPath)"
                            + m_e.message
                )
            } finally {
                if (mMediaMetadataRetriever != null) {
                    mMediaMetadataRetriever.release()
                    mMediaMetadataRetriever.close()
                }
            }
            return mBitmap
        }

        @JvmStatic
        fun getExtension(file: File): String {
            val fileName = file.name
            val lastIndexOf = fileName.lastIndexOf(".")
            return if (lastIndexOf == -1) "" else fileName.substring(lastIndexOf + 1)
        }
    }
}