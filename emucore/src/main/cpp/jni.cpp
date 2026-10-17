/* jni.cpp — the one JNI entry point for this task.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Returning data taken from Mednafen's EmulatedSS (the Saturn module's MDFNGI descriptor)
 * forces the linker to pull the ss module in, which proves the whole ss module links cleanly.
 * Nothing calls this yet; NativeCore.kt gets its external functions in a later task.
 */

#include <mednafen/mednafen.h>

#include <mednafen/ss/ss.h>

// types.h (through mednafen.h) must come before common C headers like jni.h.
#include <jni.h>

// EmulatedSS is defined at global scope in src/ss/ss.cpp. mednafen.cpp declares it the same
// way, under #ifdef WANT_SS_EMU.
extern const Mednafen::MDFNGI EmulatedSS;

extern "C" JNIEXPORT jstring JNICALL
Java_com_kira3_ametrine_emucore_NativeCore_nativeLinkCheck(JNIEnv* env, jobject)
{
	return env->NewStringUTF(EmulatedSS.fullname);
}
