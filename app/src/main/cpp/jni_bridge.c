#include <jni.h>
#include <stdint.h>
#include "miner_core.h"

JNIEXPORT jint JNICALL Java_com_lottery_miner_Native_initCore(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    miner_core_init();
    if (!miner_core_selftest_ok()) return -1;
    return miner_core_uses_hw();
}

JNIEXPORT jboolean JNICALL Java_com_lottery_miner_Native_usesArmPairScan(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return miner_core_uses_arm_pair_scan() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_com_lottery_miner_Native_scan(JNIEnv *env, jobject thiz, jbyteArray jheader,
                                                          jint start, jint count, jbyteArray jtarget,
                                                          jintArray jfound, jdoubleArray jbest) {
    (void)thiz;
    if (jheader == NULL || jtarget == NULL || jfound == NULL || jbest == NULL) return -1;
    if ((*env)->GetArrayLength(env, jheader) != 80 ||
        (*env)->GetArrayLength(env, jtarget) != 32 ||
        (*env)->GetArrayLength(env, jfound) < 8 ||
        (*env)->GetArrayLength(env, jbest) < 3) return -1;
    if (count <= 0) return -1;
    uint64_t start_u = (uint32_t)start;
    if (start_u + (uint64_t)(uint32_t)count > 0x100000000ULL) return -1;

    uint8_t header[80], target[32];
    (*env)->GetByteArrayRegion(env, jheader, 0, 80, (jbyte *)header);
    if ((*env)->ExceptionCheck(env)) return -1;
    (*env)->GetByteArrayRegion(env, jtarget, 0, 32, (jbyte *)target);
    if ((*env)->ExceptionCheck(env)) return -1;

    uint32_t found[8];
    double best = 0.0;
    uint32_t best_nonce = 0, overflow = 0;
    uint32_t nf = miner_scan(header, (uint32_t)start, (uint32_t)count, target, found, 8, &best, &best_nonce, &overflow);

    if (nf > 0) {
        (*env)->SetIntArrayRegion(env, jfound, 0, (jsize)nf, (const jint *)found);
        if ((*env)->ExceptionCheck(env)) return -1;
    }
    jdouble out[3] = {best, (jdouble)best_nonce, (jdouble)overflow};
    (*env)->SetDoubleArrayRegion(env, jbest, 0, 3, out);
    if ((*env)->ExceptionCheck(env)) return -1;
    return (jint)nf;
}

JNIEXPORT jbyteArray JNICALL Java_com_lottery_miner_Native_sha256dHeader(JNIEnv *env, jobject thiz,
                                                                         jbyteArray jheader) {
    (void)thiz;
    if (jheader == NULL || (*env)->GetArrayLength(env, jheader) != 80) return NULL;
    uint8_t header[80], out[32];
    (*env)->GetByteArrayRegion(env, jheader, 0, 80, (jbyte *)header);
    if ((*env)->ExceptionCheck(env)) return NULL;
    miner_sha256d_header(header, out);
    jbyteArray res = (*env)->NewByteArray(env, 32);
    if (res == NULL || (*env)->ExceptionCheck(env)) return NULL;
    (*env)->SetByteArrayRegion(env, res, 0, 32, (const jbyte *)out);
    if ((*env)->ExceptionCheck(env)) return NULL;
    return res;
}
