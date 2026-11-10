/*
 * pocket-exec: runs a program that lives in a location Android refuses to
 * execute from.
 *
 * Since Android 10, apps may not execve() files in their private data
 * directory. The workaround used here mirrors what Termux does: copy the
 * program into an anonymous memfd and fexecve() it, which leaves no
 * filesystem path for the kernel to reject.
 *
 * The helper itself must live in the APK's nativeLibraryDir, which is
 * executable.
 *
 * Build (on a device that has the staged toolchain):
 *   clang -O2 -o libpocket_exec.so pocket-exec.c
 */
#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef SYS_memfd_create
#if defined(__aarch64__)
#define SYS_memfd_create 279
#elif defined(__arm__)
#define SYS_memfd_create 385
#elif defined(__x86_64__)
#define SYS_memfd_create 319
#endif
#endif

extern char **environ;

/*
 * The NDK sysroot headers Termux ships omit a couple of prototypes we need, so
 * declare them here rather than relying on a newer API level.
 */
extern int fchmod(int fd, unsigned int mode);
extern int fexecve(int fd, char *const argv[], char *const envp[]);

static int copy_to_memfd(const char *path) {
    int in = open(path, O_RDONLY | O_CLOEXEC);
    if (in < 0) {
        fprintf(stderr, "pocket-exec: cannot open %s: %s\n", path, strerror(errno));
        return -1;
    }

    int fd = (int)syscall(SYS_memfd_create, "pocket-exec", 0);
    if (fd < 0) {
        fprintf(stderr, "pocket-exec: memfd_create failed: %s\n", strerror(errno));
        close(in);
        return -1;
    }

    char buffer[65536];
    ssize_t read_bytes;
    while ((read_bytes = read(in, buffer, sizeof(buffer))) > 0) {
        ssize_t written = 0;
        while (written < read_bytes) {
            ssize_t part = write(fd, buffer + written, (size_t)(read_bytes - written));
            if (part <= 0) {
                fprintf(stderr, "pocket-exec: write failed: %s\n", strerror(errno));
                close(in);
                close(fd);
                return -1;
            }
            written += part;
        }
    }

    close(in);

    /*
     * Some Android builds reject fchmod() on an anonymous memfd. It is only a
     * belt-and-braces step: the fd is created with an executable mode, so a
     * failure here is not fatal.
     */
    if (fchmod(fd, 0700) != 0) {
        fprintf(stderr, "pocket-exec: warning: fchmod failed: %s\n", strerror(errno));
    }

    return fd;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: pocket-exec <program> [args...]\n");
        return 2;
    }

    int fd = copy_to_memfd(argv[1]);
    if (fd < 0) {
        return 126;
    }

    fexecve(fd, &argv[1], environ);

    /*
     * Some Android kernels refuse fexecve() on an anonymous memfd but accept
     * the same file through /proc/self/fd, which bionic resolves before the
     * exec. Try that before giving up.
     */
    char proc_path[64];
    snprintf(proc_path, sizeof(proc_path), "/proc/self/fd/%d", fd);
    execve(proc_path, &argv[1], environ);

    fprintf(stderr, "pocket-exec: fexecve failed: %s\n", strerror(errno));
    return 127;
}
