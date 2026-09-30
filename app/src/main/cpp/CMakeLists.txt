# app/src/main/cpp/CMakeLists.txt - Foxhollow Android CMake Configuration
# This file integrates with the existing Aurora/Borealis build system

cmake_minimum_required(VERSION 3.22.1)

# Project information
project("foxhollow-android" LANGUAGES C CXX)

# Detect Android NDK
if(ANDROID)
    message(STATUS "Building for Android ABI: ${ANDROID_ABI} API: ${ANDROID_PLATFORM}")
else()
    message(WARNING "Not building for Android - some configurations may be missing")
endif()

# Include directories for Foxhollow source
set(FOXHOLLOW_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../")
set(FOXHOLLOW_GAME_DIR "${FOXHOLLOW_SOURCE_DIR}/game")
set(FOXHOLLOW_EXTERN_DIR "${FOXHOLLOW_SOURCE_DIR}/extern")
set(FOXHOLLOW_PORT_DIR "${FOXHOLLOW_SOURCE_DIR}/port")

# Aurora and Borealis include paths
set(AURORA_INCLUDE_DIR "${FOXHOLLOW_EXTERN_DIR}/aurora/include")
set(BOREALIS_INCLUDE_DIR "${FOXHOLLOW_EXTERN_DIR}/borealis/include")

# Include Foxhollow source
include_directories(
    ${FOXHOLLOW_GAME_DIR}/include
    ${FOXHOLLOW_SOURCE_DIR}/include
    ${AURORA_INCLUDE_DIR}
    ${BOREALIS_INCLUDE_DIR}
    ${FOXHOLLOW_EXTERN_DIR}/aurora/platforms/android
)

# Source files for Foxhollow library
set(FOXHOLLOW_SOURCES
    ${FOXHOLLOW_GAME_DIR}/src/main.c
    ${FOXHOLLOW_SOURCE_DIR}/src/main.c
)

# Aurora library path (prebuilt from external build)
set(AURORA_LIB_DIR "${FOXHOLLOW_EXTERN_DIR}/aurora/build-${ANDROID_ABI}")
set(BOREALIS_LIB_DIR "${FOXHOLLOW_EXTERN_DIR}/borealis/build-${ANDROID_ABI}")

# For development, we'll build Aurora/Borealis as part of this project
# Add Aurora as a subdirectory
add_subdirectory(${FOXHOLLOW_EXTERN_DIR}/aurora aurora)
# Add Borealis as a subdirectory  
add_subdirectory(${FOXHOLLOW_EXTERN_DIR}/borealis borealis)

# Foxhollow library
add_library(foxhollow SHARED ${FOXHOLLOW_SOURCES})

# Link libraries - order matters!
target_link_libraries(foxhollow
    PRIVATE
        aurora          # Aurora core
        borealis        # Borealis (depends on aurora)
        SDL3            # SDL3 (provided by borealis/aurora)
        m               # Math library
        dl              # Dynamic loading
        log             # Android log
        android         # Android native app glue
        OpenGL::GL      # OpenGL ES (if needed)
        EGL             # EGL for surface management
)

# Set target properties
set_target_properties(foxhollow PROPERTIES
    PREFIX ""                     # No lib prefix for .so
    OUTPUT_NAME "foxhollow"       # libfoxhollow.so
    CXX_STANDARD 20
    CXX_STANDARD_REQUIRED YES
    C_STANDARD 11
    C_STANDARD_REQUIRED YES
    VISIBILITY_INLINES_HIDDEN YES
    WINDOWS_EXPORT_ALL_SYMBOLS NO
)

# Android-specific settings
if(ANDROID)
    # Set Android NDK toolchain properties
    set_target_properties(foxhollow PROPERTIES
        ANDROID_STL c++_static
    )
    
    # Add compile definitions
    target_compile_definitions(foxhollow PRIVATE
        ANDROID
        FOXHOLLOW_ANDROID=1
        FOXHOLLOW_DEBUG_SHORTCUTS=${FOXHOLLOW_DEBUG_SHORTCUTS}
    )
    
    # Link with Android libraries
    target_link_libraries(foxhollow PRIVATE
        android
        log
        EGL
        GLESv2
        OpenSLess
    )
endif()

# Install target (for Gradle)
install(TARGETS foxhollow
        LIBRARY DESTINATION libs/${ANDROID_ABI})
