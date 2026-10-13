#!/bin/bash
# 给 audio_alsa.c 打补丁: 设置 start_threshold (环境变量 SP_START_FRAMES, 默认 4096)
# 原因: 此 Allwinner I2S 驱动在只写入少量帧(352)就启动时, DMA 会预取整个 period 导致立刻 XRUN
set -e
F="${1:-audio_alsa.c}"
grep -q SP_START_FRAMES "$F" && exit 0
perl -0pi -e 's|  /\* write the sw parameters \*/|  {\n    const char *sp_e = getenv("SP_START_FRAMES");\n    snd_pcm_uframes_t sp_st = sp_e ? (snd_pcm_uframes_t)strtoul(sp_e, NULL, 10) : 4096;\n    if (sp_st > 0) {\n      int sp_r = snd_pcm_sw_params_set_start_threshold(alsa_handle, alsa_swparams, sp_st);\n      debug(1, "alsa: start_threshold set to %lu frames (ret %d)", (unsigned long)sp_st, sp_r);\n    }\n  }\n\n  /* write the sw parameters */|' "$F"
grep -q SP_START_FRAMES "$F"
