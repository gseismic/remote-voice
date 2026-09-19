import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { createIcons, icons } from "lucide";

const $ = (id) => document.getElementById(id);
const tauriReady = Boolean(window.__TAURI_INTERNALS__);

const statusText = {
  idle: "未连接",
  stopped: "未连接",
  connecting: "连接中",
  registered: "等待手机",
  bridged: "已桥接",
  reconnecting: "重连中",
  fatal: "连接失败",
};

const statusDetail = {
  idle: "等待连接",
  stopped: "等待连接",
  connecting: "正在连接中继服务",
  registered: "等待手机连接",
  bridged: "音频正在转发",
  reconnecting: "网络暂时不可用",
  fatal: "请检查连接设置",
};

const fallbackState = {
  status: "stopped",
  detail: "等待连接",
  fatal_code: "",
  server: "",
  name: "",
  audio_device: "BlackHole",
  password_set: false,
  auto_reconnect: true,
  talking: false,
  dictation_enabled: true,
  dictation_mode: "hold",
  peer_name: "",
  audio_frames: 0,
  audio_bytes: 0,
  dropped_audio_frames: 0,
  audio_error: "",
  events: [],
};

let state = fallbackState;
let renderStarted = false;
let toastTimer = null;
let audioDevices = [];
let editingSettings = false;
// 连接按钮图标/文案只随「已连接与否」变化；桥接期间 app-state 约 2Hz 到达，
// 状态未变时跳过重建与全文档 SVG 图标重建，避免无谓的前端开销（PLAN-020）
let lastConnectIconState = null;
// 高频 app-state（音频统计等）只更新文本；列表/下拉/告警只在相关字段变化时重建
// （PLAN-021：音频输出失败时曾出现按帧重复推送，前端不应每事件重建 DOM）
let lastEventsSignature = null;
let lastAudioOptionsSignature = null;
let lastAudioAlertText = null;
let lastMeterHtml = null;
// 「显示密码」是显式动作：显示后保持，直到状态/密码变化或再次点按
let passwordRevealed = false;
let cachedPassword = "";

function formatBytes(value) {
  if (!Number.isFinite(value) || value < 1024) return `${Math.max(0, value || 0)} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / (1024 * 1024)).toFixed(1)} MB`;
}

function formatEventTime(timestamp) {
  const date = new Date(Number(timestamp || 0) * 1000);
  if (Number.isNaN(date.getTime()) || !timestamp) return "--:--";
  return date.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" });
}

function statusIsConnected(value) {
  return ["connecting", "registered", "bridged", "reconnecting"].includes(value);
}

function displayStatus(value) {
  return statusText[value] || "未连接";
}

function showToast(message, tone = "normal") {
  const toast = $("toast");
  toast.textContent = message;
  toast.dataset.tone = tone;
  toast.classList.add("is-visible");
  window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => toast.classList.remove("is-visible"), 3200);
}

function renderIcons() {
  createIcons({ icons, attrs: { "stroke-width": 1.8 } });
}

function commandError(error) {
  if (error && typeof error === "object") {
    return error.message || `${error.code || "command-error"}`;
  }
  try {
    const parsed = JSON.parse(error);
    return parsed.message || "操作失败";
  } catch {
    return String(error || "操作失败");
  }
}

async function call(command, args = {}) {
  if (!tauriReady) {
    throw { code: "desktop-only", message: "请在 Remote Voice 桌面应用中运行" };
  }
  return invoke(command, args);
}

function setValueIfIdle(element, value) {
  const text = value ?? "";
  if (document.activeElement !== element && element.value !== text) element.value = text;
}

function setText(element, value) {
  const text = value === null || value === undefined ? "" : String(value);
  if (element.textContent !== text) element.textContent = text;
}

function renderAudioOptions() {
  const select = $("audio-device");
  const current = state.audio_device || "BlackHole";
  const values = [...new Set([current, ...audioDevices].filter(Boolean))];
  const signature = `${current}\u0000${values.join("\u0000")}`;
  if (signature === lastAudioOptionsSignature) return;
  lastAudioOptionsSignature = signature;
  select.replaceChildren();
  if (!values.length) {
    const option = new Option("暂无可用设备", "");
    option.disabled = true;
    select.add(option);
    return;
  }
  values.forEach((value) => select.add(new Option(value, value)));
  select.value = current;
}

function renderEvents() {
  const events = Array.isArray(state.events) ? state.events : [];
  const signature = JSON.stringify(events.slice(0, 50));
  if (signature === lastEventsSignature) return;
  lastEventsSignature = signature;
  const list = $("event-list");
  list.replaceChildren();
  events.slice(0, 50).forEach((event) => {
    const item = document.createElement("li");
    item.className = "event-item";
    item.dataset.level = event.level || "info";

    const marker = document.createElement("span");
    marker.className = "event-marker";
    marker.setAttribute("aria-hidden", "true");
    const message = document.createElement("span");
    message.className = "event-message";
    message.textContent = event.message || "";
    const time = document.createElement("time");
    time.textContent = formatEventTime(event.at);
    time.dateTime = event.at ? new Date(event.at * 1000).toISOString() : "";
    item.append(marker, message, time);
    list.append(item);
  });
  $("empty-events").hidden = events.length > 0;
}

function renderConnectButton() {
  const button = $("connect-toggle");
  const connected = statusIsConnected(state.status);
  button.classList.toggle("is-disconnect", connected);
  button.setAttribute("aria-label", connected ? "断开连接" : "连接服务器");
  if (lastConnectIconState === connected) return;
  lastConnectIconState = connected;
  button.replaceChildren();
  const icon = document.createElement("i");
  icon.dataset.lucide = connected ? "power-off" : "power";
  icon.setAttribute("aria-hidden", "true");
  const label = document.createElement("span");
  label.textContent = connected ? "断开" : "连接";
  button.append(icon, label);
  renderIcons();
}

function renderRelayLine(status) {
  const connected = statusIsConnected(status);
  const lineState = connected
    ? (status === "connecting" || status === "reconnecting" ? "progress" : "true")
    : status === "fatal" ? "fatal" : "false";
  const line = $("relay-line");
  if (line.dataset.connected !== lineState) line.dataset.connected = lineState;
  setText($("relay-summary"), state.server || "未配置服务器");
  setText(
    $("relay-detail"),
    !state.server
      ? "点击填写服务器地址"
      : status === "fatal"
        ? "连接失败 · 点击本卡检查设置"
        : connected
          ? "保存修改将自动换线重连"
          : "点「连接」开始 · 点击本卡修改",
  );
}

/** 装饰电平表 + 音频状态行（真振幅数据源为开放问题，当前装饰动画） */
function renderAudioMeter(status) {
  const meter = $("meter");
  const live = status === "bridged" && !state.audio_error;
  meter.classList.toggle("off", !live || !state.talking);
  let meterHtml;
  if (state.audio_error) {
    meterHtml = "音频输出异常（详见下方提示）";
  } else if (status === "bridged" && state.talking) {
    meterHtml = state.dictation_enabled
      ? "对端说话中 · <b>模拟 Fn</b> · <b>0 丢帧</b>"
      : "对端说话中 · <b>0 丢帧</b>";
  } else if (status === "bridged") {
    meterHtml = "已就绪 · 按住手机按钮开始说话";
  } else if (statusIsConnected(status)) {
    meterHtml = "等待手机上线";
  } else {
    meterHtml = "未连接";
  }
  if (meterHtml !== lastMeterHtml) {
    lastMeterHtml = meterHtml;
    $("audiost").innerHTML = meterHtml;
  }
  // 手动解除兜底：模拟 Fn 期间可见（断线悬空时点按或按一次物理 Fn 均可放下）
  $("release-fn").hidden = !(state.talking && state.dictation_enabled);
}

function render(next) {
  state = { ...fallbackState, ...next };
  const status = state.status || "stopped";
  const cluster = $("status-cluster");
  if (cluster.dataset.status !== status) cluster.dataset.status = status;
  setText($("status-label"), displayStatus(status));
  // 证书更换恢复弹层：只在 fatal 且后端标记 cert-changed 时出现（PLAN-025）
  const certChanged = status === "fatal" && state.fatal_code === "cert-changed";
  if ($("cert-overlay").hidden !== !certChanged) $("cert-overlay").hidden = !certChanged;
  setText($("server-summary"), state.server || "尚未配置服务器");
  setText($("connection-detail"), state.detail || statusDetail[status] || "等待连接");
  setText(
    $("peer-summary"),
    state.peer_name
      ? `${state.peer_name} 已连接`
      : status === "bridged" ? "手机已连接" : "手机未连接",
  );
  // 配对动作可用性：需要已设置的服务器密码 + 可解析的服务器地址
  const pairingReady = Boolean(state.password_set && pairingServerPart());
  $("btn-qr").disabled = !pairingReady;
  $("btn-qr").title = pairingReady
    ? "出示配对二维码（手机扫码一键添加）"
    : state.password_set
      ? "先在连接设置里填写服务器地址"
      : "先在连接设置里设置服务器密码";
  $("btn-copy-password").disabled = !state.password_set;
  $("btn-show-password").disabled = !state.password_set;
  // 密码展示：默认掩码；显式「显示密码」后展示明文（缓存的配对载荷里解析）
  const code = $("password-code");
  const shown = state.password_set
    ? (passwordRevealed && cachedPassword ? cachedPassword : "••••-••••")
    : "未设置";
  setText(code, shown);
  setText($("device-status"), state.name ? `设备：${state.name}` : "身份：首次连接自动建立");
  renderCounters();

  // 弹窗打开期间不回填连接设置，避免高频 app-state 事件冲掉用户未提交的编辑
  if (!editingSettings) {
    setValueIfIdle($("server"), state.server);
    setValueIfIdle($("device-name"), state.name);
    const reconnectChecked = state.auto_reconnect !== false;
    const reconnectInput = $("auto-reconnect");
    if (document.activeElement !== reconnectInput && reconnectInput.checked !== reconnectChecked) {
      reconnectInput.checked = reconnectChecked;
    }
  }
  renderRelayLine(status);
  renderAudioMeter(status);
  renderAudioOptions();
  renderAudioAlert();
  renderEvents();
  renderConnectButton();
  renderStarted = true;
}

/** 音频统计是最高频字段，只走轻量文本更新 */
function renderCounters() {
  setText($("audio-frames"), Number(state.audio_frames || 0).toLocaleString("zh-CN"));
  setText($("audio-bytes"), formatBytes(Number(state.audio_bytes || 0)));
  setText($("dropped-frames"), Number(state.dropped_audio_frames || 0).toLocaleString("zh-CN"));
}

function renderAudioAlert() {
  const text = state.audio_error || "";
  if (text === lastAudioAlertText) return;
  lastAudioAlertText = text;
  const alert = $("audio-alert");
  alert.hidden = !text;
  alert.textContent = text;
}

async function refreshAudioDevices(quiet = false) {
  try {
    audioDevices = await call("list_audio_devices");
    renderAudioOptions();
    if (!quiet) showToast(`已发现 ${audioDevices.length} 个输出设备`);
  } catch (error) {
    renderAudioOptions();
    if (!quiet) showToast(commandError(error), "error");
  }
}

async function runAction(action, successMessage = "已完成") {
  try {
    const next = await action();
    if (next) render(next);
    if (successMessage) showToast(successMessage);
    return next;
  } catch (error) {
    showToast(commandError(error), "error");
    return null;
  }
}

// ---- 配对（服务器密码 + 二维码）----

/** 归一化配置里的服务器地址为 authority 部分（host[:port]）；不可解析返回空串。 */
function pairingServerPart() {
  let s = (state.server || "").trim();
  if (s.toLowerCase().startsWith("rv://")) s = s.slice(5);
  if (s.toLowerCase().startsWith("rvs://")) s = s.slice(6);
  s = s.split("?")[0].trim();
  // 与后端 parse_server 同规则的轻量校验：非空、无空白、无斜杠
  if (!s || /\s/.test(s) || s.includes("/")) return "";
  return s;
}

/** 从配对载荷中解码 s=（密码原文）与 n=（设备名）。 */
function parsePairingPayload(payload) {
  const query = payload.split("?")[1] || "";
  const params = new URLSearchParams(query.replace(/\+/g, "%20"));
  return { password: params.get("s") || "", name: params.get("n") || "" };
}

async function fetchPairingPayload() {
  const payload = await call("get_pairing_payload");
  cachedPassword = parsePairingPayload(payload).password;
  return payload;
}

/** 用 vendor/qrcode.js 生成模块矩阵（版本自动：从 v1 起尝试），渲染为 SVG。 */
function renderPairingQr(uri) {
  // 中文设备名需要 UTF-8 字节模式（库默认单字节编码会出错）
  window.qrcode.stringToBytes = window.qrcode.stringToBytesFuncs["UTF-8"];
  let qr = null;
  for (let version = 1; version <= 10 && !qr; version++) {
    try {
      const candidate = window.qrcode(version, "M");
      candidate.addData(uri, "Byte");
      candidate.make();
      qr = candidate;
    } catch {
      // 容量不足，试更大版本
    }
  }
  if (!qr) return null;
  const count = qr.getModuleCount();
  const cells = [];
  for (let y = 0; y < count; y++) {
    for (let x = 0; x < count; x++) {
      if (qr.isDark(y, x)) {
        cells.push(`<rect x="${x}" y="${y}" width="1" height="1"/>`);
      }
    }
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${count} ${count}" ` +
    `shape-rendering="crispEdges"><rect width="${count}" height="${count}" fill="#fff"/>` +
    `<g fill="#111">${cells.join("")}</g></svg>`;
}

async function showPairingQr() {
  let payload;
  try {
    payload = await fetchPairingPayload();
  } catch (error) {
    showToast(commandError(error), "error");
    return;
  }
  const svg = renderPairingQr(payload);
  if (!svg) {
    showToast("二维码生成失败", "error");
    return;
  }
  $("qr-box").innerHTML = svg;
  setText($("qr-secret"), cachedPassword);
  $("qr-overlay").hidden = false;
}

function closePairingQr() {
  $("qr-overlay").hidden = true;
  $("qr-box").innerHTML = "";
}

async function togglePasswordVisibility() {
  if (!state.password_set) return;
  if (passwordRevealed && cachedPassword) {
    passwordRevealed = false;
    render(state);
    return;
  }
  try {
    await fetchPairingPayload();
    passwordRevealed = true;
    render(state);
  } catch (error) {
    showToast(commandError(error), "error");
  }
}

async function copyPassword() {
  if (!state.password_set) return;
  let password = cachedPassword;
  try {
    if (!password) {
      await fetchPairingPayload();
      password = cachedPassword;
    }
    await navigator.clipboard.writeText(password);
    showToast("服务器密码已复制");
  } catch (error) {
    if (typeof error === "object" && error && error.code) {
      showToast(commandError(error), "error");
      return;
    }
    // 剪贴板不可用时退化为临时 textarea
    const input = document.createElement("textarea");
    input.value = password || "";
    input.style.position = "fixed";
    input.style.opacity = "0";
    document.body.append(input);
    input.select();
    document.execCommand("copy");
    input.remove();
    showToast("服务器密码已复制");
  }
}

async function toggleConnection() {
  if (statusIsConnected(state.status)) {
    await runAction(() => call("disconnect"), "已断开连接");
    return;
  }
  if (!$("server").value.trim()) {
    openSettings();
    showToast("请先填写服务器地址", "error");
    return;
  }
  await runAction(async () => {
    // 连接动作同时提交当前设置，让首次使用只需在弹窗填一次服务器地址即可开始。
    await call("save_settings", readSettings());
    return call("connect");
  }, "正在连接");
}

function readSettings() {
  return {
    server: $("server").value.trim(),
    name: $("device-name").value.trim(),
    audioDevice: $("audio-device").value.trim() || "BlackHole",
    dictationEnabled: $("dictation-enabled").checked,
    dictationMode: $("dictation-mode").value,
    autoReconnect: $("auto-reconnect").checked,
  };
}

function openSettings() {
  editingSettings = true;
  $("server").value = state.server || "";
  $("device-name").value = state.name || "";
  $("server-password").value = "";
  setText(
    $("password-field-label"),
    state.password_set
      ? "服务器密码（留空保持不变）"
      : "服务器密码（至少 12 位；手机端输入它即可控制本机）",
  );
  $("auto-reconnect").checked = state.auto_reconnect !== false;
  $("dictation-enabled").checked = state.dictation_enabled !== false;
  $("dictation-mode").value = state.dictation_mode || "hold";
  $("settings-error").hidden = true;
  $("settings-overlay").hidden = false;
  $("server").focus();
}

function closeSettings() {
  editingSettings = false;
  $("settings-overlay").hidden = true;
}

async function saveSettings() {
  try {
    const next = await call("save_settings", readSettings());
    // 密码输入非空 = 更新服务器密码（走 Keychain + REGISTER 热更）
    const password = $("server-password").value.trim();
    if (password) {
      await call("set_server_password", { password });
    }
    render(next);
    closeSettings();
    showToast("设置已保存");
  } catch (error) {
    const box = $("settings-error");
    box.textContent = commandError(error);
    box.hidden = false;
  }
}

function bindEvents() {
  $("btn-show-password").addEventListener("click", togglePasswordVisibility);
  $("btn-copy-password").addEventListener("click", copyPassword);
  $("btn-qr").addEventListener("click", showPairingQr);
  $("close-qr").addEventListener("click", closePairingQr);
  $("qr-overlay").addEventListener("click", (event) => {
    if (event.target === $("qr-overlay")) closePairingQr();
  });
  $("connect-toggle").addEventListener("click", toggleConnection);
  // 右上状态胶囊点按 = 连接/断开/重试（V3.2 原型交互，与底部按钮同语义）
  $("status-cluster").addEventListener("click", toggleConnection);
  $("relay-panel").addEventListener("click", openSettings);
  $("open-settings").addEventListener("click", openSettings);
  $("close-settings").addEventListener("click", closeSettings);
  $("cancel-settings").addEventListener("click", closeSettings);
  $("settings-overlay").addEventListener("click", (event) => {
    if (event.target === $("settings-overlay")) closeSettings();
  });
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && !$("settings-overlay").hidden) closeSettings();
  });
  $("save-settings").addEventListener("click", saveSettings);
  $("release-fn").addEventListener("click", () =>
    runAction(() => call("release_fn"), "已放下 Fn"));
  $("clear-trust").addEventListener("click", () => {
    if (!window.confirm("清除当前服务器信任后，下一次连接会重新接受服务器证书。继续吗？")) {
      return;
    }
    runAction(
      () => call("clear_server_trust"),
      "服务器信任已清除，下次连接将重新建立",
    );
  });
  $("refresh-audio").addEventListener("click", () => refreshAudioDevices(false));
  $("clear-history").addEventListener("click", () =>
    runAction(() => call("clear_history"), "事件已清空"));
}

async function boot() {
  bindEvents();
  render(fallbackState);
  renderIcons();
  if (!tauriReady) {
    showToast("预览模式：请通过桌面应用连接", "normal");
    return;
  }
  try {
    await listen("app-state", (event) => render(event.payload));
    const current = await call("get_state");
    render(current);
    await refreshAudioDevices(true);
  } catch (error) {
    showToast(commandError(error), "error");
  }
}

boot();
