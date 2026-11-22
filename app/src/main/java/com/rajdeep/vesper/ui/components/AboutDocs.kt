package com.rajdeep.vesper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.rajdeep.vesper.ui.text.SLASH_COMMANDS
import com.rajdeep.vesper.ui.text.formatMessage
import com.rajdeep.vesper.ui.theme.Mono
import com.rajdeep.vesper.ui.theme.V

@Composable
private fun Hero(title: String, line: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(V.accent.copy(alpha = 0.22f), V.c.s1)))
            .border(1.dp, V.accent.copy(alpha = 0.25f), RoundedCornerShape(24.dp)).padding(22.dp),
    ) {
        Text(title, style = V.t.title, color = V.c.text)
        Spacer(Modifier.height(6.dp))
        Text(line, style = V.t.bodySmall, color = V.c.text2)
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(V.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = V.accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = V.t.subhead, color = V.c.text)
            Spacer(Modifier.height(2.dp))
            Text(body, style = V.t.bodySmall, color = V.c.text2)
        }
    }
}

/** Shared by `/about` and You → About. */
@Composable
fun AboutContent() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Hero("Vesper", "A private evening room for a close circle — conversations that are present for a while, then fade.")
        Spacer(Modifier.height(10.dp))
        Feature(Icons.Rounded.Forum, "The Hall", "One shared space for everyone on Vesper. Text, photos and voice notes arrive live, with read-at-a-glance ticks.")
        Feature(Icons.Rounded.Lock, "End-to-end encrypted Inbox", "Private chats use the Signal protocol (post-quantum PQXDH, per-device sessions). Messages are locked on your phone and unlocked only on theirs — the server relays ciphertext it cannot read.")
        Feature(Icons.Rounded.Shield, "Private by design", "Username and password only — no phone number, no email, no contacts. The administrator sees who talks to whom and when, never what is said. Safety numbers let you verify a chat in person.")
        Feature(Icons.Rounded.Schedule, "Messages that fade", "Text disappears 72 hours after the server accepts it; photos and voice after 24. They're hard-deleted from the server, storage and every phone.")
        Feature(Icons.Rounded.AllInclusive, "Keep what matters", "End a bold message with forever — *like this*forever — or use /keep to make it permanent.")
        Feature(Icons.Rounded.Storage, "Instant, local-first", "Every chat lives in an encrypted database on your phone, so conversations open immediately; only what's new is fetched.")
        Feature(Icons.Rounded.CloudDone, "Encrypted backup", "Private chats are backed up as ciphertext with a key only your password unlocks, so they return after a reinstall.")
        Feature(Icons.Rounded.Image, "Photos", "Compressed like a standard messenger send, stripped of location data, and encrypted before upload.")
        Feature(Icons.Rounded.GraphicEq, "Voice notes", "Hold to record, slide to cancel, slide up to lock. Opus audio with noise suppression; play back at 1×, 1.5× or 2×.")
        Spacer(Modifier.height(10.dp))
        Text("Private infrastructure · built for friends, not for scale.", style = V.t.label, color = V.c.text3, modifier = Modifier.padding(horizontal = 20.dp))
    }
}

@Composable
private fun DocRow(syntax: String, example: String?, what: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(V.c.s1).padding(14.dp)) {
        Text(syntax, style = V.t.mono.copy(fontSize = V.t.bodySmall.fontSize), color = V.accent)
        if (example != null) {
            Spacer(Modifier.height(6.dp))
            Text(formatMessage(example, Mono, V.accent, V.c.text2, V.c.s3), style = V.t.body, color = V.c.text)
        }
        Spacer(Modifier.height(4.dp))
        Text(what, style = V.t.label, color = V.c.text2)
    }
}

/** `/docs`: every command and formatting option. */
@Composable
fun DocsContent() {
    Column {
        Hero("Vesper docs", "Formatting, commands and the little tricks — all in one place.")
        SectionLabel("Formatting", Modifier.padding(top = 18.dp))
        DocRow("*bold*", "*bold*", "Wrap text in asterisks.")
        DocRow("_italic_", "_italic_", "Wrap text in underscores.")
        DocRow("~strike~", "~strikethrough~", "Wrap text in tildes.")
        DocRow("`code`", "`inline code`", "Wrap text in backticks for monospace.")
        DocRow("```block```", "```a monospace block```", "Three backticks around one or more lines.")
        DocRow("> quote", "> a quoted line", "Start a line with > and a space.")
        DocRow("- item", "- first\n- second", "Start lines with - for a bullet list.")
        DocRow("||spoiler||", null, "Hidden text, shown as a blank bar.")
        DocRow("links", "https://example.com", "Web addresses become tappable automatically.")
        SectionLabel("Keep forever", Modifier.padding(top = 18.dp))
        DocRow("*message*forever", "*this stays*forever", "Bold message that never expires. Write forever right after the closing * — no space. Text only.")
        SectionLabel("Commands", Modifier.padding(top = 18.dp))
        SLASH_COMMANDS.forEach { c -> DocRow(c.name + if (c.args.isNotEmpty()) " " + c.args else "", null, c.summary) }
        SectionLabel("Gestures", Modifier.padding(top = 18.dp))
        DocRow("Hold a message", null, "Select it — then tap others to select more. Copy, remove from this phone, or see details.")
        DocRow("Hold the mic", null, "Record a voice note. Slide left to cancel, up to lock hands-free.")
        DocRow("Tap empty space", null, "Puts the keyboard away.")
        DocRow("Ticks", null, "🕓 sending · ✓ sent · ✓✓ delivered · blue ✓✓ seen (private chats). The Hall shows a single ✓.")
        Spacer(Modifier.height(24.dp))
    }
}
