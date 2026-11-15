package com.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.core.AiDifficulty
import com.example.core.FighterId
import com.example.core.GameMode
import com.example.ui.screens.BattleScreen
import com.example.ui.screens.CharacterSelectScreen
import com.example.ui.screens.MainMenuScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StatsScreen
import kotlinx.serialization.Serializable

@Serializable data object RouteMainMenu

@Serializable data class RouteCharacterSelect(val mode: GameMode)

@Serializable
data class RouteBattle(
  val playerId: FighterId,
  val opponentId: FighterId,
  val mode: GameMode,
  val difficulty: AiDifficulty,
  val arcadeStage: Int = 1,
)

@Serializable data object RouteSettings

@Serializable data object RouteStats

@Composable
fun ArenaNavGraph(viewModel: ArenaViewModel) {
  val navController = rememberNavController()
  val settings by viewModel.settings.collectAsStateWithLifecycle()
  val totalWins by viewModel.totalWins.collectAsStateWithLifecycle()
  val bestCombo by viewModel.bestCombo.collectAsStateWithLifecycle()
  val recentMatches by viewModel.recentMatches.collectAsStateWithLifecycle()

  NavHost(navController = navController, startDestination = RouteMainMenu) {
    composable<RouteMainMenu> {
      MainMenuScreen(
        totalWins = totalWins,
        bestCombo = bestCombo,
        isMuted = settings.isMuted,
        onToggleMute = {
          viewModel.playUiClick()
          viewModel.setMuted(!settings.isMuted)
        },
        onSelectMode = { mode ->
          viewModel.playUiClick()
          navController.navigate(RouteCharacterSelect(mode)) { launchSingleTop = true }
        },
        onOpenSettings = {
          viewModel.playUiClick()
          navController.navigate(RouteSettings) { launchSingleTop = true }
        },
        onOpenStats = {
          viewModel.playUiClick()
          navController.navigate(RouteStats) { launchSingleTop = true }
        },
        onAppear = { viewModel.playMenuMusic() },
      )
    }

    composable<RouteCharacterSelect> { entry ->
      val route = entry.toRoute<RouteCharacterSelect>()
      CharacterSelectScreen(
        mode = route.mode,
        onBack = {
          viewModel.playUiClick()
          navController.popBackStack()
        },
        onStartBattle = { player, opponent, diff ->
          viewModel.playUiClick()
          navController.navigate(
            RouteBattle(
              playerId = player,
              opponentId = opponent,
              mode = route.mode,
              difficulty = if (route.mode == GameMode.ARCADE) AiDifficulty.NOVICE else diff,
              arcadeStage = 1,
            )
          ) { launchSingleTop = true }
        },
      )
    }

    composable<RouteBattle> { entry ->
      val route = entry.toRoute<RouteBattle>()
      BattleScreen(
        viewModel = viewModel,
        playerId = route.playerId,
        opponentId = route.opponentId,
        gameMode = route.mode,
        difficulty = route.difficulty,
        arcadeStage = route.arcadeStage,
        onExitToMenu = {
          viewModel.playUiClick()
          navController.popBackStack(RouteMainMenu, inclusive = false)
        },
        onNextArcadeStage = { nextOpp, nextDiff, nextStage ->
          viewModel.playUiClick()
          navController.navigate(
            RouteBattle(
              playerId = route.playerId,
              opponentId = nextOpp,
              mode = GameMode.ARCADE,
              difficulty = nextDiff,
              arcadeStage = nextStage,
            )
          ) {
            popUpTo<RouteBattle> { inclusive = true }
          }
        },
      )
    }

    composable<RouteSettings> {
      SettingsScreen(
        settings = settings,
        onTargetFpsChange = {
          viewModel.playUiClick()
          viewModel.setTargetFps(it)
        },
        onQualityChange = {
          viewModel.playUiClick()
          viewModel.setGraphicsQuality(it)
        },
        onRenderScaleChange = { viewModel.setRenderScale(it) },
        onShadowsChange = {
          viewModel.playUiClick()
          viewModel.setShadowsEnabled(it)
        },
        onMasterVolumeChange = { viewModel.setMasterVolume(it) },
        onMusicVolumeChange = { viewModel.setMusicVolume(it) },
        onSfxVolumeChange = { viewModel.setSfxVolume(it) },
        onVoiceVolumeChange = { viewModel.setVoiceVolume(it) },
        onMuteChange = {
          viewModel.playUiClick()
          viewModel.setMuted(it)
        },
        onBack = {
          viewModel.playUiClick()
          navController.popBackStack()
        },
      )
    }

    composable<RouteStats> {
      StatsScreen(
        totalWins = totalWins,
        bestCombo = bestCombo,
        matches = recentMatches,
        onClearHistory = {
          viewModel.playUiClick()
          viewModel.clearMatchHistory()
        },
        onBack = {
          viewModel.playUiClick()
          navController.popBackStack()
        },
      )
    }
  }
}
