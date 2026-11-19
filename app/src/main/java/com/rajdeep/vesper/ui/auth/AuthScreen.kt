package com.rajdeep.vesper.ui.auth

import com.rajdeep.vesper.ui.components.Reveal
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rajdeep.vesper.core.net.ApiException
import com.rajdeep.vesper.data.AuthRepository
import com.rajdeep.vesper.shared.Limits
import com.rajdeep.vesper.ui.components.DuskGlyph
import com.rajdeep.vesper.ui.components.PrimaryButton
import com.rajdeep.vesper.ui.components.VField
import com.rajdeep.vesper.ui.components.pressable
import com.rajdeep.vesper.ui.theme.LocalSpace
import com.rajdeep.vesper.ui.theme.Motion
import com.rajdeep.vesper.ui.theme.V
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUi(
    val signUp: Boolean = false,
    val username: String = "",
    val password: String = "",
    val confirm: String = "",
    val acknowledged: Boolean = false,
    val availability: String? = null,
    val available: Boolean? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AuthViewModel @Inject constructor(private val repo: AuthRepository) : ViewModel() {
    val ui = MutableStateFlow(AuthUi())
    private var check: Job? = null

    fun mode(signUp: Boolean) = ui.update { it.copy(signUp = signUp, error = null) }
    fun password(v: String) = ui.update { it.copy(password = v, error = null) }
    fun confirm(v: String) = ui.update { it.copy(confirm = v, error = null) }
    fun acknowledge() = ui.update { it.copy(acknowledged = !it.acknowledged) }

    fun username(v: String) {
        val u = v.lowercase().filter { it.isLetterOrDigit() || it == '_' || it == '.' }.take(Limits.USERNAME_MAX)
        ui.update { it.copy(username = u, availability = null, available = null, error = null) }
        check?.cancel()
        if (!ui.value.signUp || u.length < Limits.USERNAME_MIN) return
        check = viewModelScope.launch {
            delay(350)
            Limits.usernameProblem(u)?.let { p -> ui.update { it.copy(availability = p, available = false) }; return@launch }
            runCatching { repo.availability(u) }.onSuccess { a ->
                ui.update { it.copy(available = a.available, availability = if (a.available) "Available" else a.reason) }
            }
        }
    }

    val canSubmit: Boolean get() = ui.value.let { s ->
        s.username.length >= Limits.USERNAME_MIN && s.password.isNotEmpty() && !s.busy &&
            (!s.signUp || (s.acknowledged && s.available != false && Limits.passwordProblem(s.password) == null && s.password == s.confirm))
    }

    fun submit() {
        val s = ui.value
        if (s.signUp) {
            Limits.passwordProblem(s.password)?.let { p -> ui.update { it.copy(error = p) }; return }
            if (s.password != s.confirm) { ui.update { it.copy(error = "Passwords don't match") }; return }
        }
        ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.authenticate(s.username, s.password, s.signUp) }
                .onFailure { e -> ui.update { it.copy(busy = false, error = (e as? ApiException)?.message ?: "Couldn't reach Vesper. Check your connection.") } }
                .onSuccess { ui.update { it.copy(busy = false) } }
        }
    }
}

private fun reasonText(r: String?): String? = when (r) {
    "suspended" -> "This account was suspended by the administrator."
    "deleted", "deleted_by_admin" -> "The account was deleted."
    "signed_out_by_admin" -> "You were signed out by the administrator."
    "session_reused", "expired" -> "Your session ended. Please sign in again."
    "replaced" -> "This device was signed out because too many devices were in use."
    else -> null
}

@Composable
fun AuthScreen(endReason: String?, vm: AuthViewModel = hiltViewModel()) {
    androidx.compose.runtime.CompositionLocalProvider(LocalSpace provides com.rajdeep.vesper.ui.theme.Space.HALL) { AuthContent(endReason, vm) }
}

@Composable
private fun AuthContent(endReason: String?, vm: AuthViewModel) {
    val s by vm.ui.collectAsState()
    val glow by animateFloatAsState(if (s.signUp) 1f else 0.6f, Motion.tw(Motion.L), label = "glow")
    val accent = V.accent
    Box(
        Modifier.fillMaxSize().background(V.c.bg).drawBehind {
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.10f * glow), accent.copy(alpha = 0f)), center = Offset(size.width * 0.5f, size.height * 0.16f), radius = size.width * 0.9f))
        },
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(48.dp))
            DuskGlyph(72.dp)
            Spacer(Modifier.height(18.dp))
            Text("Vesper", style = V.t.display, color = V.c.text)
            Text("A quiet place to talk. Nothing stays forever.", style = V.t.body, color = V.c.text2, modifier = Modifier.padding(top = 6.dp))
            reasonText(endReason)?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = V.t.bodySmall, color = V.c.text, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(V.c.s2).padding(14.dp))
            }
            Spacer(Modifier.height(32.dp))
            Segmented(s.signUp) { vm.mode(it) }
            Spacer(Modifier.height(20.dp))
            VField(
                s.username, vm::username, "Username",
                helper = if (s.signUp) (s.availability ?: "3–20 characters: a–z, 0–9, _ or .") else null,
                error = if (s.signUp && s.available == false) s.availability else null,
                trailing = {
                    Reveal(s.signUp && s.available == true, enter = fadeIn() + androidx.compose.animation.scaleIn(Motion.bouncy()), exit = fadeOut()) {
                        Icon(Icons.Rounded.Check, "Available", tint = V.c.success, modifier = Modifier.size(20.dp))
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            VField(
                s.password, vm::password, "Password", password = true,
                helper = if (s.signUp) strength(s.password) else null,
                ime = if (s.signUp) ImeAction.Next else ImeAction.Done,
                onIme = if (s.signUp) null else ({ if (vm.canSubmit) vm.submit() }),
            )
            Reveal(s.signUp, enter = expandVertically(Motion.tw()) + fadeIn(), exit = shrinkVertically(Motion.tw()) + fadeOut()) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    VField(s.confirm, vm::confirm, "Confirm password", password = true, ime = ImeAction.Done, onIme = { if (vm.canSubmit) vm.submit() },
                        error = if (s.confirm.isNotEmpty() && s.confirm != s.password) "Passwords don't match" else null)
                    Spacer(Modifier.height(16.dp))
                    Disclosure(s.acknowledged, vm::acknowledge)
                }
            }
            AnimatedContent(s.error, transitionSpec = { (fadeIn() + slideInVertically { -it / 3 }) togetherWith fadeOut() }, label = "err") { e ->
                if (e != null) Text(e, style = V.t.bodySmall, color = V.c.error, modifier = Modifier.padding(top = 14.dp)) else Spacer(Modifier.height(0.dp))
            }
            Spacer(Modifier.height(24.dp))
            PrimaryButton(if (s.signUp) "Create account" else "Sign in", Modifier.fillMaxWidth(), enabled = vm.canSubmit, loading = s.busy) { vm.submit() }
            Spacer(Modifier.height(14.dp))
            Text(
                "No email, no phone number. If you forget your password the account can't be recovered.",
                style = V.t.label, color = V.c.text3, modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun Segmented(signUp: Boolean, onChange: (Boolean) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(15.dp)).background(V.c.s1).padding(4.dp)) {
        val half = maxWidth / 2
        val x by animateDpAsState(if (signUp) half else 0.dp, Motion.spring(), label = "seg")
        Box(Modifier.offset(x = x).width(half).fillMaxSize().clip(RoundedCornerShape(12.dp)).background(V.c.s3))
        Row(Modifier.fillMaxSize()) {
            listOf(false to "Sign in", true to "Create account").forEach { (v, label) ->
                val color by animateColorAsState(if (signUp == v) V.c.text else V.c.text2, label = "segc")
                Box(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(12.dp)).pressable(scaleTo = 0.98f) { onChange(v) }, contentAlignment = Alignment.Center) {
                    Text(label, style = V.t.label, color = color)
                }
            }
        }
    }
}

@Composable
private fun Disclosure(ack: Boolean, toggle: () -> Unit) {
    val border by animateColorAsState(if (ack) V.accent else V.c.hairline, label = "ack")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(V.c.s1).border(1.dp, border, RoundedCornerShape(18.dp)).pressable(scaleTo = 0.99f, onClick = toggle).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Visibility, null, tint = V.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("What the administrator can see", style = V.t.subhead, color = V.c.text)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Your username, when you joined, whether you're online and when you were last active, everything in the Hall, " +
                "and who you message privately and when (message type and time).",
            style = V.t.bodySmall, color = V.c.text2,
        )
        Spacer(Modifier.height(6.dp))
        Text("The administrator can never read, hear or see your private messages — they are end-to-end encrypted.", style = V.t.bodySmall, color = V.c.text)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(if (ack) V.accent else V.c.s3), contentAlignment = Alignment.Center) {
                Reveal(ack, enter = androidx.compose.animation.scaleIn(Motion.bouncy()), exit = androidx.compose.animation.scaleOut()) {
                    Icon(Icons.Rounded.Check, null, tint = V.c.onAccent, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Text("I understand", style = V.t.label, color = V.c.text)
        }
    }
}

private fun strength(p: String): String = when {
    p.isEmpty() -> "At least ${Limits.PASSWORD_MIN} characters"
    p.length < Limits.PASSWORD_MIN -> "${Limits.PASSWORD_MIN - p.length} more characters"
    p.length >= 16 && p.any { it.isDigit() } && p.any { !it.isLetterOrDigit() } -> "Strong"
    p.length >= 12 -> "Good"
    else -> "Okay — longer is stronger"
}

@Suppress("unused") private val keepSpace = LocalSpace
