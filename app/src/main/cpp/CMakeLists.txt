cmake_minimum_required(VERSION 3.22.1)
project(korrektur C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(${CMAKE_CURRENT_LIST_DIR}/llama-optionen.cmake)
add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../../../../llama.cpp llama-build)

add_library(korrektur SHARED kern.cpp jni.cpp)
target_link_libraries(korrektur PRIVATE llama android log)
