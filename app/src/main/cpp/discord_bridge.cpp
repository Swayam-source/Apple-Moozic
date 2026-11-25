#include <jni.h>
#include <android/log.h>
#include <string>
#include <memory>
#include <mutex>
#include <cstdint>

#define LOG_TAG "BitChordDiscordJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#if __has_include("discord_partner_sdk.h") && DISCORD_SDK_LINKED
#include "discord_partner_sdk.h"
#define HAS_DISCORD_SOCIAL_SDK 1
#else
#define HAS_DISCORD_SOCIAL_SDK 0
#endif

namespace {

std::mutex g_sdk_mutex;
uint64_t g_application_id = 0;
bool g_initialized = false;
bool g_token_applied = false;

std::string JStringToStdString(JNIEnv* env, jstring jStr) {
    if (jStr == nullptr) return "";
    const char* raw = env->GetStringUTFChars(jStr, nullptr);
    if (raw == nullptr) return "";
    std::string result(raw);
    env->ReleaseStringUTFChars(jStr, raw);
    return result;
}

#if HAS_DISCORD_SOCIAL_SDK
std::shared_ptr<discordpp::Client> g_discord_client = nullptr;
#endif

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_data_discord_DiscordNative_nativeIsSdkLoaded(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {
#if HAS_DISCORD_SOCIAL_SDK
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_data_discord_DiscordNative_nativeInit(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jlong applicationId) {
    std::lock_guard<std::mutex> lock(g_sdk_mutex);
    if (applicationId <= 0) {
        LOGE("nativeInit called with invalid applicationId");
        return JNI_FALSE;
    }
    g_application_id = static_cast<uint64_t>(applicationId);

#if HAS_DISCORD_SOCIAL_SDK
    if (!g_discord_client) {
        g_discord_client = std::make_shared<discordpp::Client>();
        g_discord_client->SetApplicationId(g_application_id);
        g_discord_client->SetStatusChangedCallback(
            [](discordpp::Client::Status status,
               discordpp::Client::Error error,
               int32_t errorDetail) {
                LOGI("Discord SDK status=%d error=%d detail=%d",
                     static_cast<int>(status),
                     static_cast<int>(error),
                     errorDetail);
            });
    }
    g_initialized = true;
    return JNI_TRUE;
#else
    g_initialized = true;
    LOGW("Discord Social SDK binary/header not bundled yet; stub initialized for App ID: %llu",
         static_cast<unsigned long long>(g_application_id));
    return JNI_TRUE;
#endif
}

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_data_discord_DiscordNative_nativeUpdateToken(
        JNIEnv* env,
        jobject /*thiz*/,
        jstring accessToken) {
    std::lock_guard<std::mutex> lock(g_sdk_mutex);
    std::string token = JStringToStdString(env, accessToken);
    if (token.empty()) return JNI_FALSE;

#if HAS_DISCORD_SOCIAL_SDK
    if (!g_discord_client) return JNI_FALSE;
    g_discord_client->UpdateToken(
        discordpp::AuthorizationTokenType::Bearer,
        token,
        [](discordpp::ClientResult result) {
            if (result.Successful()) {
                std::lock_guard<std::mutex> cbLock(g_sdk_mutex);
                if (g_discord_client) g_discord_client->Connect();
            } else {
                LOGE("Discord UpdateToken failed: %s", result.Error().c_str());
            }
        });
    g_token_applied = true;
    return JNI_TRUE;
#else
    g_token_applied = true;
    LOGI("Stub nativeUpdateToken stored Bearer token (length=%zu)", token.size());
    return JNI_TRUE;
#endif
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_data_discord_DiscordNative_nativeRunCallbacks(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_sdk_mutex);
#if HAS_DISCORD_SOCIAL_SDK
    if (g_initialized) discordpp::RunCallbacks();
#endif
}

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_data_discord_DiscordNative_nativeUpdateRichPresence(
        JNIEnv* env,
        jobject /*thiz*/,
        jstring title,
        jstring artist,
        jstring album,
        jstring artworkUrl,
        jlong startTimestampSec,
        jlong endTimestampSec) {
    std::lock_guard<std::mutex> lock(g_sdk_mutex);
    std::string trackTitle = JStringToStdString(env, title);
    std::string trackArtist = JStringToStdString(env, artist);
    std::string trackAlbum = JStringToStdString(env, album);
    std::string coverUrl = JStringToStdString(env, artworkUrl);
    if (trackTitle.empty()) return JNI_FALSE;

#if HAS_DISCORD_SOCIAL_SDK
    if (!g_discord_client || !g_token_applied) return JNI_FALSE;

    discordpp::Activity activity{};
    activity.SetType(discordpp::ActivityTypes::Listening);
    activity.SetName("Apple Moozic");
    activity.SetDetails(trackTitle);
    if (!trackArtist.empty()) activity.SetState(trackArtist);

    discordpp::ActivityAssets assets{};
    if (!coverUrl.empty()) assets.SetLargeImage(coverUrl);
    if (!trackAlbum.empty()) assets.SetLargeText(trackAlbum);
    activity.SetAssets(assets);

    if (startTimestampSec > 0) {
        discordpp::ActivityTimestamps timestamps{};
        timestamps.SetStart(static_cast<uint64_t>(startTimestampSec));
        if (endTimestampSec > startTimestampSec) {
            timestamps.SetEnd(static_cast<uint64_t>(endTimestampSec));
        }
        activity.SetTimestamps(timestamps);
    }

    g_discord_client->UpdateRichPresence(
        activity,
        [](discordpp::ClientResult result) {
            if (!result.Successful()) {
                LOGE("Discord UpdateRichPresence error: %s", result.Error().c_str());
            }
        });
    return JNI_TRUE;
#else
    LOGI("Stub Rich Presence -> Listening to '%s' by '%s' [cover='%s', %lld..%lld]",
         trackTitle.c_str(), trackArtist.c_str(), coverUrl.c_str(),
         static_cast<long long>(startTimestampSec), static_cast<long long>(endTimestampSec));
    return JNI_TRUE;
#endif
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_data_discord_DiscordNative_nativeClearRichPresence(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_sdk_mutex);
#if HAS_DISCORD_SOCIAL_SDK
    if (g_discord_client && g_token_applied) {
        g_discord_client->ClearRichPresence();
    }
#endif
}

} // extern "C"
