// Der Kern der Korrektur: ein geladenes Modell, ein fester Prompt-Anfang im
// Speicher, je Auftrag nur der Text dahinter. Kein JNI hier, damit sich der
// Kern auch auf dem Rechner prüfen lässt (werkzeug/probe.cpp).
#pragma once

#include <atomic>
#include <string>
#include <vector>

struct llama_model;
struct llama_context;
struct llama_sampler;
struct llama_vocab;
struct ggml_threadpool;

namespace korrektur {

struct Messung {
    long laden_ms = 0;        // nur beim ersten Auftrag nach dem Laden gesetzt
    int prompt_token = 0;
    long prompt_ms = 0;
    int antwort_token = 0;
    long antwort_ms = 0;
};

class Kern {
public:
    // nullptr bei Fehler, dann steht der Grund in fehler.
    static Kern *laden(const std::string &pfad, int threads, int kontext, std::string &fehler);
    ~Kern();

    // true: ergebnis enthält die Antwort des Modells (roh, ohne Nachbearbeitung).
    // false: abgebrochen (fehler leer) oder Fehler (fehler gesetzt).
    bool korrigiere(const std::string &text, int max_token, std::string &ergebnis, std::string &fehler);

    // Aus jedem Thread aufrufbar; wirkt auch mitten im Einlesen.
    void abbrechen() { abbruch_ = true; }
    // Vor jedem Auftrag, und zwar bevor geprüft wird, ob er schon abgebrochen
    // ist. korrigiere() setzt den Abbruch selbst nicht zurück, sonst ginge ein
    // Abbruch verloren, der kurz vor dem Start eintrifft.
    void zuruecksetzen() { abbruch_ = false; }

    // Stellt die Rechen-Threads um, ohne neu zu laden. poll: 0 = wartende
    // Threads schlafen, 50 = llama.cpp-Grundeinstellung (sie kreisen aktiv).
    // grosse: nur auf den schnellen Kernen rechnen (höchste Taktgrenze).
    bool threads(int anzahl, int poll, bool grosse, std::string &fehler);

    std::string info() const;
    const Messung &letzteMessung() const { return messung_; }

private:
    Kern() = default;
    bool bereiteVor(std::string &fehler);
    bool lies(const std::vector<int> &token, std::string &fehler);
    std::vector<int> zerlege(const std::string &text, bool sonderzeichen, bool spezial) const;

    llama_model *modell_ = nullptr;
    llama_context *ctx_ = nullptr;
    llama_sampler *wahl_ = nullptr;
    ggml_threadpool *pool_ = nullptr;
    std::string thread_info_;
    const llama_vocab *vokabular_ = nullptr;
    int kontext_ = 0;
    int anfang_token_ = 0;   // so viele Token des festen Anfangs liegen im Speicher
    std::string schluss_;    // was nach dem Nutzertext folgt (Ende der Runde, Antwortbeginn)
    std::string vorlage_;    // erkannte Chat-Vorlage, für info()
    long laden_ms_ = 0;
    bool laden_gemeldet_ = false;
    std::atomic<bool> abbruch_{false};
    Messung messung_;
};

} // namespace korrektur
