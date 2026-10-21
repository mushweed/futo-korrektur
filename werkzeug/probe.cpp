// Prüft den Kern auf dem Rechner, ohne Android:
//   probe <modell.gguf> <saetze.txt> [threads] [poll]
// Je Zeile der Datei ein Satz; ausgegeben werden Antwort und Messwerte.
#include <cstdio>
#include <fstream>
#include <string>

#include "../app/src/main/cpp/kern.h"

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "Aufruf: probe <modell.gguf> <saetze.txt> [threads] [poll]\n");
        return 2;
    }
    int threads = argc > 3 ? atoi(argv[3]) : 4;
    int poll = argc > 4 ? atoi(argv[4]) : 0;
    std::string fehler;
    auto *kern = korrektur::Kern::laden(argv[1], threads, 2048, fehler);
    if (!kern) {
        fprintf(stderr, "Fehler: %s\n", fehler.c_str());
        return 1;
    }
    if (!kern->threads(threads, poll, false, fehler)) {
        fprintf(stderr, "Fehler: %s\n", fehler.c_str());
        return 1;
    }
    printf("%s\n\n", kern->info().c_str());

    std::ifstream datei(argv[2]);
    std::string satz;
    int fehlschlaege = 0;
    while (std::getline(datei, satz)) {
        if (satz.empty()) continue;
        std::string ergebnis;
        int max_token = (int) satz.size() / 2 + 64;
        kern->zuruecksetzen();
        if (!kern->korrigiere(satz, max_token, ergebnis, fehler)) {
            printf("FEHLER  %s\n        %s\n\n", satz.c_str(), fehler.c_str());
            fehlschlaege++;
            continue;
        }
        auto &m = kern->letzteMessung();
        printf("vorher  %s\nnachher %s\n        %d+%d Token, %ld+%ld ms%s\n\n", satz.c_str(), ergebnis.c_str(),
               m.prompt_token, m.antwort_token, m.prompt_ms, m.antwort_ms,
               m.laden_ms ? (", Laden " + std::to_string(m.laden_ms) + " ms").c_str() : "");
    }
    delete kern;
    return fehlschlaege ? 1 : 0;
}
