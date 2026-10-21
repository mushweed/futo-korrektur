#include "kern.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <functional>
#include <sys/stat.h>

#include "ggml-cpu.h"
#include "llama.h"

namespace korrektur {

namespace {

// Die Anweisung. Wortgleich zur Vorgabe aus dem Projektauftrag: nur
// Rechtschreibung, Grammatik, Zeichensetzung, Groß-/Kleinschreibung.
const char *ANWEISUNG =
    "Du korrigierst deutsche Texte, die auf dem Handy getippt wurden. "
    "Korrigiere ausschließlich Rechtschreibung, Grammatik, Zeichensetzung sowie "
    "Groß- und Kleinschreibung. Behalte Wortwahl, Ton, Dialekt, Umgangssprache, "
    "Emojis, Namen und Abkürzungen unverändert bei. Formuliere nichts um, ergänze "
    "nichts und lass nichts weg. Ist der Text schon richtig, gib ihn unverändert "
    "zurück. Antworte nur mit dem korrigierten Text, ohne Anführungszeichen, "
    "Erklärung oder Einleitung.";

// Beispiele zeigen kleinen Modellen besser als jede Anweisung, was gemeint ist.
// Sie liegen im festen Anfang und kosten deshalb je Auftrag keine Zeit.
const char *BEISPIELE[][2] = {
    {"ich hab heute keine zeit mehr weil ich noch einkaufen muss",
     "Ich hab heute keine Zeit mehr, weil ich noch einkaufen muss."},
    {"Kannst du mir morgen bei umzug helfen? 😅 wär echt nett",
     "Kannst du mir morgen beim Umzug helfen? 😅 Wär echt nett."},
    {"Treffen um 8 bei Kathi, bring bitte die Unterlagen mit.",
     "Treffen um 8 bei Kathi, bring bitte die Unterlagen mit."},
    {"voll schön das du am samstag auch kommst lg",
     "Voll schön, dass du am Samstag auch kommst lg"},
    {"bin gleich da, steh noch im stau 🙈",
     "Bin gleich da, steh noch im Stau 🙈"},
};

// Platzhalter für den Nutzertext: davor liegt der feste Anfang, danach der Schluss.
const char *PLATZHALTER = "\x01TEXT\x01";

long ms_seit(std::chrono::steady_clock::time_point start) {
    return (long) std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - start).count();
}

void still(ggml_log_level stufe, const char *text, void *) {
    if (stufe == GGML_LOG_LEVEL_ERROR) fputs(text, stderr);
}

bool abbruch_gewuenscht(void *daten) {
    return static_cast<std::atomic<bool> *>(daten)->load();
}

// Die schnellen Kerne: alle außer denen mit der niedrigsten Taktgrenze. Auf
// dem Tensor G4 sind das 4-7 (3× A720, 1× X4); 0-3 sind die Sparkerne A520.
std::vector<int> grosse_kerne() {
    std::vector<std::pair<int, long>> kerne;
    for (int i = 0; i < 64; i++) {
        char pfad[96];
        snprintf(pfad, sizeof(pfad), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        FILE *f = fopen(pfad, "r");
        if (!f) break;
        long takt = 0;
        if (fscanf(f, "%ld", &takt) != 1) takt = 0;
        fclose(f);
        kerne.emplace_back(i, takt);
    }
    std::vector<int> grosse;
    if (kerne.empty()) return grosse;
    long kleinster = kerne[0].second;
    for (auto &k : kerne) kleinster = std::min(kleinster, k.second);
    for (auto &k : kerne) {
        if (k.second > kleinster) grosse.push_back(k.first);
    }
    return grosse; // leer: alle gleich schnell, keine Einschränkung
}

} // namespace

Kern *Kern::laden(const std::string &pfad, int threads, int kontext, const std::string &zwischenablage,
                  std::string &fehler) {
    static bool einmal = [] {
        llama_log_set(still, nullptr);
        llama_backend_init();
        return true;
    }();
    (void) einmal;

    auto start = std::chrono::steady_clock::now();
    auto *kern = new Kern();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    kern->modell_ = llama_model_load_from_file(pfad.c_str(), mp);
    if (!kern->modell_) {
        fehler = "Modell lässt sich nicht laden: " + pfad;
        delete kern;
        return nullptr;
    }
    kern->vokabular_ = llama_model_get_vocab(kern->modell_);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) kontext;
    cp.n_batch = (uint32_t) kontext;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    kern->ctx_ = llama_init_from_model(kern->modell_, cp);
    if (!kern->ctx_) {
        fehler = "Kontext lässt sich nicht anlegen";
        delete kern;
        return nullptr;
    }
    kern->kontext_ = (int) llama_n_ctx(kern->ctx_);
    llama_set_abort_callback(kern->ctx_, abbruch_gewuenscht, &kern->abbruch_);

    // Gierig: bei einer Korrektur gibt es nichts zu würfeln.
    kern->wahl_ = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(kern->wahl_, llama_sampler_init_greedy());

    if (!kern->threads(threads, 0, true, fehler)) {
        delete kern;
        return nullptr;
    }
    if (!kern->bereiteVor(pfad, zwischenablage, fehler)) {
        delete kern;
        return nullptr;
    }
    kern->laden_ms_ = ms_seit(start);
    return kern;
}

Kern::~Kern() {
    if (wahl_) llama_sampler_free(wahl_);
    if (ctx_) llama_free(ctx_);
    if (pool_) ggml_threadpool_free(pool_);
    if (modell_) llama_model_free(modell_);
}

bool Kern::threads(int anzahl, int poll, bool grosse, std::string &fehler) {
    ggml_threadpool_params p = ggml_threadpool_params_default(anzahl);
    p.poll = (uint32_t) poll;
    std::string kerne = "alle Kerne";
    if (grosse) {
        auto liste = grosse_kerne();
        if (!liste.empty()) {
            kerne = "Kerne";
            for (int k : liste) {
                if (k < GGML_MAX_N_THREADS) p.cpumask[k] = true;
                kerne += " " + std::to_string(k);
            }
        }
    }
    ggml_threadpool *neu = ggml_threadpool_new(&p);
    if (!neu) {
        fehler = "Threadpool lässt sich nicht anlegen";
        return false;
    }
    llama_detach_threadpool(ctx_);
    if (pool_) ggml_threadpool_free(pool_);
    pool_ = neu;
    llama_attach_threadpool(ctx_, pool_, pool_);
    llama_set_n_threads(ctx_, anzahl, anzahl);
    thread_info_ = std::to_string(anzahl) + " Threads, " + kerne + (poll ? ", kreisend" : ", schlafend");
    return true;
}

std::vector<int> Kern::zerlege(const std::string &text, bool sonderzeichen, bool spezial) const {
    int n = -llama_tokenize(vokabular_, text.data(), (int32_t) text.size(), nullptr, 0, sonderzeichen, spezial);
    std::vector<llama_token> token(std::max(n, 0));
    if (n > 0) {
        llama_tokenize(vokabular_, text.data(), (int32_t) text.size(), token.data(), n, sonderzeichen, spezial);
    }
    return {token.begin(), token.end()};
}

bool Kern::lies(const std::vector<int> &token, std::string &fehler) {
    size_t i = 0;
    std::vector<llama_token> puffer(token.begin(), token.end());
    while (i < puffer.size()) {
        int n = (int) std::min(puffer.size() - i, (size_t) kontext_);
        int rc = llama_decode(ctx_, llama_batch_get_one(puffer.data() + i, n));
        if (rc == 2) return false; // abgebrochen
        if (rc != 0) {
            fehler = "llama_decode schlug fehl (" + std::to_string(rc) + ")";
            return false;
        }
        i += n;
    }
    return true;
}

bool Kern::bereiteVor(const std::string &modell, const std::string &zwischenablage, std::string &fehler) {
    std::vector<llama_chat_message> verlauf;
    verlauf.push_back({"system", ANWEISUNG});
    for (auto &b : BEISPIELE) {
        verlauf.push_back({"user", b[0]});
        verlauf.push_back({"assistant", b[1]});
    }
    verlauf.push_back({"user", PLATZHALTER});

    const char *eigene = llama_model_chat_template(modell_, nullptr);
    std::vector<char> puffer(16384);
    auto anwenden = [&](const char *vorlage) {
        int n = llama_chat_apply_template(vorlage, verlauf.data(), verlauf.size(), true,
                                          puffer.data(), (int32_t) puffer.size());
        if (n > (int) puffer.size()) {
            puffer.resize(n);
            n = llama_chat_apply_template(vorlage, verlauf.data(), verlauf.size(), true,
                                          puffer.data(), (int32_t) puffer.size());
        }
        return n;
    };
    int n = eigene ? anwenden(eigene) : -1;
    vorlage_ = "eigene";
    if (n < 0) {
        // Unbekannte Vorlage: ChatML ist bei kleinen Instruct-Modellen am häufigsten.
        n = anwenden("chatml");
        vorlage_ = "chatml (Ersatz)";
    }
    if (n < 0) {
        fehler = "Keine passende Chat-Vorlage";
        return false;
    }
    std::string ganz(puffer.data(), n);
    size_t stelle = ganz.find(PLATZHALTER);
    if (stelle == std::string::npos) {
        fehler = "Chat-Vorlage verschluckt den Text";
        return false;
    }
    std::string anfang = ganz.substr(0, stelle);
    schluss_ = ganz.substr(stelle + strlen(PLATZHALTER));

    // Qwen3 und Verwandte denken sonst erst laut nach. Ein leerer Denkblock
    // ist deren eigener Weg, das abzuschalten.
    if (eigene && strstr(eigene, "enable_thinking")) {
        schluss_ += "<think>\n\n</think>\n\n";
        vorlage_ += ", ohne Denken";
    }

    auto token = zerlege(anfang, true, true);
    if ((int) token.size() >= kontext_ / 2) {
        fehler = "Fester Anfang ist zu lang für den Kontext";
        return false;
    }
    abbruch_ = false;
    llama_memory_clear(llama_get_memory(ctx_), true);

    // Den Anfang einzulesen kostet auf dem Handy mehrere Sekunden. Einmal
    // gerechnet, liegt er in der Zwischenablage, je Modell und Anfang eine
    // Datei; Größe und Änderungszeit des Modells gehen in den Namen ein.
    std::string datei, vorsilbe;
    if (!zwischenablage.empty()) {
        struct stat st {};
        stat(modell.c_str(), &st);
        size_t m = std::hash<std::string>{}(modell + "|" + std::to_string(st.st_size) + "|" +
                                             std::to_string(st.st_mtime) + "|" + std::to_string(kontext_));
        size_t a = std::hash<std::string>{}(anfang);
        char name[96];
        snprintf(name, sizeof(name), "anfang-%016zx-", m);
        vorsilbe = name;
        snprintf(name, sizeof(name), "%016zx.bin", a);
        datei = zwischenablage + "/" + vorsilbe + name;

        std::vector<llama_token> gelesen(token.size() + 1);
        size_t n = 0;
        if (llama_state_seq_load_file(ctx_, datei.c_str(), 0, gelesen.data(), gelesen.size(), &n) > 0 &&
            n == token.size() && std::equal(token.begin(), token.end(), gelesen.begin())) {
            anfang_token_ = (int) token.size();
            anfang_gespeichert_ = true;
            return true;
        }
        llama_memory_clear(llama_get_memory(ctx_), true);
    }

    if (!lies(token, fehler)) {
        if (fehler.empty()) fehler = "Vorbereitung abgebrochen";
        return false;
    }
    anfang_token_ = (int) token.size();

    if (!datei.empty()) {
        // Ältere Stände desselben Modells (anderer Anfang) wegräumen.
        if (DIR *d = opendir(zwischenablage.c_str())) {
            while (dirent *e = readdir(d)) {
                std::string n = e->d_name;
                if (n.rfind(vorsilbe, 0) == 0) remove((zwischenablage + "/" + n).c_str());
            }
            closedir(d);
        }
        std::vector<llama_token> t(token.begin(), token.end());
        llama_state_seq_save_file(ctx_, datei.c_str(), 0, t.data(), t.size());
    }
    return true;
}

bool Kern::korrigiere(const std::string &text, int max_token, std::string &ergebnis, std::string &fehler) {
    ergebnis.clear();
    fehler.clear();
    messung_ = Messung();
    if (!laden_gemeldet_) {
        messung_.laden_ms = laden_ms_;
        laden_gemeldet_ = true;
    }

    // Alles hinter dem festen Anfang verwerfen, der Anfang bleibt liegen.
    llama_memory_seq_rm(llama_get_memory(ctx_), 0, anfang_token_, -1);

    // Der Nutzertext wird ohne Sonderzeichen zerlegt: steht darin zufällig
    // "<|im_end|>", soll das Text bleiben und nicht die Runde beenden.
    auto token = zerlege(text, false, false);
    auto schluss = zerlege(schluss_, false, true);
    token.insert(token.end(), schluss.begin(), schluss.end());

    if (anfang_token_ + (int) token.size() + max_token > kontext_) {
        fehler = "Text ist zu lang";
        return false;
    }

    auto start = std::chrono::steady_clock::now();
    if (!lies(token, fehler)) return false;
    messung_.prompt_token = (int) token.size();
    messung_.prompt_ms = ms_seit(start);

    start = std::chrono::steady_clock::now();
    llama_sampler_reset(wahl_);
    char stueck[256];
    for (int i = 0; i < max_token; i++) {
        if (abbruch_) return false;
        llama_token t = llama_sampler_sample(wahl_, ctx_, -1);
        if (llama_vocab_is_eog(vokabular_, t)) break;
        int n = llama_token_to_piece(vokabular_, t, stueck, sizeof(stueck), 0, false);
        if (n > 0) ergebnis.append(stueck, n);
        messung_.antwort_token++;
        if (!lies({t}, fehler)) return false;
    }
    if (abbruch_) return false;
    messung_.antwort_ms = ms_seit(start);
    return true;
}

std::string Kern::info() const {
    char beschreibung[256];
    llama_model_desc(modell_, beschreibung, sizeof(beschreibung));
    char zeile[512];
    snprintf(zeile, sizeof(zeile), "%s · %.0f Mio. Parameter · %.0f MB · Vorlage %s · Anfang %d Token · %s",
             beschreibung, llama_model_n_params(modell_) / 1e6, llama_model_size(modell_) / 1048576.0,
             vorlage_.c_str(), anfang_token_, thread_info_.c_str());
    if (anfang_gespeichert_) strncat(zeile, " · Anfang aus Zwischenablage", sizeof(zeile) - strlen(zeile) - 1);
    return zeile;
}

} // namespace korrektur
