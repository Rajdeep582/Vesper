package com.rajdeep.vesper

import com.rajdeep.vesper.ui.components.Reveal
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.People
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.rajdeep.vesper.core.session.SessionManager
import com.rajdeep.vesper.core.session.SessionState
import com.rajdeep.vesper.data.MediaRepository
import com.rajdeep.vesper.data.Messages
import com.rajdeep.vesper.data.Prefs
import com.rajdeep.vesper.data.Settings
import com.rajdeep.vesper.media.VoicePlayer
import com.rajdeep.vesper.shared.MediaRef
import com.rajdeep.vesper.ui.auth.AuthScreen
import com.rajdeep.vesper.ui.chat.ChatScreen
import com.rajdeep.vesper.ui.chat.LocalMediaHost
import com.rajdeep.vesper.ui.chat.MediaHost
import com.rajdeep.vesper.ui.components.DuskGlyph
import com.rajdeep.vesper.ui.components.pressable
import com.rajdeep.vesper.ui.console.ConsoleScreen
import com.rajdeep.vesper.ui.console.MemberScreen
import com.rajdeep.vesper.ui.screens.ChatInfoScreen
import com.rajdeep.vesper.ui.screens.DirectListScreen
import com.rajdeep.vesper.ui.screens.PeopleScreen
import com.rajdeep.vesper.ui.screens.ProfileScreen
import com.rajdeep.vesper.ui.screens.YouScreen
import com.rajdeep.vesper.ui.theme.LocalSpace
import com.rajdeep.vesper.ui.theme.Motion
import com.rajdeep.vesper.ui.theme.Space
import com.rajdeep.vesper.ui.theme.V
import com.rajdeep.vesper.ui.theme.VesperTheme
import com.rajdeep.vesper.ui.theme.accent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable object HomeRoute
@Serializable data class DirectChatRoute(val peer: String)
@Serializable data class ChatInfoRoute(val peer: String)
@Serializable data class ProfileRoute(val id: String)
@Serializable data class MemberRoute(val id: String)

enum class Tab(val label: String, val icon: ImageVector, val space: Space) {
    HALL("Hall", Icons.Rounded.Forum, Space.HALL),
    DIRECT("Direct", Icons.Rounded.Lock, Space.DIRECT),
    PEOPLE("People", Icons.Rounded.People, Space.NEUTRAL),
    YOU("You", Icons.Rounded.AccountCircle, Space.NEUTRAL),
    CONSOLE("Console", Icons.Rounded.Insights, Space.CONSOLE),
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var session: SessionManager
    @Inject lateinit var settings: Settings
    @Inject lateinit var media: MediaRepository
    @Inject lateinit var voice: VoicePlayer
    @Inject lateinit var messages: Messages

    private val openPeer = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { session.state.value is SessionState.Loading }
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        openPeer.value = intent?.getStringExtra(EXTRA_PEER)
        val host = object : MediaHost {
            override suspend fun open(ref: MediaRef) = media.open(ref)
            override fun cached(mediaId: String) = media.cached(mediaId)
            override val player = voice
        }
        setContent {
            val prefs by settings.prefs.collectAsState(Prefs())
            val state by session.state.collectAsState()
            VesperTheme(prefs) {
                CompositionLocalProvider(LocalMediaHost provides host) {
                    AnimatedContent(
                        when (state) { is SessionState.SignedIn -> 2; is SessionState.SignedOut -> 1; else -> 0 },
                        transitionSpec = { (fadeIn(Motion.tw(Motion.L)) + scaleIn(Motion.spring(), initialScale = 0.98f)) togetherWith fadeOut(Motion.tw(Motion.S)) },
                        label = "root",
                    ) { s ->
                        when (s) {
                            2 -> SignedIn((state as? SessionState.SignedIn)?.session?.isAdmin == true)
                            1 -> AuthScreen((state as? SessionState.SignedOut)?.reason)
                            else -> Box(Modifier.fillMaxSize().background(V.c.bg), contentAlignment = Alignment.Center) { DuskGlyph(72.dp) }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_PEER)?.let { openPeer.value = it }
    }

    @Composable
    private fun SignedIn(isAdmin: Boolean) {
        val nav = rememberNavController()
        val peer by openPeer.collectAsState()
        LaunchedEffect(peer) { peer?.let { nav.navigate(DirectChatRoute(it)) { launchSingleTop = true }; openPeer.value = null } }
        val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        val focus = androidx.compose.ui.platform.LocalFocusManager.current
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            focus.clearFocus(); keyboard?.hide()
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notif.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        NavHost(
            nav, startDestination = HomeRoute, modifier = Modifier.fillMaxSize().background(V.c.bg),
            enterTransition = { slideInHorizontally(Motion.tw()) { it / 5 } + fadeIn(Motion.tw()) },
            exitTransition = { slideOutHorizontally(Motion.tw()) { -it / 10 } + fadeOut(Motion.tw(Motion.S)) },
            popEnterTransition = { slideInHorizontally(Motion.tw()) { -it / 10 } + fadeIn(Motion.tw()) },
            popExitTransition = { slideOutHorizontally(Motion.tw()) { it / 5 } + fadeOut(Motion.tw(Motion.S)) },
        ) {
            composable<HomeRoute> { Home(nav, isAdmin) }
            composable<DirectChatRoute> {
                val r = it.arguments?.getString("peer") ?: ""
                CompositionLocalProvider(LocalSpace provides Space.DIRECT) {
                    ChatScreen(onBack = { nav.popBackStack() }, onOpenInfo = { nav.navigate(ChatInfoRoute(r)) }, onOpenProfile = { id -> nav.navigate(ProfileRoute(id)) })
                }
            }
            composable<ChatInfoRoute> {
                CompositionLocalProvider(LocalSpace provides Space.DIRECT) { ChatInfoScreen(onBack = { nav.popBackStack() }, onProfile = { id -> nav.navigate(ProfileRoute(id)) }) }
            }
            composable<ProfileRoute> {
                ProfileScreen(onBack = { nav.popBackStack() }, onMessage = { id -> nav.navigate(DirectChatRoute(id)) })
            }
            composable<MemberRoute> {
                CompositionLocalProvider(LocalSpace provides Space.CONSOLE) { MemberScreen(onBack = { nav.popBackStack() }) }
            }
        }
    }

    @Composable
    private fun Home(nav: NavHostController, isAdmin: Boolean) {
        var tab by rememberSaveable { mutableStateOf(Tab.HALL) }
        val tabs = Tab.entries.filter { it != Tab.CONSOLE || isAdmin }
        val unread by messages.unreadDirect().collectAsState(0)
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        androidx.activity.compose.BackHandler(enabled = tab != Tab.HALL) { tab = Tab.HALL }
        LaunchedEffect(isAdmin) { if (tab == Tab.CONSOLE && !isAdmin) tab = Tab.HALL }
        Column(Modifier.fillMaxSize().background(V.c.bg)) {
            Box(Modifier.weight(1f)) {
                AnimatedContent(tab, transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (fadeIn(Motion.tw(Motion.M)) + slideInHorizontally(Motion.tw()) { it / 14 * dir }) togetherWith (fadeOut(Motion.tw(Motion.S)) + slideOutHorizontally(Motion.tw()) { -it / 14 * dir })
                }, label = "tabs") { t ->
                    CompositionLocalProvider(LocalSpace provides t.space) {
                        when (t) {
                            Tab.HALL -> ChatScreen(onBack = null, onOpenInfo = null, onOpenProfile = { nav.navigate(ProfileRoute(it)) })
                            Tab.DIRECT -> DirectListScreen(onOpen = { nav.navigate(DirectChatRoute(it)) }, onFindPeople = { tab = Tab.PEOPLE })
                            Tab.PEOPLE -> PeopleScreen(onProfile = { nav.navigate(ProfileRoute(it)) })
                            Tab.YOU -> YouScreen()
                            Tab.CONSOLE -> ConsoleScreen(onMember = { nav.navigate(MemberRoute(it)) })
                        }
                    }
                }
            }
            Reveal(!imeVisible || tab != Tab.HALL) { BottomBar(tabs, tab, unread) { tab = it } }
        }
    }

    @Composable
    private fun BottomBar(tabs: List<Tab>, selected: Tab, unread: Int, onSelect: (Tab) -> Unit) {
        val fb = V.fb
        Box(Modifier.fillMaxWidth().background(V.c.bg).navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp)) {
            BoxWithConstraints(
                Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(24.dp)).background(V.c.s1).border(1.dp, V.c.hairline, RoundedCornerShape(24.dp)).padding(5.dp),
            ) {
                val w = maxWidth / tabs.size
                val idx = tabs.indexOf(selected).coerceAtLeast(0)
                val x by animateDpAsState(w * idx, Motion.spring(), label = "indicator")
                val accent by animateColorAsState(V.c.accent(selected.space).let { if (selected.space == Space.NEUTRAL) V.c.text else it }, Motion.tw(), label = "acc")
                Box(Modifier.offset(x = x).width(w).fillMaxSize().clip(RoundedCornerShape(19.dp)).background(accent.copy(alpha = 0.13f)))
                Row(Modifier.fillMaxSize()) {
                    tabs.forEach { t ->
                        val sel = t == selected
                        val tint by animateColorAsState(if (sel) accent else V.c.text3, Motion.tw(Motion.S), label = "tint")
                        Column(
                            Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(19.dp))
                                .pressable(scaleTo = 0.92f, haptic = false) { if (!sel) fb.tick(); onSelect(t) }
                                .semantics { contentDescription = t.label },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        ) {
                            Box {
                                val pop by androidx.compose.animation.core.animateFloatAsState(if (sel) 1.12f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 500f), label = "pop")
                                Icon(t.icon, null, tint = tint, modifier = Modifier.size(22.dp).graphicsLayer { scaleX = pop; scaleY = pop; translationY = (pop - 1f) * -10f })
                                if (t == Tab.DIRECT) {
                                    Reveal(unread > 0, modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-3).dp), enter = scaleIn(Motion.bouncy()), exit = scaleOut()) {
                                        Box(Modifier.size(9.dp).clip(CircleShape).background(V.c.direct).border(1.5.dp, V.c.s1, CircleShape))
                                    }
                                }
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(t.label, style = V.t.caption, color = tint)
                        }
                    }
                }
            }
        }
    }

    companion object { const val EXTRA_PEER = "peer" }
}
