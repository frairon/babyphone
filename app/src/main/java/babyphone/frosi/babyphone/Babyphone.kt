package babyphone.frosi.babyphone

import android.animation.ValueAnimator
import android.content.*
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.*
import android.view.animation.LinearInterpolator
import android.widget.PopupWindow
import android.widget.SeekBar
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.ViewModelProviders
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.viewpager.widget.ViewPager
import babyphone.frosi.babyphone.databinding.ActivityMonitorBinding
import babyphone.frosi.babyphone.databinding.VisualOptionsBinding
import babyphone.frosi.babyphone.models.DeviceViewModel
import babyphone.frosi.babyphone.models.ImagePager
import babyphone.frosi.babyphone.models.MonitorViewModel
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.jakewharton.threetenabp.AndroidThreeTen
import com.jjoe64.graphview.GraphView
import com.jjoe64.graphview.helper.DateAsXAxisLabelFormatter
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.PointsGraphSeries
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.activity_monitor.*
import kotlinx.android.synthetic.main.conn_details.*
import kotlinx.android.synthetic.main.monitor_picture.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.threeten.bp.Instant
import java.io.InputStream
import java.net.URL
import java.text.SimpleDateFormat
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

    private var serviceBroadcastReceiver: BroadcastReceiver? = null

    private val loaderJob = Job()
    private val loaderScope = CoroutineScope(Dispatchers.IO + loaderJob)

    private val uiScope = UiLifecycleScope()

    private val imagePager = ImagePager(this)

    private lateinit var model: MonitorViewModel
    private lateinit var deviceModel: DeviceViewModel

    private val disposables = CompositeDisposable()

    private var motionReloadAnimator: ValueAnimator? = null

    private val player = Player()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        model = ViewModelProviders
                .of(this, MonitorViewModel.Factory(this.application))
                .get(MonitorViewModel::class.java)

        deviceModel = ViewModelProviders
                .of(this, DeviceViewModel.Factory(this.application))
                .get(DeviceViewModel::class.java)

        lifecycle.addObserver(uiScope)
        // create the layout and bind the model to it
        val binding = DataBindingUtil.setContentView<ActivityMonitorBinding>(this, R.layout.activity_monitor)
        binding.model = model
        binding.deviceModel = deviceModel
        binding.lifecycleOwner = this

        this.btn_live.setOnClickListener(this)
        this.hide_menu.setOnClickListener(this)
        this.inactive_blocker.setOnClickListener { true }
        this.btn_visual_settings.setOnClickListener(this)

//        val coordinatorLayout = findViewById<View>(R.id.coordinator) as CoordinatorLayout
        setSupportActionBar(findViewById<View>(R.id.toolbar) as Toolbar)

        val actionbar = getSupportActionBar()
        if (actionbar != null) {
            actionbar.setDisplayHomeAsUpEnabled(true)
            actionbar.title = "Babyphone"
        }

        val collapsingToolbar = findViewById<View>(R.id.collapsing_toolbar) as CollapsingToolbarLayout
        collapsingToolbar.title = getString(R.string.app_name)

        AndroidThreeTen.init(this);

        initVolumeGraph()

        this.liveVideo.holder.addCallback(this.player)

        this.images.adapter = this.model.imagePager
        disposables.add(this.model.movementUpdated
                .filter { it.intervalMillis != 0L }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    if (motionReloadAnimator != null) {
                        motionReloadAnimator?.cancel()
                        motionReloadAnimator?.removeAllUpdateListeners()
                        this.motionReloadAnimator = null
                    }
                    Log.d(TAG, "movement updated, starting animation $it")

                    val ani = ValueAnimator.ofInt(0, 1000)
                    ani.duration = it.intervalMillis
                    ani.interpolator = LinearInterpolator()
                    ani.addUpdateListener {
                        this.prog_refresh_timeout.progress = it.animatedValue as Int
                    }
                    motionReloadAnimator = ani

                    ani.start()
                })

//        val activity = this
//
//        val volumeSeek = this.findViewById<View>(R.id.vol_alarm_seeker) as SeekBar
//        setVolumeThresholdIcon()
//        volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
//            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
//            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
//
//            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
//                activity.service?.volumeThreshold = volumeSeek.progress
//
//                activity.setGraphThreshold(progress)
//
//                activity.setVolumeThresholdIcon()
//            }
//        })
//        setGraphThreshold(volumeSeek.progress)
//
//        val volAlarmAuto = this.findViewById<View>(R.id.vol_alarm_auto) as Switch
//        volAlarmAuto.setOnCheckedChangeListener { _, isChecked ->
//            volumeSeek.isEnabled = !isChecked
//            activity.service?.volumeThreshold = isChecked
//        }
//
//        val volAlarmEnabled = this.findViewById<View>(R.id.vol_alarm_enabled) as Switch
//        volAlarmEnabled.setOnCheckedChangeListener { _, isChecked ->
//            volAlarmAuto.isEnabled = isChecked
//            volumeSeek.isEnabled = isChecked && !volAlarmAuto.isChecked
//            this.service?.alarmsEnabled = isChecked
//        }
//        val viewPager = this.findViewById<View>(R.id.images) as ViewPager
//        viewPager.adapter = imagePager
//
//        volAlarmAuto.isEnabled = volAlarmEnabled.isChecked
//        volumeSeek.isEnabled = volAlarmEnabled.isChecked && !volAlarmAuto.isChecked
//        activity.service?.volumeThreshold = volAlarmAuto.isChecked

        connectToServiceBroadcast()
        ConnectionService.startService(this)
        this.bindService(Intent(this, ConnectionService::class.java), this, 0)
    }

    var popup: PopupWindow? = null

    override fun onClick(v: View?) {
        if (v == null) {
            return
        }
        when (v.id) {

            R.id.hide_menu -> {
                val abl = findViewById<View>(R.id.app_bar_layout) as AppBarLayout
                abl.setExpanded(false)
            }
            R.id.btn_live -> {
                var live = this.model.livePicture.value == true
                live = !live
                this.model.livePicture.postValue(live)
                if (live) {
                    this.player.start()
                } else {
                    this.player.stop()
                }
            }
            R.id.btn_visual_settings -> {

                val inflater = this.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
                val binding = DataBindingUtil.inflate<VisualOptionsBinding>(inflater, R.layout.visual_options, null, false)
                val popup = PopupWindow(binding.root,
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                binding.model = this.model

                popup.isFocusable = true
                popup.isTouchable = true
                popup.showAsDropDown(this.btn_visual_settings)
            }
        }
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
    }

    class TimedDrawable(val drawable: Drawable, val instant: Instant) {
        companion object {
            val INVALID = TimedDrawable(ColorDrawable(), Instant.now())
        }
    }

    fun loadAndShowImage() {
        loaderScope.launch {
            val uri = service?.getMotionUrl()
            if (uri != null) {
                val timedImage = loadMotionImage(uri)
                if (timedImage != null) {
                    uiScope.launch {
                        displayMotionImage(timedImage)
                        val viewPager = findViewById<View>(R.id.images) as ViewPager
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
//                if (data != null) {
//                    useLights = data.getBooleanExtra("lights", false)
//                }
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

    private fun initVolumeGraph() {
        val graph = findViewById(R.id.graph_volume) as GraphView

        graph.addSeries(this.model.volumeSeries)
        graph.addSeries(this.model.thresholdSeries)
        graph.addSeries(this.model.alarmSeries)

        this.model.alarmSeries.shape = PointsGraphSeries.Shape.TRIANGLE
        this.model.alarmSeries.color = Color.MAGENTA

        graph.gridLabelRenderer.labelFormatter = DateAsXAxisLabelFormatter(this, SimpleDateFormat("HH:mm:ss"))
        this.model.volumeSeries.thickness = 4


        this.model.thresholdSeries.color = Color.RED
        this.model.thresholdSeries.thickness = 2
        val vp = graph.viewport
        vp.isScrollable = false
        vp.isScalable = false
        vp.isXAxisBoundsManual = true
        vp.isYAxisBoundsManual = true
        vp.setMinY(0.0)
        vp.setMaxY(100.0)
        vp.setMinX(this.model.volumeSeries.lowestValueX)
        vp.setMaxX(this.model.volumeSeries.highestValueX)

        disposables.add(this.model.volumeUpdated.observeOn(AndroidSchedulers.mainThread()).subscribe { vol ->
            graph.viewport.setMinX(this.model.volumeSeries.lowestValueX)
            graph.viewport.setMaxX(this.model.volumeSeries.highestValueX)
        })

        disposables.add(this.model.imagePager.sizeUpdated
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    Log.d(TAG, "adding image to imagepager in thread ${Thread.currentThread().name}")
                    this.images.currentItem = it - 1
                })

        graph.gridLabelRenderer.numHorizontalLabels = 3
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        this.service = null
        if (this.serviceBroadcastReceiver != null) {
            Log.i("babyphone", "service disconnected, will unregister receiver")
            LocalBroadcastManager.getInstance(this).unregisterReceiver(this.serviceBroadcastReceiver!!)
        }
    }

    companion object {
        val TAG = "babyphone"
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

        this.model.connectService(this.service!!)
        this.deviceModel.connectService(this.service!!)
        this.player.connectService(this.service!!)

//        this.setConnectionStatus(this.service!!.connectionState, true)

//        this.initVolumeHistory()

//        val volumeSeek = this.findViewById<View>(R.id.vol_alarm_seeker) as SeekBar
//        volumeSeek.progress = this.service!!.volumeThreshold
//        this.setGraphThreshold(volumeSeek.progress)

//        val volAlarmAuto = this.findViewById<View>(R.id.vol_alarm_auto) as Switch
//        this.service!!.volumeThreshold = volAlarmAuto.isChecked
    }

//    fun initVolumeHistory() {
//        if (this.service == null) {
//            return
//        }
//
//        val alarmPoints = this.service!!.history.alarms.map { it -> DataPoint(Date(it.toEpochMilli()), 0.0) }
//        this.alarmSeries.resetData(alarmPoints.toTypedArray())
//
//        val dataPoints = this.service!!.history.volumes.map { it -> DataPoint(it.time, it.volume.toDouble()) }
//        this.volumeSeries.resetData(dataPoints.toTypedArray())
//
//        val movementPoints = this.service!!.history.movements.map { it -> DataPoint(it.time, it.volume.toDouble()) }
//        this.movementSeries.resetData(movementPoints.toTypedArray())

//        val graph = this.findViewById(R.id.graph_volume) as GraphView?
//
//        graph?.viewport?.setMinX(model.volumeSeries.lowestValueX)
//        graph?.viewport?.setMaxX(model.volumeSeries.highestValueX)
//    }

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

    override fun onResume() {
        super.onResume()

        if (this.model.livePicture.value == true) {
            this.player.start()
        }
    }

    override fun onPause() {
        super.onPause()
        if (this.model.livePicture.value == true) {
            this.player.stop()
        }
    }

    private fun connectToServiceBroadcast() {
        val activity = this

        this.serviceBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ConnectionService.ACTION_MOVEMENT_RECEIVED -> {
                        val vol = intent.getSerializableExtra(ConnectionService.ACTION_EXTRA_MOVEMENT) as Point?
                        if (vol == null) {
                            Log.e("babyphone", "Receivd null movement in movement intent. Ignoring.")
                            return
                        }

//                        activity.addMovementToGraph(vol)

                        loadAndShowImage()
                    }
                    ConnectionService.ACTION_ALARM_TRIGGERED -> {
//                        activity.alarmSeries.appendData(DataPoint(Date(), 0.0), false, MAX_GRAPH_ELEMENTS)
                    }
                    ConnectionService.ACTION_AUTOTHRESHOLD_UPDATED -> {
                        val volume = intent.getIntExtra(ConnectionService.ACTION_EXTRA_VOLUME, 50)
//                        setGraphThreshold(volume)
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


//    fun addMovementToGraph(vol: Point) {
//        val newPoint = DataPoint(vol.time, vol.volume.toDouble())
//        runOnUiThread {
//            movementSeries.appendData(newPoint, true, MAX_GRAPH_ELEMENTS)
//        }
//    }

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

        disposables.clear()

        super.onDestroy()
    }

}

