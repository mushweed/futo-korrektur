# Gemeinsame Schalter für llama.cpp, für die App und für werkzeug/probe.
# Nur die Bibliothek; kein Netz (kein curl, kein OpenSSL), keine Werkzeuge.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

if(ANDROID)
    # Gebaut nur fürs Pixel 9 Pro XL (Tensor G4, Armv9.2): Punktprodukt und
    # Int8-Matrixbefehle sind auf allen acht Kernen da.
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_ARM_ARCH "armv8.6-a+dotprod+i8mm" CACHE STRING "" FORCE)
    # KleidiAI (ARM): schnellere Matrixkerne, aber nur für Q4_0- und Q8_0-Modelle.
    # Q4_K_M rechnet ggml schon über seinen eigenen i8mm-Weg (q4_K_8x8) - dort
    # ändert KleidiAI nichts. Die Quellen lädt CMake beim Bauen von GitHub
    # (Releases von ARM-software/kleidiai), auf dem Gerät wird nichts nachgeladen.
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
endif()
