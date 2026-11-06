#include "raw_merge.h"

#include <android/log.h>

#include <algorithm>
#include <climits>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <new>
#include <thread>
#include <vector>

#define LOG_TAG "AuralCam-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

/*
 * Multi-frame RAW merge, designed to run streaming with about 4 bytes per sensor pixel:
 *
 *  1. Every frame is binned 2x2 into a "quad gray" plane. A 2x2 Bayer block holds all four colours
 *     in any CFA order, so this is CFA-independent. Alignment shifts are whole quads, which keeps
 *     the Bayer phase intact.
 *  2. Tile-based alignment against the first frame: 16x16-quad tiles, a 3-level pyramid, SAD search.
 *  3. Noise is estimated from the data (median of aligned differences, per brightness bucket), which
 *     sets how much difference counts as "same".
 *  4. Each aligned quad is merged into a running weighted average. Quads that differ more than the noise
 *     allows (moving subjects, bad alignment) get low or zero weight, so ghosts are rejected.
 */
namespace auralcam {
namespace {

constexpr int kTile = 16;            // tile size in quads (32 sensor pixels)
constexpr int kTileShift = 4;
constexpr int kCoarseRange = 4;      // +-4 at pyramid level 2 = +-16 quads
constexpr int kRefineRange = 1;
constexpr int kMargin = 2;           // SAD window grows by this many pixels (at each level) around a tile
constexpr int kBuckets = 8;
constexpr int kHistBins = 1024;
constexpr int kMaxFrames = 64;
constexpr int64_t kMaxPixels = 64LL * 1000 * 1000;
constexpr uint16_t kUnitWeight = 256;

struct Plane {
    int w = 0;
    int h = 0;
    std::vector<uint16_t> px;
    void alloc(int W, int H) {
        w = W;
        h = H;
        px.assign(static_cast<size_t>(W) * H, 0);
    }
};

template <class F>
void parallelRows(int rows, F &&f) {
    unsigned n = std::thread::hardware_concurrency();
    n = std::max(1u, std::min(n, 4u));
    if (n == 1 || rows < 32) {
        f(0, rows);
        return;
    }
    const int chunk = (rows + static_cast<int>(n) - 1) / static_cast<int>(n);
    std::vector<std::thread> threads;
    for (unsigned i = 1; i < n; ++i) {
        const int a = static_cast<int>(i) * chunk;
        const int b = std::min(rows, a + chunk);
        if (a >= b) break;
        threads.emplace_back([&f, a, b] { f(a, b); });
    }
    f(0, std::min(rows, chunk));
    for (auto &t : threads) t.join();
}

}  // namespace

struct RawMerger {
    int width = 0;
    int height = 0;
    int qw = 0;
    int qh = 0;
    int frames = 0;
    int added = 0;
    int tilesX = 0;
    int tilesY = 0;
    std::vector<uint16_t> acc;     // running weighted average, tightly packed
    std::vector<uint16_t> wsum;    // sum of weights per pixel (kUnitWeight = 1.0)
    Plane refL0, refL1, refL2;
    Plane curL0, curL1, curL2;
    std::vector<int16_t> vx, vy;   // per-tile shift in quads
    float invMaxRef = 1.0f;
    float invCut[kBuckets];
};

namespace {

void downsample2(const Plane &src, Plane &dst) {
    dst.alloc(std::max(1, src.w / 2), std::max(1, src.h / 2));
    for (int y = 0; y < dst.h; ++y) {
        const int y0 = std::min(2 * y, src.h - 1);
        const int y1 = std::min(2 * y + 1, src.h - 1);
        const uint16_t *r0 = &src.px[static_cast<size_t>(y0) * src.w];
        const uint16_t *r1 = &src.px[static_cast<size_t>(y1) * src.w];
        uint16_t *d = &dst.px[static_cast<size_t>(y) * dst.w];
        for (int x = 0; x < dst.w; ++x) {
            const int x0 = std::min(2 * x, src.w - 1);
            const int x1 = std::min(2 * x + 1, src.w - 1);
            d[x] = static_cast<uint16_t>((r0[x0] + r0[x1] + r1[x0] + r1[x1] + 2) >> 2);
        }
    }
}

void computeL0(const uint8_t *data, int rowStride, int qw, int qh, Plane &dst) {
    dst.alloc(qw, qh);
    parallelRows(qh, [&](int a, int b) {
        for (int qy = a; qy < b; ++qy) {
            const uint16_t *r0 = reinterpret_cast<const uint16_t *>(data + static_cast<size_t>(2 * qy) * rowStride);
            const uint16_t *r1 = reinterpret_cast<const uint16_t *>(data + static_cast<size_t>(2 * qy + 1) * rowStride);
            uint16_t *d = &dst.px[static_cast<size_t>(qy) * qw];
            for (int qx = 0; qx < qw; ++qx) {
                d[qx] = static_cast<uint16_t>((r0[2 * qx] + r0[2 * qx + 1] + r1[2 * qx] + r1[2 * qx + 1] + 2) >> 2);
            }
        }
    });
}

inline int64_t sadWindow(const Plane &ref, const Plane &cur, int x0, int y0, int x1, int y1,
                         int dx, int dy, int64_t limit) {
    if (x0 + dx < 0 || y0 + dy < 0 || x1 + dx > cur.w || y1 + dy > cur.h) return INT64_MAX;
    int64_t s = 0;
    const int n = x1 - x0;
    for (int y = y0; y < y1; ++y) {
        const uint16_t *a = &ref.px[static_cast<size_t>(y) * ref.w + x0];
        const uint16_t *b = &cur.px[static_cast<size_t>(y + dy) * cur.w + x0 + dx];
        for (int x = 0; x < n; ++x) s += std::abs(static_cast<int>(a[x]) - static_cast<int>(b[x]));
        if (s >= limit) return s;
    }
    return s;
}

void searchLevel(const Plane &ref, const Plane &cur, int tx, int ty, int tilesX, int tilesY,
                 int level, int cx, int cy, int range, int &ox, int &oy) {
    int x0 = (tx << kTileShift) >> level;
    int x1 = ((tx + 1) << kTileShift) >> level;
    int y0 = (ty << kTileShift) >> level;
    int y1 = ((ty + 1) << kTileShift) >> level;
    if (tx == tilesX - 1) x1 = ref.w;
    if (ty == tilesY - 1) y1 = ref.h;
    x0 = std::max(0, x0 - kMargin);
    y0 = std::max(0, y0 - kMargin);
    x1 = std::min(ref.w, x1 + kMargin);
    y1 = std::min(ref.h, y1 + kMargin);
    ox = cx;
    oy = cy;
    if (x1 <= x0 || y1 <= y0) return;

    // A small penalty keeps flat, textureless tiles from drifting away from the predicted motion.
    const int64_t pen = std::max<int64_t>(1, static_cast<int64_t>(x1 - x0) * (y1 - y0) / 4);
    int64_t best = INT64_MAX;
    for (int dy = -range; dy <= range; ++dy) {
        for (int dx = -range; dx <= range; ++dx) {
            const int64_t pc = pen * (std::abs(dx) + std::abs(dy));
            const int64_t limit = best == INT64_MAX ? INT64_MAX : std::max<int64_t>(0, best - pc);
            const int64_t s = sadWindow(ref, cur, x0, y0, x1, y1, cx + dx, cy + dy, limit);
            if (s == INT64_MAX) continue;
            const int64_t cost = s + pc;
            if (cost < best) {
                best = cost;
                ox = cx + dx;
                oy = cy + dy;
            }
        }
    }
}

void alignTiles(RawMerger &m) {
    const int tiles = m.tilesX * m.tilesY;
    parallelRows(m.tilesY, [&](int a, int b) {
        for (int ty = a; ty < b; ++ty) {
            for (int tx = 0; tx < m.tilesX; ++tx) {
                int x2, y2, x1, y1, x0, y0;
                searchLevel(m.refL2, m.curL2, tx, ty, m.tilesX, m.tilesY, 2, 0, 0, kCoarseRange, x2, y2);
                searchLevel(m.refL1, m.curL1, tx, ty, m.tilesX, m.tilesY, 1, 2 * x2, 2 * y2, kRefineRange, x1, y1);
                searchLevel(m.refL0, m.curL0, tx, ty, m.tilesX, m.tilesY, 0, 2 * x1, 2 * y1, kRefineRange, x0, y0);
                const int ti = ty * m.tilesX + tx;
                m.vx[ti] = static_cast<int16_t>(x0);
                m.vy[ti] = static_cast<int16_t>(y0);
            }
        }
    });
    (void)tiles;
}

inline int bucketOf(const RawMerger &m, int v) {
    const int b = static_cast<int>(std::sqrt(static_cast<float>(v) * m.invMaxRef) * kBuckets);
    return std::min(b, kBuckets - 1);
}

float medianOf(const uint32_t *hist, uint64_t total) {
    if (total == 0) return 2.0f;
    uint64_t run = 0;
    for (int i = 0; i < kHistBins; ++i) {
        run += hist[i];
        if (run * 2 >= total) return static_cast<float>(i) + 0.5f;
    }
    return static_cast<float>(kHistBins);
}

/** Per-brightness noise level from the aligned frame-to-reference differences. */
void estimateNoise(RawMerger &m) {
    std::vector<uint32_t> hist(static_cast<size_t>(kBuckets) * kHistBins, 0);
    std::vector<uint32_t> all(kHistBins, 0);
    uint64_t counts[kBuckets] = {0};
    uint64_t total = 0;

    for (int qy = 0; qy < m.qh; qy += 2) {
        const int ty = std::min(qy >> kTileShift, m.tilesY - 1);
        for (int qx = 0; qx < m.qw; qx += 2) {
            const int tx = std::min(qx >> kTileShift, m.tilesX - 1);
            const int ti = ty * m.tilesX + tx;
            const int sx = qx + m.vx[ti];
            const int sy = qy + m.vy[ti];
            if (sx < 0 || sy < 0 || sx >= m.qw || sy >= m.qh) continue;
            const int rv = m.refL0.px[static_cast<size_t>(qy) * m.qw + qx];
            const int cv = m.curL0.px[static_cast<size_t>(sy) * m.qw + sx];
            const int d = std::min(std::abs(rv - cv), kHistBins - 1);
            const int b = bucketOf(m, rv);
            hist[static_cast<size_t>(b) * kHistBins + d]++;
            all[d]++;
            counts[b]++;
            total++;
        }
    }

    const float globalMedian = medianOf(all.data(), total);
    for (int b = 0; b < kBuckets; ++b) {
        const float med = counts[b] >= 200 ? medianOf(&hist[static_cast<size_t>(b) * kHistBins], counts[b])
                                           : globalMedian;
        const float sigma = med / 0.6745f;                 // median |N(0, s)| = 0.6745 s
        const float cut = std::max(3.0f * sigma, 6.0f);    // reject differences beyond ~3 sigma
        m.invCut[b] = 1.0f / cut;
    }
}

void mergeFrame(RawMerger &m, const uint8_t *data, int rowStride) {
    parallelRows(m.qh, [&](int a, int b) {
        for (int qy = a; qy < b; ++qy) {
            const int ty = std::min(qy >> kTileShift, m.tilesY - 1);
            for (int qx = 0; qx < m.qw; ++qx) {
                const int tx = std::min(qx >> kTileShift, m.tilesX - 1);
                const int ti = ty * m.tilesX + tx;
                const int sqx = qx + m.vx[ti];
                const int sqy = qy + m.vy[ti];
                if (sqx < 0 || sqy < 0 || sqx >= m.qw || sqy >= m.qh) continue;

                const int rv = m.refL0.px[static_cast<size_t>(qy) * m.qw + qx];
                const int cv = m.curL0.px[static_cast<size_t>(sqy) * m.qw + sqx];
                const float t = std::abs(rv - cv) * m.invCut[bucketOf(m, rv)];
                if (t >= 1.0f) continue;
                const uint32_t w = static_cast<uint32_t>(256.0f * (1.0f - t * t));
                if (w == 0) continue;

                for (int j = 0; j < 2; ++j) {
                    const uint16_t *src = reinterpret_cast<const uint16_t *>(
                            data + static_cast<size_t>(2 * sqy + j) * rowStride) + 2 * sqx;
                    const size_t di = static_cast<size_t>(2 * qy + j) * m.width + 2 * qx;
                    for (int i = 0; i < 2; ++i) {
                        const uint32_t ws = m.wsum[di + i];
                        const uint32_t nw = ws + w;
                        const uint32_t v = (static_cast<uint32_t>(m.acc[di + i]) * ws + src[i] * w + nw / 2) / nw;
                        m.acc[di + i] = static_cast<uint16_t>(v);
                        m.wsum[di + i] = static_cast<uint16_t>(nw);
                    }
                }
            }
        }
    });
}

void buildPyramid(const Plane &l0, Plane &l1, Plane &l2) {
    downsample2(l0, l1);
    downsample2(l1, l2);
}

}  // namespace

RawMerger *mergerCreate(int width, int height, int frames) {
    if (width < 64 || height < 64 || frames < 2 || frames > kMaxFrames) {
        LOGW("merge: unsupported geometry %dx%d, %d frames", width, height, frames);
        return nullptr;
    }
    if (static_cast<int64_t>(width) * height > kMaxPixels) {
        LOGW("merge: %dx%d is too large for the low-light merge", width, height);
        return nullptr;
    }
    try {
        std::unique_ptr<RawMerger> m(new RawMerger());
        m->width = width;
        m->height = height;
        m->qw = width / 2;
        m->qh = height / 2;
        m->frames = frames;
        m->tilesX = (m->qw + kTile - 1) / kTile;
        m->tilesY = (m->qh + kTile - 1) / kTile;
        m->acc.resize(static_cast<size_t>(width) * height);
        m->wsum.assign(static_cast<size_t>(width) * height, kUnitWeight);
        m->vx.assign(static_cast<size_t>(m->tilesX) * m->tilesY, 0);
        m->vy.assign(static_cast<size_t>(m->tilesX) * m->tilesY, 0);
        for (float &c : m->invCut) c = 1.0f / 6.0f;
        LOGI("merge begin: %dx%d, %d frames, %d x %d tiles", width, height, frames, m->tilesX, m->tilesY);
        return m.release();
    } catch (const std::bad_alloc &) {
        LOGW("merge: out of memory for %dx%d", width, height);
        return nullptr;
    }
}

bool mergerAdd(RawMerger *m, const uint8_t *data, size_t capacity, int rowStrideBytes) {
    if (!m || !data || m->added >= kMaxFrames) return false;
    if (rowStrideBytes < m->width * 2 || (rowStrideBytes & 1) != 0) return false;
    if (capacity < static_cast<size_t>(m->height - 1) * rowStrideBytes + static_cast<size_t>(m->width) * 2) return false;

    try {
        if (m->added == 0) {
            for (int y = 0; y < m->height; ++y) {
                std::memcpy(&m->acc[static_cast<size_t>(y) * m->width],
                            data + static_cast<size_t>(y) * rowStrideBytes,
                            static_cast<size_t>(m->width) * 2);
            }
            computeL0(data, rowStrideBytes, m->qw, m->qh, m->refL0);
            buildPyramid(m->refL0, m->refL1, m->refL2);
            int maxRef = 1;
            for (uint16_t v : m->refL1.px) maxRef = std::max<int>(maxRef, v);
            m->invMaxRef = 1.0f / static_cast<float>(maxRef);
            m->added = 1;
            return true;
        }

        computeL0(data, rowStrideBytes, m->qw, m->qh, m->curL0);
        buildPyramid(m->curL0, m->curL1, m->curL2);
        alignTiles(*m);
        estimateNoise(*m);
        mergeFrame(*m, data, rowStrideBytes);
        m->added++;
        return true;
    } catch (const std::bad_alloc &) {
        LOGW("merge: out of memory while adding a frame");
        return false;
    }
}

bool mergerFinish(RawMerger *m, uint8_t *out, size_t outCapacity) {
    if (!m || !out || m->added == 0) return false;
    const size_t bytes = static_cast<size_t>(m->width) * m->height * 2;
    if (outCapacity < bytes) return false;
    std::memcpy(out, m->acc.data(), bytes);
    LOGI("merge finished: %d frames", m->added);
    return true;
}

void mergerDestroy(RawMerger *m) { delete m; }

}  // namespace auralcam