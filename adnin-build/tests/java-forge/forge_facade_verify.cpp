#ifdef ADNIN_FORGE_FINAL_IMAGE
#include <jni.h>
#include <windows.h>
#include <stdint.h>
#include <string.h>
#include "forge-final-image.h"

static unsigned char* mapped_image;
static bool initialize_image() {
    if (mapped_image) return true;
    auto* image=static_cast<unsigned char*>(VirtualAlloc(nullptr,FORGE_IMAGE_SIZE,MEM_RESERVE|MEM_COMMIT,PAGE_READWRITE));
    if (!image) return false;
    memcpy(image+FORGE_CODE_RVA,FORGE_CODE,sizeof(FORGE_CODE));
    for (unsigned long rva : FORGE_RELOCATIONS)
        *reinterpret_cast<uint64_t*>(image+rva)+=reinterpret_cast<uintptr_t>(image)-FORGE_PREFERRED_BASE;
    DWORD old;
    if (!VirtualProtect(image+FORGE_CODE_RVA,sizeof(FORGE_CODE),PAGE_EXECUTE_READ,&old)) {
        VirtualFree(image,0,MEM_RELEASE);
        return false;
    }
    FlushInstructionCache(GetCurrentProcess(),image+FORGE_CODE_RVA,sizeof(FORGE_CODE));
    mapped_image=image;
    return true;
}

static jclass adnin_forge_find(JNIEnv* env,const char* name) {
    using Function=jclass(JNICALL*)(JNIEnv*,const char*);
    return reinterpret_cast<Function>(mapped_image+FORGE_FIND_RVA)(env,name);
}
#define FORGE_MEMBER_WRAPPER(name,type) \
static type adnin_forge_##name(JNIEnv* env,jclass owner,const char* name,const char* signature) { \
    using Function=type(JNICALL*)(JNIEnv*,jclass,const char*,const char*); \
    return reinterpret_cast<Function>(mapped_image+FORGE_##name##_RVA)(env,owner,name,signature); \
}
FORGE_MEMBER_WRAPPER(method,jmethodID)
FORGE_MEMBER_WRAPPER(static_method,jmethodID)
FORGE_MEMBER_WRAPPER(field,jfieldID)
FORGE_MEMBER_WRAPPER(static_field,jfieldID)
#else
#define ADNIN_FORGE_FIXTURE 1
#include "../../src/native/forge-jni.cpp"
#endif

static bool check(JNIEnv* env, bool ok, const char* message) {
    if (env->ExceptionCheck()) return false;
    if (ok) return true;
    env->ThrowNew(env->FindClass("java/lang/AssertionError"),message);
    return false;
}
extern "C" __declspec(dllexport) void JNICALL Java_ForgeFacadeVerify_verify(JNIEnv* env,jclass verifier) {
#ifdef ADNIN_FORGE_FINAL_IMAGE
    if (!check(env,initialize_image(),"Final Forge image could not be mapped")) return;
#endif
    jmethodID unloaded=env->GetStaticMethodID(verifier,"uninitialized","(Ljava/lang/String;)Ljava/lang/Class;");
    jmethodID initialized=env->GetStaticMethodID(verifier,"initialized","(Ljava/lang/String;)Z");
    if (!check(env,unloaded && initialized,"Initialization fixture helpers missing")) return;
    auto was_initialized=[&](const char* name) {
        jstring text=env->NewStringUTF(name);
        jvalue argument[1];argument[0].l=text;
        jboolean result=env->CallStaticBooleanMethodA(verifier,initialized,argument);
        env->DeleteLocalRef(text);
        return result!=0;
    };
    auto load_uninitialized=[&](const char* name) {
        jstring text=env->NewStringUTF(name);
        jvalue argument[1];argument[0].l=text;
        jclass result=static_cast<jclass>(env->CallStaticObjectMethodA(verifier,unloaded,argument));
        env->DeleteLocalRef(text);
        return result;
    };
    jclass found=adnin_forge_find(env,"net/minecraft/fixture/InitializationProbe$Found");
    if (!check(env,found && was_initialized("Found"),"FindClass did not initialize its result")) return;
    env->DeleteLocalRef(found);
    jclass methods=load_uninitialized("Methods");
    jmethodID resolving=adnin_forge_static_method(env,methods,"resolve",
            "(Lnet/minecraft/fixture/InitializationProbe$Argument;)Lnet/minecraft/fixture/InitializationProbe$Argument;");
    if (!check(env,resolving && was_initialized("Methods"),"Method lookup did not initialize its owner")) return;
    env->DeleteLocalRef(methods);
    jclass fields=load_uninitialized("Fields");
    jfieldID resolving_field=adnin_forge_static_field(env,fields,"value","Lnet/minecraft/fixture/InitializationProbe$Argument;");
    if (!check(env,resolving_field && was_initialized("Fields"),"Field lookup did not initialize its owner")) return;
    env->DeleteLocalRef(fields);
    if (!check(env,!was_initialized("Argument"),"Member descriptor lookup initialized an argument class")) return;
    jclass type=adnin_forge_find(env,"q");
    if (!check(env,type!=nullptr,"Mapped class missing")) return;
    jmethodID constructor=adnin_forge_method(env,type,"<init>","()V");
    if (!check(env,constructor!=nullptr,"Constructor missing")) return;
    jobject instance=env->NewObjectA(type,constructor,nullptr);
    if (!check(env,instance!=nullptr,"Construction failed")) return;
    jmethodID number=adnin_forge_method(env,type,"a","(I)I");
    if (!check(env,number!=nullptr,"Inherited SRG method missing")) return;
    jvalue value[1];value[0].i=5;
    if (!check(env,env->CallIntMethodA(instance,number,value)==22,"Mapped method returned the wrong value")) return;
    jfieldID field=adnin_forge_field(env,type,"x","I");
    if (!check(env,field && env->GetIntField(instance,field)==17,"Inherited private field missing")) return;
    jfieldID static_field=adnin_forge_static_field(env,type,"s","I");
    if (!check(env,static_field && env->GetStaticIntField(type,static_field)==29,"Static field missing")) return;
    jmethodID static_method=adnin_forge_static_method(env,type,"b","()Lq;");
    if (!check(env,static_method!=nullptr,"Static method missing")) return;
    jobject other=env->CallStaticObjectMethodA(type,static_method,nullptr);
    if (!check(env,other && env->IsInstanceOf(other,type),"Static method has the wrong mapped return type")) return;
    if (!check(env,adnin_forge_method(env,type,"a","([Lq;)[I")!=nullptr,"Array descriptor failed")) return;
    if (!check(env,adnin_forge_find(env,"[Lq;")!=nullptr,"Mapped array class failed")) return;
    adnin_forge_method(env,type,"missing","()V");
    if (!env->ExceptionCheck()) { check(env,false,"Lookup failure did not preserve its exception"); return; }
    env->ExceptionClear();
    check(env,adnin_forge_method(env,type,"a","(I)I")!=nullptr,"Lookup cannot recover after the caller clears an exception");
    env->DeleteLocalRef(other);env->DeleteLocalRef(instance);env->DeleteLocalRef(type);
}
