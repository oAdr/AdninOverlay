#include <jni.h>
#include <stdint.h>
#include "forge-mapper-bytes.h"

extern "C" int adnin_forge_entry(void*, unsigned long, void*) { return 1; }

// This facade is called only from Adnin's pinned lookup instructions. The JVM's
// JNI table, game classes and unrelated native libraries are never modified.
struct ForgeState { jclass mapper; jmethodID find_class, find_member; unsigned long initializing; };
extern "C" unsigned char __ImageBase;
#ifndef FORGE_STATE_LINK_RVA
#define FORGE_STATE_LINK_RVA 0x100000
#endif
static ForgeState& state() {
#ifdef ADNIN_FORGE_FIXTURE
    static ForgeState fixture;
    return fixture;
#else
    uintptr_t base = reinterpret_cast<uintptr_t>(&__ImageBase);
    // The state lives in another PE section, outside the linker symbol's object.
    __asm__("" : "+r"(base));
    return *reinterpret_cast<ForgeState*>(base + FORGE_STATE_LINK_RVA);
#endif
}
#define mapper state().mapper
#define find_class state().find_class
#define find_member state().find_member

static bool ready(JNIEnv* env) {
    if (__atomic_load_n(&mapper,__ATOMIC_ACQUIRE)) return true;
    if (!env || env->ExceptionCheck()) return false;
    unsigned long expected=0;
    if (!__atomic_compare_exchange_n(&state().initializing,&expected,1,false,__ATOMIC_ACQUIRE,__ATOMIC_RELAXED)) return false;
    struct Guard { ~Guard() { __atomic_store_n(&state().initializing,0,__ATOMIC_RELEASE); } } guard;
    if (__atomic_load_n(&mapper,__ATOMIC_ACQUIRE)) return true;
    jclass loader_type = env->FindClass("java/lang/ClassLoader");
    if (!loader_type) return false;
    jmethodID get_loader = env->GetStaticMethodID(loader_type, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    if (!get_loader) { env->DeleteLocalRef(loader_type); return false; }
    jobject loader = env->CallStaticObjectMethodA(loader_type, get_loader, nullptr);
    jmethodID load = env->GetMethodID(loader_type, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    env->DeleteLocalRef(loader_type);
    if (!loader || env->ExceptionCheck()) return false;
    jstring launch_name = env->NewStringUTF("net.minecraft.launchwrapper.Launch");
    if (!load || !launch_name || env->ExceptionCheck()) {
        env->DeleteLocalRef(loader);
        if (launch_name) env->DeleteLocalRef(launch_name);
        return false;
    }
    jvalue launch_argument[1]; launch_argument[0].l = launch_name;
    jclass launch = static_cast<jclass>(env->CallObjectMethodA(loader, load, launch_argument));
    env->DeleteLocalRef(launch_name);
    env->DeleteLocalRef(loader);
    if (!launch || env->ExceptionCheck()) return false;
    jfieldID game_loader = env->GetStaticFieldID(launch, "classLoader", "Lnet/minecraft/launchwrapper/LaunchClassLoader;");
    if (!game_loader) { env->DeleteLocalRef(launch); return false; }
    loader = env->GetStaticObjectField(launch, game_loader);
    env->DeleteLocalRef(launch);
    if (!loader || env->ExceptionCheck()) return false;
    jclass local = env->DefineClass("adnin/forge/RuntimeMappings", loader,
            reinterpret_cast<const jbyte*>(ADNIN_FORGE_MAPPER), sizeof(ADNIN_FORGE_MAPPER));
    env->DeleteLocalRef(loader);
    if (!local || env->ExceptionCheck()) return false;
    jmethodID classes = env->GetStaticMethodID(local, "findClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jmethodID members = env->GetStaticMethodID(local, "findMember",
            "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/Object;");
    if (!classes || !members || env->ExceptionCheck()) {
        env->DeleteLocalRef(local);
        return false;
    }
    jclass global = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (!global || env->ExceptionCheck()) return false;
    find_class = classes;
    find_member = members;
    __atomic_store_n(&mapper,global,__ATOMIC_RELEASE);
    return true;
}

extern "C" __declspec(dllexport) jclass JNICALL adnin_forge_find(JNIEnv* env, const char* name) {
    if (!ready(env) || !name) return nullptr;
    jstring text = env->NewStringUTF(name);
    if (!text) return nullptr;
    jvalue values[1]; values[0].l = text;
    jclass result = static_cast<jclass>(env->CallStaticObjectMethodA(mapper, find_class, values));
    env->DeleteLocalRef(text);
    return result;
}

static jobject member(JNIEnv* env, jclass owner, const char* name, const char* descriptor, jint kind) {
    if (!ready(env) || !owner || !name || !descriptor) return nullptr;
    jstring text = env->NewStringUTF(name);
    if (!text) return nullptr;
    jstring signature = env->NewStringUTF(descriptor);
    if (!signature) { env->DeleteLocalRef(text); return nullptr; }
    jvalue values[4]; values[0].l=owner; values[1].l=text; values[2].l=signature; values[3].i=kind;
    jobject result = env->CallStaticObjectMethodA(mapper, find_member, values);
    env->DeleteLocalRef(signature);
    env->DeleteLocalRef(text);
    return result;
}

static jmethodID method(JNIEnv* env, jclass owner, const char* name, const char* signature, jint kind) {
    jobject reflected = member(env, owner, name, signature, kind);
    if (!reflected || env->ExceptionCheck()) return nullptr;
    jmethodID result = env->FromReflectedMethod(reflected);
    env->DeleteLocalRef(reflected);
    return result;
}

static jfieldID field(JNIEnv* env, jclass owner, const char* name, const char* signature, jint kind) {
    jobject reflected = member(env, owner, name, signature, kind);
    if (!reflected || env->ExceptionCheck()) return nullptr;
    jfieldID result = env->FromReflectedField(reflected);
    env->DeleteLocalRef(reflected);
    return result;
}

extern "C" __declspec(dllexport) jmethodID JNICALL adnin_forge_method(JNIEnv* env, jclass owner, const char* name, const char* signature) {
    return method(env, owner, name, signature, 0);
}
extern "C" __declspec(dllexport) jmethodID JNICALL adnin_forge_static_method(JNIEnv* env, jclass owner, const char* name, const char* signature) {
    return method(env, owner, name, signature, 1);
}
extern "C" __declspec(dllexport) jfieldID JNICALL adnin_forge_field(JNIEnv* env, jclass owner, const char* name, const char* signature) {
    return field(env, owner, name, signature, 2);
}
extern "C" __declspec(dllexport) jfieldID JNICALL adnin_forge_static_field(JNIEnv* env, jclass owner, const char* name, const char* signature) {
    return field(env, owner, name, signature, 3);
}
