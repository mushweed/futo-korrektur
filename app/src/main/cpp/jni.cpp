// Dünne Brücke zu KernNativ.kt. Texte gehen als UTF-8-Bytes hin und zurück:
// JNIs NewStringUTF erwartet "modified UTF-8" und zerlegt Emojis außerhalb
// der Grundebene, und Emojis gehören zu dem, was erhalten bleiben muss.
#include <jni.h>

#include <string>

#include "kern.h"

using korrektur::Kern;

namespace {

std::string bytes(JNIEnv *env, jbyteArray feld) {
    jsize n = env->GetArrayLength(feld);
    std::string s(n, '\0');
    env->GetByteArrayRegion(feld, 0, n, reinterpret_cast<jbyte *>(s.data()));
    return s;
}

jbyteArray feld(JNIEnv *env, const std::string &s) {
    jbyteArray f = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(f, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return f;
}

void wirf(JNIEnv *env, const std::string &meldung) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), meldung.c_str());
}

Kern *kern(jlong zeiger) { return reinterpret_cast<Kern *>(zeiger); }

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_de_hnshnsen_korrektur_KernNativ_laden(JNIEnv *env, jclass, jbyteArray pfad, jint threads, jint kontext) {
    std::string fehler;
    Kern *k = Kern::laden(bytes(env, pfad), threads, kontext, fehler);
    if (!k) {
        wirf(env, fehler);
        return 0;
    }
    return reinterpret_cast<jlong>(k);
}

JNIEXPORT jbyteArray JNICALL
Java_de_hnshnsen_korrektur_KernNativ_korrigiere(JNIEnv *env, jclass, jlong zeiger, jbyteArray text, jint max_token) {
    std::string ergebnis, fehler;
    if (kern(zeiger)->korrigiere(bytes(env, text), max_token, ergebnis, fehler)) return feld(env, ergebnis);
    if (!fehler.empty()) wirf(env, fehler);
    return nullptr; // abgebrochen
}

JNIEXPORT jlongArray JNICALL
Java_de_hnshnsen_korrektur_KernNativ_messung(JNIEnv *env, jclass, jlong zeiger) {
    const auto &m = kern(zeiger)->letzteMessung();
    jlong werte[] = {m.laden_ms, m.prompt_token, m.prompt_ms, m.antwort_token, m.antwort_ms};
    jlongArray f = env->NewLongArray(5);
    env->SetLongArrayRegion(f, 0, 5, werte);
    return f;
}

JNIEXPORT jbyteArray JNICALL
Java_de_hnshnsen_korrektur_KernNativ_info(JNIEnv *env, jclass, jlong zeiger) {
    return feld(env, kern(zeiger)->info());
}

JNIEXPORT void JNICALL
Java_de_hnshnsen_korrektur_KernNativ_abbrechen(JNIEnv *, jclass, jlong zeiger) {
    kern(zeiger)->abbrechen();
}

JNIEXPORT void JNICALL
Java_de_hnshnsen_korrektur_KernNativ_zuruecksetzen(JNIEnv *, jclass, jlong zeiger) {
    kern(zeiger)->zuruecksetzen();
}

JNIEXPORT void JNICALL
Java_de_hnshnsen_korrektur_KernNativ_freigeben(JNIEnv *, jclass, jlong zeiger) {
    delete kern(zeiger);
}

} // extern "C"
