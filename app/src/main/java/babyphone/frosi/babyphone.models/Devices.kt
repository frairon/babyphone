package babyphone.frosi.babyphone.models

import android.app.Application
import android.content.Context
import android.text.TextUtils
import android.util.Log
import androidx.lifecycle.*
import babyphone.frosi.babyphone.*
import babyphone.frosi.babyphone.R
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import kotlinx.android.synthetic.main.devices_current.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode


class DeviceViewModel(application: Application) : AndroidViewModel(application) {
    val allDevices: LiveData<List<Device>>

    val disconnectedDevices = MutableLiveData<List<Device>>()

    val activeDevice = MutableLiveData<Device>()

    val connectionState = MutableLiveData<DeviceConnection.ConnectionState>()

    private var service: ConnectionService? = null

    private val repository: DeviceRepository = DeviceRepository(DeviceDatabase.getDatabase(application).deviceDao())

    private val discovery = Discovery()

    private lateinit var connDisposable: Disposable
    private var disposables = CompositeDisposable()

    init {
        allDevices = repository.allDevices
        allDevices.observeForever {
            discover()
            updateDisconnectedDevices()
        }
        activeDevice.observeForever {
            discover()
            updateDisconnectedDevices()
        }
        EventBus.getDefault().register(this)
        updateDisconnectedDevices()

        // default is disconnected
        connectionState.value = DeviceConnection.ConnectionState.Disconnected

        discovery.start()
    }

    fun connectDevice(device: Device) {
        service?.connect(device, true)
        this.activeDevice.postValue(device)
    }

    fun connectService(service: ConnectionService) {
        this.service = service

        connDisposable = service.connections.subscribe { conn ->
            Log.i("deviceModel", "getting new connection $conn")
            this.updateConnection(conn)
        }
    }

    private fun updateConnection(conn: DeviceConnection) {
        // clear subscribers of old connection, if any
        disposables.clear()
        disposables = CompositeDisposable()

        // wire to the new connection
        disposables.add(conn.connectionState.subscribe { n ->
            this.connectionState.postValue(n)
            this.pingDevices()
            if (n == DeviceConnection.ConnectionState.Disconnected) {
                this.activeDevice.postValue(null)
            }
        })
        disposables.add(conn.connectionState.subscribe { n -> pingDevices() })
        this.activeDevice.postValue(conn.device)
    }

    fun discover() {
        // start recovery every time we go back to the activity somehow
        viewModelScope.launch(Dispatchers.IO) {
            discovery.discover()
        }

        this.pingDevices()
    }

    private fun updateDisconnectedDevices() {
        val dis = allDevices.value?.filter { x -> x != activeDevice.value }
//        val sorted = dis?.sortedWith(Device.Comparator())
        disconnectedDevices.postValue(dis)
    }

    fun insert(device: Device) = viewModelScope.launch(Dispatchers.IO) {
        repository.insert(device)
    }

    fun delete(device: Device) = viewModelScope.launch(Dispatchers.IO) {
        repository.delete(device)
    }

    private fun pingDevices() {
        allDevices.value?.forEach { dev ->
            viewModelScope.launch(Dispatchers.IO) {
                dev.alive = this@DeviceViewModel.discovery.checkHostIsAlive(dev.hostname)
                updateDisconnectedDevices()
            }
        }
    }


    @Subscribe(threadMode = ThreadMode.MAIN)
    fun handleBabyphoneAdvertise(adv: Advertise) {
        val existing = allDevices.value?.find { device ->
            TextUtils.equals(device.hostname.toLowerCase().trim(),
                    adv.host.toLowerCase().trim())
        }

        if (existing != null) {
            existing.alive = true
            updateDisconnectedDevices()
        } else {
            val newDev = Device(hostname = adv.host.toLowerCase().trim(), hostIp = "some-IP")
            newDev.alive = true
            insert(newDev)
        }
    }


    override fun onCleared() {
        super.onCleared()
        discovery.stop()
        disposables.clear()
        connDisposable.dispose()
        EventBus.getDefault().unregister(this)
    }
}

class DeviceViewModelFactory(val application: Application) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return DeviceViewModel(application) as T
    }
}

class ViewUtils(private val ctx: Context) {
    fun connectionState(cs: DeviceConnection.ConnectionState): String {
        return when (cs) {
            DeviceConnection.ConnectionState.Connecting -> ctx.getString(R.string.text_connecting)
            DeviceConnection.ConnectionState.Connected -> ctx.getString(R.string.text_connected)
            else -> cs.toString()
        }
    }

}