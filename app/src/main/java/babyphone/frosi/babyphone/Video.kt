package babyphone.frosi.babyphone

import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.support.design.widget.Snackbar
import android.support.v4.content.LocalBroadcastManager
import android.support.v7.app.AppCompatActivity;
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.widget.*
import com.jjoe64.graphview.series.DataPoint

import kotlinx.android.synthetic.main.activity_video.*
import java.util.*

class Video : AppCompatActivity(), ServiceConnection {

    var player: Player? = null

    var url: String? = null

    enum class StreamMode(val suffix: String) {
        Audio("audio"),
        AudioVideo("audiovideo")
    }


    var service: ConnectionService? = null

    private var serviceBroadcastReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video)
        setSupportActionBar(toolbar)

        url = intent.getStringExtra("url")

        try {
            this.player = Player(this)
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val sv = this.findViewById<View>(R.id.surface_video) as SurfaceView
        val sh = sv.holder
        sh.addCallback(this.player)


        val swtLights = this.findViewById<View>(R.id.btn_lights) as Switch
        swtLights.setOnClickListener {
            this.service?.toggleLights();
        }
//        val playAudio = this.findViewById<View>(R.id.button_audio) as ImageButton
//        playAudio.setOnClickListener {
//            this.player?.play(createUrl(Babyphone.StreamMode.Audio))
//        }
//        val playVideo = this.findViewById<View>(R.id.button_video) as ImageButton
//        playVideo.setOnClickListener {
//            this.player?.play(createUrl(Babyphone.StreamMode.AudioVideo))
//        }
//
//        val pause = this.findViewById<View>(R.id.button_stop) as ImageButton
//        pause.setOnClickListener {
//            this.player?.pause()
//        }

        this.player?.play(createUrl(StreamMode.AudioVideo))

        supportActionBar?.setDisplayHomeAsUpEnabled(true)


        connectToServiceBroadcast()
        this.player?.initialize()
        this.bindService(Intent(this, ConnectionService::class.java), this, 0)
    }

    fun createUrl(streamMode: StreamMode): String {
        val hostInput = this.findViewById<View>(R.id.text_host) as TextView
        return "rtsp://" + this.url + ":8554/" + streamMode.suffix
    }
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {

        Log.i("Video", "service connected")
        this.service = (service as ConnectionService.ConnectionServiceBinder).service

        if (this.service == null) {
            return
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        this.service = null
        if (this.serviceBroadcastReceiver != null) {
            Log.i("video", "service disconnected, will unregister receiver")
            LocalBroadcastManager.getInstance(this).unregisterReceiver(this.serviceBroadcastReceiver)
        }
        Log.i("video", "service disconnected, stopping activity")

        this.finish()
    }

    private fun connectToServiceBroadcast() {
        val activity = this
        this.serviceBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                }
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(
                this.serviceBroadcastReceiver,
                ConnectionService.createActionIntentFilter()
        )
    }

    // Called from native code when the size of the media changes or is first detected.
    // Inform the video surface about the new size and recalculate the layout.
    fun onMediaSizeChanged(width: Int, height: Int) {
        Log.i("GStreamer", "Media size changed to " + width + "x" + height)
        val gsv = this.findViewById<View>(R.id.surface_video) as GStreamerSurfaceView
        gsv.media_width = width
        gsv.media_height = height
        runOnUiThread { gsv.requestLayout() }
    }

    // Called from native code. This sets the content of the TextView from the UI thread.
    fun setMessage(message: String) {
        val tv = this.findViewById<View>(R.id.textview_message) as TextView
        runOnUiThread { tv.text = message }
    }

    // Called from native code. Native code calls this once it has created its pipeline and
    // the main loop is running, so it is ready to accept commands.
    fun onGStreamerInitialized() {
        Log.i("GStreamer", "GStreamer initialized:")
    }

    override fun onDestroy() {
        Log.i("connection-service", "connection service onDestroy")
        this.player?.destroy()
        super.onDestroy()
    }


}
