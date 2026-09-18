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
  server: "",
  name: "",
  audio_device: "BlackHole",
  temp_secret: "--------",
  temp_exp: 0,
  temp_duration: 28800,
  keep: false,
  talking: false,
  dictation_enabled: true,
  dictation_mode: "hold",
  permanent_enabled: false,
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
let expiryRefreshAttempt = 0;
let editingSettings = false;
// 连接按钮图标/文案只随「已连接与否」变化；桥接期间 app-state 约 2Hz 到达，
// 状态未变时跳过重建与全文档 SVG 图标重建，避免无谓的前端开销（PLAN-020）
let lastConnectIconState = null;

function formatBytes(value) {
  if (!Number.isFinite(value) || value < 1024) return `${Math.max(0, value || 0)} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / (1024 * 1024)).toFixed(1)} MB`;
}

function formatTime(seconds) {
  const value = Math.max(0, Math.floor(seconds));
  const hours = String(Math.floor(value / 3600)).padStart(2, "0");
  const minutes = String(Math.floor((value % 3600) / 60)).padStart(2, "0");
  const remainder = String(value % 60).padStart(2, "0");
  return `${hours}:${minutes}:${remainder}`;
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
  if (document.activeElement !== element) element.value = value ?? "";
}

function renderAudioOptions() {
  const select = $("audio-device");
  const current = state.audio_device || "BlackHole";
  const values = [...new Set([current, ...audioDevices].filter(Boolean))];
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
  const list = $("event-list");
  const events = Array.isArray(state.events) ? state.events : [];
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
  $("relay-line").dataset.connected = lineState;
  $("relay-summary").textContent = state.server || "未配置服务器";
  $("relay-detail").textContent = !state.server
    ? "点击填写服务器地址"
    : status === "fatal"
      ? "连接失败 · 点击本卡检查设置"
      : connected
        ? "保存修改将自动换线重连"
        : "点「连接」开始 · 点击本卡修改";
}

/** 装饰电平表 + 音频状态行（真振幅数据源为开放问题，当前装饰动画） */
function renderAudioMeter(status) {
  const meter = $("meter");
  const live = status === "bridged" && !state.audio_error;
  meter.classList.toggle("off", !live || !state.talking);
  const audiost = $("audiost");
  if (state.audio_error) {
    audiost.textContent = "音频输出异常（详见下方提示）";
  } else if (status === "bridged" && state.talking) {
    audiost.innerHTML = state.dictation_enabled
      ? "对端说话中 · <b>模拟 Fn</b> · <b>0 丢帧</b>"
      : "对端说话中 · <b>0 丢帧</b>";
  } else if (status === "bridged") {
    audiost.textContent = "已就绪 · 按住手机按钮开始说话";
  } else if (statusIsConnected(status)) {
    audiost.textContent = "等待手机上线";
  } else {
    audiost.textContent = "未连接";
  }
  // 手动解除兜底：模拟 Fn 期间可见（断线悬空时点按或按一次物理 Fn 均可放下）
  $("release-fn").hidden = !(state.talking && state.dictation_enabled);
}

function render(next) {
  state = { ...fallbackState, ...next };
  const status = state.status || "stopped";
  const cluster = $("status-cluster");
  cluster.dataset.status = status;
  $("status-label").textContent = displayStatus(status);
  $("server-summary").textContent = state.server || "尚未配置服务器";
  $("connection-detail").textContent = state.detail || statusDetail[status] || "等待连接";
  $("peer-summary").textContent = state.peer_name
    ? `${state.peer_name} 已连接`
    : status === "bridged" ? "手机已连接" : "手机未连接";
  $("temp-secret").textContent = state.temp_secret || "--------";
  $("permanent-state").textContent = state.permanent_enabled ? "已启用" : "未设置";
  $("permanent-state").dataset.enabled = state.permanent_enabled ? "true" : "false";
  $("device-status").textContent = state.name ? `设备：${state.name}` : "身份：首次连接自动建立";
  $("audio-frames").textContent = Number(state.audio_frames || 0).toLocaleString("zh-CN");
  $("audio-bytes").textContent = formatBytes(Number(state.audio_bytes || 0));
  $("dropped-frames").textContent = Number(state.dropped_audio_frames || 0).toLocaleString("zh-CN");

  // 弹窗打开期间不回填连接设置，避免高频 app-state 事件冲掉用户未提交的编辑
  if (!editingSettings) {
    setValueIfIdle($("server"), state.server);
    setValueIfIdle($("device-name"), state.name);
    if (document.activeElement !== $("temp-expiry-choice")) {
      $("temp-expiry-choice").value = String(state.temp_duration || 28800);
    }
    if (document.activeElement !== $("keep-temp")) $("keep-temp").checked = Boolean(state.keep);
  }
  renderRelayLine(status);
  renderAudioMeter(status);
  renderAudioOptions();
  const alert = $("audio-alert");
  alert.hidden = !state.audio_error;
  alert.textContent = state.audio_error || "";
  renderEvents();
  renderConnectButton();
  renderStarted = true;
}

function renderCountdown() {
  const seconds = Number(state.temp_exp || 0) - Math.floor(Date.now() / 1000);
  const expiry = $("temp-expiry");
  expiry.textContent = seconds > 0 ? formatTime(seconds) : "已过期";
  expiry.dataset.expired = seconds > 0 ? "false" : "true";
  if (
    seconds <= 0 &&
    state.temp_exp > 0 &&
    tauriReady &&
    renderStarted &&
    expiryRefreshAttempt !== state.temp_exp
  ) {
    expiryRefreshAttempt = state.temp_exp;
    runAction(() => call("regenerate_temp"), "临时密码已自动更新");
  }
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

async function copyTempSecret() {
  if (!state.temp_secret || state.temp_secret === "--------") {
    showToast("临时密码尚未生成", "error");
    return;
  }
  try {
    await navigator.clipboard.writeText(state.temp_secret);
    showToast("临时密码已复制");
  } catch {
    const input = document.createElement("textarea");
    input.value = state.temp_secret;
    input.style.position = "fixed";
    input.style.opacity = "0";
    document.body.append(input);
    input.select();
    document.execCommand("copy");
    input.remove();
    showToast("临时密码已复制");
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
    tempDuration: Number($("temp-expiry-choice").value),
    keep: $("keep-temp").checked,
    dictationEnabled: $("dictation-enabled").checked,
    dictationMode: $("dictation-mode").value,
  };
}

function openSettings() {
  editingSettings = true;
  $("server").value = state.server || "";
  $("device-name").value = state.name || "";
  $("temp-expiry-choice").value = String(state.temp_duration || 28800);
  $("keep-temp").checked = Boolean(state.keep);
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

function togglePermFold() {
  const panel = $("perm-panel");
  panel.classList.toggle("open");
  $("btn-fold").textContent = panel.classList.contains("open") ? "收起 ▴" : "展开管理 ▾";
}

async function saveSettings() {
  const wasConnected = statusIsConnected(state.status);
  try {
    const next = await call("save_settings", readSettings());
    render(next);
    closeSettings();
    // 已连接时后端保存会自动按新地址换线重连（controller.save_settings）
    showToast(wasConnected ? "设置已保存，正在按新设置重连" : "设置已保存");
  } catch (error) {
    const box = $("settings-error");
    box.textContent = commandError(error);
    box.hidden = false;
  }
}

function bindEvents() {
  $("copy-temp").addEventListener("click", copyTempSecret);
  $("regenerate-temp").addEventListener("click", () =>
    runAction(() => call("regenerate_temp"), "临时密码已更新"));
  $("connect-toggle").addEventListener("click", toggleConnection);
  // 右上状态胶囊点按 = 连接/断开/重试（V3.2 原型交互，与底部按钮同语义）
  $("status-cluster").addEventListener("click", toggleConnection);
  $("btn-fold").addEventListener("click", togglePermFold);
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
  $("set-permanent").addEventListener("click", async () => {
    const secret = $("permanent-input").value.trim();
    if (!secret) {
      showToast("请输入永久密码", "error");
      $("permanent-input").focus();
      return;
    }
    const next = await runAction(() => call("set_permanent", { secret }), "永久密码已启用");
    if (next) $("permanent-input").value = "";
  });
  $("clear-permanent").addEventListener("click", () =>
    runAction(() => call("clear_permanent"), "永久密码已停用"));
}

async function boot() {
  bindEvents();
  render(fallbackState);
  renderIcons();
  window.setInterval(renderCountdown, 1000);
  renderCountdown();
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
