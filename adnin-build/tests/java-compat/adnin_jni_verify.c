/* Owned test shim: defines a harmless generated bootstrap class and reads JVMTI
 * initialization status. It never loads the supplied DLL or invokes game code. */
#include <jni.h>
#include <jvmti.h>

JNIEXPORT jclass JNICALL Java_AdninSignedBootstrapVerify_define(JNIEnv *env, jclass unused,
    jobject loader, jstring name, jbyteArray bytes) {
    const char *class_name = (*env)->GetStringUTFChars(env, name, 0);
    if (!class_name) return 0;
    jbyte *data = (*env)->GetByteArrayElements(env, bytes, 0);
    if (!data) { (*env)->ReleaseStringUTFChars(env, name, class_name); return 0; }
    jsize length = (*env)->GetArrayLength(env, bytes);
    jclass defined = (*env)->DefineClass(env, class_name, loader, data, length);
    (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, name, class_name);
    return defined;
}

JNIEXPORT jboolean JNICALL Java_AdninSignedBootstrapVerify_initialized(JNIEnv *env, jclass unused, jclass target) {
    JavaVM *vm = 0;
    jvmtiEnv *jvmti = 0;
    jint status = 0;
    if ((*env)->GetJavaVM(env, &vm) != JNI_OK ||
        (*vm)->GetEnv(vm, (void **)&jvmti, JVMTI_VERSION_1_0) != JNI_OK || !jvmti ||
        (*jvmti)->GetClassStatus(jvmti, target, &status) != JVMTI_ERROR_NONE) {
        jclass error = (*env)->FindClass(env, "java/lang/AssertionError");
        if (error) (*env)->ThrowNew(env, error, "Could not check JVMTI class initialization status");
        return JNI_TRUE;
    }
    return (status & JVMTI_CLASS_STATUS_INITIALIZED) ? JNI_TRUE : JNI_FALSE;
}
