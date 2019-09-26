package babyphone.frosi.babyphone.models

import android.app.Application
import android.content.BroadcastReceiver
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import babyphone.frosi.babyphone.*
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries
import com.jjoe64.graphview.series.PointsGraphSeries
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.PublishSubject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.TimeUnit


class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    var service: ConnectionService? = null

    val volumeSeries = LineGraphSeries<DataPoint>()
    val volumeUpdated = PublishSubject.create<Volume>()
    val thresholdSeries = LineGraphSeries<DataPoint>()
    val alarmSeries = PointsGraphSeries<DataPoint>()
    val movementSeries = PointsGraphSeries<DataPoint>()
    val useLights: Boolean = false

    val livePicture = MutableLiveData<Boolean>()

    private var serviceBroadcastReceiver: BroadcastReceiver? = null

    private val loaderJob = Job()
    private val loaderScope = CoroutineScope(Dispatchers.IO + loaderJob)

    private val uiScope = UiLifecycleScope()

    private var disposables = CompositeDisposable()

    init {
        livePicture.value = false
    }

//    private val imagePager = ImagePager(this)

    fun connectService(service: ConnectionService) {
        this.service = service

        // TODO do the wiring

        disposables.add(service.connections.subscribe { conn ->
            this.updateConnection(conn)

        })
    }

    private fun updateConnection(conn: DeviceConnection) {

        // clear subscribers of old connection, if any
        disposables.clear()
        disposables = CompositeDisposable()

        conn.volumes.subscribe { vol ->
            volumeSeries
                    .appendData(DataPoint(vol.time, vol.volume.toDouble()), true, MAX_GRAPH_ELEMENTS)
        }

        disposables.add(
                conn.volumes
                        .debounce(100, TimeUnit.MILLISECONDS)
                        .subscribe { n -> volumeUpdated.onNext(n) }
        )
        /*volumeSeries.appendData(newPoint, true, MAX_GRAPH_ELEMENTS)
            val graph = this.findViewById(R.id.graph_volume) as GraphView
            graph.viewport.setMinX(volumeSeries.lowestValueX)
            graph.viewport.setMaxX(volumeSeries.highestValueX)*/
    }

    override fun onCleared() {
        super.onCleared()
        disposables.clear()
    }


    companion object {
        val MAX_GRAPH_ELEMENTS = 120
    }
}

class MonitorViewModelFactory(val application: Application) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MonitorViewModel(application) as T
    }
}