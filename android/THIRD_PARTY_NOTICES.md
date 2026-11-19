# Android third-party notices

## LAME 4.0

LowTube Android includes a separately linked `libmp3lame.so` built from the official LAME 4.0 source distribution. LAME is licensed under LGPL; its original `COPYING`, `LICENSE`, and `README` are retained in `app/src/main/cpp/third_party/lame/`. LowTube's JNI bridge is a separate shared library (`liblowtube_lame.so`) linked to LAME at runtime.

Source: https://sourceforge.net/projects/lame/files/lame/4.0/lame-4.0.tar.gz/download

Source archive SHA-256: `3df5124d5ad3a98312ffd7ba6a9b36230e4f8a3e66d3ce0f425e336c32d216eb`.

The encoder uses LAME's encoding library only; its optional MP3 decoder implementation is not included. Audio decoding uses Android MediaCodec.
