# Desktop-only CMake project — NOT part of the Android/Gradle build.
#
# This is what lets the shared pure-C++ DSP code (starting tiny, with the
# sine generator; growing large in Phase 3 with the calibration sweep +
# matched filter) be developed and tested on a laptop with a debugger and
# an instant edit/test loop, instead of by ear on a phone. Configure and
# run it directly with a desktop CMake + compiler:
#
#   cmake -S . -B build
#   cmake --build build
#   ctest --test-dir build --output-on-failure
#
# Requires only a C++17 compiler and CMake — no Android SDK/NDK, no Java.
cmake_minimum_required(VERSION 3.22)
project(songnotes_audio_host_tests CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)
set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
FetchContent_Declare(
    googletest
    URL https://github.com/google/googletest/archive/refs/tags/v1.15.2.zip
)
FetchContent_MakeAvailable(googletest)

# The same sine_wave.cpp that ships in the Android .so, compiled here
# unmodified — no Android/Oboe/JNI headers anywhere in its include chain.
add_library(songnotes_dsp STATIC
    ../dsp/sine_wave.cpp
)
target_include_directories(songnotes_dsp PUBLIC ..)

enable_testing()

add_executable(dsp_tests
    test_sine_wave.cpp
)
target_link_libraries(dsp_tests PRIVATE songnotes_dsp GTest::gtest_main)

include(GoogleTest)
gtest_discover_tests(dsp_tests)
