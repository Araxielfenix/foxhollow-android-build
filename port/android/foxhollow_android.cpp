/**
 * Android entry point and JNI glue for the Foxhollow port.
 *
 * On Android there is no process `main()`. SDLActivity loads libfoxhollow.so, resolves
 * `SDL_main` from it with dlsym and runs that on its own thread. SDL_main is what this file
 * exports, and it forwards to aurora_main: Aurora's aurora/main.h renames the game's `main`,
 * which is what src/main.c defines.
 *
 * Everything Aurora needs to know about the app arrives through environment variables (see
 * port/src/foxhollow_config.c), so paths are resolved here right before the game starts.
 */

#include <jni.h>

#include <android/log.h>

#include <dolphin/gx/GXAurora.h>
#include <dolphin/pad.h>
#include <dolphin/vi.h>

#include <aurora/gfx.h>

#include <SDL3/SDL_filesystem.h>
#include <SDL3/SDL_hints.h>
#include <SDL3/SDL_log.h>
#include <SDL3/SDL_stdinc.h>
#include <SDL3/SDL_system.h>

#include <cstdio>
#include <cstdlib>

extern "C" int aurora_main(int argc, char** argv);

#define FH_MAX_PATH 1024

namespace {

char sGamePath[FH_MAX_PATH] = {};
bool sGamePathSet = false;

void set_env(const char* name, const char* value) {
  if (value == nullptr || value[0] == '\0') {
    return;
  }
  SDL_SetHint(name, value);
  setenv(name, value, 1);
}

/**
 * Points the game at a readable disc image.
 *
 * Preference order: an explicit path from the Java layer, then a copy sitting in the app's
 * external files directory. Anything else is left unset so the game's own error message
 * shows up.
 */
void resolve_disc() {
  const char* external = SDL_GetAndroidExternalStoragePath();

  if (sGamePathSet && sGamePath[0] != '\0') {
    set_env("FOXHOLLOW_DISC", sGamePath);
    return;
  }

  if (external != nullptr) {
    SDL_PathInfo info;
    char candidate[FH_MAX_PATH];

    SDL_snprintf(candidate, sizeof(candidate), "%s/Star Fox Adventures.iso", external);
    if (SDL_GetPathInfo(candidate, &info) && info.type == SDL_PATHTYPE_FILE) {
      set_env("FOXHOLLOW_DISC", candidate);
      return;
    }
  }

  SDL_Log("foxhollow: no disc image set; pass one through nativeSetGamePath");
}

/**
 * Routes SDL's own logging to logcat.
 *
 * SDL's default output goes to stdout/stderr, which Android drops, so without this the port
 * gives no diagnostic output at all. Aurora's log module is routed separately, by
 * log_callback() in src/main.c.
 */
void SDL_LogOutputFunction(void* userdata, int category, SDL_LogPriority priority, const char* message) {
  (void)userdata;
  (void)priority;

  const char* tag = category == SDL_LOG_CATEGORY_APPLICATION ? "foxhollow" : "foxhollow-sdl";
  __android_log_print(ANDROID_LOG_INFO, tag, "%s", message != nullptr ? message : "");
}

void install_logging() {
  SDL_SetLogOutputFunction(SDL_LogOutputFunction, nullptr);
}

void resolve_paths() {  char* pref = SDL_GetPrefPath("Foxhollow", "Foxhollow");
  char cardPath[FH_MAX_PATH];
  char shaderCache[FH_MAX_PATH];


  if (pref != nullptr) {
    SDL_CreateDirectory(pref);
    set_env("FOXHOLLOW_USER_DIR", pref);
    set_env("FOXHOLLOW_CACHE_DIR", pref);

    SDL_snprintf(cardPath, sizeof(cardPath), "%sfoxhollow-memcard.card", pref);
    SDL_snprintf(shaderCache, sizeof(shaderCache), "%sshaders", pref);
    SDL_CreateDirectory(shaderCache);
    set_env("FOXHOLLOW_MEMORY_CARD", cardPath);
    set_env("FOXHOLLOW_SHADER_CACHE", shaderCache);
  }

  set_env("FOXHOLLOW_REV", "0");

  resolve_disc();
}

} // namespace

extern "C" int SDL_main(int argc, char* argv[]) {
  install_logging();
  resolve_paths();
  return aurora_main(argc, argv);
}

extern "C" {

JNIEXPORT void JNICALL Java_dev_encounter_foxhollow_FoxhollowActivity_nativeInitJNI(JNIEnv*, jclass) {
  SDL_Log("foxhollow: native JNI up");
}

JNIEXPORT void JNICALL Java_dev_encounter_foxhollow_FoxhollowActivity_nativeSetGamePath(JNIEnv* env, jclass,
                                                                                       jstring path) {
  if (path == nullptr) {
    sGamePathSet = false;
    sGamePath[0] = '\0';
    return;
  }

  const char* chars = env->GetStringUTFChars(path, nullptr);
  if (chars == nullptr) {
    return;
  }

  SDL_strlcpy(sGamePath, chars, sizeof(sGamePath));
  sGamePathSet = true;

  env->ReleaseStringUTFChars(path, chars);

  SDL_Log("foxhollow: disc image set to %s", sGamePath);
  set_env("FOXHOLLOW_DISC", sGamePath);
}

JNIEXPORT void JNICALL Java_dev_encounter_foxhollow_FoxhollowActivity_nativeFolderDialogResult(JNIEnv*, jclass, jlong,
                                                                                             jstring, jstring) {}

/**
 * Feeds the on-screen controls into GameCube controller slot 0.
 *
 * Aurora merges a "virtual" status on top of whatever real controllers report, so the touch
 * overlay can drive the game without pretending to be an SDL controller. stickX/stickY are
 * -127..127 with the GameCube's inverted vertical axis already applied by the Java side.
 */
JNIEXPORT void JNICALL Java_dev_encounter_foxhollow_FoxhollowActivity_nativeSetVirtualPad(JNIEnv*, jclass,
                                                                                         jint buttonMask, jint stickX,
                                                                                         jint stickY) {
  PADStatus status = {};

  status.button = static_cast<u16>(buttonMask);
  status.stickX = static_cast<s8>(SDL_clamp(stickX, -127, 127));
  status.stickY = static_cast<s8>(SDL_clamp(stickY, -127, 127));
  status.err = PAD_ERR_NONE;

  PADSetVirtualStatus(PAD_CHAN0, &status);
}

/**
 * Releases every virtual button and recenters the stick.
 *
 * Must be called when the activity loses focus: a finger lifted outside the window would
 * otherwise leave a direction or trigger stuck down.
 */
JNIEXPORT void JNICALL Java_dev_encounter_foxhollow_FoxhollowActivity_nativeClearVirtualPad(JNIEnv*, jclass) {
  PADClearVirtualStatus(PAD_CHAN0);
}

/**
 * Presents per second, measured from Aurora's own present timestamps.
 *
 * Reported rather than sampled on the Java side: the UI thread only ever redraws at the display
 * refresh rate, so counting its frames would measure the screen and not the game.
 */
JNIEXPORT jfloat JNICALL Java_dev_encounter_foxhollow_FoxhollowActivity_nativeGetFps(JNIEnv*, jclass) {
  return aurora_get_fps();
}

/**
 * Changes how much of the screen the game renders into.
 *
 * Below 1 the emulator draws into a smaller buffer and lets the compositor scale it up, which is
 * the cheapest way to buy frames on a weak GPU. Aurora applies this live, so it takes effect on
 * the next frame rather than needing a restart.
 */
JNIEXPORT void JNICALL Java_dev_encounter_foxhollow_FoxhollowActivity_nativeSetRenderScale(JNIEnv*, jclass,
                                                                                          jfloat scale) {
  VISetFrameBufferScale(scale);
}

/**
 * Picks how the game picture is fitted to the screen.
 *
 * 0 keeps the original 4:3 with black bars, 1 widens to 16:9, and 2 stretches the framebuffer to
 * the shape of the display so there are no bars at all. Both Aurora calls are cheap state changes
 * that request a framebuffer resize, so the change lands on the next frame.
 */
JNIEXPORT void JNICALL Java_dev_encounter_foxhollow_FoxhollowActivity_nativeSetDisplayMode(JNIEnv*, jclass,
                                                                                           jint mode) {
  switch (mode) {
  case 1:
    AuroraSetViewportPolicy(AURORA_VIEWPORT_FIT);
    AuroraSetDisplayAspect(16.0f / 9.0f);
    break;
  case 2:
    AuroraSetViewportPolicy(AURORA_VIEWPORT_STRETCH);
    AuroraSetDisplayAspect(0.f);
    break;
  default:
    AuroraSetViewportPolicy(AURORA_VIEWPORT_FIT);
    AuroraSetDisplayAspect(4.0f / 3.0f);
    break;
  }
}

} // extern "C"
