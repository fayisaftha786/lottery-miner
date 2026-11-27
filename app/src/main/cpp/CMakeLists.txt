cmake_minimum_required(VERSION 3.22.1)
project(minercore C)

set(CMAKE_C_STANDARD 11)
if(ANDROID)
    add_library(minercore SHARED jni_bridge.c miner_core.c)
else()
    add_library(minercore STATIC miner_core.c)
    include(CTest)
    if(BUILD_TESTING)
        target_compile_definitions(minercore PRIVATE MINER_CORE_TESTING=1)
        add_executable(minercore_test native_core_test.c)
        target_compile_definitions(minercore_test PRIVATE MINER_CORE_TESTING=1)
        target_link_libraries(minercore_test PRIVATE minercore)
        add_test(NAME minercore_test COMMAND minercore_test)
    endif()
endif()

if(ANDROID)
    # NDK r26 defaults to 4 KB ELF segment alignment. Explicitly build 16 KB
    # compatible shared libraries for modern arm64/x86_64 Android devices.
    target_link_options(minercore PRIVATE "-Wl,-z,max-page-size=16384")
    target_compile_definitions(minercore PRIVATE __BIONIC_NO_PAGE_SIZE_MACRO=1)
endif()

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # ARMv8 Crypto Extension (hardware SHA-256). Chosen at runtime only if the CPU reports it
    # and the built-in self-test (Bitcoin genesis block) passes.
    target_sources(minercore PRIVATE sha256_arm.c)
    set_source_files_properties(sha256_arm.c PROPERTIES COMPILE_FLAGS "-march=armv8-a+crypto")
    target_compile_definitions(minercore PRIVATE HAVE_ARM_SHA2=1)
endif()

target_compile_options(minercore PRIVATE -Wall -Wextra -fstack-protector-strong)

if(ANDROID)
    # Android's debug variant is the APK used for iterative device testing.
    # Keep the native hashing hot loop optimized in every build variant;
    # otherwise a debug APK can benchmark dramatically slower than release.
    # Do not use -ffast-math: best-difficulty reporting relies on IEEE doubles.
    target_compile_options(minercore PRIVATE -O3)
endif()
