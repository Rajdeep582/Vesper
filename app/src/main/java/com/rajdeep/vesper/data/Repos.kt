package com.rajdeep.vesper.data

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rajdeep.vesper.core.crypto.SignalKeygen
import com.rajdeep.vesper.core.db.UserEntity
import com.rajdeep.vesper.core.db.VesperDatabase
import com.rajdeep.vesper.core.net.Api
import com.rajdeep.vesper.core.net.Realtime
import com.rajdeep.vesper.core.session.SessionManager
import com.rajdeep.vesper.shared.ActivityEvent
import com.rajdeep.vesper.shared.ActivityPage
import com.rajdeep.vesper.shared.AdminActivity
import com.rajdeep.vesper.shared.AdminDeviceList
import com.rajdeep.vesper.shared.AdminMemberDetail
import com.rajdeep.vesper.shared.AdminOverview
import com.rajdeep.vesper.shared.AdminPresence
import com.rajdeep.vesper.shared.AuthRequest
import com.rajdeep.vesper.shared.AuthResponse
import com.rajdeep.vesper.shared.Availability
import com.rajdeep.vesper.shared.ConversationPage
import com.rajdeep.vesper.shared.HallPage
import com.rajdeep.vesper.shared.MemberPage
import com.rajdeep.vesper.shared.MuteRequest
import com.rajdeep.vesper.shared.Ok
import com.rajdeep.vesper.shared.PasswordChange
import com.rajdeep.vesper.shared.PasswordReset
import com.rajdeep.vesper.shared.PasswordConfirm
import com.rajdeep.vesper.shared.UserPage
import com.rajdeep.vesper.shared.UserSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: Api,
    private val session: SessionManager,
    private val db: VesperDatabase,
    private val messages: Messages,
    private val lifecycle: AppLifecycle,
    private val notifier: Notifier,
    private val push: PushRegistrar,
) {
    suspend fun availability(u: String): Availability =
        api.get("/v1/auth/available?u=" + URLEncoder.encode(u, "UTF-8"), Availability.serializer(), auth = false)

    /** Creates a fresh device identity (libsignal keys) and registers it with the server. */
    suspend fun authenticate(username: String, password: String, signUp: Boolean) {
        val gen = withContext(Dispatchers.Default) { SignalKeygen.newDevice(Build.MODEL ?: "Android") }
        val req = AuthRequest(username.trim().lowercase(), password, gen.registration)
        val res = api.post(if (signUp) "/v1/auth/signup" else "/v1/auth/signin", req, AuthRequest.serializer(), AuthResponse.serializer(), auth = false)
        withContext(Dispatchers.IO) {
            db.clearAllTables()
            session.start(res, gen.identity)
            gen.apply(messages.stores)
            db.users().upsert(listOf(UserEntity(res.user.id, res.user.username, res.user.joinedAt)))
        }
        lifecycle.onSignedIn()
        push.register()
    }

    suspend fun signOut() {
        runCatching { api.postEmpty("/v1/auth/signout", Ok.serializer()) }
        notifier.cancelAll()
        session.end("signed_out")
    }

    suspend fun changePassword(username: String, current: String, next: String) {
        api.post("/v1/me/password", PasswordChange(current, next, username), PasswordChange.serializer(), Ok.serializer())
    }

    /** Signed-out change: verified by username + current password; ends all sessions of that account. */
    suspend fun resetPassword(username: String, current: String, next: String) {
        api.post("/v1/auth/password", PasswordReset(username, current, next), PasswordReset.serializer(), Ok.serializer(), auth = false)
    }

    suspend fun deleteAccount(password: String) {
        api.post("/v1/me/delete", PasswordConfirm(password), PasswordConfirm.serializer(), Ok.serializer())
        notifier.cancelAll()
        session.end("deleted")
    }

    suspend fun myDevices(): AdminDeviceList = api.get("/v1/me/devices", AdminDeviceList.serializer())
}

@Singleton
class PeopleRepository @Inject constructor(private val api: Api, private val db: VesperDatabase) {
    suspend fun search(query: String, cursor: String? = null): UserPage {
        val q = URLEncoder.encode(query, "UTF-8")
        val page = api.get("/v1/users?query=$q" + (cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") } ?: ""), UserPage.serializer())
        db.users().upsert(page.items.map { UserEntity(it.id, it.username, it.joinedAt) })
        return page
    }

    suspend fun profile(id: String): UserSummary {
        db.users().get(id)?.takeIf { it.joinedAt > 0 }?.let { return UserSummary(it.id, it.username, it.joinedAt) }
        return api.get("/v1/users/$id", UserSummary.serializer()).also { db.users().upsert(listOf(UserEntity(it.id, it.username, it.joinedAt))) }
    }

    fun observe(id: String) = db.users().observe(id)
}

@Singleton
class AdminRepository @Inject constructor(private val api: Api, rt: Realtime) {
    val activity: Flow<ActivityEvent> = rt.frames.filterIsInstance<AdminActivity>().map { it.event }
    val presence: Flow<AdminPresence> = rt.frames.filterIsInstance<AdminPresence>()

    suspend fun overview() = api.get("/v1/admin/overview", AdminOverview.serializer())
    suspend fun members(q: String) = api.get("/v1/admin/members?query=" + URLEncoder.encode(q, "UTF-8"), MemberPage.serializer())
    suspend fun member(id: String) = api.get("/v1/admin/members/$id", AdminMemberDetail.serializer())
    suspend fun activityPage(before: Long? = null, type: String? = null) =
        api.get("/v1/admin/activity?" + listOfNotNull(before?.let { "before=$it" }, type?.let { "type=$it" }).joinToString("&"), ActivityPage.serializer())
    suspend fun conversations() = api.get("/v1/admin/conversations", ConversationPage.serializer())
    suspend fun hall(user: String? = null) = api.get("/v1/admin/hall" + (user?.let { "?user=$it" } ?: ""), HallPage.serializer())
    suspend fun suspend(id: String) = api.postEmpty("/v1/admin/members/$id/suspend", Ok.serializer())
    suspend fun unsuspend(id: String) = api.postEmpty("/v1/admin/members/$id/unsuspend", Ok.serializer())
    suspend fun mute(id: String, minutes: Long?) = api.post("/v1/admin/members/$id/mute", MuteRequest(minutes), MuteRequest.serializer(), Ok.serializer())
    suspend fun unmute(id: String) = api.postEmpty("/v1/admin/members/$id/unmute", Ok.serializer())
    suspend fun signOutAll(id: String) = api.postEmpty("/v1/admin/members/$id/signout", Ok.serializer())
    suspend fun deleteMember(id: String) = api.delete("/v1/admin/members/$id", Ok.serializer())
    suspend fun deleteHall(id: String) = api.delete("/v1/admin/hall/$id", Ok.serializer())
}

private val Context.prefs by preferencesDataStore("vesper_prefs")

enum class ThemeMode { SYSTEM, DARK, LIGHT }

data class Prefs(val theme: ThemeMode = ThemeMode.DARK, val sounds: Boolean = true, val haptics: Boolean = true, val reduceMotion: Boolean = false, val disclosureSeen: Boolean = false)

/** Non-sensitive UI preferences only. */
@Singleton
class Settings @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val theme = stringPreferencesKey("theme")
    private val sounds = booleanPreferencesKey("sounds")
    private val haptics = booleanPreferencesKey("haptics")
    private val motion = booleanPreferencesKey("reduce_motion")

    val prefs: Flow<Prefs> = ctx.prefs.data.map { p ->
        Prefs(
            theme = p[theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
            sounds = p[sounds] ?: true,
            haptics = p[haptics] ?: true,
            reduceMotion = p[motion] ?: false,
        )
    }

    suspend fun setTheme(t: ThemeMode) = ctx.prefs.edit { it[theme] = t.name }
    suspend fun setSounds(v: Boolean) = ctx.prefs.edit { it[sounds] = v }
    suspend fun setHaptics(v: Boolean) = ctx.prefs.edit { it[haptics] = v }
    suspend fun setReduceMotion(v: Boolean) = ctx.prefs.edit { it[motion] = v }
}
