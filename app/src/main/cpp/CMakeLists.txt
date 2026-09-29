cmake_minimum_required(VERSION 3.22.1)
project(airplaytv C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -fPIC -Wno-unused-function -Wno-deprecated-declarations")
set(CMAKE_CXX_STANDARD 17)

# ---------- libplist (2.7.0, >= 2.3.0 -> PLIST_210 + PLIST_230 like upstream) ----------
set(PLIST_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/deps/libplist-2.7.0)
set(PLIST_SOURCES
    ${PLIST_DIR}/src/base64.c
    ${PLIST_DIR}/src/bytearray.c
    ${PLIST_DIR}/src/hashtable.c
    ${PLIST_DIR}/src/ptrarray.c
    ${PLIST_DIR}/src/time64.c
    ${PLIST_DIR}/src/xplist.c
    ${PLIST_DIR}/src/bplist.c
    ${PLIST_DIR}/src/jsmn.c
    ${PLIST_DIR}/src/jplist.c
    ${PLIST_DIR}/src/oplist.c
    ${PLIST_DIR}/src/out-default.c
    ${PLIST_DIR}/src/out-plutil.c
    ${PLIST_DIR}/src/out-limd.c
    ${PLIST_DIR}/src/plist.c
    ${PLIST_DIR}/libcnary/node.c
    ${PLIST_DIR}/libcnary/node_list.c
)

# expose <plist/plist.h>: stage the public C header (src/plist.h is private)
set(PLIST_STAGE ${CMAKE_BINARY_DIR}/plist_include/plist)
file(MAKE_DIRECTORY ${PLIST_STAGE})
file(COPY ${PLIST_DIR}/include/plist/plist.h DESTINATION ${PLIST_STAGE})

add_library(plist STATIC ${PLIST_SOURCES})
target_include_directories(plist PUBLIC ${CMAKE_BINARY_DIR}/plist_include
    PRIVATE ${PLIST_DIR}/src ${PLIST_DIR}/include ${PLIST_DIR}/libcnary ${PLIST_DIR}/libcnary/include)
target_compile_definitions(plist PRIVATE PLIST_210 PLIST_230
    PACKAGE_VERSION="2.7.0" PACKAGE_STRING="libplist 2.7.0"
    HAVE_STRNDUP HAVE_STPCPY HAVE_VASPRINTF HAVE_ASPRINTF
    HAVE_MEMCPY HAVE_STRERROR HAVE_STRNCASECMP)

# ---------- UxPlay airplay library ----------
set(UXLIB ${CMAKE_CURRENT_SOURCE_DIR}/uxplay/lib)

file(GLOB UX_SOURCES ${UXLIB}/*.c)
# httpd needs airplay_video only for HLS; keep it compiled (hls off at runtime)
add_library(airplay STATIC
    ${UX_SOURCES}
    ${UXLIB}/playfair/hand_garble.c
    ${UXLIB}/playfair/modified_md5.c
    ${UXLIB}/playfair/omg_hax.c
    ${UXLIB}/playfair/playfair.c
    ${UXLIB}/playfair/sap_hash.c
    ${UXLIB}/llhttp/api.c
    ${UXLIB}/llhttp/http.c
    ${UXLIB}/llhttp/llhttp.c
    ${UXLIB}/mdnsd/dnssd_mdnsd.c
    ${UXLIB}/mdnsd/mdnsd.c
)
target_include_directories(airplay PUBLIC ${UXLIB}
    PRIVATE ${UXLIB}/playfair ${UXLIB}/llhttp ${UXLIB}/mdnsd
    ${CMAKE_BINARY_DIR}/plist_include
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/prebuilt/${ANDROID_ABI}/include)
target_compile_definitions(airplay PUBLIC
    STANDALONE __STDC_CONSTANT_MACROS __STDC_LIMIT_MACROS TARGET_POSIX
    _LINUX _REENTRANT _LARGEFILE64_SOURCE _FILE_OFFSET_BITS=64
    NOHOLD PLIST_210 PLIST_230 OPENSSL_API_COMPAT=0x10101000L)
target_link_libraries(airplay PUBLIC plist)

# ---------- JNI bridge ----------
add_library(airplaytv SHARED airplay_jni.cpp)
target_include_directories(airplaytv PRIVATE
    ${UXLIB}
    ${CMAKE_BINARY_DIR}/plist_include
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/prebuilt/${ANDROID_ABI}/include)
target_link_libraries(airplaytv
    airplay
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/prebuilt/${ANDROID_ABI}/lib/libcrypto.a
    log)
