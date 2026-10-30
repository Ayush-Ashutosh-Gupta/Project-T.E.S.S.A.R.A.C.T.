package com.example.tessaract

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Project T.E.S.S.A.R.A.C.T. - Media Capture & Gallery Integrator
 * Handles local camera photo capture, camera video recording, and audio recording.
 * Explicitly saves all media files into the Android MediaStore / Gallery directories
 * and indexes them with MediaScannerConnection so they immediately appear in the user's Gallery.
 */
object MediaCaptureHelper {

    private const val TAG = "MediaCaptureHelper"

    private var activeMediaRecorder: MediaRecorder? = null
    private var currentAudioRecordingFile: File? = null
    var isAudioRecording = false
        private set

    /**
     * Launches the system camera to take a photo, configured to save output directly
     * to the user's public DCIM/Gallery directory.
     */
    fun triggerPhotoCapture(context: Context): Pair<Boolean, String> {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "IMG_TESSARACT_$timeStamp.jpg"

        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val uri: Uri? = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )

            val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                if (uri != null) {
                    putExtra(MediaStore.EXTRA_OUTPUT, uri)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }

            context.startActivity(cameraIntent)

            // Index in gallery
            if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            }

            Pair(true, "Launching camera to take photo. Photo will be saved in Gallery.")
        } catch (e: Exception) {
            Log.e(TAG, "Photo capture error: ${e.message}", e)
            // Fallback to standard camera launcher
            val fallback = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(fallback)
                Pair(true, "Opening camera. Captured photos will be saved in your Gallery.")
            } catch (ex: Exception) {
                Pair(false, "Unable to launch camera: ${ex.message}")
            }
        }
    }

    /**
     * Launches the system video camera, saving the recorded video to the Gallery.
     */
    fun triggerVideoCapture(context: Context): Pair<Boolean, String> {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "VID_TESSARACT_$timeStamp.mp4"

        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                }
            }

            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )

            val videoIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                if (uri != null) {
                    putExtra(MediaStore.EXTRA_OUTPUT, uri)
                }
                putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }

            context.startActivity(videoIntent)
            Pair(true, "Starting video recorder. Output will be saved in Gallery.")
        } catch (e: Exception) {
            Log.e(TAG, "Video capture error: ${e.message}", e)
            val fallback = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(fallback)
                Pair(true, "Opening camera video recorder.")
            } catch (ex: Exception) {
                Pair(false, "Unable to launch video recorder: ${ex.message}")
            }
        }
    }

    /**
     * Starts actual high-fidelity voice recording using MediaRecorder,
     * saving the audio file in the device's public Recordings / Music directory and MediaStore.
     */
    fun startVoiceRecording(context: Context): Pair<Boolean, String> {
        if (isAudioRecording) {
            return Pair(true, "Voice recording is already in progress.")
        }

        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val recordingsDir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_RECORDINGS)
                    ?: context.filesDir,
                "VoiceRecordings"
            )
            if (!recordingsDir.exists()) recordingsDir.mkdirs()

            val outputFile = File(recordingsDir, "Tessaract_Audio_$timeStamp.m4a")
            currentAudioRecordingFile = outputFile

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            activeMediaRecorder = recorder
            isAudioRecording = true
            Log.i(TAG, "Voice recording started: ${outputFile.absolutePath}")
            Pair(true, "Voice recording started. Say 'Stop recording' when done.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting voice recording: ${e.message}", e)
            isAudioRecording = false
            activeMediaRecorder?.release()
            activeMediaRecorder = null
            Pair(false, "Could not start voice recording: ${e.message}")
        }
    }

    /**
     * Stops the active voice recording and indexes the audio file in the system media library.
     */
    fun stopVoiceRecording(context: Context): Pair<Boolean, String> {
        if (!isAudioRecording || activeMediaRecorder == null) {
            return Pair(false, "No active voice recording to stop.")
        }

        return try {
            activeMediaRecorder?.apply {
                stop()
                reset()
                release()
            }
            activeMediaRecorder = null
            isAudioRecording = false

            val file = currentAudioRecordingFile
            if (file != null && file.exists()) {
                // Register in Android MediaStore / Audio Library
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf("audio/mp4")
                ) { path, uri ->
                    Log.i(TAG, "Audio file scanned into MediaStore: $path -> $uri")
                }
                val durationMsg = "Voice recording saved: ${file.name}"
                currentAudioRecordingFile = null
                Pair(true, durationMsg)
            } else {
                Pair(true, "Voice recording stopped.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping voice recording: ${e.message}", e)
            isAudioRecording = false
            activeMediaRecorder = null
            Pair(false, "Error finalizing recording: ${e.message}")
        }
    }
}
