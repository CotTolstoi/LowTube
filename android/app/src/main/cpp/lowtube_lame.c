#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "lame.h"

JNIEXPORT jlong JNICALL Java_com_lowtube_android_downloads_LameMp3Encoder_nativeOpen
  (JNIEnv *env, jobject self, jint sample_rate, jint channels, jint bitrate) {
    (void)env; (void)self;
    lame_t gfp = lame_init();
    if (!gfp) return 0;
    lame_set_in_samplerate(gfp, sample_rate);
    lame_set_num_channels(gfp, channels);
    lame_set_out_samplerate(gfp, sample_rate <= 24000 ? sample_rate : 44100);
    lame_set_brate(gfp, bitrate);
    lame_set_mode(gfp, channels == 1 ? MONO : JOINT_STEREO);
    lame_set_quality(gfp, 5);
    if (lame_init_params(gfp) < 0) { lame_close(gfp); return 0; }
    return (jlong)(intptr_t)gfp;
}

JNIEXPORT jbyteArray JNICALL Java_com_lowtube_android_downloads_LameMp3Encoder_nativeEncode
  (JNIEnv *env, jobject self, jlong handle, jshortArray pcm, jint frames) {
    (void)self;
    lame_t gfp = (lame_t)(intptr_t)handle;
    if (!gfp || frames <= 0) return (*env)->NewByteArray(env, 0);
    const int channels = lame_get_num_channels(gfp);
    const int capacity = (int)(1.25 * frames + 7200);
    short *samples = (*env)->GetShortArrayElements(env, pcm, NULL);
    unsigned char *encoded = (unsigned char *)malloc((size_t)capacity);
    if (!samples || !encoded) { if (samples) (*env)->ReleaseShortArrayElements(env, pcm, samples, JNI_ABORT); free(encoded); return NULL; }
    int bytes = channels == 2
      ? lame_encode_buffer_interleaved(gfp, samples, frames, encoded, capacity)
      : lame_encode_buffer(gfp, samples, samples, frames, encoded, capacity);
    (*env)->ReleaseShortArrayElements(env, pcm, samples, JNI_ABORT);
    if (bytes < 0) { free(encoded); return NULL; }
    jbyteArray result = (*env)->NewByteArray(env, bytes);
    if (result && bytes > 0) (*env)->SetByteArrayRegion(env, result, 0, bytes, (const jbyte *)encoded);
    free(encoded);
    return result;
}

JNIEXPORT jbyteArray JNICALL Java_com_lowtube_android_downloads_LameMp3Encoder_nativeFlush
  (JNIEnv *env, jobject self, jlong handle) {
    (void)self;
    lame_t gfp = (lame_t)(intptr_t)handle;
    if (!gfp) return (*env)->NewByteArray(env, 0);
    unsigned char encoded[7200];
    int bytes = lame_encode_flush(gfp, encoded, (int)sizeof(encoded));
    if (bytes < 0) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, bytes);
    if (result && bytes > 0) (*env)->SetByteArrayRegion(env, result, 0, bytes, (const jbyte *)encoded);
    return result;
}

JNIEXPORT void JNICALL Java_com_lowtube_android_downloads_LameMp3Encoder_nativeClose
  (JNIEnv *env, jobject self, jlong handle) {
    (void)env; (void)self;
    lame_t gfp = (lame_t)(intptr_t)handle;
    if (gfp) lame_close(gfp);
}
