/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamViewModel - DAT Camera Streaming API Demo
//
// This ViewModel demonstrates the DAT Camera Streaming APIs for:
// - Creating and managing stream sessions with wearable devices
// - Receiving video frames from device cameras
// - Capturing photos during streaming sessions
// - Handling different video qualities and formats
// - Processing raw video data (I420 -> NV21 conversion)

package com.meta.wearable.dat.externalsampleapps.landmarkguide.stream

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.externalsampleapps.landmarkguide.wearables.WearablesViewModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class StreamViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "StreamViewModel"
    private val INITIAL_STATE = StreamUiState()
  }

  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector
  private var streamSession: StreamSession? = null

  private val _uiState = MutableStateFlow(INITIAL_STATE)
  val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

  private val streamTimer = StreamTimer()

  private var videoJob: Job? = null
  private var audioJob: Job? = null // DAT audio collection job
  private var stateJob: Job? = null
  private var timerJob: Job? = null
  private var isRestarting = false // Flag to prevent navigation during intentional restart
  
  /** Set to true to completely disable auto-navigation (for test screens) */
  var disableNavigation = false
  
  /** Silent Mode Change - mute volume for 0.5s during state transitions */
  var silentModeChange = false
  
  private val audioManager: AudioManager by lazy {
    getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
  }
  
  private var savedVolume = -1  // Store volume before muting
  private var volumeRestoreJob: Job? = null
  
  /** Temporarily mute volume for duration ms, then restore */
  private fun temporaryMute(durationMs: Long) {
    volumeRestoreJob?.cancel()
    val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    if (savedVolume < 0) {
      savedVolume = currentVolume
    }
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    Log.d(TAG, "🔇 Volume muted (was $savedVolume)")
    
    volumeRestoreJob = viewModelScope.launch {
      kotlinx.coroutines.delay(durationMs)
      audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
      Log.d(TAG, "🔊 Volume restored to $savedVolume")
      savedVolume = -1
    }
  }

  init {
    // Collect timer state
    timerJob =
        viewModelScope.launch {
          launch {
            streamTimer.timerMode.collect { mode -> _uiState.update { it.copy(timerMode = mode) } }
          }

          launch {
            streamTimer.remainingTimeSeconds.collect { seconds ->
              _uiState.update { it.copy(remainingTimeSeconds = seconds) }
            }
          }

          launch {
            streamTimer.isTimerExpired.collect { expired ->
              if (expired) {
                // Stop streaming and navigate back
                stopStream()
                wearablesViewModel.navigateToDeviceSelection()
              }
            }
          }
        }
    
    // Monitor device connection - stop streaming if device disconnects (e.g., glasses in case)
    viewModelScope.launch {
      wearablesViewModel.uiState.collect { wearablesState ->
        if (!wearablesState.hasActiveDevice && streamSession != null) {
          Log.d(TAG, "Device disconnected - stopping stream to save battery")
          stopStream()
          wearablesViewModel.navigateToDeviceSelection()
        }
      }
    }
  }

  fun startStream() {
    startStreamWithState(StreamState.AI_RECOGNITION) // Default: Medium @ 30fps
  }
  
  /**
   * Start stream with specific state configuration
   */
  fun startStreamWithState(state: StreamState) {
    if (!state.videoEnabled) {
      Log.d(TAG, "State $state does not require video, skipping stream start")
      _uiState.update { it.copy(currentStreamState = state) }
      return
    }
    
    val quality = state.videoQuality ?: VideoQuality.MEDIUM
    val fps = state.frameRate ?: 24
    
    Log.d(TAG, "Starting stream with state: $state (${quality.name} @ ${fps}fps)")
    val startTime = System.currentTimeMillis()
    
    // Set flag to prevent navigation during stream restart
    isRestarting = true
    
    // Cancel all jobs FIRST to prevent state collection from old session
    videoJob?.cancel()
    videoJob = null
    audioJob?.cancel()
    audioJob = null
    stateJob?.cancel()
    stateJob = null
    
    // Close old session
    streamSession?.close()
    streamSession = null
    
    // Reset UI state for new stream
    _uiState.update { it.copy(streamStartTime = 0L, streamSessionState = StreamSessionState.STOPPED) }
    
    resetTimer()
    streamTimer.startTimer()
    
    // Start new session immediately (no delay)
    val streamSession =
        Wearables.startStreamSession(
                getApplication(),
                deviceSelector,
                StreamConfiguration(videoQuality = quality, fps),
            )
            .also { this.streamSession = it }
    
    _uiState.update { it.copy(currentStreamState = state) }
    
    videoJob = viewModelScope.launch { 
      streamSession.videoStream.collect { frame ->
        // Log first frame timing
        if (_uiState.value.streamStartTime == 0L) {
          val elapsed = System.currentTimeMillis() - startTime
          Log.d(TAG, "First frame received in ${elapsed}ms")
          _uiState.update { it.copy(streamStartTime = elapsed) }
        }
        handleVideoFrame(frame) 
      } 
    }
    stateJob =
        viewModelScope.launch {
          var startingTimeoutJob: kotlinx.coroutines.Job? = null
          val currentTargetState = state  // Capture the target state for retry
          
          streamSession.state.collect { currentState ->
            val prevState = _uiState.value.streamSessionState
            Log.d(TAG, "📊 Session state: $prevState → $currentState (isRestarting=$isRestarting, disableNav=$disableNavigation)")
            _uiState.update { it.copy(streamSessionState = currentState) }

            // Cancel timeout job if we progress past STARTING
            if (currentState != StreamSessionState.STARTING && currentState != StreamSessionState.STOPPED) {
              startingTimeoutJob?.cancel()
              startingTimeoutJob = null
            }

            // Handle state transitions
            when (currentState) {
              StreamSessionState.STARTING -> {
                Log.d(TAG, "⏳ Session starting...")
                // Start timeout job - if we stay in STARTING for 5 seconds, reset and retry
                startingTimeoutJob = viewModelScope.launch {
                  kotlinx.coroutines.delay(5000)
                  Log.w(TAG, "⚠️ STARTING timeout - doing full reset before retry")
                  _uiState.update { it.copy(streamSessionState = StreamSessionState.STOPPED) }
                  // Full reset
                  stopStream()
                  // Wait for SDK to fully cleanup
                  kotlinx.coroutines.delay(1000)
                  Log.d(TAG, "🔄 Retrying stream start after reset")
                  startStreamWithState(currentTargetState)
                }
              }
              StreamSessionState.STARTED -> Log.d(TAG, "✓ Session started, waiting for stream...")
              StreamSessionState.STREAMING -> {
                Log.d(TAG, "🎥 Streaming active!")
                // Silent mode change - mute for 0.5s during transition
                if (silentModeChange) {
                  temporaryMute(500)
                }
                // Reset isRestarting flag when stream is active (state-based, no delay)
                if (isRestarting) {
                  isRestarting = false
                  Log.d(TAG, "✅ isRestarting reset on STREAMING state")
                }
              }
              StreamSessionState.STOPPING -> Log.d(TAG, "⚠️ Session stopping...")
              StreamSessionState.STOPPED -> {
                Log.d(TAG, "⏹️ Session stopped")
                // Silent mode change - mute for 0.5s during transition
                if (silentModeChange && currentState != prevState) {
                  temporaryMute(500)
                }
                // Only navigate if not intentional restart and navigation enabled
                if (currentState != prevState && !isRestarting && !disableNavigation) {
                  Log.d(TAG, "⛔ Unexpected stop - navigating to device selection")
                  stopStream()
                  wearablesViewModel.navigateToDeviceSelection()
                }
              }
              StreamSessionState.CLOSED -> {
                Log.d(TAG, "🔒 Session closed (final state)")
                // Session is closed - cannot be reused
              }
            }
          }
        }
  }
  
  /**
   * Switch to a different stream state (resolution/framerate change)
   */
  fun switchToState(newState: StreamState) {
    val currentState = _uiState.value.currentStreamState
    Log.d(TAG, "Switching from $currentState to $newState")
    
    if (!newState.videoEnabled) {
      // Switching to audio-only, stop video stream
      // isRestarting is set true to prevent navigation during stop
      isRestarting = true
      stopStream()
      _uiState.update { it.copy(currentStreamState = newState) }
      // Reset immediately since we're staying in test screen (no new stream to wait for)
      isRestarting = false
      Log.d(TAG, "Switched to audio-only mode")
      return
    }
    
    if (currentState?.videoEnabled == true && newState.videoEnabled) {
      // Both have video - need to restart stream with new config
      val switchStartTime = System.currentTimeMillis()
      Log.d(TAG, "State switch: stopping current stream first")
      stopStream()
      
      // Launch new session with small delay to ensure SDK cleanup completes
      viewModelScope.launch {
        // Wait for SDK to fully close previous session
        kotlinx.coroutines.delay(300)
        Log.d(TAG, "State switch: starting new stream after SDK cleanup")
        startStreamWithState(newState)
        Log.d(TAG, "State switch completed in ${System.currentTimeMillis() - switchStartTime}ms")
      }
    } else {
      // Starting video from audio-only state (no previous stream to close)
      startStreamWithState(newState)
    }
  }

  fun stopStream() {
    videoJob?.cancel()
    videoJob = null
    audioJob?.cancel()
    audioJob = null
    stateJob?.cancel()
    stateJob = null
    streamSession?.close()
    streamSession = null
    streamTimer.stopTimer()
    // Preserve capturedPhoto when stopping stream
    val currentPhoto = _uiState.value.capturedPhoto
    _uiState.update { INITIAL_STATE.copy(capturedPhoto = currentPhoto) }
  }

  fun capturePhoto() {
    if (uiState.value.isCapturing) {
      Log.d(TAG, "Photo capture already in progress, ignoring request")
      return
    }

    if (uiState.value.streamSessionState == StreamSessionState.STREAMING) {
      Log.d(TAG, "Starting photo capture")
      val captureStartTime = System.currentTimeMillis()
      _uiState.update { it.copy(isCapturing = true, photoCaptureTime = 0L) }

      viewModelScope.launch {
        streamSession
            ?.capturePhoto()
            ?.onSuccess { photoData ->
              val elapsed = System.currentTimeMillis() - captureStartTime
              Log.d(TAG, "Photo capture successful in ${elapsed}ms")
              handlePhotoData(photoData)
              _uiState.update { it.copy(isCapturing = false, photoCaptureTime = elapsed) }
            }
            ?.onFailure {
              Log.e(TAG, "Photo capture failed")
              _uiState.update { it.copy(isCapturing = false, photoCaptureTime = -1L) }
            }
      }
    } else {
      Log.w(
          TAG,
          "Cannot capture photo: stream not active (state=${uiState.value.streamSessionState})",
      )
    }
  }

  /**
   * Capture photo and return to original mode.
   * Workflow: Save current mode → Switch to capture quality → Capture photo → Return to original mode
   * @param captureQuality: The quality to capture at (STANDBY=LOW, AI_RECOGNITION=MED, PHOTO_CAPTURE=HIGH)
   */
  fun captureWithReturn(captureQuality: StreamState) {
    if (uiState.value.isCapturing) {
      Log.d(TAG, "Capture already in progress, ignoring request")
      return
    }
    
    val originalState = uiState.value.currentStreamState
    Log.d(TAG, "Starting capture workflow: ${originalState?.name ?: "OFF"} → ${captureQuality.name} → ${originalState?.name ?: "OFF"}")
    val workflowStartTime = System.currentTimeMillis()
    _uiState.update { it.copy(isCapturing = true, micToCaptureTime = 0L) }
    
    viewModelScope.launch {
      // Step 1: Switch to capture quality mode
      isRestarting = true
      stopStream()
      startStreamWithState(captureQuality)
      isRestarting = false
      
      // Step 2: Wait for stream to be ready (wait for first frame)
      var waitCount = 0
      while (uiState.value.streamSessionState != StreamSessionState.STREAMING && waitCount < 50) {
        kotlinx.coroutines.delay(100)
        waitCount++
      }
      
      if (uiState.value.streamSessionState != StreamSessionState.STREAMING) {
        Log.e(TAG, "Failed to start stream for capture")
        _uiState.update { it.copy(isCapturing = false, micToCaptureTime = -1L) }
        return@launch
      }
      
      // Step 2.5: Wait for first frame to arrive (STREAMING state doesn't mean frames are ready)
      Log.d(TAG, "STREAMING state reached, waiting for first frame...")
      kotlinx.coroutines.delay(1000)  // Wait 1 second for frames to stabilize
      
      // Step 3: Capture photo
      Log.d(TAG, "Stream ready, capturing photo at ${captureQuality.name}")
      streamSession?.capturePhoto()
          ?.onSuccess { photoData ->
            Log.d(TAG, "Photo captured successfully")
            handlePhotoData(photoData)
            
            // Step 4: Return to original mode (if it was something)
            if (originalState != null && originalState != StreamState.OFF) {
              if (originalState.videoEnabled) {
                // Return to a video mode - switch to that quality
                Log.d(TAG, "Returning to original video mode: ${originalState.name}")
                isRestarting = true
                stopStream()
                switchToState(originalState)
                isRestarting = false
              } else {
                // Return to audio-only mode - just stop the video stream
                Log.d(TAG, "Returning to audio-only mode: ${originalState.name}")
                isRestarting = true
                stopStream()
                _uiState.update { it.copy(currentStreamState = originalState) }
                isRestarting = false
              }
            } else {
              // Original was OFF - just stop everything
              Log.d(TAG, "Original was OFF, stopping stream")
              stopStream()
            }
            
            val elapsed = System.currentTimeMillis() - workflowStartTime
            Log.d(TAG, "Capture workflow completed in ${elapsed}ms")
            _uiState.update { it.copy(isCapturing = false, photoCaptureTime = elapsed, micToCaptureTime = elapsed) }
          }
          ?.onFailure {
            Log.e(TAG, "Photo capture failed")
            val elapsed = System.currentTimeMillis() - workflowStartTime
            _uiState.update { it.copy(isCapturing = false, photoCaptureTime = -1L, micToCaptureTime = -elapsed) }
          }
    }
  }

  fun showShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = true) }
  }

  fun hideShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = false) }
  }

  fun sharePhoto(bitmap: Bitmap) {
    val context = getApplication<Application>()
    val imagesFolder = File(context.cacheDir, "images")
    try {
      imagesFolder.mkdirs()
      val file = File(imagesFolder, "shared_image.png")
      FileOutputStream(file).use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
      }

      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      val intent = Intent(Intent.ACTION_SEND)
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      intent.putExtra(Intent.EXTRA_STREAM, uri)
      intent.type = "image/png"
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

      val chooser = Intent.createChooser(intent, "Share Image")
      chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      context.startActivity(chooser)
    } catch (e: IOException) {
      Log.e("StreamViewModel", "Failed to share photo", e)
    }
  }

  fun cycleTimerMode() {
    streamTimer.cycleTimerMode()
    if (_uiState.value.streamSessionState == StreamSessionState.STREAMING) {
      streamTimer.startTimer()
    }
  }

  fun resetTimer() {
    streamTimer.resetTimer()
  }

  private fun handleVideoFrame(videoFrame: VideoFrame) {
    // VideoFrame contains raw I420 video data in a ByteBuffer
    val buffer = videoFrame.buffer
    val dataSize = buffer.remaining()
    val byteArray = ByteArray(dataSize)

    // Save current position
    val originalPosition = buffer.position()
    buffer.get(byteArray)
    // Restore position
    buffer.position(originalPosition)

    // Convert I420 to NV21 format which is supported by Android's YuvImage
    val nv21 = convertI420toNV21(byteArray, videoFrame.width, videoFrame.height)
    val image = YuvImage(nv21, ImageFormat.NV21, videoFrame.width, videoFrame.height, null)
    val out =
        ByteArrayOutputStream().use { stream ->
          image.compressToJpeg(Rect(0, 0, videoFrame.width, videoFrame.height), 50, stream)
          stream.toByteArray()
        }

    val bitmap = BitmapFactory.decodeByteArray(out, 0, out.size)
    _uiState.update { it.copy(videoFrame = bitmap) }
  }

  // Convert I420 (YYYYYYYY:UUVV) to NV21 (YYYYYYYY:VUVU)
  private fun convertI420toNV21(input: ByteArray, width: Int, height: Int): ByteArray {
    val output = ByteArray(input.size)
    val size = width * height
    val quarter = size / 4

    input.copyInto(output, 0, 0, size) // Y is the same

    for (n in 0 until quarter) {
      output[size + n * 2] = input[size + quarter + n] // V first
      output[size + n * 2 + 1] = input[size + n] // U second
    }
    return output
  }

  private fun handlePhotoData(photo: PhotoData) {
    Log.d(TAG, "handlePhotoData called, photo type: ${photo::class.simpleName}")
    val capturedPhoto =
        when (photo) {
          is PhotoData.Bitmap -> {
            Log.d(TAG, "Photo is Bitmap: ${photo.bitmap.width}x${photo.bitmap.height}")
            photo.bitmap
          }
          is PhotoData.HEIC -> {
            Log.d(TAG, "Photo is HEIC, decoding...")
            val byteArray = ByteArray(photo.data.remaining())
            photo.data.get(byteArray)

            // Extract EXIF transformation matrix and apply to bitmap
            val exifInfo = getExifInfo(byteArray)
            val transform = getTransform(exifInfo)
            val decoded = decodeHeic(byteArray, transform)
            Log.d(TAG, "HEIC decoded: ${decoded.width}x${decoded.height}")
            decoded
          }
        }
    Log.d(TAG, "Setting capturedPhoto: ${capturedPhoto.width}x${capturedPhoto.height}")
    _uiState.update { it.copy(capturedPhoto = capturedPhoto, isShareDialogVisible = true) }
  }

  // HEIC Decoding with EXIF transformation
  private fun decodeHeic(heicBytes: ByteArray, transform: Matrix): Bitmap {
    val bitmap = BitmapFactory.decodeByteArray(heicBytes, 0, heicBytes.size)
    return applyTransform(bitmap, transform)
  }

  private fun getExifInfo(heicBytes: ByteArray): ExifInterface? {
    return try {
      ByteArrayInputStream(heicBytes).use { inputStream -> ExifInterface(inputStream) }
    } catch (e: IOException) {
      Log.w(TAG, "Failed to read EXIF from HEIC", e)
      null
    }
  }

  private fun getTransform(exifInfo: ExifInterface?): Matrix {
    val matrix = Matrix()

    if (exifInfo == null) {
      return matrix // Identity matrix (no transformation)
    }

    when (
        exifInfo.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    ) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_180 -> {
        matrix.postRotate(180f)
      }
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
        matrix.postScale(1f, -1f)
      }
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> {
        matrix.postRotate(90f)
      }
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> {
        matrix.postRotate(270f)
      }
      ExifInterface.ORIENTATION_NORMAL,
      ExifInterface.ORIENTATION_UNDEFINED -> {
        // No transformation needed
      }
    }

    return matrix
  }

  private fun applyTransform(bitmap: Bitmap, matrix: Matrix): Bitmap {
    if (matrix.isIdentity) {
      return bitmap
    }

    return try {
      val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      if (transformed != bitmap) {
        bitmap.recycle()
      }
      transformed
    } catch (e: OutOfMemoryError) {
      Log.e(TAG, "Failed to apply transformation due to memory", e)
      bitmap
    }
  }

  override fun onCleared() {
    super.onCleared()
    stopStream()
    stateJob?.cancel()
    timerJob?.cancel()
    streamTimer.cleanup()
  }

  class Factory(
      private val application: Application,
      private val wearablesViewModel: WearablesViewModel,
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(StreamViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST", "KotlinGenericsCast")
        return StreamViewModel(
            application = application,
            wearablesViewModel = wearablesViewModel,
        )
            as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}
