package com.rajdeep.vesper.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.rajdeep.vesper.shared.Limits
import com.rajdeep.vesper.ui.components.PrimaryButton
import com.rajdeep.vesper.ui.components.VField

/** Username, current password, new password, confirm. Used signed-out (sign-in page) and signed-in (You). */
@Composable
fun PasswordForm(busy: Boolean, onSubmit: (username: String, current: String, next: String) -> Unit) {
    var u by remember { mutableStateOf("") }
    var cur by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val nextProblem = if (next.isEmpty()) null else Limits.passwordProblem(next) ?: if (next == cur) "New password must be different" else null
    val mismatch = confirm.isNotEmpty() && confirm != next
    val ok = u.length >= Limits.USERNAME_MIN && cur.isNotEmpty() && next.isNotEmpty() && nextProblem == null && confirm == next && !busy
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        VField(u, { v -> u = v.lowercase().filter { it.isLetterOrDigit() || it == '_' || it == '.' }.take(Limits.USERNAME_MAX) }, "Username", ime = ImeAction.Next)
        VField(cur, { cur = it }, "Old password", password = true, ime = ImeAction.Next)
        VField(next, { next = it }, "New password", password = true, ime = ImeAction.Next, helper = "At least ${Limits.PASSWORD_MIN} characters", error = nextProblem)
        VField(confirm, { confirm = it }, "Confirm new password", password = true, ime = ImeAction.Done, onIme = { if (ok) onSubmit(u, cur, next) },
            error = if (mismatch) "Passwords don't match" else null)
        PrimaryButton("Submit", Modifier.fillMaxWidth(), enabled = ok, loading = busy) { onSubmit(u, cur, next) }
    }
}
