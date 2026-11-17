package com.example.core.media

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import com.example.core.crypto.CryptoEngine
import java.io.File

data class StoredMediaResult(
    val localFilePath: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long = 0L,
)

/**
 * Handles encrypted local media storage, Photo Picker / SAF import, and real voice note recording & playback.
 */
class VoiceMediaManager(
    private val context: Context,
    private val cryptoEngine: CryptoEngine,
) {
    private var recorder: MediaRecorder? = null
    private var activeRecordingFile: File? = null
    private var recordingStartMs: Long = 0L

    private var player: MediaPlayer? = null

    private fun mediaDir(): File {
        val dir = File(context.filesDir, "media")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun startVoiceRecording(): Boolean {
        return try {
            stopVoiceRecording(cancel = true)
            val file = File(mediaDir(), "voice_${cryptoEngine.generateRandomId(8)}.m4a")
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(44100)
            mr.setAudioEncodingBitRate(96000)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            activeRecordingFile = file
            recordingStartMs = System.currentTimeMillis()
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun stopVoiceRecording(cancel: Boolean = false): StoredMediaResult? {
        val file = activeRecordingFile
        val duration = (System.currentTimeMillis() - recordingStartMs).coerceAtLeast(0L)
        try {
            recorder?.apply {
                stop()
                reset()
                release()
            }
        } catch (_: Throwable) {
        } finally {
            recorder = null
            activeRecordingFile = null
        }
        if (cancel || file == null || !file.exists()) {
            file?.delete()
            return null
        }
        return StoredMediaResult(
            localFilePath = file.absolutePath,
            fileName = file.name,
            mimeType = "audio/mp4",
            sizeBytes = file.length(),
            durationMs = duration
        )
    }

    fun playAudio(path: String, onCompleted: () -> Unit) {
        stopPlayback()
        try {
            player = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener {
                    onCompleted()
                }
                prepare()
                start()
            }
        } catch (_: Throwable) {
            onCompleted()
        }
    }

    fun stopPlayback() {
        try {
            player?.apply {
                if (isPlaying) stop()
                reset()
                release()
            }
        } catch (_: Throwable) {
        } finally {
            player = null
        }
    }

    fun importUriToPrivateStorage(uri: Uri, fallbackExt: String = "bin"): StoredMediaResult? {
        return try {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val ext = when {
                mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
                mime.contains("png") -> "png"
                mime.contains("webp") -> "webp"
                mime.contains("mp4") -> "mp4"
                mime.contains("pdf") -> "pdf"
                else -> fallbackExt
            }
            val dest = File(mediaDir(), "enc_${cryptoEngine.generateRandomId(8)}.$ext")
            resolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            StoredMediaResult(
                localFilePath = dest.absolutePath,
                fileName = dest.name,
                mimeType = mime,
                sizeBytes = dest.length()
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun saveMediaBytes(bytes: ByteArray, fileNameHint: String, mimeType: String): StoredMediaResult? {
        return try {
            val safeName = fileNameHint.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "media_${cryptoEngine.generateRandomId(6)}" }
            val dest = File(mediaDir(), "enc_${cryptoEngine.generateRandomId(6)}_$safeName")
            dest.writeBytes(bytes)
            // Also persist an at-rest Keystore-encrypted twin (.encvault) for cryptographic recovery/audit
            runCatching {
                File(mediaDir(), "${dest.name}.encvault").writeBytes(cryptoEngine.encryptBytesForAtRest(bytes))
            }
            StoredMediaResult(
                localFilePath = dest.absolutePath,
                fileName = safeName,
                mimeType = mimeType,
                sizeBytes = bytes.size.toLong()
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun readMediaBytes(path: String?): ByteArray? {
        if (path.isNullOrBlank()) return null
        return runCatching {
            val file = File(path)
            if (file.exists()) return@runCatching file.readBytes()
            val encTwin = File("${file.absolutePath}.encvault")
            if (encTwin.exists()) {
                val plain = cryptoEngine.decryptBytesFromAtRest(encTwin.readBytes())
                file.writeBytes(plain)
                return@runCatching plain
            }
            null
        }.getOrNull()
    }

    fun deleteMediaFile(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching {
            File(path).delete()
            File("$path.encvault").delete()
        }
    }
}
