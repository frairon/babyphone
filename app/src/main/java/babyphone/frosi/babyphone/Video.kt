package babyphone.frosi.babyphone

import android.app.Activity
import android.content.*
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode


class Video : AppCompatActivity(), ServiceConnection, SurfaceHolder.Callback {

    var url: String? = null

    var mc: MediaCodec? = null

    var useLights: Boolean = false
        set(value) {
            field = value
            setResult(Activity.RESULT_OK, Intent().putExtra("lights", value))
        }

    enum class StreamMode(val suffix: String) {
        Audio("audio"),
        AudioVideo("audiovideo")
    }

    var service: ConnectionService? = null

    private var serviceBroadcastReceiver: BroadcastReceiver? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("Video", "onCreate called")
        setContentView(R.layout.activity_video)
//        setSupportActionBar(toolbar)

        url = intent.getStringExtra("url")


        val vv = this.findViewById<View>(R.id.videoView) as SurfaceView
        vv.holder.addCallback(this)


        val swtLights = this.findViewById<View>(R.id.btn_lights) as Switch
        swtLights.setOnCheckedChangeListener { _, isChecked ->
            this.service?.lights = isChecked
            useLights = isChecked
        }

        if (intent.getBooleanExtra("lights", false)
                || savedInstanceState?.getBoolean("lights") == true) {
            useLights = true
            service?.lights = useLights
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        connectToServiceBroadcast()
        this.bindService(Intent(this, ConnectionService::class.java), this, 0)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int): Unit {

    }

    override fun surfaceDestroyed(holder: SurfaceHolder): Unit {

    }

    override fun surfaceCreated(holder: SurfaceHolder): Unit {

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

    @Subscribe(threadMode = ThreadMode.POSTING)
    fun handleVideoFrame(cu: VideoFrame) {
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

    override fun onStart() {
        super.onStart()
        val eb = EventBus.getDefault()
        eb.register(this)
        eb.post(StreamAction(StreamAction.Action.Start))

    }

    override fun onStop() {
        super.onStop()
        val eb = EventBus.getDefault()
        eb.unregister(this)
        eb.post(StreamAction(StreamAction.Action.Stop))
    }


    override fun onPause() {
        super.onPause()
        Log.i("Video", "onPause called")
        this.service?.lights = false
    }

    override fun onResume() {
        super.onResume()
        Log.i("Video", "onResume called")

        this.service?.lights = useLights
    }

    override fun onRestoreInstanceState(state: Bundle?) {
        super.onRestoreInstanceState(state)
        Log.i("video", "onrestoreinstancestate called")
        if (state == null) {
            return
        }
        useLights = state.getBoolean("lights", false)
        this.service?.lights = useLights
    }

    override fun onSaveInstanceState(state: Bundle) {
        Log.i("video", "onsaveinstancestate called")
        state.putBoolean("lights", useLights)
        super.onSaveInstanceState(state)
    }


    fun createUrl(streamMode: StreamMode): String {
        return "rtsp://" + this.url + ":8554/" + streamMode.suffix
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {

        Log.i("Video", "service connected")
        this.service = (service as ConnectionService.ConnectionServiceBinder).service

        if (this.service == null) {
            return
        }

        if (useLights) {
            val swtLights = this.findViewById<View>(R.id.btn_lights) as Switch
            swtLights.isChecked = true
            this.service?.lights = useLights
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        this.service = null
        if (this.serviceBroadcastReceiver != null) {
            Log.i("video", "service disconnected, will unregister receiver")
            LocalBroadcastManager.getInstance(this).unregisterReceiver(this.serviceBroadcastReceiver!!)
        }
        Log.i("video", "service disconnected, stopping activity")

        this.finish()
    }

    private fun connectToServiceBroadcast() {
        this.serviceBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                }
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(
                this.serviceBroadcastReceiver!!,
                ConnectionService.createActionIntentFilter()
        )
    }

    override fun onDestroy() {
        Log.i("connection-service", "connection service onDestroy")
        this.unbindService(this)

        mc?.stop()
        mc?.release()


        super.onDestroy()
    }


}
