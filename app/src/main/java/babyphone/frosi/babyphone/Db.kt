package babyphone.frosi.babyphone

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.room.*


@Database(entities = arrayOf(Device::class), version = 1)
abstract class DeviceDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao

    companion object {
        @Volatile
        private var INSTANCE: DeviceDatabase? = null

        fun getDatabase(context: Context): DeviceDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                        context.applicationContext,
                        DeviceDatabase::class.java,
                        "device_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices")
    fun getAll(): LiveData<List<Device>>

    @Insert
    suspend fun insert(device: Device)

    @Delete
    suspend fun delete(device: Device)
}


@Entity(tableName = "devices")
data class Device(
        @PrimaryKey(autoGenerate = true)
        var id: Int = 0,

        val hostname: String,

        val hostIp: String
)


class DeviceRepository(private val deviceDao: DeviceDao) {

    val allDevices: LiveData<List<Device>> = deviceDao.getAll()

    @WorkerThread
    suspend fun insert(device: Device) {
        deviceDao.insert(device)
    }

    @WorkerThread
    suspend fun delete(device: Device) {
        deviceDao.delete(device)
    }
}