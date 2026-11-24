cmake_minimum_required(VERSION 3.15)
project(dna_hasher C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 99)

cmake_policy(SET CMP0063 NEW)

if(MSVC)
    add_compile_options(/O2 /arch:AVX2)
else()
    add_compile_options(-O3 -march=native -funroll-loops -Wall -Wextra)
endif()

set(EDLIB_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/edlib-1.2.7)
set(XXHASH_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/xxHash-0.8.2)

add_library(edlib_lib STATIC ${EDLIB_ROOT}/edlib/src/edlib.cpp)
target_include_directories(edlib_lib PUBLIC ${EDLIB_ROOT}/edlib/include)
if(NOT MSVC)
    target_compile_options(edlib_lib PRIVATE -O3)
endif()

add_library(xxhash_c STATIC ${XXHASH_ROOT}/xxhash.c)
target_include_directories(xxhash_c PUBLIC ${XXHASH_ROOT})
if(NOT MSVC)
    target_compile_options(xxhash_c PRIVATE -O3)
endif()

add_library(dna_hasher SHARED src/dna_hasher.cpp)
target_link_libraries(dna_hasher PRIVATE xxhash_c edlib_lib)
target_include_directories(dna_hasher PRIVATE
    src
    ${EDLIB_ROOT}/edlib/include
    ${XXHASH_ROOT}
)

enable_testing()
add_executable(dna_hasher_tests tests/test_dna_hasher.cpp)
target_link_libraries(dna_hasher_tests PRIVATE dna_hasher xxhash_c edlib_lib)
target_include_directories(dna_hasher_tests PRIVATE
    src
    ${EDLIB_ROOT}/edlib/include
    ${XXHASH_ROOT}
)
add_test(NAME dna_hasher_tests COMMAND dna_hasher_tests)

install(TARGETS dna_hasher LIBRARY DESTINATION lib)
