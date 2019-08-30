package babyphone.frosi.babyphone

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.*
import androidx.recyclerview.widget.RecyclerView

import kotlinx.android.synthetic.main.activity_devices.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.sql.Connection


class DeviceViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: DeviceRepository

    val allDevices: LiveData<List<Device>>

    init {
        repository = DeviceRepository(DeviceDatabase.getDatabase(application).deviceDao())
        allDevices = repository.allDevices
    }

    fun insert(device: Device) = viewModelScope.launch(Dispatchers.IO) {
        repository.insert(device)
    }

    fun delete(device: Device) = viewModelScope.launch(Dispatchers.IO) {
        repository.delete(device)
    }
}

class DeviceViewModelFactory(val application: Application) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return DeviceViewModel(application) as T
    }
}

class DeviceListAdapter internal constructor(
        context: Context,
        private val model: DeviceViewModel
) : RecyclerView.Adapter<DeviceListAdapter.DeviceViewHolder>() {
    private val ctx = context
    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private var devices = emptyList<Device>() // Cached copy of devices

    inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val hostname = itemView.findViewById(R.id.hostname) as TextView
        val ip = itemView.findViewById(R.id.ip) as TextView
        val btnConnect = itemView.findViewById(R.id.btn_connect) as MaterialButton
        val btnDelete = itemView.findViewById(R.id.btn_delete) as MaterialButton
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val itemView = inflater.inflate(R.layout.devices_item, parent, false)
        return DeviceViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val current = devices[position]
        holder.hostname.text = current.hostname
        holder.ip.text = current.hostIp

        holder.btnDelete.setOnClickListener {
            model.delete(current)
        }
        holder.btnConnect.setOnClickListener {
            val intent = Intent(ctx, Babyphone::class.java)
            intent.putExtra(Babyphone.EXTRA_DEVICE_ADDR, holder.hostname.text.toString().trim())
            ctx.startActivity(intent)
        }
    }

    internal fun setDevices(devices: List<Device>) {
        this.devices = devices
        notifyDataSetChanged()
    }

    override fun getItemCount() = devices.size
}

class Devices : AppCompatActivity(), ServiceConnection {

    companion object {
        const val newConnectionActivityRequestCode = 1
    }

    private lateinit var devicesViewModel: DeviceViewModel

    private var service: ConnectionService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_devices)
        setSupportActionBar(toolbar)

        devicesViewModel = ViewModelProviders
                .of(this, DeviceViewModelFactory(this.application))
                .get(DeviceViewModel::class.java)


        val adapter = DeviceListAdapter(this, devicesViewModel)
        val recyclerView = findViewById<RecyclerView>(R.id.devices_list)
        recyclerView.adapter = adapter

        devicesViewModel.allDevices.observe(this, Observer { devices ->
            // Update the cached copy of the words in the adapter.
            devices?.let { adapter.setDevices(it) }
        })

        create_connection.setOnClickListener { view ->
            this.startActivityForResult(Intent(this, NewConnection::class.java),
                    newConnectionActivityRequestCode)
        }


        val componentName = this.startService(Intent(this, ConnectionService::class.java))
        if (componentName == null) {
            throw RuntimeException("Could not start connection service. does not exist")
        }
        this.bindService(Intent(this, ConnectionService::class.java), this, 0)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun handleBabyphoneAdvertise(adv: Advertise) {
        // TODO create a new element
//        val hostInput = this.findViewById<View>(R.id.text_host) as TextView
//        hostInput.text = adv.host
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        Log.i("devices", "service connected")
        this.service = (service as ConnectionService.ConnectionServiceBinder).service

        if (this.service == null) {
            return
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == newConnectionActivityRequestCode && resultCode == Activity.RESULT_OK) {
            data?.let {
                val device = Device(hostname = it.getStringExtra(NewConnection.EXTRA_HOSTNAME),
                        hostIp = it.getStringExtra(NewConnection.EXTRA_IP))
                devicesViewModel.insert(device)
            }
        } else {
            Toast.makeText(
                    applicationContext,
                    "not saved",
                    Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {

        Log.i("device", "onDestroy")
        this.service?.disconnect()
        this.unbindService(this)
        this.stopService(Intent(this, ConnectionService::class.java))

        super.onDestroy()
    }
}


class NewConnection : AppCompatActivity() {


    companion object {
        const val EXTRA_HOSTNAME = "babyphone.frosi.babyphone.connection.hostname"
        const val EXTRA_IP = "babyphone.frosi.babyphone.connection.ip"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_connection)

        val hostName = findViewById(R.id.host_name) as TextView
        val ip = findViewById(R.id.ip) as TextView


        val button = findViewById(R.id.btn_create) as View
        button.setOnClickListener {
            val replyIntent = Intent()
            var error = false
            if (TextUtils.isEmpty(hostName.text)) {
                hostName.error = "Please specify a hostname"
                error = true
            } else {
                hostName.error = ""
            }

            if (TextUtils.isEmpty(ip.text)) {
                ip.error = "Please specify an IP"
                error = true
            } else {
                ip.error = ""
            }
            if (!error) {
                replyIntent.putExtra(EXTRA_HOSTNAME, hostName.text.toString())
                replyIntent.putExtra(EXTRA_IP, ip.text.toString())
                setResult(Activity.RESULT_OK, replyIntent)
            }
            finish()
        }
    }
}
