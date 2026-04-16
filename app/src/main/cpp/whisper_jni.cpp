#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct ProgressState {
    JavaVM*   vm      = nullptr;
    jobject   jcb     = nullptr;  // global ref to ProgressCallback
    jmethodID onProg  = nullptr;
};

std::atomic_bool g_cancel{false};

void progress_cb(struct whisper_context*, struct whisper_state*, int progress, void* user_data) {
    auto* s = static_cast<ProgressState*>(user_data);
    if (!s || !s->vm || !s->jcb) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (s->vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (s->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    env->CallVoidMethod(s->jcb, s->onProg, progress);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) s->vm->DetachCurrentThread();
}

bool cancel_cb(void*) {
    return g_cancel.load();
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_subtitlecreator_jni_WhisperLib_nativeInitContext(
        JNIEnv* env, jobject /*thiz*/, jstring modelPath) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model: %s", path);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    // DTW disabled by default — split_on_word + max_len=1 gives native word segments
    // which are already accurate. Enable DTW here if you want sub-token precision.
    cparams.dtw_token_timestamps = false;

    whisper_context* ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!ctx) {
        LOGE("whisper_init_from_file_with_params failed");
        return 0;
    }
    LOGI("Model loaded ok (multilingual=%d, vocab=%d)",
         whisper_is_multilingual(ctx), whisper_n_vocab(ctx));
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_subtitlecreator_jni_WhisperLib_nativeFreeContext(
        JNIEnv*, jobject, jlong ctxPtr) {
    auto* ctx = reinterpret_cast<whisper_context*>(ctxPtr);
    if (ctx) whisper_free(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_subtitlecreator_jni_WhisperLib_nativeCancel(JNIEnv*, jobject) {
    g_cancel.store(true);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_subtitlecreator_jni_WhisperLib_nativeTranscribe(
        JNIEnv* env, jobject /*thiz*/,
        jlong ctxPtr,
        jfloatArray pcm,
        jstring language,
        jint nThreads,
        jboolean translate,
        jobject progressCb) {

    auto* ctx = reinterpret_cast<whisper_context*>(ctxPtr);
    if (!ctx) {
        LOGE("nativeTranscribe: null ctx");
        return nullptr;
    }

    g_cancel.store(false);

    jsize nSamples = env->GetArrayLength(pcm);
    jfloat* samples = env->GetFloatArrayElements(pcm, nullptr);

    const char* lang = env->GetStringUTFChars(language, nullptr);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.language          = lang;
    p.translate         = translate;
    p.n_threads         = nThreads > 0 ? nThreads : 4;
    p.print_realtime    = false;
    p.print_progress    = false;
    p.print_timestamps  = false;
    p.print_special     = false;
    p.no_context        = true;
    p.single_segment    = false;
    p.suppress_blank    = true;

    // === word-level timestamps: 1 segment per word with native start/end ===
    p.token_timestamps  = true;
    p.split_on_word     = true;
    p.max_len           = 1;
    p.thold_pt          = 0.01f;

    // progress & cancel hooks
    ProgressState pstate;
    pstate.jcb = nullptr;
    if (progressCb) {
        env->GetJavaVM(&pstate.vm);
        pstate.jcb = env->NewGlobalRef(progressCb);
        jclass cbCls = env->GetObjectClass(progressCb);
        pstate.onProg = env->GetMethodID(cbCls, "onProgress", "(I)V");
        p.progress_callback = progress_cb;
        p.progress_callback_user_data = &pstate;
    }
    p.abort_callback = cancel_cb;
    p.abort_callback_user_data = nullptr;

    LOGI("transcribe: samples=%d threads=%d lang=%s", nSamples, p.n_threads, lang);
    int rc = whisper_full(ctx, p, samples, nSamples);

    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);

    if (pstate.jcb) env->DeleteGlobalRef(pstate.jcb);

    if (rc != 0) {
        LOGE("whisper_full failed rc=%d", rc);
        return nullptr;
    }

    const int nSeg = whisper_full_n_segments(ctx);
    LOGI("got %d segments (word-level)", nSeg);

    jclass strCls = env->FindClass("java/lang/String");
    // We return a flat String[] of the form [text, startMs, endMs, text, startMs, endMs, ...]
    // cheaper than building a Java object per word; Kotlin wraps it into a data class.
    jobjectArray arr = env->NewObjectArray(nSeg * 3, strCls, nullptr);

    for (int i = 0; i < nSeg; ++i) {
        const char* txt = whisper_full_get_segment_text(ctx, i);
        // whisper timestamps are in 10ms units → convert to ms
        int64_t t0 = whisper_full_get_segment_t0(ctx, i) * 10;
        int64_t t1 = whisper_full_get_segment_t1(ctx, i) * 10;

        env->SetObjectArrayElement(arr, i * 3 + 0, env->NewStringUTF(txt));
        env->SetObjectArrayElement(arr, i * 3 + 1, env->NewStringUTF(std::to_string(t0).c_str()));
        env->SetObjectArrayElement(arr, i * 3 + 2, env->NewStringUTF(std::to_string(t1).c_str()));
    }

    return arr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_subtitlecreator_jni_WhisperLib_nativeSystemInfo(JNIEnv* env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}
