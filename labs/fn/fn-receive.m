#import <Cocoa/Cocoa.h>
#import <Carbon/Carbon.h>
#import <CoreGraphics/CoreGraphics.h>

int main(void) {
    @autoreleasepool {
        NSApplication *app = NSApplication.sharedApplication;
        [app setActivationPolicy:NSApplicationActivationPolicyRegular];
        [app finishLaunching];

        NSWindow *window = [[NSWindow alloc] initWithContentRect:NSMakeRect(0, 0, 360, 100)
            styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable
            backing:NSBackingStoreBuffered defer:NO];
        window.title = @"Fn 接收测试（按实体 Fn 或运行发送器）";
        window.releasedWhenClosed = NO;

        // 只观察当前应用收到的事件。保持事件按原路径分发。
        id monitor = [NSEvent addLocalMonitorForEventsMatchingMask:NSEventMaskFlagsChanged
            handler:^NSEvent *(NSEvent *event) {
                if (window.isKeyWindow && event.keyCode == kVK_Function) {
                    BOOL down = (event.modifierFlags & NSEventModifierFlagFunction) != 0;
                    CGEventRef cgEvent = event.CGEvent;
                    long long source = cgEvent == NULL ? 0 :
                        CGEventGetIntegerValueField(cgEvent, kCGEventSourceUserData);
                    printf("%.3f Fn=%d source=%llx\n", NSDate.date.timeIntervalSince1970,
                           down, (unsigned long long)source);
                    fflush(stdout);
                }
                return event;
            }];
        [NSNotificationCenter.defaultCenter addObserverForName:NSWindowWillCloseNotification
            object:window queue:nil usingBlock:^(__unused NSNotification *note) {
                [NSEvent removeMonitor:monitor];
                [app terminate:nil];
            }];

        [window center];
        [window makeKeyAndOrderFront:nil];
        [app activateIgnoringOtherApps:YES];
        puts("Fn 接收测试窗口已打开。");
        fflush(stdout);
        [app run];
    }
    return 0;
}
