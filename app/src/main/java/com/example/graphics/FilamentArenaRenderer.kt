package com.example.graphics

import android.content.Context
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import com.example.arena.VillageArenaData
import com.example.assets.AssetPaths
import com.example.camera.ArenaCameraController
import com.example.characters.ElementType
import com.example.characters.FighterState
import com.example.core.FighterId
import com.example.core.Math3D
import com.example.core.Vec3
import com.example.settings.GameSettings
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer

/**
 * Native 3D Filament Renderer wrapping GLTF/GLB skeletal animation, crossfade blending,
 * shadow mapping, dynamic elemental lighting, and render resolution scaling.
 */
class FilamentArenaRenderer(private val context: Context) {

  companion object {
    private const val TAG = "FilamentArenaRenderer"
    @Volatile private var libsInitialized = false

    fun ensureInit(): Boolean {
      if (libsInitialized) return true
      return synchronized(this) {
        if (libsInitialized) return@synchronized true
        try {
          Filament.init()
          Gltfio.init()
          Utils.init()
          libsInitialized = true
          true
        } catch (t: Throwable) {
          Log.e(TAG, "Failed to initialize Filament native libraries", t)
          false
        }
      }
    }
  }

  private var engine: Engine? = null
  private var renderer: Renderer? = null
  private var scene: Scene? = null
  private var view: View? = null
  private var camera: Camera? = null
  private var swapChain: SwapChain? = null
  private var uiHelper: UiHelper? = null

  private var assetLoader: AssetLoader? = null
  private var resourceLoader: ResourceLoader? = null
  private var materialProvider: UbershaderProvider? = null

  private var arenaAsset: FilamentAsset? = null
  private var playerAsset: FilamentAsset? = null
  private var opponentAsset: FilamentAsset? = null
  private var vfxSystem: VfxParticleSystem? = null

  private var sunEntity: Int = 0
  private var playerAuraLightEntity: Int = 0
  private var opponentAuraLightEntity: Int = 0
  private val lanternLightEntities = mutableListOf<Int>()
  private var indirectLight: IndirectLight? = null
  private var skybox: Skybox? = null

  private var currentSettings = GameSettings()
  private var surfaceWidth = 1280
  private var surfaceHeight = 720
  private var playerAuraIntensity = 0f
  private var opponentAuraIntensity = 0f
  @Volatile private var isDestroyed = false

  var initializationError: String? = null
    private set

  private val matScratch = FloatArray(16)

  val vfx: VfxParticleSystem? get() = vfxSystem

  fun attachToSurfaceView(
    surfaceView: SurfaceView,
    playerId: FighterId,
    opponentId: FighterId,
    settings: GameSettings,
  ) {
    if (isDestroyed) return
    if (!ensureInit()) {
      initializationError = "Native 3D engine libraries unavailable on this device."
      return
    }
    currentSettings = settings

    try {
      val eng = Engine.create()
      engine = eng
      renderer = eng.createRenderer()
      scene = eng.createScene()
      view = eng.createView()
      val camEntity = EntityManager.get().create()
      camera = eng.createCamera(camEntity)
      view?.scene = scene
      view?.camera = camera

      materialProvider = UbershaderProvider(eng)
      assetLoader = AssetLoader(eng, materialProvider!!, EntityManager.get())
      resourceLoader = ResourceLoader(eng)

      setupAtmosphereAndLighting(eng, settings)
      loadArenaAndFighters(playerId, opponentId)
      applyQualitySettings(settings)

      val helper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
      helper.renderCallback =
        object : UiHelper.RendererCallback {
          override fun onNativeWindowChanged(surface: Surface) {
            if (isDestroyed || !eng.isValid) return
            try {
              swapChain?.let { eng.destroySwapChain(it) }
              swapChain = eng.createSwapChain(surface, helper.swapChainFlags)
            } catch (t: Throwable) {
              Log.e(TAG, "Failed to create SwapChain", t)
            }
          }

          override fun onDetachedFromSurface() {
            if (!eng.isValid) return
            try {
              swapChain?.let {
                eng.destroySwapChain(it)
                swapChain = null
              }
            } catch (t: Throwable) {
              Log.w(TAG, "Error destroying SwapChain on detach", t)
            }
          }

          override fun onResized(width: Int, height: Int) {
            if (isDestroyed || !eng.isValid) return
            surfaceWidth = width.coerceAtLeast(1)
            surfaceHeight = height.coerceAtLeast(1)
            view?.viewport = Viewport(0, 0, surfaceWidth, surfaceHeight)
          }
        }
      helper.attachTo(surfaceView)
      uiHelper = helper
    } catch (t: Throwable) {
      Log.e(TAG, "Filament initialization error", t)
      initializationError = t.message ?: "3D renderer initialization failed"
      destroy()
    }
  }

  private fun setupAtmosphereAndLighting(eng: Engine, settings: GameSettings) {
    val scn = scene ?: return

    val sb = Skybox.Builder().color(0.06f, 0.07f, 0.16f, 1.0f).build(eng)
    skybox = sb
    scn.skybox = sb

    val harmonics =
      floatArrayOf(
        0.38f, 0.36f, 0.52f,
        0.10f, 0.08f, 0.18f,
        0.02f, 0.02f, 0.04f,
        -0.06f, -0.05f, -0.04f,
        0f, 0f, 0f,
        0f, 0f, 0f,
        0f, 0f, 0f,
        0f, 0f, 0f,
        0f, 0f, 0f,
      )
    val ibl = IndirectLight.Builder().irradiance(3, harmonics).intensity(26_000f).build(eng)
    indirectLight = ibl
    scn.indirectLight = ibl

    sunEntity = EntityManager.get().create()
    LightManager.Builder(LightManager.Type.DIRECTIONAL)
      .color(1.0f, 0.86f, 0.74f)
      .intensity(92_000f)
      .direction(-0.45f, -0.78f, -0.42f)
      .castShadows(true)
      .shadowOptions(
        LightManager.ShadowOptions().apply {
          mapSize = settings.graphicsQuality.shadowMapSize
        }
      )
      .build(eng, sunEntity)
    scn.addEntity(sunEntity)

    for (lp in VillageArenaData.LANTERN_POSITIONS) {
      val le = EntityManager.get().create()
      LightManager.Builder(LightManager.Type.POINT)
        .color(1.0f, 0.68f, 0.32f)
        .intensity(32_000f)
        .falloff(11.0f)
        .position(lp.x, lp.y + 0.4f, lp.z)
        .castShadows(false)
        .build(eng, le)
      scn.addEntity(le)
      lanternLightEntities.add(le)
    }

    playerAuraLightEntity = EntityManager.get().create()
    LightManager.Builder(LightManager.Type.POINT)
      .color(0.2f, 0.9f, 1.0f)
      .intensity(0f)
      .falloff(8.5f)
      .position(0f, 1.4f, 0f)
      .castShadows(false)
      .build(eng, playerAuraLightEntity)
    scn.addEntity(playerAuraLightEntity)

    opponentAuraLightEntity = EntityManager.get().create()
    LightManager.Builder(LightManager.Type.POINT)
      .color(1.0f, 0.4f, 0.1f)
      .intensity(0f)
      .falloff(8.5f)
      .position(0f, 1.4f, 0f)
      .castShadows(false)
      .build(eng, opponentAuraLightEntity)
    scn.addEntity(opponentAuraLightEntity)
  }

  private fun loadAssetBytes(path: String): ByteArray =
    context.assets.open(path).use { it.readBytes() }

  private fun loadGlb(path: String): FilamentAsset? {
    val loader = assetLoader ?: return null
    val resLoader = resourceLoader ?: return null
    val scn = scene ?: return null
    return try {
      val bytes = loadAssetBytes(path)
      val buf = ByteBuffer.allocateDirect(bytes.size).apply {
        put(bytes)
        flip()
      }
      val asset = loader.createAsset(buf) ?: return null
      resLoader.loadResources(asset)
      asset.releaseSourceData()
      scn.addEntities(asset.entities)
      asset
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to load GLB asset: $path", t)
      null
    }
  }

  private fun loadArenaAndFighters(playerId: FighterId, opponentId: FighterId) {
    arenaAsset = loadGlb(AssetPaths.ARENA_VILLAGE_GLB)
    playerAsset = loadGlb(AssetPaths.fighterModel(playerId))?.also(::disableCullingForSkinnedAsset)
    opponentAsset = loadGlb(AssetPaths.fighterModel(opponentId))?.also(::disableCullingForSkinnedAsset)

    val eng = engine ?: return
    val scn = scene ?: return
    val loader = assetLoader ?: return
    val resLoader = resourceLoader ?: return
    vfxSystem =
      VfxParticleSystem(eng, scn, loader, resLoader, ::loadAssetBytes).also {
        it.initialize(currentSettings.graphicsQuality.maxVfxParticles)
      }
  }

  fun applyQualitySettings(settings: GameSettings) {
    currentSettings = settings
    val eng = engine ?: return
    if (isDestroyed || !eng.isValid) return
    val v = view ?: return
    v.setShadowingEnabled(settings.shadowsEnabled)
    v.dynamicResolutionOptions =
      View.DynamicResolutionOptions().apply {
        enabled = settings.renderScale < 0.99f
        minScale = settings.renderScale
        maxScale = settings.renderScale
        sharpness = 0.85f
      }
    v.multiSampleAntiAliasingOptions =
      View.MultiSampleAntiAliasingOptions().apply {
        enabled = settings.graphicsQuality.msaaSamples > 1
        sampleCount = settings.graphicsQuality.msaaSamples
      }
    vfxSystem?.setMaxActiveLimit(settings.graphicsQuality.maxVfxParticles)
  }

  fun pulseAuraLight(isPlayer: Boolean, element: ElementType, intensity: Float) {
    if (isDestroyed) return
    if (isPlayer) {
      playerAuraIntensity = maxOf(playerAuraIntensity, intensity)
      setLightColor(playerAuraLightEntity, element)
    } else {
      opponentAuraIntensity = maxOf(opponentAuraIntensity, intensity)
      setLightColor(opponentAuraLightEntity, element)
    }
  }

  private fun setLightColor(entity: Int, element: ElementType) {
    val eng = engine ?: return
    if (!eng.isValid || entity == 0) return
    val lm = eng.lightManager
    val inst = lm.getInstance(entity)
    if (inst != 0) {
      if (element == ElementType.LIGHTNING) {
        lm.setColor(inst, 0.15f, 0.88f, 1.0f)
      } else {
        lm.setColor(inst, 1.0f, 0.38f, 0.10f)
      }
    }
  }

  fun renderFrame(
    dt: Float,
    player: FighterState,
    opponent: FighterState,
    camCtrl: ArenaCameraController,
    isUltimateActive: Boolean,
  ) {
    if (isDestroyed) return
    val eng = engine ?: return
    if (!eng.isValid) return
    val rend = renderer ?: return
    val sc = swapChain ?: return
    val vw = view ?: return
    val cam = camera ?: return
    if (uiHelper?.isReadyToRender != true) return

    try {
      updateFighterAsset(eng, playerAsset, player)
      updateFighterAsset(eng, opponentAsset, opponent)

      playerAuraIntensity = (playerAuraIntensity - dt * 60_000f).coerceAtLeast(if (isUltimateActive) 18_000f else 4_000f)
      opponentAuraIntensity = (opponentAuraIntensity - dt * 60_000f).coerceAtLeast(4_000f)
      updateAuraLightTransform(eng, playerAuraLightEntity, player.position, playerAuraIntensity)
      updateAuraLightTransform(eng, opponentAuraLightEntity, opponent.position, opponentAuraIntensity)

      val lm = eng.lightManager
      val sunInst = lm.getInstance(sunEntity)
      if (sunInst != 0) {
        lm.setIntensity(sunInst, if (isUltimateActive) 28_000f else 92_000f)
      }

      vfxSystem?.update(dt)

      val aspect = surfaceWidth.toDouble() / surfaceHeight.toDouble().coerceAtLeast(1.0)
      cam.setProjection(camCtrl.fovDegrees.toDouble(), aspect, 0.1, 140.0, Camera.Fov.VERTICAL)
      cam.lookAt(
        camCtrl.eye.x.toDouble(),
        camCtrl.eye.y.toDouble(),
        camCtrl.eye.z.toDouble(),
        camCtrl.target.x.toDouble(),
        camCtrl.target.y.toDouble(),
        camCtrl.target.z.toDouble(),
        0.0,
        1.0,
        0.0,
      )

      if (rend.beginFrame(sc, System.nanoTime())) {
        rend.render(vw)
        rend.endFrame()
      }
    } catch (t: Throwable) {
      Log.e(TAG, "Render frame error", t)
    }
  }

  private fun updateAuraLightTransform(eng: Engine, entity: Int, pos: Vec3, intensity: Float) {
    if (entity == 0) return
    val lm = eng.lightManager
    val inst = lm.getInstance(entity)
    if (inst != 0) {
      lm.setPosition(inst, pos.x, pos.y + 1.35f, pos.z)
      lm.setIntensity(inst, intensity)
    }
  }

  private fun disableCullingForSkinnedAsset(asset: FilamentAsset) {
    val eng = engine ?: return
    if (!eng.isValid) return
    val rm = eng.renderableManager
    for (entity in asset.renderableEntities) {
      val inst = rm.getInstance(entity)
      if (inst != 0) {
        rm.setCulling(inst, false)
      }
    }
  }

  private fun updateFighterAsset(eng: Engine, asset: FilamentAsset?, state: FighterState) {
    asset ?: return
    val tm = eng.transformManager
    val ti = tm.getInstance(asset.root)
    if (ti != 0) {
      Math3D.composeTrsColumnMajor(state.position, state.yawRad, 0f, 0f, 1.0f, matScratch)
      tm.setTransform(ti, matScratch)
    }

    val animator = asset.instance.animator
    val animCount = animator.animationCount
    if (animCount > 0) {
      val curIdx = state.animMachine.currentState.clipIndex.coerceIn(0, animCount - 1)
      animator.applyAnimation(curIdx, state.animMachine.stateTime)
      if (state.animMachine.isBlending()) {
        val prevIdx = state.animMachine.previousState.clipIndex.coerceIn(0, animCount - 1)
        val alpha = state.animMachine.blendAlpha()
        animator.applyCrossFade(prevIdx, state.animMachine.previousTime, alpha)
      }
      animator.updateBoneMatrices()
    }
  }

  fun destroy() {
    if (isDestroyed) return
    isDestroyed = true
    val eng = engine ?: return

    try {
      uiHelper?.detach()
      uiHelper = null

      vfxSystem?.destroy()
      vfxSystem = null

      listOfNotNull(arenaAsset, playerAsset, opponentAsset).forEach { asset ->
        scene?.removeEntities(asset.entities)
        assetLoader?.destroyAsset(asset)
      }
      arenaAsset = null
      playerAsset = null
      opponentAsset = null

      val em = EntityManager.get()
      if (sunEntity != 0) {
        scene?.removeEntity(sunEntity)
        eng.destroyEntity(sunEntity)
        em.destroy(sunEntity)
        sunEntity = 0
      }
      if (playerAuraLightEntity != 0) {
        scene?.removeEntity(playerAuraLightEntity)
        eng.destroyEntity(playerAuraLightEntity)
        em.destroy(playerAuraLightEntity)
        playerAuraLightEntity = 0
      }
      if (opponentAuraLightEntity != 0) {
        scene?.removeEntity(opponentAuraLightEntity)
        eng.destroyEntity(opponentAuraLightEntity)
        em.destroy(opponentAuraLightEntity)
        opponentAuraLightEntity = 0
      }
      for (le in lanternLightEntities) {
        scene?.removeEntity(le)
        eng.destroyEntity(le)
        em.destroy(le)
      }
      lanternLightEntities.clear()

      indirectLight?.let { eng.destroyIndirectLight(it) }
      indirectLight = null
      skybox?.let { eng.destroySkybox(it) }
      skybox = null
      swapChain?.let { eng.destroySwapChain(it) }
      swapChain = null

      resourceLoader?.destroy()
      resourceLoader = null
      materialProvider?.destroyMaterials()
      materialProvider?.destroy()
      materialProvider = null
      assetLoader?.destroy()
      assetLoader = null

      view?.let { eng.destroyView(it) }
      view = null
      camera?.let {
        eng.destroyCameraComponent(it.entity)
        em.destroy(it.entity)
      }
      camera = null
      scene?.let { eng.destroyScene(it) }
      scene = null
      renderer?.let { eng.destroyRenderer(it) }
      renderer = null

      eng.flushAndWait()
      eng.destroy()
    } catch (t: Throwable) {
      Log.w(TAG, "Error during Filament teardown", t)
    } finally {
      engine = null
    }
  }
}
