#include <ApplicationServices/ApplicationServices.h>
#include <Carbon/Carbon.h>
#include <stdio.h>
#include <unistd.h>

static void post_fn(CGEventRef event, bool down) {
    CGEventFlags flags = CGEventSourceFlagsState(kCGEventSourceStateHIDSystemState);
    CGEventSetType(event, kCGEventFlagsChanged);
    CGEventSetFlags(event, down ? flags | kCGEventFlagMaskSecondaryFn
                                : flags & ~(CGEventFlags)kCGEventFlagMaskSecondaryFn);
    // 与 Remote Voice 使用相同的标记，便于接收器识别模拟事件。
    CGEventSetIntegerValueField(event, kCGEventSourceUserData, 0x5256464e);
    CGEventPost(kCGHIDEventTap, event);
    puts(down ? "已提交 Fn 按下" : "已提交 Fn 松开");
    fflush(stdout);
}

int main(void) {
    if (!CGPreflightPostEventAccess()) {
        fputs("当前进程没有发送按键的权限，请在 macOS 的「辅助功能」中授权。\n", stderr);
        return 2;
    }

    // 先准备两条事件，确保开始按下时已有对应的松开事件。
    CGEventRef down = CGEventCreateKeyboardEvent(NULL, kVK_Function, true);
    CGEventRef up = CGEventCreateKeyboardEvent(NULL, kVK_Function, false);
    if (down == NULL || up == NULL) {
        if (down != NULL) CFRelease(down);
        if (up != NULL) CFRelease(up);
        fputs("创建 Fn 事件失败。\n", stderr);
        return 1;
    }

    puts("3 秒后发送 Fn，请切到接收窗口。");
    fflush(stdout);
    sleep(3);
    post_fn(down, true);
    sleep(2);
    post_fn(up, false);
    CFRelease(down);
    CFRelease(up);
    return 0;
}
