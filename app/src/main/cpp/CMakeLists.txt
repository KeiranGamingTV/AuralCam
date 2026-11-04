cmake_minimum_required(VERSION 3.22.1)
project(auralcam_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Any .cpp file added to this folder is picked up automatically.
file(GLOB AURALCAM_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/*.cpp")

add_library(auralcam_native SHARED ${AURALCAM_SOURCES})

target_compile_options(auralcam_native PRIVATE
    -O3
    -ffast-math
    -Wall
    -Wextra
)

find_library(log-lib log)

target_link_libraries(auralcam_native
    ${log-lib}
    jnigraphics
).