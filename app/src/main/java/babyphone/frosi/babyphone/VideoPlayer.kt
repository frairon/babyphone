package babyphone.frosi.babyphone

import android.media.*
import android.util.Base64
import android.util.Log
import android.view.SurfaceHolder
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import java.nio.ByteBuffer
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

class VideoPlayer : SurfaceHolder.Callback {

    private lateinit var player: Player

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

    class Player(val holder: SurfaceHolder, val input: BlockingQueue<VideoFrame>) {
        private lateinit var mc: MediaCodec

        init {
            val codecs = MediaCodecList(MediaCodecList.ALL_CODECS)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 320, 240)
            val codec = codecs.findDecoderForFormat(format)

            this.mc = MediaCodec.createByCodecName(codec)
            mc.configure(format, holder.surface, null, 0)
            mc.start()
        }

        fun doIt(element: VideoFrame) {
            try {
                Log.d(TAG, "doit frame ${Thread.currentThread().name}")
                if (handleInput(element)) {
                    renderFrame()
                }
            } catch (e: MediaCodec.CodecException) {
                Log.e(TAG, "codec exception", e)
                return
            } catch (e: IllegalStateException) {
                Log.e(TAG, "codec was in an illegal state while working with it", e)
                return
            } catch (e: Exception) {
                Log.e(TAG, "random exception", e)
            } finally {
                Log.d(TAG, "stopping the codec")
//                this.mc.stop()
                Log.d(TAG, "releasing the codec")
//                this.mc.release()
                Log.d(TAG, "videoplayer shutdown done")
            }
        }


        private fun handleInput(cu: VideoFrame): Boolean {

            val inputBufferId = this.mc.dequeueInputBuffer(1000000)
            if (inputBufferId < 0) {
                Log.d(TAG, "no input buffer available, skipping frame")
                return false
            }

            var inputBuffer = this.mc.getInputBuffer(inputBufferId)
            if (inputBuffer == null) {
                Log.e(TAG, "returned input buffer is null. Stopping")
                throw NullPointerException("input buffer was null but shouldn't have been")
            }
            // fill in the frame
            inputBuffer.put(cu.data)
            var flags = 0
            if (cu.type == VideoFrame.Type.Config) {
                flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            } else if (cu.partial) {
                flags = MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
            }

            Log.d(TAG, "queueing input buffer")
            this.mc.queueInputBuffer(inputBufferId,
                    0,
                    cu.data.size,
                    0,
                    flags)
            return true
        }

        private fun renderFrame() {
            val bufferInfo = MediaCodec.BufferInfo()
            var bufId = this.mc.dequeueOutputBuffer(bufferInfo, 0)
            if (bufId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                Log.d(TAG, "no output buffer available")
                return
            }
            if (bufId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.d(TAG, "output format changed ${this.mc.outputFormat}")
                return
            }
            if (bufId == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                Log.d(TAG, "deprecated output returned... ignoring")
                return
            }
            Log.d(TAG, "releasing output buffer")
            this.mc.releaseOutputBuffer(bufId, true)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int): Unit {
        Log.i(Babyphone.TAG, "surface changed format: $format, width: $width, height: $height")


    }

    override fun surfaceDestroyed(holder: SurfaceHolder): Unit {
        Log.i(Babyphone.TAG, "surface destroyed")
        Log.d(TAG, "clearing disposables")
        this.frameDisposable?.dispose()
        Log.d(TAG, "stopping player")
//        this.player.interrupt()
    }

    override fun surfaceCreated(holder: SurfaceHolder): Unit {
        Log.i(Babyphone.TAG, "Surface Created")

        val queue = LinkedBlockingQueue<VideoFrame>()
        this.player = Player(holder, queue)
        this.frameDisposable = this.service.conn?.frames
//                ?.observeOn(AndroidSchedulers.mainThread())
                ?.subscribe {
                    Log.d(TAG, "sending frame")
                    this.player.doIt(it)
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