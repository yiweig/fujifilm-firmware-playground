// Host IORegistry notifications only. No USB/interface opens or device requests.
#include <CoreFoundation/CoreFoundation.h>
#include <IOKit/IOKitLib.h>
#include <inttypes.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

typedef struct { uint64_t id; int64_t vid, pid, location, address; } Device;
typedef struct { bool baseline; unsigned count; } Context;
static Device cache[256];
static struct timespec started;

static void timestamp(void) {
    struct timespec wall, mono;
    struct tm utc;
    char date[32];
    clock_gettime(CLOCK_REALTIME, &wall);
    clock_gettime(CLOCK_MONOTONIC, &mono);
    gmtime_r(&wall.tv_sec, &utc);
    strftime(date, sizeof(date), "%Y-%m-%dT%H:%M:%S", &utc);
    double elapsed = (double)(mono.tv_sec - started.tv_sec) +
                     (double)(mono.tv_nsec - started.tv_nsec) / 1e9;
    printf("\"utc\":\"%s.%03ldZ\",\"elapsed_s\":%.6f", date, wall.tv_nsec / 1000000, elapsed);
}

static int64_t number(CFDictionaryRef properties, CFStringRef key) {
    int64_t result = -1;
    CFTypeRef value = properties ? CFDictionaryGetValue(properties, key) : NULL;
    if (value && CFGetTypeID(value) == CFNumberGetTypeID())
        CFNumberGetValue((CFNumberRef)value, kCFNumberSInt64Type, &result);
    return result;
}

static Device metadata(io_service_t service) {
    Device d = {0, -1, -1, -1, -1};
    IORegistryEntryGetRegistryEntryID(service, &d.id);
    CFMutableDictionaryRef properties = NULL;
    if (IORegistryEntryCreateCFProperties(service, &properties, kCFAllocatorDefault, 0) == KERN_SUCCESS) {
        d.vid = number(properties, CFSTR("idVendor"));
        d.pid = number(properties, CFSTR("idProduct"));
        d.location = number(properties, CFSTR("locationID"));
        d.address = number(properties, CFSTR("USB Address"));
        CFRelease(properties);
    }
    return d;
}

static void field(const char *name, int64_t value) {
    printf(",\"%s\":", name);
    if (value < 0) printf("null"); else printf("%" PRId64, value);
}

static void emit(io_service_t service, const char *event, bool removed) {
    Device d = metadata(service);
    int free_slot = -1, slot = -1;
    for (int i = 0; i < 256; ++i) {
        if (d.id && cache[i].id == d.id) slot = i;
        if (!cache[i].id && free_slot < 0) free_slot = i;
    }
    if (slot >= 0 && d.vid < 0) d = cache[slot];
    if (!removed && d.id) {
        if (slot < 0) slot = free_slot;
        if (slot >= 0) cache[slot] = d;
        else fprintf(stderr, "Observer metadata cache full; termination fallback unavailable.\n");
    }
    printf("{"); timestamp();
    printf(",\"event\":\"%s\",\"registry_id\":%" PRIu64, event, d.id);
    field("vendor_id", d.vid); field("product_id", d.pid);
    field("location_id", d.location); field("usb_address", d.address);
    printf("}\n"); fflush(stdout);
    if (removed && slot >= 0) memset(&cache[slot], 0, sizeof(cache[slot]));
}

static void arrived(void *ref, io_iterator_t iterator) {
    Context *ctx = ref;
    io_service_t service;
    while ((service = IOIteratorNext(iterator))) {
        emit(service, ctx->baseline ? "baseline" : "arrived", false);
        ++ctx->count;
        IOObjectRelease(service);
    }
}

static void terminated(void *ref, io_iterator_t iterator) {
    (void)ref;
    io_service_t service;
    while ((service = IOIteratorNext(iterator))) {
        emit(service, "terminated", true);
        IOObjectRelease(service);
    }
}

static void finished(CFRunLoopTimerRef timer, void *info) {
    (void)timer; (void)info;
    printf("{"); timestamp(); printf(",\"event\":\"ended\",\"reason\":\"time_limit\"}\n"); fflush(stdout);
    CFRunLoopStop(CFRunLoopGetCurrent());
}

int main(int argc, char **argv) {
    char *end = NULL;
    long seconds = argc == 2 ? strtol(argv[1], &end, 10) : 0;
    if (argc != 2 || !end || *end || seconds < 30 || seconds > 1800) {
        fprintf(stderr, "usage: usb-startup-observer SECONDS (30..1800)\n"); return 2;
    }
    clock_gettime(CLOCK_MONOTONIC, &started);
    IONotificationPortRef port = IONotificationPortCreate(kIOMainPortDefault);
    if (!port) { fprintf(stderr, "Cannot create registry notification port.\n"); return 1; }
    CFRunLoopSourceRef source = IONotificationPortGetRunLoopSource(port);
    if (!source) { IONotificationPortDestroy(port); return 1; }
    CFRunLoopAddSource(CFRunLoopGetCurrent(), source, kCFRunLoopDefaultMode);
    Context ctx = {true, 0};
    io_iterator_t add = IO_OBJECT_NULL, remove = IO_OBJECT_NULL;
    kern_return_t result = IOServiceAddMatchingNotification(port, kIOFirstMatchNotification,
        IOServiceMatching("IOUSBHostDevice"), arrived, &ctx, &add);
    if (result != KERN_SUCCESS) { fprintf(stderr, "Arrival subscription failed: 0x%x\n", result); IONotificationPortDestroy(port); return 1; }
    result = IOServiceAddMatchingNotification(port, kIOTerminatedNotification,
        IOServiceMatching("IOUSBHostDevice"), terminated, &ctx, &remove);
    if (result != KERN_SUCCESS) { fprintf(stderr, "Removal subscription failed: 0x%x\n", result); IOObjectRelease(add); IONotificationPortDestroy(port); return 1; }
    arrived(&ctx, add); terminated(&ctx, remove); ctx.baseline = false;
    printf("{"); timestamp();
    printf(",\"event\":\"ready\",\"baseline_count\":%u,\"duration_s\":%ld,\"scope\":\"registry_notifications_only\"}\n", ctx.count, seconds);
    fflush(stdout);
    CFRunLoopTimerRef timer = CFRunLoopTimerCreate(kCFAllocatorDefault,
        CFAbsoluteTimeGetCurrent() + seconds, 0, 0, 0, finished, NULL);
    if (!timer) { IOObjectRelease(add); IOObjectRelease(remove); IONotificationPortDestroy(port); return 1; }
    CFRunLoopAddTimer(CFRunLoopGetCurrent(), timer, kCFRunLoopDefaultMode);
    CFRunLoopRun();
    CFRunLoopTimerInvalidate(timer); CFRelease(timer);
    IOObjectRelease(add); IOObjectRelease(remove); IONotificationPortDestroy(port);
    return 0;
}
