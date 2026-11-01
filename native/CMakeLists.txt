cmake_minimum_required(VERSION 3.15)
project(dna_hasher CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

cmake_policy(SET CMP0063 NEW)

if(MSVC)
    add_compile_options(/O2 /arch:AVX2)
else()
    add_compile_options(-O3 -march=native -funroll-loops)
endif()

include(FetchContent)

FetchContent_Declare(
    edlib
    GIT_REPOSITORY https://github.com/Martinsos/edlib.git
    GIT_TAG        v1.2.7
)
FetchContent_MakeAvailable(edlib)

find_package(ZLIB REQUIRED)
add_library(dna_hasher SHARED src/dna_hasher.cpp)
target_link_libraries(dna_hasher PRIVATE xxhash edlib ZLIB::ZLIB)
target_include_directories(dna_hasher PRIVATE src ${edlib_SOURCE_DIR}/edlib/include)