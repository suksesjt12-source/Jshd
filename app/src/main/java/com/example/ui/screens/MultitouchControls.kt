package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.ArenaMidnight
import com.example.ui.theme.ArenaObsidian
import com.example.ui.theme.FlameScarlet
import com.example.ui.theme.ImperialGold
import com.example.ui.theme.LightningCyan
import com.example.ui.theme.TextMuted
import kotlin.math.sqrt

/**
 * Independent multitouch virtual analog stick.
 * Tracks its own pointer sequence without interfering with right-hand attack buttons.
 */
@Composable
fun VirtualAnalogStick(
  onStickMoved: (x: Float, y: Float) -> Unit,
  modifier: Modifier = Modifier,
  size: Dp = 136.dp,
) {
  var knobX by remember { mutableFloatStateOf(0f) }
  var knobY by remember { mutableFloatStateOf(0f) }
  var isDragging by remember { mutableStateOf(false) }

  Box(
    modifier =
      modifier
        .size(size)
        .testTag("virtual_analog_stick")
        .pointerInput(Unit) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            isDragging = true
            val center = Offset(this.size.width * 0.5f, this.size.height * 0.5f)
            val maxRadius = (this.size.width * 0.38f).coerceAtLeast(1f)

            fun updateFromPos(pos: Offset) {
              val dx = pos.x - center.x
              val dy = pos.y - center.y
              val dist = sqrt(dx * dx + dy * dy)
              val clamped = dist.coerceAtMost(maxRadius)
              val nx = if (dist > 1e-3f) (dx / dist) * (clamped / maxRadius) else 0f
              val ny = if (dist > 1e-3f) (dy / dist) * (clamped / maxRadius) else 0f
              knobX = nx
              knobY = ny
              onStickMoved(nx, ny)
            }

            updateFromPos(down.position)

            while (true) {
              val event = awaitPointerEvent()
              val change = event.changes.firstOrNull { it.id == down.id }
              if (change == null || !change.pressed) {
                break
              }
              change.consume()
              updateFromPos(change.position)
            }

            isDragging = false
            knobX = 0f
            knobY = 0f
            onStickMoved(0f, 0f)
          }
        },
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.size(size)) {
      val radius = this.size.minDimension * 0.44f
      val center = Offset(this.size.width * 0.5f, this.size.height * 0.5f)
      drawCircle(
        color = ArenaObsidian.copy(alpha = 0.62f),
        radius = radius,
        center = center,
      )
      drawCircle(
        color = if (isDragging) LightningCyan else LightningCyan.copy(alpha = 0.45f),
        radius = radius,
        center = center,
        style = Stroke(width = 3f),
      )
      val knobOffset = Offset(center.x + knobX * radius * 0.72f, center.y + knobY * radius * 0.72f)
      drawCircle(
        color = if (isDragging) LightningCyan.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.55f),
        radius = radius * 0.38f,
        center = knobOffset,
      )
    }
  }
}

@Composable
fun CombatActionCluster(
  energy: Float,
  specialCooldownSec: Float,
  heavyCooldownSec: Float,
  accentColor: Color,
  onLight: () -> Unit,
  onHeavy: () -> Unit,
  onLauncher: () -> Unit,
  onSpecial: () -> Unit,
  onUltimate: () -> Unit,
  onJump: () -> Unit,
  onDodge: () -> Unit,
  onGuardChanged: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val specialReady = energy >= 30f && specialCooldownSec <= 0f
  val ultimateReady = energy >= 100f

  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.End,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // Top row: Ultimate + Special + Launcher
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      TapCombatButton(
        label = "ULT",
        subLabel = if (ultimateReady) "MAX" else "${energy.toInt()}%",
        accent = if (ultimateReady) ImperialGold else TextMuted,
        enabled = ultimateReady,
        size = 58.dp,
        testTag = "btn_ultimate",
        onTap = onUltimate,
      )
      TapCombatButton(
        label = "SPEC",
        subLabel = if (specialCooldownSec > 0.1f) "${"%.1f".format(specialCooldownSec)}s" else "30 EN",
        accent = if (specialReady) accentColor else TextMuted,
        enabled = specialReady,
        size = 56.dp,
        testTag = "btn_special",
        onTap = onSpecial,
      )
      TapCombatButton(
        label = "LNCH",
        subLabel = "▲",
        accent = ImperialGold,
        enabled = true,
        size = 52.dp,
        testTag = "btn_launcher",
        onTap = onLauncher,
      )
    }

    // Middle row: Block (Hold) + Dodge + Heavy
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      HoldCombatButton(
        label = "BLK",
        accent = LightningCyan,
        size = 54.dp,
        testTag = "btn_block",
        onHoldChanged = onGuardChanged,
      )
      TapCombatButton(
        label = "DODGE",
        subLabel = null,
        accent = Color(0xFF64FFDA),
        enabled = true,
        size = 54.dp,
        testTag = "btn_dodge",
        onTap = onDodge,
      )
      TapCombatButton(
        label = "HEAVY",
        subLabel = if (heavyCooldownSec > 0.1f) "${"%.1f".format(heavyCooldownSec)}s" else null,
        accent = FlameScarlet,
        enabled = heavyCooldownSec <= 0f,
        size = 62.dp,
        testTag = "btn_heavy",
        onTap = onHeavy,
      )
    }

    // Bottom row: Jump + Light (Primary)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
      TapCombatButton(
        label = "JUMP",
        subLabel = null,
        accent = Color(0xFFB388FF),
        enabled = true,
        size = 54.dp,
        testTag = "btn_jump",
        onTap = onJump,
      )
      TapCombatButton(
        label = "LIGHT",
        subLabel = "COMBO",
        accent = accentColor,
        enabled = true,
        size = 72.dp,
        testTag = "btn_light",
        onTap = onLight,
      )
    }
  }
}

@Composable
private fun TapCombatButton(
  label: String,
  subLabel: String?,
  accent: Color,
  enabled: Boolean,
  size: Dp,
  testTag: String,
  onTap: () -> Unit,
) {
  var pressed by remember { mutableStateOf(false) }

  Box(
    modifier =
      Modifier.size(size)
        .clip(CircleShape)
        .background(
          when {
            !enabled -> ArenaMidnight.copy(alpha = 0.55f)
            pressed -> accent.copy(alpha = 0.48f)
            else -> ArenaObsidian.copy(alpha = 0.78f)
          }
        )
        .border(
          width = if (enabled) 2.dp else 1.dp,
          color = if (enabled) accent else TextMuted.copy(alpha = 0.35f),
          shape = CircleShape,
        )
        .testTag(testTag)
        .pointerInput(enabled) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            if (enabled) {
              pressed = true
              onTap()
            }
            while (true) {
              val ev = awaitPointerEvent()
              val ch = ev.changes.firstOrNull { it.id == down.id }
              if (ch == null || !ch.pressed) break
            }
            pressed = false
          }
        }
        .padding(4.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (enabled) Color.White else TextMuted,
      )
      if (subLabel != null) {
        Text(
          text = subLabel,
          style = MaterialTheme.typography.labelSmall,
          color = if (enabled) accent else TextMuted,
        )
      }
    }
  }
}

@Composable
private fun HoldCombatButton(
  label: String,
  accent: Color,
  size: Dp,
  testTag: String,
  onHoldChanged: (Boolean) -> Unit,
) {
  var held by remember { mutableStateOf(false) }

  Box(
    modifier =
      Modifier.size(size)
        .clip(CircleShape)
        .background(if (held) accent.copy(alpha = 0.45f) else ArenaObsidian.copy(alpha = 0.78f))
        .border(2.dp, accent, CircleShape)
        .testTag(testTag)
        .pointerInput(Unit) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            held = true
            onHoldChanged(true)
            while (true) {
              val ev = awaitPointerEvent()
              val ch = ev.changes.firstOrNull { it.id == down.id }
              if (ch == null || !ch.pressed) break
              ch.consume()
            }
            held = false
            onHoldChanged(false)
          }
        },
    contentAlignment = Alignment.Center,
  ) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White)
  }
}
