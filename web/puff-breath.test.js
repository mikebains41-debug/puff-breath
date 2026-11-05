// Tests for puff-breath.js. Run with:  node --test web/
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createBreathController } = require('./puff-breath.js');

function fakeClock(start = 1000) {
  let t = start;
  return { now: () => t, advance: (ms) => { t += ms; } };
}

// A breathing waveform sampled at 30 Hz, like the SDK's chest trace.
function wave(seconds, breathsPerMin = 12, hz = 30) {
  const out = [];
  for (let i = 0; i < seconds * hz; i++) {
    out.push(Math.sin(2 * Math.PI * (breathsPerMin / 60) * (i / hz)));
  }
  return out;
}

// Deliver samples the way the app does: 3 samples every 100 ms.
function feed(ctrl, clock, values, extra = {}) {
  for (let i = 0; i < values.length; i += 3) {
    ctrl.receive({ trace: values.slice(i, i + 3), stable: true, rate: null, ...extra });
    clock.advance(100);
  }
}

function warmedUp(extra = {}) {
  const clock = fakeClock();
  const ctrl = createBreathController({ now: clock.now });
  feed(ctrl, clock, wave(6), extra);
  return { ctrl, clock };
}

test('is inactive before any data arrives', () => {
  const ctrl = createBreathController({ now: fakeClock().now });
  assert.equal(ctrl.active(), false);
  assert.equal(ctrl.level(), null);
  assert.equal(ctrl.speedFactor(), 1);
});

test('stays in calibration while the signal is not yet stable', () => {
  const clock = fakeClock();
  const ctrl = createBreathController({ now: clock.now });
  feed(ctrl, clock, wave(6), { stable: false });
  assert.equal(ctrl.active(), false);
  assert.equal(ctrl.calibrating(), true);
});

test('becomes active after a few seconds of stable signal', () => {
  const { ctrl } = warmedUp();
  assert.equal(ctrl.active(), true);
  assert.equal(ctrl.calibrating(), false);
});

test('level is near 1 at the top of an inhale and near 0 at the bottom', () => {
  const { ctrl, clock } = warmedUp();
  feed(ctrl, clock, new Array(30).fill(1));
  assert.ok(ctrl.level() > 0.9, `expected high level, got ${ctrl.level()}`);
  feed(ctrl, clock, new Array(30).fill(-1));
  assert.ok(ctrl.level() < 0.1, `expected low level, got ${ctrl.level()}`);
});

test('invert flips the level for cameras whose trace runs the other way', () => {
  const { ctrl, clock } = warmedUp();
  ctrl.setInvert(true);
  feed(ctrl, clock, new Array(30).fill(1));
  assert.ok(ctrl.level() < 0.1, `expected inverted low level, got ${ctrl.level()}`);
});

test('goes inactive when data stops arriving, handing control back to taps', () => {
  const { ctrl, clock } = warmedUp({ rate: 6 });
  clock.advance(2500);
  assert.equal(ctrl.active(), false);
  assert.equal(ctrl.speedFactor(), 1);
});

test('targetY maps a full inhale to the top and a full exhale to the bottom', () => {
  const { ctrl, clock } = warmedUp();
  feed(ctrl, clock, new Array(30).fill(1));
  assert.ok(Math.abs(ctrl.targetY(100, 500) - 100) < 40);
  feed(ctrl, clock, new Array(30).fill(-1));
  assert.ok(Math.abs(ctrl.targetY(100, 500) - 500) < 40);
});

test('targetY is null when breath control is not active', () => {
  const ctrl = createBreathController({ now: fakeClock().now });
  assert.equal(ctrl.targetY(100, 500), null);
});

test('speedFactor slows obstacles for slow breathing and speeds them up for fast breathing', () => {
  assert.ok(Math.abs(warmedUp({ rate: 6 }).ctrl.speedFactor() - 0.7) < 0.01);
  assert.ok(Math.abs(warmedUp({ rate: 20 }).ctrl.speedFactor() - 1.2) < 0.01);
  assert.ok(Math.abs(warmedUp({ rate: 35 }).ctrl.speedFactor() - 1.2) < 0.01);
  assert.equal(warmedUp().ctrl.speedFactor(), 1);
});

test('keeps the last valid rate when a later message has none', () => {
  const ctrl = createBreathController({ now: fakeClock().now });
  ctrl.receive({ trace: [0.1], stable: true, rate: 8 });
  ctrl.receive({ trace: [0.2], stable: true, rate: null });
  assert.equal(ctrl.rate(), 8);
});

test('ignores malformed messages without throwing', () => {
  const ctrl = createBreathController({ now: fakeClock().now });
  assert.doesNotThrow(() => {
    ctrl.receive(null);
    ctrl.receive('nope');
    ctrl.receive({ trace: 'x' });
    ctrl.receive({ trace: [NaN, Infinity, 'a', null] });
    ctrl.receive({ rate: 1e9, pulse: -5 });
  });
  assert.equal(ctrl.sampleCount(), 0);
  assert.equal(ctrl.rate(), null);
  assert.equal(ctrl.pulse(), null);
});

test('caps how many samples a single message can add', () => {
  const ctrl = createBreathController({ now: fakeClock().now });
  ctrl.receive({ trace: new Array(10000).fill(0.5), stable: true });
  assert.ok(ctrl.sampleCount() <= 120, `got ${ctrl.sampleCount()} samples`);
});

test('reset clears signal, rate and pulse', () => {
  const { ctrl } = warmedUp({ rate: 10, pulse: 70 });
  ctrl.reset();
  assert.equal(ctrl.active(), false);
  assert.equal(ctrl.sampleCount(), 0);
  assert.equal(ctrl.rate(), null);
  assert.equal(ctrl.pulse(), null);
});
