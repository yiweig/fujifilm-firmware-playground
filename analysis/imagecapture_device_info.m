// Two fixed standard reads: GetDeviceInfo or GetStorageIDs, one per invocation.
// Apple manages the session and may catalog media.
// No vendor operations, property setters, clock sync, uploads, capture or resets.
#import <Foundation/Foundation.h>
#import <AppKit/AppKit.h>
#import <ImageCaptureCore/ImageCaptureCore.h>
#include <fcntl.h>
#include <unistd.h>

static NSData *InfoCommand(BOOL storageIDs) {
    const uint8_t bytes[] = {12,0,0,0,1,0,storageIDs ? 4 : 1,16,0,0,0,0};
    return [NSData dataWithBytes:bytes length:sizeof(bytes)];
}
static uint32_t U32(const uint8_t *p) {
    return (uint32_t)p[0] | (uint32_t)p[1]<<8 | (uint32_t)p[2]<<16 | (uint32_t)p[3]<<24;
}
static uint16_t U16(const uint8_t *p) { return p[0] | (uint16_t)p[1]<<8; }
static BOOL ValidStorageIDs(NSData *data) {
    if (data.length < 4 || data.length > 16) return NO;
    uint32_t count = U32(data.bytes);
    return count <= 3 && data.length == 4 + 4 * count;
}

@interface InfoReader : NSObject <ICDeviceBrowserDelegate, ICDeviceDelegate>
@property(nonatomic, strong) NSMutableArray<ICDevice *> *matches;
@property(nonatomic, strong) ICCameraDevice *camera;
@property(nonatomic, strong) NSMutableDictionary *report;
@property(nonatomic, copy) NSString *datasetPath;
@property(nonatomic, strong) NSTimer *stageTimer;
@property(nonatomic) BOOL opening, opened, catalogReady, sent, inFlight, closing, done;
@property(nonatomic) BOOL readStorageIDs;
- (void)beginSession;
- (void)finish:(NSString *)status;
@end

@implementation InfoReader
- (instancetype)init {
    if ((self = [super init])) {
        _matches = [NSMutableArray array];
        _report = [@{@"explicit_ptp_command_count": @0,
                     @"discovery_callbacks": @0,
                     @"session_opened": @NO, @"session_closed": @NO,
                     @"catalog_complete": @NO, @"catalog_items_reported": @0,
                     @"wire_traffic_observed": @NO} mutableCopy];
    }
    return self;
}
- (void)finish:(NSString *)status {
    if (self.done) return;
    self.done = YES;
    [self.stageTimer invalidate];
    self.report[@"status"] = status;
    self.report[@"client_has_open_session_at_exit"] = @(self.camera.hasOpenSession);
    [NSApp stop:nil];
    [NSApp postEvent:[NSEvent otherEventWithType:NSEventTypeApplicationDefined
        location:NSZeroPoint modifierFlags:0 timestamp:0 windowNumber:0
        context:nil subtype:0 data1:0 data2:0] atStart:NO];
}
- (void)armTimeout:(NSTimeInterval)seconds status:(NSString *)status {
    [self.stageTimer invalidate];
    self.stageTimer = [NSTimer scheduledTimerWithTimeInterval:seconds repeats:NO
        block:^(NSTimer *timer) { (void)timer; [self finish:status]; }];
}
- (void)noteError:(NSError *)error {
    // Localized descriptions can contain paths or device identifiers.
    self.report[@"error_domain"] = error.domain;
    self.report[@"error_code"] = @(error.code);
}
- (void)deviceBrowser:(ICDeviceBrowser *)browser didAddDevice:(ICDevice *)device
          moreComing:(BOOL)moreComing {
    if (self.opening || self.done) return;
    self.report[@"discovery_callbacks"] =
        @([self.report[@"discovery_callbacks"] unsignedIntegerValue] + 1);
    if (device.usbVendorID == 0x04cb && device.usbProductID == 0x030a &&
        [device isKindOfClass:[ICCameraDevice class]]) {
        if (![self.matches containsObject:device]) [self.matches addObject:device];
    }
}
- (void)deviceBrowser:(ICDeviceBrowser *)browser didRemoveDevice:(ICDevice *)device
           moreGoing:(BOOL)moreGoing {
    [self.matches removeObject:device];
    if (device == self.camera) [self finish:@"device_removed"];
}
- (void)beginSession {
    if (self.done) return;
    if (self.matches.count != 1) { [self finish:@"no_unique_camera"]; return; }
    self.camera = (ICCameraDevice *)self.matches.firstObject;
    if (![self.camera.name isEqualToString:@"X-HF1"] ||
        ![self.camera.capabilities containsObject:ICCameraDeviceCanAcceptPTPCommands] ||
        self.camera.hasOpenSession) {
        [self finish:@"identity_capability_or_session_precondition_failed"]; return;
    }
    self.report[@"model_from_discovery"] = @"X-HF1";
    self.report[@"usb_identity"] = @"04cb:030a";
    self.camera.delegate = self;
    self.opening = YES;
    [self armTimeout:30 status:@"session_or_catalog_timeout_no_explicit_cleanup"];
    [self.camera requestOpenSession];
}
- (void)sendIfReady {
    if (self.done || !self.opened || !self.catalogReady || self.sent) return;
    // Recheck identity on the retained object, not a cached USB address or port.
    if (self.camera.usbVendorID != 0x04cb || self.camera.usbProductID != 0x030a ||
        !self.camera.hasOpenSession) {
        [self finish:@"session_identity_changed"]; return;
    }
    self.sent = YES;
    self.inFlight = YES;
    self.report[@"explicit_ptp_command_count"] = @1;
    self.report[@"explicit_opcode"] = self.readStorageIDs ? @"1004" : @"1001";
    [self armTimeout:10 status:@"command_timeout_no_explicit_cleanup"];
    [self.camera requestSendPTPCommand:InfoCommand(self.readStorageIDs) outData:nil sendCommandDelegate:self
        didSendCommandSelector:@selector(didSendPTPCommand:inData:response:error:contextInfo:)
        contextInfo:NULL];
}
- (void)device:(ICDevice *)device didOpenSessionWithError:(NSError *)error {
    if (self.done || device != self.camera) return;
    if (error) { [self noteError:error]; [self finish:@"open_failed"]; return; }
    self.opened = YES;
    self.report[@"session_opened"] = @YES;
    [self sendIfReady];
}
- (void)deviceDidBecomeReadyWithCompleteContentCatalog:(ICCameraDevice *)camera {
    if (self.done || camera != self.camera) return;
    self.catalogReady = YES;
    self.report[@"catalog_complete"] = @YES;
    [self sendIfReady];
}
- (void)cameraDevice:(ICCameraDevice *)camera didAddItems:(NSArray *)items {
    if (self.done || camera != self.camera) return;
    self.report[@"catalog_items_reported"] =
        @([self.report[@"catalog_items_reported"] unsignedIntegerValue] + items.count);
}
- (void)cameraDevice:(ICCameraDevice *)camera didRemoveItems:(NSArray *)items {
    (void)camera; (void)items;
}
- (void)didSendPTPCommand:(NSData *)command inData:(NSData *)data
                response:(NSData *)response error:(NSError *)error contextInfo:(void *)context {
    (void)command; (void)context;
    if (self.done || !self.inFlight) return;
    self.inFlight = NO;
    if (error) { [self noteError:error]; [self finish:@"request_failed_no_explicit_cleanup"]; return; }
    if (response.length != 12 || U32(response.bytes) != 12 ||
        U16((const uint8_t *)response.bytes+4) != 3 ||
        U16((const uint8_t *)response.bytes+6) != 0x2001 ||
        data.length == 0 || data.length > 65536 ||
        (self.readStorageIDs && !ValidStorageIDs(data))) {
        [self finish:@"unexpected_reply_no_explicit_cleanup"]; return;
    }
    self.report[@"response_code"] = @"2001";
    self.report[@"returned_transaction_id"] = @(U32((const uint8_t *)response.bytes+8));
    self.report[@"dataset_bytes"] = @(data.length);
    // Private transient dataset may contain serial. Never print/base64 it.
    int fd = open(self.datasetPath.fileSystemRepresentation, O_WRONLY|O_CREAT|O_EXCL, 0600);
    BOOL saved = NO;
    if (fd >= 0) {
        ssize_t written = write(fd, data.bytes, data.length);
        int closeResult = close(fd);
        saved = written == (ssize_t)data.length && closeResult == 0;
    }
    self.report[@"dataset_saved"] = @(saved);
    self.closing = YES;
    [self armTimeout:10 status:@"close_timeout"];
    [self.camera requestCloseSession];
}
- (void)device:(ICDevice *)device didCloseSessionWithError:(NSError *)error {
    if (self.done || device != self.camera) return;
    if (error) { [self noteError:error]; [self finish:@"close_failed"]; return; }
    self.report[@"session_closed"] = @YES;
    [self finish:self.closing && [self.report[@"dataset_saved"] boolValue] ? @"ok" : @"session_closed_without_dataset"];
}
- (void)didRemoveDevice:(ICDevice *)device {
    if (device == self.camera) [self finish:@"device_removed"];
}
- (void)device:(ICDevice *)device didEncounterError:(NSError *)error {
    if (!self.done && device == self.camera && error) {
        [self noteError:error]; [self finish:@"device_error_no_explicit_cleanup"];
    }
}
@end

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        if (argc == 2 && strcmp(argv[1], "--self-test") == 0) {
            const uint8_t empty[] = {0,0,0,0}, one[] = {1,0,0,0,1,0,1,0};
            const uint8_t tooMany[] = {4,0,0,0}, trailing[] = {0,0,0,0,0};
            BOOL ok = U16((const uint8_t *)InfoCommand(NO).bytes+6) == 0x1001 &&
                U16((const uint8_t *)InfoCommand(YES).bytes+6) == 0x1004 &&
                InfoCommand(NO).length == 12 && InfoCommand(YES).length == 12 &&
                ValidStorageIDs([NSData dataWithBytes:empty length:sizeof(empty)]) &&
                ValidStorageIDs([NSData dataWithBytes:one length:sizeof(one)]) &&
                !ValidStorageIDs([NSData dataWithBytes:tooMany length:sizeof(tooMany)]) &&
                !ValidStorageIDs([NSData dataWithBytes:trailing length:sizeof(trailing)]);
            for (NSUInteger i = 0; i < sizeof(one); ++i)
                if (ValidStorageIDs([NSData dataWithBytes:one length:i])) ok = NO;
            puts(ok ? "Fixed metadata command and StorageIDs boundary self-tests passed; no camera access." : "Self-test failed.");
            return ok ? 0 : 1;
        }
        BOOL storageIDs = argc == 3 && strcmp(argv[1], "--read-storage-ids") == 0;
        if (argc != 3 || (!storageIDs && strcmp(argv[1], "--read-device-info") != 0) || argv[2][0] != '/') {
            fprintf(stderr, "Usage: imagecapture_device_info {--read-device-info|--read-storage-ids} /absolute/private/dataset\n");
            return 2;
        }
        [NSApplication sharedApplication];
        [NSApp setActivationPolicy:NSApplicationActivationPolicyProhibited];
        InfoReader *reader = [InfoReader new];
        reader.readStorageIDs = storageIDs;
        reader.datasetPath = [NSString stringWithUTF8String:argv[2]];
        ICDeviceBrowser *browser = [ICDeviceBrowser new];
        browser.delegate = reader;
        browser.browsedDeviceTypeMask = (ICDeviceTypeMask)
            (ICDeviceTypeMaskCamera | ICDeviceLocationTypeMaskLocal);
        [browser start];
        // Allow time for the user to answer macOS's removable-volume prompt.
        // The framework itself, not a private TCC bypass, obtains authorization.
        [NSTimer scheduledTimerWithTimeInterval:60 repeats:NO block:^(NSTimer *timer) {
            (void)timer; [reader beginSession];
        }];
        [NSApp run];
        reader.camera.delegate = nil;
        [browser stop];
        NSData *json = [NSJSONSerialization dataWithJSONObject:reader.report
            options:NSJSONWritingPrettyPrinted|NSJSONWritingSortedKeys error:nil];
        if (!json) return 1;
        fwrite(json.bytes, 1, json.length, stdout); fputc('\n', stdout);
        return [reader.report[@"status"] isEqualToString:@"ok"] ? 0 : 1;
    }
}
