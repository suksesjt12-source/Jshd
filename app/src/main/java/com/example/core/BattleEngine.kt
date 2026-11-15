package com.example.core

import androidx.compose.runtime.Immutable
import com.example.ai.AiOpponentController
import com.example.animation.AnimationState
import com.example.arena.VillageArenaData
import com.example.assets.AssetPaths
import com.example.audio.AudioEngine
import com.example.audio.BgmTrack
import com.example.audio.VoiceCue
import com.example.audio.VoiceSystem
import com.example.camera.ArenaCameraController
import com.example.characters.AttackMoveType
import com.example.characters.ElementType
import com.example.characters.FighterActionPhase
import com.example.characters.FighterCatalog
import com.example.characters.FighterSpec
import com.example.characters.FighterState
import com.example.combat.ActionKind
import com.example.combat.CombatFeedbackEvent
import com.example.combat.CombatSystem
import com.example.combat.UltimateCinematicController
import com.example.combat.UltimateSnapshot
import com.example.combat.UltimateStage
import com.example.graphics.FilamentArenaRenderer
import com.example.physics.ArenaPhysics
import com.example.storage.MatchRecordEntity
import com.example.storage.MatchRecordRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class BattleHudState(
  val playerSpec: FighterSpec = FighterCatalog.RAIDEN,
  val opponentSpec: FighterSpec = FighterCatalog.HOMURA,
  val playerHp: Int = 1000,
  val playerMaxHp: Int = 1000,
  val playerEnergy: Float = 25f,
  val playerGuard: Float = 100f,
  val opponentHp: Int = 1100,
  val opponentMaxHp: Int = 1100,
  val opponentEnergy: Float = 25f,
  val opponentGuard: Float = 100f,
  val playerCombo: Int = 0,
  val opponentCombo: Int = 0,
  val specialCooldownSec: Float = 0f,
  val heavyCooldownSec: Float = 0f,
  val roundTimeRemainingSec: Float = 99f,
  val matchPhase: MatchPhase = MatchPhase.INTRO,
  val introBannerText: String = "READY...",
  val lockOnEnabled: Boolean = true,
  val arcadeStage: Int = 1,
  val gameMode: GameMode = GameMode.VERSUS,
  val ultimate: UltimateSnapshot = UltimateSnapshot(),
  val popups: List<DamagePopup> = emptyList(),
  val currentFps: Int = 30,
  val maxComboAchieved: Int = 0,
  val damageDealt: Int = 0,
)

class BattleEngine(
  private val playerId: FighterId,
  private val opponentId: FighterId,
  private val gameMode: GameMode,
  private val difficulty: AiDifficulty,
  private val arcadeStage: Int,
  private val audioEngine: AudioEngine,
  private val recordRepository: MatchRecordRepository,
  private val coroutineScope: CoroutineScope,
) {
  val player: FighterState =
    FighterState(
      spec = FighterCatalog.get(playerId),
      isPlayer = true,
      initialPos = VillageArenaData.PLAYER_SPAWN,
      initialYawRad = kotlin.math.PI.toFloat(),
    )

  val opponent: FighterState =
    FighterState(
      spec = FighterCatalog.get(opponentId),
      isPlayer = false,
      initialPos = VillageArenaData.OPPONENT_SPAWN,
      initialYawRad = 0f,
    )

  val cameraController = ArenaCameraController()
  private val combatSystem = CombatSystem()
  private val ultimateController = UltimateCinematicController()
  private val aiController = AiOpponentController(difficulty, gameMode)
  private val voiceSystem = VoiceSystem(audioEngine)

  private val popupPool = ObjectPool<DamagePopup>(16, { DamagePopup() }, { it.ttl = 0f })
  private val activePopups = mutableListOf<DamagePopup>()
  private val frameEvents = mutableListOf<CombatFeedbackEvent>()

  private var rendererRef: FilamentArenaRenderer? = null
  private var matchPhase: MatchPhase = MatchPhase.INTRO
  private var introTimerSec = 1.6f
  private var roundTimerSec = 99f
  private var lockOnEnabled = true
  private var joyX = 0f
  private var joyY = 0f
  private var isGuardingInput = false
  private var matchRecorded = false
  private var intenseBgmActive = false
  private var popupIdSeq = 1L
  private var idleVoiceTimerSec = 9.0f

  private var fpsAccumulator = 0f
  private var fpsFrames = 0
  private var displayedFps = 30

  private val _hudState = MutableStateFlow(buildHudSnapshot())
  val hudState: StateFlow<BattleHudState> = _hudState.asStateFlow()

  init {
    cameraController.reset(player.position, opponent.position)
    ultimateController.onStageAudioCue = { atk, stage ->
      when (stage) {
        UltimateStage.PREP_CLOSEUP -> {
          audioEngine.playBgm(BgmTrack.ULTIMATE, loop = true)
          audioEngine.playSfx(AssetPaths.SFX_CINEMATIC_WHOOSH)
          voiceSystem.trigger(atk.spec.id, VoiceCue.ULT_PREP)
        }
        UltimateStage.ENERGY_GATHER -> {
          audioEngine.playSfx(AssetPaths.SFX_ENERGY_CHARGE)
        }
        UltimateStage.STRIKE_DASH -> {
          voiceSystem.trigger(atk.spec.id, VoiceCue.ULT_SHOUT)
          audioEngine.playSfx(
            if (atk.spec.element == ElementType.LIGHTNING) AssetPaths.SFX_LIGHTNING
            else AssetPaths.SFX_FIRE
          )
        }
        UltimateStage.IMPACT_SLOWMO -> {
          audioEngine.playSfx(AssetPaths.SFX_ULTIMATE_IMPACT)
          cameraController.triggerShake(0.45f)
        }
        else -> Unit
      }
    }
    ultimateController.onSpawnUltimateVfx = { atk, def, stage ->
      val r = rendererRef
      when (stage) {
        UltimateStage.PREP_CLOSEUP,
        UltimateStage.ENERGY_GATHER -> {
          r?.pulseAuraLight(atk.isPlayer, atk.spec.element, 140_000f)
          r?.vfx?.spawnShockwave(atk.position, atk.spec.element, maxRadiusScale = 2.8f, lifetime = 0.48f)
          r?.vfx?.spawnBurst(atk.position + Vec3(0f, 1.1f, 0f), atk.spec.element, count = 8, speed = 5.5f)
        }
        UltimateStage.STRIKE_DASH -> {
          r?.pulseAuraLight(atk.isPlayer, atk.spec.element, 180_000f)
          r?.vfx?.spawnSlash(atk.position, atk.yawRad, atk.spec.element, scale = 2.1f)
        }
        UltimateStage.IMPACT_SLOWMO -> {
          r?.pulseAuraLight(atk.isPlayer, atk.spec.element, 220_000f)
          r?.vfx?.spawnShockwave(def.position, atk.spec.element, maxRadiusScale = 4.6f, lifetime = 0.65f)
          r?.vfx?.spawnBurst(def.position + Vec3(0f, 1.1f, 0f), atk.spec.element, count = 12, speed = 11.5f, scale = 1.4f)
        }
        else -> Unit
      }
    }
    audioEngine.playBgm(BgmTrack.BATTLE_NORMAL, loop = true)
    voiceSystem.trigger(player.spec.id, VoiceCue.INTRO)
  }

  fun attachRenderer(renderer: FilamentArenaRenderer) {
    rendererRef = renderer
  }

  fun setJoystickInput(x: Float, y: Float) {
    joyX = x.coerceIn(-1f, 1f)
    joyY = y.coerceIn(-1f, 1f)
  }

  fun toggleLockOn() {
    lockOnEnabled = !lockOnEnabled
    audioEngine.playSfx(AssetPaths.SFX_UI_CLICK)
  }

  fun onLightAttackPressed() {
    if (matchPhase != MatchPhase.FIGHTING) return
    combatSystem.requestMove(player, opponent, AttackMoveType.LIGHT_1, frameEvents)
  }

  fun onHeavyAttackPressed() {
    if (matchPhase != MatchPhase.FIGHTING) return
    combatSystem.requestMove(player, opponent, AttackMoveType.HEAVY_1, frameEvents)
  }

  fun onLauncherPressed() {
    if (matchPhase != MatchPhase.FIGHTING) return
    combatSystem.requestMove(player, opponent, AttackMoveType.LAUNCHER, frameEvents)
  }

  fun onSpecialAttackPressed() {
    if (matchPhase != MatchPhase.FIGHTING) return
    combatSystem.requestMove(player, opponent, AttackMoveType.SPECIAL, frameEvents)
  }

  fun onUltimatePressed() {
    if (matchPhase != MatchPhase.FIGHTING) return
    if (ultimateController.tryActivate(player, opponent, combatSystem, frameEvents)) {
      matchPhase = MatchPhase.ULTIMATE_CINEMATIC
    }
  }

  fun onSkipUltimate() {
    if (matchPhase != MatchPhase.ULTIMATE_CINEMATIC) return
    ultimateController.skipCinematic(combatSystem, frameEvents)
    restoreBattleBgmAfterUltimate()
    matchPhase = MatchPhase.FIGHTING
  }

  fun onJumpPressed() {
    if (matchPhase != MatchPhase.FIGHTING) return
    combatSystem.performJump(player, frameEvents)
  }

  fun onDodgePressed() {
    if (matchPhase != MatchPhase.FIGHTING) return
    val worldMove = computeCameraRelativeMove(joyX, joyY)
    combatSystem.performDodge(player, worldMove.x, worldMove.z, frameEvents)
  }

  fun onGuardChanged(held: Boolean) {
    isGuardingInput = held
    if (matchPhase == MatchPhase.FIGHTING) {
      combatSystem.setGuarding(player, held, frameEvents)
    }
  }

  fun setPaused(paused: Boolean) {
    if (paused && (matchPhase == MatchPhase.FIGHTING || matchPhase == MatchPhase.INTRO)) {
      matchPhase = MatchPhase.PAUSED
      audioEngine.onPause()
    } else if (!paused && matchPhase == MatchPhase.PAUSED) {
      matchPhase = if (introTimerSec > 0f) MatchPhase.INTRO else MatchPhase.FIGHTING
      audioEngine.onResume()
    }
    _hudState.value = buildHudSnapshot()
  }

  fun resetMatch() {
    player.resetForRound(VillageArenaData.PLAYER_SPAWN, kotlin.math.PI.toFloat(), startEnergy = if (gameMode == GameMode.TRAINING) 100f else 25f)
    opponent.resetForRound(VillageArenaData.OPPONENT_SPAWN, 0f, startEnergy = 25f)
    combatSystem.reset()
    aiController.reset()
    voiceSystem.reset()
    cameraController.reset(player.position, opponent.position)
    rendererRef?.vfx?.clearActive()
    activePopups.forEach { popupPool.release(it) }
    activePopups.clear()
    roundTimerSec = 99f
    introTimerSec = 1.6f
    matchPhase = MatchPhase.INTRO
    matchRecorded = false
    intenseBgmActive = false
    audioEngine.playBgm(BgmTrack.BATTLE_NORMAL, loop = true)
    voiceSystem.trigger(player.spec.id, VoiceCue.INTRO)
    _hudState.value = buildHudSnapshot()
  }

  fun stepFrame(rawDt: Float) {
    val clampedDt = rawDt.coerceIn(0.001f, 0.05f)
    fpsAccumulator += clampedDt
    fpsFrames += 1
    if (fpsAccumulator >= 0.5f) {
      displayedFps = (fpsFrames / fpsAccumulator).toInt().coerceAtLeast(1)
      fpsAccumulator = 0f
      fpsFrames = 0
    }

    if (matchPhase == MatchPhase.PAUSED) {
      rendererRef?.renderFrame(0f, player, opponent, cameraController, false)
      return
    }

    voiceSystem.update(clampedDt)

    // Popups decay
    var pi = activePopups.size - 1
    while (pi >= 0) {
      val p = activePopups[pi]
      p.ttl -= clampedDt
      p.worldY += 0.9f * clampedDt
      if (p.ttl <= 0f) {
        activePopups.removeAt(pi)
        popupPool.release(p)
      }
      pi--
    }

    var ultSnapshot = UltimateSnapshot()
    var simDt = clampedDt

    when (matchPhase) {
      MatchPhase.INTRO -> {
        introTimerSec -= clampedDt
        player.animMachine.update(clampedDt)
        opponent.animMachine.update(clampedDt)
        if (introTimerSec <= 0f) {
          matchPhase = MatchPhase.FIGHTING
        }
      }
      MatchPhase.ULTIMATE_CINEMATIC -> {
        ultSnapshot = ultimateController.update(clampedDt, combatSystem, frameEvents)
        simDt = clampedDt * ultSnapshot.timeScale
        player.animMachine.update(simDt)
        opponent.animMachine.update(simDt)
        if (!ultSnapshot.isActive) {
          restoreBattleBgmAfterUltimate()
          matchPhase = MatchPhase.FIGHTING
          checkMatchEnd()
        }
      }
      MatchPhase.FIGHTING -> {
        if (!combatSystem.tickHitstop(clampedDt)) {
          if (gameMode != GameMode.TRAINING) {
            roundTimerSec = (roundTimerSec - clampedDt).coerceAtLeast(0f)
          } else {
            // Training mode regenerates opponent HP when idle
            if (opponent.actionPhase == FighterActionPhase.NEUTRAL && opponent.hp < opponent.spec.maxHp) {
              opponent.hp = (opponent.hp + (160f * clampedDt).toInt()).coerceAtMost(opponent.spec.maxHp)
            }
          }

          // Update player movement & locomotion animations
          updatePlayerMovement(clampedDt)
          combatSystem.setGuarding(player, isGuardingInput, frameEvents)

          // Update AI
          aiController.update(clampedDt, opponent, player, combatSystem, ultimateController, frameEvents)
          if (ultimateController.isActive) {
            matchPhase = MatchPhase.ULTIMATE_CINEMATIC
          }

          // Combat timers & hit detection
          combatSystem.updateFighterCombatState(
            player,
            opponent,
            clampedDt,
            frameEvents,
            infiniteEnergyTraining = gameMode == GameMode.TRAINING,
          )
          combatSystem.updateFighterCombatState(opponent, player, clampedDt, frameEvents)

          // Physics & collision
          val wasPlayerGrounded = player.isGrounded
          val wasOppGrounded = opponent.isGrounded
          ArenaPhysics.stepFighterPhysics(player, clampedDt)
          ArenaPhysics.stepFighterPhysics(opponent, clampedDt)
          ArenaPhysics.resolveFighterSeparation(player, opponent)

          if (!wasPlayerGrounded && player.isGrounded) {
            frameEvents.add(CombatFeedbackEvent.ActionTriggered(true, ActionKind.LAND, player.position, player.spec.element))
          }
          if (!wasOppGrounded && opponent.isGrounded) {
            frameEvents.add(CombatFeedbackEvent.ActionTriggered(false, ActionKind.LAND, opponent.position, opponent.spec.element))
          }

          updateAirborneAnimation(player)
          updateAirborneAnimation(opponent)

          player.animMachine.update(clampedDt)
          opponent.animMachine.update(clampedDt)

          // Adaptive battle BGM intensity when either fighter drops below 35% HP
          if (!intenseBgmActive && (player.hp < player.spec.maxHp * 0.35f || opponent.hp < opponent.spec.maxHp * 0.35f)) {
            intenseBgmActive = true
            audioEngine.playBgm(BgmTrack.BATTLE_INTENSE, loop = true)
          }

          // Idle character voice flavor
          idleVoiceTimerSec -= clampedDt
          if (idleVoiceTimerSec <= 0f) {
            idleVoiceTimerSec = 11f
            if (player.actionPhase == FighterActionPhase.NEUTRAL && opponent.actionPhase == FighterActionPhase.NEUTRAL) {
              voiceSystem.trigger(player.spec.id, VoiceCue.IDLE)
            }
          }

          checkMatchEnd()
        }
      }
      MatchPhase.VICTORY,
      MatchPhase.DEFEAT,
      MatchPhase.DRAW -> {
        ArenaPhysics.stepFighterPhysics(player, clampedDt)
        ArenaPhysics.stepFighterPhysics(opponent, clampedDt)
        player.animMachine.update(clampedDt)
        opponent.animMachine.update(clampedDt)
      }
      MatchPhase.PAUSED -> Unit
    }

    processFrameEvents()

    val ended = matchPhase == MatchPhase.VICTORY || matchPhase == MatchPhase.DEFEAT || matchPhase == MatchPhase.DRAW
    val winner = when (matchPhase) {
      MatchPhase.VICTORY -> player
      MatchPhase.DEFEAT -> opponent
      else -> null
    }

    cameraController.update(
      dt = clampedDt,
      player = player,
      opponent = opponent,
      lockOnEnabled = lockOnEnabled,
      ultimateCtrl = ultimateController,
      matchEnded = ended,
      winner = winner,
    )

    rendererRef?.renderFrame(simDt, player, opponent, cameraController, ultimateController.isActive)
    _hudState.value = buildHudSnapshot(ultSnapshot)
  }

  private fun updatePlayerMovement(dt: Float) {
    if (player.actionPhase != FighterActionPhase.NEUTRAL) return

    if (lockOnEnabled) {
      val toOpp = opponent.position - player.position
      if (toOpp.lengthXZ() > 0.1f) {
        val targetYaw = Math3D.yawFromDir(toOpp.x, toOpp.z)
        player.yawRad = Math3D.lerpAngle(player.yawRad, targetYaw, 12f * dt)
      }
    }

    val magSq = joyX * joyX + joyY * joyY
    if (magSq > 0.02f) {
      val worldDir = computeCameraRelativeMove(joyX, joyY)
      val mag = kotlin.math.sqrt(magSq).coerceIn(0f, 1f)
      val sprinting = mag > 0.72f
      val speed = if (sprinting) player.spec.runSpeed else player.spec.moveSpeed * (0.5f + 0.5f * mag)
      player.velocity = Vec3(worldDir.x * speed, player.velocity.y, worldDir.z * speed)
      if (!lockOnEnabled) {
        player.yawRad = Math3D.lerpAngle(player.yawRad, Math3D.yawFromDir(worldDir.x, worldDir.z), 14f * dt)
      }
      if (player.isGrounded) {
        player.animMachine.transitionTo(if (sprinting) AnimationState.RUN else AnimationState.WALK)
        player.footstepTimerSec -= dt
        if (player.footstepTimerSec <= 0f) {
          player.footstepTimerSec = if (sprinting) 0.24f else 0.38f
          frameEvents.add(CombatFeedbackEvent.ActionTriggered(true, ActionKind.FOOTSTEP, player.position, player.spec.element))
        }
      }
    } else if (player.isGrounded) {
      player.animMachine.transitionTo(AnimationState.IDLE)
    }
  }

  private fun computeCameraRelativeMove(jx: Float, jy: Float): Vec3 {
    val camFwd = (cameraController.target - cameraController.eye).normalizedXZ()
    val camRight = Vec3(-camFwd.z, 0f, camFwd.x)
    return (camRight * jx + camFwd * (-jy)).normalizedXZ()
  }

  private fun updateAirborneAnimation(f: FighterState) {
    if (!f.isGrounded && f.actionPhase == FighterActionPhase.NEUTRAL) {
      if (f.velocity.y > 0.6f) {
        f.animMachine.transitionTo(AnimationState.JUMP)
      } else {
        f.animMachine.transitionTo(AnimationState.FALL)
      }
    }
  }

  private fun restoreBattleBgmAfterUltimate() {
    audioEngine.playBgm(if (intenseBgmActive) BgmTrack.BATTLE_INTENSE else BgmTrack.BATTLE_NORMAL, loop = true)
  }

  private fun checkMatchEnd() {
    if (gameMode == GameMode.TRAINING) return
    val playerDead = !player.isAlive
    val oppDead = !opponent.isAlive
    val timeOut = roundTimerSec <= 0f
    if (!playerDead && !oppDead && !timeOut) return

    val playerPct = player.hp.toFloat() / player.spec.maxHp
    val oppPct = opponent.hp.toFloat() / opponent.spec.maxHp

    matchPhase =
      when {
        oppDead && !playerDead -> MatchPhase.VICTORY
        playerDead && !oppDead -> MatchPhase.DEFEAT
        playerPct > oppPct -> MatchPhase.VICTORY
        oppPct > playerPct -> MatchPhase.DEFEAT
        else -> MatchPhase.DRAW
      }

    when (matchPhase) {
      MatchPhase.VICTORY -> {
        player.actionPhase = FighterActionPhase.VICTORIOUS
        opponent.actionPhase = FighterActionPhase.DEFEATED
        player.animMachine.transitionTo(AnimationState.VICTORY, forceRestart = true)
        opponent.animMachine.transitionTo(AnimationState.DEFEAT, forceRestart = true)
        audioEngine.playSfx(AssetPaths.SFX_VICTORY)
        audioEngine.playBgm(BgmTrack.VICTORY, loop = false)
        voiceSystem.trigger(player.spec.id, VoiceCue.VICTORY)
      }
      MatchPhase.DEFEAT -> {
        player.actionPhase = FighterActionPhase.DEFEATED
        opponent.actionPhase = FighterActionPhase.VICTORIOUS
        player.animMachine.transitionTo(AnimationState.DEFEAT, forceRestart = true)
        opponent.animMachine.transitionTo(AnimationState.VICTORY, forceRestart = true)
        audioEngine.playSfx(AssetPaths.SFX_DEFEAT)
        audioEngine.playBgm(BgmTrack.DEFEAT, loop = false)
        voiceSystem.trigger(player.spec.id, VoiceCue.DEFEAT)
      }
      else -> {
        audioEngine.playBgm(BgmTrack.DEFEAT, loop = false)
      }
    }

    if (!matchRecorded) {
      matchRecorded = true
      val won = matchPhase == MatchPhase.VICTORY
      coroutineScope.launch {
        recordRepository.recordMatch(
          MatchRecordEntity(
            playerFighter = player.spec.displayName,
            opponentFighter = opponent.spec.displayName,
            gameMode = gameMode.name,
            won = won,
            maxCombo = player.maxComboAchieved,
            damageDealt = player.totalDamageDealt,
            ultimatesLanded = player.ultimatesLanded,
            remainingSeconds = roundTimerSec.toInt(),
          )
        )
      }
    }
  }

  private fun processFrameEvents() {
    if (frameEvents.isEmpty()) return
    for (ev in frameEvents) {
      when (ev) {
        is CombatFeedbackEvent.MoveStarted -> {
          val actor = if (ev.attackerIsPlayer) player else opponent
          when (ev.moveType) {
            AttackMoveType.LIGHT_1,
            AttackMoveType.LIGHT_2,
            AttackMoveType.LIGHT_3 -> {
              voiceSystem.trigger(actor.spec.id, VoiceCue.LIGHT)
              rendererRef?.vfx?.spawnSlash(ev.origin, ev.yawRad, ev.element, scale = 1.1f)
              rendererRef?.pulseAuraLight(ev.attackerIsPlayer, ev.element, 42_000f)
            }
            AttackMoveType.HEAVY_1,
            AttackMoveType.HEAVY_2,
            AttackMoveType.LAUNCHER,
            AttackMoveType.AIR_COMBO -> {
              voiceSystem.trigger(actor.spec.id, VoiceCue.HEAVY)
              rendererRef?.vfx?.spawnSlash(ev.origin, ev.yawRad, ev.element, scale = 1.45f)
              rendererRef?.pulseAuraLight(ev.attackerIsPlayer, ev.element, 68_000f)
            }
            AttackMoveType.SPECIAL -> {
              voiceSystem.trigger(actor.spec.id, VoiceCue.SPECIAL)
              audioEngine.playSfx(AssetPaths.SFX_ENERGY_RELEASE)
              audioEngine.playSfx(
                if (ev.element == ElementType.LIGHTNING) AssetPaths.SFX_LIGHTNING else AssetPaths.SFX_FIRE
              )
              rendererRef?.vfx?.spawnSlash(ev.origin, ev.yawRad, ev.element, scale = 1.85f)
              rendererRef?.vfx?.spawnShockwave(ev.origin, ev.element, maxRadiusScale = 2.6f)
              rendererRef?.pulseAuraLight(ev.attackerIsPlayer, ev.element, 110_000f)
            }
            AttackMoveType.ULTIMATE -> Unit
          }
        }
        is CombatFeedbackEvent.HitLanded -> {
          val defender = if (ev.attackerIsPlayer) opponent else player
          if (ev.isBlocked) {
            audioEngine.playSfx(AssetPaths.SFX_BLOCK)
            rendererRef?.vfx?.spawnShockwave(ev.impactPoint - Vec3(0f, 0.8f, 0f), ev.element, maxRadiusScale = 1.4f, lifetime = 0.22f)
          } else {
            voiceSystem.trigger(defender.spec.id, VoiceCue.HIT)
            val isHeavy = ev.isCritical || ev.isUltimate || ev.isGuardBreak
            audioEngine.playSfx(if (isHeavy) AssetPaths.SFX_HEAVY_IMPACT else AssetPaths.SFX_LIGHT_HIT)
            if (ev.moveType == AttackMoveType.SPECIAL || ev.isUltimate) {
              audioEngine.playSfx(
                if (ev.element == ElementType.LIGHTNING) AssetPaths.SFX_LIGHTNING else AssetPaths.SFX_FIRE
              )
            }
            cameraController.triggerShake(
              when {
                ev.isUltimate -> 0.42f
                ev.isCritical -> 0.22f
                else -> 0.10f
              }
            )
            rendererRef?.vfx?.spawnBurst(ev.impactPoint, ev.element, count = if (isHeavy) 8 else 4, speed = if (isHeavy) 9f else 5.5f)
            if (isHeavy) {
              rendererRef?.vfx?.spawnShockwave(defender.position, ev.element, maxRadiusScale = 2.2f)
            }
            rendererRef?.pulseAuraLight(ev.attackerIsPlayer, ev.element, 95_000f)
          }

          val popup = popupPool.acquire()
          popup.id = popupIdSeq++
          popup.amount = ev.damage
          popup.isBlocked = ev.isBlocked
          popup.isCritical = ev.isCritical || ev.isGuardBreak
          popup.isUltimate = ev.isUltimate
          popup.worldX = ev.impactPoint.x
          popup.worldY = ev.impactPoint.y + 0.5f
          popup.worldZ = ev.impactPoint.z
          popup.ttl = 0.85f
          activePopups.add(popup)
        }
        is CombatFeedbackEvent.ActionTriggered -> {
          when (ev.kind) {
            ActionKind.JUMP -> audioEngine.playSfx(AssetPaths.SFX_JUMP)
            ActionKind.DODGE -> {
              audioEngine.playSfx(AssetPaths.SFX_DODGE)
              rendererRef?.vfx?.spawnShockwave(ev.position, ev.element, maxRadiusScale = 1.5f, lifetime = 0.24f)
            }
            ActionKind.GUARD_START -> audioEngine.playSfx(AssetPaths.SFX_BLOCK, rate = 1.15f, volumeScale = 0.6f)
            ActionKind.FOOTSTEP -> audioEngine.playSfx(AssetPaths.SFX_FOOTSTEP, volumeScale = 0.55f)
            ActionKind.LAND -> audioEngine.playSfx(AssetPaths.SFX_FOOTSTEP, rate = 0.85f, volumeScale = 0.75f)
          }
        }
      }
    }
    frameEvents.clear()
  }

  private fun buildHudSnapshot(ult: UltimateSnapshot = UltimateSnapshot()): BattleHudState {
    val banner =
      when {
        matchPhase == MatchPhase.INTRO && introTimerSec > 0.7f -> "READY..."
        matchPhase == MatchPhase.INTRO -> "FIGHT!"
        else -> ""
      }
    return BattleHudState(
      playerSpec = player.spec,
      opponentSpec = opponent.spec,
      playerHp = player.hp,
      playerMaxHp = player.spec.maxHp,
      playerEnergy = player.energy,
      playerGuard = player.guardGauge,
      opponentHp = opponent.hp,
      opponentMaxHp = opponent.spec.maxHp,
      opponentEnergy = opponent.energy,
      opponentGuard = opponent.guardGauge,
      playerCombo = player.comboCounter,
      opponentCombo = opponent.comboCounter,
      specialCooldownSec = player.cooldowns[AttackMoveType.SPECIAL] ?: 0f,
      heavyCooldownSec = player.cooldowns[AttackMoveType.HEAVY_1] ?: 0f,
      roundTimeRemainingSec = roundTimerSec,
      matchPhase = matchPhase,
      introBannerText = banner,
      lockOnEnabled = lockOnEnabled,
      arcadeStage = arcadeStage,
      gameMode = gameMode,
      ultimate = ult,
      popups = activePopups.map { it.copy() },
      currentFps = displayedFps,
      maxComboAchieved = player.maxComboAchieved,
      damageDealt = player.totalDamageDealt,
    )
  }
}
