// EARWAX 4.8 — High-Precision C++17 Multi-Threaded 3D Analytical SDF Raymarcher
// Renders Ultra-HD Y2K Floating Popups & Twin 3D Wing Drawers (whale, orb, pill, drawer_l, drawer_r)
// with 2x SSAA, 96-step raymarching, 4-tap multi-scale AO, and 10-step soft shadows.
//
// Compile: g++ -O3 -ffast-math -std=c++17 -pthread tools/popup_render.cpp -o tools/popup_render
// Usage:   ./tools/popup_render <mode> <width> <height> <scale_px_per_world> <out_rgba_raw_path> [threads]

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <thread>
#include <vector>

struct Vec3 {
    float x, y, z;
    inline Vec3 operator+(const Vec3& o) const { return {x + o.x, y + o.y, z + o.z}; }
    inline Vec3 operator-(const Vec3& o) const { return {x - o.x, y - o.y, z - o.z}; }
    inline Vec3 operator*(float s) const { return {x * s, y * s, z * s}; }
    inline Vec3 operator*(const Vec3& o) const { return {x * o.x, y * o.y, z * o.z}; }
};

inline Vec3 operator*(float s, const Vec3& v) { return {v.x * s, v.y * s, v.z * s}; }
inline float dot(const Vec3& a, const Vec3& b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
inline float length(const Vec3& v) { return std::sqrt(dot(v, v)); }
inline Vec3 normalize(const Vec3& v) { return v * (1.0f / (length(v) + 1e-8f)); }
inline float clampf(float x, float lo, float hi) { return x < lo ? lo : (x > hi ? hi : x); }
inline float sstep(float a, float b, float x) {
    float t = clampf((x - a) / (b - a), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}
inline float smin(float a, float b, float k) {
    float h = clampf(0.5f + 0.5f * (b - a) / k, 0.0f, 1.0f);
    return b * (1.0f - h) + a * h - k * h * (1.0f - h);
}
inline float smax(float a, float b, float k) { return -smin(-a, -b, k); }

// ------------------------------------------------------------------ SDF Primitives
inline float sd_ellipsoid(float x, float y, float z,
                          float cx, float cy, float cz,
                          float rx, float ry, float rz) {
    float qx = (x - cx) / rx, qy = (y - cy) / ry, qz = (z - cz) / rz;
    float k0 = std::sqrt(qx * qx + qy * qy + qz * qz);
    float k1 = std::sqrt((qx / rx) * (qx / rx) + (qy / ry) * (qy / ry) + (qz / rz) * (qz / rz)) + 1e-8f;
    return k0 * (k0 - 1.0f) / k1;
}

inline float sd_capsule(float x, float y, float z,
                        float ax, float ay, float az,
                        float bx, float by, float bz, float r) {
    float px = x - ax, py = y - ay, pz = z - az;
    float dx = bx - ax, dy = by - ay, dz = bz - az;
    float h = clampf((px * dx + py * dy + pz * dz) / (dx * dx + dy * dy + dz * dz + 1e-12f), 0.0f, 1.0f);
    float ex = px - dx * h, ey = py - dy * h, ez = pz - dz * h;
    return std::sqrt(ex * ex + ey * ey + ez * ez) - r;
}

inline float sd_extruded_rrect(float x, float y, float z,
                               float cx, float cy, float cz,
                               float hx, float hy, float hz, float r, float rz) {
    float qx = std::fabs(x - cx) - hx + r;
    float qy = std::fabs(y - cy) - hy + r;
    float ox = qx > 0.0f ? qx : 0.0f;
    float oy = qy > 0.0f ? qy : 0.0f;
    float d2 = std::sqrt(ox * ox + oy * oy) + std::min(std::max(qx, qy), 0.0f) - r;
    float wz = std::fabs(z - cz) - hz + rz;
    float w1 = d2 + rz;
    float ow1 = w1 > 0.0f ? w1 : 0.0f;
    float owz = wz > 0.0f ? wz : 0.0f;
    return std::sqrt(ow1 * ow1 + owz * owz) + std::min(std::max(w1, wz), 0.0f) - rz;
}

inline float sd_cylinder_z(float x, float y, float z,
                           float cx, float cy, float cz, float r, float hz) {
    float dx = std::sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) - r;
    float dz = std::fabs(z - cz) - hz;
    float odx = dx > 0.0f ? dx : 0.0f;
    float odz = dz > 0.0f ? dz : 0.0f;
    return std::min(std::max(dx, dz), 0.0f) + std::sqrt(odx * odx + odz * odz);
}

inline float sd_torus_z(float x, float y, float z,
                        float cx, float cy, float cz, float major_r, float minor_r) {
    float q = std::sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) - major_r;
    float dz = z - cz;
    return std::sqrt(q * q + dz * dz) - minor_r;
}

inline float sd_2d_capsule(float x, float y, float ax, float ay, float bx, float by, float r) {
    float px = x - ax, py = y - ay;
    float dx = bx - ax, dy = by - ay;
    float h = clampf((px * dx + py * dy) / (dx * dx + dy * dy + 1e-12f), 0.0f, 1.0f);
    float ex = px - dx * h, ey = py - dy * h;
    return std::sqrt(ex * ex + ey * ey) - r;
}

inline float extrude_2d(float d2, float z, float cz, float hz, float rz) {
    float wz = std::fabs(z - cz) - hz + rz;
    float w1 = d2 + rz;
    float ow1 = w1 > 0.0f ? w1 : 0.0f;
    float owz = wz > 0.0f ? wz : 0.0f;
    return std::sqrt(ow1 * ow1 + owz * owz) + std::min(std::max(w1, wz), 0.0f) - rz;
}

struct Hit {
    float d;
    int8_t mat;
};

// ------------------------------------------------------------------ 1. WHALE (Cyber Deck)
struct BtnSpec { float x, y, r; int8_t mid; bool is_chip; };

static const BtnSpec WHALE_DOCK[9] = {
    {-1.15f, -0.58f, 0.088f, 3, false},
    {-0.91f, -0.58f, 0.106f, 3, false},
    {-0.67f, -0.58f, 0.088f, 3, false},
    {-0.31f, -0.58f, 0.082f, 6, true},
    { 0.03f, -0.58f, 0.082f, 6, true},
    { 0.37f, -0.58f, 0.082f, 6, true},
    { 0.69f, -0.58f, 0.080f, 3, false},
    { 0.92f, -0.58f, 0.080f, 3, false},
    { 1.15f, -0.58f, 0.080f, 7, false},
};

inline float whale_body_2d(float x, float y) {
    float d_main  = sd_2d_capsule(x, y, -1.06f,  0.07f, 1.00f,  0.10f, 0.62f);
    float d_wing  = sd_2d_capsule(x, y,  0.90f,  0.24f, 1.46f,  0.48f, 0.22f);
    float d_dock  = sd_2d_capsule(x, y, -1.08f, -0.55f, 1.08f, -0.55f, 0.30f);
    float d_belly = sd_2d_capsule(x, y, -0.78f, -0.24f, 0.85f, -0.28f, 0.48f);
    float d_crest = sd_2d_capsule(x, y, -0.28f,  0.69f, 0.28f,  0.69f, 0.20f);
    float d = smin(d_main, d_dock, 0.22f);
    d = smin(d, d_belly, 0.18f);
    d = smin(d, d_wing, 0.22f);
    d = smin(d, d_crest, 0.16f);
    return d;
}

inline Hit scene_whale(float x, float y, float z) {
    constexpr float LCD_CX = -0.58f, LCD_CY = 0.08f, LCD_HX = 0.90f, LCD_HY = 0.36f;
    constexpr float KNOB_CX = 0.78f, KNOB_CY = 0.08f, KNOB_R = 0.255f;
    constexpr float MET_CX = 1.30f, MET_CY = 0.08f, MET_HX = 0.055f, MET_HY = 0.34f;
    constexpr float DOCK_CX = 0.00f, DOCK_CY = -0.58f, DOCK_HX = 1.32f, DOCK_HY = 0.155f;

    float d2 = whale_body_2d(x, y);
    float dome = 0.065f * std::exp(-(x * x * 0.28f + y * y * 0.78f));
    float d = extrude_2d(d2, z - dome, 0.0f, 0.22f, 0.125f);

    float plate_cut = sd_extruded_rrect(x, y, z, -0.12f, 0.08f, 0.255f, 1.46f, 0.44f, 0.08f, 0.36f, 0.03f);
    d = smax(d, -plate_cut, 0.018f);

    float lcd_cut = sd_extruded_rrect(x, y, z, LCD_CX, LCD_CY, 0.24f, LCD_HX + 0.015f, LCD_HY + 0.015f, 0.12f, 0.22f, 0.035f);
    d = smax(d, -lcd_cut, 0.022f);

    float knob_well = sd_cylinder_z(x, y, z, KNOB_CX, KNOB_CY, 0.24f, KNOB_R + 0.068f, 0.11f);
    d = smax(d, -knob_well, 0.020f);

    float met_cut = sd_extruded_rrect(x, y, z, MET_CX, MET_CY, 0.24f, MET_HX + 0.014f, MET_HY + 0.014f, 0.10f, 0.055f, 0.020f);
    d = smax(d, -met_cut, 0.012f);

    float dock_cut = sd_extruded_rrect(x, y, z, DOCK_CX, DOCK_CY, 0.24f, DOCK_HX + 0.04f, DOCK_HY + 0.02f, 0.095f, 0.145f, 0.032f);
    d = smax(d, -dock_cut, 0.018f);

    float dr_well = sd_extruded_rrect(x, y, z, 0.00f, 0.74f, 0.235f, 0.27f, 0.105f, 0.08f, 0.095f, 0.020f);
    d = smax(d, -dr_well, 0.014f);

    float c_well = sd_cylinder_z(x, y, z, 1.48f, 0.51f, 0.23f, 0.084f + 0.024f, 0.08f);
    d = smax(d, -c_well, 0.014f);

    float best = d;
    int8_t mat = 0;
    auto put = [&](float dd, int8_t mid) { if (dd < best) { best = dd; mat = mid; } };

    float plate = sd_extruded_rrect(x, y, z, -0.12f, 0.08f, 0.172f, 1.44f, 0.42f, 0.022f, 0.34f, 0.016f);
    plate = smax(plate, -lcd_cut, 0.015f);
    plate = smax(plate, -knob_well, 0.015f);
    plate = smax(plate, -met_cut, 0.010f);
    put(plate, 2);

    float dx_l = (x - LCD_CX) / LCD_HX, dy_l = (y - LCD_CY) / LCD_HY;
    float glass_bulge = 0.018f * (1.0f - clampf(dx_l * dx_l + dy_l * dy_l, 0.0f, 1.0f));
    put(sd_extruded_rrect(x, y, z - glass_bulge, LCD_CX, LCD_CY, 0.125f, LCD_HX - 0.008f, LCD_HY - 0.008f, 0.024f, 0.20f, 0.015f), 1);

    put(sd_torus_z(x, y, z, KNOB_CX, KNOB_CY, 0.170f, KNOB_R + 0.036f, 0.016f), 5);
    float krho = std::sqrt((x - KNOB_CX) * (x - KNOB_CX) + (y - KNOB_CY) * (y - KNOB_CY));
    float krn = krho / (KNOB_R * 0.85f);
    float dish = -0.016f * (1.0f - clampf(krn * krn, 0.0f, 1.0f));
    float knob_cap = sd_cylinder_z(x, y, z - dish, KNOB_CX, KNOB_CY, 0.212f, KNOB_R, 0.068f);
    float knob_bevel = sd_torus_z(x, y, z, KNOB_CX, KNOB_CY, 0.262f, KNOB_R - 0.022f, 0.022f);
    put(smin(knob_cap, knob_bevel, 0.016f), 2);
    put(sd_torus_z(x, y, z, KNOB_CX, KNOB_CY, 0.268f, KNOB_R - 0.026f, 0.011f), 0);

    put(sd_extruded_rrect(x, y, z, MET_CX, MET_CY, 0.145f, MET_HX - 0.004f, MET_HY - 0.004f, 0.020f, 0.046f, 0.012f), 1);
    put(sd_extruded_rrect(x, y, z, DOCK_CX, DOCK_CY, 0.135f, DOCK_HX + 0.01f, DOCK_HY - 0.005f, 0.020f, 0.13f, 0.012f), 1);

    if (y < -0.25f) {
        for (int i = 0; i < 9; ++i) {
            const auto& b = WHALE_DOCK[i];
            if (b.is_chip) {
                put(sd_extruded_rrect(x, y, z, b.x, b.y, 0.172f, b.r * 1.48f, b.r * 0.72f, 0.036f, b.r * 0.68f, 0.024f), 6);
            } else {
                put(sd_torus_z(x, y, z, b.x, b.y, 0.158f, b.r + 0.010f, 0.010f), 0);
                put(sd_ellipsoid(x, y, z, b.x, b.y, 0.165f, b.r, b.r, b.r * 0.62f), b.mid);
            }
        }
    }

    if (y > 0.45f && std::fabs(x) < 0.45f) {
        float dr_pill = sd_extruded_rrect(x, y, z, 0.00f, 0.74f, 0.190f, 0.245f, 0.082f, 0.048f, 0.078f, 0.034f);
        put(dr_pill, 3);
        float dr_ring = sd_extruded_rrect(x, y, z, 0.00f, 0.74f, 0.176f, 0.262f, 0.095f, 0.012f, 0.090f, 0.008f);
        put(std::max(dr_ring, -(dr_pill - 0.004f)), 5);
    }

    if (x > 1.20f && y > 0.25f) {
        put(sd_torus_z(x, y, z, 1.48f, 0.51f, 0.190f, 0.084f + 0.014f, 0.012f), 0);
        put(sd_ellipsoid(x, y, z, 1.48f, 0.51f, 0.195f, 0.084f, 0.084f, 0.084f * 0.65f), 4);
    }

    float d_rim = std::sqrt((d2 + 0.058f) * (d2 + 0.058f) + (z - 0.215f) * (z - 0.215f)) - 0.011f;
    float pipe_l = smax(d_rim, smax(std::fabs(y - 0.10f) - 0.28f, x + 1.44f, 0.03f), 0.018f);
    float pipe_r = smax(d_rim, smax(std::fabs(y + 0.04f) - 0.24f, 1.42f - x, 0.03f), 0.018f);
    put(std::min(pipe_l, pipe_r), 5);

    return {best, mat};
}

// ------------------------------------------------------------------ 2. ORB (Sonic Orb)
struct OrbBtn { float x, y, r; int8_t mid; bool is_drawer; };
static const OrbBtn ORB_BTNS[7] = {
    {-0.31f, -0.585f, 0.095f, 3, false},
    { 0.00f, -0.640f, 0.122f, 3, false},
    { 0.31f, -0.585f, 0.095f, 3, false},
    {-0.61f, -0.145f, 0.088f, 3, false},
    { 0.61f, -0.145f, 0.088f, 7, false},
    { 0.00f,  0.635f, 0.088f, 3, true},
    { 0.49f,  0.430f, 0.074f, 4, false},
};

inline Hit scene_orb(float x, float y, float z) {
    constexpr float LCD_R = 0.455f, DIAL_R0 = 0.785f, DIAL_R1 = 0.905f;
    constexpr float DEG2RAD = 3.14159265358979323846f / 180.0f;
    float rho = std::sqrt(x * x + y * y);
    float ang = std::atan2(x, y) * (180.0f / 3.14159265358979323846f);

    float rn = clampf(rho / 1.04f, 0.0f, 1.0f);
    float dome = 0.15f * std::sqrt(std::max(0.0f, 1.0f - rn * rn));
    float d = extrude_2d(rho - 1.00f, z - dome, 0.0f, 0.19f, 0.145f);

    float step_ring = std::max(std::fabs(rho - 0.725f) - 0.012f, 0.255f - z);
    d = smax(d, -step_ring, 0.008f);

    float mid_r = 0.5f * (DIAL_R0 + DIAL_R1);
    float half_w = 0.5f * (DIAL_R1 - DIAL_R0);
    float ang_rad = clampf(ang, -132.0f, 132.0f) * DEG2RAD;
    float arc_x = std::sin(ang_rad) * mid_r;
    float arc_y = std::cos(ang_rad) * mid_r;
    float d_arc2d = std::sqrt((x - arc_x) * (x - arc_x) + (y - arc_y) * (y - arc_y)) - half_w;
    float track_cut = std::max(d_arc2d, (0.205f + dome * 0.55f) - z);
    d = smax(d, -track_cut, 0.015f);

    float lcd_cut = std::max(rho - (LCD_R + 0.024f), 0.23f - z);
    d = smax(d, -lcd_cut, 0.020f);

    for (int i = 0; i < 7; ++i) {
        const auto& b = ORB_BTNS[i];
        float brho = std::hypot(b.x, b.y) / 1.04f;
        float b_dome = 0.15f * std::sqrt(std::max(0.0f, 1.0f - brho * brho));
        float well = sd_cylinder_z(x, y, z, b.x, b.y, 0.20f + b_dome, b.r + 0.022f, 0.095f);
        d = smax(d, -well, 0.014f);
    }

    float best = d;
    int8_t mat = 0;
    auto put = [&](float dd, int8_t mid) { if (dd < best) { best = dd; mat = mid; } };

    float mid_face = std::max(std::fabs(rho - 0.60f) - 0.115f, std::fabs(z - (0.18f + dome * 0.92f)) - 0.015f);
    mid_face = std::max(mid_face, d - 0.002f);
    put(mid_face, 2);

    float track_fl = std::max(d_arc2d + 0.006f, std::fabs(z - (0.202f + dome * 0.55f)) - 0.012f);
    put(track_fl, 6);

    put(sd_torus_z(x, y, z, 0.0f, 0.0f, 0.302f, LCD_R + 0.014f, 0.011f), 5);

    float rlcd = rho / LCD_R;
    float glass_bulge = 0.036f * (1.0f - clampf(rlcd * rlcd, 0.0f, 1.0f));
    put(sd_cylinder_z(x, y, z - glass_bulge, 0.0f, 0.0f, 0.258f, LCD_R - 0.004f, 0.022f), 1);

    for (int i = 0; i < 7; ++i) {
        const auto& b = ORB_BTNS[i];
        float brho = std::hypot(b.x, b.y) / 1.04f;
        float zb = 0.175f + 0.15f * std::sqrt(std::max(0.0f, 1.0f - brho * brho));
        put(sd_torus_z(x, y, z, b.x, b.y, zb + 0.012f, b.r + 0.010f, 0.010f), b.is_drawer ? 5 : 0);
        put(sd_ellipsoid(x, y, z, b.x, b.y, zb + 0.016f, b.r, b.r, b.r * 0.64f), b.mid);
    }
    return {best, mat};
}

// ------------------------------------------------------------------ 3. PILL (Mini-Pill)
static const BtnSpec PILL_BTNS[5] = {
    {0.64f, -0.060f, 0.108f, 3, false},
    {0.93f, -0.060f, 0.134f, 3, false},
    {1.22f, -0.060f, 0.108f, 3, false},
    {1.47f,  0.125f, 0.072f, 7, false},
    {1.47f, -0.245f, 0.072f, 4, false},
};

inline Hit scene_pill(float x, float y, float z) {
    constexpr float LCD_CX = -0.58f, LCD_CY = -0.06f, LCD_HX = 0.99f, LCD_HY = 0.30f;
    float d_pill = sd_2d_capsule(x, y, -1.25f, -0.06f, 1.25f, -0.06f, 0.48f);
    float d_tab  = sd_2d_capsule(x, y, -0.24f,  0.42f, 0.24f,  0.42f, 0.17f);
    float d2 = smin(d_pill, d_tab, 0.14f);
    float dome = 0.055f * std::exp(-(x * x * 0.18f + y * y * 2.0f));
    float d = extrude_2d(d2, z - dome, 0.0f, 0.21f, 0.13f);

    float bay_cut = sd_extruded_rrect(x, y, z, 0.0f, -0.06f, 0.24f, 1.62f, 0.34f, 0.09f, 0.32f, 0.030f);
    d = smax(d, -bay_cut, 0.018f);

    float lcd_cut = sd_extruded_rrect(x, y, z, LCD_CX, LCD_CY, 0.21f, LCD_HX + 0.012f, LCD_HY + 0.012f, 0.10f, 0.28f, 0.025f);
    d = smax(d, -lcd_cut, 0.015f);

    float dr_well = sd_extruded_rrect(x, y, z, 0.00f, 0.46f, 0.23f, 0.24f, 0.095f, 0.08f, 0.085f, 0.018f);
    d = smax(d, -dr_well, 0.014f);

    float best = d;
    int8_t mat = 0;
    auto put = [&](float dd, int8_t mid) { if (dd < best) { best = dd; mat = mid; } };

    float ctrl_plate = sd_extruded_rrect(x, y, z, 0.0f, -0.06f, 0.145f, 1.59f, 0.32f, 0.024f, 0.30f, 0.015f);
    ctrl_plate = smax(ctrl_plate, -lcd_cut, 0.012f);
    put(ctrl_plate, 2);

    put(sd_extruded_rrect(x, y, z, LCD_CX, LCD_CY, 0.125f, LCD_HX - 0.005f, LCD_HY - 0.005f, 0.024f, 0.26f, 0.014f), 1);
    put(sd_capsule(x, y, z, -1.15f, -0.465f, 0.205f, 1.15f, -0.465f, 0.205f, 0.015f), 5);

    if (y > 0.22f && std::fabs(x) < 0.40f) {
        put(sd_extruded_rrect(x, y, z, 0.00f, 0.46f, 0.185f, 0.215f, 0.075f, 0.044f, 0.072f, 0.030f), 3);
    }

    if (x > 0.35f) {
        for (int i = 0; i < 5; ++i) {
            const auto& b = PILL_BTNS[i];
            put(sd_torus_z(x, y, z, b.x, b.y, 0.172f, b.r + 0.012f, 0.012f), 0);
            put(sd_ellipsoid(x, y, z, b.x, b.y, 0.178f, b.r, b.r, b.r * 0.65f), b.mid);
        }
    }
    return {best, mat};
}

// ------------------------------------------------------------------ 4. DRAWER_L & DRAWER_R
inline Hit scene_drawer(float x, float y, float z, float s_dir) {
    constexpr float SCR_HX = 0.78f, SCR_HY = 1.05f;
    float z_tilt = z - s_dir * x * 0.085f;
    float hx_taper = 0.94f - 0.04f * (y * y * 0.32f);
    float qx = std::fabs(x) - hx_taper + 0.24f;
    float qy = std::fabs(y) - 1.22f + 0.24f;
    float ox = qx > 0.0f ? qx : 0.0f;
    float oy = qy > 0.0f ? qy : 0.0f;
    float d2 = std::sqrt(ox * ox + oy * oy) + std::min(std::max(qx, qy), 0.0f) - 0.24f;
    float dome = 0.045f * std::exp(-(x * x * 0.65f + y * y * 0.35f));
    float d = extrude_2d(d2, z_tilt - dome, 0.0f, 0.165f, 0.095f);

    float grip = sd_capsule(x, y, z_tilt, s_dir * 0.94f, 0.42f, 0.06f, s_dir * 0.94f, -0.42f, 0.06f, 0.065f);
    d = smin(d, grip, 0.055f);

    float k1 = sd_capsule(x, y, z_tilt, -s_dir * 0.88f,  0.86f, -0.02f, -s_dir * 0.88f,  0.58f, -0.02f, 0.072f);
    float k2 = sd_capsule(x, y, z_tilt, -s_dir * 0.88f, -0.58f, -0.02f, -s_dir * 0.88f, -0.86f, -0.02f, 0.072f);
    d = smin(d, k1, 0.04f);
    d = smin(d, k2, 0.04f);

    float scr_cx = -s_dir * 0.02f;
    float scr_cut = sd_extruded_rrect(x, y, z_tilt, scr_cx, 0.0f, 0.20f, SCR_HX + 0.018f, SCR_HY + 0.018f, 0.115f, 0.165f, 0.032f);
    d = smax(d, -scr_cut, 0.020f);

    float best = d;
    int8_t mat = 0;
    auto put = [&](float dd, int8_t mid) { if (dd < best) { best = dd; mat = mid; } };

    put(sd_extruded_rrect(x, y, z_tilt, scr_cx, 0.0f, 0.098f, SCR_HX - 0.005f, SCR_HY - 0.005f, 0.022f, 0.15f, 0.014f), 1);
    put(sd_capsule(x, y, z_tilt, s_dir * 0.865f, 0.78f, 0.155f, s_dir * 0.865f, -0.78f, 0.155f, 0.016f), 5);
    put(sd_capsule(x, y, z_tilt, -0.42f, 1.135f, 0.155f, 0.42f, 1.135f, 0.155f, 0.014f), 5);

    return {best, mat};
}

enum SceneMode { MODE_WHALE = 0, MODE_ORB = 1, MODE_PILL = 2, MODE_DRAWER_L = 3, MODE_DRAWER_R = 4 };
static SceneMode g_mode = MODE_WHALE;

inline Hit eval_scene(float x, float y, float z) {
    switch (g_mode) {
        case MODE_WHALE:    return scene_whale(x, y, z);
        case MODE_ORB:      return scene_orb(x, y, z);
        case MODE_PILL:     return scene_pill(x, y, z);
        case MODE_DRAWER_L: return scene_drawer(x, y, z, -1.0f);
        case MODE_DRAWER_R: return scene_drawer(x, y, z,  1.0f);
    }
    return scene_whale(x, y, z);
}

// ------------------------------------------------------------------ Y2K Studio HDRI & Shading (matches y2k_shader.py)
static const Vec3 L1 = normalize({-0.40f, 0.66f, 0.64f});
static const Vec3 H1 = normalize({L1.x, L1.y, L1.z + 1.0f});
static const Vec3 L2 = normalize({ 0.60f, 0.30f, 0.74f});
static const Vec3 H2 = normalize({L2.x, L2.y, L2.z + 1.0f});

inline Vec3 env_y2k(float rx, float ry, float rz, float softness) {
    float s = softness;
    float sky = 0.24f + 0.56f * sstep(0.02f, 0.88f, ry);
    float ground = 0.04f + 0.20f * sstep(-0.95f, -0.06f, ry);
    float base = ry >= 0.0f ? sky : ground;

    float d_band = (ry + 0.055f) / (0.065f * s);
    base *= (1.0f - 0.68f * std::exp(-d_band * d_band));
    float h_band = (ry - 0.045f) / (0.048f * s);
    float horizon = 0.72f * std::exp(-h_band * h_band);
    float u_band = (ry - 0.48f) / (0.11f * s);
    float upper_ring = 0.34f * std::exp(-u_band * u_band);

    auto softbox = [&](float x0, float x1, float y0, float y1, float val, float edge) {
        float e = edge * s;
        return val
            * sstep(x0 - e, x0 + e, rx) * (1.0f - sstep(x1 - e, x1 + e, rx))
            * sstep(y0 - e, y0 + e, ry) * (1.0f - sstep(y1 - e, y1 + e, ry))
            * sstep(-0.15f, 0.22f, rz);
    };

    float lum = base + horizon + upper_ring
        + softbox(-0.92f, -0.20f,  0.20f,  0.92f, 1.18f, 0.06f)
        + softbox(-0.44f,  0.44f,  0.72f,  1.05f, 0.85f, 0.08f)
        + softbox( 0.38f,  0.88f, -0.18f,  0.54f, 0.64f, 0.07f)
        + softbox(-0.55f,  0.25f, -0.78f, -0.35f, 0.34f, 0.09f);

    float t_sky = sstep(-0.35f, 0.55f, ry);
    float r = lum * (0.96f * (1.0f - t_sky) + 0.88f * t_sky);
    float g = lum * (0.96f * (1.0f - t_sky) + 0.96f * t_sky);
    float b = lum * (0.99f * (1.0f - t_sky) + 1.10f * t_sky);

    float cx1 = (rx + 0.78f) / (0.25f * s), cy1 = (ry - 0.08f) / (0.62f * s);
    float cyan_strip = 0.78f * std::exp(-(cx1 * cx1 + cy1 * cy1)) * sstep(-0.45f, 0.55f, rz);
    float vx1 = (rx - 0.80f) / (0.25f * s), vy1 = (ry - 0.04f) / (0.62f * s);
    float violet_strip = 0.68f * std::exp(-(vx1 * vx1 + vy1 * vy1)) * sstep(-0.45f, 0.55f, rz);

    return {
        r + cyan_strip * 0.14f + violet_strip * 0.46f,
        g + cyan_strip * 0.88f + violet_strip * 0.34f,
        b + cyan_strip * 1.22f + violet_strip * 1.20f
    };
}

inline Vec3 tonemap_y2k(const Vec3& c) {
    auto tm = [](float v) {
        v = v > 0.0f ? v : 0.0f;
        float num = v * (2.30f * v + 0.04f);
        float den = v * (2.12f * v + 0.50f) + 0.055f;
        return std::pow(clampf(num / den, 0.0f, 1.0f), 1.0f / 1.06f);
    };
    return {tm(c.x), tm(c.y), tm(c.z)};
}

inline Vec3 shade_material(float nx, float ny, float nz, float x, float y, float z, int8_t mat, float ao, float sh) {
    float ndv = clampf(nz, 0.0f, 1.0f);
    float rx = 2.0f * nz * nx, ry = 2.0f * nz * ny, rz = 2.0f * nz * nz - 1.0f;
    float fresnel = (1.0f - ndv) * (1.0f - ndv) * (1.0f - ndv);
    float ndh1 = clampf(nx * H1.x + ny * H1.y + nz * H1.z, 0.0f, 1.0f);
    float ndh2 = clampf(nx * H2.x + ny * H2.y + nz * H2.z, 0.0f, 1.0f);
    float ndl1 = clampf(nx * L1.x + ny * L1.y + nz * L1.z, 0.0f, 1.0f);
    float ao_t = 0.36f + 0.64f * ao;
    float sh_t = 0.50f + 0.50f * sh;

    Vec3 out = {0.0f, 0.0f, 0.0f};
    if (mat == 0) {
        Vec3 env = env_y2k(rx, ry, rz, 1.0f);
        float sp = std::pow(ndh1, 165.0f) * 1.85f * sh + std::pow(ndh1, 24.0f) * 0.30f * sh + std::pow(ndh2, 95.0f) * 0.48f;
        float t_rim = sstep(-0.38f, 0.38f, nx);
        Vec3 rim_col = Vec3{0.18f, 0.88f, 1.18f} * (1.0f - t_rim) + Vec3{0.52f, 0.44f, 1.14f} * t_rim;
        out = (env * Vec3{0.94f, 0.98f, 1.06f} + Vec3{ndl1 * 0.08f, ndl1 * 0.08f, ndl1 * 0.08f}) * (ao_t * sh_t)
            + Vec3{0.98f, 0.99f, 1.06f} * sp + rim_col * (fresnel * 0.56f * ao_t);
    } else if (mat == 1) {
        Vec3 env = env_y2k(rx, ry, rz, 1.0f);
        float sh_d = (x * 0.60f - y * 0.44f + 0.10f) / 0.38f;
        float sheen = std::exp(-sh_d * sh_d) * 0.14f;
        float gl_sp = std::pow(ndh1, 200.0f) * 1.35f * sh;
        out = Vec3{0.006f, 0.014f, 0.028f} + env * (0.045f + 0.28f * fresnel)
            + Vec3{gl_sp, gl_sp, gl_sp} + Vec3{0.45f, 0.78f, 1.05f} * sheen;
    } else if (mat == 2) {
        Vec3 env = env_y2k(rx, ry, rz, 1.80f);
        float grain = 0.93f + 0.07f * std::sin(y * 280.0f) * std::cos(x * 12.0f);
        float sp = std::pow(ndh1, 48.0f) * 0.72f * sh + std::pow(ndh2, 30.0f) * 0.28f;
        out = (env * Vec3{0.74f, 0.81f, 0.91f} * grain + Vec3{ndl1 * 0.14f, ndl1 * 0.14f, ndl1 * 0.14f}) * (ao_t * sh_t)
            + Vec3{sp, sp, sp};
    } else if (mat == 3 || mat == 4 || mat == 7) {
        Vec3 env = env_y2k(rx, ry, rz, 1.0f);
        float ca = clampf(-ny * 0.78f + 0.28f, 0.0f, 1.0f);
        float caustic = ca * ca * (0.45f + 0.55f * ndv);
        float top_glint = std::pow(clampf(ny * 0.90f - nx * 0.46f + 0.14f, 0.0f, 1.0f), 10.0f);
        float sp = std::pow(ndh1, 150.0f) * 1.55f;
        Vec3 base_c, caust_c, fres_c, glint_c;
        float fres_k = 0.68f, env_fk = 0.26f;
        if (mat == 3) {
            base_c = {0.02f, 0.14f, 0.58f}; caust_c = {0.12f, 0.85f, 1.25f};
            fres_c = {0.28f, 0.88f, 1.25f}; glint_c = {0.94f, 0.99f, 1.08f};
            fres_k = 0.76f; env_fk = 0.28f;
        } else if (mat == 4) {
            base_c = {0.62f, 0.03f, 0.09f}; caust_c = {1.22f, 0.30f, 0.22f};
            fres_c = {1.15f, 0.40f, 0.52f}; glint_c = {1.05f, 0.94f, 0.96f};
        } else {
            base_c = {0.03f, 0.48f, 0.38f}; caust_c = {0.22f, 1.22f, 0.88f};
            fres_c = {0.36f, 1.18f, 1.05f}; glint_c = {1.00f, 1.00f, 1.00f};
        }
        out = (base_c * (0.42f + 0.78f * ndl1)
               + caust_c * (caustic * 1.05f)
               + fres_c * (fresnel * fres_k)
               + env * (0.12f + env_fk * fresnel)
               + glint_c * (top_glint * 1.05f)
               + Vec3{sp, sp, sp}) * ao_t;
    } else if (mat == 5) {
        float core = 0.58f + 0.56f * ndv;
        float sp = std::pow(ndh1, 75.0f) * 0.95f;
        out = Vec3{0.20f, 1.12f, 1.50f} * core + Vec3{sp, sp, sp};
    } else {
        Vec3 env = env_y2k(rx, ry, rz, 1.80f);
        float sp = std::pow(ndh1, 65.0f) * 0.30f;
        out = (env * Vec3{0.09f, 0.12f, 0.17f} + Vec3{sp, sp, sp}) * ao_t;
    }
    return tonemap_y2k(out);
}

int main(int argc, char** argv) {
    if (argc < 6) {
        std::fprintf(stderr, "Usage: %s <whale|orb|pill|drawer_l|drawer_r> <W> <H> <S_px_per_world> <out.rgba> [threads] [ss]\n", argv[0]);
        return 1;
    }
    const char* mode_s = argv[1];
    if (std::strcmp(mode_s, "whale") == 0) g_mode = MODE_WHALE;
    else if (std::strcmp(mode_s, "orb") == 0) g_mode = MODE_ORB;
    else if (std::strcmp(mode_s, "pill") == 0) g_mode = MODE_PILL;
    else if (std::strcmp(mode_s, "drawer_l") == 0) g_mode = MODE_DRAWER_L;
    else if (std::strcmp(mode_s, "drawer_r") == 0) g_mode = MODE_DRAWER_R;
    else { std::fprintf(stderr, "Unknown mode: %s\n", mode_s); return 1; }

    int W = std::atoi(argv[2]);
    int H = std::atoi(argv[3]);
    float S = std::strtof(argv[4], nullptr);
    const char* out_path = argv[5];
    int threads = (argc >= 7) ? std::max(1, std::atoi(argv[6])) : 2;
    int ss = (argc >= 8) ? std::max(1, std::atoi(argv[7])) : 2;

    const float px_world = 1.0f / (S * ss);
    const float inv_ss = 1.0f / (float)ss;
    const float inv_samples = 1.0f / (float)(ss * ss);
    const float z_start = 1.6f;
    const float max_dist = 3.0f;
    const int max_steps = 96;
    const float eps = 0.0004f;
    const float norm_e = 0.0008f;

    std::vector<uint8_t> buf((size_t)W * (size_t)H * 4, 0);
    std::atomic<int> next_row{0};

    auto worker = [&]() {
        while (true) {
            int i = next_row.fetch_add(1, std::memory_order_relaxed);
            if (i >= H) break;
            uint8_t* row = buf.data() + (size_t)i * (size_t)W * 4;
            for (int j = 0; j < W; ++j) {
                float acc_r = 0.0f, acc_g = 0.0f, acc_b = 0.0f, acc_a = 0.0f;

                for (int sy = 0; sy < ss; ++sy) {
                    float y = (H * 0.5f - (i + (sy + 0.5f) * inv_ss)) / S;
                    for (int sx = 0; sx < ss; ++sx) {
                        float x = (j + (sx + 0.5f) * inv_ss - W * 0.5f) / S;
                        float t = 0.0f;
                        float mind = 9.0f;
                        bool hit = false;
                        for (int step = 0; step < max_steps; ++step) {
                            float d = eval_scene(x, y, z_start - t).d;
                            if (d < mind) mind = d;
                            if (d < eps) { hit = true; break; }
                            t += d * 0.94f;
                            if (t >= max_dist) break;
                        }
                        if (hit) {
                            float hz = z_start - t;
                            int8_t mat = eval_scene(x, y, hz).mat;

                            float d1 = eval_scene(x + norm_e, y - norm_e, hz - norm_e).d;
                            float d2 = eval_scene(x - norm_e, y - norm_e, hz + norm_e).d;
                            float d3 = eval_scene(x - norm_e, y + norm_e, hz - norm_e).d;
                            float d4 = eval_scene(x + norm_e, y + norm_e, hz + norm_e).d;
                            Vec3 n = normalize({d1 - d2 - d3 + d4, -d1 - d2 + d3 + d4, -d1 + d2 - d3 + d4});

                            // 4-tap multi-scale AO
                            float ao = 1.0f, sca = 1.0f;
                            const float ao_steps[4] = {0.02f, 0.06f, 0.14f, 0.26f};
                            for (int k = 0; k < 4; ++k) {
                                float sh_k = ao_steps[k];
                                float dd = eval_scene(x + n.x * sh_k, y + n.y * sh_k, hz + n.z * sh_k).d;
                                ao -= clampf(sh_k - dd, 0.0f, 1.0f) * sca * 1.95f;
                                sca *= 0.62f;
                            }
                            ao = clampf(ao, 0.24f, 1.0f);

                            // 10-step soft key shadow
                            float sh = 1.0f, st = 0.03f;
                            for (int k = 0; k < 10; ++k) {
                                float sd = eval_scene(x + L1.x * st, y + L1.y * st, hz + L1.z * st).d;
                                sh = std::min(sh, clampf(5.6f * sd / st, 0.0f, 1.0f));
                                st += clampf(sd, 0.025f, 0.16f);
                            }
                            sh = clampf(sh, 0.30f, 1.0f);

                            Vec3 col = shade_material(n.x, n.y, n.z, x, y, hz, mat, ao, sh);
                            acc_r += col.x;
                            acc_g += col.y;
                            acc_b += col.z;
                            acc_a += 1.0f;
                        } else {
                            float aa = clampf(1.0f - mind / px_world, 0.0f, 1.0f);
                            if (aa > 0.005f) {
                                acc_r += 0.478f * aa;
                                acc_g += 0.620f * aa;
                                acc_b += 0.800f * aa;
                                acc_a += aa;
                            }
                        }
                    }
                }

                float out_a = acc_a * inv_samples;
                if (out_a <= 0.002f) continue;
                float inv_a = 1.0f / std::max(acc_a, 1e-6f);
                row[j * 4 + 0] = (uint8_t)clampf((acc_r * inv_a) * 255.0f + 0.5f, 0.0f, 255.0f);
                row[j * 4 + 1] = (uint8_t)clampf((acc_g * inv_a) * 255.0f + 0.5f, 0.0f, 255.0f);
                row[j * 4 + 2] = (uint8_t)clampf((acc_b * inv_a) * 255.0f + 0.5f, 0.0f, 255.0f);
                row[j * 4 + 3] = (uint8_t)clampf(out_a * 255.0f + 0.5f, 0.0f, 255.0f);
            }
        }
    };

    std::vector<std::thread> pool;
    for (int t = 0; t < threads; ++t) pool.emplace_back(worker);
    for (auto& th : pool) th.join();

    std::ofstream ofs(out_path, std::ios::binary);
    ofs.write(reinterpret_cast<const char*>(buf.data()), (std::streamsize)buf.size());
    return ofs.good() ? 0 : 2;
}
