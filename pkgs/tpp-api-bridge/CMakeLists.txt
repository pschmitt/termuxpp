# CMake 4 dropped compatibility with versions below 3.5 and warns below 3.10.
# Nothing here needs more than 3.10 (continue(), install(TYPE), file(STRINGS)).
cmake_minimum_required(VERSION 3.10)
project(termux-api)
include(GNUInstallDirs)

set(TERMUX_PREFIX ${CMAKE_INSTALL_PREFIX})

add_library(termux-api SHARED termux-api.c)
add_library(termux-api-static STATIC termux-api.c)
set_target_properties(termux-api-static PROPERTIES OUTPUT_NAME termux-api)

add_executable(termux-api-broadcast termux-api-broadcast.c)
target_link_libraries(termux-api-broadcast termux-api-static)

# The tpp commands are not built here: the Rust crate tpp-api (part of the one
# tpp binary, built separately) implements them all. What is built here is the
# platform bridge: the C client of the socket protocol with its library, and
# termux-callback, for scripts and programs that still use the old interface.

configure_file(
  ${CMAKE_CURRENT_SOURCE_DIR}/termux-callback.in
  termux-callback @ONLY
)

install(
  FILES ${CMAKE_BINARY_DIR}/termux-api-broadcast
  DESTINATION ${CMAKE_INSTALL_PREFIX}/libexec
  PERMISSIONS OWNER_READ OWNER_WRITE OWNER_EXECUTE
    GROUP_READ GROUP_EXECUTE
    WORLD_READ WORLD_EXECUTE
)

# Create a symlink for termux-api-broadcast->termux-api for backwards
# compatibility
INSTALL(CODE "execute_process( \
  COMMAND ${CMAKE_COMMAND} -E create_symlink \
  termux-api-broadcast \
  \"\$ENV{DESTDIR}${CMAKE_INSTALL_PREFIX}/libexec/termux-api\" \
  )"
)

install(
  FILES
    ${CMAKE_BINARY_DIR}/libtermux-api.so
    ${CMAKE_BINARY_DIR}/libtermux-api.a
  TYPE LIB
)

install(
  FILES ${CMAKE_CURRENT_SOURCE_DIR}/termux-api.h
  TYPE INCLUDE
)

install(
  FILES ${CMAKE_CURRENT_SOURCE_DIR}/completions/tpp.bash
  DESTINATION ${CMAKE_INSTALL_PREFIX}/share/bash-completion/completions
  RENAME tpp
)
install(
  FILES ${CMAKE_CURRENT_SOURCE_DIR}/completions/tpp.zsh
  DESTINATION ${CMAKE_INSTALL_PREFIX}/share/zsh/site-functions
  RENAME _tpp
)
install(
  FILES ${CMAKE_CURRENT_SOURCE_DIR}/completions/tpp.fish
  DESTINATION ${CMAKE_INSTALL_PREFIX}/share/fish/vendor_completions.d
)

install(
  FILES ${CMAKE_BINARY_DIR}/termux-callback
  DESTINATION ${CMAKE_INSTALL_PREFIX}/libexec
  PERMISSIONS OWNER_READ OWNER_WRITE OWNER_EXECUTE
    GROUP_READ GROUP_EXECUTE
    WORLD_READ WORLD_EXECUTE
)
