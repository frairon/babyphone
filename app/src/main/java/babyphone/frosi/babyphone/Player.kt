package babyphone.frosi.babyphone

import android.media.*
import android.util.Base64
import android.util.Log
import android.view.SurfaceHolder
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class Player : SurfaceHolder.Callback {

    var mc: MediaCodec? = null

    lateinit var service: ConnectionService

    private lateinit var connDisposable: Disposable
    private var disposables = CompositeDisposable()

    fun connectService(service: ConnectionService) {
        this.service = service

        connDisposable = service.connections.subscribe { conn ->
            this.updateConnection(conn)

        }

    }

    private fun updateConnection(conn: DeviceConnection) {

        // clear subscribers of old connection, if any
        disposables.clear()
        disposables = CompositeDisposable()

        disposables.add(conn.frames.subscribe() { f -> this.playFrame(f) })
    }


    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int): Unit {
        Log.i(Babyphone.TAG, "surface changed")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder): Unit {
        Log.i(Babyphone.TAG, "surface destroyed")
    }

    override fun surfaceCreated(holder: SurfaceHolder): Unit {
        Log.i(Babyphone.TAG, "surface created")
        val codecs = MediaCodecList(MediaCodecList.ALL_CODECS)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 320, 240)
        val codec = codecs.findDecoderForFormat(format)
        try {

            val mc = MediaCodec.createByCodecName(codec)
            mc.configure(format, holder.surface, null, 0)
            mc.start()
            this.mc = mc
        } catch (e: Exception) {
            Log.e(TAG, "Error creating codec for format $format, codec $codec", e)
        }
    }

    fun start() {
        this.service.startStream()
    }

    fun stop() {
        this.service.stopStream()
        this.mc = null
    }

    fun playFrame(cu: VideoFrame) {
        // acquire a buffer-ID, wait up to 200ms for that
        if (this.mc == null) {
            return
        }
//        Log.w(TAG, "received input frame ${cu.data.size}")
        val inputBufferId = this.mc!!.dequeueInputBuffer(100000)
        if (inputBufferId < 0) {
            Log.e(TAG, "No buffer available. Increase timeout?")
            return
        }

        val inputBuffer = this.mc!!.getInputBuffer(inputBufferId)
        // fill in the frame
        inputBuffer.put(cu.data)
        var flags = 0
        if (cu.type == VideoFrame.Type.Config) {
            flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG
        } else if (cu.partial) {
            flags = MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        try {
            this.mc!!.queueInputBuffer(inputBufferId,
                    0,
                    cu.data.size,
                    cu.pts,
                    flags)
        } catch (e: Exception) {
            Log.e(TAG, "error adding buffer", e)
        }
        try {
            val bufInfo = MediaCodec.BufferInfo()
            val outId = this.mc!!.dequeueOutputBuffer(bufInfo, 100000)
            if (outId < 0) {
                Log.d(TAG, "no output buffer available")
            } else {
                Log.d(TAG, "releasing output buffer")
                this.mc!!.releaseOutputBuffer(outId, true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "error releasing output buffer", e)
        }
    }

    companion object {
        const val TAG = "videoplayer"
    }

}

class AudioPlayer(val conn: DeviceConnection) {

    private val disp = CompositeDisposable()
    private val track: AudioTrack
    private val codec: MediaCodec
    private val runner: Thread

    private var state = State.RUNNING

    enum class State {
        RUNNING,
        STOPPED
    }

    companion object {
        const val INPUT_DEQ_TIMEOUT = 100000L
        const val TAG = "audioplayer"
    }

    init {

        val codecs = MediaCodecList(MediaCodecList.ALL_CODECS)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_G711_ALAW, 8000, 1)

        val codecName = codecs.findDecoderForFormat(format)


        try {
            track = AudioTrack.Builder().setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(8000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                    .setAudioAttributes(AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(4096)
                    .build()

            track.play()

            codec = MediaCodec.createByCodecName(codecName)
            codec.configure(format, null, null, 0)
            codec.start()

            Log.d(TAG, "started codec ${codec.codecInfo}")
        } catch (e: MediaCodec.CodecException) {
            Log.e(TAG, "Error configuring/starting codec", e)
            throw(e)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating codec", e)
            throw e
        }

        conn.audio
                .observeOn(Schedulers.newThread()).subscribe {
                    try {
                        val bufId = codec.dequeueInputBuffer(INPUT_DEQ_TIMEOUT)
                        if (bufId != -1) {
                            val buf = codec.getInputBuffer(bufId)
                            val decoded = Base64.decode(it.data, Base64.DEFAULT)
                            buf.put(decoded)
                            codec.queueInputBuffer(bufId,
                                    0,
                                    decoded.size,
                                    it.pts,
                                    0)
                        } else {
                            Log.d(TAG, "couldn't get audio codec input buffer")
                        }
                    } catch (e: IllegalStateException) {
                        Log.e(TAG, "Codec seems to be in an illegal state", e)
                    } catch (e: MediaCodec.CodecException) {
                        Log.e(TAG, "Codec error", e)
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, "Error decoding base64", e)
                    }
                }
                .addTo(disp)

        this.conn.startAudio()

        runner = thread {
            Log.i(TAG, "getting buffer from audiotrack in thread ${Thread.currentThread().name}")
            try {
                val bufferInfo = MediaCodec.BufferInfo()

                while (this.state == State.RUNNING) {
                    val bufId = codec.dequeueOutputBuffer(bufferInfo, 100000)

                    if (bufId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        continue
                    }
                    if (bufId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.d(TAG, "output format changed ${codec.outputFormat}")
                        continue
                    }
                    if (bufId == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        Log.d(TAG, "deprecated output returned... stopping")
                        break
                    }
                    val buf = codec.getOutputBuffer(bufId)
                    if (buf == null) {
                        Log.e(TAG, "Could not get output buffer although it was dequeued")
                    }

                    val res = track.write(buf.position(bufferInfo.offset) as ByteBuffer,
                            bufferInfo.size,
                            AudioTrack.WRITE_BLOCKING)

                    if (res <= 0) {
                        Log.e(TAG, "error writing audio snippet to track: $res")
                    }
                    codec.releaseOutputBuffer(bufId, false)
                }
            } catch (e: InterruptedException) {
                Log.d(TAG, "runner stopped by Thread.interrupt. Will stop playing")
            } catch (e: MediaCodec.CodecException) {
                Log.e(TAG, "Error playing: codec exception ${e.diagnosticInfo}, code ${e.errorCode}, recoverable: ${e.isRecoverable}, transient: ${e.isTransient}")
                codec.release()
            } catch (e: Exception) {
                Log.e(TAG, "error playing $e")
            }
        }
    }

    fun stop() {
        if (this.state == State.STOPPED) {
            return
        }
        this.state = State.STOPPED
        Log.d(TAG, "stopping streaming in the connection")
        this.conn.stopAudio()
        Log.d(TAG, "stopping audio playing and interrupting the player")
        disp.clear()
        runner.interrupt()
        track.stop()
        codec.stop()
        codec.release()
        Log.d(TAG, "waiting for the player to shut down.")
        runner.join()
        Log.d(TAG, "Player terminated")
    }

    fun isRunning(): Boolean {
        return this.state == State.RUNNING
    }
}