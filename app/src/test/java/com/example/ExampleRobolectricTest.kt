package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.assets.AssetPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule
  val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun `app name matches Anime Arena Legends`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Anime Arena Legends", appName)
  }

  @Test
  fun `all 3d glb models and audio assets exist in apk assets`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val assetManager = context.assets

    val requiredModels =
      listOf(
        AssetPaths.ARENA_VILLAGE_GLB,
        AssetPaths.FIGHTER_RAIDEN_GLB,
        AssetPaths.FIGHTER_HOMURA_GLB,
        AssetPaths.VFX_SLASH_CYAN_GLB,
        AssetPaths.VFX_SLASH_FIRE_GLB,
        AssetPaths.VFX_RING_CYAN_GLB,
        AssetPaths.VFX_RING_FIRE_GLB,
        AssetPaths.VFX_SHARD_CYAN_GLB,
        AssetPaths.VFX_SHARD_FIRE_GLB,
      )

    for (modelPath in requiredModels) {
      assetManager.open(modelPath).use { stream ->
        val bytes = stream.readBytes()
        assertTrue("Expected non-empty GLB asset: $modelPath", bytes.size > 100)
        val magic = java.nio.ByteBuffer.wrap(bytes, 0, 12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals("GLB magic mismatch for $modelPath", 0x46546C67, magic.int)
        assertEquals("GLB version mismatch for $modelPath", 2, magic.int)
        val chunkHeader = java.nio.ByteBuffer.wrap(bytes, 12, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val jsonLen = chunkHeader.int
        val jsonStr = String(bytes, 20, jsonLen, Charsets.UTF_8)
        assertTrue("GLB missing bufferViews in $modelPath", jsonStr.contains("\"bufferViews\":["))
        assertTrue("GLB missing accessors in $modelPath", jsonStr.contains("\"accessors\":["))
      }
    }

    val requiredAudio =
      listOf(
        AssetPaths.BGM_MENU,
        AssetPaths.BGM_BATTLE,
        AssetPaths.BGM_BATTLE_INTENSE,
        AssetPaths.BGM_ULTIMATE,
        AssetPaths.BGM_VICTORY,
        AssetPaths.BGM_DEFEAT,
        AssetPaths.SFX_LIGHT_HIT,
        AssetPaths.SFX_HEAVY_IMPACT,
        AssetPaths.SFX_BLOCK,
        AssetPaths.SFX_DODGE,
        AssetPaths.SFX_JUMP,
        AssetPaths.SFX_LIGHTNING,
        AssetPaths.SFX_FIRE,
        AssetPaths.SFX_ULTIMATE_IMPACT,
      )

    for (audioPath in requiredAudio) {
      assetManager.open(audioPath).use { stream ->
        assertTrue("Expected non-empty WAV asset: $audioPath", stream.available() > 100)
      }
    }
  }

  @Test
  fun `full ui navigation across menu, settings, records, character select, and battle runs without force close`() {
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("btn_versus_mode").assertIsDisplayed()
    composeTestRule.onNodeWithTag("btn_settings").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("chip_fps_60").assertIsDisplayed().performClick()
    composeTestRule.onNodeWithTag("btn_settings_back").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("btn_records").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("btn_stats_back").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("btn_versus_mode").performClick()
    composeTestRule.waitForIdle()

    // BattleScreen runs an active Choreographer frame loop; step the clock manually so Compose test does not spin in waitForIdle.
    composeTestRule.mainClock.autoAdvance = false
    composeTestRule.onNodeWithTag("btn_enter_arena").assertIsDisplayed().performClick()
    repeat(6) { composeTestRule.mainClock.advanceTimeByFrame() }

    // Battle HUD & Pause flow
    composeTestRule.onNodeWithTag("btn_pause").assertIsDisplayed().performClick()
    repeat(4) { composeTestRule.mainClock.advanceTimeByFrame() }
    composeTestRule.onNodeWithTag("btn_pause_exit").assertIsDisplayed().performClick()
    repeat(4) { composeTestRule.mainClock.advanceTimeByFrame() }

    composeTestRule.mainClock.autoAdvance = true
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("btn_versus_mode").assertIsDisplayed()
  }
}
