cmake_minimum_required(VERSION 3.22.1)

project(network_control_center_native C)

set(BYEDPI_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/byedpi")

if(NOT EXISTS "${BYEDPI_DIR}/main.c")
    message(FATAL_ERROR "ByeDPI sources are missing. Run scripts/bootstrap-byedpi.* first.")
endif()

file(GLOB BYEDPI_SRC CONFIGURE_DEPENDS "${BYEDPI_DIR}/*.c")
list(REMOVE_ITEM BYEDPI_SRC "${BYEDPI_DIR}/win_service.c")

add_library(byedpi SHARED
    ${BYEDPI_SRC}
    native-lib.c
)

target_include_directories(byedpi PRIVATE "${BYEDPI_DIR}")
target_compile_options(byedpi PRIVATE
    -std=c99
    -O2
    -Wall
    -Wextra
    -Wno-unused
    -Wno-unused-parameter
    -pedantic
)
target_compile_definitions(byedpi PRIVATE ANDROID_APP)
target_link_libraries(byedpi PRIVATE android log)

target_link_options(byedpi PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
