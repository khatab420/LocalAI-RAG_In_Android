cmake_minimum_required(VERSION 3.22.1)
project("localai" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp is downloaded automatically at configure time (needs internet on first sync).
include(FetchContent)

# Keep the downloaded source and its build output outside the Gradle build dir,
# so "Clean Project" does not force a re-download/recompile of all of llama.cpp.
set(FETCHCONTENT_BASE_DIR "${CMAKE_SOURCE_DIR}/.fetchcontent" CACHE PATH "" FORCE)

set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    ON  CACHE BOOL "" FORCE)

# Use a release tarball instead of `git clone`; git-over-HTTPS stalls on some networks.
# If the source was pre-extracted, use it and skip the network entirely.
if(EXISTS "${FETCHCONTENT_BASE_DIR}/llama-src/CMakeLists.txt")
    set(FETCHCONTENT_SOURCE_DIR_LLAMA "${FETCHCONTENT_BASE_DIR}/llama-src" CACHE PATH "" FORCE)
endif()
FetchContent_Declare(
    llama
    URL
      "https://gh-proxy.com/https://github.com/ggml-org/llama.cpp/archive/refs/tags/b11946.tar.gz"
      "https://github.com/ggml-org/llama.cpp/archive/refs/tags/b11946.tar.gz"
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(llama)

add_library(localai SHARED llama_jni.cpp)
target_link_libraries(localai PRIVATE llama log android)
