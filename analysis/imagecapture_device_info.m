// Fixed metadata reads and one factory cached-chunk availability probe.
// Apple manages the session and may catalog media.
// No factory load/write, property setters, clock sync, uploads, capture or resets.
#import <Foundation/Foundation.h>
#import <AppKit/AppKit.h>
#import <ImageCaptureCore/ImageCaptureCore.h>
#include <fcntl.h>
#include <unistd.h>

static NSData *InfoCommand(BOOL storageIDs) {
    const uint8_t bytes[] = {12,0,0,0,1,0,storageIDs ? 4 : 1,16,0,0,0,0};
    return [NSData dataWithBytes:bytes length:sizeof(bytes)];
}
static NSData *StorageInfoCommand(void) {
    const uint8_t bytes[] = {16,0,0,0,1,0,5,16,0,0,0,0,1,0,1,0};
    return [NSData dataWithBytes:bytes length:sizeof(bytes)];
}
static NSData *D220DescCommand(void) {
    const uint8_t bytes[] = {16,0,0,0,1,0,20,16,0,0,0,0,32,210,0,0};
    return [NSData dataWithBytes:bytes length:sizeof(bytes)];
}
static NSData *FactoryProbeCommand(void) {
    // Exactly 1027(0): read first cached chunk. No user-selected offset/mode.
    const uint8_t bytes[] = {16,0,0,0,1,0,39,16,0,0,0,0,0,0,0,0};
    return [NSData dataWithBytes:bytes length:sizeof(bytes)];
}
static uint32_t U32(const uint8_t *p) {
    return (uint32_t)p[0] | (uint32_t)p[1]<<8 | (uint32_t)p[2]<<16 | (uint32_t)p[3]<<24;
}
static uint16_t U16(const uint8_t *p) { return p[0] | (uint16_t)p[1]<<8; }
static int FactoryProbeReply(NSData *response, NSData *data) {
    if (data.length || (response.length != 12 && response.length != 32)) return 0;
    const uint8_t *p = response.bytes;
    if (U32(p) != response.length || U16(p+4) != 3) return 0;
    if (response.length == 12 && U16(p+6) == 0x2005) return 2;
    if (response.length == 32 && U16(p+6) == 0x2001 && U32(p+12) == 0) return 1;
    return 0;
}
static BOOL ValidStorageIDs(NSData *data) {
    if (data.length < 4 || data.length > 16) return NO;
    uint32_t count = U32(data.bytes);
    return count <= 3 && data.length == 4 + 4 * count;
}
static BOOL ValidStorageInfo(NSData *data) {
    if (data.length < 28 || data.length > 1048) return NO;
    const uint8_t *p = data.bytes;
    NSUInteger offset = 26;
    for (int i = 0; i < 2; ++i) {
        if (offset >= data.length) return NO;
        NSUInteger units = p[offset++];
        if (units * 2 > data.length - offset) return NO;
        if (units) {
            if (U16(p + offset + (units - 1) * 2) != 0) return NO;
            for (NSUInteger j = 0; j + 1 < units; ++j)
                if (U16(p + offset + j * 2) == 0) return NO;
            NSString *decoded = [[NSString alloc] initWithBytes:p+offset
                length:(units-1)*2 encoding:NSUTF16LittleEndianStringEncoding];
            if (!decoded) return NO;
        }
        offset += units * 2;
    }
    return offset == data.length;
}

static BOOL ValidD220Desc(NSData *data) {
    if (data.length != 26) return NO;
    const uint8_t *p = data.bytes;
    return U16(p) == 0xd220 && U16(p+2) == 6 && p[4] == 0 && p[13] == 1;
}

@interface InfoReader : NSObject <ICDeviceBrowserDelegate, ICDeviceDelegate>
@property(nonatomic, strong) NSMutableArray<ICDevice *> *matches;
@property(nonatomic, strong) ICCameraDevice *camera;
@property(nonatomic, strong) NSMutableDictionary *report;
@property(nonatomic, copy) NSString *datasetPath;
@property(nonatomic, strong) NSTimer *stageTimer;
@property(nonatomic) BOOL opening, opened, catalogReady, sent, inFlight, closing, done;
@property(nonatomic) BOOL readStorageIDs;
@property(nonatomic) BOOL readStorageInfo;
@property(nonatomic) BOOL readD220Desc;
@property(nonatomic) BOOL readFactoryProbe;
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
    self.report[@"explicit_opcode"] = self.readFactoryProbe ? @"1027" : self.readD220Desc ? @"1014" : self.readStorageInfo ? @"1005" : self.readStorageIDs ? @"1004" : @"1001";
    if (self.readFactoryProbe) self.report[@"factory_parameter"] = @"00000000";
    if (self.readD220Desc) self.report[@"property_code_parameter"] = @"d220";
    if (self.readStorageInfo) self.report[@"storage_id_parameter"] = @"00010001";
    [self armTimeout:10 status:@"command_timeout_no_explicit_cleanup"];
    NSData *command = self.readFactoryProbe ? FactoryProbeCommand() : self.readD220Desc ? D220DescCommand() : self.readStorageInfo ? StorageInfoCommand() : InfoCommand(self.readStorageIDs);
    [self.camera requestSendPTPCommand:command outData:nil sendCommandDelegate:self
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
    if (self.readFactoryProbe) {
        int outcome = FactoryProbeReply(response, data);
        if (!outcome) { [self finish:@"unexpected_reply_no_explicit_cleanup"]; return; }
        self.report[@"response_code"] = outcome == 1 ? @"2001" : @"2005";
        self.report[@"factory_probe_outcome"] = outcome == 1 ? @"cached_chunk_reply" : @"operation_not_supported";
        self.report[@"returned_transaction_id"] = @(U32((const uint8_t *)response.bytes+8));
        self.report[@"response_bytes"] = @(response.length);
        self.report[@"dataset_bytes"] = @(data.length);
        self.report[@"factory_chunk_bytes_omitted"] = outcome == 1 ? @16 : @0;
        // Deliberately omit cached factory words: not a fresh or validated record.
        self.closing = YES;
        [self armTimeout:10 status:@"close_timeout"];
        [self.camera requestCloseSession];
        return;
    }
    if (response.length != 12 || U32(response.bytes) != 12 ||
        U16((const uint8_t *)response.bytes+4) != 3 ||
        U16((const uint8_t *)response.bytes+6) != 0x2001 ||
        data.length == 0 || data.length > 65536 ||
        (self.readStorageIDs && !ValidStorageIDs(data)) ||
        (self.readStorageInfo && !ValidStorageInfo(data)) ||
        (self.readD220Desc && !ValidD220Desc(data))) {
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
    BOOL resultReady = [self.report[@"dataset_saved"] boolValue] ||
        (self.readFactoryProbe && self.report[@"factory_probe_outcome"] != nil);
    [self finish:self.closing && resultReady ? @"ok" : @"session_closed_without_result"];
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
            const uint8_t minimalInfo[28] = {0};
            uint8_t invalidInfo[28] = {0}; invalidInfo[26] = 255;
            NSData *infoCommand = StorageInfoCommand();
            ok = ok && infoCommand.length == 16 && U16((const uint8_t *)infoCommand.bytes+6) == 0x1005 &&
                U32((const uint8_t *)infoCommand.bytes+12) == 0x00010001 &&
                ValidStorageInfo([NSData dataWithBytes:minimalInfo length:28]) &&
                !ValidStorageInfo([NSData dataWithBytes:invalidInfo length:28]);
            for (NSUInteger i = 0; i < 28; ++i)
                if (ValidStorageInfo([NSData dataWithBytes:minimalInfo length:i])) ok = NO;
            const uint8_t desc[26] = {32,210,6,0,0,0,0,0,0,0,0,0,0,1};
            NSData *descCommand = D220DescCommand();
            ok = ok && descCommand.length == 16 && U32(descCommand.bytes) == 16 &&
                U16((const uint8_t *)descCommand.bytes+4) == 1 &&
                U16((const uint8_t *)descCommand.bytes+6) == 0x1014 &&
                U32((const uint8_t *)descCommand.bytes+8) == 0 &&
                U32((const uint8_t *)descCommand.bytes+12) == 0xd220 &&
                ValidD220Desc([NSData dataWithBytes:desc length:26]);
            for (NSUInteger i = 0; i < 26; ++i)
                if (ValidD220Desc([NSData dataWithBytes:desc length:i])) ok = NO;
            for (NSUInteger i = 0; i < 5; ++i) {
                uint8_t bad[27]; memcpy(bad, desc, 26); bad[26] = 0;
                NSUInteger positions[] = {0,2,4,13};
                if (i < 4) bad[positions[i]] ^= 1;
                if (ValidD220Desc([NSData dataWithBytes:bad length:i == 4 ? 27 : 26])) ok = NO;
            }
            const uint8_t probeCommand[] = {16,0,0,0,1,0,39,16,0,0,0,0,0,0,0,0};
            ok = ok && [FactoryProbeCommand() isEqualToData:[NSData dataWithBytes:probeCommand length:16]];
            uint8_t supported[33] = {32,0,0,0,3,0,1,32,7,0,0,0};
            uint8_t unsupported[13] = {12,0,0,0,3,0,5,32,9,0,0,0};
            NSData *emptyData = [NSData data];
            ok = ok && FactoryProbeReply([NSData dataWithBytes:supported length:32], emptyData) == 1 &&
                FactoryProbeReply([NSData dataWithBytes:unsupported length:12], emptyData) == 2 &&
                !FactoryProbeReply([NSData dataWithBytes:supported length:32], InfoCommand(NO));
            for (NSUInteger i = 0; i < 33; ++i)
                if (i != 32 && FactoryProbeReply([NSData dataWithBytes:supported length:i], emptyData)) ok = NO;
            for (NSUInteger i = 0; i < 14; ++i) {
                uint8_t bad[32]; memcpy(bad, supported, 32);
                NSUInteger positions[] = {0,1,2,3,4,5,6,7,12,13,14,15};
                if (i < 12) bad[positions[i]] ^= 1;
                else { bad[6] = 5; if (i == 13) bad[0] = 12; }
                if (FactoryProbeReply([NSData dataWithBytes:bad length:32], emptyData)) ok = NO;
            }
            for (NSUInteger i = 0; i < 14; ++i)
                if (i != 12 && FactoryProbeReply([NSData dataWithBytes:unsupported length:i], emptyData)) ok = NO;
            puts(ok ? "Fixed reads, factory probe and reply boundary self-tests passed; no camera access." : "Self-test failed.");
            return ok ? 0 : 1;
        }
        BOOL factoryProbe = argc == 3 && strcmp(argv[1], "--probe-factory-chunk") == 0;
        BOOL d220Desc = argc == 3 && strcmp(argv[1], "--read-d220-desc") == 0;
        BOOL storageIDs = argc == 3 && strcmp(argv[1], "--read-storage-ids") == 0;
        BOOL storageInfo = argc == 3 && strcmp(argv[1], "--read-storage-info-known") == 0;
        if (argc != 3 || (!factoryProbe && !d220Desc && !storageIDs && !storageInfo && strcmp(argv[1], "--read-device-info") != 0) || argv[2][0] != '/') {
            fprintf(stderr, "Usage: imagecapture_device_info {--read-device-info|--read-storage-ids|--read-storage-info-known|--read-d220-desc|--probe-factory-chunk} /absolute/private/dataset\n");
            return 2;
        }
        [NSApplication sharedApplication];
        [NSApp setActivationPolicy:NSApplicationActivationPolicyProhibited];
        InfoReader *reader = [InfoReader new];
        reader.readStorageIDs = storageIDs;
        reader.readStorageInfo = storageInfo;
        reader.readD220Desc = d220Desc;
        reader.readFactoryProbe = factoryProbe;
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
