"use strict";

// ---------- SAST clock ----------

const sastClockEl = document.getElementById("sastClock");

function renderSastClock() {
  const now = new Date();
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: "Africa/Johannesburg",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).formatToParts(now);
  const get = (type) => parts.find((p) => p.type === type)?.value ?? "--";
  sastClockEl.textContent = `SAST ${get("hour")}:${get("minute")}:${get("second")}`;
}
setInterval(renderSastClock, 1000);
renderSastClock();

// ---------- Wake lock (keep screen on) ----------

let wakeLock = null;
async function requestWakeLock() {
  try {
    if ("wakeLock" in navigator) {
      wakeLock = await navigator.wakeLock.request("screen");
    }
  } catch (_) {
    // Not fatal — just means the screen may dim.
  }
}
requestWakeLock();
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible") requestWakeLock();
});

// ---------- Beep (Web Audio) ----------

let audioCtx = null;
function ensureAudioContext() {
  if (!audioCtx) {
    audioCtx = new (window.AudioContext || window.webkitAudioContext)();
  }
  if (audioCtx.state === "suspended") audioCtx.resume();
  return audioCtx;
}

function beep(durationMs, frequency = 880) {
  const ctx = ensureAudioContext();
  const osc = ctx.createOscillator();
  const gain = ctx.createGain();
  osc.frequency.value = frequency;
  osc.connect(gain);
  gain.connect(ctx.destination);
  gain.gain.setValueAtTime(0.2, ctx.currentTime);
  osc.start();
  osc.stop(ctx.currentTime + durationMs / 1000);
}

// ---------- Camera + focus readout ----------

const cameraPreview = document.getElementById("cameraPreview");
const focusReadout = document.getElementById("focusReadout");
const permissionNote = document.getElementById("permissionNote");

async function openCamera() {
  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: "environment" },
      audio: false,
    });
    cameraPreview.srcObject = stream;

    const [track] = stream.getVideoTracks();
    pollFocusCapabilities(track);
  } catch (err) {
    permissionNote.textContent =
      "Camera unavailable: " + (err && err.message ? err.message : "permission denied");
  }
}

function pollFocusCapabilities(track) {
  if (!("ImageCapture" in window)) {
    focusReadout.textContent = "Focus readout: not supported by this browser";
    return;
  }
  let imageCapture;
  try {
    imageCapture = new ImageCapture(track);
  } catch (_) {
    focusReadout.textContent = "Focus readout: not supported by this browser";
    return;
  }

  const poll = () => {
    imageCapture
      .getPhotoCapabilities()
      .then((caps) => {
        const settings = track.getSettings ? track.getSettings() : {};
        const focusDistance = settings.focusDistance;
        if (typeof focusDistance === "number") {
          focusReadout.textContent = `Focus distance: ${focusDistance.toFixed(2)}`;
        } else if (caps && caps.focusDistance) {
          focusReadout.textContent = `Focus range: ${caps.focusDistance.min}–${caps.focusDistance.max}`;
        } else {
          focusReadout.textContent = "Focus: continuous autofocus (no distance value exposed)";
        }
      })
      .catch(() => {
        focusReadout.textContent = "Focus: continuous autofocus (no distance value exposed)";
      });
  };

  poll();
  setInterval(poll, 2000);
}

openCamera();

// ---------- Timer / Stopwatch ----------

const MODE = { TIMER: "timer", STOPWATCH: "stopwatch" };
let mode = MODE.TIMER;
let running = false;
let totalMillis = 0;
let elapsedMillis = 0;
let lastWholeSecond = -1;
let tickIntervalId = null;

const tabTimerBtn = document.getElementById("tabTimer");
const tabStopwatchBtn = document.getElementById("tabStopwatch");
const mainTimeEl = document.getElementById("mainTime");
const minutesRow = document.getElementById("minutesRow");
const minutesInput = document.getElementById("minutesInput");
const startPauseBtn = document.getElementById("startPauseBtn");
const resetBtn = document.getElementById("resetBtn");

function switchMode(newMode) {
  if (running) return;
  mode = newMode;
  tabTimerBtn.classList.toggle("active", mode === MODE.TIMER);
  tabStopwatchBtn.classList.toggle("active", mode === MODE.STOPWATCH);
  minutesRow.style.display = mode === MODE.TIMER ? "flex" : "none";
  resetTimer();
}

function toggleStartPause() {
  ensureAudioContext();
  if (!running) {
    if (mode === MODE.TIMER && totalMillis === 0) {
      const minutes = parseInt(minutesInput.value, 10);
      if (!minutes || minutes <= 0) return;
      totalMillis = minutes * 60_000;
      elapsedMillis = 0;
    }
    running = true;
    startPauseBtn.textContent = "Pause";
    lastWholeSecond = -1;
    startTicking();
  } else {
    running = false;
    startPauseBtn.textContent = "Start";
    stopTicking();
  }
}

function resetTimer() {
  running = false;
  elapsedMillis = 0;
  totalMillis = 0;
  startPauseBtn.textContent = "Start";
  stopTicking();
  renderTime();
}

function startTicking() {
  stopTicking();
  tickIntervalId = setInterval(tick, 200);
}

function stopTicking() {
  if (tickIntervalId !== null) {
    clearInterval(tickIntervalId);
    tickIntervalId = null;
  }
}

function tick() {
  if (!running) return;
  elapsedMillis += 200;

  const remainingMillis = totalMillis - elapsedMillis;
  if (mode === MODE.TIMER && remainingMillis <= 0) {
    running = false;
    startPauseBtn.textContent = "Start";
    stopTicking();
    beep(800, 660);
    mainTimeEl.textContent = "00:00";
    return;
  }

  const currentWholeSecond =
    mode === MODE.TIMER ? Math.floor(remainingMillis / 1000) : Math.floor(elapsedMillis / 1000);

  if (currentWholeSecond !== lastWholeSecond) {
    lastWholeSecond = currentWholeSecond;
    onWholeSecondPassed(remainingMillis);
  }

  renderTime();
}

/** Beep every full minute; every second during the timer's final 20 seconds. */
function onWholeSecondPassed(remainingMillis) {
  const inFinalCountdown = mode === MODE.TIMER && remainingMillis > 0 && remainingMillis <= 20_000;
  if (inFinalCountdown) {
    beep(150, 880);
    return;
  }
  const secondsElapsedTotal =
    mode === MODE.TIMER ? Math.floor((totalMillis - remainingMillis) / 1000) : Math.floor(elapsedMillis / 1000);
  if (secondsElapsedTotal > 0 && secondsElapsedTotal % 60 === 0) {
    beep(150, 880);
  }
}

function renderTime() {
  const millisToShow = mode === MODE.TIMER ? Math.max(0, totalMillis - elapsedMillis) : elapsedMillis;
  const totalSeconds = Math.floor(millisToShow / 1000);
  const m = Math.floor(totalSeconds / 60);
  const s = totalSeconds % 60;
  mainTimeEl.textContent = `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

tabTimerBtn.addEventListener("click", () => switchMode(MODE.TIMER));
tabStopwatchBtn.addEventListener("click", () => switchMode(MODE.STOPWATCH));
startPauseBtn.addEventListener("click", toggleStartPause);
resetBtn.addEventListener("click", resetTimer);

renderTime();

// ---------- Fixed daily alarms ----------
// 5:30, 9:00, 12:00, 15:00, 18:00, 21:00 (device local time).
// A page tab has no true background execution, so this only fires while the
// tab is open; Notification permission lets it also surface a system toast.

const ALARM_TIMES = [
  [5, 30],
  [9, 0],
  [12, 0],
  [15, 0],
  [18, 0],
  [21, 0],
];

const alarmOverlay = document.getElementById("alarmOverlay");
const alarmLabel = document.getElementById("alarmLabel");
const dismissAlarmBtn = document.getElementById("dismissAlarmBtn");

let alarmAudioIntervalId = null;
let firedThisMinuteKey = null;

function requestNotificationPermission() {
  if ("Notification" in window && Notification.permission === "default") {
    Notification.requestPermission();
  }
}
requestNotificationPermission();

function checkAlarms() {
  const now = new Date();
  const hour = now.getHours();
  const minute = now.getMinutes();
  const key = `${now.getFullYear()}-${now.getMonth()}-${now.getDate()}-${hour}-${minute}`;

  const isAlarmTime = ALARM_TIMES.some(([h, m]) => h === hour && m === minute);
  if (isAlarmTime && key !== firedThisMinuteKey) {
    firedThisMinuteKey = key;
    triggerAlarm(hour, minute);
  }
}
setInterval(checkAlarms, 1000);

function triggerAlarm(hour, minute) {
  const label = `${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}`;
  alarmLabel.textContent = `Alarm ${label}`;
  alarmOverlay.classList.add("show");

  ensureAudioContext();
  alarmAudioIntervalId = setInterval(() => beep(400, 700), 700);

  if ("Notification" in window && Notification.permission === "granted") {
    new Notification(`Focus Timer alarm: ${label}`);
  }
}

function dismissAlarm() {
  alarmOverlay.classList.remove("show");
  if (alarmAudioIntervalId !== null) {
    clearInterval(alarmAudioIntervalId);
    alarmAudioIntervalId = null;
  }
}
dismissAlarmBtn.addEventListener("click", dismissAlarm);
