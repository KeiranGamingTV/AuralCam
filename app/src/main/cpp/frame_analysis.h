#pragma once

#include <cstddef>
#include <cstdint>

namespace auralcam {

struct AnalysisInput {
    const uint8_t *y;
    const uint8_t *u;
    const uint8_t *v;
    size_t yCap;
    size_t uCap;
    size_t vCap;
    int width;
    int height;
    int yRowStride;
    int uvRowStride;
    int uvPixelStride;
    int rotationDeg;      // clockwise, multiple of 90
    bool peaking;
    int peakThreshold;
    uint32_t peakColorArgb;
    bool zebra;
    int zebraThreshold;
    int zebraPhase;
    float meter[4];       // left, top, right, bottom in the upright image, 0..1
};

struct AnalysisOutput {
    int32_t hist[1024];   // luma, R, G, B (256 each)
    float stats[8];       // meterLuma, hi%, lo%, mean, peaking%
    uint8_t *overlay;     // RGBA_8888 premultiplied, upright; may be null
    int overlayStride;
    int overlayWidth;
    int overlayHeight;
};

/** Size of the upright output for a w x h sensor frame rotated by rot degrees. */
void outputDims(int w, int h, int rot, int &outW, int &outH);

bool analyzeYuv(const AnalysisInput &in, AnalysisOutput &out);

}  // namespace auralcam