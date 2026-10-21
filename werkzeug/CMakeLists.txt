# Baut den Kern für den Rechner, zum Prüfen ohne Handy:
#   cmake -S werkzeug -B build-probe -DCMAKE_BUILD_TYPE=Release
#   cmake --build build-probe -j
#   build-probe/probe modell.gguf werkzeug/saetze.txt
cmake_minimum_required(VERSION 3.22)
project(probe C CXX)
set(CMAKE_CXX_STANDARD 17)

include(${CMAKE_CURRENT_LIST_DIR}/../app/src/main/cpp/llama-optionen.cmake)
add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../llama.cpp llama-build)

add_executable(probe probe.cpp ../app/src/main/cpp/kern.cpp)
target_link_libraries(probe PRIVATE llama)
