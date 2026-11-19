# Design — "Dusk"

Vesper means evening: a quiet, candlelit dark space where messages are present for a while and then fade.

| Token | Value |
|---|---|
| Background / surfaces | `#0D0E12` / `#15171D` `#1C1F27` `#242833`, hairline `#2A2E38` |
| Text | `#ECE8E1`, `#9A968E`, `#6B6862` |
| Space accents | Hall **Ember** `#E2B36B` · Direct **Twilight** `#8FA3C8` · Console **Verdigris** `#6FB5A6` |
| Light theme | paper `#F6F3EE`, accents darkened for AA |
| Type | Fraunces (display/titles), Instrument Sans (UI/body), JetBrains Mono (numerals, timers, metrics) |
| Motion | 90/180/280/420 ms, emphasized easing `(0.2,0,0,1)`, spring damping 0.85 |
| Shape | 20 dp bubbles with a 6 dp grouped corner, tone-based elevation, 1 dp hairlines |

Signature details: the accent always tells you which space you're in; bubbles carry a thin ring that drains over the
message's lifetime and dims in the final hour; expired messages dissolve out of the list; voice is a scrubbable
waveform; photos reveal from a blurhash. Haptics use system constants; sounds are original and silent in
silent/vibrate mode. Code: `app/src/main/java/com/rajdeep/vesper/ui/theme/Theme.kt`, `ui/components/`.
