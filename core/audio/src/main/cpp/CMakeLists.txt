cmake_minimum_required(VERSION 3.22.1)
project(songnotes_audio)

# Provided by AGP via the prefab package mechanism — requires
# android.buildFeatures.prefab = true and an `implementation(libs.oboe)`
# dependency in core/audio/build.gradle.kts. See docs/handoff/PHASE-00.md
# if this find_package fails on first sync.
find_package(oboe REQUIRED CONFIG)

add_library(songnotes_audio SHARED
    audio_engine.cpp
    jni_bridge.cpp
    calibration_jni.cpp
    dsp/sine_wave.cpp
    dsp/click.cpp
    dsp/fft.cpp
    dsp/sweep.cpp
    dsp/matched_filter.cpp
    dsp/calibration_stats.cpp
    dsp/click_track.cpp
    dsp/mix.cpp
)

target_include_directories(songnotes_audio PRIVATE .)

target_link_libraries(songnotes_audio
    PRIVATE
    oboe::oboe
    android
    log
)

target_compile_options(songnotes_audio PRIVATE -Wall -Wextra)

# 16 KB page size is mandatory on Android 15+ devices — every .so this
# module produces must be page-aligned or the app fails to load on those
# devices. This is a LINK-time flag, not a compile flag, hence
# target_link_options rather than the cppFlags in build.gradle.kts.
target_link_options(songnotes_audio PRIVATE "-Wl,-z,max-page-size=16384")
