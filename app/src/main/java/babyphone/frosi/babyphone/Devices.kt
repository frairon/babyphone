package babyphone.frosi.babyphone

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.TextUtils
import android.util.Log
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.RecyclerView
import babyphone.frosi.babyphone.databinding.ActivityDevicesBinding
import babyphone.frosi.babyphone.databinding.DevicesItemBinding
import babyphone.frosi.babyphone.models.DeviceViewModel
import babyphone.frosi.babyphone.models.DeviceViewModelFactory
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.android.synthetic.main.activity_devices.*
import kotlinx.android.synthetic.main.devices_current.*


class DeviceListAdapter internal constructor(
        val context: Devices,
        private val model: DeviceViewModel
) : RecyclerView.Adapter<DeviceListAdapter.DeviceViewHolder>() {
    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private var devices = emptyList<Device>() // Cached copy of devices

    inner class DeviceViewHolder(val binding: DevicesItemBinding) : RecyclerView.ViewHolder(binding.root), View.OnClickListener {

        init {
            itemView.findViewById<View>(R.id.btn_connect).setOnClickListener(this)
            itemView.findViewById<View>(R.id.btn_delete).setOnClickListener(this)
        }

        override fun onClick(v: View?) {
            val device = devices.get(this.getAdapterPosition())
            when (v?.id) {
                R.id.btn_connect -> {
                    context.connectToDevice(device)
                }
                R.id.btn_delete -> {
                    model.delete(device)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = DataBindingUtil.inflate<DevicesItemBinding>(inflater, R.layout.devices_item, parent, false)
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val current = devices[position]
        holder.binding.device = current
    }

    internal fun setDevices(devices: List<Device>) {
        this.devices = devices
        notifyDataSetChanged()
    }

    override fun getItemCount() = devices.size
}

class Devices : AppCompatActivity(), ServiceConnection, View.OnClickListener {

    companion object {
        const val newConnectionActivityRequestCode = 1
    }

    private lateinit var devicesViewModel: DeviceViewModel

    private var service: ConnectionService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i("devices", "oncreate")

        devicesViewModel = ViewModelProviders
                .of(this, DeviceViewModelFactory(this.application))
                .get(DeviceViewModel::class.java)

        val binding = DataBindingUtil.setContentView<ActivityDevicesBinding>(this, R.layout.activity_devices)
        binding.deviceViewModel = devicesViewModel
        binding.lifecycleOwner = this
        setSupportActionBar(toolbar)

        this.btn_disconnect.setOnClickListener(this)
        this.btn_monitor.setOnClickListener(this)
        this.btn_menu.setOnClickListener(this)
        this.fab.setOnClickListener(this)

        val adapter = DeviceListAdapter(this, devicesViewModel)
        val recyclerView = findViewById<RecyclerView>(R.id.devices_list)
        recyclerView.adapter = adapter

        devicesViewModel.disconnectedDevices.observe(this, Observer { devices ->
            devices?.let { adapter.setDevices(it) }
        })

        val componentName = this.startService(Intent(this, ConnectionService::class.java))
        if (componentName == null) {
            throw RuntimeException("Could not start connection service. does not exist")
        }
        this.bindService(Intent(this, ConnectionService::class.java), this, 0)

    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        getMenuInflater().inflate(R.menu.menu_devices, menu);
        return true;
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.action_exit -> {
                this.exit()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        Log.i("devices", "onresume")
        devicesViewModel.discover()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.btn_disconnect -> {
                service?.disconnect()
            }
            R.id.btn_monitor -> {
                val intent = Intent(this, Babyphone::class.java)
                this.startActivity(intent)
            }
            R.id.btn_menu -> {
                val popup = PopupMenu(this, v)
                val inflater = popup.menuInflater
                inflater.inflate(R.menu.popup_active_conn, popup.menu)
                popup.show()
                popup.setOnMenuItemClickListener { v ->
                    when (v?.itemId) {
                        R.id.action_shutdown -> {
                            service?.conn?.shutdown()
                            true
                        }
                        R.id.action_restart -> {
                            Toast.makeText(this, "Not implemented", Toast.LENGTH_SHORT)
                            service?.conn?.restart()
                            true
                        }
                    }
                    false
                }
            }
            R.id.fab -> {
                this.startActivityForResult(Intent(this, NewConnection::class.java),
                        newConnectionActivityRequestCode)
            }
        }
    }

    fun connectToDevice(device: Device) {

        if (devicesViewModel.activeDevice.value != null ||
                devicesViewModel.connectionState.value != DeviceConnection.ConnectionState.Disconnected) {

            MaterialAlertDialogBuilder(this)
                    .setTitle("Active Connection")
                    .setMessage("Another connection is already active. This will be terminated. Continue?")
                    .setPositiveButton("Yes") { _, _ ->
                        devicesViewModel.connectDevice(device)
                    }
                    .setNegativeButton("No", null)
                    .show();
        } else {
            devicesViewModel.connectDevice(device)
        }

    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        Log.i("devices", "service connected")
        val svc = (service as ConnectionService.ConnectionServiceBinder).service

        if (svc == null) {
            return
        }
        devicesViewModel.connectService(svc)
        this.service = svc
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        this.service = null
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
        Log.i("devices", "onDestroy")
        this.unbindService(this)
        super.onDestroy()
    }

    private fun exit() {
        this.service?.disconnect()
        this.stopService(Intent(this, ConnectionService::class.java))
        this.finishAffinity()
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
                hostName.error = null
            }

            if (TextUtils.isEmpty(ip.text)) {
                ip.error = "Please specify an IP"
                error = true
            } else {
                ip.error = null
            }
            if (!error) {
                replyIntent.putExtra(EXTRA_HOSTNAME, hostName.text.toString())
                replyIntent.putExtra(EXTRA_IP, ip.text.toString())
                setResult(Activity.RESULT_OK, replyIntent)
                finish()
            }
        }
    }
}
