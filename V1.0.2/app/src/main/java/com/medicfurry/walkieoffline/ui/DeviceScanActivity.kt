package com.medicfurry.walkieoffline.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.medicfurry.walkieoffline.R
import com.medicfurry.walkieoffline.ble.BleScanner
import com.medicfurry.walkieoffline.databinding.ActivityDeviceScanBinding
import com.medicfurry.walkieoffline.databinding.ItemDeviceBinding
import kotlinx.coroutines.launch

/**
 * 设备扫描页：扫描附近的 BLE 对讲机，选择后进入主页面。
 */
class DeviceScanActivity : BaseActivity() {

    private lateinit var binding: ActivityDeviceScanBinding
    private lateinit var scanner: BleScanner
    private lateinit var adapter: DeviceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.title = getString(R.string.scan_title)

        scanner = BleScanner(this)
        adapter = DeviceAdapter { device ->
            // 选择设备 → 进入主页面并立即发起 GATT 连接
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("device_name", device.name)
                putExtra("device_address", device.address)
                putExtra("device", device.device)
            }
            startActivity(intent)
        }

        binding.deviceList.layoutManager = LinearLayoutManager(this)
        binding.deviceList.adapter = adapter

        binding.refreshBtn.setOnClickListener { scanner.startScan() }

        lifecycleScope.launch {
            scanner.devices.collect { devices ->
                adapter.submit(devices.values.toList())
                binding.emptyText.visibility =
                    if (devices.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        // 自动开始扫描
        scanner.startScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner.stopScan()
    }

    /** 设备列表适配器：按 RSSI 从强到弱排序，点击整行即选择该设备 */
    private inner class DeviceAdapter(
        private val onClick: (BleScanner.ScanDevice) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.VH>() {

        private var items: List<BleScanner.ScanDevice> = emptyList()

        /** 扫描结果每次回调都会刷新整表（设备数量少，直接 notifyDataSetChanged 足够） */
        fun submit(list: List<BleScanner.ScanDevice>) {
            items = list.sortedByDescending { it.rssi }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class VH(private val b: ItemDeviceBinding) : RecyclerView.ViewHolder(b.root) {
            /** 绑定设备名 / MAC / 信号强度，点击整行回调 onClick */
            fun bind(device: BleScanner.ScanDevice) {
                b.deviceName.text = device.name
                b.deviceAddress.text = device.address
                b.deviceRssi.text = "RSSI: ${device.rssi} dBm"
                b.root.setOnClickListener { onClick(device) }
            }
        }
    }
}
