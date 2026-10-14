package com.medicfurry.walkieoffline.ble

/**
 * 当前 BLE 协议会话的全局持有者。
 * MainActivity 建连后注册，频道设置等子页面共用同一会话（不断连）。
 */
object ConnectorHolder {
    @Volatile
    var connector: BleConnector? = null
}
