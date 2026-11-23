#if defined(SCALANATIVE_COMPILE_ALWAYS) || defined(__SCALANATIVE_MEMORY_OWNED)

/* Allocator for instances of `scala.scalanative.memory.Owned`.
 *
 * Owned objects live outside of the garbage collected heap: the compiler
 * frees them deterministically when their owner goes out of scope (see the
 * `scalanative-ownership` compiler phase). They may still reference objects
 * of the garbage collected heap, so every live allocation is reported to the
 * collector as a root through a dynamic root provider.
 *
 * Live allocations form a doubly linked list, so that both allocation and
 * deallocation are O(1) and a collection scans only live owned objects. */

#include <stdatomic.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "shared/GCHooks.h"

#define OWNED_MAGIC ((uintptr_t)0x0FED0B1EC7ULL)

typedef struct OwnedHeader {
    struct OwnedHeader *prev;
    struct OwnedHeader *next;
    size_t size;
    uintptr_t magic;
} OwnedHeader;

// The object itself has to stay 16 byte aligned like GC allocations.
_Static_assert(sizeof(OwnedHeader) % 16 == 0, "OwnedHeader must be aligned");

static OwnedHeader *ownedHead = NULL;
static atomic_flag ownedLock = ATOMIC_FLAG_INIT;
static atomic_bool providerRegistered = false;
static atomic_size_t ownedLiveCount = 0;
static atomic_size_t ownedLiveBytes = 0;
static atomic_size_t ownedTotalFreed = 0;

static inline void ownedLockAcquire(void) {
    while (atomic_flag_test_and_set_explicit(&ownedLock, memory_order_acquire))
        ;
}

static inline void ownedLockRelease(void) {
    atomic_flag_clear_explicit(&ownedLock, memory_order_release);
}

// Runs with all mutators stopped, so the list is not modified concurrently.
static void scanOwnedRoots(void *data, GC_ScanRangeFn *scan, void *ctx) {
    (void)data;
    for (OwnedHeader *it = ownedHead; it != NULL; it = it->next) {
        char *obj = (char *)(it + 1);
        scan(ctx, obj, obj + it->size);
    }
}

static void ensureProviderRegistered(void) {
    if (atomic_load(&providerRegistered))
        return;
    ownedLockAcquire();
    if (!atomic_load(&providerRegistered)) {
        if (!scalanative_GC_add_root_provider(scanOwnedRoots, NULL)) {
            fprintf(stderr, "Scala Native: cannot register owned roots\n");
            abort();
        }
        atomic_store(&providerRegistered, true);
    }
    ownedLockRelease();
}

void *scalanative_owned_alloc(void *info, size_t size) {
    ensureProviderRegistered();
    OwnedHeader *header = calloc(1, sizeof(OwnedHeader) + size);
    if (header == NULL) {
        fprintf(stderr, "Scala Native: out of memory for owned object\n");
        abort();
    }
    header->size = size;
    header->magic = OWNED_MAGIC;
    void **obj = (void **)(header + 1);
    *obj = info;

    scalanative_GC_pause_collections();
    ownedLockAcquire();
    header->prev = NULL;
    header->next = ownedHead;
    if (ownedHead != NULL)
        ownedHead->prev = header;
    ownedHead = header;
    ownedLockRelease();
    scalanative_GC_resume_collections();

    atomic_fetch_add(&ownedLiveCount, 1);
    atomic_fetch_add(&ownedLiveBytes, size);
    return obj;
}

void scalanative_owned_free(void *obj) {
    if (obj == NULL)
        return;
    OwnedHeader *header = ((OwnedHeader *)obj) - 1;
    if (header->magic != OWNED_MAGIC) {
        fprintf(stderr,
                "Scala Native: freeing %p which is not a live owned object "
                "(double drop?)\n",
                obj);
        abort();
    }

    scalanative_GC_pause_collections();
    ownedLockAcquire();
    if (header->prev != NULL)
        header->prev->next = header->next;
    else
        ownedHead = header->next;
    if (header->next != NULL)
        header->next->prev = header->prev;
    ownedLockRelease();
    scalanative_GC_resume_collections();

    size_t size = header->size;
    header->magic = 0;
    // Clear the object so that stale references do not keep heap objects
    // alive through conservative scanning of reused memory.
    memset(obj, 0, size);
    free(header);

    atomic_fetch_sub(&ownedLiveCount, 1);
    atomic_fetch_sub(&ownedLiveBytes, size);
    atomic_fetch_add(&ownedTotalFreed, 1);
}

bool scalanative_owned_is_owned(void *obj) {
    if (obj == NULL)
        return false;
    bool found = false;
    ownedLockAcquire();
    for (OwnedHeader *it = ownedHead; it != NULL && !found; it = it->next)
        found = (void *)(it + 1) == obj;
    ownedLockRelease();
    return found;
}

size_t scalanative_owned_live_count(void) {
    return atomic_load(&ownedLiveCount);
}

size_t scalanative_owned_live_bytes(void) {
    return atomic_load(&ownedLiveBytes);
}

size_t scalanative_owned_total_freed(void) {
    return atomic_load(&ownedTotalFreed);
}

#endif
