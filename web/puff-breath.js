/*
 * puff-breath.js — the game-side half of the Puff breath bridge.
 *
 * The Android app runs SmartSpectra and calls window.PuffBreath.receive({...})
 * several times a second. On the normal website nothing ever calls it, so
 * active() stays false and Puff keeps its tap controls.
 *
 * Message from the app:
 *   { trace: [numbers], stable: bool, rate: number|null, pulse: number|null }
 *   trace  - new chest-movement samples (arbitrary units, about 30 per second)
 *   stable - SDK says the breathing signal is reliable (takes ~30 s at first)
 *   rate   - breaths per minute (30-second average), only sent when stable
 *   pulse  - beats per minute, only sent when stable
 */
(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  if (root) root.PuffBreath = api.createBreathController();
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';

  var DEFAULTS = {
    now: function () { return Date.now(); },
    windowMs: 10000, // scale the waveform against the last 10 s of breathing
    warmupMs: 3000,  // need roughly one full breath before steering
    staleMs: 2000,   // no samples for 2 s: hand control back to taps
    smoothing: 0.3,  // weight of each new sample in the running average
    maxBatch: 120,   // most samples one message may add (bounds the work)
    maxSamples: 1200 // hard cap on stored samples
  };

  // Accepted input ranges; anything outside is treated as bad data.
  var RATE_MIN = 5, RATE_MAX = 40;     // SDK's valid breathing range
  var PULSE_MIN = 30, PULSE_MAX = 220;

  // Breathing rate -> obstacle speed. Slow, calm breathing slows the game.
  var SLOW_RATE = 6, SLOW_FACTOR = 0.7;
  var FAST_RATE = 20, FAST_FACTOR = 1.2;

  function isNumber(x) { return typeof x === 'number' && isFinite(x); }
  function clamp(x, lo, hi) { return Math.min(hi, Math.max(lo, x)); }
  function inRange(x, lo, hi) { return isNumber(x) && x >= lo && x <= hi; }

  function createBreathController(options) {
    var opt = {};
    var key;
    for (key in DEFAULTS) opt[key] = DEFAULTS[key];
    if (options) for (key in options) opt[key] = options[key];

    var samples = [];      // { t: arrival ms, v: smoothed value }, oldest first
    var smoothed = null;
    var lastSampleAt = -Infinity;
    var stable = false;
    var lastRate = null;
    var lastPulse = null;
    var invert = false;
    var inApp = false;

    function fresh() { return opt.now() - lastSampleAt <= opt.staleMs; }

    function prune() {
      var cutoff = opt.now() - opt.windowMs;
      while (samples.length && samples[0].t < cutoff) samples.shift();
      if (samples.length > opt.maxSamples) samples.splice(0, samples.length - opt.maxSamples);
    }

    function range() {
      var lo = Infinity, hi = -Infinity;
      for (var i = 0; i < samples.length; i++) {
        if (samples[i].v < lo) lo = samples[i].v;
        if (samples[i].v > hi) hi = samples[i].v;
      }
      return { lo: lo, hi: hi };
    }

    function warmedUp() {
      return samples.length > 1 &&
        samples[samples.length - 1].t - samples[0].t >= opt.warmupMs;
    }

    function active() {
      if (!stable || !fresh() || !warmedUp()) return false;
      var r = range();
      return r.hi > r.lo;
    }

    function receive(msg) {
      if (!msg || typeof msg !== 'object') return;
      inApp = true;

      if (inRange(msg.rate, RATE_MIN, RATE_MAX)) lastRate = msg.rate;
      if (inRange(msg.pulse, PULSE_MIN, PULSE_MAX)) lastPulse = msg.pulse;

      if (!Array.isArray(msg.trace)) return;
      var batch = msg.trace.slice(-opt.maxBatch);
      var now = opt.now();
      var added = 0;
      for (var i = 0; i < batch.length; i++) {
        var v = batch[i];
        if (!isNumber(v)) continue;
        smoothed = smoothed === null ? v : smoothed + opt.smoothing * (v - smoothed);
        samples.push({ t: now, v: smoothed });
        added++;
      }
      if (added) {
        lastSampleAt = now;
        stable = msg.stable === true;
        prune();
      }
    }

    // Breath level from 0 (fully out) to 1 (fully in), or null when inactive.
    function level() {
      if (!active()) return null;
      prune();
      var r = range();
      var lv = (samples[samples.length - 1].v - r.lo) / (r.hi - r.lo);
      lv = clamp(lv, 0, 1);
      return invert ? 1 - lv : lv;
    }

    // Where the fish should be: `top` on a full breath in, `bottom` on a full breath out.
    function targetY(top, bottom) {
      var lv = level();
      return lv === null ? null : bottom + (top - bottom) * lv;
    }

    // Multiplier for obstacle speed; 1 when breath control is off.
    function speedFactor() {
      if (!active() || lastRate === null) return 1;
      var f = (lastRate - SLOW_RATE) / (FAST_RATE - SLOW_RATE);
      return SLOW_FACTOR + clamp(f, 0, 1) * (FAST_FACTOR - SLOW_FACTOR);
    }

    function reset() {
      samples = [];
      smoothed = null;
      lastSampleAt = -Infinity;
      stable = false;
      lastRate = null;
      lastPulse = null;
    }

    // Lets the page hide things that don't belong in the app (e.g. the ad slot)
    // with CSS:  .puff-in-app .ad-slot { display: none; }
    function setInApp(on) {
      inApp = !!on;
      if (typeof document !== 'undefined' && document.documentElement) {
        document.documentElement.classList.toggle('puff-in-app', inApp);
      }
    }

    return {
      receive: receive,
      active: active,
      calibrating: function () { return fresh() && !active(); },
      level: level,
      targetY: targetY,
      speedFactor: speedFactor,
      rate: function () { return lastRate; },
      pulse: function () { return lastPulse; },
      sampleCount: function () { return samples.length; },
      setInvert: function (on) { invert = !!on; },
      setInApp: setInApp,
      isInApp: function () { return inApp; },
      reset: reset
    };
  }

  return { createBreathController: createBreathController };
});
