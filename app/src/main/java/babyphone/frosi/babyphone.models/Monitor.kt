package babyphone.frosi.babyphone.models

import android.app.Application
import android.content.BroadcastReceiver
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import androidx.annotation.WorkerThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.viewpager.widget.ViewPager
import babyphone.frosi.babyphone.*
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries
import com.jjoe64.graphview.series.PointsGraphSeries
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.ReplaySubject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.threeten.bp.Instant
import java.io.InputStream
import java.net.URL
import java.util.concurrent.TimeUnit


class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    var service: ConnectionService? = null

    val volumeSeries = LineGraphSeries<DataPoint>()
    val volumeUpdated = PublishSubject.create<Volume>()
    val thresholdSeries = LineGraphSeries<DataPoint>()
    val alarmSeries = PointsGraphSeries<DataPoint>()
    val useLights: Boolean = false

    val movementUpdated = PublishSubject.create<Movement>()
    val movementImagesSize = PublishSubject.create<Int>()

    val livePicture = MutableLiveData<Boolean>()
    val imagePager = ImagePager(application)

    val connectionState = MutableLiveData<DeviceConnection.ConnectionState>()

    val nightMode = MutableLiveData<Boolean>()

    val motionDetection = MutableLiveData<Boolean>()


    private var serviceBroadcastReceiver: BroadcastReceiver? = null

    private val loaderJob = Job()
    private val loaderScope = CoroutineScope(Dispatchers.IO + loaderJob)

    private val uiScope = UiLifecycleScope()

    private lateinit var connDisposable: Disposable
    private var disposables = CompositeDisposable()

    init {
        livePicture.value = false
        motionDetection.value = false
    }

    fun onSwitchNightMode(view: View, nightMode: Boolean) {
        Log.i(TAG, "switching night mode to $nightMode")
        service?.conn?.updateConfig(Configuration(nightMode = nightMode))
    }

    fun onSwitchMotionDetection(view: View, motionDetection: Boolean) {
        service?.conn?.updateConfig(Configuration(motionDetection = motionDetection))
    }

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

        disposables.add(conn.volumes.observeOn(AndroidSchedulers.mainThread()).subscribe { vol ->
            volumeSeries
                    .appendData(DataPoint(vol.time, vol.volume.toDouble()), true, MAX_GRAPH_ELEMENTS)
        })

        disposables.add(
                conn.volumes
                        .debounce(100, TimeUnit.MILLISECONDS)
                        .subscribe { volumeUpdated.onNext(it) }
        )

        disposables.add(
                conn.config.subscribe { cfg ->
                    Log.d(TAG, "got new config$cfg")
                    if (cfg.motionDetection != null) {
                        motionDetection.postValue(cfg.motionDetection)
                    }
                    if (cfg.nightMode != null) {
                        nightMode.postValue(cfg.nightMode)
                    }
                }
        )

        disposables.add(conn.movement.subscribe { movementUpdated.onNext(it) })


        val movementPictureLoadedDisp = conn.movement
                .observeOn(Schedulers.io())
                .startWith(Movement(0.0, false, 0))
                .map {
                    try {
                        val motionURL = "http://${conn.device.hostname}:8081/latest"
                        val url = URL(motionURL)
                        Log.d(TAG, "loading image from $motionURL in thread ${Thread.currentThread().name}")
                        val connection = url.openConnection()
                        val pictureTime = connection.getHeaderField("picture-time")
                        val inputStream = connection.content as InputStream
                        Log.d(TAG, "...success!")
                        val time = Instant.ofEpochSecond(pictureTime.toLong())

                                Babyphone.TimedDrawable(
                                        Drawable.createFromStream(inputStream, time.toEpochMilli().toString()),
                                        time
                                )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading image $e")
                        Babyphone.TimedDrawable.INVALID
                    }
                }
                .filter {it != Babyphone.TimedDrawable.INVALID}
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe{
                    Log.d(TAG, "adding image to imagepager in thread ${Thread.currentThread().name}")

                    this.imagePager.addImage(it)
                }

        disposables.add(movementPictureLoadedDisp)

    }

    override fun onCleared() {
        super.onCleared()
        disposables.clear()
        connDisposable.dispose()
    }


    companion object {
        val MAX_GRAPH_ELEMENTS = 120
        const val TAG = "monitor-mv"
    }

    class Factory(val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MonitorViewModel(application) as T
        }
    }
}

