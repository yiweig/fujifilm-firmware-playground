// Discover the previously confirmed X-half identity through Image Capture.
// No session-open, PTP passthrough, capture, import, property or reset APIs.
// Discovery can activate Apple's agent: this is not a bus-passive guarantee.
#import <Foundation/Foundation.h>
#import <AppKit/AppKit.h>
#import <ImageCaptureCore/ImageCaptureCore.h>

@interface Inventory : NSObject <ICDeviceBrowserDelegate>
@property(nonatomic, strong) NSMutableArray<ICDevice *> *matches;
@property(nonatomic) NSUInteger discoveredDeviceCount;
@end

@implementation Inventory
- (instancetype)init {
    if ((self = [super init])) _matches = [NSMutableArray array];
    return self;
}
- (void)deviceBrowser:(ICDeviceBrowser *)browser didAddDevice:(ICDevice *)device
          moreComing:(BOOL)moreComing {
    self.discoveredDeviceCount++;
    if (device.usbVendorID == 0x04cb && device.usbProductID == 0x030a &&
        [device isKindOfClass:[ICCameraDevice class]]) {
        if (![self.matches containsObject:device]) [self.matches addObject:device];
    }
}
- (void)deviceBrowser:(ICDeviceBrowser *)browser didRemoveDevice:(ICDevice *)device
           moreGoing:(BOOL)moreGoing {
    [self.matches removeObject:device];
}
@end

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        if (argc != 2 || strcmp(argv[1], "--browse") != 0) {
            fprintf(stderr, "Usage: imagecapture_inventory --browse\n"
                    "Starts local camera discovery for 8 seconds; opens no session.\n");
            return 2;
        }
        Inventory *inventory = [Inventory new];
        [NSApplication sharedApplication];
        [NSApp setActivationPolicy:NSApplicationActivationPolicyProhibited];
        ICDeviceBrowser *browser = [ICDeviceBrowser new];
        browser.delegate = inventory;
        browser.browsedDeviceTypeMask = (ICDeviceTypeMask)
            (ICDeviceTypeMaskCamera | ICDeviceLocationTypeMaskLocal);
        [browser start];
        [NSTimer scheduledTimerWithTimeInterval:8 repeats:NO block:^(NSTimer *timer) {
            (void)timer;
            [NSApp stop:nil];
            // Wake AppKit's event loop so stop takes effect without a GUI event.
            [NSApp postEvent:[NSEvent otherEventWithType:NSEventTypeApplicationDefined
                location:NSZeroPoint modifierFlags:0 timestamp:0 windowNumber:0
                context:nil subtype:0 data1:0 data2:0] atStart:NO];
        }];
        [NSApp run];
        NSMutableArray *records = [NSMutableArray array];
        for (ICDevice *device in inventory.matches) {
            // Never serialize a device description, UUID, serial or media list.
            [records addObject:@{
                @"vendor_id": @"04cb", @"product_id": @"030a",
                @"name": device.name ?: @"",
                @"transport": device.transportType ?: @"",
                @"has_open_session": @(device.hasOpenSession),
                @"accepts_ptp": @([device.capabilities
                    containsObject:ICCameraDeviceCanAcceptPTPCommands]),
                @"module_path": device.modulePath ?: @"",
                @"module_version": device.moduleVersion ?: @""
            }];
        }
        [browser stop];
        NSDictionary *report = @{
            @"mode": @"ImageCaptureCore discovery; no explicit session or PTP request",
            @"matching_devices": records,
            @"discovery_add_callbacks": @(inventory.discoveredDeviceCount),
            @"unique_match": @(records.count == 1),
            @"wire_traffic_observed": @NO
        };
        NSError *error = nil;
        NSData *json = [NSJSONSerialization dataWithJSONObject:report
            options:NSJSONWritingPrettyPrinted | NSJSONWritingSortedKeys error:&error];
        if (!json) { fprintf(stderr, "JSON serialization failed\n"); return 1; }
        fwrite(json.bytes, 1, json.length, stdout);
        fputc('\n', stdout);
        return records.count == 1 ? 0 : 1;
    }
}
