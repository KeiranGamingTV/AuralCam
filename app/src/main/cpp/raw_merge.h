#pragma once

#include <cstddef>
#include <cstdint>

namespace auralcam {

struct RawMerger;

/** Returns null if the geometry is unsupported or memory runs out (about 4 bytes per sensor pixel). */
RawMerger *mergerCreate(int width, int height, int frames);

/** Adds one RAW16 frame. The first frame is the reference; later frames are aligned to it. */
bool mergerAdd(RawMerger *m, const uint8_t *data, size_t capacity, int rowStrideBytes);

/** Copies the merged frame (tightly packed, width*2 stride) into [out]. */
bool mergerFinish(RawMerger *m, uint8_t *out, size_t outCapacity);

void mergerDestroy(RawMerger *m);

}  // namespace auralcam