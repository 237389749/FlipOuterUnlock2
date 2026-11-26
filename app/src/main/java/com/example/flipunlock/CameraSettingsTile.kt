package com.example.flipunlock

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * QS 磁贴「相机设置」（2026-11-26）。
 *
 * 外屏（折叠）下 MIUI 相机界面**没有设置入口**（`uiautomator dump` 里连"设置"都没有），
 * 而 `com.android.camera/.CameraPreferenceActivity` 是 exported 的，也带着
 * `miui.intent.action.APP_SETTINGS` 与 `QS_TILE_PREFERENCES` filter。
 * 相机自带的 `DocumentTileService`（文档扫描磁贴）在设备上被禁用（`lastDisabledCaller=com.android.camera`），
 * 所以做这个模块自己的磁贴当入口。
 *
 * 用法：QS 编辑 → 添加「相机设置」→ 点一下直接进相机设置页。
 */
class CameraSettingsTile : TileService() {

    override fun onClick() {
        val intent = Intent("miui.intent.action.APP_SETTINGS").apply {
            component = ComponentName(
                "com.android.camera",
                "com.android.camera.CameraPreferenceActivity"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }
}