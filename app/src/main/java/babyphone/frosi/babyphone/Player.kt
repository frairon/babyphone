package babyphone.frosi.babyphone

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import android.view.SurfaceHolder
import babyphone.frosi.babyphone.models.MonitorViewModel
import com.jjoe64.graphview.series.DataPoint
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import java.util.concurrent.TimeUnit

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
            Log.e("Video", "Error creating codec for format $format, codec $codec", e)
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
//        Log.w("Video", "received input frame ${cu.data.size}")
        val inputBufferId = this.mc!!.dequeueInputBuffer(1000000)
        if (inputBufferId < 0) {
            Log.e("video", "No buffer available. Increase timeout?")
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
                    cu.offset,
                    cu.data.size,
                    cu.timestamp,
                    flags)
        } catch (e: Exception) {
            Log.e("Video", "error adding buffer", e)
        }
        try {
            val bufInfo = MediaCodec.BufferInfo()
            val outId = this.mc!!.dequeueOutputBuffer(bufInfo, 0)
            if (outId < 0) {
                Log.i("Video", "no output buffer available")
            } else {
//                Log.i("video", "releasing output buffer")
                this.mc!!.releaseOutputBuffer(outId, true)
            }
        } catch (e: Exception) {
            Log.e("Video", "error releasing output buffer", e)
        }
    }


}