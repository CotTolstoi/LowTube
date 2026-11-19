cmake_minimum_required(VERSION 3.22.1)
project(lowtube_native_audio C)

set(LAME_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/lame)
set(LAME_SOURCES
    VbrTag.c bitstream.c encoder.c fft.c gain_analysis.c id3tag.c lame.c
    newmdct.c presets.c psymodel.c quantize.c quantize_pvt.c reservoir.c
    set_get.c tables.c takehiro.c util.c vbrquantize.c version.c)
list(TRANSFORM LAME_SOURCES PREPEND "${LAME_ROOT}/libmp3lame/")

# Keep LAME as a separate LGPL shared library so it can be replaced independently.
add_library(mp3lame SHARED ${LAME_SOURCES})
target_include_directories(mp3lame PUBLIC ${LAME_ROOT}/include PRIVATE ${LAME_ROOT}/libmp3lame)
target_compile_definitions(mp3lame PRIVATE STDC_HEADERS HAVE_STDINT_H HAVE_INTTYPES_H HAVE_STRING_H HAVE_STDLIB_H HAVE_MATH_H HAVE_ERRNO_H HAVE_FCNTL_H HAVE_UNISTD_H HAVE_STRCHR HAVE_MEMCPY HAVE_MEMSET HAVE_MALLOC HAVE_FREE HAVE_FLOOR HAVE_CEIL HAVE_SQRT HAVE_LOG HAVE_POW HAVE_SINF HAVE_COSF HAVE_FABS HAVE_FABSF HAVE_LRINT HAVE_LRINTF HAVE_ROUND HAVE_ROUNDF HAVE_ISFINITE HAVE_ISNAN HAVE_STDIO_H)
target_compile_options(mp3lame PRIVATE -include ${CMAKE_CURRENT_SOURCE_DIR}/lame_android_config.h)
target_compile_options(mp3lame PRIVATE -O2 -fPIC -Wno-unused-parameter -Wno-implicit-function-declaration)
target_link_libraries(mp3lame PRIVATE m)

add_library(lowtube_lame SHARED lowtube_lame.c)
target_include_directories(lowtube_lame PRIVATE ${LAME_ROOT}/include)
target_compile_options(lowtube_lame PRIVATE -O2 -fPIC)
target_link_libraries(lowtube_lame PRIVATE mp3lame log)
