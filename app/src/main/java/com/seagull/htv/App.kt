package com.seagull.htv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.seagull.htv.data.Site
import com.shuyu.gsyvideoplayer.player.PlayerFactory
import tv.danmaku.ijk.media.exo2.Exo2PlayerManager

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()

        // ★ 必须显式指定播放内核。
        // GSY 的 PlayerFactory.getPlayManager() 默认是 IjkPlayerManager，
        // 而 IjkPlayerManager 的静态字段 logLevel = IjkMediaPlayer.IJK_LOG_DEFAULT
        // 会在 new IjkMediaPlayer() 时触发其 <clinit> → System.loadLibrary("ijkffmpeg")。
        // 我们为了瘦身剔掉了 libijk*.so（占 APK 75%），所以默认内核必然 UnsatisfiedLinkError 闪退。
        // 指定 Exo2 后走 media3，既不碰 IJK 也不会加载那些 .so。
        PlayerFactory.setPlayManager(Exo2PlayerManager::class.java)

        CrashHandler.install(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { Site.client }
            .crossfade(true)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("img_cache"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .build()
}
