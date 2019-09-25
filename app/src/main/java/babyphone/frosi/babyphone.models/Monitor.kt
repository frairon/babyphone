package babyphone.frosi.babyphone.models

import android.app.Application
import android.content.BroadcastReceiver
import androidx.lifecycle.AndroidViewModel
import babyphone.frosi.babyphone.ConnectionService
import babyphone.frosi.babyphone.ImagePager
import babyphone.frosi.babyphone.UiLifecycleScope
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries
import com.jjoe64.graphview.series.PointsGraphSeries
import io.reactivex.disposables.CompositeDisposable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job


class MonitorModel(application: Application) : AndroidViewModel(application) {

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

    private var disposables = CompositeDisposable()

//    private val imagePager = ImagePager(this)

    fun connectService(service: ConnectionService) {
        this.service = service

        // TODO do the wiring
    }
}