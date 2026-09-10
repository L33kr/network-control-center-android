#include <jni.h>
#include <getopt.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include "error.h"
#include "main.h"

extern int server_fd;
static int g_proxy_running = 0;

struct params default_params = {
    .await_int = 10,
    .ipv6 = 1,
    .resolve = 1,
    .udp = 1,
    .max_open = 512,
    .bfsize = 16384,
    .baddr = {
        .in6 = { .sin6_family = AF_INET6 }
    },
    .laddr = {
        .in = { .sin_family = AF_INET }
    },
    .debug = 0
};

static void reset_params(void) {
    clear_params(NULL, NULL);
    params = default_params;
}

JNIEXPORT jint JNICALL
Java_io_github_l33kr_networkcontrolcenter_byedpi_ByeDpiProxy_jniStartProxy(
    JNIEnv *env,
    jobject thiz,
    jobjectArray args
) {
    (void) thiz;

    if (g_proxy_running) {
        LOG(LOG_S, "proxy already running");
        return -1;
    }

    const int argc = (*env)->GetArrayLength(env, args);
    char **argv = calloc((size_t) argc, sizeof(char *));
    if (!argv) {
        LOG(LOG_E, "failed to allocate argv");
        return -1;
    }

    for (int i = 0; i < argc; ++i) {
        jstring arg = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        if (!arg) continue;

        const char *value = (*env)->GetStringUTFChars(env, arg, NULL);
        argv[i] = value ? strdup(value) : NULL;
        if (value) (*env)->ReleaseStringUTFChars(env, arg, value);
        (*env)->DeleteLocalRef(env, arg);
    }

    reset_params();
    optind = 1;
    g_proxy_running = 1;
    LOG(LOG_S, "starting current ByeDPI core with %d args", argc);

    const int result = main(argc, argv);

    g_proxy_running = 0;
    for (int i = 0; i < argc; ++i) free(argv[i]);
    free(argv);

    LOG(LOG_S, "ByeDPI exited with %d", result);
    return result;
}

JNIEXPORT jint JNICALL
Java_io_github_l33kr_networkcontrolcenter_byedpi_ByeDpiProxy_jniStopProxy(
    JNIEnv *env,
    jobject thiz
) {
    (void) env;
    (void) thiz;

    if (!g_proxy_running) return -1;
    shutdown(server_fd, SHUT_RDWR);
    g_proxy_running = 0;
    return 0;
}

JNIEXPORT jint JNICALL
Java_io_github_l33kr_networkcontrolcenter_byedpi_ByeDpiProxy_jniForceClose(
    JNIEnv *env,
    jobject thiz
) {
    (void) env;
    (void) thiz;

    if (server_fd < 0) return -1;
    const int result = close(server_fd);
    g_proxy_running = 0;
    return result;
}
