package com.mtgscanbuild

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.mtgscanbuild.data.AppDatabase
import com.mtgscanbuild.data.AppSettings
import com.mtgscanbuild.scan.ScanSounds
import com.mtgscanbuild.data.CardNameIndex
import com.mtgscanbuild.data.Repository
import com.mtgscanbuild.data.ScryfallApi
import com.mtgscanbuild.data.PlanAccess
import com.mtgscanbuild.data.ProEntitlements
import okhttp3.OkHttpClient

class MtgApp : Application(), ImageLoaderFactory {
    val access = PlanAccess()
    lateinit var entitlements: ProEntitlements
        private set
    lateinit var repo: Repository
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var sounds: ScanSounds
        private set

    override fun onCreate() {
        super.onCreate()
        initializeBackendProtection()
        entitlements = createProEntitlements(this, access)
        val api = ScryfallApi()
        repo = Repository(this, AppDatabase.create(this), api, CardNameIndex(filesDir, api), access = access)
        settings = AppSettings(this, access)
        sounds = ScanSounds(this, settings)
    }

    // Scryfall's image CDN rejects OkHttp's default User-Agent (HTTP 400), so card images need our own.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient {
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", "MTGScanBuild/1.0").build())
                }
                .build()
        }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("card_images")).maxSizeBytes(250L * 1024 * 1024).build() }
        .crossfade(true)
        .build()
}
