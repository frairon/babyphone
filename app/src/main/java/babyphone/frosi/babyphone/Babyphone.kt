package babyphone.frosi.babyphone

import android.content.*
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.viewpager.widget.ViewPager
import com.google.android.material.appbar.AppBarLayout
import com.jakewharton.threetenabp.AndroidThreeTen
import com.jjoe64.graphview.GraphView
import com.jjoe64.graphview.helper.DateAsXAxisLabelFormatter
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries
import com.jjoe64.graphview.series.PointsGraphSeries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.threeten.bp.Instant
import java.io.InputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context that automatically is cancelled when UI is destroyed
 */
class UiLifecycleScope : CoroutineScope, LifecycleObserver {

    private lateinit var job: Job
    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.Main

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    fun onCreate() {
        job = Job()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun destroy() = job.cancel()
}

class Babyphone : AppCompatActivity(), ServiceConnection, View.OnClickListener {


    var service: ConnectionService? = null

    private var volumeSeries = LineGraphSeries<DataPoint>()
    private var thresholdSeries = LineGraphSeries<DataPoint>()
    private var alarmSeries = PointsGraphSeries<DataPoint>()
    private var movementSeries = PointsGraphSeries<DataPoint>()
    private var useLights: Boolean = false

    private var serviceBroadcastReceiver: BroadcastReceiver? = null

    private val loaderJob = Job()
    private val loaderScope = CoroutineScope(Dispatchers.IO + loaderJob)

    private val uiScope = UiLifecycleScope()

    private val imagePager = ImagePager(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycle.addObserver(uiScope)
        Log.i("connection-service", "connection service creating")
        setContentView(R.layout.activity_babyphone)


//        val coordinatorLayout = findViewById<View>(R.id.coordinator) as CoordinatorLayout
        setSupportActionBar(findViewById<View>(R.id.toolbar) as Toolbar)

        val actionbar = getSupportActionBar()
        if (actionbar != null) {
            actionbar.setDisplayHomeAsUpEnabled(true)
            actionbar.title = "Babyphone"
        }

//        val collapsingToolbar = findViewById<View>(R.id.collapsing_toolbar) as CollapsingToolbarLayout
//        collapsingToolbar.title = getString(R.string.app_name)


        AndroidThreeTen.init(this);

        initVolumeGraph()

        val activity = this

        val volumeSeek = this.findViewById<View>(R.id.vol_alarm_seeker) as SeekBar
        setVolumeThresholdIcon()
        volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                activity.service?.volumeThreshold = volumeSeek.progress

                activity.setGraphThreshold(progress)

                activity.setVolumeThresholdIcon()
            }
        })
        setGraphThreshold(volumeSeek.progress)

        val volAlarmAuto = this.findViewById<View>(R.id.vol_alarm_auto) as Switch
        volAlarmAuto.setOnCheckedChangeListener { _, isChecked ->
            volumeSeek.isEnabled = !isChecked
            activity.service?.autoVolumeLevel = isChecked
        }

        this.findViewById<View>(R.id.hide_menu).setOnClickListener(this)

        val volAlarmEnabled = this.findViewById<View>(R.id.vol_alarm_enabled) as Switch
        volAlarmEnabled.setOnCheckedChangeListener { _, isChecked ->
            volAlarmAuto.isEnabled = isChecked
            volumeSeek.isEnabled = isChecked && !volAlarmAuto.isChecked
            this.service?.alarmsEnabled = isChecked
        }
        val viewPager = this.findViewById<View>(R.id.imagePager) as ViewPager
        viewPager.adapter = imagePager

        volAlarmAuto.isEnabled = volAlarmEnabled.isChecked
        volumeSeek.isEnabled = volAlarmEnabled.isChecked && !volAlarmAuto.isChecked
        activity.service?.autoVolumeLevel = volAlarmAuto.isChecked

        connectToServiceBroadcast()
        this.bindService(Intent(this, ConnectionService::class.java), this, 0)
    }


    override fun onClick(v: View?) {
        if (v == null) {
            return
        }
        when (v.id) {

            R.id.hide_menu -> {
                val abl = findViewById<View>(R.id.app_bar_layout) as AppBarLayout
                abl.setExpanded(false)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        EventBus.getDefault().register(this)
    }

    override fun onStop() {
        super.onStop()
        EventBus.getDefault().unregister(this)
    }

    class TimedDrawable(val drawable: Drawable, val instant: Instant)

    fun loadAndShowImage() {
        loaderScope.launch {
            val uri = service?.getMotionUrl()
            if (uri != null) {
                val timedImage = loadMotionImage(uri)
                if (timedImage != null) {
                    uiScope.launch {
                        displayMotionImage(timedImage)
                        val viewPager = findViewById<View>(R.id.imagePager) as ViewPager
                        Log.d("babyphone", "updating current view pager item to " + imagePager.getCount())
                        viewPager.setCurrentItem(imagePager.getCount(), true)
                    }
                }

            }
        }
    }

    @WorkerThread
    fun loadMotionImage(uri: String): TimedDrawable? {

        try {
            val url = URL(uri)
            Log.d("babyphone", "loading image from " + uri)
            val connection = url.openConnection()
            val pictureTime = connection.getHeaderField("picture-time")
            val inputStream = connection.getContent() as InputStream
            Log.d("babyphone", "...success!")
            return TimedDrawable(
                    Drawable.createFromStream(inputStream, "src name"),
                    Instant.ofEpochSecond(pictureTime.toLong())
            )
        } catch (e: Exception) {
            Log.e("babyphone", "Error loading image " + e)
            return null
        }
    }

    @UiThread
    fun displayMotionImage(image: TimedDrawable) {
        Log.d("babyhpone", "displaying image")
        imagePager.addImage(image)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            VIDEO_ACTIVITY_REQ_CODE -> {
                if (data != null) {
                    useLights = data.getBooleanExtra("lights", false)
                }
            }
        }
    }

    fun setVolumeThresholdIcon() {

        val volumeSeek = this.findViewById<View>(R.id.vol_alarm_seeker) as SeekBar
        val progress = volumeSeek.progress
        if (progress == 0 || progress == 100) {
            volumeSeek.thumb = ContextCompat.getDrawable(this, R.drawable.ic_volume_off_black_24dp)
        } else if (progress < 50) {
            volumeSeek.thumb = ContextCompat.getDrawable(this, R.drawable.ic_volume_down_black_24dp)
        } else {
            volumeSeek.thumb = ContextCompat.getDrawable(this, R.drawable.ic_volume_up_black_24dp)
        }
    }

    fun setGraphThreshold(threshold: Int) {
        this.thresholdSeries.resetData(arrayOf(DataPoint(0.0, threshold.toDouble()), DataPoint(Double.MAX_VALUE, threshold.toDouble())))
    }

    fun initVolumeGraph() {
        val graph = findViewById(R.id.graph_volume) as GraphView

        graph.addSeries(this.volumeSeries)
        graph.addSeries(this.thresholdSeries)
        graph.addSeries(this.alarmSeries)
        graph.addSeries(this.movementSeries)

        this.alarmSeries.shape = PointsGraphSeries.Shape.TRIANGLE
        this.alarmSeries.color = Color.MAGENTA

        this.movementSeries.shape = PointsGraphSeries.Shape.POINT
        this.movementSeries.color = Color.DKGRAY
        this.movementSeries.size = 5F

        graph.gridLabelRenderer.labelFormatter = DateAsXAxisLabelFormatter(this, SimpleDateFormat("HH:mm:ss"))
        this.volumeSeries.thickness = 4


        this.thresholdSeries.color = Color.RED
        this.thresholdSeries.thickness = 2
        val vp = graph.viewport
        vp.isScrollable = true
        vp.isScalable = true
        vp.isXAxisBoundsManual = true
        vp.isYAxisBoundsManual = true
        vp.setMinY(0.0)
        vp.setMaxY(100.0)
        vp.setMinX(this.volumeSeries.lowestValueX)
        vp.setMaxX(this.volumeSeries.highestValueX)

        graph.getGridLabelRenderer().setNumHorizontalLabels(3)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        this.service = null
        if (this.serviceBroadcastReceiver != null) {
            Log.i("babyphone", "service disconnected, will unregister receiver")
            LocalBroadcastManager.getInstance(this).unregisterReceiver(this.serviceBroadcastReceiver!!)
        }
    }

    companion object {
        val MAX_GRAPH_ELEMENTS = 120
        val VIDEO_ACTIVITY_REQ_CODE = 1
        val EXTRA_DEVICE_ADDR = "io.frosi.babyphone.device.addr"
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {

        Log.i("babyphone", "service connected")
        this.service = (service as ConnectionService.ConnectionServiceBinder).service

        if (this.service == null) {
            return
        }

//        this.setConnectionStatus(this.service!!.connectionState, true)

        this.initVolumeHistory()

        val volumeSeek = this.findViewById<View>(R.id.vol_alarm_seeker) as SeekBar
        volumeSeek.progress = this.service!!.volumeThreshold
        this.setGraphThreshold(volumeSeek.progress)

        val volAlarmAuto = this.findViewById<View>(R.id.vol_alarm_auto) as Switch
        this.service!!.autoVolumeLevel = volAlarmAuto.isChecked
    }

    fun initVolumeHistory() {
        if (this.service == null) {
            return
        }
//
//        val alarmPoints = this.service!!.history.alarms.map { it -> DataPoint(Date(it.toEpochMilli()), 0.0) }
//        this.alarmSeries.resetData(alarmPoints.toTypedArray())
//
//        val dataPoints = this.service!!.history.volumes.map { it -> DataPoint(it.time, it.volume.toDouble()) }
//        this.volumeSeries.resetData(dataPoints.toTypedArray())
//
//        val movementPoints = this.service!!.history.movements.map { it -> DataPoint(it.time, it.volume.toDouble()) }
//        this.movementSeries.resetData(movementPoints.toTypedArray())

        val graph = this.findViewById(R.id.graph_volume) as GraphView?

        graph?.viewport?.setMinX(volumeSeries.lowestValueX)
        graph?.viewport?.setMaxX(volumeSeries.highestValueX)
    }

//    fun setConnectionStatus(proxyState: ConnectionService.ConnectionState, setButton: Boolean = false) {
//
//        val actionbar = supportActionBar!!
//
//        when (proxyState) {
//            ConnectionService.ConnectionState.Connecting -> {
//                runOnUiThread {
//                    actionbar.subtitle = "connecting to..."
//                }
//            }
//            ConnectionService.ConnectionState.Connected -> {
//                runOnUiThread {
//                    actionbar.subtitle = "connected to "
//                }
//
//                loadAndShowImage()
//            }
//            ConnectionService.ConnectionState.Disconnected -> {
//                runOnUiThread {
//                    actionbar.subtitle = "disconnected"
//                }
//            }
//        }
//    }

//    @Subscribe(threadMode = ThreadMode.POSTING)
//    fun handleConnnectionState(cu: ConnectionStateUpdated) {
//        setConnectionStatus(cu.proxyState)
//    }

    private fun connectToServiceBroadcast() {
        val activity = this

        this.serviceBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ConnectionService.ACTION_VOLUME_RECEIVED -> {
                        val vol = intent.getSerializableExtra(ConnectionService.ACTION_EXTRA_VOLUME) as Point?
                        if (vol == null) {
                            Log.e("babyphone", "Receivd null volume in volume intent. Ignoring.")
                            return
                        }
                        activity.addVolumeToGraph(vol)
                    }
                    ConnectionService.ACTION_MOVEMENT_RECEIVED -> {
                        val vol = intent.getSerializableExtra(ConnectionService.ACTION_EXTRA_MOVEMENT) as Point?
                        if (vol == null) {
                            Log.e("babyphone", "Receivd null movement in movement intent. Ignoring.")
                            return
                        }

                        activity.addMovementToGraph(vol)

                        loadAndShowImage()
                    }
                    ConnectionService.ACTION_ALARM_TRIGGERED -> {
                        activity.alarmSeries.appendData(DataPoint(Date(), 0.0), false, MAX_GRAPH_ELEMENTS)
                    }
                    ConnectionService.ACTION_AUTOTHRESHOLD_UPDATED -> {
                        val volume = intent.getIntExtra(ConnectionService.ACTION_EXTRA_VOLUME, 50)
                        setGraphThreshold(volume)
                        val volumeSeek = activity.findViewById<View>(R.id.vol_alarm_seeker) as SeekBar
                        volumeSeek.progress = volume
                    }
                    else -> {
                        Log.w("websocket", "unhandled action in intent:" + intent.action)
                    }
                }
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(
                this.serviceBroadcastReceiver!!,
                ConnectionService.createActionIntentFilter()
        )
    }


    fun addVolumeToGraph(vol: Point) {
        val newPoint = DataPoint(vol.time, vol.volume.toDouble())
        runOnUiThread {
            volumeSeries.appendData(newPoint, true, MAX_GRAPH_ELEMENTS)
            val graph = this.findViewById(R.id.graph_volume) as GraphView
            graph.viewport.setMinX(volumeSeries.lowestValueX)
            graph.viewport.setMaxX(volumeSeries.highestValueX)
        }
    }

    fun addMovementToGraph(vol: Point) {
        val newPoint = DataPoint(vol.time, vol.volume.toDouble())
        runOnUiThread {
            movementSeries.appendData(newPoint, true, MAX_GRAPH_ELEMENTS)
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        if (menu == null) {
            return super.onPrepareOptionsMenu(menu)
        }
        val mi = menu!!.findItem(R.id.action_edit) as MenuItem
        if (x) {
            mi.icon = ContextCompat.getDrawable(this, R.drawable.ic_headset_black_24dp)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_babyphone, menu)
        return true
    }

    var x: Boolean = false

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_edit -> {
                x = !x
                this.invalidateOptionsMenu()
                val abl = findViewById<View>(R.id.app_bar_layout) as AppBarLayout
                abl.setExpanded(true)
                return true

            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        Log.i("babyphone", "onDestroy")
        this.unbindService(this)
        lifecycle.removeObserver(uiScope)

        super.onDestroy()
    }

}

