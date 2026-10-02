package com.liuli.btchat.bt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 蓝牙运行时权限。
 *
 * Android 12（API 31）起经典蓝牙被拆成两个运行时权限：
 * `BLUETOOTH_CONNECT`（建链 / 读设备名 / 读已配对列表）与 `BLUETOOTH_SCAN`（发现设备）。
 * 更早的版本沿用安装期权限 `BLUETOOTH` / `BLUETOOTH_ADMIN`，而发现设备还需要
 * 定位权限（`ACCESS_FINE_LOCATION`）。
 *
 * 本对象只做判断，不申请权限 —— 申请由 UI 层负责。
 */
object BtPermissions {

    /**
     * 当前系统版本下需要申请的全部运行时权限。
     *
     * 返回的是「完整可用」（连接 + 扫描）所需的集合。
     */
    fun required(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        listOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    /** [required] 中的权限是否全部授予。 */
    fun has(ctx: Context): Boolean = required().all { granted(ctx, it) }

    /** 尚未授予的权限，可直接喂给 `requestPermissions`。 */
    fun missing(ctx: Context): List<String> = required().filterNot { granted(ctx, it) }

    /** 能否建链 / 读已配对设备 / 查看本机名称。 */
    fun canConnect(ctx: Context): Boolean = granted(
        ctx,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT
        else Manifest.permission.BLUETOOTH
    )

    /** 能否扫描周围设备。 */
    fun canScan(ctx: Context): Boolean = granted(
        ctx,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN
        else Manifest.permission.ACCESS_FINE_LOCATION
    )

    private fun granted(ctx: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
}
