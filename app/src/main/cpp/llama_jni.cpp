#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "LocalAI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model*       model   = nullptr;
    llama_context*     ctx     = nullptr;
    llama_sampler*     sampler = nullptr;
    const llama_vocab* vocab   = nullptr;
    int                n_ctx   = 0;
    std::atomic<bool>  stop{false};
};

std::string to_std(JNIEnv* env, jstring js) {
    const char* c = env->GetStringUTFChars(js, nullptr);
    std::string s(c ? c : "");
    if (c) env->ReleaseStringUTFChars(js, c);
    return s;
}

// Length of the longest prefix that ends on a complete UTF-8 character
// (a token can end in the middle of a multi-byte character).
size_t utf8_complete_prefix(const std::string& s) {
    size_t i = 0;
    const size_t n = s.size();
    while (i < n) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        size_t len = c < 0x80 ? 1 : (c >> 5) == 6 ? 2 : (c >> 4) == 14 ? 3 : (c >> 3) == 30 ? 4 : 1;
        if (i + len > n) break;
        i += len;
    }
    return i;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_localai_llm_LlamaBridge_nativeLoad(JNIEnv* env, jobject, jstring jpath,
                                                    jint nCtx, jint nThreads) {
    static bool backend_ready = false;
    if (!backend_ready) {
        llama_backend_init();
        backend_ready = true;
    }

    const std::string path = to_std(env, jpath);

    llama_model_params mp = llama_model_default_params();
    llama_model* model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) {
        LOGE("failed to load model: %s", path.c_str());
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = static_cast<uint32_t>(nCtx);
    cp.n_batch         = static_cast<uint32_t>(nCtx);   // whole prompt can go in one decode call
    cp.n_ubatch        = 512;
    cp.n_threads       = nThreads;
    cp.n_threads_batch = nThreads;
    llama_context* ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        LOGE("failed to create context (out of memory?)");
        llama_model_free(model);
        return 0;
    }

    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    auto* s  = new Session();
    s->model = model;
    s->ctx   = ctx;
    s->sampler = smpl;
    s->vocab = llama_model_get_vocab(model);
    s->n_ctx = static_cast<int>(llama_n_ctx(ctx));
    LOGI("model loaded, n_ctx=%d threads=%d", s->n_ctx, nThreads);
    return reinterpret_cast<jlong>(s);
}

// Returns number of generated tokens, or: -1 bad handle, -2 prompt too long, -3 template failure
extern "C" JNIEXPORT jint JNICALL
Java_com_example_localai_llm_LlamaBridge_nativeGenerate(JNIEnv* env, jobject, jlong handle,
        jobjectArray roles, jobjectArray contents, jint maxTokens, jobject callback) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) return -1;
    s->stop = false;

    // 1) Build the prompt with the model's own chat template
    const int n = env->GetArrayLength(roles);
    std::vector<std::string> r(n), c(n);
    std::vector<llama_chat_message> msgs(n);
    for (int i = 0; i < n; i++) {
        auto jr = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto jc = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        r[i] = to_std(env, jr);
        c[i] = to_std(env, jc);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
    }
    for (int i = 0; i < n; i++) msgs[i] = { r[i].c_str(), c[i].c_str() };

    std::string prompt;
    auto apply = [&](const char* tmpl) -> bool {
        std::vector<char> buf(16384);
        int len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true,
                                            buf.data(), static_cast<int32_t>(buf.size()));
        if (len > static_cast<int>(buf.size())) {
            buf.resize(len);
            len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true,
                                            buf.data(), static_cast<int32_t>(buf.size()));
        }
        if (len < 0) return false;
        prompt.assign(buf.data(), static_cast<size_t>(len));
        return true;
    };
    const char* model_tmpl = llama_model_chat_template(s->model, nullptr);
    if (!(model_tmpl && apply(model_tmpl)) && !apply("chatml")) return -3;

    // 2) Tokenize
    const int n_tok = -llama_tokenize(s->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                      nullptr, 0, true, true);
    if (n_tok <= 0) return -3;
    std::vector<llama_token> toks(n_tok);
    if (llama_tokenize(s->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                       toks.data(), static_cast<int32_t>(toks.size()), true, true) < 0) return -3;

    const int budget = std::min<int>(maxTokens, s->n_ctx - static_cast<int>(toks.size()) - 1);
    if (budget <= 0) return -2;

    // 3) Fresh KV cache for each turn (simple and robust)
    llama_memory_clear(llama_get_memory(s->ctx), true);
    llama_sampler_reset(s->sampler);

    jclass cls = env->GetObjectClass(callback);
    jmethodID mid = env->GetMethodID(cls, "onToken", "([B)Z");
    auto emit = [&](const std::string& bytes) -> bool {
        jbyteArray arr = env->NewByteArray(static_cast<jsize>(bytes.size()));
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(bytes.size()),
                                reinterpret_cast<const jbyte*>(bytes.data()));
        const jboolean cont = env->CallBooleanMethod(callback, mid, arr);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        return cont == JNI_TRUE;
    };

    // 4) Prefill the prompt in chunks so a Stop tap is honoured while the prompt is still
    //    being processed (a long history can take seconds). Positions are explicit, logits
    //    only for the last token.
    const int32_t total = static_cast<int32_t>(toks.size());
    constexpr int32_t kPrefillChunk = 256;
    int generated = 0;
    bool aborted = false;
    std::string pending;

    llama_batch prefill = llama_batch_init(kPrefillChunk, 0, 1);
    int32_t done = 0;
    llama_pos pos = 0;
    while (done < total && !s->stop.load()) {
        const int32_t len = std::min(kPrefillChunk, total - done);
        for (int32_t j = 0; j < len; j++) {
            prefill.token[j]     = toks[done + j];
            prefill.pos[j]       = pos + j;
            prefill.n_seq_id[j]  = 1;
            prefill.seq_id[j][0] = 0;
            prefill.logits[j]    = (done + j == total - 1) ? 1 : 0;
        }
        prefill.n_tokens = len;
        if (llama_decode(s->ctx, prefill) != 0) {
            LOGE("llama_decode failed on the prompt");
            break;
        }
        pos  += len;
        done += len;
    }
    llama_batch_free(prefill);
    if (done < total) return generated;      // stopped (or failed) before any output

    // 5) Decode loop: sample, emit what is complete, then decode the sampled token.
    llama_token tok = 0;                     // must outlive each batch: it points at this
    while (generated < budget && !s->stop.load()) {
        tok = llama_sampler_sample(s->sampler, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, tok)) break;

        char piece[256];
        const int len = llama_token_to_piece(s->vocab, tok, piece, sizeof(piece), 0, true);
        if (len > 0) pending.append(piece, static_cast<size_t>(len));

        const size_t ok = utf8_complete_prefix(pending);
        if (ok > 0) {
            if (!emit(pending.substr(0, ok))) { aborted = true; break; }
            pending.erase(0, ok);
        }
        generated++;

        llama_batch batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(s->ctx, batch) != 0) {
            LOGE("llama_decode failed");
            break;
        }
    }
    if (!pending.empty() && !aborted && !s->stop.load()) emit(pending);
    return generated;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_localai_llm_LlamaBridge_nativeStop(JNIEnv*, jobject, jlong handle) {
    if (auto* s = reinterpret_cast<Session*>(handle)) s->stop = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_localai_llm_LlamaBridge_nativeUnload(JNIEnv*, jobject, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) return;
    llama_sampler_free(s->sampler);
    llama_free(s->ctx);
    llama_model_free(s->model);
    delete s;
}

// =============================================================================================
// Embeddings (for RAG): a separate, small embedding model (e.g. bge-small) in its own context
// =============================================================================================

namespace {
struct EmbedSession {
    llama_model*       model = nullptr;
    llama_context*     ctx   = nullptr;
    const llama_vocab* vocab = nullptr;
    int                n_embd = 0;
    int                n_ctx  = 0;
};
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_localai_llm_LlamaBridge_nativeEmbedLoad(JNIEnv* env, jobject, jstring jpath,
                                                         jint nCtx, jint nThreads) {
    static bool backend_ready = false;
    if (!backend_ready) {
        llama_backend_init();
        backend_ready = true;
    }
    const std::string path = to_std(env, jpath);

    llama_model* model = llama_model_load_from_file(path.c_str(), llama_model_default_params());
    if (!model) { LOGE("embed: failed to load %s", path.c_str()); return 0; }
    if (llama_model_has_encoder(model) && llama_model_has_decoder(model)) {
        LOGE("embed: encoder-decoder models are not supported");
        llama_model_free(model);
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = static_cast<uint32_t>(nCtx);
    cp.n_batch         = static_cast<uint32_t>(nCtx);
    cp.n_ubatch        = static_cast<uint32_t>(nCtx);   // non-causal models need ubatch == batch
    cp.n_threads       = nThreads;
    cp.n_threads_batch = nThreads;
    cp.embeddings      = true;
    llama_context* ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); return 0; }

    auto* s   = new EmbedSession();
    s->model  = model;
    s->ctx    = ctx;
    s->vocab  = llama_model_get_vocab(model);
    s->n_embd = llama_model_n_embd(model);
    s->n_ctx  = static_cast<int>(llama_n_ctx(ctx));
    LOGI("embedding model loaded: n_embd=%d n_ctx=%d", s->n_embd, s->n_ctx);
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_localai_llm_LlamaBridge_nativeEmbedDim(JNIEnv*, jobject, jlong handle) {
    auto* s = reinterpret_cast<EmbedSession*>(handle);
    return s ? s->n_embd : 0;
}

// Returns the (unnormalized) pooled embedding, or null on failure.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_localai_llm_LlamaBridge_nativeEmbed(JNIEnv* env, jobject, jlong handle, jstring jtext) {
    auto* s = reinterpret_cast<EmbedSession*>(handle);
    if (!s) return nullptr;
    const std::string text = to_std(env, jtext);

    const int n_tok = -llama_tokenize(s->vocab, text.c_str(), static_cast<int32_t>(text.size()),
                                      nullptr, 0, true, true);
    if (n_tok <= 0) return nullptr;
    std::vector<llama_token> toks(n_tok);
    if (llama_tokenize(s->vocab, text.c_str(), static_cast<int32_t>(text.size()),
                       toks.data(), static_cast<int32_t>(toks.size()), true, true) < 0) return nullptr;
    if (static_cast<int>(toks.size()) > s->n_ctx) {          // truncate but keep the final special token
        const llama_token last = toks.back();
        toks.resize(s->n_ctx);
        toks.back() = last;
    }

    llama_batch batch = llama_batch_init(static_cast<int32_t>(toks.size()), 0, 1);
    for (size_t i = 0; i < toks.size(); i++) {
        batch.token[i]     = toks[i];
        batch.pos[i]       = static_cast<llama_pos>(i);
        batch.n_seq_id[i]  = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i]    = 1;
    }
    batch.n_tokens = static_cast<int32_t>(toks.size());

    llama_memory_clear(llama_get_memory(s->ctx), true);      // no-op for memory-less (BERT-style) models
    const int rc = llama_decode(s->ctx, batch);              // falls back to encode() when there is no memory
    jfloatArray result = nullptr;
    if (rc == 0) {
        const float* emb = (llama_pooling_type(s->ctx) == LLAMA_POOLING_TYPE_NONE)
                               ? llama_get_embeddings_ith(s->ctx, -1)
                               : llama_get_embeddings_seq(s->ctx, 0);
        if (emb) {
            result = env->NewFloatArray(s->n_embd);
            env->SetFloatArrayRegion(result, 0, s->n_embd, emb);
        }
    } else {
        LOGE("embed: decode failed rc=%d", rc);
    }
    llama_batch_free(batch);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_localai_llm_LlamaBridge_nativeEmbedUnload(JNIEnv*, jobject, jlong handle) {
    auto* s = reinterpret_cast<EmbedSession*>(handle);
    if (!s) return;
    llama_free(s->ctx);
    llama_model_free(s->model);
    delete s;
}
