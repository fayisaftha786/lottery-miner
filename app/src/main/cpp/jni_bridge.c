#include <jni.h>
#include <stdint.h>
#include "miner_core.h"

JNIEXPORT jint JNICALL Java_com_lottery_miner_Native_initCore(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    miner_core_init();
    if (!miner_core_selftest_ok()) return -1;
    return miner_core_uses_hw();
}

JNIEXPORT jint JNICALL Java_com_lottery_miner_Native_scan(JNIEnv *env, jobject thiz, jbyteArray jheader,
                                                          jint start, jint count, jbyteArray jtarget,
                                                          jintArray jfound, jdoubleArray jbest) {
    (void)thiz;
    uint8_t header[80], target[32];
    (*env)->GetByteArrayRegion(env, jheader, 0, 80, (jbyte *)header);
    (*env)->GetByteArrayRegion(env, jtarget, 0, 32, (jbyte *)target);

    uint32_t found[8];
    double best = 0.0;
    uint32_t best_nonce = 0;
    uint32_t nf = miner_scan(header, (uint32_t)start, (uint32_t)count, target, found, 8, &best, &best_nonce);

    if (nf > 0) (*env)->SetIntArrayRegion(env, jfound, 0, (jsize)nf, (const jint *)found);
    jdouble out[2];
    out[0] = best;
    out[1] = (jdouble)best_nonce;
    (*env)->SetDoubleArrayRegion(env, jbest, 0, 2, out);
    return (jint)nf;
}

JNIEXPORT jbyteArray JNICALL Java_com_lottery_miner_Native_sha256dHeader(JNIEnv *env, jobject thiz,
                                                                         jbyteArray jheader) {
    (void)thiz;
    uint8_t header[80], out[32];
    (*env)->GetByteArrayRegion(env, jheader, 0, 80, (jbyte *)header);
    miner_sha256d_header(header, out);
    jbyteArray res = (*env)->NewByteArray(env, 32);
    (*env)->SetByteArrayRegion(env, res, 0, 32, (const jbyte *)out);
    return res;
}
