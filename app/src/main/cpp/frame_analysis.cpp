#include "frame_analysis.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>

namespace auralcam {
namespace {

// Zebra stripe colour (ARGB). Change this to restyle the stripes.
constexpr uint32_t kZebraArgb = 0xE0FF2A2A;

// RGBA_8888 on little-endian ARM is A<<24 | B<<16 | G<<8 | R, with colour premultiplied by alpha.
inline uint32_t premultiplyToRgba(uint32_t argb) {
    uint32_t a = (argb >> 24) & 0xFF;
    uint32_t r = (argb >> 16) & 0xFF;
    uint32_t g = (argb >> 8) & 0xFF;
    uint32_t b = argb & 0xFF;
    r = r * a / 255;
    g = g * a / 255;
    b = b * a / 255;
    return (a << 24) | (b << 16) | (g << 8) | r;
}

inline int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

}  // namespace

void outputDims(int w, int h, int rot, int &outW, int &outH) {
    const int r = ((rot % 360) + 360) % 360;
    if (r == 90 || r == 270) {
        outW = h;
        outH = w;
    } else {
        outW = w;
        outH = h;
    }
}

bool analyzeYuv(const AnalysisInput &in, AnalysisOutput &out) {
    const int W = in.width;
    const int H = in.height;
    if (W < 2 || H < 2 || in.yRowStride < W) return false;
    if (in.yCap < static_cast<size_t>(H - 1) * in.yRowStride + W) return false;
    if (in.uCap == 0 || in.vCap == 0 || in.uvPixelStride < 1) return false;

    const int rot = ((in.rotationDeg % 360) + 360) % 360;
    int outW = 0, outH = 0;
    outputDims(W, H, rot, outW, outH);

    // Source (sx, sy) -> upright output (ox, oy) as an affine map.
    int ox0 = 0, oxx = 1, oxy = 0, oy0 = 0, oyx = 0, oyy = 1;
    switch (rot) {
        case 90:  ox0 = H - 1; oxx = 0;  oxy = -1; oy0 = 0;     oyx = 1;  oyy = 0;  break;
        case 180: ox0 = W - 1; oxx = -1; oxy = 0;  oy0 = H - 1; oyx = 0;  oyy = -1; break;
        case 270: ox0 = 0;     oxx = 0;  oxy = 1;  oy0 = W - 1; oyx = -1; oyy = 0;  break;
        default: break;
    }

    // Metered rectangle in output pixels.
    int mx0 = std::clamp(static_cast<int>(std::floor(in.meter[0] * outW)), 0, outW - 1);
    int my0 = std::clamp(static_cast<int>(std::floor(in.meter[1] * outH)), 0, outH - 1);
    int mx1 = std::clamp(static_cast<int>(std::ceil(in.meter[2] * outW)), 0, outW);
    int my1 = std::clamp(static_cast<int>(std::ceil(in.meter[3] * outH)), 0, outH);
    if (mx1 <= mx0) mx1 = std::min(outW, mx0 + 1);
    if (my1 <= my0) my1 = std::min(outH, my0 + 1);

    std::memset(out.hist, 0, sizeof(out.hist));
    int32_t *yHist = out.hist;
    int32_t *rHist = out.hist + 256;
    int32_t *gHist = out.hist + 512;
    int32_t *bHist = out.hist + 768;

    const bool drawOverlay = out.overlay != nullptr && out.overlayWidth == outW && out.overlayHeight == outH;
    const uint32_t peakPx = premultiplyToRgba(in.peakColorArgb);
    const uint32_t zebraPx = premultiplyToRgba(kZebraArgb);
    const int peakThr = std::max(1, in.peakThreshold);

    int64_t sumAll = 0, meterSum = 0;
    int64_t meterCnt = 0, hiCnt = 0, loCnt = 0, peakCnt = 0;

    for (int sy = 0; sy < H; ++sy) {
        const uint8_t *row = in.y + static_cast<size_t>(sy) * in.yRowStride;
        const uint8_t *rowUp = sy > 0 ? row - in.yRowStride : row;
        const uint8_t *rowDn = sy < H - 1 ? row + in.yRowStride : row;
        const size_t chromaRow = static_cast<size_t>(sy >> 1) * in.uvRowStride;

        for (int sx = 0; sx < W; ++sx) {
            const int c = row[sx];
            yHist[c]++;
            sumAll += c;
            if (c >= 250) hiCnt++;
            if (c <= 5) loCnt++;

            const int ox = ox0 + oxx * sx + oxy * sy;
            const int oy = oy0 + oyx * sx + oyy * sy;
            if (ox >= mx0 && ox < mx1 && oy >= my0 && oy < my1) {
                meterSum += c;
                meterCnt++;
            }

            // RGB histogram on every second pixel (one chroma sample per 2x2 block). BT.601 full range.
            if (((sx | sy) & 1) == 0) {
                size_t ui = chromaRow + static_cast<size_t>(sx >> 1) * in.uvPixelStride;
                size_t vi = ui;
                if (ui >= in.uCap) ui = in.uCap - 1;
                if (vi >= in.vCap) vi = in.vCap - 1;
                const int d = in.u[ui] - 128;
                const int e = in.v[vi] - 128;
                rHist[clamp255(c + ((359 * e) >> 8))]++;
                gHist[clamp255(c - ((88 * d + 183 * e) >> 8))]++;
                bHist[clamp255(c + ((454 * d) >> 8))]++;
            }

            if (drawOverlay) {
                uint32_t px = 0;
                bool isPeak = false;
                if (in.peaking && c > 16) {
                    const int l = row[sx > 0 ? sx - 1 : sx];
                    const int r = row[sx < W - 1 ? sx + 1 : sx];
                    // Laplacian: strong only where the image has fine, in-focus detail.
                    const int lap = std::abs(4 * c - l - r - rowUp[sx] - rowDn[sx]);
                    if (lap >= peakThr) {
                        isPeak = true;
                        peakCnt++;
                    }
                }
                if (isPeak) {
                    px = peakPx;
                } else if (in.zebra && c >= in.zebraThreshold && (((ox + oy + in.zebraPhase) >> 2) & 1)) {
                    px = zebraPx;
                }
                *reinterpret_cast<uint32_t *>(out.overlay + static_cast<size_t>(oy) * out.overlayStride +
                                              static_cast<size_t>(ox) * 4) = px;
            }
        }
    }

    const double total = static_cast<double>(W) * H;
    const double mean = static_cast<double>(sumAll) / (total * 255.0);
    std::memset(out.stats, 0, sizeof(out.stats));
    out.stats[0] = meterCnt > 0 ? static_cast<float>(meterSum / (static_cast<double>(meterCnt) * 255.0))
                                : static_cast<float>(mean);
    out.stats[1] = static_cast<float>(100.0 * hiCnt / total);
    out.stats[2] = static_cast<float>(100.0 * loCnt / total);
    out.stats[3] = static_cast<float>(mean);
    out.stats[4] = static_cast<float>(100.0 * peakCnt / total);
    return true;
}

}  // namespace auralcam