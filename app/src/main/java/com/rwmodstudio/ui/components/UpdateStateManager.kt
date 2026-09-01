package com.rwmodstudio.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.rwmodstudio.RwModApplication
import com.rwmodstudio.core.UpdateChecker
import com.rwmodstudio.core.UpdateDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 进程级更新状态管理器：弹窗状态与下载协程挂在应用进程生命周期上，
 * 因此切换页面、返回首页、切到其他页面都不会让进度丢失或弹窗消失。
 *
 * - 状态用全局 [state]（任何页面组合读取渲染）
 * - 下载协程跑在 [RwModApplication].applicationScope（进程存活则持续下载）
 * - 下载完成自动调起安装并置为 [UpdateDialogState.ReadyToInstall] 提示
 */
object UpdateStateManager {

    /** 全局更新弹窗状态（null 表示不显示） */
    val state: MutableState<UpdateDialogState?> = mutableStateOf(null)

    /** 手动检查更新：结果写入全局状态（检查中 / 有新版本 / 无新版本 / 失败） */
    fun checkForUpdate(currentVersion: String) {
        if (state.value is UpdateDialogState.Checking) return
        state.value = UpdateDialogState.Checking
        RwModApplication.getInstance().applicationScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val info = UpdateChecker.fetchLatest()
                    if (UpdateChecker.isNewerThan(info.versionName, currentVersion)) {
                        UpdateDialogState.Available(info)
                    } else {
                        UpdateDialogState.NoUpdate
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // 协程取消必须重新抛出，否则取消被当「检查失败」显示
                } catch (e: Exception) {
                    UpdateDialogState.Error(
                        message = e.message ?: "未知错误",
                        title = "检查更新失败"
                    )
                }
            }
            state.value = result
        }
    }

    /**
     * 开始下载更新。运行在进程级作用域，切页/回首页不中断。
     * @param context 用于调起系统安装界面
     */
    fun startDownload(context: Context, info: UpdateChecker.UpdateInfo) {
        val url = info.apkUrl
        if (url == null) {
            // 无 APK 直链：回退到浏览器打开详情页
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl))
                )
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "无法打开下载页面", android.widget.Toast.LENGTH_SHORT).show()
            }
            state.value = null
            return
        }
        state.value = UpdateDialogState.Downloading(info, 0, -1)
        val target = UpdateDownloader.apkTargetFile(info.versionName)
        RwModApplication.getInstance().applicationScope.launch {
            try {
                val downloaded = withContext(Dispatchers.IO) {
                    UpdateDownloader.downloadApk(
                        url = url,
                        targetFile = target,
                        onProgress = { bytes, total ->
                            // IO 线程写 Compose 状态是快照安全的
                            state.value = UpdateDialogState.Downloading(info, bytes, total)
                        }
                    )
                }
                // 安装调起必须切主线程：Android 12+ 从后台线程 startActivity 会被拦截，部分机型安装界面调不起来
                withContext(Dispatchers.Main) {
                    UpdateDownloader.installApk(context.applicationContext, downloaded)
                }
                state.value = UpdateDialogState.ReadyToInstall(info)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // 下载协程取消（进程级 scope 一般不取消，防御处理）
            } catch (e: Exception) {
                state.value = UpdateDialogState.Error(
                    message = "下载失败：${e.message ?: "未知错误"}",
                    title = "下载失败"
                )
            }
        }
    }

    /** 关闭当前弹窗（用户点确定/完成/隐藏进度等） */
    fun dismiss() {
        state.value = null
    }
}