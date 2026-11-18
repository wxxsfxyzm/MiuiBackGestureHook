cmake_minimum_required(VERSION 3.22)

project(miui_home_hyos_native LANGUAGES CXX ASM)

if(NOT ANDROID)
    message(FATAL_ERROR "miui_home_hyos_native must use the Android NDK toolchain")
endif()

if(NOT "${ANDROID_ABI}" STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Only arm64-v8a is supported; got '${ANDROID_ABI}'")
endif()

find_package(lsplt REQUIRED CONFIG)

set(MIUI_HOME_NATIVE_SOURCES
    miui_home_native_hook.cpp
    lsposed_hook_backend.cpp
    runtime_profile_resolver.cpp
    dart_runtime_resolver.cpp
    broadcast_options_tail_hook.S
    broadcast_send_capture_hook.S
    dart_state_hooks.S
    contextual_home_action_hook.S
    input_monitor_pilfer_hook.S
)
set(MIUI_HOME_NATIVE_TARGET miui_home_hyos_lsp)
set(MIUI_HOME_NATIVE_EXPORTS exports-lsposed.map)

add_library(${MIUI_HOME_NATIVE_TARGET} SHARED ${MIUI_HOME_NATIVE_SOURCES})

target_include_directories(${MIUI_HOME_NATIVE_TARGET} PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_features(${MIUI_HOME_NATIVE_TARGET} PRIVATE cxx_std_17)
target_compile_options(${MIUI_HOME_NATIVE_TARGET} PRIVATE
    -fno-exceptions
    -fno-rtti
    -fno-threadsafe-statics
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
    -mbranch-protection=standard
    -Wall
    -Wextra
    -Werror
)

target_link_options(${MIUI_HOME_NATIVE_TARGET} PRIVATE
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/${MIUI_HOME_NATIVE_EXPORTS}"
    -Wl,--gc-sections
    -Wl,--no-undefined
    -Wl,-z,relro
    -Wl,-z,now
)

target_link_options(${MIUI_HOME_NATIVE_TARGET} PRIVATE "-Wl,-rpath,$ORIGIN")

target_link_libraries(${MIUI_HOME_NATIVE_TARGET} PRIVATE log dl lsplt::lsplt)

set_target_properties(${MIUI_HOME_NATIVE_TARGET} PROPERTIES
    CXX_EXTENSIONS OFF
    OUTPUT_NAME "${MIUI_HOME_NATIVE_TARGET}"
)
