package babyphone.frosi.babyphone

import android.app.Activity
import android.content.*
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.Switch
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.android.synthetic.main.activity_video.*
import com.google.android.exoplayer2.ExoPlayerFactory
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.util.Util.getUserAgent
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util
import java.net.URL


class Video : AppCompatActivity(), ServiceConnection {

    var player: Player? = null

    var url: String? = null

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

        val player = ExoPlayerFactory.newSimpleInstance(this)

        val vv = this.findViewById<View>(R.id.videoView) as PlayerView
        vv.setPlayer(player)

        val dataSourceFactory = DefaultDataSourceFactory(this,
                Util.getUserAgent(this, "yourApplicationName"))
// This is the MediaSource representing the media to be played.
        val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(Uri.parse("http://192.168.178.80:5000/stream.ts"))
// Prepare the player with the source.
        player.prepare(videoSource)
        player.playWhenReady = true
        // Bind the player to the view.
        //playerView.setPlayer(player);

//        try {
//            this.player = Player(this)
//        } catch (e: Exception) {
//            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
//            finish()
//            return
//        }

//        val sv = this.findViewById<View>(R.id.surface_video) as SurfaceView
//        val sh = sv.holder
//        sh.addCallback(this.player)


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

    override fun onPause() {
        super.onPause()
        Log.i("Video", "onPause called")
        this.service?.lights = false
        //val vv = this.findViewById<View>(R.id.videoView) as VideoView
//        vv.stopPlayback()
//        this.player?.pause()
//        this.player?.destroy()
    }

    override fun onResume() {
        super.onResume()
        Log.i("Video", "onResume called")
//        this.player?.initialize()

//        val vv = this.findViewById<View>(R.id.videoView) as VideoView
//        vv.setVideoURI(Uri.parse("http://192.168.178.72:5000/stream"))
//        vv.setOnErrorListener( { mp, what, extra ->
//            Log.e("videoview", "what: $what extra: $extra")
//            false
//        })
//        vv.start()
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

    // Called from native code when the size of the media changes or is first detected.
    // Inform the video surface about the new size and recalculate the layout.
    fun onMediaSizeChanged(width: Int, height: Int) {
//        Log.i("GStreamer", "Media size changed to " + width + "x" + height)
//        val gsv = this.findViewById<View>(R.id.surface_video) as GStreamerSurfaceView
//        gsv.media_width = width
//        gsv.media_height = height
//        runOnUiThread { gsv.requestLayout() }
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
        this.player?.play(createUrl(StreamMode.AudioVideo))
    }

    override fun onDestroy() {
        Log.i("connection-service", "connection service onDestroy")
        this.unbindService(this)
        super.onDestroy()
    }


}
