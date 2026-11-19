package com.rajdeep.vesper

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.room.Room
import androidx.work.Configuration
import com.rajdeep.vesper.core.crypto.LocalKeys
import com.rajdeep.vesper.core.db.VesperDatabase
import com.rajdeep.vesper.core.session.SessionManager
import com.rajdeep.vesper.data.AppLifecycle
import com.rajdeep.vesper.data.Messages
import com.rajdeep.vesper.data.SyncWorker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Inject
import javax.inject.Singleton

@HiltAndroidApp
class VesperApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var session: SessionManager
    @Inject lateinit var lifecycle: AppLifecycle
    @Inject lateinit var messages: Messages

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        lifecycle.install()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            session.load()
            if (session.current() != null) lifecycle.onSignedIn()
        }
        SyncWorker.schedule(this)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext ctx: Context): VesperDatabase {
        System.loadLibrary("sqlcipher")
        val pass = LocalKeys.databasePassphrase(ctx)
        return Room.databaseBuilder(ctx, VesperDatabase::class.java, "vesper.db")
            .openHelperFactory(SupportOpenHelperFactory(pass))
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }
}
