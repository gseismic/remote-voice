package server

import (
	"sync"
	"time"
)

// 防爆破限速参数（设计文档 D4）：
// 同一来源 IP 在滑动窗口内失败≥maxFail 次即刻锁定，逐级递增且封顶；
// 锁定期内命中全部直接拒绝（rate-limit 不计数），成功认证清零。
const (
	maxFailures   = 5                      // 窗口内失败次数阈值
	failureWindow = 60 * time.Second       // 滑动窗口
	lockBase      = 1 * time.Minute        // 第一次锁定
	lockLevels    = 3                      // 1m → 5m → 30m，共 3 级后封顶
	lockLevelMult = 5                      // 每升一级 ×5
)

// rateLimiter 按 IP 维护失败滑窗与递增锁。所有方法并发安全。
// 键为来源 IP（不含端口）。实现为简单结构体，单机使用足够。
type rateLimiter struct {
	mu sync.Mutex
	m  map[string]*limiterEntry
}

type limiterEntry struct {
	failTimes []time.Time // 滑窗内最近失败时间（最多 maxFailures 个）
	lockUntil time.Time   // 锁定期截止；零值=未锁定
	level     int         // 当前锁级（锁定时有效）：1..lockLevels
}

func newRateLimiter() *rateLimiter {
	return &rateLimiter{m: make(map[string]*limiterEntry)}
}

// locked 返回该 IP 当前是否处于锁定期。若锁已过期则顺带复位状态。
func (rl *rateLimiter) locked(ip string, now time.Time) bool {
	rl.mu.Lock()
	defer rl.mu.Unlock()
	e, ok := rl.m[ip]
	if !ok {
		return false
	}
	if e.lockUntil.IsZero() {
		return false // 从未锁定：零值不等于过期
	}
	if now.After(e.lockUntil) {
		// 锁到期：清空计数与锁级，重新计数（锁级不降，下次直接按当前级续锁）
		e.failTimes = nil
		e.lockUntil = time.Time{}
		return false
	}
	return true
}

// fail 记录一次认证失败（仅对错误秘密类失败调用，见 I6），
// 达到窗口阈值即开出锁定（逐级递增）。返回值告知是否本次失败触发了新锁。
func (rl *rateLimiter) fail(ip string, now time.Time) (locked bool) {
	rl.mu.Lock()
	defer rl.mu.Unlock()
	e, ok := rl.m[ip]
	if !ok {
		e = &limiterEntry{}
		rl.m[ip] = e
	}
	// 越过锁定期后再失败：清滑窗，锁级保留（零值=从未锁定）
	if !e.lockUntil.IsZero() && now.After(e.lockUntil) {
		e.failTimes = nil
		e.lockUntil = time.Time{}
	}
	// 滑窗滚动：剔除窗口外旧失败（新分配切片，避免原地过滤的别名覆盖）
	cutoff := now.Add(-failureWindow)
	kept := make([]time.Time, 0, len(e.failTimes)+1)
	for _, t := range e.failTimes {
		if t.After(cutoff) {
			kept = append(kept, t)
		}
	}
	e.failTimes = append(kept, now)
	if len(e.failTimes) < maxFailures {
		return false
	}
	// 达到阈值：递增锁定（逐级封顶）
	if e.level < lockLevels {
		e.level++
	}
	mult := time.Duration(1)
	for i := 1; i < e.level; i++ {
		mult *= lockLevelMult
	}
	e.lockUntil = now.Add(lockBase * mult)
	e.failTimes = nil
	return true
}

// clear 认证成功：清零该 IP 全部状态。
func (rl *rateLimiter) clear(ip string) {
	rl.mu.Lock()
	defer rl.mu.Unlock()
	delete(rl.m, ip)
}
