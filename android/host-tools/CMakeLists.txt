cmake_minimum_required(VERSION 3.20)
project(aseprite_android_host_tools LANGUAGES C CXX)

if(CMAKE_CROSSCOMPILING OR NOT CMAKE_SYSTEM_NAME STREQUAL "Linux")
  message(FATAL_ERROR "Build these tools natively on the Linux host, without an Android toolchain")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")
get_filename_component(ASEPRITE_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)

# gen only needs LAF base, cfg, and tinyxml2. Do not configure the GUI
# backend or cross-compile a generator which must execute on this host.
set(LAF_LIST_DIR "${ASEPRITE_SOURCE_DIR}/laf")
set(LAF_BINARY_DIR "${CMAKE_BINARY_DIR}/laf")
set(LAF_WITH_TESTS OFF)
set(BUILD_SHARED_LIBS OFF)
set(tinyxml2_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(TINYXML_LIBRARY tinyxml2)
add_subdirectory("${ASEPRITE_SOURCE_DIR}/third_party/tinyxml2" tinyxml2)
add_subdirectory("${LAF_LIST_DIR}/base" laf/base)
target_include_directories(laf-base PUBLIC "${ASEPRITE_SOURCE_DIR}/src")
target_compile_definitions(laf-base PUBLIC HAVE_CONFIG_H)
add_subdirectory("${ASEPRITE_SOURCE_DIR}/src/cfg" cfg)
target_include_directories(cfg-lib PRIVATE "${ASEPRITE_SOURCE_DIR}/third_party/simpleini")
add_subdirectory("${ASEPRITE_SOURCE_DIR}/src/gen" gen)
