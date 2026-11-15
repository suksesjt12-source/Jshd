package com.example.ui.screens

import android.view.Choreographer
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.characters.ElementType
import com.example.core.AiDifficulty
import com.example.core.BattleEngine
import com.example.core.BattleHudState
import com.example.core.FighterId
import com.example.core.GameMode
import com.example.core.MatchPhase
import com.example.graphics.FilamentArenaRenderer
import com.example.ui.ArenaViewModel
import com.example.ui.theme.ArenaMidnight
import com.example.ui.theme.ArenaObsidian
import com.example.ui.theme.CrimsonDamage
import com.example.ui.theme.EmeraldHp
import com.example.ui.theme.FlameScarlet
import com.example.ui.theme.ImperialGold
import com.example.ui.theme.LightningCyan
import com.example.ui.theme.TextMuted
import kotlin.math.roundToInt

@Composable
fun BattleScreen(
  viewModel: ArenaViewModel,
  playerId: FighterId,
  opponentId: FighterId,
  gameMode: GameMode,
  difficulty: AiDifficulty,
  arcadeStage: Int,
  onExitToMenu: () -> Unit,
  onNextArcadeStage: (FighterId, AiDifficulty, Int) -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val coroutineScope = rememberCoroutineScope()
  val settings by viewModel.settings.collectAsStateWithLifecycle()

  val battleEngine =
    remember(playerId, opponentId, gameMode, difficulty, arcadeStage) {
      BattleEngine(
        playerId = playerId,
        opponentId = opponentId,
        gameMode = gameMode,
        difficulty = difficulty,
        arcadeStage = arcadeStage,
        audioEngine = viewModel.audioEngine,
        recordRepository = viewModel.matchRepository,
        coroutineScope = coroutineScope,
      )
    }

  val renderer = remember(playerId, opponentId) { FilamentArenaRenderer(context) }

  LaunchedEffect(renderer) { battleEngine.attachRenderer(renderer) }
  LaunchedEffect(settings) { renderer.applyQualitySettings(settings) }

  val hud by battleEngine.hudState.collectAsStateWithLifecycle()

  BackHandler {
    if (hud.matchPhase == MatchPhase.PAUSED) {
      battleEngine.setPaused(false)
    } else if (hud.matchPhase == MatchPhase.FIGHTING || hud.matchPhase == MatchPhase.INTRO) {
      battleEngine.setPaused(true)
    } else {
      onExitToMenu()
    }
  }

  // Lifecycle pause/resume for audio & loop safety
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_PAUSE -> {
          viewModel.audioEngine.onPause()
          if (battleEngine.hudState.value.matchPhase == MatchPhase.FIGHTING) {
            battleEngine.setPaused(true)
          }
        }
        Lifecycle.Event.ON_RESUME -> viewModel.audioEngine.onResume()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }

  // Choreographer-driven frame-paced game & 3D render loop (30 FPS or 60 FPS)
  DisposableEffect(battleEngine, settings.targetFps) {
    val choreographer = Choreographer.getInstance()
    var running = true
    var lastStepNs = System.nanoTime()
    val minFrameIntervalNs = if (settings.targetFps >= 60) 14_500_000L else 31_000_000L

    val callback =
      object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
          if (!running) return
          val elapsedNs = frameTimeNanos - lastStepNs
          if (elapsedNs >= minFrameIntervalNs) {
            val dt = (elapsedNs / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            lastStepNs = frameTimeNanos
            battleEngine.stepFrame(dt)
          }
          choreographer.postFrameCallback(this)
        }
      }
    choreographer.postFrameCallback(callback)

    onDispose {
      running = false
      choreographer.removeFrameCallback(callback)
    }
  }

  DisposableEffect(renderer) {
    onDispose { renderer.destroy() }
  }

  val playerAccent =
    if (hud.playerSpec.element == ElementType.LIGHTNING) LightningCyan else FlameScarlet

  Box(modifier = Modifier.fillMaxSize()) {
    // 1. Native 3D Filament SurfaceView
    AndroidView(
      factory = { ctx ->
        SurfaceView(ctx).also { sv ->
          renderer.attachToSurfaceView(sv, playerId, opponentId, settings)
        }
      },
      modifier = Modifier.fillMaxSize().testTag("filament_battle_surface"),
    )

    // 2. Impact Flash & Ultimate Letterbox Bars
    if (hud.ultimate.isActive) {
      if (hud.ultimate.flashAlpha > 0.01f) {
        Box(
          modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = hud.ultimate.flashAlpha))
        )
      }
      Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween,
      ) {
        Box(
          modifier =
            Modifier.fillMaxWidth()
              .height(34.dp)
              .background(Color.Black.copy(alpha = 0.88f))
              .padding(horizontal = 24.dp),
          contentAlignment = Alignment.CenterStart,
        ) {
          Text(
            text = "ULTIMATE ART • ${hud.ultimate.title.uppercase()}",
            style = MaterialTheme.typography.titleMedium,
            color = ImperialGold,
          )
        }
        Box(
          modifier =
            Modifier.fillMaxWidth()
              .height(36.dp)
              .background(Color.Black.copy(alpha = 0.88f))
              .padding(horizontal = 24.dp),
          contentAlignment = Alignment.CenterEnd,
        ) {
          if (hud.ultimate.canSkip) {
            OutlinedButton(
              onClick = { battleEngine.onSkipUltimate() },
              modifier = Modifier.height(28.dp).testTag("btn_skip_ultimate"),
              border = BorderStroke(1.dp, ImperialGold),
            ) {
              Icon(Icons.Default.FastForward, contentDescription = "Skip Ultimate", tint = ImperialGold, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(4.dp))
              Text("SKIP", style = MaterialTheme.typography.labelSmall, color = ImperialGold)
            }
          }
        }
      }
    }

    // 3. Top Combat HUD (Health, Energy, Guard, Portraits, Timer, Combo, Damage Popups)
    Box(
      modifier =
        Modifier.fillMaxSize()
          .windowInsetsPadding(WindowInsets.safeDrawing)
          .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
      TopBattleHud(
        hud = hud,
        onToggleLockOn = { battleEngine.toggleLockOn() },
        onPauseClick = { battleEngine.setPaused(true) },
        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
      )

      // Combo & Floating Damage Popups
      Column(
        modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp, bottom = 76.dp),
      ) {
        AnimatedVisibility(visible = hud.playerCombo >= 2, enter = fadeIn(), exit = fadeOut()) {
          Surface(
            color = ArenaObsidian.copy(alpha = 0.82f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.5.dp, ImperialGold),
          ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
              Text(
                text = "${hud.playerCombo} HIT COMBO!",
                style = MaterialTheme.typography.headlineMedium,
                color = ImperialGold,
                modifier = Modifier.testTag("combo_counter_text"),
              )
              Text("Keep chaining!", style = MaterialTheme.typography.labelSmall, color = playerAccent)
            }
          }
        }

        hud.popups.takeLast(3).forEach { popup ->
          val txt =
            when {
              popup.isBlocked -> "GUARD -${popup.amount}"
              popup.isUltimate -> "ULTIMATE -${popup.amount}!!"
              popup.isCritical -> "CRIT -${popup.amount}!"
              else -> "-${popup.amount}"
            }
          Text(
            text = txt,
            style = if (popup.isUltimate) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
            color =
              when {
                popup.isBlocked -> LightningCyan
                popup.isUltimate -> ImperialGold
                else -> CrimsonDamage
              },
          )
        }
      }

      // Intro "READY... FIGHT!" Banner
      if (hud.introBannerText.isNotEmpty()) {
        Box(
          modifier =
            Modifier.align(Alignment.Center)
              .clip(RoundedCornerShape(14.dp))
              .background(ArenaMidnight.copy(alpha = 0.82f))
              .border(2.dp, ImperialGold, RoundedCornerShape(14.dp))
              .padding(horizontal = 32.dp, vertical = 12.dp),
        ) {
          Text(hud.introBannerText, style = MaterialTheme.typography.displayLarge, color = ImperialGold)
        }
      }

      // 4. Multitouch Gameplay Controls (Active during FIGHTING)
      if (hud.matchPhase == MatchPhase.FIGHTING || hud.matchPhase == MatchPhase.INTRO) {
        VirtualAnalogStick(
          onStickMoved = { x, y -> battleEngine.setJoystickInput(x, y) },
          modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 4.dp),
        )

        CombatActionCluster(
          energy = hud.playerEnergy,
          specialCooldownSec = hud.specialCooldownSec,
          heavyCooldownSec = hud.heavyCooldownSec,
          accentColor = playerAccent,
          onLight = { battleEngine.onLightAttackPressed() },
          onHeavy = { battleEngine.onHeavyAttackPressed() },
          onLauncher = { battleEngine.onLauncherPressed() },
          onSpecial = { battleEngine.onSpecialAttackPressed() },
          onUltimate = { battleEngine.onUltimatePressed() },
          onJump = { battleEngine.onJumpPressed() },
          onDodge = { battleEngine.onDodgePressed() },
          onGuardChanged = { held -> battleEngine.onGuardChanged(held) },
          modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 4.dp),
        )
      }
    }

    // 5. Pause Menu Overlay
    if (hud.matchPhase == MatchPhase.PAUSED) {
      PauseOverlayModal(
        isMuted = settings.isMuted,
        targetFps = settings.targetFps,
        onToggleMute = { viewModel.setMuted(!settings.isMuted) },
        onToggleFps = { viewModel.setTargetFps(if (settings.targetFps >= 60) 30 else 60) },
        onResume = { battleEngine.setPaused(false) },
        onRestart = { battleEngine.resetMatch() },
        onExit = onExitToMenu,
      )
    }

    // 6. Victory / Defeat / Draw Result Modal
    if (
      hud.matchPhase == MatchPhase.VICTORY ||
        hud.matchPhase == MatchPhase.DEFEAT ||
        hud.matchPhase == MatchPhase.DRAW
    ) {
      MatchResultModal(
        hud = hud,
        onRematch = { battleEngine.resetMatch() },
        onNextArcade = {
          val nextStage = hud.arcadeStage + 1
          val nextOpp = if (opponentId == FighterId.RAIDEN) FighterId.HOMURA else FighterId.RAIDEN
          val nextDiff =
            when (nextStage) {
              2 -> AiDifficulty.WARRIOR
              else -> AiDifficulty.MASTER
            }
          onNextArcadeStage(nextOpp, nextDiff, nextStage)
        },
        onExit = onExitToMenu,
      )
    }
  }
}

@Composable
private fun TopBattleHud(
  hud: BattleHudState,
  onToggleLockOn: () -> Unit,
  onPauseClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.Top,
  ) {
    // Player 1 Status Bar
    FighterStatusBar(
      name = hud.playerSpec.displayName,
      portraitRes = hud.playerSpec.portraitRes,
      hp = hud.playerHp,
      maxHp = hud.playerMaxHp,
      energy = hud.playerEnergy,
      guard = hud.playerGuard,
      accent = if (hud.playerSpec.element == ElementType.LIGHTNING) LightningCyan else FlameScarlet,
      isLeft = true,
      modifier = Modifier.weight(1f),
    )

    // Center Timer + Lock-On + Pause
    Column(
      modifier = Modifier.padding(horizontal = 10.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Surface(
        shape = RoundedCornerShape(10.dp),
        color = ArenaObsidian.copy(alpha = 0.88f),
        border = BorderStroke(1.5.dp, ImperialGold),
      ) {
        Column(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Text(
            text =
              if (hud.gameMode == GameMode.TRAINING) "DOJO"
              else "${hud.roundTimeRemainingSec.roundToInt()}",
            style = MaterialTheme.typography.headlineMedium,
            color = ImperialGold,
            modifier = Modifier.testTag("hud_timer_text"),
          )
          Text(
            text = "${hud.currentFps} FPS",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
          )
        }
      }

      Spacer(Modifier.height(4.dp))

      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IconButton(
          onClick = onToggleLockOn,
          modifier =
            Modifier.size(40.dp)
              .clip(CircleShape)
              .background(ArenaObsidian.copy(alpha = 0.8f))
              .border(1.dp, if (hud.lockOnEnabled) LightningCyan else TextMuted, CircleShape)
              .testTag("btn_lock_on"),
        ) {
          Icon(
            imageVector = if (hud.lockOnEnabled) Icons.Default.CenterFocusStrong else Icons.Default.CenterFocusWeak,
            contentDescription = "Toggle Target Lock-On",
            tint = if (hud.lockOnEnabled) LightningCyan else TextMuted,
            modifier = Modifier.size(18.dp),
          )
        }
        IconButton(
          onClick = onPauseClick,
          modifier =
            Modifier.size(40.dp)
              .clip(CircleShape)
              .background(ArenaObsidian.copy(alpha = 0.8f))
              .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
              .testTag("btn_pause"),
        ) {
          Icon(Icons.Default.Pause, contentDescription = "Pause Match", tint = Color.White, modifier = Modifier.size(18.dp))
        }
      }
    }

    // Opponent Status Bar
    FighterStatusBar(
      name = hud.opponentSpec.displayName,
      portraitRes = hud.opponentSpec.portraitRes,
      hp = hud.opponentHp,
      maxHp = hud.opponentMaxHp,
      energy = hud.opponentEnergy,
      guard = hud.opponentGuard,
      accent = if (hud.opponentSpec.element == ElementType.LIGHTNING) LightningCyan else FlameScarlet,
      isLeft = false,
      modifier = Modifier.weight(1f),
    )
  }
}

@Composable
private fun FighterStatusBar(
  name: String,
  portraitRes: Int,
  hp: Int,
  maxHp: Int,
  energy: Float,
  guard: Float,
  accent: Color,
  isLeft: Boolean,
  modifier: Modifier = Modifier,
) {
  val hpFrac = (hp.toFloat() / maxHp.coerceAtLeast(1)).coerceIn(0f, 1f)
  val enFrac = (energy / 100f).coerceIn(0f, 1f)

  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = if (isLeft) Arrangement.Start else Arrangement.End,
  ) {
    if (isLeft) {
      Image(
        painter = painterResource(id = portraitRes),
        contentDescription = name,
        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).border(1.5.dp, accent, RoundedCornerShape(10.dp)),
        contentScale = ContentScale.Crop,
      )
      Spacer(Modifier.width(8.dp))
    }

    Column(modifier = Modifier.weight(1f), horizontalAlignment = if (isLeft) Alignment.Start else Alignment.End) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(name, style = MaterialTheme.typography.labelLarge, color = Color.White)
        Text("$hp / $maxHp", style = MaterialTheme.typography.labelSmall, color = TextMuted)
      }
      Spacer(Modifier.height(3.dp))
      LinearProgressIndicator(
        progress = { hpFrac },
        modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)),
        color = if (hpFrac > 0.35f) EmeraldHp else CrimsonDamage,
        trackColor = ArenaObsidian.copy(alpha = 0.85f),
      )
      Spacer(Modifier.height(4.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        LinearProgressIndicator(
          progress = { enFrac },
          modifier = Modifier.weight(0.7f).height(7.dp).clip(RoundedCornerShape(4.dp)),
          color = if (energy >= 100f) ImperialGold else accent,
          trackColor = ArenaObsidian,
        )
        LinearProgressIndicator(
          progress = { (guard / 100f).coerceIn(0f, 1f) },
          modifier = Modifier.weight(0.3f).height(5.dp).clip(RoundedCornerShape(4.dp)),
          color = Color(0xFF90CAF9),
          trackColor = ArenaObsidian,
        )
      }
    }

    if (!isLeft) {
      Spacer(Modifier.width(8.dp))
      Image(
        painter = painterResource(id = portraitRes),
        contentDescription = name,
        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).border(1.5.dp, accent, RoundedCornerShape(10.dp)),
        contentScale = ContentScale.Crop,
      )
    }
  }
}

@Composable
private fun PauseOverlayModal(
  isMuted: Boolean,
  targetFps: Int,
  onToggleMute: () -> Unit,
  onToggleFps: () -> Unit,
  onResume: () -> Unit,
  onRestart: () -> Unit,
  onExit: () -> Unit,
) {
  Box(
    modifier = Modifier.fillMaxSize().background(ArenaMidnight.copy(alpha = 0.78f)),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      shape = RoundedCornerShape(18.dp),
      color = ArenaObsidian,
      border = BorderStroke(1.5.dp, LightningCyan),
      modifier = Modifier.width(360.dp),
    ) {
      Column(
        modifier = Modifier.padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text("MATCH PAUSED", style = MaterialTheme.typography.headlineMedium, color = ImperialGold)

        Button(
          onClick = onResume,
          modifier = Modifier.fillMaxWidth().height(48.dp).testTag("btn_pause_resume"),
        ) {
          Text("RESUME BATTLE")
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(
            onClick = onToggleMute,
            modifier = Modifier.weight(1f).height(48.dp).testTag("btn_pause_mute"),
          ) {
            Text(if (isMuted) "UNMUTE" else "MUTE")
          }
          OutlinedButton(
            onClick = onToggleFps,
            modifier = Modifier.weight(1f).height(48.dp).testTag("btn_pause_fps"),
          ) {
            Text("${targetFps} FPS")
          }
        }
        OutlinedButton(
          onClick = onRestart,
          modifier = Modifier.fillMaxWidth().height(48.dp).testTag("btn_pause_restart"),
        ) {
          Text("RESTART MATCH")
        }
        OutlinedButton(
          onClick = onExit,
          modifier = Modifier.fillMaxWidth().height(48.dp).testTag("btn_pause_exit"),
        ) {
          Text("EXIT TO MENU", color = CrimsonDamage)
        }
      }
    }
  }
}

@Composable
private fun MatchResultModal(
  hud: BattleHudState,
  onRematch: () -> Unit,
  onNextArcade: () -> Unit,
  onExit: () -> Unit,
) {
  val title =
    when (hud.matchPhase) {
      MatchPhase.VICTORY -> "VICTORY!"
      MatchPhase.DEFEAT -> "DEFEAT"
      else -> "TIME OVER — DRAW"
    }
  val titleColor =
    when (hud.matchPhase) {
      MatchPhase.VICTORY -> ImperialGold
      MatchPhase.DEFEAT -> CrimsonDamage
      else -> LightningCyan
    }
  val canAdvanceArcade =
    hud.gameMode == GameMode.ARCADE && hud.matchPhase == MatchPhase.VICTORY && hud.arcadeStage < 3

  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(
          Brush.verticalGradient(
            listOf(ArenaMidnight.copy(alpha = 0.68f), ArenaMidnight.copy(alpha = 0.90f))
          )
        ),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      shape = RoundedCornerShape(20.dp),
      color = ArenaObsidian,
      border = BorderStroke(2.dp, titleColor),
      modifier = Modifier.width(420.dp),
    ) {
      Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(title, style = MaterialTheme.typography.displayLarge, color = titleColor, modifier = Modifier.testTag("result_title_text"))
        Text(
          text =
            if (hud.gameMode == GameMode.ARCADE && hud.matchPhase == MatchPhase.VICTORY && hud.arcadeStage >= 3) {
              "ARCADE CHAMPION! All 3 Stages Cleared!"
            } else {
              "${hud.playerSpec.displayName} vs ${hud.opponentSpec.displayName}"
            },
          style = MaterialTheme.typography.bodyLarge,
          color = TextMuted,
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
          ResultStatBox("MAX COMBO", "${hud.maxComboAchieved} HITS")
          ResultStatBox("DAMAGE", "${hud.damageDealt}")
          ResultStatBox("TIME LEFT", "${hud.roundTimeRemainingSec.roundToInt()}s")
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          if (canAdvanceArcade) {
            Button(
              onClick = onNextArcade,
              modifier = Modifier.weight(1f).height(48.dp).testTag("btn_next_stage"),
              colors = ButtonDefaults.buttonColors(containerColor = ImperialGold, contentColor = ArenaMidnight),
            ) {
              Text("STAGE ${hud.arcadeStage + 1}")
            }
          }
          Button(
            onClick = onRematch,
            modifier = Modifier.weight(1f).height(48.dp).testTag("btn_rematch"),
          ) {
            Text("REMATCH")
          }
          OutlinedButton(
            onClick = onExit,
            modifier = Modifier.weight(1f).height(48.dp).testTag("btn_result_menu"),
          ) {
            Text("MENU")
          }
        }
      }
    }
  }
}

@Composable
private fun ResultStatBox(label: String, value: String) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    Text(value, style = MaterialTheme.typography.titleMedium, color = Color.White)
  }
}
