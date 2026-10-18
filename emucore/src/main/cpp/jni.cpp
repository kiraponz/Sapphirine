// The JNI functions of com.kira3.ametrine.emucore.NativeCore, one per external of the Kotlin
// object, plus JNI_OnLoad, which hands the JVM over to android_host (for the callbacks the
// core makes into NativeCore).
//
// SPDX-License-Identifier: GPL-3.0-or-later

// types.h (through mednafen.h) #errors if it comes after jni.h (which android_host.h pulls in
// and which defines NULL), so mednafen.h goes first. config.h through types.h provides
// MEDNAFEN_VERSION.
#include <mednafen/mednafen.h>
#include "android_host.h"
#include "disc.h"

#include <android/log.h>
#include <android/native_window_jni.h>

#include <unistd.h>

#include <string>
#include <vector>

namespace
{

std::string ToString(JNIEnv* env, jstring str)
{
	if (!str)
		return {};

	const char* chars = env->GetStringUTFChars(str, nullptr);
	std::string ret(chars);
	env->ReleaseStringUTFChars(str, chars);
	return ret;
}

/** Null for no error. */
jstring ErrorToString(JNIEnv* env, bool ok, const std::string& error)
{
	return ok ? nullptr : env->NewStringUTF(error.empty() ? "Unknown error" : error.c_str());
}

} // namespace

#define NATIVE_CORE(name) Java_com_kira3_ametrine_emucore_NativeCore_##name

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
	(void)reserved;

	JNIEnv* env;
	if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
		return JNI_ERR;

	jclass native_core = env->FindClass("com/kira3/ametrine/emucore/NativeCore");
	if (!native_core)
		return JNI_ERR;

	AndroidHost::SetJavaVM(vm, static_cast<jclass>(env->NewGlobalRef(native_core)));
	env->DeleteLocalRef(native_core);
	return JNI_VERSION_1_6;
}

// ---- Setup ----

extern "C" JNIEXPORT jstring JNICALL NATIVE_CORE(initialize)(JNIEnv* env, jclass, jstring data_dir)
{
	std::string error;
	const bool ok = AndroidHost::Initialize(ToString(env, data_dir), &error);
	return ErrorToString(env, ok, error);
}

extern "C" JNIEXPORT jstring JNICALL NATIVE_CORE(getBuildVersion)(JNIEnv* env, jclass)
{
	return env->NewStringUTF(MEDNAFEN_VERSION);
}

// ---- Settings ----

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(setSetting)(JNIEnv* env, jclass, jstring section, jstring key, jstring value)
{
	try
	{
		AndroidHost::SetSetting(ToString(env, section).c_str(), ToString(env, key).c_str(),
			ToString(env, value).c_str());
	}
	catch (const std::exception& e)
	{
		__android_log_print(ANDROID_LOG_ERROR, "NativeCore", "setSetting: %s", e.what());
	}
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(commitSettings)(JNIEnv*, jclass)
{
	try
	{
		AndroidHost::CommitSettings();
	}
	catch (const std::exception& e)
	{
		__android_log_print(ANDROID_LOG_ERROR, "NativeCore", "commitSettings: %s", e.what());
	}
}

// ---- Surface ----

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(setSurface)(JNIEnv* env, jclass, jobject surface, jint width, jint height)
{
	// The reference the window gets here is the one SetWindow holds and releases when the
	// surface is replaced or dropped.
	ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
	AndroidHost::SetWindow(window, window ? (uint32_t)width : 0, window ? (uint32_t)height : 0);
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(setDisplayRefreshRate)(JNIEnv*, jclass, jfloat hz)
{
	AndroidHost::SetDisplayRefreshRate(hz);
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(setDisplayLayout)(JNIEnv*, jclass, jboolean portrait_top, jint top_margin)
{
	AndroidHost::SetDisplayLayout(portrait_top == JNI_TRUE, top_margin < 0 ? 0u : (uint32_t)top_margin);
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(setOsdSafeArea)(JNIEnv*, jclass, jint left, jint top, jint right, jint bottom)
{
	AndroidHost::SetOsdSafeArea(left < 0 ? 0u : (uint32_t)left, top < 0 ? 0u : (uint32_t)top,
		right < 0 ? 0u : (uint32_t)right, bottom < 0 ? 0u : (uint32_t)bottom);
}

// ---- VM lifecycle ----

extern "C" JNIEXPORT jstring JNICALL NATIVE_CORE(runSystem)(JNIEnv* env, jclass, jstring path, jstring save_state)
{
	std::string error;
	try
	{
		const bool ok = AndroidHost::RunSystem(ToString(env, path), ToString(env, save_state), &error);
		return ErrorToString(env, ok, error);
	}
	catch (const std::exception& e)
	{
		return env->NewStringUTF(e.what());
	}
}

extern "C" JNIEXPORT jboolean JNICALL NATIVE_CORE(hasActiveSystem)(JNIEnv*, jclass)
{
	return AndroidHost::HasActiveSystem() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(pause)(JNIEnv*, jclass)
{
	AndroidHost::RequestPause(true);
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(resume)(JNIEnv*, jclass)
{
	AndroidHost::RequestPause(false);
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(shutdown)(JNIEnv*, jclass)
{
	AndroidHost::RequestShutdown();
}

extern "C" JNIEXPORT jfloat JNICALL NATIVE_CORE(getFps)(JNIEnv*, jclass)
{
	return AndroidHost::GetFps();
}

extern "C" JNIEXPORT jlong JNICALL NATIVE_CORE(getFrameNumber)(JNIEnv*, jclass)
{
	return AndroidHost::GetFrameNumber();
}

// ---- Input and speed ----

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(setFastForward)(JNIEnv*, jclass, jboolean enabled)
{
	AndroidHost::SetFastForward(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL NATIVE_CORE(setPadBind)(JNIEnv*, jclass, jint port, jint index, jfloat value)
{
	if (port >= 0 && index >= 0)
		AndroidHost::SetPadBind((uint32_t)port, (uint32_t)index, value > 0.5f);
}

// ---- Discs ----

extern "C" JNIEXPORT jstring JNICALL NATIVE_CORE(getGameSerial)(JNIEnv* env, jclass, jstring path)
{
	const std::string serial = AndroidHost::ReadDiscSerial(ToString(env, path));
	return serial.empty() ? nullptr : env->NewStringUTF(serial.c_str());
}

extern "C" JNIEXPORT jobjectArray JNICALL NATIVE_CORE(getDiscPlaylist)(JNIEnv* env, jclass)
{
	std::vector<std::string> titles;
	int current = 0;
	if (!AndroidHost::GetDiscPlaylist(&titles, &current))
		return nullptr;

	// The current index first (as a string), then the titles (NativeCore.getDiscPlaylist).
	jclass string_class = env->FindClass("java/lang/String");
	jobjectArray ret = env->NewObjectArray((jsize)titles.size() + 1, string_class, nullptr);
	jstring index = env->NewStringUTF(std::to_string(current).c_str());
	env->SetObjectArrayElement(ret, 0, index);
	env->DeleteLocalRef(index);
	for (size_t i = 0; i < titles.size(); i++)
	{
		jstring title = env->NewStringUTF(titles[i].c_str());
		env->SetObjectArrayElement(ret, (jsize)i + 1, title);
		env->DeleteLocalRef(title);
	}
	env->DeleteLocalRef(string_class);
	return ret;
}

extern "C" JNIEXPORT jboolean JNICALL NATIVE_CORE(switchDisc)(JNIEnv*, jclass, jint index)
{
	return AndroidHost::SwitchDisc(index) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL NATIVE_CORE(insertDisc)(JNIEnv* env, jclass, jstring path)
{
	return AndroidHost::InsertDisc(ToString(env, path)) ? JNI_TRUE : JNI_FALSE;
}

// ---- BIOS ----

extern "C" JNIEXPORT jstring JNICALL NATIVE_CORE(getBiosInfoFromFd)(JNIEnv* env, jclass, jint fd)
{
	// Takes ownership of the descriptor (NativeCore.getBiosInfoFromFd).
	const std::string info = AndroidHost::GetBiosInfo(fd);
	close(fd);
	return info.empty() ? nullptr : env->NewStringUTF(info.c_str());
}
