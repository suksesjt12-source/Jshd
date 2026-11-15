package com.example.graphics

import com.example.assets.AssetPaths
import com.example.characters.ElementType
import com.example.core.Math3D
import com.example.core.ObjectPool
import com.example.core.Vec3
import com.google.android.filament.Engine
import com.google.android.filament.Scene
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import java.nio.ByteBuffer
import kotlin.random.Random

enum class VfxKind {
  SLASH_CYAN,
  SLASH_FIRE,
  RING_CYAN,
  RING_FIRE,
  SHARD_CYAN,
  SHARD_FIRE,
}

class VfxParticle {
  var kind: VfxKind = VfxKind.SLASH_CYAN
  var asset: FilamentAsset? = null
  var active: Boolean = false
  var age: Float = 0f
  var lifetime: Float = 0.3f
  var position: Vec3 = Vec3.ZERO
  var velocity: Vec3 = Vec3.ZERO
  var yawRad: Float = 0f
  var pitchRad: Float = 0f
  var rollRad: Float = 0f
  var spinRate: Float = 0f
  var startScale: Float = 1f
  var endScale: Float = 1f
}

/**
 * Object-pooled 3D VFX system rendered natively inside Filament.
 * Pre-allocates lightweight GLB instances so combat sparks, sword slash arcs,
 * dodge bursts, and ultimate shockwaves cause zero per-frame allocations.
 */
class VfxParticleSystem(
  private val engine: Engine,
  private val scene: Scene,
  private val assetLoader: AssetLoader,
  private val resourceLoader: ResourceLoader,
  private val loadBytes: (String) -> ByteArray,
) {
  private val pools = mutableMapOf<VfxKind, ObjectPool<VfxParticle>>()
  private val activeParticles = mutableListOf<VfxParticle>()
  private val allAssets = mutableListOf<FilamentAsset>()
  private val rng = Random(99)
  private val trsScratch = FloatArray(16)
  private val hiddenMatrix = FloatArray(16).apply {
    this[0] = 0.0001f
    this[5] = 0.0001f
    this[10] = 0.0001f
    this[13] = -500f
    this[15] = 1f
  }

  private var maxActiveLimit = 48

  fun initialize(maxParticles: Int) {
    maxActiveLimit = maxParticles.coerceIn(16, 80)
    val perKindCount = mapOf(
      VfxKind.SLASH_CYAN to 6,
      VfxKind.SLASH_FIRE to 6,
      VfxKind.RING_CYAN to 6,
      VfxKind.RING_FIRE to 6,
      VfxKind.SHARD_CYAN to 14,
      VfxKind.SHARD_FIRE to 14,
    )

    val pathMap = mapOf(
      VfxKind.SLASH_CYAN to AssetPaths.VFX_SLASH_CYAN_GLB,
      VfxKind.SLASH_FIRE to AssetPaths.VFX_SLASH_FIRE_GLB,
      VfxKind.RING_CYAN to AssetPaths.VFX_RING_CYAN_GLB,
      VfxKind.RING_FIRE to AssetPaths.VFX_RING_FIRE_GLB,
      VfxKind.SHARD_CYAN to AssetPaths.VFX_SHARD_CYAN_GLB,
      VfxKind.SHARD_FIRE to AssetPaths.VFX_SHARD_FIRE_GLB,
    )

    for ((kind, count) in perKindCount) {
      val bytes = runCatching { loadBytes(pathMap.getValue(kind)) }.getOrNull() ?: continue
      val pool = ObjectPool<VfxParticle>(
        maxCapacity = count,
        factory = {
          val asset =
            if (engine.isValid) {
              runCatching {
                val buf = ByteBuffer.allocateDirect(bytes.size).apply {
                  put(bytes)
                  flip()
                }
                assetLoader.createAsset(buf)?.also { loaded ->
                  resourceLoader.loadResources(loaded)
                  loaded.releaseSourceData()
                  scene.addEntities(loaded.entities)
                  hideAsset(loaded)
                  allAssets.add(loaded)
                }
              }.getOrNull()
            } else {
              null
            }
          VfxParticle().apply {
            this.kind = kind
            this.asset = asset
          }
        },
        reset = { p ->
          p.active = false
          p.age = 0f
          p.asset?.let { hideAsset(it) }
        },
      )
      pool.prewarm(count)
      pools[kind] = pool
    }
  }

  fun setMaxActiveLimit(limit: Int) {
    maxActiveLimit = limit.coerceIn(16, 80)
  }

  fun spawnSlash(pos: Vec3, yawRad: Float, element: ElementType, scale: Float = 1.25f) {
    val kind = if (element == ElementType.LIGHTNING) VfxKind.SLASH_CYAN else VfxKind.SLASH_FIRE
    spawn(
      kind = kind,
      pos = pos + Vec3(0f, 1.15f, 0f),
      vel = Math3D.forwardFromYaw(yawRad) * 2.5f,
      yaw = yawRad,
      pitch = (rng.nextFloat() - 0.5f) * 0.45f,
      roll = (rng.nextFloat() - 0.5f) * 0.7f,
      spin = 3.5f,
      startScale = scale * 0.7f,
      endScale = scale * 1.45f,
      lifetime = 0.24f,
    )
  }

  fun spawnShockwave(pos: Vec3, element: ElementType, maxRadiusScale: Float = 2.4f, lifetime: Float = 0.36f) {
    val kind = if (element == ElementType.LIGHTNING) VfxKind.RING_CYAN else VfxKind.RING_FIRE
    spawn(
      kind = kind,
      pos = pos,
      vel = Vec3.ZERO,
      yaw = rng.nextFloat() * 6.28f,
      pitch = 0f,
      roll = 0f,
      spin = 2.0f,
      startScale = 0.35f,
      endScale = maxRadiusScale,
      lifetime = lifetime,
    )
  }

  fun spawnBurst(pos: Vec3, element: ElementType, count: Int = 6, speed: Float = 7.5f, scale: Float = 1.0f) {
    val kind = if (element == ElementType.LIGHTNING) VfxKind.SHARD_CYAN else VfxKind.SHARD_FIRE
    repeat(count) {
      val angle = rng.nextFloat() * 6.283f
      val up = 2.0f + rng.nextFloat() * 4.5f
      val spd = speed * (0.5f + rng.nextFloat() * 0.7f)
      spawn(
        kind = kind,
        pos = pos,
        vel = Vec3(kotlin.math.cos(angle) * spd, up, kotlin.math.sin(angle) * spd),
        yaw = angle,
        pitch = (rng.nextFloat() - 0.5f) * 1.2f,
        roll = (rng.nextFloat() - 0.5f) * 1.2f,
        spin = 6.0f,
        startScale = scale,
        endScale = 0.1f,
        lifetime = 0.32f + rng.nextFloat() * 0.18f,
      )
    }
  }

  private fun spawn(
    kind: VfxKind,
    pos: Vec3,
    vel: Vec3,
    yaw: Float,
    pitch: Float,
    roll: Float,
    spin: Float,
    startScale: Float,
    endScale: Float,
    lifetime: Float,
  ) {
    if (activeParticles.size >= maxActiveLimit) {
      val oldest = activeParticles.removeAt(0)
      pools[oldest.kind]?.release(oldest)
    }
    val pool = pools[kind] ?: return
    val p = pool.acquire()
    p.active = true
    p.age = 0f
    p.lifetime = lifetime
    p.position = pos
    p.velocity = vel
    p.yawRad = yaw
    p.pitchRad = pitch
    p.rollRad = roll
    p.spinRate = spin
    p.startScale = startScale
    p.endScale = endScale
    activeParticles.add(p)
  }

  fun update(dt: Float) {
    val tm = engine.transformManager
    var i = activeParticles.size - 1
    while (i >= 0) {
      val p = activeParticles[i]
      p.age += dt
      if (p.age >= p.lifetime) {
        activeParticles.removeAt(i)
        pools[p.kind]?.release(p)
      } else {
        p.position = p.position + p.velocity * dt
        p.yawRad += p.spinRate * dt
        val frac = (p.age / p.lifetime).coerceIn(0f, 1f)
        val s = Math3D.lerp(p.startScale, p.endScale, frac)
        p.asset?.let { asset ->
          val ti = tm.getInstance(asset.root)
          if (ti != 0) {
            Math3D.composeTrsColumnMajor(p.position, p.yawRad, p.pitchRad, p.rollRad, s, trsScratch)
            tm.setTransform(ti, trsScratch)
          }
        }
      }
      i--
    }
  }

  private fun hideAsset(asset: FilamentAsset) {
    val tm = engine.transformManager
    val ti = tm.getInstance(asset.root)
    if (ti != 0) {
      tm.setTransform(ti, hiddenMatrix)
    }
  }

  fun clearActive() {
    for (p in activeParticles) {
      pools[p.kind]?.release(p)
    }
    activeParticles.clear()
  }

  fun destroy() {
    clearActive()
    for (asset in allAssets) {
      scene.removeEntities(asset.entities)
      assetLoader.destroyAsset(asset)
    }
    allAssets.clear()
    pools.clear()
  }
}
