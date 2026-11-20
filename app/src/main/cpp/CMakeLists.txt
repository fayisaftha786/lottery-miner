cmake_minimum_required(VERSION 3.22.1)
project(minercore C)

set(CMAKE_C_STANDARD 11)
add_library(minercore SHARED jni_bridge.c miner_core.c)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # ARMv8 Crypto Extension (hardware SHA-256). Chosen at runtime only if the CPU reports it
    # and the built-in self-test (Bitcoin genesis block) passes.
    target_sources(minercore PRIVATE sha256_arm.c)
    set_source_files_properties(sha256_arm.c PROPERTIES COMPILE_FLAGS "-march=armv8-a+crypto")
    target_compile_definitions(minercore PRIVATE HAVE_ARM_SHA2=1)
endif()

target_compile_options(minercore PRIVATE -Wall -Wextra -fstack-protector-strong)
