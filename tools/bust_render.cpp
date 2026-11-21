// EARWAX 4.7 — High-Precision C++17 Multi-Threaded 3D Analytical SDF Raymarcher
// Sculpted Realistic Humanoid Liquid-Chrome Head, Integrated Cyber-Ears, Anatomical Neck & Chest Bust.
//
// Compile: g++ -O3 -ffast-math -std=c++17 -pthread tools/bust_render.cpp -o tools/bust_render
// Usage:   ./tools/bust_render <width> <height> <out_rgba_raw_path> [threads]

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
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
inline Vec3 normalize(const Vec3& v) {
    float inv = 1.0f / (length(v) + 1e-8f);
    return v * inv;
}
inline float clampf(float x, float lo, float hi) {
    return x < lo ? lo : (x > hi ? hi : x);
}
inline float sstep(float a, float b, float x) {
    float t = clampf((x - a) / (b - a), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}
inline float smin(float a, float b, float k) {
    float h = clampf(0.5f + 0.5f * (b - a) / k, 0.0f, 1.0f);
    return b * (1.0f - h) + a * h - k * h * (1.0f - h);
}
inline float smax(float a, float b, float k) {
    return -smin(-a, -b, k);
}

// ------------------------------------------------------------------ SDF Primitives
inline float sd_sphere(float x, float y, float z, float cx, float cy, float cz, float r) {
    float dx = x - cx, dy = y - cy, dz = z - cz;
    return std::sqrt(dx * dx + dy * dy + dz * dz) - r;
}

inline float sd_ellipsoid(float x, float y, float z,
                          float cx, float cy, float cz,
                          float rx, float ry, float rz) {
    float qx = (x - cx) / rx;
    float qy = (y - cy) / ry;
    float qz = (z - cz) / rz;
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

inline float sd_taper_capsule(float x, float y, float z,
                              float ax, float ay, float az,
                              float bx, float by, float bz, float r1, float r2) {
    float px = x - ax, py = y - ay, pz = z - az;
    float dx = bx - ax, dy = by - ay, dz = bz - az;
    float h = clampf((px * dx + py * dy + pz * dz) / (dx * dx + dy * dy + dz * dz + 1e-12f), 0.0f, 1.0f);
    float ex = px - dx * h, ey = py - dy * h, ez = pz - dz * h;
    return std::sqrt(ex * ex + ey * ey + ez * ez) - (r1 + (r2 - r1) * h);
}

inline float sd_rbox(float x, float y, float z,
                     float cx, float cy, float cz,
                     float bx, float by, float bz, float r) {
    float qx = std::fabs(x - cx) - bx + r;
    float qy = std::fabs(y - cy) - by + r;
    float qz = std::fabs(z - cz) - bz + r;
    float mx = std::max(qx, 0.0f), my = std::max(qy, 0.0f), mz = std::max(qz, 0.0f);
    return std::sqrt(mx * mx + my * my + mz * mz) + std::min(std::max(qx, std::max(qy, qz)), 0.0f) - r;
}

inline float sd_extruded_rrect(float x, float y, float z,
                               float cx, float cy, float cz,
                               float hx, float hy, float hz, float r, float rz) {
    float qx = std::fabs(x - cx) - hx + r;
    float qy = std::fabs(y - cy) - hy + r;
    float mx = std::max(qx, 0.0f), my = std::max(qy, 0.0f);
    float d2 = std::sqrt(mx * mx + my * my) + std::min(std::max(qx, qy), 0.0f) - r;
    float wz = std::fabs(z - cz) - hz + rz;
    float w1 = d2 + rz;
    float mw1 = std::max(w1, 0.0f), mwz = std::max(wz, 0.0f);
    return std::sqrt(mw1 * mw1 + mwz * mwz) + std::min(std::max(w1, wz), 0.0f) - rz;
}

inline float sd_cylinder_z(float x, float y, float z, float cx, float cy, float cz, float r, float hz) {
    float dx = std::sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) - r;
    float dz = std::fabs(z - cz) - hz;
    float mdx = std::max(dx, 0.0f), mdz = std::max(dz, 0.0f);
    return std::min(std::max(dx, dz), 0.0f) + std::sqrt(mdx * mdx + mdz * mdz);
}

inline float sd_torus_z(float x, float y, float z, float cx, float cy, float cz, float maj_r, float min_r) {
    float q = std::sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) - maj_r;
    return std::sqrt(q * q + (z - cz) * (z - cz)) - min_r;
}

// ------------------------------------------------------------------ Layout Constants (matches bust.json)
static constexpr float DESIGN_W = 1080.0f;
static constexpr float DESIGN_H = 2100.0f;
static constexpr float WORLD_W  = 3.25f;
static constexpr float S0       = DESIGN_W / WORLD_W; // 332.3077
static constexpr float YC       = 1.98f;

// Visor & Face Landmarks
static constexpr float VISOR_Y    = 0.50f;
static constexpr float VISOR_HX   = 0.65f;
static constexpr float VISOR_HY   = 0.31f;
static constexpr float Y_NOSE_TIP = -0.18f;
static constexpr float Y_MOUTH    = -0.50f;
static constexpr float Y_CHIN     = -0.80f;

// Ergonomic Integrated Cyber-Ear Housings (anatomically positioned at temple/ear level!)
static constexpr float POD_X = 0.94f;
static constexpr float POD_Y = 0.16f;

struct ConeSpec { float sx; float cy; float cone_r; float sock_r; };
static constexpr ConeSpec CONES[6] = {
    {-1.0f, POD_Y + 0.36f, 0.114f, 0.132f},
    {-1.0f, POD_Y + 0.00f, 0.124f, 0.142f},
    {-1.0f, POD_Y - 0.37f, 0.134f, 0.152f},
    { 1.0f, POD_Y + 0.36f, 0.114f, 0.132f},
    { 1.0f, POD_Y + 0.00f, 0.124f, 0.142f},
    { 1.0f, POD_Y - 0.37f, 0.134f, 0.152f},
};

// Chest Console Panel (x=126, y=1280, w=828, h=672 -> matches bust.json exactly)
static constexpr float PANEL_CY    = YC - (1280.0f + 672.0f * 0.5f) / S0; // -2.883
static constexpr float PANEL_HX    = (828.0f * 0.5f) / S0;                // 1.246
static constexpr float PANEL_HY    = (672.0f * 0.5f) / S0;                // 1.011
static constexpr float CHEST_FRONT = 0.44f;
static constexpr float VENT_Y      = YC - 2016.0f / S0;                   // -4.087

// ------------------------------------------------------------------ Realistic Sculpted Head & Neck SDF
inline float head_and_neck_sdf(float x, float y, float z) {
    const float ax = std::fabs(x);

    // 1. Anatomically Proportioned Cranium (natural parietal dome, slight temple taper, realistic height)
    float d = sd_ellipsoid(x, y, z, 0.0f, 0.62f, -0.06f, 0.76f, 0.84f, 0.82f);

    // Frontal bone (forehead plane curving smoothly above the visor)
    float d_forehead = sd_ellipsoid(x, y, z, 0.0f, 0.84f, 0.18f, 0.66f, 0.44f, 0.56f);
    d = smin(d, d_forehead, 0.22f);

    // Subtle supraorbital brow ridge framing the top of the visor (kept cleanly behind visor glass!)
    float brow_y = 0.90f - 0.05f * (ax * ax);
    float d_brow = sd_taper_capsule(ax, y, z, 0.0f, 0.91f, 0.56f, 0.56f, brow_y - 0.03f, 0.42f, 0.10f, 0.085f);
    d = smin(d, d_brow, 0.18f);

    // 2. Sculpted Midface, Cheekbones (Zygoma) & Realistic Mandible (Jawline)
    float d_midface = sd_ellipsoid(x, y, z, 0.0f, 0.04f, 0.10f, 0.71f, 0.52f, 0.68f);
    d = smin(d, d_midface, 0.24f);

    // Smooth anatomical cheekbones sweeping cleanly back toward the ear tragus
    float d_cheek = sd_taper_capsule(ax, y, z, 0.42f, 0.02f, 0.48f, 0.66f, 0.08f, 0.12f, 0.17f, 0.14f);
    d = smin(d, d_cheek, 0.26f);

    // Realistic human lower jaw with natural gonial angle (corner of jaw below ear) -> sculpted chin
    float d_jaw_core = sd_ellipsoid(x, y, z, 0.0f, -0.34f, 0.18f, 0.56f, 0.46f, 0.57f);
    d = smin(d, d_jaw_core, 0.24f);

    // Mandibular ramus (vertical back of jaw dropping from ear to jaw corner)
    float d_ramus = sd_taper_capsule(ax, y, z, 0.63f, -0.02f, 0.04f, 0.54f, -0.44f, 0.18f, 0.135f, 0.125f);
    d = smin(d, d_ramus, 0.20f);

    // Mandibular body (clean crisp jawline sweeping from jaw corner to chin)
    float d_mandible = sd_taper_capsule(ax, y, z, 0.54f, -0.44f, 0.18f, 0.15f, Y_CHIN + 0.02f, 0.56f, 0.13f, 0.115f);
    d = smin(d, d_mandible, 0.18f);

    // Sculpted human chin (mentum) with subtle heroic contour
    float d_chin = sd_ellipsoid(x, y, z, 0.0f, Y_CHIN + 0.02f, 0.57f, 0.21f, 0.135f, 0.165f);
    d = smin(d, d_chin, 0.14f);

    // 3. Realistic Sculpted Human Nose
    // Nasal bridge emerging smoothly from beneath visor center
    float d_bridge = sd_taper_capsule(x, y, z, 0.0f, 0.18f, 0.72f, 0.0f, Y_NOSE_TIP + 0.02f, 0.89f, 0.056f, 0.070f);
    d = smin(d, d_bridge, 0.095f);
    // Refined nasal tip
    float d_tip = sd_ellipsoid(x, y, z, 0.0f, Y_NOSE_TIP, 0.90f, 0.072f, 0.062f, 0.076f);
    d = smin(d, d_tip, 0.065f);
    // Sculpted nasal wings (alae)
    float d_ala = sd_ellipsoid(ax, y, z, 0.092f, Y_NOSE_TIP - 0.022f, 0.815f, 0.052f, 0.044f, 0.058f);
    d = smin(d, d_ala, 0.055f);
    // Subtle nasal base / columella
    float d_col = sd_capsule(x, y, z, 0.0f, Y_NOSE_TIP - 0.03f, 0.88f, 0.0f, Y_NOSE_TIP - 0.07f, 0.78f, 0.028f);
    d = smin(d, d_col, 0.035f);
    // Delicate nostrils (understated, realistic shadow)
    float d_nostril = sd_ellipsoid(ax, y, z, 0.054f, Y_NOSE_TIP - 0.056f, 0.850f, 0.026f, 0.017f, 0.038f);
    d = smax(d, -d_nostril, 0.014f);
    // Subtle philtrum dimple between nose and upper lip
    float d_phil = sd_capsule(x, y, z, 0.0f, Y_NOSE_TIP - 0.09f, 0.77f, 0.0f, Y_MOUTH + 0.075f, 0.77f, 0.022f);
    d = smax(d, -d_phil, 0.042f);

    // 4. Realistic Sculpted Human Lips (natural Cupid's bow & lower-lip fullness)
    // Perioral muzzle (gentle forward curve of mouth area so lips sit organically on the face)
    float d_muzzle = sd_ellipsoid(x, y, z, 0.0f, Y_MOUTH, 0.56f, 0.30f, 0.20f, 0.22f);
    d = smin(d, d_muzzle, 0.14f);
    // Upper lip (two gentle curved lobes forming a subtle Cupid's bow)
    float d_ulip_c = sd_ellipsoid(ax, y, z, 0.068f, Y_MOUTH + 0.034f, 0.735f, 0.125f, 0.032f, 0.062f);
    float d_ulip_w = sd_taper_capsule(ax, y, z, 0.04f, Y_MOUTH + 0.032f, 0.745f, 0.205f, Y_MOUTH + 0.002f, 0.675f, 0.030f, 0.018f);
    d = smin(d, smin(d_ulip_c, d_ulip_w, 0.035f), 0.050f);
    // Lower lip (fuller, softly curved)
    float d_llip = sd_ellipsoid(x, y, z, 0.0f, Y_MOUTH - 0.038f, 0.738f, 0.165f, 0.042f, 0.068f);
    d = smin(d, d_llip, 0.052f);
    // Natural lip parting line (gentle shadow crease, not a harsh gash)
    float mouth_curve = 0.012f * std::cos(clampf(ax / 0.21f, 0.0f, 1.0f) * 3.14159f);
    float d_part = sd_ellipsoid(x, y - mouth_curve, z, 0.0f, Y_MOUTH - 0.002f, 0.815f, 0.215f, 0.0075f, 0.062f);
    d = smax(d, -d_part, 0.012f);
    // Subtle mentolabial fold (clean hollow between lower lip and chin)
    float d_mlf = sd_ellipsoid(x, y, z, 0.0f, Y_MOUTH - 0.125f, 0.73f, 0.15f, 0.028f, 0.055f);
    d = smax(d, -d_mlf, 0.095f);

    // 5. Anatomically Connected Muscular Neck (solid, proportional, zero bobblehead / zero double-chin!)
    // Core cervical neck column (oval cylinder, realistic width = 0.37, properly set beneath skull base)
    float d_neck_col = sd_taper_capsule(x, y, z, 0.0f, -0.58f, 0.02f, 0.0f, -1.56f, 0.06f, 0.355f, 0.415f);
    d = smin(d, d_neck_col, 0.22f);

    // Clean anatomical submental plane (smooth underside of jaw meeting upper throat cleanly)
    float d_submental = sd_ellipsoid(x, y, z, 0.0f, -0.76f, 0.22f, 0.33f, 0.16f, 0.28f);
    d = smin(d, d_submental, 0.16f);

    // Subtle throat / larynx column (slightly forward on lower-mid neck)
    float d_throat = sd_taper_capsule(x, y, z, 0.0f, -0.92f, 0.24f, 0.0f, -1.48f, 0.26f, 0.165f, 0.210f);
    d = smin(d, d_throat, 0.18f);

    // Sculpted sternocleidomastoid (SCM) V-muscles sweeping from mastoid behind ears down to sternum
    float d_scm = sd_taper_capsule(ax, y, z, 0.41f, -0.52f, -0.02f, 0.11f, -1.52f, 0.31f, 0.115f, 0.095f);
    d = smin(d, d_scm, 0.16f);

    // Graceful trapezius slopes flowing seamlessly from mid-neck out to broad deltoid shoulders
    float d_trap = sd_taper_capsule(ax, y, z, 0.30f, -0.96f, -0.08f, 1.24f, -1.68f, -0.04f, 0.175f, 0.31f);
    d = smin(d, d_trap, 0.26f);

    // Anatomical S-curved clavicles (collarbones) bridging sternum to shoulder deltoids
    float d_clav = sd_taper_capsule(ax, y, z, 0.14f, -1.54f, 0.35f, 1.16f, -1.66f, 0.18f, 0.090f, 0.135f);
    d = smin(d, d_clav, 0.18f);

    // Broad sculpted base-of-neck / upper-chest transition bridge
    float d_collar_base = sd_ellipsoid(x, y, z, 0.0f, -1.58f, 0.06f, 0.88f, 0.34f, 0.42f);
    d = smin(d, d_collar_base, 0.26f);

    // Delicate suprasternal notch dimple at base of throat
    float d_notch = sd_ellipsoid(x, y, z, 0.0f, -1.50f, 0.42f, 0.115f, 0.072f, 0.095f);
    d = smax(d, -d_notch, 0.12f);

    // 6. Sleek Curved Wrap-Around Cyber-Visor Bezel (organically integrated into orbital brow/cheeks)
    float zv = z + 0.22f * (x * x);
    float nose_arch = 0.068f * std::exp(-((x / 0.24f) * (x / 0.24f)));
    float yv = y - VISOR_Y - nose_arch * sstep(-0.05f, -0.30f, y - VISOR_Y);

    float bez_out = sd_extruded_rrect(x, yv, zv, 0.0f, 0.0f, 0.62f, VISOR_HX + 0.082f, VISOR_HY + 0.074f, 0.24f, 0.26f, 0.066f);
    float bez_in  = sd_extruded_rrect(x, yv, zv, 0.0f, 0.0f, 0.84f, VISOR_HX - 0.010f, VISOR_HY - 0.010f, 0.15f, 0.19f, 0.038f);
    float bez = smax(bez_out, -bez_in, 0.022f);
    d = smin(d, bez, 0.085f);

    return d;
}

// ------------------------------------------------------------------ Realistic Ergonomic Cyber-Ear Pods SDF
// Anatomically contoured headphone/auricle housing with a crisp machined temple yoke (NO melted frog-head!)
inline float ear_pods_sdf(float x, float y, float z, float& out_joint_d) {
    const float ax = std::fabs(x);

    // Gently curved ergonomic outer shell hugging the head silhouette
    float dy = y - POD_Y;
    float curve_x = -0.055f * (dy * dy); // hugs inward at top & bottom like a real ear/headset cup!
    float w_taper = 0.022f * sstep(0.32f, -0.40f, dy);
    float d_pod = sd_extruded_rrect(ax, y, z, POD_X + curve_x, POD_Y, 0.04f,
                                    0.198f + w_taper, 0.575f, 0.235f, 0.195f, 0.105f);

    // Sculpted anatomical ear-base collar / tragus-to-mastoid mounting yoke (clean mechanical joint!)
    float d_mount = sd_ellipsoid(ax, y, z, 0.74f, POD_Y - 0.02f, -0.04f, 0.125f, 0.40f, 0.16f);
    // Upper & lower sleek curved titanium-chrome temple arms hugging the cranium naturally
    float d_arm_top = sd_taper_capsule(ax, y, z, 0.66f, POD_Y + 0.36f, -0.02f, POD_X - 0.10f, POD_Y + 0.36f, -0.02f, 0.080f, 0.088f);
    float d_arm_bot = sd_taper_capsule(ax, y, z, 0.62f, POD_Y - 0.34f, -0.02f, POD_X - 0.10f, POD_Y - 0.35f, -0.02f, 0.078f, 0.088f);
    out_joint_d = smin(d_mount, smin(d_arm_top, d_arm_bot, 0.045f), 0.050f);

    // Subtle recessed groove along outer ear helix rim for cyan acoustic light-pipe
    float sp_y = clampf(dy, -0.35f, 0.35f);
    float sp_cx = POD_X + 0.184f - 0.055f * (sp_y * sp_y) + 0.022f * sstep(0.32f, -0.40f, sp_y);
    float sp_dx = ax - sp_cx, sp_dy = y - (POD_Y + sp_y), sp_dz = z - 0.135f;
    float spine_groove = std::sqrt(sp_dx * sp_dx + sp_dy * sp_dy + sp_dz * sp_dz) - 0.024f;
    d_pod = smax(d_pod, -spine_groove, 0.012f);

    // Carve 3 deep precision-chamfered acoustic speaker sockets
    for (int i = 0; i < 6; ++i) {
        if ((CONES[i].sx < 0.0f && x > 0.0f) || (CONES[i].sx > 0.0f && x < 0.0f)) continue;
        float rx = x - CONES[i].sx * POD_X;
        float ry = y - CONES[i].cy;
        float rho = std::sqrt(rx * rx + ry * ry);
        float cav = std::max(rho - (CONES[i].sock_r + 0.006f), 0.148f - z);
        d_pod = smax(d_pod, -cav, 0.024f);
    }

    return d_pod;
}

// ------------------------------------------------------------------ Chest Armor & Shoulders SDF
inline float chest_sdf(float x, float y, float z) {
    float hx_taper = 1.48f - 0.12f * sstep(-2.40f, -4.25f, y);
    float qx = std::fabs(x) - hx_taper + 0.52f;
    float qy = std::fabs(y - (-2.90f)) - 1.32f + 0.52f;
    float mx = std::max(qx, 0.0f), my = std::max(qy, 0.0f);
    float d2 = std::sqrt(mx * mx + my * my) + std::min(std::max(qx, qy), 0.0f) - 0.52f;
    float wz = std::fabs(z - (-0.02f)) - 0.46f + 0.18f;
    float w1 = d2 + 0.18f;
    float mw1 = std::max(w1, 0.0f), mwz = std::max(wz, 0.0f);
    float body = std::sqrt(mw1 * mw1 + mwz * mwz) + std::min(std::max(w1, wz), 0.0f) - 0.18f;

    // Sculpted upper chest & deltoid shoulder cap flowing smoothly into trapezius & clavicles
    float traps = sd_ellipsoid(x, y, z, 0.0f, -1.68f, -0.01f, 1.32f, 0.44f, 0.45f);
    float d = smin(body, traps, 0.34f);

    // Crisp diagonal cyber-armor shoulder contour grooves
    const float ax = std::fabs(x);
    float sh_g = sd_taper_capsule(ax, y, z, 0.78f, -1.68f, 0.40f, 1.38f, -2.18f, 0.36f, 0.016f, 0.013f);
    d = smax(d, -sh_g, 0.015f);

    // 11 Precision-Milled 3D Acoustic Grille Slots
    if (y < -3.72f) {
        float slot_idx = clampf(std::round(x / 0.145f), -5.0f, 5.0f);
        float vx = slot_idx * 0.145f;
        float slot = sd_rbox(x, y, z, vx, VENT_Y, CHEST_FRONT + 0.06f, 0.026f, 0.058f, 0.16f, 0.024f);
        d = smax(d, -slot, 0.011f);
    }

    return d;
}

// ------------------------------------------------------------------ Full Scene Evaluator
struct SceneHit {
    float d;
    int mat;
};

inline SceneHit eval_scene(float x, float y, float z) {
    float d_body;
    int mat = 0;

    if (y < -2.45f) {
        d_body = chest_sdf(x, y, z);
    } else if (y > -0.45f) {
        d_body = head_and_neck_sdf(x, y, z);
    } else {
        d_body = smin(head_and_neck_sdf(x, y, z), chest_sdf(x, y, z), 0.30f);
    }

    // Carve chamfered chest console pocket AFTER head+chest fusion so bezel is 100% crisp
    if (y < -1.55f) {
        float lip_cut = sd_extruded_rrect(x, y, z, 0.0f, PANEL_CY, CHEST_FRONT + 0.14f,
                                          PANEL_HX + 0.022f, PANEL_HY + 0.022f, 0.28f, 0.175f, 0.050f);
        d_body = smax(d_body, -lip_cut, 0.036f);
    }

    float best = d_body;

    // Ergonomic Cyber-Ear Pods + Satin-Titanium Temple Mount Joint (clean sculptural union, NOT melted!)
    if (y > -0.68f && y < 1.05f && std::fabs(x) > 0.45f) {
        float d_joint = 9.0f;
        float d_pod = ear_pods_sdf(x, y, z, d_joint);
        // Subtle crisp fillet (k = 0.045) between head & satin titanium temple mount,
        // and clean machined seam (k = 0.032) between mount & chrome ear pod!
        float d_with_mount = smin(best, d_joint, 0.045f);
        if (d_joint < best + 0.02f && d_joint < d_pod) {
            mat = 2; // Brushed Satin Titanium ear-mount collar!
        }
        best = d_with_mount;
        float d_fused = smin(best, d_pod, 0.032f);
        if (d_pod < best) {
            mat = 0; // Mirror Liquid Chrome ear housing
        }
        best = d_fused;
    }

    auto put = [&](float dd, int m_id) {
        if (dd < best) { best = dd; mat = m_id; }
    };

    // 1. Forehead Visor Glass + Luminous Cyan Trim
    if (y > -0.10f && y < 1.05f && std::fabs(x) < 0.85f) {
        float zv = z + 0.22f * (x * x);
        float nose_arch = 0.068f * std::exp(-((x / 0.24f) * (x / 0.24f)));
        float yv = y - VISOR_Y - nose_arch * sstep(-0.05f, -0.30f, y - VISOR_Y);
        float glass_v = sd_extruded_rrect(x, yv, zv + 0.042f * (x * x + yv * yv),
                                          0.0f, 0.0f, 0.760f, VISOR_HX - 0.014f, VISOR_HY - 0.014f, 0.040f, 0.18f, 0.026f);
        put(glass_v, 1);

        float vr_out = sd_extruded_rrect(x, yv, zv, 0.0f, 0.0f, 0.790f, VISOR_HX + 0.009f, VISOR_HY + 0.009f, 0.015f, 0.195f, 0.009f);
        float vr_in  = sd_extruded_rrect(x, yv, zv, 0.0f, 0.0f, 0.790f, VISOR_HX - 0.013f, VISOR_HY - 0.013f, 0.034f, 0.178f, 0.009f);
        put(std::max(vr_out, -vr_in), 5);
    }

    // 2. Ear Speaker Socket Chambers, Brushed Titanium Bezels & Cyan Light-Pipes
    if (y > -0.60f && y < 0.98f && std::fabs(x) > 0.65f) {
        for (int i = 0; i < 6; ++i) {
            if ((CONES[i].sx < 0.0f && x > 0.0f) || (CONES[i].sx > 0.0f && x < 0.0f)) continue;
            float fl = sd_cylinder_z(x, y, z, CONES[i].sx * POD_X, CONES[i].cy, 0.152f, CONES[i].sock_r + 0.004f, 0.008f);
            put(fl, 6);
            float bezel = sd_torus_z(x, y, z, CONES[i].sx * POD_X, CONES[i].cy, 0.232f, CONES[i].sock_r + 0.009f, 0.014f);
            put(bezel, 2);
        }
        float ax = std::fabs(x);
        float sp_y = clampf(y - POD_Y, -0.34f, 0.34f);
        float sp_cx = POD_X + 0.176f - 0.055f * (sp_y * sp_y) + 0.022f * sstep(0.32f, -0.40f, sp_y);
        float sp_dx = ax - sp_cx, sp_dy = y - (POD_Y + sp_y), sp_dz = z - 0.130f;
        float spine = std::sqrt(sp_dx * sp_dx + sp_dy * sp_dy + sp_dz * sp_dz) - 0.017f;
        put(spine, 5);
    }

    // 3. Chest Console Glass, Collar Accent Bar & Vent Backing
    if (y < -1.35f) {
        float glass_c = sd_extruded_rrect(x, y, z, 0.0f, PANEL_CY, CHEST_FRONT - 0.10f,
                                          PANEL_HX - 0.012f, PANEL_HY - 0.012f, 0.024f, 0.15f, 0.016f);
        put(glass_c, 1);

        float collar_bar = sd_capsule(x, y, z, -0.40f, -1.72f, 0.455f, 0.40f, -1.72f, 0.455f, 0.015f);
        put(collar_bar, 5);

        if (y < -3.72f) {
            float vent_back = sd_rbox(x, y, z, 0.0f, VENT_Y, CHEST_FRONT - 0.075f, 0.82f, 0.068f, 0.014f, 0.010f);
            put(vent_back, 6);
        }
    }

    return {best, mat};
}

inline float eval_dist(float x, float y, float z) {
    return eval_scene(x, y, z).d;
}

// ------------------------------------------------------------------ Studio Y2K HDRI Environment & PBR Shading
static const Vec3 L1 = normalize({-0.42f, 0.66f, 0.62f});
static const Vec3 H1 = normalize({L1.x, L1.y, L1.z + 1.0f});
static const Vec3 L2 = normalize({ 0.58f, 0.32f, 0.74f});
static const Vec3 H2 = normalize({L2.x, L2.y, L2.z + 1.0f});

inline Vec3 env_y2k(float rx, float ry, float rz, float softness) {
    float s = softness;
    float sky = 0.25f + 0.56f * sstep(0.02f, 0.88f, ry);
    float ground = 0.05f + 0.20f * sstep(-0.95f, -0.06f, ry);
    float base = ry >= 0.0f ? sky : ground;

    float d_band = (ry + 0.055f) / (0.068f * s);
    base *= (1.0f - 0.64f * std::exp(-d_band * d_band));

    float h_band = (ry - 0.045f) / (0.050f * s);
    float horizon = 0.74f * std::exp(-h_band * h_band);

    float u_band = (ry - 0.48f) / (0.115f * s);
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
        + softbox(-0.44f,  0.44f,  0.72f,  1.05f, 0.86f, 0.08f)
        + softbox( 0.38f,  0.88f, -0.18f,  0.54f, 0.65f, 0.07f)
        + softbox(-0.55f,  0.25f, -0.78f, -0.35f, 0.34f, 0.09f);

    float t_sky = sstep(-0.35f, 0.55f, ry);
    float r = lum * (0.96f * (1.0f - t_sky) + 0.89f * t_sky);
    float g = lum * (0.96f * (1.0f - t_sky) + 0.96f * t_sky);
    float b = lum * (0.99f * (1.0f - t_sky) + 1.10f * t_sky);

    float cx = (rx + 0.78f) / (0.25f * s), cy = (ry - 0.08f) / (0.62f * s);
    float cyan_strip = 0.76f * std::exp(-(cx * cx + cy * cy)) * sstep(-0.45f, 0.55f, rz);

    float vx = (rx - 0.80f) / (0.25f * s), vy = (ry - 0.04f) / (0.62f * s);
    float violet_strip = 0.64f * std::exp(-(vx * vx + vy * vy)) * sstep(-0.45f, 0.55f, rz);

    return {
        r + cyan_strip * 0.14f + violet_strip * 0.44f,
        g + cyan_strip * 0.86f + violet_strip * 0.34f,
        b + cyan_strip * 1.20f + violet_strip * 1.18f
    };
}

inline Vec3 tonemap_y2k(const Vec3& c) {
    auto tm = [](float v) {
        v = std::max(v, 0.0f);
        float o = clampf((v * (2.30f * v + 0.04f)) / (v * (2.12f * v + 0.50f) + 0.055f), 0.0f, 1.0f);
        return std::pow(o, 1.0f / 1.06f);
    };
    return {tm(c.x), tm(c.y), tm(c.z)};
}

inline Vec3 calc_normal(float x, float y, float z, float e) {
    float d1 = eval_dist(x + e, y - e, z - e);
    float d2 = eval_dist(x - e, y - e, z + e);
    float d3 = eval_dist(x - e, y + e, z - e);
    float d4 = eval_dist(x + e, y + e, z + e);
    return normalize({d1 - d2 - d3 + d4, -d1 - d2 + d3 + d4, -d1 + d2 - d3 + d4});
}

inline Vec3 shade_hit(float hx, float hy, float hz, const Vec3& n, int mat, float ao, float sh, bool allow_bounce) {
    float ndv = clampf(n.z, 0.0f, 1.0f);
    Vec3 refl = {2.0f * n.z * n.x, 2.0f * n.z * n.y, 2.0f * n.z * n.z - 1.0f};

    float fresnel = std::pow(1.0f - ndv, 3.0f);
    float ndh1 = clampf(dot(n, H1), 0.0f, 1.0f);
    float ndh2 = clampf(dot(n, H2), 0.0f, 1.0f);
    float ndl1 = clampf(dot(n, L1), 0.0f, 1.0f);

    float ao_term = 0.38f + 0.62f * ao;
    float sh_term = 0.52f + 0.48f * sh;

    Vec3 env_sharp = env_y2k(refl.x, refl.y, refl.z, 1.0f);

    // True 2nd-bounce self-reflection for mirror chrome (makes jaw/neck/ear joints look photorealistic!)
    if (allow_bounce && mat == 0) {
        float bt = 0.035f;
        bool bhit = false;
        float bx = hx, by = hy, bz = hz;
        for (int s = 0; s < 22; ++s) {
            bx = hx + refl.x * bt;
            by = hy + refl.y * bt;
            bz = hz + refl.z * bt;
            float bd = eval_dist(bx, by, bz);
            if (bd < 0.0025f) { bhit = true; break; }
            bt += bd * 0.92f;
            if (bt > 1.35f) break;
        }
        if (bhit) {
            Vec3 bn = calc_normal(bx, by, bz, 0.0020f);
            Vec3 bcol = env_y2k(2.0f * bn.z * bn.x, 2.0f * bn.z * bn.y, 2.0f * bn.z * bn.z - 1.0f, 1.35f);
            float mix_b = 0.42f * (1.0f - sstep(0.4f, 1.35f, bt));
            env_sharp = env_sharp * (1.0f - mix_b) + bcol * mix_b;
        }
    }

    if (mat == 0) {
        // Mirror Liquid Chrome (Platinum-Silver)
        float sp = std::pow(ndh1, 165.0f) * 1.85f * sh + std::pow(ndh1, 24.0f) * 0.28f * sh + std::pow(ndh2, 95.0f) * 0.46f;
        float t_rim = sstep(-0.38f, 0.38f, n.x);
        Vec3 rim_col = Vec3{0.18f, 0.88f, 1.18f} * (1.0f - t_rim) + Vec3{0.52f, 0.44f, 1.14f} * t_rim;
        Vec3 c = (env_sharp * Vec3{0.94f, 0.98f, 1.06f} + Vec3{0.08f, 0.08f, 0.09f} * ndl1) * (ao_term * sh_term);
        c = c + Vec3{0.98f, 0.99f, 1.06f} * sp + rim_col * (fresnel * 0.52f * ao_term);
        return tonemap_y2k(c);
    }
    if (mat == 1) {
        // Deep Glossy Obsidian-Sapphire Curved Glass
        float sh_d = (hx * 0.60f - hy * 0.44f + 0.10f) / 0.38f;
        float sheen = std::exp(-sh_d * sh_d) * 0.14f;
        Vec3 gl_env = env_sharp * (0.045f + 0.28f * fresnel);
        float gl_sp = std::pow(ndh1, 200.0f) * 1.35f * sh;
        Vec3 c = Vec3{0.006f, 0.014f, 0.028f} + gl_env + Vec3{gl_sp, gl_sp, gl_sp} + Vec3{0.45f, 0.78f, 1.05f} * sheen;
        return tonemap_y2k(c);
    }
    if (mat == 2) {
        // Brushed Satin Titanium / Anodized Machined Metal (ear mounts & speaker bezels!)
        Vec3 env_satin = env_y2k(refl.x, refl.y, refl.z, 1.75f);
        float grain = 0.94f + 0.06f * std::sin(hy * 280.0f) * std::cos(hx * 12.0f);
        float sp = std::pow(ndh1, 52.0f) * 0.72f * sh + std::pow(ndh2, 32.0f) * 0.28f;
        Vec3 c = (env_satin * Vec3{0.70f, 0.78f, 0.89f} * grain + Vec3{0.14f, 0.14f, 0.16f} * ndl1) * (ao_term * sh_term) + Vec3{sp, sp, sp};
        return tonemap_y2k(c);
    }
    if (mat == 5) {
        // Luminous Cyber-Cyan Optical Light-Pipe
        float core = 0.58f + 0.56f * ndv;
        float sp = std::pow(ndh1, 75.0f) * 0.95f;
        Vec3 c = Vec3{0.20f, 1.12f, 1.50f} * core + Vec3{sp, sp, sp};
        return tonemap_y2k(c);
    }
    // Mat 6: Deep Anodized Obsidian-Gunmetal Acoustic Cavity
    Vec3 env_satin = env_y2k(refl.x, refl.y, refl.z, 1.80f);
    float sp = std::pow(ndh1, 65.0f) * 0.30f;
    Vec3 c = (env_satin * Vec3{0.09f, 0.12f, 0.17f} + Vec3{sp, sp, sp}) * ao_term;
    return tonemap_y2k(c);
}

int main(int argc, char** argv) {
    if (argc < 4) {
        std::fprintf(stderr, "Usage: %s <W> <H> <out.raw> [threads]\n", argv[0]);
        return 1;
    }
    const int W = std::atoi(argv[1]);
    const int H = std::atoi(argv[2]);
    const char* out_path = argv[3];
    int n_threads = (argc >= 5) ? std::atoi(argv[4]) : (int)std::thread::hardware_concurrency();
    if (n_threads < 1) n_threads = 4;

    const float S = W / WORLD_W;
    const float px_world = 1.0f / S;
    const float z_start = 2.4f;
    const float max_dist = 4.6f;
    const int max_steps = 120;
    const float eps = 0.00055f;
    const float norm_e = 0.0011f;

    std::vector<uint8_t> rgba((size_t)W * H * 4, 0);
    std::atomic<int> next_row{0};

    auto worker = [&]() {
        while (true) {
            int i = next_row.fetch_add(1);
            if (i >= H) break;
            float y = (YC * S - (i + 0.5f)) / S;
            if (y >= 1.58f) continue;

            uint8_t* row_ptr = rgba.data() + (size_t)i * W * 4;
            for (int j = 0; j < W; ++j) {
                float x = (j + 0.5f - W * 0.5f) / S;
                if (std::fabs(x) >= 1.62f) continue;

                float t = 0.0f;
                float mind = 9.0f;
                bool hit = false;
                for (int s = 0; s < max_steps; ++s) {
                    float z = z_start - t;
                    float d = eval_dist(x, y, z);
                    if (d < mind) mind = d;
                    if (d < eps) { hit = true; break; }
                    t += d * 0.94f;
                    if (t >= max_dist) break;
                }

                float aa = clampf(1.0f - mind / (px_world * 1.15f), 0.0f, 1.0f);
                float alpha = hit ? 1.0f : aa;
                if (alpha <= 0.002f) continue;

                Vec3 col = {0.48f, 0.62f, 0.80f};
                if (hit) {
                    float hz = z_start - t;
                    SceneHit sh_hit = eval_scene(x, y, hz);
                    Vec3 n = calc_normal(x, y, hz, norm_e);

                    // 5-tap multi-scale Ambient Occlusion
                    float ao = 1.0f;
                    float sca = 1.0f;
                    const float ao_steps[5] = {0.018f, 0.055f, 0.12f, 0.22f, 0.36f};
                    for (int k = 0; k < 5; ++k) {
                        float step_h = ao_steps[k];
                        float dd = eval_dist(x + n.x * step_h, y + n.y * step_h, hz + n.z * step_h);
                        ao -= clampf(step_h - dd, 0.0f, 1.0f) * sca * 1.75f;
                        sca *= 0.60f;
                    }
                    ao = clampf(ao, 0.24f, 1.0f);

                    // 12-step soft key shadow
                    float sh = 1.0f;
                    float st = 0.035f;
                    for (int k = 0; k < 12; ++k) {
                        float sd = eval_dist(x + L1.x * st, y + L1.y * st, hz + L1.z * st);
                        sh = std::min(sh, clampf(5.6f * sd / st, 0.0f, 1.0f));
                        st += clampf(sd, 0.03f, 0.18f);
                        if (sh < 0.28f || st > 2.2f) break;
                    }
                    sh = clampf(sh, 0.30f, 1.0f);

                    col = shade_hit(x, y, hz, n, sh_hit.mat, ao, sh, true);
                }

                row_ptr[j * 4 + 0] = (uint8_t)clampf(col.x * 255.0f + 0.5f, 0.0f, 255.0f);
                row_ptr[j * 4 + 1] = (uint8_t)clampf(col.y * 255.0f + 0.5f, 0.0f, 255.0f);
                row_ptr[j * 4 + 2] = (uint8_t)clampf(col.z * 255.0f + 0.5f, 0.0f, 255.0f);
                row_ptr[j * 4 + 3] = (uint8_t)clampf(alpha * 255.0f + 0.5f, 0.0f, 255.0f);
            }
        }
    };

    std::vector<std::thread> pool;
    for (int t = 0; t < n_threads; ++t) pool.emplace_back(worker);
    for (auto& th : pool) th.join();

    std::ofstream ofs(out_path, std::ios::binary);
    if (!ofs) {
        std::fprintf(stderr, "Failed to open %s for writing\n", out_path);
        return 2;
    }
    ofs.write(reinterpret_cast<const char*>(rgba.data()), rgba.size());
    return 0;
}
