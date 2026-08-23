cmake_minimum_required(VERSION 3.22)
project(e11npu)

set(RKNN_RUNTIME "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/librknnrt.so")
set(RKNN_HEADER "${CMAKE_SOURCE_DIR}/rknn_api.h")

# RKNN SDK 的头文件和运行库受 Rockchip 自身条款约束，不进入本仓库。
# 两者均存在时才编译 NPU JNI；缺失时源码基线仍可正常构建，运行时会降级停用 NPU。
if(EXISTS "${RKNN_RUNTIME}" AND EXISTS "${RKNN_HEADER}")
    add_library(e11npu SHARED npu_jni.cpp)

    add_library(rknnrt SHARED IMPORTED)
    set_target_properties(rknnrt PROPERTIES IMPORTED_LOCATION "${RKNN_RUNTIME}")

    target_include_directories(e11npu PRIVATE ${CMAKE_SOURCE_DIR})
    target_link_libraries(e11npu rknnrt android log)
else()
    message(STATUS "RKNN SDK not found; building without the optional NPU JNI bridge")
endif()
