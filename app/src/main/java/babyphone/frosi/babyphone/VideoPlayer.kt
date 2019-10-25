package babyphone.frosi.babyphone

import android.app.ActivityManager
import android.media.*
import android.util.Base64
import android.util.Log
import android.view.SurfaceHolder
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.sendBlocking
import java.lang.IllegalStateException
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class VideoPlayer : SurfaceHolder.Callback {

    private lateinit var renderContext: ExecutorCoroutineDispatcher
    private lateinit var inputRenderer: Job
    private lateinit var renderer: Job

    private lateinit var mc: MediaCodec

    private lateinit var service: ConnectionService

    private var playerState = BehaviorSubject.create<PlayerState>()

    private lateinit var connDisposable: Disposable
    private var frameDisposable: Disposable? = null

    companion object {
        const val TAG = "videoplayer"
    }

    fun connectService(service: ConnectionService) {
        this.service = service

    }

    init {
        playerState.onNext(PlayerState.STOPPED)
    }

    class Player{
        fun run(){

        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int): Unit {
        Log.i(Babyphone.TAG, "surface changed format: $format, width: $width, height: $height")


        val codecs = MediaCodecList(MediaCodecList.ALL_CODECS)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 320, 240)
        val codec = codecs.findDecoderForFormat(format)
        try {

            this.mc = MediaCodec.createByCodecName(codec)
            mc.configure(format, holder.surface, null, 0)
            mc.start()

            val channel = Channel<VideoFrame>(1024)

            this.renderContext = newSingleThreadContext("videorenderer")

            this.inputRenderer = GlobalScope.launch {
                withContext(renderContext) {
                    Log.d(TAG, "starting input renderer ")
                    try {
                        while (coroutineContext.isActive) {
                            Log.d(TAG, "waiting for channel to receive frame")
                            this@VideoPlayer.handleFrame(channel.receive())
                        }
                    } catch (e: CancellationException) {
                        Log.d(TAG, "job cancelled. Exiting")
                        return@withContext
                    } catch (e: Exception) {
                        Log.e(TAG, "exception during input rendering", e)
                    }
                }
            }

            this.frameDisposable = this.service.conn?.frames
                    ?.observeOn(Schedulers.computation())
                    ?.subscribe {
                        Log.d(TAG, "send the video frame to the channel")
                        channel.sendBlocking(it)
                    }

            this.renderer = GlobalScope.launch {
                this@VideoPlayer.render()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error creating codec for format $format, codec $codec", e)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder): Unit {
        Log.i(Babyphone.TAG, "surface destroyed")
        this.frameDisposable?.dispose()

        runBlocking {
            this@VideoPlayer.inputRenderer.cancelAndJoin()
            this@VideoPlayer.renderer.cancelAndJoin()
        }

        this.mc.stop()
        this.mc.release()
        Log.d(TAG, "stopping renderer thread")

        Log.d(TAG, "videoplayer shutdown done")
    }

    override fun surfaceCreated(holder: SurfaceHolder): Unit {
        Log.i(Babyphone.TAG, "Surface Created. Configuring Codec")

    }

    private suspend fun render() {
        Log.i(AudioPlayer.TAG, "handling output buffers in thread ${Thread.currentThread().name}")

        try {
            val bufferInfo = MediaCodec.BufferInfo()

            while (this.playerState.value != PlayerState.RUNNING) {
                Log.d(TAG, "waiting for player to get started")
                delay(500)
            }

            while (this.playerState.value == PlayerState.RUNNING) {
                var bufId = -1
                withContext(renderContext) {
                    bufId = this@VideoPlayer.mc.dequeueOutputBuffer(bufferInfo, 0)
                }
                if (bufId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    Log.d(TAG, "no output buffer available")
                    delay(100)
                    continue
                }
                if (bufId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(AudioPlayer.TAG, "output format changed ${this.mc.outputFormat}")
                    continue
                }
                if (bufId == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    Log.d(AudioPlayer.TAG, "deprecated output returned... stopping")
                    this.stop()
                }
                Log.d(TAG, "releasing")
                withContext(renderContext) {
                    this@VideoPlayer.mc.releaseOutputBuffer(bufId, true)
                }
            }
            Log.d(TAG, "player not in running state anymore. Exiting.")
        } catch (e: InterruptedException) {
            Log.d(AudioPlayer.TAG, "runner stopped by Thread.interrupt. Will stop playing")
        } catch (e: MediaCodec.CodecException) {
            Log.e(AudioPlayer.TAG, "Error playing: codec exception ${e.diagnosticInfo}, code ${e.errorCode}, recoverable: ${e.isRecoverable}, transient: ${e.isTransient}")
        } catch (e: Exception) {
            Log.e(AudioPlayer.TAG, "error playing $e")
        }
    }

    fun start() {
        this.service.startStream()
        this.playerState.onNext(PlayerState.RUNNING)
    }

    fun stop() {
        this.playerState.onNext(PlayerState.STOPPED)
        this.service.stopStream()
    }

    private suspend fun handleFrame(cu: VideoFrame) {
        Log.d(TAG, "handling frame in thread ${Thread.currentThread().name}")
        if (this.playerState.value == PlayerState.STOPPED) {
            return
        }


        try {
            var inputBufferId = -1

            withContext(renderContext) {
                inputBufferId = this@VideoPlayer.mc.dequeueInputBuffer(0)
            }
            if (inputBufferId < 0) {
//                Log.d(TAG, "No buffer available. Increase timeout?")
                return
            }

            var inputBuffer: ByteBuffer? = null
            withContext(renderContext) {
                inputBuffer = this@VideoPlayer.mc.getInputBuffer(inputBufferId)
            }
            if (inputBuffer == null) {
                Log.e(TAG, "returned input buffer is null. Stopping")
                this.stop()
                return
            }
            // fill in the frame
            inputBuffer?.put(cu.data)
            var flags = 0
            if (cu.type == VideoFrame.Type.Config) {
                flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            } else if (cu.partial) {
                flags = MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
            }

            withContext(renderContext) {
                this@VideoPlayer.mc.queueInputBuffer(inputBufferId,
                        cu.offset.toInt(),
                        cu.data.size,
                        cu.pts,
                        flags)
            }
        } catch (e: MediaCodec.CodecException) {
            Log.e(TAG, "codec exception", e)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "codec was in an illegal state while working with it", e)
        } catch (e: Exception) {
            Log.e(TAG, "unknown exception", e)
        }
    }

}

enum class PlayerState {
    RUNNING,
    STOPPED
}

class AudioPlayer(val conn: DeviceConnection) {

    private val disp = CompositeDisposable()
    private val track: AudioTrack
    private val codec: MediaCodec
    private val runner: Thread

    private var state = PlayerState.RUNNING

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

                while (this.state == PlayerState.RUNNING) {
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
                        this.stop()
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
            } catch (e: Exception) {
                Log.e(TAG, "error playing $e")
            }
        }
    }

    fun stop() {
        if (this.state == PlayerState.STOPPED) {
            return
        }
        this.state = PlayerState.STOPPED
        Log.d(TAG, "stopping streaming in the connection")
        this.conn.stopAudio()
        Log.d(TAG, "stopping audio playing and interrupting the player")
        disp.clear()
        runner.interrupt()
        Log.d(TAG, "waiting for the player to shut down.")
        runner.join()
        track.stop()
        track.release()
        codec.stop()
        codec.release()
        Log.d(TAG, "VideoPlayer terminated")
    }

    fun isRunning(): Boolean {
        return this.state == PlayerState.RUNNING
    }
}