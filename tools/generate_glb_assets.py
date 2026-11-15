#!/usr/bin/env python3
"""
Procedural 3D GLB Asset Generator for Anime Arena Legends.
Generates rigged, skinned, UV-textured, multi-animation anatomical anime fighters,
Japanese fantasy village arena architecture, and 3D VFX meshes in binary glTF 2.0 (.glb).
Original IP / CC0 project assets — zero external copyright dependencies.
"""

import json
import math
import os
import struct
import zlib

OUT_DIR = os.path.join("app", "src", "main", "assets", "models")
os.makedirs(OUT_DIR, exist_ok=True)


# ---------------------------------------------------------------------------
# Pure-Python PNG encoder (RGBA)
# ---------------------------------------------------------------------------
def encode_png(width: int, height: int, pixels_rgba: bytearray) -> bytes:
    def chunk(tag: bytes, data: bytes) -> bytes:
        crc = zlib.crc32(tag + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)

    raw = bytearray()
    stride = width * 4
    for y in range(height):
        raw.append(0)  # filter type 0
        raw.extend(pixels_rgba[y * stride:(y + 1) * stride])

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    idat = zlib.compress(bytes(raw), level=6)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", idat) + chunk(b"IEND", b"")


# ---------------------------------------------------------------------------
# Math helpers
# ---------------------------------------------------------------------------
def v_sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def v_add(a, b):
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])


def v_cross(a, b):
    return (
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )


def v_norm(a):
    l = math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
    if l < 1e-8:
        return (0.0, 1.0, 0.0)
    return (a[0] / l, a[1] / l, a[2] / l)


def euler_to_quat(rx: float, ry: float, rz: float):
    """XYZ intrinsic Euler (radians) -> (x, y, z, w) quaternion."""
    cx, sx = math.cos(rx * 0.5), math.sin(rx * 0.5)
    cy, sy = math.cos(ry * 0.5), math.sin(ry * 0.5)
    cz, sz = math.cos(rz * 0.5), math.sin(rz * 0.5)
    w = cx * cy * cz + sx * sy * sz
    x = sx * cy * cz - cx * sy * sz
    y = cx * sy * cz + sx * cy * sz
    z = cx * cy * sz - sx * sy * cz
    l = math.sqrt(x * x + y * y + z * z + w * w)
    if l < 1e-8:
        return [0.0, 0.0, 0.0, 1.0]
    return [x / l, y / l, z / l, w / l]


def mat4_translate(tx, ty, tz):
    return [
        1, 0, 0, 0,
        0, 1, 0, 0,
        0, 0, 1, 0,
        tx, ty, tz, 1,
    ]


# ---------------------------------------------------------------------------
# Mesh Builder
# ---------------------------------------------------------------------------
class MeshBuilder:
    def __init__(self, skinned: bool = False):
        self.skinned = skinned
        self.positions = []
        self.normals = []
        self.uvs = []
        self.joints = []
        self.weights = []
        self.indices = []

    def add_vertex(self, pos, normal, uv, j4=(0, 0, 0, 0), w4=(1.0, 0.0, 0.0, 0.0)):
        idx = len(self.positions)
        self.positions.append(pos)
        self.normals.append(v_norm(normal))
        self.uvs.append(uv)
        if self.skinned:
            self.joints.append(j4)
            s = sum(w4)
            if s > 1e-6:
                w4 = tuple(w / s for w in w4)
            else:
                w4 = (1.0, 0.0, 0.0, 0.0)
            self.weights.append(w4)
        return idx

    def add_tri(self, i0, i1, i2):
        self.indices.extend([i0, i1, i2])

    def add_quad(self, i0, i1, i2, i3):
        self.indices.extend([i0, i1, i2, i0, i2, i3])

    def add_loft(
        self,
        rings,
        uv_rect=(0.0, 0.0, 1.0, 1.0),
        closed_caps=True,
        twist_fn=None,
    ):
        """
        rings: list of (center_xyz, rx, rz, j4, w4, ring_shape_fn)
        ring_shape_fn optional: (theta, rx, rz) -> (dx, dy, dz) in local XZ plane
        """
        segs = 14
        u0, v0, u1, v1 = uv_rect
        ring_verts = []
        n_rings = len(rings)
        for ri, rdata in enumerate(rings):
            cx, cy, cz = rdata[0]
            rx, rz = rdata[1], rdata[2]
            j4 = rdata[3]
            w4 = rdata[4]
            shape_fn = rdata[5] if len(rdata) > 5 else None
            v_coord = v0 + (v1 - v0) * (ri / max(1, n_rings - 1))
            row = []
            for si in range(segs + 1):
                frac = si / segs
                theta = (frac - 0.5) * 2.0 * math.pi
                if twist_fn:
                    theta += twist_fn(ri, n_rings)
                if shape_fn:
                    dx, dy, dz = shape_fn(theta, rx, rz)
                else:
                    dx = math.sin(theta) * rx
                    dy = 0.0
                    dz = math.cos(theta) * rz
                px, py, pz = cx + dx, cy + dy, cz + dz
                nx, ny, nz = dx / max(1e-4, rx), 0.0, dz / max(1e-4, rz)
                u_coord = u0 + (u1 - u0) * frac
                row.append(self.add_vertex((px, py, pz), (nx, ny, nz), (u_coord, v_coord), j4, w4))
            ring_verts.append(row)

        for ri in range(n_rings - 1):
            rA = ring_verts[ri]
            rB = ring_verts[ri + 1]
            for si in range(segs):
                self.add_quad(rA[si], rB[si], rB[si + 1], rA[si + 1])

        if closed_caps and n_rings >= 2:
            # Bottom cap
            c0 = rings[0][0]
            j0, w0 = rings[0][3], rings[0][4]
            center_bot = self.add_vertex(c0, (0, -1, 0), ((u0 + u1) * 0.5, v0), j0, w0)
            for si in range(segs):
                self.add_tri(center_bot, ring_verts[0][si + 1], ring_verts[0][si])
            # Top cap
            c1 = rings[-1][0]
            j1, w1 = rings[-1][3], rings[-1][4]
            center_top = self.add_vertex(c1, (0, 1, 0), ((u0 + u1) * 0.5, v1), j1, w1)
            for si in range(segs):
                self.add_tri(center_top, ring_verts[-1][si], ring_verts[-1][si + 1])

        self.recompute_smooth_normals_partial(ring_verts)

    def recompute_smooth_normals_partial(self, ring_verts):
        # Smooth normals already well-approximated radially; refine vertical slope for limbs
        pass

    def add_sculpted_ribbon(self, pts, widths, thicknesses, uv_rect, j4, w4):
        """Creates a smooth tapered 3D blade/hair-lock/coat-tail ribbon along 3D spine points."""
        u0, v0, u1, v1 = uv_rect
        rings = []
        n = len(pts)
        for i in range(n):
            p = pts[i]
            if i < n - 1:
                tang = v_norm(v_sub(pts[i + 1], p))
            else:
                tang = v_norm(v_sub(p, pts[i - 1]))
            up = (0.0, 1.0, 0.0) if abs(tang[1]) < 0.92 else (0.0, 0.0, 1.0)
            side = v_norm(v_cross(tang, up))
            ortho = v_norm(v_cross(side, tang))
            w = widths[i]
            t = thicknesses[i]
            v = v0 + (v1 - v0) * (i / max(1, n - 1))
            # 6-point diamond/hexagonal contoured cross-section (sharp edges, convex center)
            profile = [
                (side[0] * w, side[1] * w, side[2] * w, side),
                (side[0] * w * 0.4 + ortho[0] * t, side[1] * w * 0.4 + ortho[1] * t, side[2] * w * 0.4 + ortho[2] * t, ortho),
                (-side[0] * w * 0.4 + ortho[0] * t, -side[1] * w * 0.4 + ortho[1] * t, -side[2] * w * 0.4 + ortho[2] * t, ortho),
                (-side[0] * w, -side[1] * w, -side[2] * w, (-side[0], -side[1], -side[2])),
                (-side[0] * w * 0.4 - ortho[0] * t, -side[1] * w * 0.4 - ortho[1] * t, -side[2] * w * 0.4 - ortho[2] * t, (-ortho[0], -ortho[1], -ortho[2])),
                (side[0] * w * 0.4 - ortho[0] * t, side[1] * w * 0.4 - ortho[1] * t, side[2] * w * 0.4 - ortho[2] * t, (-ortho[0], -ortho[1], -ortho[2])),
            ]
            row = []
            for pi, (dx, dy, dz, nrm) in enumerate(profile):
                u = u0 + (u1 - u0) * (pi / 5.0)
                row.append(self.add_vertex((p[0] + dx, p[1] + dy, p[2] + dz), nrm, (u, v), j4, w4))
            row.append(row[0])
            rings.append(row)

        for i in range(n - 1):
            for k in range(6):
                self.add_quad(rings[i][k], rings[i + 1][k], rings[i + 1][k + 1], rings[i][k + 1])

    def add_box_extruded(self, cx, cy, cz, sx, sy, sz, uv_rect=(0, 0, 1, 1), bevel=0.0, rot_y=0.0):
        """Architectural contoured prism (with optional pitched roof bevel on top)."""
        u0, v0, u1, v1 = uv_rect
        hx, hy, hz = sx * 0.5, sy * 0.5, sz * 0.5
        tx = hx * (1.0 - bevel)
        tz = hz * (1.0 - bevel)
        corners = [
            (-hx, -hy, -hz), (hx, -hy, -hz), (hx, -hy, hz), (-hx, -hy, hz),
            (-tx, hy, -tz), (tx, hy, -tz), (tx, hy, tz), (-tx, hy, tz),
        ]
        cy_r, sy_r = math.cos(rot_y), math.sin(rot_y)
        pts = []
        for x, y, z in corners:
            rx = x * cy_r - z * sy_r + cx
            rz = x * sy_r + z * cy_r + cz
            pts.append((rx, y + cy, rz))

        faces = [
            (0, 1, 5, 4),
            (1, 2, 6, 5),
            (2, 3, 7, 6),
            (3, 0, 4, 7),
            (4, 5, 6, 7),
            (3, 2, 1, 0),
        ]
        for a, b, c, d in faces:
            e1 = v_sub(pts[b], pts[a])
            e2 = v_sub(pts[c], pts[a])
            nrm = v_norm(v_cross(e1, e2))
            ia = self.add_vertex(pts[a], nrm, (u0, v0))
            ib = self.add_vertex(pts[b], nrm, (u1, v0))
            ic = self.add_vertex(pts[c], nrm, (u1, v1))
            id_ = self.add_vertex(pts[d], nrm, (u0, v1))
            self.add_quad(ia, ib, ic, id_)


# ---------------------------------------------------------------------------
# GLB Writer
# ---------------------------------------------------------------------------
def write_glb(path: str, gltf_dict: dict, bin_bytes: bytes):
    # Pad BIN to 4 bytes
    bin_pad = (4 - (len(bin_bytes) % 4)) % 4
    bin_bytes = bin_bytes + (b"\x00" * bin_pad)
    gltf_dict["buffers"] = [{"byteLength": len(bin_bytes)}]

    json_bytes = json.dumps(gltf_dict, separators=(",", ":")).encode("utf-8")
    json_pad = (4 - (len(json_bytes) % 4)) % 4
    json_bytes = json_bytes + (b" " * json_pad)

    total_len = 12 + 8 + len(json_bytes) + 8 + len(bin_bytes)
    with open(path, "wb") as f:
        f.write(struct.pack("<III", 0x46546C67, 2, total_len))
        f.write(struct.pack("<II", len(json_bytes), 0x4E4F534A))
        f.write(json_bytes)
        f.write(struct.pack("<II", len(bin_bytes), 0x004E4942))
        f.write(bin_bytes)
    print(f"Generated {path} ({total_len / 1024:.1f} KB)")


class BinPacker:
    def __init__(self):
        self.buf = bytearray()
        self.views = []
        self.accessors = []

    def append_bytes(self, data: bytes) -> tuple[int, int]:
        pad = (4 - (len(self.buf) % 4)) % 4
        if pad:
            self.buf.extend(b"\x00" * pad)
        offset = len(self.buf)
        self.buf.extend(data)
        return offset, len(data)

    def add_buffer_view(self, data: bytes, target: int = None) -> int:
        offset, length = self.append_bytes(data)
        bv = {"buffer": 0, "byteOffset": offset, "byteLength": length}
        if target is not None:
            bv["target"] = target
        idx = len(self.views)
        self.views.append(bv)
        return idx

    def add_accessor(self, bv_idx: int, component_type: int, count: int, acc_type: str, min_v=None, max_v=None) -> int:
        acc = {
            "bufferView": bv_idx,
            "componentType": component_type,
            "count": count,
            "type": acc_type,
        }
        if min_v is not None:
            acc["min"] = min_v
        if max_v is not None:
            acc["max"] = max_v
        idx = len(self.accessors)
        self.accessors.append(acc)
        return idx


# ---------------------------------------------------------------------------
# Fighter Texture Generator (512x512 stylized cel-shaded anime atlas)
# UV layout:
#  [0.00..0.50, 0.00..0.50] -> Skin + Anime Face (eyes centered around u=0.25, v=0.28)
#  [0.50..1.00, 0.00..0.50] -> Hair gradient & highlights
#  [0.00..0.50, 0.50..1.00] -> Armored Haori / Kimono fabric + trim + runes
#  [0.50..1.00, 0.50..1.00] -> Hakama, Greaves, Metal Pauldrons & Katana Blade
# ---------------------------------------------------------------------------
def build_fighter_textures(theme: str):
    W, H = 512, 512
    base = bytearray(W * H * 4)
    emis = bytearray(W * H * 4)

    if theme == "raiden":
        skin_lit = (246, 214, 192)
        skin_shd = (212, 168, 146)
        eye_iris = (0, 225, 255)
        hair_root = (180, 210, 240)
        hair_tip = (238, 248, 255)
        coat_main = (22, 34, 72)
        coat_trim = (240, 196, 82)
        accent = (0, 230, 255)
        hakama = (16, 22, 42)
        metal = (150, 175, 210)
        blade_edge = (110, 245, 255)
    else:
        skin_lit = (242, 206, 182)
        skin_shd = (205, 158, 134)
        eye_iris = (255, 185, 30)
        hair_root = (70, 14, 22)
        hair_tip = (230, 48, 42)
        coat_main = (128, 20, 28)
        coat_trim = (245, 175, 58)
        accent = (255, 105, 30)
        hakama = (26, 20, 24)
        metal = (110, 96, 104)
        blade_edge = (255, 118, 40)

    def put(buf, x, y, r, g, b, a=255):
        if 0 <= x < W and 0 <= y < H:
            i = (y * W + x) * 4
            buf[i] = max(0, min(255, int(r)))
            buf[i + 1] = max(0, min(255, int(g)))
            buf[i + 2] = max(0, min(255, int(b)))
            buf[i + 3] = max(0, min(255, int(a)))

    for y in range(H):
        v = y / (H - 1)
        for x in range(W):
            u = x / (W - 1)
            er, eg, eb = 0, 0, 0
            if u < 0.5 and v < 0.5:
                # Skin & Anime Face quadrant
                shade = 1.0 - 0.18 * abs(u - 0.25) * 4.0
                r = skin_lit[0] * shade + skin_shd[0] * (1 - shade)
                g = skin_lit[1] * shade + skin_shd[1] * (1 - shade)
                b = skin_lit[2] * shade + skin_shd[2] * (1 - shade)
                # Anime eyes on front face (u around 0.19 and 0.31, v around 0.24)
                for ex in (0.19, 0.31):
                    dx = (u - ex) / 0.036
                    dy = (v - 0.24) / 0.022
                    d2 = dx * dx + dy * dy
                    if d2 < 1.0:
                        if d2 > 0.72:
                            r, g, b = 22, 20, 32  # sharp lash
                        elif (dx * dx * 1.4 + dy * dy) < 0.35:
                            r, g, b = eye_iris
                            er, eg, eb = eye_iris[0] // 3, eye_iris[1] // 3, eye_iris[2] // 3
                            if (dx + 0.25) ** 2 + (dy + 0.25) ** 2 < 0.08:
                                r, g, b = 255, 255, 255  # specular anime catchlight
                        else:
                            r, g, b = 248, 250, 255
                # Determined eyebrows (above eyes since v0=chin, v1=crown)
                for side in (-1, 1):
                    bx = 0.25 + side * 0.06
                    by = 0.285 + abs(u - bx) * 0.15
                    if abs(u - bx) < 0.038 and abs(v - by) < 0.005:
                        r, g, b = 30, 28, 38
                # Mouth line (below eyes, closer to chin v0)
                if abs(u - 0.25) < 0.028 and abs(v - 0.14) < 0.004:
                    r, g, b = 150, 88, 82
            elif u >= 0.5 and v < 0.5:
                # Hair quadrant with cel highlight band
                t = (v / 0.5)
                band = 1.18 if 0.38 < t < 0.52 else 1.0
                r = (hair_root[0] * (1 - t) + hair_tip[0] * t) * band
                g = (hair_root[1] * (1 - t) + hair_tip[1] * t) * band
                b = (hair_root[2] * (1 - t) + hair_tip[2] * t) * band
                # Strand lines
                if int(u * 120) % 5 == 0:
                    r *= 0.86
                    g *= 0.86
                    b *= 0.86
            elif u < 0.5 and v >= 0.5:
                # Haori / Armor jacket + trim + elemental runes
                lu = u / 0.5
                lv = (v - 0.5) / 0.5
                r, g, b = coat_main
                # Subtle kimono weave
                if (x + y) % 6 < 2:
                    r *= 0.92
                    g *= 0.92
                    b *= 0.92
                # Gold / elemental border trim
                if lv > 0.86 or lv < 0.08 or abs(lu - 0.5) < 0.05:
                    r, g, b = coat_trim
                # Glowing elemental rune stripe
                if 0.72 < lv < 0.79 and (int(lu * 16) % 2 == 0):
                    r, g, b = accent
                    er, eg, eb = accent[0] // 2, accent[1] // 2, accent[2] // 2
            else:
                # Hakama + Metal Armor + Katana Blade
                lu = (u - 0.5) / 0.5
                lv = (v - 0.5) / 0.5
                if lv < 0.45:
                    # Pleated hakama
                    pleat = 1.0 - 0.22 * ((math.sin(lu * math.pi * 14) + 1.0) * 0.5)
                    r, g, b = hakama[0] * pleat, hakama[1] * pleat, hakama[2] * pleat
                elif lv < 0.78:
                    # Metallic armor plates
                    sheen = 0.85 + 0.3 * math.sin(lv * 25.0)
                    r, g, b = metal[0] * sheen, metal[1] * sheen, metal[2] * sheen
                    if abs(lu - 0.5) < 0.07:
                        r, g, b = coat_trim
                else:
                    # Katana blade with glowing elemental edge
                    if lu < 0.35:
                        r, g, b = blade_edge
                        er, eg, eb = blade_edge[0], blade_edge[1], blade_edge[2]
                    else:
                        r, g, b = 220, 232, 245

            put(base, x, y, r, g, b, 255)
            put(emis, x, y, er, eg, eb, 255)

    return encode_png(W, H, base), encode_png(W, H, emis)


# ---------------------------------------------------------------------------
# Skeleton & Animation Clips for Anime Fighters
# ---------------------------------------------------------------------------
BONES = [
    ("Root", -1, (0.0, 0.0, 0.0)),           # 0
    ("Hips", 0, (0.0, 0.96, 0.0)),           # 1
    ("Spine", 1, (0.0, 0.14, 0.0)),          # 2
    ("Chest", 2, (0.0, 0.24, 0.0)),          # 3
    ("Neck", 3, (0.0, 0.24, 0.0)),           # 4
    ("Head", 4, (0.0, 0.10, 0.0)),           # 5
    ("Shoulder_L", 3, (0.16, 0.19, 0.0)),    # 6
    ("UpperArm_L", 6, (0.08, -0.02, 0.0)),   # 7
    ("Forearm_L", 7, (0.0, -0.26, 0.0)),     # 8
    ("Hand_L", 8, (0.0, -0.24, 0.0)),        # 9
    ("Shoulder_R", 3, (-0.16, 0.19, 0.0)),   # 10
    ("UpperArm_R", 10, (-0.08, -0.02, 0.0)), # 11
    ("Forearm_R", 11, (0.0, -0.26, 0.0)),    # 12
    ("Hand_R", 12, (0.0, -0.24, 0.0)),       # 13
    ("Weapon_R", 13, (0.0, -0.04, 0.08)),    # 14
    ("Thigh_L", 1, (0.11, -0.04, 0.0)),      # 15
    ("Shin_L", 15, (0.0, -0.42, 0.0)),       # 16
    ("Foot_L", 16, (0.0, -0.44, 0.0)),       # 17
    ("Thigh_R", 1, (-0.11, -0.04, 0.0)),     # 18
    ("Shin_R", 18, (0.0, -0.42, 0.0)),       # 19
    ("Foot_R", 19, (0.0, -0.44, 0.0)),       # 20
]
BONE_IDX = {name: i for i, (name, _, _) in enumerate(BONES)}


def compute_world_positions():
    world = []
    for i, (_, parent, offset) in enumerate(BONES):
        if parent < 0:
            world.append(offset)
        else:
            world.append(v_add(world[parent], offset))
    return world


def build_fighter_mesh(theme: str) -> MeshBuilder:
    mb = MeshBuilder(skinned=True)
    wpos = compute_world_positions()

    def bpos(name, dx=0.0, dy=0.0, dz=0.0):
        p = wpos[BONE_IDX[name]]
        return (p[0] + dx, p[1] + dy, p[2] + dz)

    # UV regions
    UV_SKIN = (0.04, 0.04, 0.46, 0.46)
    UV_FACE = (0.02, 0.02, 0.48, 0.48)
    UV_HAIR = (0.52, 0.04, 0.96, 0.46)
    UV_COAT = (0.04, 0.54, 0.46, 0.96)
    UV_HAKAMA = (0.54, 0.52, 0.96, 0.70)
    UV_ARMOR = (0.54, 0.72, 0.96, 0.86)
    UV_BLADE = (0.54, 0.89, 0.96, 0.98)

    # 1. Torso (Hips -> Spine -> Chest -> Collar)
    hips_i = BONE_IDX["Hips"]
    spine_i = BONE_IDX["Spine"]
    chest_i = BONE_IDX["Chest"]
    mb.add_loft([
        (bpos("Hips", 0, -0.05, 0), 0.16, 0.12, (hips_i, spine_i, 0, 0), (0.8, 0.2, 0, 0)),
        (bpos("Spine", 0, -0.02, 0), 0.135, 0.105, (spine_i, hips_i, 0, 0), (0.75, 0.25, 0, 0)),
        (bpos("Spine", 0, 0.12, 0), 0.155, 0.115, (spine_i, chest_i, 0, 0), (0.5, 0.5, 0, 0)),
        (bpos("Chest", 0, 0.04, 0), 0.19, 0.13, (chest_i, spine_i, 0, 0), (0.85, 0.15, 0, 0)),
        (bpos("Chest", 0, 0.20, 0), 0.175, 0.11, (chest_i, 0, 0, 0), (1.0, 0, 0, 0)),
        (bpos("Neck", 0, 0.03, 0), 0.075, 0.07, (BONE_IDX["Neck"], chest_i, 0, 0), (0.7, 0.3, 0, 0)),
    ], uv_rect=UV_COAT)

    # 2. Flared Haori coat tails (4 sculpted flowing panels around waist)
    for ang_deg, w0, w1 in [(-150, 0.12, 0.17), (-30, 0.12, 0.17), (150, 0.12, 0.17), (30, 0.12, 0.17)]:
        ang = math.radians(ang_deg)
        sx, sz = math.sin(ang), math.cos(ang)
        pts = [
            bpos("Spine", sx * 0.14, 0.05, sz * 0.11),
            bpos("Hips", sx * 0.18, -0.04, sz * 0.15),
            bpos("Hips", sx * 0.22, -0.22, sz * 0.19),
            bpos("Hips", sx * 0.26, -0.40, sz * 0.22),
        ]
        mb.add_sculpted_ribbon(pts, [w0, w0 * 1.15, w1, w1 * 1.1], [0.015, 0.014, 0.012, 0.008],
                               UV_COAT, (hips_i, spine_i, 0, 0), (0.7, 0.3, 0, 0))

    # 3. Neck & Sculpted Anime Head (jawline taper, brow, cranium)
    neck_i = BONE_IDX["Neck"]
    head_i = BONE_IDX["Head"]

    def face_shape(theta, rx, rz):
        # Slightly pinched chin in front (+Z), rounded cranium in back (-Z)
        x = math.sin(theta) * rx
        z = math.cos(theta) * rz
        if z > 0:
            z *= 1.06
        return (x, 0.0, z)

    mb.add_loft([
        (bpos("Neck", 0, 0.0, 0), 0.058, 0.058, (neck_i, head_i, 0, 0), (0.7, 0.3, 0, 0)),
        (bpos("Head", 0, 0.01, 0.02), 0.055, 0.068, (head_i, neck_i, 0, 0), (0.85, 0.15, 0, 0), face_shape),
        (bpos("Head", 0, 0.07, 0.015), 0.088, 0.098, (head_i, 0, 0, 0), (1.0, 0, 0, 0), face_shape),
        (bpos("Head", 0, 0.14, 0.005), 0.096, 0.105, (head_i, 0, 0, 0), (1.0, 0, 0, 0), face_shape),
        (bpos("Head", 0, 0.21, 0.0), 0.085, 0.095, (head_i, 0, 0, 0), (1.0, 0, 0, 0)),
        (bpos("Head", 0, 0.25, -0.01), 0.04, 0.045, (head_i, 0, 0, 0), (1.0, 0, 0, 0)),
    ], uv_rect=UV_FACE)

    # 4. Sculpted Anime Hair Spikes (16 dynamic tapered locks)
    spike_count = 14 if theme == "raiden" else 12
    for si in range(spike_count):
        frac = si / spike_count
        ang = frac * 2.0 * math.pi
        is_front = math.cos(ang) > 0.35
        # Crown start -> sweep outward & backward/downward like anime locks
        ox = math.sin(ang) * 0.06
        oz = math.cos(ang) * 0.07
        if is_front:
            # Sharp anime bangs framing the forehead
            pts = [
                bpos("Head", ox * 0.8, 0.23, oz * 0.8),
                bpos("Head", ox * 1.45, 0.19, oz * 1.45 + 0.02),
                bpos("Head", ox * 1.35, 0.11, oz * 1.4 + 0.02),
            ]
            widths = [0.032, 0.026, 0.004]
        else:
            # Swept-back spiky anime mane
            lift = 0.09 if theme == "raiden" else 0.04
            pts = [
                bpos("Head", ox * 0.8, 0.21, oz * 0.8),
                bpos("Head", ox * 1.6, 0.22 + lift * 0.5, oz * 1.7 - 0.04),
                bpos("Head", ox * 2.1, 0.18 + lift, oz * 2.2 - 0.10),
            ]
            widths = [0.042, 0.032, 0.005]
        mb.add_sculpted_ribbon(pts, widths, [0.018, 0.014, 0.004], UV_HAIR, (head_i, 0, 0, 0), (1.0, 0, 0, 0))

    # 5. Arms, Armored Pauldrons & Gauntlets (Left & Right)
    for side, sh_n, ua_n, fa_n, ha_n in [
        (1, "Shoulder_L", "UpperArm_L", "Forearm_L", "Hand_L"),
        (-1, "Shoulder_R", "UpperArm_R", "Forearm_R", "Hand_R"),
    ]:
        sh_i, ua_i, fa_i, ha_i = BONE_IDX[sh_n], BONE_IDX[ua_n], BONE_IDX[fa_n], BONE_IDX[ha_n]
        # Pauldron wing
        mb.add_sculpted_ribbon([
            bpos(sh_n, 0, 0.04, 0),
            bpos(ua_n, side * 0.06, 0.04, 0),
            bpos(ua_n, side * 0.12, -0.02, 0),
        ], [0.085, 0.09, 0.06], [0.022, 0.018, 0.008], UV_ARMOR, (sh_i, chest_i, 0, 0), (0.8, 0.2, 0, 0))

        # Upper arm -> Forearm -> Gauntlet -> Hand
        mb.add_loft([
            (bpos(ua_n, 0, 0.02, 0), 0.062, 0.062, (ua_i, sh_i, 0, 0), (0.85, 0.15, 0, 0)),
            (bpos(ua_n, 0, -0.14, 0), 0.054, 0.054, (ua_i, fa_i, 0, 0), (0.8, 0.2, 0, 0)),
            (bpos(fa_n, 0, 0.02, 0), 0.052, 0.052, (fa_i, ua_i, 0, 0), (0.75, 0.25, 0, 0)),
            (bpos(fa_n, 0, -0.14, 0), 0.056, 0.052, (fa_i, 0, 0, 0), (1.0, 0, 0, 0)),
            (bpos(ha_n, 0, 0.01, 0), 0.042, 0.036, (ha_i, fa_i, 0, 0), (0.8, 0.2, 0, 0)),
            (bpos(ha_n, 0, -0.08, 0), 0.036, 0.026, (ha_i, 0, 0, 0), (1.0, 0, 0, 0)),
        ], uv_rect=UV_ARMOR)

    # 6. Legs: Pleated Hakama + Shin Greaves + Tabi Boots
    for side, th_n, sh_n, ft_n in [
        (1, "Thigh_L", "Shin_L", "Foot_L"),
        (-1, "Thigh_R", "Shin_R", "Foot_R"),
    ]:
        th_i, sh_i, ft_i = BONE_IDX[th_n], BONE_IDX[sh_n], BONE_IDX[ft_n]
        mb.add_loft([
            (bpos(th_n, 0, 0.04, 0), 0.105, 0.105, (th_i, hips_i, 0, 0), (0.8, 0.2, 0, 0)),
            (bpos(th_n, 0, -0.22, 0), 0.115, 0.11, (th_i, sh_i, 0, 0), (0.8, 0.2, 0, 0)),
            (bpos(sh_n, 0, 0.02, 0), 0.082, 0.082, (sh_i, th_i, 0, 0), (0.75, 0.25, 0, 0)),
            (bpos(sh_n, 0, -0.24, 0), 0.068, 0.072, (sh_i, 0, 0, 0), (1.0, 0, 0, 0)),
            (bpos(ft_n, 0, 0.02, 0.01), 0.056, 0.068, (ft_i, sh_i, 0, 0), (0.85, 0.15, 0, 0)),
            (bpos(ft_n, 0, -0.08, 0.05), 0.055, 0.115, (ft_i, 0, 0, 0), (1.0, 0, 0, 0)),
        ], uv_rect=UV_HAKAMA)

    # 7. Katana Weapon attached to Weapon_R (curved single-edge blade + tsuba guard + hilt)
    wep_i = BONE_IDX["Weapon_R"]
    # Hilt
    mb.add_loft([
        (bpos("Weapon_R", 0, 0, -0.18), 0.018, 0.022, (wep_i, 0, 0, 0), (1, 0, 0, 0)),
        (bpos("Weapon_R", 0, 0, 0.06), 0.019, 0.023, (wep_i, 0, 0, 0), (1, 0, 0, 0)),
        (bpos("Weapon_R", 0, 0, 0.08), 0.052, 0.052, (wep_i, 0, 0, 0), (1, 0, 0, 0)),  # Tsuba guard
        (bpos("Weapon_R", 0, 0, 0.10), 0.016, 0.028, (wep_i, 0, 0, 0), (1, 0, 0, 0)),
    ], uv_rect=UV_ARMOR)

    # Curved Samurai Katana Blade
    blade_pts = []
    blade_w = []
    blade_t = []
    for k in range(8):
        t = k / 7.0
        bz = 0.09 + t * 0.92
        by = (t * t) * 0.065  # authentic sori curvature
        blade_pts.append(bpos("Weapon_R", 0.0, by, bz))
        blade_w.append(0.006 * (1.0 - 0.4 * t))
        blade_t.append(0.024 * (1.0 - 0.55 * t))
    mb.add_sculpted_ribbon(blade_pts, blade_w, blade_t, UV_BLADE, (wep_i, 0, 0, 0), (1, 0, 0, 0))

    return mb


# ---------------------------------------------------------------------------
# Animation Clip Definitions (20 clips matching AnimationState enum)
# Format: clip_name -> (duration_sec, list of (time, {bone_name: (rx, ry, rz)}))
# Angles in degrees for readability, converted to radians -> quaternions.
# ---------------------------------------------------------------------------
def deg_pose(d):
    return {k: euler_to_quat(math.radians(v[0]), math.radians(v[1]), math.radians(v[2])) for k, v in d.items()}


def build_fighter_animations():
    base_stance = {
        "Hips": (4, -18, 0),
        "Spine": (6, 8, 0),
        "Chest": (4, 12, 0),
        "Head": (-6, -4, 0),
        "UpperArm_R": (-28, -18, -22),
        "Forearm_R": (-68, 10, 0),
        "Hand_R": (0, 0, -15),
        "UpperArm_L": (-22, 16, 26),
        "Forearm_L": (-55, -10, 0),
        "Thigh_L": (-16, 8, 6),
        "Shin_L": (26, 0, 0),
        "Thigh_R": (12, -10, -6),
        "Shin_R": (18, 0, 0),
    }

    def merge(override):
        p = dict(base_stance)
        for k, v in override.items():
            p[k] = v
        return p

    clips = []

    # 0. Idle
    clips.append(("Idle", 1.6, [
        (0.0, base_stance),
        (0.8, merge({"Spine": (10, 8, 0), "Chest": (8, 12, 0), "UpperArm_R": (-32, -18, -24), "UpperArm_L": (-26, 16, 28)})),
        (1.6, base_stance),
    ]))

    # 1. Walk
    clips.append(("Walk", 0.9, [
        (0.0, merge({"Thigh_L": (-28, 0, 4), "Shin_L": (24, 0, 0), "Thigh_R": (22, 0, -4), "Shin_R": (12, 0, 0), "UpperArm_L": (-10, 0, 18), "UpperArm_R": (-22, 0, -18)})),
        (0.45, merge({"Thigh_L": (22, 0, 4), "Shin_L": (12, 0, 0), "Thigh_R": (-28, 0, -4), "Shin_R": (24, 0, 0), "UpperArm_L": (-34, 0, 18), "UpperArm_R": (-8, 0, -18)})),
        (0.9, merge({"Thigh_L": (-28, 0, 4), "Shin_L": (24, 0, 0), "Thigh_R": (22, 0, -4), "Shin_R": (12, 0, 0), "UpperArm_L": (-10, 0, 18), "UpperArm_R": (-22, 0, -18)})),
    ]))

    # 2. Run (anime ninja/swordsman sprint, blade swept back)
    run_a = merge({"Hips": (22, 0, 0), "Spine": (18, 0, 0), "Chest": (14, 0, 0), "Head": (-20, 0, 0),
                   "UpperArm_R": (48, 12, -24), "Forearm_R": (-18, 0, 0),
                   "UpperArm_L": (-45, 0, 18), "Forearm_L": (-55, 0, 0),
                   "Thigh_L": (-55, 0, 4), "Shin_L": (75, 0, 0), "Thigh_R": (35, 0, -4), "Shin_R": (20, 0, 0)})
    run_b = merge({"Hips": (22, 0, 0), "Spine": (18, 0, 0), "Chest": (14, 0, 0), "Head": (-20, 0, 0),
                   "UpperArm_R": (52, 14, -26), "Forearm_R": (-16, 0, 0),
                   "UpperArm_L": (25, 0, 18), "Forearm_L": (-40, 0, 0),
                   "Thigh_L": (35, 0, 4), "Shin_L": (20, 0, 0), "Thigh_R": (-55, 0, -4), "Shin_R": (75, 0, 0)})
    clips.append(("Run", 0.56, [(0.0, run_a), (0.28, run_b), (0.56, run_a)]))

    # 3. Jump
    jump_pose = merge({"Hips": (8, 0, 0), "Spine": (10, 0, 0), "Thigh_L": (-58, 0, 8), "Shin_L": (86, 0, 0),
                       "Thigh_R": (-24, 0, -8), "Shin_R": (45, 0, 0), "UpperArm_R": (-65, -20, -35), "UpperArm_L": (-50, 20, 35)})
    clips.append(("Jump", 0.5, [(0.0, base_stance), (0.22, jump_pose), (0.5, jump_pose)]))

    # 4. Fall
    fall_pose = merge({"Hips": (4, 0, 0), "Thigh_L": (-34, 0, 10), "Shin_L": (54, 0, 0),
                       "Thigh_R": (-16, 0, -10), "Shin_R": (32, 0, 0), "UpperArm_R": (-35, 0, -52), "UpperArm_L": (-35, 0, 52)})
    clips.append(("Fall", 0.6, [(0.0, fall_pose), (0.3, fall_pose), (0.6, fall_pose)]))

    # 5. Light1 (diagonal slash)
    clips.append(("Light1", 0.36, [
        (0.0, merge({"Chest": (8, -32, 0), "UpperArm_R": (-42, -52, -40), "Forearm_R": (-45, 0, 0)})),
        (0.14, merge({"Chest": (14, 42, 0), "UpperArm_R": (-78, 35, 18), "Forearm_R": (-15, 0, 0), "Thigh_L": (-35, 0, 5), "Shin_L": (42, 0, 0)})),
        (0.36, base_stance),
    ]))

    # 6. Light2 (reverse upward diagonal)
    clips.append(("Light2", 0.38, [
        (0.0, merge({"Chest": (12, 40, 0), "UpperArm_R": (-68, 40, 20), "Forearm_R": (-25, 0, 0)})),
        (0.16, merge({"Chest": (-6, -42, 0), "UpperArm_R": (-118, -35, -30), "Forearm_R": (-18, 0, 0)})),
        (0.38, base_stance),
    ]))

    # 7. Light3 (spinning thrust finisher)
    clips.append(("Light3", 0.48, [
        (0.0, merge({"Hips": (10, -60, 0), "Chest": (12, -30, 0), "UpperArm_R": (-35, -50, -35)})),
        (0.20, merge({"Hips": (14, 48, 0), "Chest": (18, 45, 0), "UpperArm_R": (-88, 12, -6), "Forearm_R": (-6, 0, 0), "Thigh_L": (-48, 0, 4), "Shin_L": (58, 0, 0)})),
        (0.48, base_stance),
    ]))

    # 8. Heavy1 (two-handed overhead thunder/flame cleave)
    clips.append(("Heavy1", 0.62, [
        (0.0, merge({"Chest": (-14, -18, 0), "UpperArm_R": (-145, -10, -14), "Forearm_R": (-25, 0, 0), "UpperArm_L": (-135, 15, 14), "Forearm_L": (-30, 0, 0)})),
        (0.28, merge({"Hips": (18, 10, 0), "Chest": (26, 22, 0), "UpperArm_R": (-32, 18, -12), "Forearm_R": (-12, 0, 0), "UpperArm_L": (-38, -12, 12)})),
        (0.62, base_stance),
    ]))

    # 9. Heavy2 (lunging cross slash)
    clips.append(("Heavy2", 0.58, [
        (0.0, merge({"Hips": (12, -45, 0), "Chest": (10, -45, 0), "UpperArm_R": (-85, -60, -25)})),
        (0.24, merge({"Hips": (16, 42, 0), "Chest": (20, 48, 0), "UpperArm_R": (-72, 52, 22), "Forearm_R": (-10, 0, 0)})),
        (0.58, base_stance),
    ]))

    # 10. Launcher (rising dragon uppercut slash)
    clips.append(("Launcher", 0.56, [
        (0.0, merge({"Hips": (18, 0, 0), "Thigh_L": (-55, 0, 6), "Shin_L": (85, 0, 0), "Thigh_R": (-45, 0, -6), "Shin_R": (75, 0, 0), "UpperArm_R": (15, -35, -35)})),
        (0.22, merge({"Hips": (-10, 28, 0), "Chest": (-18, 30, 0), "UpperArm_R": (-155, 20, -12), "Forearm_R": (-12, 0, 0), "Thigh_L": (-25, 0, 6), "Shin_L": (25, 0, 0)})),
        (0.56, base_stance),
    ]))

    # 11. AirCombo (mid-air whirlwind slash)
    clips.append(("AirCombo", 0.46, [
        (0.0, merge({"Hips": (8, -70, 0), "Thigh_L": (-50, 0, 10), "Shin_L": (75, 0, 0), "UpperArm_R": (-90, -50, -30)})),
        (0.20, merge({"Hips": (12, 70, 0), "Chest": (14, 45, 0), "UpperArm_R": (-85, 55, 25), "Forearm_R": (-8, 0, 0)})),
        (0.46, fall_pose),
    ]))

    # 12. HitReaction
    clips.append(("HitReaction", 0.38, [
        (0.0, base_stance),
        (0.12, merge({"Hips": (-12, 0, 0), "Spine": (-22, 0, 0), "Chest": (-24, 15, 0), "Head": (-28, 12, 0), "UpperArm_R": (-15, 0, -45), "UpperArm_L": (-15, 0, 45)})),
        (0.38, base_stance),
    ]))

    # 13. Guard
    guard_pose = merge({"Chest": (10, -15, 0), "UpperArm_R": (-75, 32, -16), "Forearm_R": (-85, 10, 0), "UpperArm_L": (-65, -24, 16), "Forearm_L": (-75, 0, 0), "Thigh_L": (-24, 8, 8), "Shin_L": (34, 0, 0)})
    clips.append(("Guard", 0.5, [(0.0, guard_pose), (0.25, guard_pose), (0.5, guard_pose)]))

    # 14. Dodge
    dodge_pose = merge({"Hips": (16, 25, 14), "Spine": (18, 0, 12), "UpperArm_R": (25, 0, -32), "UpperArm_L": (-35, 0, 35), "Thigh_L": (-48, 0, 14), "Shin_L": (65, 0, 0)})
    clips.append(("Dodge", 0.34, [(0.0, base_stance), (0.14, dodge_pose), (0.34, base_stance)]))

    # 15. SpecialAttack
    clips.append(("SpecialAttack", 0.72, [
        (0.0, merge({"Hips": (18, -45, 0), "Chest": (16, -35, 0), "UpperArm_R": (28, -32, -28), "Forearm_R": (-95, 0, 0), "UpperArm_L": (-55, 25, 25)})),
        (0.28, merge({"Hips": (20, 48, 0), "Chest": (24, 52, 0), "UpperArm_R": (-88, 42, 10), "Forearm_R": (-6, 0, 0), "Thigh_L": (-55, 0, 6), "Shin_L": (65, 0, 0)})),
        (0.72, base_stance),
    ]))

    # 16. UltimatePrep
    ult_prep = merge({"Hips": (14, -22, 0), "Spine": (-6, 0, 0), "Chest": (-10, -18, 0), "Head": (12, 8, 0),
                      "UpperArm_R": (-95, 38, -18), "Forearm_R": (-82, 0, 0), "UpperArm_L": (-120, -10, 28), "Forearm_L": (-45, 0, 0)})
    clips.append(("UltimatePrep", 1.2, [
        (0.0, base_stance),
        (0.45, ult_prep),
        (1.2, merge({"Chest": (-14, -24, 0), "UpperArm_R": (-135, 22, -12), "UpperArm_L": (-140, -12, 20)})),
    ]))

    # 17. UltimateStrike
    clips.append(("UltimateStrike", 1.1, [
        (0.0, merge({"Chest": (-18, -35, 0), "UpperArm_R": (-155, -20, -18), "Forearm_R": (-15, 0, 0)})),
        (0.26, merge({"Hips": (24, 35, 0), "Chest": (28, 45, 0), "UpperArm_R": (-42, 48, 22), "Forearm_R": (-8, 0, 0), "Thigh_L": (-52, 0, 6), "Shin_L": (60, 0, 0)})),
        (0.60, merge({"Hips": (18, 52, 0), "Chest": (22, 58, 0), "UpperArm_R": (-28, 58, 28)})),
        (1.1, base_stance),
    ]))

    # 18. Victory
    clips.append(("Victory", 1.8, [
        (0.0, base_stance),
        (0.5, merge({"Chest": (-8, 20, 0), "Head": (-8, -6, 0), "UpperArm_R": (-138, 12, -24), "Forearm_R": (-16, 0, 0), "UpperArm_L": (-25, 10, 32)})),
        (1.2, merge({"Chest": (-6, -10, 0), "UpperArm_R": (-45, -20, -35), "Forearm_R": (-75, 0, 0)})),
        (1.8, merge({"Chest": (-6, -10, 0), "UpperArm_R": (-45, -20, -35), "Forearm_R": (-75, 0, 0)})),
    ]))

    # 19. Defeat
    defeat_pose = merge({"Hips": (28, 0, 0), "Spine": (24, 0, 0), "Chest": (28, 0, 0), "Head": (32, 0, 0),
                         "Thigh_L": (-75, 0, 12), "Shin_L": (110, 0, 0), "Thigh_R": (-25, 0, -12), "Shin_R": (95, 0, 0),
                         "UpperArm_R": (-20, 0, -25), "Forearm_R": (-40, 0, 0), "UpperArm_L": (10, 0, 28)})
    clips.append(("Defeat", 1.4, [(0.0, base_stance), (0.65, defeat_pose), (1.4, defeat_pose)]))

    return clips


def export_fighter_glb(filename: str, theme: str):
    mb = build_fighter_mesh(theme)
    base_png, emis_png = build_fighter_textures(theme)
    clips = build_fighter_animations()

    packer = BinPacker()

    # Geometry buffers
    pos_bytes = b"".join(struct.pack("<fff", *p) for p in mb.positions)
    nrm_bytes = b"".join(struct.pack("<fff", *n) for n in mb.normals)
    uv_bytes = b"".join(struct.pack("<ff", *u) for u in mb.uvs)
    jnt_bytes = b"".join(struct.pack("<HHHH", *j) for j in mb.joints)
    wgt_bytes = b"".join(struct.pack("<ffff", *w) for w in mb.weights)
    idx_bytes = b"".join(struct.pack("<I", i) for i in mb.indices)

    bv_pos = packer.add_buffer_view(pos_bytes, 34962)
    bv_nrm = packer.add_buffer_view(nrm_bytes, 34962)
    bv_uv = packer.add_buffer_view(uv_bytes, 34962)
    bv_jnt = packer.add_buffer_view(jnt_bytes, 34962)
    bv_wgt = packer.add_buffer_view(wgt_bytes, 34962)
    bv_idx = packer.add_buffer_view(idx_bytes, 34963)

    xs = [p[0] for p in mb.positions]
    ys = [p[1] for p in mb.positions]
    zs = [p[2] for p in mb.positions]

    acc_pos = packer.add_accessor(bv_pos, 5126, len(mb.positions), "VEC3", [min(xs), min(ys), min(zs)], [max(xs), max(ys), max(zs)])
    acc_nrm = packer.add_accessor(bv_nrm, 5126, len(mb.normals), "VEC3")
    acc_uv = packer.add_accessor(bv_uv, 5126, len(mb.uvs), "VEC2")
    acc_jnt = packer.add_accessor(bv_jnt, 5123, len(mb.joints), "VEC4")
    acc_wgt = packer.add_accessor(bv_wgt, 5126, len(mb.weights), "VEC4")
    acc_idx = packer.add_accessor(bv_idx, 5125, len(mb.indices), "SCALAR")

    # Inverse bind matrices
    wpos = compute_world_positions()
    ibm_bytes = bytearray()
    for p in wpos:
        m = mat4_translate(-p[0], -p[1], -p[2])
        ibm_bytes.extend(struct.pack("<16f", *m))
    bv_ibm = packer.add_buffer_view(bytes(ibm_bytes))
    acc_ibm = packer.add_accessor(bv_ibm, 5126, len(BONES), "MAT4")

    # Embedded PNG textures
    bv_base_png = packer.add_buffer_view(base_png)
    bv_emis_png = packer.add_buffer_view(emis_png)

    # Nodes: 0..20 are skeleton joints, 21 is Skinned Mesh node, 22 is Scene Root
    nodes = []
    children_map = {i: [] for i in range(len(BONES))}
    for i, (name, parent, offset) in enumerate(BONES):
        if parent >= 0:
            children_map[parent].append(i)
        nodes.append({"name": name, "translation": list(offset)})
    for i in range(len(BONES)):
        if children_map[i]:
            nodes[i]["children"] = children_map[i]

    mesh_node_idx = len(BONES)
    nodes.append({"name": f"Fighter_{theme}_Mesh", "mesh": 0, "skin": 0})
    root_wrapper_idx = mesh_node_idx + 1
    nodes.append({"name": "CharacterRoot", "children": [0, mesh_node_idx]})

    # Animations
    gltf_anims = []
    for clip_name, dur, keyframes in clips:
        times = [kf[0] for kf in keyframes]
        t_bytes = b"".join(struct.pack("<f", t) for t in times)
        bv_t = packer.add_buffer_view(t_bytes)
        acc_t = packer.add_accessor(bv_t, 5126, len(times), "SCALAR", [min(times)], [max(times)])

        samplers = []
        channels = []
        # Animate all bones that appear in any keyframe of this clip
        animated_bones = set()
        for _, pose in keyframes:
            animated_bones.update(pose.keys())

        for bname in sorted(animated_bones):
            bidx = BONE_IDX[bname]
            quats = []
            for _, pose in keyframes:
                if bname in pose:
                    rx, ry, rz = pose[bname]
                    quats.append(euler_to_quat(math.radians(rx), math.radians(ry), math.radians(rz)))
                else:
                    quats.append([0.0, 0.0, 0.0, 1.0])
            q_bytes = b"".join(struct.pack("<ffff", *q) for q in quats)
            bv_q = packer.add_buffer_view(q_bytes)
            acc_q = packer.add_accessor(bv_q, 5126, len(quats), "VEC4")
            samp_idx = len(samplers)
            samplers.append({"input": acc_t, "interpolation": "LINEAR", "output": acc_q})
            channels.append({"sampler": samp_idx, "target": {"node": bidx, "path": "rotation"}})

        gltf_anims.append({"name": clip_name, "channels": channels, "samplers": samplers})

    gltf = {
        "asset": {"version": "2.0", "generator": "AnimeArenaLegends-AssetGen"},
        "scene": 0,
        "scenes": [{"nodes": [root_wrapper_idx]}],
        "nodes": nodes,
        "skins": [{"inverseBindMatrices": acc_ibm, "joints": list(range(len(BONES))), "skeleton": 0}],
        "meshes": [{
            "name": f"Fighter_{theme}",
            "primitives": [{
                "attributes": {
                    "POSITION": acc_pos,
                    "NORMAL": acc_nrm,
                    "TEXCOORD_0": acc_uv,
                    "JOINTS_0": acc_jnt,
                    "WEIGHTS_0": acc_wgt,
                },
                "indices": acc_idx,
                "material": 0,
            }],
        }],
        "materials": [{
            "name": f"Mat_{theme}",
            "pbrMetallicRoughness": {
                "baseColorTexture": {"index": 0},
                "metallicFactor": 0.25,
                "roughnessFactor": 0.58,
            },
            "emissiveTexture": {"index": 1},
            "emissiveFactor": [1.0, 1.0, 1.0],
            "doubleSided": True,
        }],
        "textures": [{"source": 0, "sampler": 0}, {"source": 1, "sampler": 0}],
        "samplers": [{"magFilter": 9729, "minFilter": 9987, "wrapS": 10497, "wrapT": 10497}],
        "images": [
            {"bufferView": bv_base_png, "mimeType": "image/png"},
            {"bufferView": bv_emis_png, "mimeType": "image/png"},
        ],
        "bufferViews": packer.views,
        "accessors": packer.accessors,
        "animations": gltf_anims,
    }

    write_glb(os.path.join(OUT_DIR, filename), gltf, bytes(packer.buf))


# ---------------------------------------------------------------------------
# Arena Environment GLB Generator (Japanese Fantasy Village)
# ---------------------------------------------------------------------------
def build_arena_texture():
    W, H = 512, 512
    base = bytearray(W * H * 4)
    emis = bytearray(W * H * 4)

    def put(buf, x, y, r, g, b, a=255):
        i = (y * W + x) * 4
        buf[i] = max(0, min(255, int(r)))
        buf[i + 1] = max(0, min(255, int(g)))
        buf[i + 2] = max(0, min(255, int(b)))
        buf[i + 3] = max(0, min(255, int(a)))

    # 4 quadrants:
    # Q1 (u<0.5, v<0.5): Cobblestone courtyard + glowing gold/cyan arena ring accent
    # Q2 (u>=0.5, v<0.5): Dark cedar wood & roof tiles + warm glowing shoji windows
    # Q3 (u<0.5, v>=0.5): Vermilion torii paint + granite stone
    # Q4 (u>=0.5, v>=0.5): Sakura blossom canopy + twilight grass/moss + mountain slate
    for y in range(H):
        v = y / (H - 1)
        for x in range(W):
            u = x / (W - 1)
            er, eg, eb = 0, 0, 0
            if u < 0.5 and v < 0.5:
                lu, lv = u * 2.0, v * 2.0
                # Flagstone grid
                gx = int(lu * 12)
                gy = int(lv * 12)
                fx = (lu * 12) % 1.0
                fy = (lv * 12) % 1.0
                mortar = fx < 0.07 or fy < 0.07
                tone = 0.85 + 0.15 * (((gx * 7 + gy * 13) % 5) / 4.0)
                if mortar:
                    r, g, b = 46, 48, 58
                else:
                    r, g, b = 96 * tone, 102 * tone, 118 * tone
            elif u >= 0.5 and v < 0.5:
                lu, lv = (u - 0.5) * 2.0, v * 2.0
                if lv < 0.35:
                    # Shoji warm lantern window lattice
                    grid = (int(lu * 10) % 2 == 0 and (lu * 10) % 1.0 < 0.14) or ((lv * 10) % 1.0 < 0.14)
                    if grid:
                        r, g, b = 48, 30, 22
                    else:
                        r, g, b = 255, 210, 128
                        er, eg, eb = 220, 155, 65
                elif lv < 0.68:
                    # Timber wood planks
                    grain = 0.88 + 0.12 * math.sin(lu * 45.0)
                    r, g, b = 92 * grain, 58 * grain, 38 * grain
                else:
                    # Pagoda dark indigo-slate roof tiles
                    rib = 0.8 + 0.25 * math.sin(lu * 50.0)
                    r, g, b = 44 * rib, 50 * rib, 76 * rib
            elif u < 0.5 and v >= 0.5:
                lu, lv = u * 2.0, (v - 0.5) * 2.0
                if lv < 0.5:
                    # Vermilion Torii lacquer
                    r, g, b = 198, 42, 34
                else:
                    # Lantern glowing amber core & stone
                    if lu > 0.55:
                        r, g, b = 255, 220, 130
                        er, eg, eb = 255, 190, 90
                    else:
                        r, g, b = 132, 134, 142
            else:
                lu, lv = (u - 0.5) * 2.0, (v - 0.5) * 2.0
                if lv < 0.45:
                    # Sakura pink blossoms
                    dapple = 0.84 + 0.22 * math.sin(lu * 32.0) * math.cos(lv * 32.0)
                    r, g, b = 248 * dapple, 156 * dapple, 188 * dapple
                    er, eg, eb = 32, 12, 22
                elif lv < 0.72:
                    # Mossy earth & grass
                    r, g, b = 46, 78, 56
                else:
                    # Twilight misty mountain slate
                    r, g, b = 52, 56, 86

            put(base, x, y, r, g, b, 255)
            put(emis, x, y, er, eg, eb, 255)

    return encode_png(W, H, base), encode_png(W, H, emis)


def export_arena_glb(filename: str):
    mb = MeshBuilder(skinned=False)

    UV_STONE = (0.02, 0.02, 0.48, 0.48)
    UV_SHOJI = (0.52, 0.02, 0.98, 0.16)
    UV_WOOD = (0.52, 0.19, 0.98, 0.33)
    UV_ROOF = (0.52, 0.36, 0.98, 0.48)
    UV_TORII = (0.02, 0.52, 0.48, 0.73)
    UV_LANTERN_GLOW = (0.28, 0.76, 0.48, 0.98)
    UV_GRANITE = (0.02, 0.76, 0.24, 0.98)
    UV_SAKURA = (0.52, 0.52, 0.98, 0.71)
    UV_GRASS = (0.52, 0.74, 0.98, 0.85)
    UV_MOUNTAIN = (0.52, 0.88, 0.98, 0.98)

    # 1. Outer Village Meadow Ground Disk (radius 70m, y=-0.12)
    segs = 36
    c_meadow = mb.add_vertex((0, -0.12, 0), (0, 1, 0), (0.75, 0.79))
    ring_m = []
    for i in range(segs + 1):
        th = (i / segs) * 2.0 * math.pi
        ring_m.append(mb.add_vertex((math.cos(th) * 68, -0.12, math.sin(th) * 68), (0, 1, 0), (0.55 + 0.4 * (i / segs), 0.79)))
    for i in range(segs):
        mb.add_tri(c_meadow, ring_m[i + 1], ring_m[i])

    # 2. Combat Stone Courtyard Plaza (radius 22.5m, y=0.0, with raised stepped stone rim)
    c_plaza = mb.add_vertex((0, 0.0, 0), (0, 1, 0), (0.25, 0.25))
    ring_p = []
    for i in range(segs + 1):
        th = (i / segs) * 2.0 * math.pi
        x, z = math.cos(th) * 22.5, math.sin(th) * 22.5
        ring_p.append(mb.add_vertex((x, 0.0, z), (0, 1, 0), (0.25 + 0.22 * math.cos(th), 0.25 + 0.22 * math.sin(th))))
    for i in range(segs):
        mb.add_tri(c_plaza, ring_p[i + 1], ring_p[i])

    # Stepped granite arena border curb
    for i in range(24):
        th = (i / 24.0) * 2.0 * math.pi
        bx, bz = math.cos(th) * 22.8, math.sin(th) * 22.8
        mb.add_box_extruded(bx, 0.14, bz, 6.2, 0.35, 0.9, UV_GRANITE, bevel=0.1, rot_y=-th + math.pi * 0.5)

    # 3. Traditional Japanese Dojo / Pagoda Pavilion (North edge z = -25.5)
    # Stone podium
    mb.add_box_extruded(0, 0.4, -26.5, 16.0, 0.8, 9.0, UV_GRANITE, bevel=0.05)
    # Main wooden hall + glowing shoji facade
    mb.add_box_extruded(0, 2.6, -26.5, 13.5, 3.6, 6.8, UV_WOOD)
    mb.add_box_extruded(0, 2.5, -23.02, 10.0, 2.6, 0.2, UV_SHOJI)
    # Swept tiered pagoda roofs
    mb.add_box_extruded(0, 4.9, -26.5, 17.2, 1.3, 9.6, UV_ROOF, bevel=0.28)
    mb.add_box_extruded(0, 6.6, -26.5, 9.5, 2.2, 5.2, UV_WOOD)
    mb.add_box_extruded(0, 6.5, -23.85, 6.4, 1.6, 0.18, UV_SHOJI)
    mb.add_box_extruded(0, 8.2, -26.5, 12.6, 1.4, 7.2, UV_ROOF, bevel=0.45)

    # 4. Flanking Village Machiya Houses (East x=27.5 & West x=-27.5)
    for sx, ry in [(27.5, -math.pi * 0.5), (-27.5, math.pi * 0.5)]:
        mb.add_box_extruded(sx, 0.3, -4.0, 8.5, 0.6, 12.0, UV_GRANITE)
        mb.add_box_extruded(sx, 2.2, -4.0, 7.2, 3.2, 10.5, UV_WOOD, rot_y=ry)
        mb.add_box_extruded(sx + (-3.65 if sx > 0 else 3.65), 2.1, -4.0, 0.2, 2.0, 6.5, UV_SHOJI)
        mb.add_box_extruded(sx, 4.4, -4.0, 9.6, 1.4, 12.6, UV_ROOF, bevel=0.35, rot_y=ry)

    # 5. Vermilion Torii Gates (South z=25.5 & North-West)
    for tx, tz, ry in [(0.0, 25.2, 0.0), (-18.5, -18.5, math.pi * 0.25)]:
        c, s = math.cos(ry), math.sin(ry)
        for pside in (-2.4, 2.4):
            px = tx + pside * c
            pz = tz + pside * s
            mb.add_box_extruded(px, 0.35, pz, 0.72, 0.7, 0.72, UV_GRANITE, rot_y=ry)
            mb.add_box_extruded(px, 3.1, pz, 0.46, 5.4, 0.46, UV_TORII, rot_y=ry)
        mb.add_box_extruded(tx, 4.7, tz, 6.2, 0.34, 0.34, UV_TORII, rot_y=ry)
        mb.add_box_extruded(tx, 5.75, tz, 7.6, 0.52, 0.62, UV_ROOF, bevel=0.18, rot_y=ry)
        mb.add_box_extruded(tx, 5.45, tz, 7.0, 0.32, 0.48, UV_TORII, rot_y=ry)

    # 6. Four Stone Toro Lanterns around the plaza perimeter (matching physics collider coords)
    lantern_coords = [(-13.5, -13.5), (13.5, -13.5), (-13.5, 13.5), (13.5, 13.5)]
    for lx, lz in lantern_coords:
        mb.add_box_extruded(lx, 0.35, lz, 1.1, 0.7, 1.1, UV_GRANITE, bevel=0.12)
        mb.add_box_extruded(lx, 1.35, lz, 0.45, 1.4, 0.45, UV_GRANITE)
        mb.add_box_extruded(lx, 2.3, lz, 0.66, 0.62, 0.66, UV_LANTERN_GLOW)
        mb.add_box_extruded(lx, 2.85, lz, 1.25, 0.5, 1.25, UV_GRANITE, bevel=0.55)

    # 7. Four Gnarled Sakura Trees with layered blossom canopies
    tree_coords = [(-18.5, 11.0), (18.5, 11.0), (-19.0, -10.0), (19.0, -10.0)]
    for tx, tz in tree_coords:
        mb.add_loft([
            ((tx, 0.0, tz), 0.52, 0.52, (0, 0, 0, 0), (1, 0, 0, 0)),
            ((tx + 0.15, 1.8, tz - 0.1), 0.38, 0.38, (0, 0, 0, 0), (1, 0, 0, 0)),
            ((tx - 0.1, 3.6, tz + 0.1), 0.28, 0.28, (0, 0, 0, 0), (1, 0, 0, 0)),
        ], uv_rect=UV_WOOD, closed_caps=False)
        for ox, oy, oz, rad in [(0, 4.6, 0, 3.0), (-1.4, 4.1, 1.0, 2.1), (1.5, 4.2, -0.8, 2.2), (0.3, 5.5, 0.2, 2.0)]:
            mb.add_loft([
                ((tx + ox, oy - 0.9, tz + oz), rad * 0.45, rad * 0.45, (0, 0, 0, 0), (1, 0, 0, 0)),
                ((tx + ox, oy, tz + oz), rad, rad, (0, 0, 0, 0), (1, 0, 0, 0)),
                ((tx + ox, oy + 1.0, tz + oz), rad * 0.72, rad * 0.72, (0, 0, 0, 0), (1, 0, 0, 0)),
                ((tx + ox, oy + 1.65, tz + oz), rad * 0.2, rad * 0.2, (0, 0, 0, 0), (1, 0, 0, 0)),
            ], uv_rect=UV_SAKURA)

    # 8. Encircling Twilight Mountain Silhouettes (radius 58m)
    m_peaks = 18
    for i in range(m_peaks):
        th = (i / m_peaks) * 2.0 * math.pi
        mx, mz = math.cos(th) * 58.0, math.sin(th) * 58.0
        mh = 12.0 + (i % 3) * 5.5
        mb.add_box_extruded(mx, mh * 0.45, mz, 22.0, mh, 14.0, UV_MOUNTAIN, bevel=0.82, rot_y=-th)

    base_png, emis_png = build_arena_texture()
    packer = BinPacker()

    pos_bytes = b"".join(struct.pack("<fff", *p) for p in mb.positions)
    nrm_bytes = b"".join(struct.pack("<fff", *n) for n in mb.normals)
    uv_bytes = b"".join(struct.pack("<ff", *u) for u in mb.uvs)
    idx_bytes = b"".join(struct.pack("<I", i) for i in mb.indices)

    bv_pos = packer.add_buffer_view(pos_bytes, 34962)
    bv_nrm = packer.add_buffer_view(nrm_bytes, 34962)
    bv_uv = packer.add_buffer_view(uv_bytes, 34962)
    bv_idx = packer.add_buffer_view(idx_bytes, 34963)

    xs = [p[0] for p in mb.positions]
    ys = [p[1] for p in mb.positions]
    zs = [p[2] for p in mb.positions]

    acc_pos = packer.add_accessor(bv_pos, 5126, len(mb.positions), "VEC3", [min(xs), min(ys), min(zs)], [max(xs), max(ys), max(zs)])
    acc_nrm = packer.add_accessor(bv_nrm, 5126, len(mb.normals), "VEC3")
    acc_uv = packer.add_accessor(bv_uv, 5126, len(mb.uvs), "VEC2")
    acc_idx = packer.add_accessor(bv_idx, 5125, len(mb.indices), "SCALAR")

    bv_base_png = packer.add_buffer_view(base_png)
    bv_emis_png = packer.add_buffer_view(emis_png)

    gltf = {
        "asset": {"version": "2.0", "generator": "AnimeArenaLegends-ArenaGen"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"name": "VillageArena", "mesh": 0}],
        "meshes": [{
            "name": "VillageArenaMesh",
            "primitives": [{
                "attributes": {"POSITION": acc_pos, "NORMAL": acc_nrm, "TEXCOORD_0": acc_uv},
                "indices": acc_idx,
                "material": 0,
            }],
        }],
        "materials": [{
            "name": "Mat_VillageArena",
            "pbrMetallicRoughness": {
                "baseColorTexture": {"index": 0},
                "metallicFactor": 0.08,
                "roughnessFactor": 0.82,
            },
            "emissiveTexture": {"index": 1},
            "emissiveFactor": [1.0, 1.0, 1.0],
            "doubleSided": True,
        }],
        "textures": [{"source": 0, "sampler": 0}, {"source": 1, "sampler": 0}],
        "samplers": [{"magFilter": 9729, "minFilter": 9987, "wrapS": 10497, "wrapT": 10497}],
        "images": [
            {"bufferView": bv_base_png, "mimeType": "image/png"},
            {"bufferView": bv_emis_png, "mimeType": "image/png"},
        ],
        "bufferViews": packer.views,
        "accessors": packer.accessors,
    }

    write_glb(os.path.join(OUT_DIR, filename), gltf, bytes(packer.buf))


# ---------------------------------------------------------------------------
# Lightweight 3D VFX Mesh GLBs (Slash Crescent, Shockwave Ring, Energy Shard)
# ---------------------------------------------------------------------------
def export_vfx_glb(filename: str, builder_fn, color_rgba, emissive_rgb):
    mb = builder_fn()
    packer = BinPacker()
    pos_bytes = b"".join(struct.pack("<fff", *p) for p in mb.positions)
    nrm_bytes = b"".join(struct.pack("<fff", *n) for n in mb.normals)
    uv_bytes = b"".join(struct.pack("<ff", *u) for u in mb.uvs)
    idx_bytes = b"".join(struct.pack("<I", i) for i in mb.indices)

    bv_pos = packer.add_buffer_view(pos_bytes, 34962)
    bv_nrm = packer.add_buffer_view(nrm_bytes, 34962)
    bv_uv = packer.add_buffer_view(uv_bytes, 34962)
    bv_idx = packer.add_buffer_view(idx_bytes, 34963)

    xs = [p[0] for p in mb.positions]
    ys = [p[1] for p in mb.positions]
    zs = [p[2] for p in mb.positions]

    acc_pos = packer.add_accessor(bv_pos, 5126, len(mb.positions), "VEC3", [min(xs), min(ys), min(zs)], [max(xs), max(ys), max(zs)])
    acc_nrm = packer.add_accessor(bv_nrm, 5126, len(mb.normals), "VEC3")
    acc_uv = packer.add_accessor(bv_uv, 5126, len(mb.uvs), "VEC2")
    acc_idx = packer.add_accessor(bv_idx, 5125, len(mb.indices), "SCALAR")

    gltf = {
        "asset": {"version": "2.0", "generator": "AnimeArenaLegends-VfxGen"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"name": "VfxRoot", "mesh": 0}],
        "meshes": [{
            "name": "VfxMesh",
            "primitives": [{
                "attributes": {"POSITION": acc_pos, "NORMAL": acc_nrm, "TEXCOORD_0": acc_uv},
                "indices": acc_idx,
                "material": 0,
            }],
        }],
        "materials": [{
            "name": "VfxMat",
            "pbrMetallicRoughness": {
                "baseColorFactor": color_rgba,
                "metallicFactor": 0.0,
                "roughnessFactor": 0.4,
            },
            "emissiveFactor": emissive_rgb,
            "alphaMode": "BLEND",
            "doubleSided": True,
        }],
        "bufferViews": packer.views,
        "accessors": packer.accessors,
    }
    write_glb(os.path.join(OUT_DIR, filename), gltf, bytes(packer.buf))


def make_slash_crescent():
    mb = MeshBuilder(skinned=False)
    pts = []
    widths = []
    thicks = []
    for i in range(13):
        t = i / 12.0
        ang = (t - 0.5) * math.pi * 1.15
        pts.append((math.sin(ang) * 1.25, 0.0, math.cos(ang) * 1.25))
        envelope = math.sin(t * math.pi)
        widths.append(0.32 * envelope + 0.02)
        thicks.append(0.03 * envelope + 0.005)
    mb.add_sculpted_ribbon(pts, widths, thicks, (0, 0, 1, 1), (0, 0, 0, 0), (1, 0, 0, 0))
    return mb


def make_shockwave_ring():
    mb = MeshBuilder(skinned=False)
    segs = 24
    rings = []
    for r_idx, (rad, h) in enumerate([(0.65, 0.04), (1.0, 0.14), (1.18, 0.02)]):
        row = []
        for i in range(segs + 1):
            th = (i / segs) * 2.0 * math.pi
            row.append(mb.add_vertex((math.cos(th) * rad, h, math.sin(th) * rad), (0, 1, 0), (i / segs, r_idx / 2.0)))
        rings.append(row)
    for r in range(2):
        for i in range(segs):
            mb.add_quad(rings[r][i], rings[r + 1][i], rings[r + 1][i + 1], rings[r][i + 1])
    return mb


def make_energy_shard():
    mb = MeshBuilder(skinned=False)
    pts = [(0, -0.45, 0), (0.08, -0.1, 0.04), (-0.06, 0.25, -0.04), (0, 0.65, 0)]
    mb.add_sculpted_ribbon(pts, [0.02, 0.11, 0.08, 0.01], [0.02, 0.08, 0.06, 0.01], (0, 0, 1, 1), (0, 0, 0, 0), (1, 0, 0, 0))
    return mb


if __name__ == "__main__":
    export_fighter_glb("fighter_raiden.glb", "raiden")
    export_fighter_glb("fighter_homura.glb", "homura")
    export_arena_glb("arena_village.glb")
    export_vfx_glb("vfx_slash_cyan.glb", make_slash_crescent, [0.25, 0.92, 1.0, 0.85], [0.15, 0.85, 1.0])
    export_vfx_glb("vfx_slash_fire.glb", make_slash_crescent, [1.0, 0.45, 0.12, 0.85], [1.0, 0.35, 0.08])
    export_vfx_glb("vfx_ring_cyan.glb", make_shockwave_ring, [0.3, 0.95, 1.0, 0.75], [0.20, 0.80, 1.0])
    export_vfx_glb("vfx_ring_fire.glb", make_shockwave_ring, [1.0, 0.5, 0.15, 0.75], [1.0, 0.38, 0.10])
    export_vfx_glb("vfx_shard_cyan.glb", make_energy_shard, [0.4, 0.95, 1.0, 0.9], [0.22, 0.85, 1.0])
    export_vfx_glb("vfx_shard_fire.glb", make_energy_shard, [1.0, 0.55, 0.15, 0.9], [1.0, 0.42, 0.10])
