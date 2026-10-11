package com.medicfurry.walkieoffline.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.medicfurry.walkieoffline.databinding.ActivityPermissionBinding

/**
 * 权限申请流程：
 * 提示用户 BLE 需同时授予定位权限（但不获取定位信息）。
 * 点击「我已知道」后申请权限，完成后进入设备扫描。
 */
class PermissionActivity : BaseActivity() {

    private lateinit var binding: ActivityPermissionBinding

    private val requiredPermissions: Array<String>
        get() = buildList {
            // Android 12+ 需要 BLUETOOTH_SCAN / BLUETOOTH_CONNECT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            // 定位权限：按需求全版本申请（安卓 BLE 扫描的历史限制，
            // 本 app 不获取任何定位信息，仅为满足系统要求）
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }.toTypedArray()

    companion object {
        private const val REQ_PERMISSIONS = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.confirmBtn.setOnClickListener {
            requestPermissions()
        }
    }

    private fun requestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            // 已全部授予，直接进入扫描
            goToScan()
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            goToScan()
        }
    }

    private fun goToScan() {
        startActivity(Intent(this, DeviceScanActivity::class.java))
        finish()
    }
}
