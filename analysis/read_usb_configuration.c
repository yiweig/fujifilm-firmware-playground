/* Configuration-descriptor retrieval only. Default invocation performs no I/O.
 * Uses the inspected macOS IOUSBLib plugin; may populate the descriptor cache.
 * Never calls USBDeviceOpen, interface open/claim, DeviceRequest, reset,
 * SetConfiguration, or any PTP operation. IOService user-client creation is
 * distinct from exclusive USBDeviceOpen. See macos-descriptor-api.md.
 */
#include <CoreFoundation/CoreFoundation.h>
#include <IOKit/IOKitLib.h>
#include <IOKit/IOCFPlugIn.h>
#include <IOKit/usb/IOUSBLib.h>
#include <errno.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int number(CFDictionaryRef properties, CFStringRef key, int64_t *value) {
    CFTypeRef object = CFDictionaryGetValue(properties, key);
    return object && CFGetTypeID(object) == CFNumberGetTypeID() &&
           CFNumberGetValue(object, kCFNumberSInt64Type, value);
}

static int valid_configuration(const unsigned char *data, size_t size) {
    if (size < 9 || size > 4096 || data[0] != 9 || data[1] != 2 ||
        ((size_t)data[2] | (size_t)data[3] << 8) != size || data[5] != 1)
        return 0;
    size_t offset = 9;
    while (offset < size) {
        if (offset + 2 > size || data[offset] < 2 || offset + data[offset] > size)
            return 0;
        offset += data[offset];
    }
    return offset == size;
}

static int self_test(void) {
    unsigned char fixture[] = {9,2,18,0,1,1,0,0x80,50, 9,4,0,0,0,6,1,1,0};
    if (!valid_configuration(fixture, sizeof fixture)) return 1;
    fixture[9] = 0;
    if (valid_configuration(fixture, sizeof fixture)) return 1;
    fixture[9] = 9;
    if (valid_configuration(fixture, sizeof fixture - 1)) return 1;
    puts("PASS: local descriptor-validation checks; no registry or USB access");
    return 0;
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--self-test") == 0) return self_test();
    if (argc != 3 || strcmp(argv[1], "--read-descriptor") != 0) {
        fprintf(stderr, "Usage: %s --self-test | --read-descriptor REGISTRY_ENTRY_ID\n"
                "Reads configuration metadata from one selected 04cb:030a device.\n"
                "May populate the OS descriptor cache; no PTP or exclusive open.\n", argv[0]);
        return 2;
    }
    errno = 0;
    char *end = NULL;
    uint64_t selected = strtoull(argv[2], &end, 0);
    if (errno || !selected || !end || end == argv[2] || *end || argv[2][0] == '-') {
        fputs("Invalid registry entry ID\n", stderr); return 2;
    }
    int result = 1;
    io_service_t service = IOServiceGetMatchingService(kIOMainPortDefault, IORegistryEntryIDMatching(selected));
    CFMutableDictionaryRef properties = NULL;
    IOCFPlugInInterface **plugin = NULL;
    IOUSBDeviceInterface **device = NULL;
    if (!service || !IOObjectConformsTo(service, "IOUSBHostDevice")) {
        fputs("Selected device is absent or has the wrong service class\n", stderr); goto done;
    }
    uint64_t actual = 0;
    int64_t vendor = 0, product = 0, configurations = 0, active = 0;
    if (IORegistryEntryGetRegistryEntryID(service, &actual) != KERN_SUCCESS || actual != selected ||
        IORegistryEntryCreateCFProperties(service, &properties, kCFAllocatorDefault, 0) != KERN_SUCCESS ||
        !properties || !number(properties, CFSTR("idVendor"), &vendor) || vendor != 0x04cb ||
        !number(properties, CFSTR("idProduct"), &product) || product != 0x030a ||
        !number(properties, CFSTR("bNumConfigurations"), &configurations) || configurations != 1 ||
        !number(properties, CFSTR("kUSBCurrentConfiguration"), &active) || active != 1) {
        fputs("Registry identity/configuration preflight failed\n", stderr); goto done;
    }
    SInt32 score = 0;
    IOReturn status = IOCreatePlugInInterfaceForService(service, kIOUSBDeviceUserClientTypeID,
                                                       kIOCFPlugInInterfaceID, &plugin, &score);
    if (status != kIOReturnSuccess || !plugin) {
        fprintf(stderr, "Metadata plugin creation failed: 0x%08x\n", status); goto done;
    }
    HRESULT query = (*plugin)->QueryInterface(plugin, CFUUIDGetUUIDBytes(kIOUSBDeviceInterfaceID),
                                              (LPVOID *)&device);
    if (query || !device) {
        fprintf(stderr, "Device metadata interface failed: 0x%08x\n", (unsigned)query); goto done;
    }
    IOUSBConfigurationDescriptorPtr descriptor = NULL;
    status = (*device)->GetConfigurationDescriptorPtr(device, 0, &descriptor);
    if (status != kIOReturnSuccess || !descriptor) {
        fprintf(stderr, "Configuration descriptor retrieval failed: 0x%08x\n", status); goto done;
    }
    const unsigned char *bytes = (const unsigned char *)descriptor;
    size_t size = (size_t)bytes[2] | (size_t)bytes[3] << 8;
    if (!valid_configuration(bytes, size)) {
        fputs("Malformed, oversized or unexpected descriptor\n", stderr); goto done;
    }
    printf("{\"registry_entry_id\":\"0x%" PRIx64 "\",\"vendor\":\"04cb\","
           "\"product\":\"030a\",\"active_configuration\":1,\"descriptor_hex\":\"", selected);
    for (size_t i = 0; i < size; ++i) printf("%02x", bytes[i]);
    puts("\"}");
    result = 0;
done:
    if (device) (*device)->Release(device);
    if (plugin) IODestroyPlugInInterface(plugin);
    if (properties) CFRelease(properties);
    if (service) IOObjectRelease(service);
    return result;
}
