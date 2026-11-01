package com.lfcreative.geartracker.di

import android.content.Context
import androidx.room.Room
import com.lfcreative.geartracker.BuildConfig
import com.lfcreative.geartracker.data.auth.AuthRepository
import com.lfcreative.geartracker.data.auth.PrefsSessionManager
import com.lfcreative.geartracker.data.auth.SupabaseAuthRepository
import com.lfcreative.geartracker.data.local.AppDatabase
import com.lfcreative.geartracker.data.outbox.EventOutbox
import com.lfcreative.geartracker.data.outbox.OutboxRepository
import com.lfcreative.geartracker.feedback.FeedbackPlayer
import com.lfcreative.geartracker.feedback.FeedbackSink
import com.lfcreative.geartracker.session.TagResolver
import com.lfcreative.geartracker.session.TagResolving
import java.time.Clock
import com.lfcreative.geartracker.data.settings.SessionSettings
import com.lfcreative.geartracker.data.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "geartracker.db").build()

    @Provides fun tagDao(db: AppDatabase) = db.tags()
    @Provides fun assetDao(db: AppDatabase) = db.assets()
    @Provides fun locationDao(db: AppDatabase) = db.locations()
    @Provides fun projectDao(db: AppDatabase) = db.projects()
    @Provides fun openCheckoutDao(db: AppDatabase) = db.openCheckouts()
    @Provides fun outboxDao(db: AppDatabase) = db.outbox()

    @Provides @Singleton
    fun clock(): Clock = Clock.systemUTC()

    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The server URL is read once at startup; changing it in Settings takes effect after a restart. */
    @Provides @Singleton
    fun supabaseClient(@ApplicationContext context: Context, settings: SettingsRepository): SupabaseClient =
        createSupabaseClient(
            supabaseUrl = settings.effectiveServerUrl,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY.ifBlank { "anon-key-not-configured" },
        ) {
            install(Auth) { sessionManager = PrefsSessionManager(context) }
            install(Postgrest)
        }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun authRepository(impl: SupabaseAuthRepository): AuthRepository
    @Binds abstract fun tagResolving(impl: TagResolver): TagResolving
    @Binds abstract fun eventOutbox(impl: OutboxRepository): EventOutbox
    @Binds abstract fun feedbackSink(impl: FeedbackPlayer): FeedbackSink
    @Binds abstract fun sessionSettings(impl: SettingsRepository): SessionSettings
}
