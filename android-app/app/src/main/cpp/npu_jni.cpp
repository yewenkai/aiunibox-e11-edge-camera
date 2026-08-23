#include <jni.h>
#include <cstdint>
#include <vector>

#include "rknn_api.h"

namespace {

struct NpuCtx {
    rknn_context ctx = 0;
    uint32_t nInputs = 0;
    uint32_t nOutputs = 0;
    uint32_t inputWidth = 0;
    uint32_t inputHeight = 0;
    rknn_tensor_format inputFormat = RKNN_TENSOR_UNDEFINED;
};

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_e11camera_edge_NpuDetector_nativeCreate(
        JNIEnv *env, jobject /*thiz*/, jbyteArray modelBytes) {
    jsize len = env->GetArrayLength(modelBytes);
    if (len <= 0) return 0;
    jboolean isCopy = JNI_FALSE;
    jbyte *data = env->GetByteArrayElements(modelBytes, &isCopy);

    rknn_context ctx = 0;
    int ret = rknn_init(&ctx, data, len, 0, nullptr);
    env->ReleaseByteArrayElements(modelBytes, data, JNI_ABORT);
    if (ret != RKNN_SUCC || ctx == 0) return 0;

    auto *n = new NpuCtx();
    n->ctx = ctx;
    rknn_input_output_num io{};
    if (rknn_query(ctx, RKNN_QUERY_IN_OUT_NUM, &io, sizeof(io)) == RKNN_SUCC) {
        n->nInputs = io.n_input;
        n->nOutputs = io.n_output;
    }
    rknn_tensor_attr inputAttr{};
    inputAttr.index = 0;
    if (rknn_query(ctx, RKNN_QUERY_INPUT_ATTR, &inputAttr, sizeof(inputAttr)) == RKNN_SUCC) {
        n->inputFormat = inputAttr.fmt;
        if (inputAttr.fmt == RKNN_TENSOR_NCHW && inputAttr.n_dims >= 4) {
            n->inputHeight = inputAttr.dims[2];
            n->inputWidth = inputAttr.dims[3];
        } else if (inputAttr.fmt == RKNN_TENSOR_NHWC && inputAttr.n_dims >= 4) {
            n->inputHeight = inputAttr.dims[1];
            n->inputWidth = inputAttr.dims[2];
        }
    }
    return reinterpret_cast<jlong>(n);
}

/** 返回 int[6]：[ok, n_inputs, n_outputs, w, h, fmt] */
extern "C" JNIEXPORT jintArray JNICALL
Java_org_e11camera_edge_NpuDetector_nativeQuery(
        JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *n = reinterpret_cast<NpuCtx *>(handle);
    jint out[6] = {0, 0, 0, 0, 0, 0};
    if (n == nullptr) {
        jintArray arr = env->NewIntArray(6);
        env->SetIntArrayRegion(arr, 0, 6, out);
        return arr;
    }
    out[0] = n->nInputs == 1 && n->nOutputs > 0 &&
                     n->inputWidth > 0 && n->inputHeight > 0 ? 1 : 0;
    out[1] = static_cast<jint>(n->nInputs);
    out[2] = static_cast<jint>(n->nOutputs);
    out[3] = static_cast<jint>(n->inputWidth);
    out[4] = static_cast<jint>(n->inputHeight);
    out[5] = static_cast<jint>(n->inputFormat);
    jintArray arr = env->NewIntArray(6);
    env->SetIntArrayRegion(arr, 0, 6, out);
    return arr;
}

/** 输入 RGB u8（w*h*3），返回 float[][]（每个输出张量一行）。 */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_e11camera_edge_NpuDetector_nativeRun(
        JNIEnv *env, jobject /*thiz*/, jlong handle,
        jbyteArray input, jint width, jint height) {
    auto *n = reinterpret_cast<NpuCtx *>(handle);
    if (n == nullptr || n->nOutputs == 0 || n->nOutputs > 32 ||
        width <= 0 || height <= 0 ||
        static_cast<uint32_t>(width) != n->inputWidth ||
        static_cast<uint32_t>(height) != n->inputHeight) {
        return nullptr;
    }

    const jsize expectedSize = width * height * 3;
    if (env->GetArrayLength(input) != expectedSize) return nullptr;

    jboolean isCopy = JNI_FALSE;
    jbyte *data = env->GetByteArrayElements(input, &isCopy);

    rknn_input inputs[1]{};
    inputs[0].index = 0;
    inputs[0].type = RKNN_TENSOR_UINT8;
    inputs[0].fmt = RKNN_TENSOR_NHWC; // RGB 字节序，与模型转换时的输入约定一致
    inputs[0].size = static_cast<uint32_t>(width * height * 3);
    inputs[0].buf = data;
    if (rknn_inputs_set(n->ctx, 1, inputs) != RKNN_SUCC) {
        env->ReleaseByteArrayElements(input, data, JNI_ABORT);
        return nullptr;
    }
    if (rknn_run(n->ctx, nullptr) != RKNN_SUCC) {
        env->ReleaseByteArrayElements(input, data, JNI_ABORT);
        return nullptr;
    }
    env->ReleaseByteArrayElements(input, data, JNI_ABORT);

    uint32_t nOut = n->nOutputs;
    std::vector<rknn_output> outputs(nOut);
    for (uint32_t i = 0; i < nOut; i++) {
        outputs[i].index = i;
        outputs[i].want_float = 1;
    }
    if (rknn_outputs_get(n->ctx, nOut, outputs.data(), nullptr) != RKNN_SUCC) {
        return nullptr;
    }

    jclass floatArrCls = env->FindClass("[F");
    jobjectArray result = env->NewObjectArray(
            static_cast<jsize>(nOut), floatArrCls, nullptr);
    for (uint32_t i = 0; i < nOut; i++) {
        auto size = static_cast<jsize>(outputs[i].size / sizeof(float));
        jfloatArray row = env->NewFloatArray(size);
        env->SetFloatArrayRegion(row, 0, size,
                                 reinterpret_cast<const jfloat *>(outputs[i].buf));
        env->SetObjectArrayElement(result, static_cast<jsize>(i), row);
        env->DeleteLocalRef(row);
    }
    rknn_outputs_release(n->ctx, nOut, outputs.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_org_e11camera_edge_NpuDetector_nativeDestroy(
        JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *n = reinterpret_cast<NpuCtx *>(handle);
    if (n == nullptr) return;
    if (n->ctx != 0) rknn_destroy(n->ctx);
    delete n;
}

// PoseDetector 同时持有 Pose 与按需 RetinaFace 两个 RKNN context，复用同一薄 JNI 桥。
extern "C" JNIEXPORT jlong JNICALL
Java_org_e11camera_edge_PoseDetector_nativeCreate(
        JNIEnv *env, jobject thiz, jbyteArray modelBytes) {
    return Java_org_e11camera_edge_NpuDetector_nativeCreate(env, thiz, modelBytes);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_org_e11camera_edge_PoseDetector_nativeQuery(
        JNIEnv *env, jobject thiz, jlong handle) {
    return Java_org_e11camera_edge_NpuDetector_nativeQuery(env, thiz, handle);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_e11camera_edge_PoseDetector_nativeRun(
        JNIEnv *env, jobject thiz, jlong handle,
        jbyteArray input, jint width, jint height) {
    return Java_org_e11camera_edge_NpuDetector_nativeRun(
            env, thiz, handle, input, width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_org_e11camera_edge_PoseDetector_nativeDestroy(
        JNIEnv *env, jobject thiz, jlong handle) {
    Java_org_e11camera_edge_NpuDetector_nativeDestroy(env, thiz, handle);
}
