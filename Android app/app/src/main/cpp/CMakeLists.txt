cmake_minimum_required(VERSION 3.22.1)

project("packetboost_jni")

# Target Android ABI directory for compiled Go shared library (.so)
set(GO_CORE_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

# Prebuilt Go shared library
add_library(packetboost_core SHARED IMPORTED)
set_target_properties(packetboost_core PROPERTIES
    IMPORTED_LOCATION "${GO_CORE_LIB_DIR}/libpacketboost_core.so"
)

# C++ JNI Shared Library
add_library(packetboost_jni SHARED
    packetboost_jni.cpp
)

find_library(log-lib log)
find_library(android-lib android)

# Link Libraries
target_link_libraries(
    packetboost_jni
    packetboost_core
    ${log-lib}
    ${android-lib}
)
