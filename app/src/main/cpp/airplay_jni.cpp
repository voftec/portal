/*
 * Pantalla AirPlay — JNI bridge between the Kotlin app and the vendored
 * UxPlay AirPlay server library (GPL-3.0).
 *
 * Mirrors the server setup and callback semantics of uxplay.cpp
 * (upstream commit b3202df, see uxplay/UPSTREAM.md), replacing the
 * GStreamer renderers with MediaCodec/AudioTrack on Android.
 */

#include <jni.h>
#include <pthread.h>
#include <string.h>
#include <stdlib.h>
#include <stdarg.h>
#include <atomic>
#include <android/log.h>

extern "C" {
#include "raop.h"
#include "dnssd.h"
#include "dnssdint.h"
#include "logger.h"
#include "stream.h"
}

#define LOG_TAG "AirPlayTV"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace {

JavaVM *g_vm = nullptr;
jobject g_listener = nullptr;          // global ref to AirPlayNative.Listener
raop_t *g_raop = nullptr;
dnssd_t *g_dnssd = nullptr;
pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
std::atomic<bool> g_running{false};
std::atomic<int> g_open_connections{0};
std::atomic<bool> g_mirroring{false};
double g_current_volume = -30.0;       // AirPlay dB range {-30:0}, -144 = mute

/* jmethodIDs resolved once in nativeStart. UxPlay's native threads stay
 * attached to the JVM for the whole session, so local refs created per
 * frame (classes, buffers, strings) would overflow the local ref table —
 * nothing here may leak a local ref. */
struct CbIds {
    jmethodID onConnectionOpen = nullptr;
    jmethodID onConnectionClose = nullptr;
    jmethodID onConnectionReset = nullptr;
    jmethodID onMirrorStart = nullptr;
    jmethodID onVideoData = nullptr;
    jmethodID onVideoFlush = nullptr;
    jmethodID onVideoReset = nullptr;
    jmethodID onVideoSize = nullptr;
    jmethodID onAudioFormat = nullptr;
    jmethodID onAudioData = nullptr;
    jmethodID onAudioFlush = nullptr;
    jmethodID onAudioVolume = nullptr;
    jmethodID onClientRequest = nullptr;
};
CbIds g_ids;

/* fixed legacy ports (upstream "-p" defaults): TCP 7100:7000:7001 UDP 7011:6001:6000 */
unsigned short g_tcp[3] = {7100, 7000, 7001};
unsigned short g_udp[3] = {7011, 6001, 6000};

/* Attach the calling (native) thread to the JVM; detach automatically on
 * thread exit via a pthread key destructor. */
pthread_key_t g_jni_key;
void jni_detach(void *) {
    if (g_vm) g_vm->DetachCurrentThread();
}
struct JniEnv {
    JNIEnv *env;
    JniEnv() : env(nullptr) {
        if (!g_vm) return;
        if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
            if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                pthread_setspecific(g_jni_key, reinterpret_cast<void *>(1));
            } else {
                env = nullptr;
            }
        }
    }
    bool ok() const { return env != nullptr && g_listener != nullptr; }
};

pthread_once_t g_key_once = PTHREAD_ONCE_INIT;
void make_key() { pthread_key_create(&g_jni_key, jni_detach); }

void check_exception(JNIEnv *env) {
    if (env->ExceptionCheck()) env->ExceptionDescribe();
}

/* Call a void listener method with varargs (CallVoidMethodV: `ap` carries
 * the actual arguments). */
void call_void(jmethodID m, ...) {
    if (!m) return;
    JniEnv e;
    if (!e.ok()) return;
    va_list ap;
    va_start(ap, m);
    e.env->CallVoidMethodV(g_listener, m, ap);
    va_end(ap);
    check_exception(e.env);
}

/* ---------- upstream-style callbacks ---------- */

extern "C" {

void jni_log_callback(void *cls, int level, const char *msg) {
    switch (level) {
        case LOGGER_DEBUG:    ALOGD("%s", msg); break;
        case LOGGER_WARNING:  ALOGW("%s", msg); break;
        case LOGGER_INFO:     ALOGI("%s", msg); break;
        case LOGGER_ERR:      ALOGE("%s", msg); break;
        default:              ALOGI("%s", msg); break;
    }
}

void cb_conn_init(void *cls) {
    int n = ++g_open_connections;
    ALOGD("Open connections: %d", n);
    call_void(g_ids.onConnectionOpen);
}

void cb_conn_destroy(void *cls) {
    int n = --g_open_connections;
    ALOGD("Open connections: %d", n);
    if (n <= 0) {
        g_open_connections = 0;
        g_mirroring = false;
    }
    call_void(g_ids.onConnectionClose);
}

void cb_conn_feedback(void *cls) { /* heartbeat: connection still alive */ }

void cb_conn_reset(void *cls, int reason) {
    if (reason == 1) ALOGE("lost connection with client (network problem?)");
    g_mirroring = false;
    call_void(g_ids.onConnectionReset, (jint) reason);
}

void cb_video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    /* Annex-B H.264, SPS/PPS prepended before IDRs. No clock sync: we hand the
     * access unit straight to MediaCodec for lowest-latency rendering. */
    if (!g_mirroring.exchange(true)) {
        call_void(g_ids.onMirrorStart);
    }
    if (!g_ids.onVideoData) return;
    JniEnv e;
    if (!e.ok()) return;
    jobject buf = e.env->NewDirectByteBuffer(data->data, data->data_len);
    if (!buf) { e.env->ExceptionClear(); return; }
    e.env->CallVoidMethod(g_listener, g_ids.onVideoData, buf,
                          (jint) data->data_len, (jint) data->nal_count);
    e.env->DeleteLocalRef(buf);
    check_exception(e.env);
}

void cb_audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    if (!g_ids.onAudioData) return;
    JniEnv e;
    if (!e.ok()) return;
    jobject buf = e.env->NewDirectByteBuffer(data->data, data->data_len);
    if (!buf) { e.env->ExceptionClear(); return; }
    e.env->CallVoidMethod(g_listener, g_ids.onAudioData, buf,
                          (jint) data->data_len, (jint) data->ct,
                          (jint) data->seqnum);
    e.env->DeleteLocalRef(buf);
    check_exception(e.env);
}

void cb_video_pause(void *cls) {}
void cb_video_resume(void *cls) {}

void cb_video_reset(void *cls, reset_type_t type) {
    ALOGD("video_reset: type = %d", (int) type);
    g_mirroring = false;
    call_void(g_ids.onVideoReset, (jint) type);
}

int cb_video_set_codec(void *cls, video_codec_t codec) {
    /* h265 is disabled in the advertised feature set; accept anything so the
     * mirror stream keeps flowing (we only ever asked for H.264). */
    return 0;
}

void cb_video_report_size(void *cls, float *width_source, float *height_source,
                          float *width, float *height) {
    call_void(g_ids.onVideoSize, (jfloat) *width_source, (jfloat) *height_source,
              (jfloat) *width, (jfloat) *height);
}

void cb_audio_flush(void *cls) { call_void(g_ids.onAudioFlush); }
void cb_video_flush(void *cls) { call_void(g_ids.onVideoFlush); }

double cb_audio_set_client_volume(void *cls) { return g_current_volume; }

void cb_audio_set_volume(void *cls, float volume) {
    g_current_volume = volume;
    call_void(g_ids.onAudioVolume, (jfloat) volume);
}

void cb_audio_get_format(void *cls, unsigned char *ct, unsigned short *spf,
                         bool *usingScreen, bool *isMedia, uint64_t *audioFormat) {
    ALOGI("audio_get_format ct=%d spf=%d usingScreen=%d isMedia=%d audioFormat=0x%lx",
          *ct, *spf, *usingScreen, *isMedia, (unsigned long) *audioFormat);
    call_void(g_ids.onAudioFormat, (jint) *ct, (jint) *spf);
}

void cb_report_client_request(void *cls, char *deviceid, char *model, char *name,
                              bool *admit) {
    ALOGI("connection request from %s (%s) deviceID=%s", name, model, deviceid);
    *admit = true;
    if (!g_ids.onClientRequest) return;
    JniEnv e;
    if (!e.ok()) return;
    jstring jname = e.env->NewStringUTF(name ? name : "?");
    if (!jname) { e.env->ExceptionClear(); return; }
    e.env->CallVoidMethod(g_listener, g_ids.onClientRequest, jname);
    e.env->DeleteLocalRef(jname);
    check_exception(e.env);
}

void cb_display_pin(void *cls, char *pin) { ALOGI("client PIN = %s", pin); }

const char *cb_passwd(void *cls, int *len) { *len = 0; return nullptr; }

void cb_register_client(void *cls, const char *device_id, const char *pk_str,
                        const char *name) {}

bool cb_check_register(void *cls, const char *pk_str) { return true; }

void cb_export_dacp(void *cls, const char *active_remote, const char *dacp_id) {}
void cb_audio_set_metadata(void *cls, const void *buffer, int buflen) {}
void cb_audio_set_coverart(void *cls, const void *buffer, int buflen) {}
void cb_audio_stop_coverart_rendering(void *cls) {}
void cb_audio_remote_control_id(void *cls, const char *a, const char *b) {}
void cb_audio_set_progress(void *cls, uint32_t *s, uint32_t *c, uint32_t *e) {}

/* HLS is disabled; these exist only because lib calls them unconditionally. */
void cb_on_video_play(void *cls, const char *location, const float start_position) {}
void cb_on_video_scrub(void *cls, const float position) {}
void cb_on_video_rate(void *cls, const float rate) {}
void cb_on_video_stop(void *cls) {}
float cb_on_video_playlist_remove(void *cls) { return 0.0f; }
void cb_on_video_acquire_playback_info(void *cls, playback_info_t *info) {
    info->duration = -1.0;
    info->position = -1.0;
    info->rate = 0.0f;
    info->ready_to_play = false;
    info->playback_buffer_empty = true;
    info->playback_buffer_full = false;
    info->playback_likely_to_keep_up = false;
}

} // extern "C"

/* ---------- server setup, mirroring uxplay.cpp ---------- */

int apply_feature_bits(dnssd_t *dnssd) {
    /* default feature set: FEATURES_1 = 0x5A7FFEE6, FEATURES_2 = 0
     * (as in upstream start_dnssd) */
    dnssd_set_airplay_features(dnssd, 0, 0);  // AirPlay video (HLS off)
    dnssd_set_airplay_features(dnssd, 1, 1);  // photo
    dnssd_set_airplay_features(dnssd, 2, 1);  // FairPlay DRM video
    dnssd_set_airplay_features(dnssd, 3, 0);  // volume control for videos
    dnssd_set_airplay_features(dnssd, 4, 0);  // HLS
    dnssd_set_airplay_features(dnssd, 5, 1);  // slideshow
    dnssd_set_airplay_features(dnssd, 6, 1);
    dnssd_set_airplay_features(dnssd, 7, 1);  // mirroring
    dnssd_set_airplay_features(dnssd, 8, 0);  // screen rotation
    dnssd_set_airplay_features(dnssd, 9, 1);  // audio
    dnssd_set_airplay_features(dnssd, 10, 1);
    dnssd_set_airplay_features(dnssd, 11, 1); // audio packet redundancy
    dnssd_set_airplay_features(dnssd, 12, 1); // FairPlay secure auth
    dnssd_set_airplay_features(dnssd, 13, 1); // photo preloading
    dnssd_set_airplay_features(dnssd, 14, 1); // FairPlay auth
    dnssd_set_airplay_features(dnssd, 15, 1); // metadata: artwork
    dnssd_set_airplay_features(dnssd, 16, 1); // metadata: progress
    dnssd_set_airplay_features(dnssd, 17, 1); // metadata: text
    dnssd_set_airplay_features(dnssd, 18, 1); // audio format 1
    dnssd_set_airplay_features(dnssd, 19, 1); // audio format 2 (AirPlay 2)
    dnssd_set_airplay_features(dnssd, 20, 1); // audio format 3 (AirPlay 2)
    dnssd_set_airplay_features(dnssd, 21, 1); // audio format 4
    dnssd_set_airplay_features(dnssd, 22, 1); // FairPlay auth type 4
    dnssd_set_airplay_features(dnssd, 23, 0); // RSA auth
    dnssd_set_airplay_features(dnssd, 24, 0);
    dnssd_set_airplay_features(dnssd, 25, 1);
    dnssd_set_airplay_features(dnssd, 26, 0); // unified advertiser info
    dnssd_set_airplay_features(dnssd, 28, 1);
    dnssd_set_airplay_features(dnssd, 29, 0);
    dnssd_set_airplay_features(dnssd, 30, 1); // RAOP support (AirTunes not needed)
    dnssd_set_airplay_features(dnssd, 31, 0);
    /* bits 32-63 left at 0 (FEATURES_2): no CarPlay, no PTP, no h265 (bit 42),
     * no HomeKit/system pairing, no buffered audio */
    dnssd_set_airplay_features(dnssd, 42, 0); // screen multi-codec / h265: OFF
    /* bit 27: legacy pairing protocol. Upstream default (setup_legacy_pairing
     * = false) clears this bit -> AirPlay 2 style pairing. */
    dnssd_set_airplay_features(dnssd, 27, 0);
    return 0;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_voftec_airplaytv_AirPlayNative_nativeStart(
        JNIEnv *env, jclass clazz, jstring jname, jbyteArray jhwAddr,
        jstring jkeyfile, jobject listener) {
    pthread_mutex_lock(&g_lock);
    if (g_running) { pthread_mutex_unlock(&g_lock); return JNI_TRUE; }

    pthread_once(&g_key_once, make_key);
    env->GetJavaVM(&g_vm);
    if (g_listener) env->DeleteGlobalRef(g_listener);
    g_listener = env->NewGlobalRef(listener);

    /* resolve all method IDs once; no local refs are created per frame */
    memset(&g_ids, 0, sizeof(g_ids));
    {
        jclass cls = env->GetObjectClass(g_listener);
        g_ids.onConnectionOpen = env->GetMethodID(cls, "onConnectionOpen", "()V");
        g_ids.onConnectionClose = env->GetMethodID(cls, "onConnectionClose", "()V");
        g_ids.onConnectionReset = env->GetMethodID(cls, "onConnectionReset", "(I)V");
        g_ids.onMirrorStart = env->GetMethodID(cls, "onMirrorStart", "()V");
        g_ids.onVideoData = env->GetMethodID(cls, "onVideoData", "(Ljava/nio/ByteBuffer;II)V");
        g_ids.onVideoFlush = env->GetMethodID(cls, "onVideoFlush", "()V");
        g_ids.onVideoReset = env->GetMethodID(cls, "onVideoReset", "(I)V");
        g_ids.onVideoSize = env->GetMethodID(cls, "onVideoSize", "(FFFF)V");
        g_ids.onAudioFormat = env->GetMethodID(cls, "onAudioFormat", "(II)V");
        g_ids.onAudioData = env->GetMethodID(cls, "onAudioData", "(Ljava/nio/ByteBuffer;III)V");
        g_ids.onAudioFlush = env->GetMethodID(cls, "onAudioFlush", "()V");
        g_ids.onAudioVolume = env->GetMethodID(cls, "onAudioVolume", "(F)V");
        g_ids.onClientRequest = env->GetMethodID(cls, "onClientRequest", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(cls);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();

    const char *name = env->GetStringUTFChars(jname, nullptr);
    const char *keyfile = env->GetStringUTFChars(jkeyfile, nullptr);
    jbyte *hw = env->GetByteArrayElements(jhwAddr, nullptr);
    char hw_addr[6];
    memcpy(hw_addr, hw, 6);

    /* device_id for raop_init2 is the MAC in "aa:bb:cc:dd:ee:ff" form */
    char mac_str[18];
    snprintf(mac_str, sizeof(mac_str), "%02x:%02x:%02x:%02x:%02x:%02x",
             (unsigned char) hw_addr[0], (unsigned char) hw_addr[1],
             (unsigned char) hw_addr[2], (unsigned char) hw_addr[3],
             (unsigned char) hw_addr[4], (unsigned char) hw_addr[5]);

    ALOGI("starting AirPlay receiver \"%s\" mac=%s", name, mac_str);
    ALOGI("using network ports UDP %d %d %d TCP %d %d %d",
          g_udp[0], g_udp[1], g_udp[2], g_tcp[0], g_tcp[1], g_tcp[2]);

    int dnssd_error = 0;
    unsigned short raop_port = 0;
    g_dnssd = dnssd_init(name, (int) strlen(name), hw_addr, 6, 0 /* pin_pw */,
                         &dnssd_error);
    env->ReleaseByteArrayElements(jhwAddr, hw, JNI_ABORT);
    if (!g_dnssd || dnssd_error) {
        ALOGE("dnssd_init failed: error %d", dnssd_error);
        goto fail;
    }
    apply_feature_bits(g_dnssd);
    ALOGI("advertised AirPlay features = 0x%llX",
          (unsigned long long) dnssd_get_airplay_features(g_dnssd));

    {
        raop_callbacks_t cbs;
        memset(&cbs, 0, sizeof(cbs));
        cbs.conn_init = cb_conn_init;
        cbs.conn_destroy = cb_conn_destroy;
        cbs.conn_feedback = cb_conn_feedback;
        cbs.conn_reset = cb_conn_reset;
        cbs.audio_process = cb_audio_process;
        cbs.video_process = cb_video_process;
        cbs.audio_flush = cb_audio_flush;
        cbs.video_flush = cb_video_flush;
        cbs.video_pause = cb_video_pause;
        cbs.video_resume = cb_video_resume;
        cbs.audio_set_client_volume = cb_audio_set_client_volume;
        cbs.audio_set_volume = cb_audio_set_volume;
        cbs.audio_set_metadata = cb_audio_set_metadata;
        cbs.audio_set_coverart = cb_audio_set_coverart;
        cbs.audio_stop_coverart_rendering = cb_audio_stop_coverart_rendering;
        cbs.audio_remote_control_id = cb_audio_remote_control_id;
        cbs.audio_set_progress = cb_audio_set_progress;
        cbs.audio_get_format = cb_audio_get_format;
        cbs.video_report_size = cb_video_report_size;
        cbs.report_client_request = cb_report_client_request;
        cbs.display_pin = cb_display_pin;
        cbs.register_client = cb_register_client;
        cbs.check_register = cb_check_register;
        cbs.passwd = cb_passwd;
        cbs.export_dacp = cb_export_dacp;
        cbs.video_reset = cb_video_reset;
        cbs.video_set_codec = cb_video_set_codec;
        cbs.on_video_play = cb_on_video_play;
        cbs.on_video_scrub = cb_on_video_scrub;
        cbs.on_video_rate = cb_on_video_rate;
        cbs.on_video_stop = cb_on_video_stop;
        cbs.on_video_acquire_playback_info = cb_on_video_acquire_playback_info;
        cbs.on_video_playlist_remove = cb_on_video_playlist_remove;

        g_raop = raop_init(&cbs);
    }
    if (!g_raop) {
        ALOGE("raop_init failed");
        goto fail;
    }
    raop_set_log_callback(g_raop, jni_log_callback, nullptr);
    raop_set_log_level(g_raop, LOGGER_INFO);

    if (raop_init2(g_raop, 1 /* nohold */, mac_str, keyfile)) {
        ALOGE("raop_init2 failed");
        goto fail;
    }
    env->ReleaseStringUTFChars(jkeyfile, keyfile);

    /* display plist: 1920x1080 @60Hz, maxFPS 60, not overscanned */
    raop_set_plist(g_raop, "width", 1920);
    raop_set_plist(g_raop, "height", 1080);
    raop_set_plist(g_raop, "refreshRate", 60);
    raop_set_plist(g_raop, "maxFPS", 60);
    raop_set_plist(g_raop, "overscanned", 0);

    raop_set_tcp_ports(g_raop, g_tcp);   // {mirror_data=7100, raop=7000}
    raop_set_udp_ports(g_raop, g_udp);   // {timing=7011, control=6001, data=6000}

    raop_port = raop_get_port(g_raop);
    if (raop_start_httpd(g_raop, &raop_port) < 0) {  // returns 1 on success
        ALOGE("raop_start_httpd failed");
        goto fail;
    }
    raop_set_port(g_raop, raop_port);
    raop_set_dnssd(g_raop, g_dnssd);

    if (dnssd_register_raop(g_dnssd, raop_port)) {
        ALOGE("dnssd_register_raop failed");
        goto fail;
    }
    if (dnssd_register_airplay(g_dnssd, raop_port)) {
        ALOGE("dnssd_register_airplay failed");
        goto fail;
    }
    ALOGI("AirPlay receiver up: raop/airplay port %d, name \"%s\"", raop_port, name);

    env->ReleaseStringUTFChars(jname, name);
    g_open_connections = 0;
    g_mirroring = false;
    g_running = true;
    pthread_mutex_unlock(&g_lock);
    return JNI_TRUE;

fail:
    env->ReleaseStringUTFChars(jname, name);
    env->ReleaseStringUTFChars(jkeyfile, keyfile);
    if (g_raop) { raop_destroy(g_raop); g_raop = nullptr; }
    if (g_dnssd) { dnssd_destroy(g_dnssd); g_dnssd = nullptr; }
    pthread_mutex_unlock(&g_lock);
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_voftec_airplaytv_AirPlayNative_nativeStop(JNIEnv *env, jclass clazz) {
    pthread_mutex_lock(&g_lock);
    g_running = false;
    g_mirroring = false;
    g_open_connections = 0;
    /* upstream order: stop the raop server (raop_destroy) first so no
     * callback can still be in flight, then tear down dnssd, and only
     * then release the listener. */
    if (g_raop) { raop_destroy(g_raop); g_raop = nullptr; }
    if (g_dnssd) {
        dnssd_unregister_raop(g_dnssd);
        dnssd_unregister_airplay(g_dnssd);
        dnssd_destroy(g_dnssd);
        g_dnssd = nullptr;
    }
    if (g_listener) { env->DeleteGlobalRef(g_listener); g_listener = nullptr; }
    pthread_mutex_unlock(&g_lock);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_voftec_airplaytv_AirPlayNative_nativeIsRunning(JNIEnv *env, jclass clazz) {
    return g_running.load() ? JNI_TRUE : JNI_FALSE;
}
