package com.seagull.htv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.seagull.htv.data.Site

class App : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { Site.client }
            .crossfade(true)
            .build()
}
