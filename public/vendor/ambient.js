/* Three.js — the drifting field behind the interface.
   Loaded as a module and imported lazily, so the 670KB library is never on the
   path to first paint and never fetched at all for someone who will not see it.
   The CSS gradients stay underneath as the fallback. */
const REDUCED = matchMedia('(prefers-reduced-motion: reduce)').matches;

const OFF_KEY = 'recordroom.ambient.off';
function canRun() {
  if (REDUCED) return false;
  // A previous visit on this device measured it as too slow to be worth it.
  try { if (sessionStorage.getItem(OFF_KEY) === '1') return false; } catch (e) {}
  if (navigator.deviceMemory && navigator.deviceMemory < 2) return false;
  try {
    const c = document.createElement('canvas');
    return !!(c.getContext('webgl2') || c.getContext('webgl'));
  } catch (e) { return false; }
}

if (canRun()) {
  import('./three.module.min.js').then(THREE => start(THREE)).catch(() => {});
}

function start(THREE) {
  const host = document.createElement('div');
  host.className = 'ambient3d';
  host.setAttribute('aria-hidden', 'true');
  document.body.insertBefore(host, document.body.firstChild);

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(55, innerWidth / innerHeight, 1, 1400);
  camera.position.z = 420;

  let renderer;
  try {
    renderer = new THREE.WebGLRenderer({ alpha: true, antialias: false, powerPreference: 'low-power' });
  } catch (e) { host.remove(); return; }
  renderer.setPixelRatio(Math.min(devicePixelRatio, 1.5));
  renderer.setSize(innerWidth, innerHeight);
  host.appendChild(renderer.domElement);

  // Particle count follows the viewport, so a phone is not asked to draw a
  // desktop's worth of points.
  const area = innerWidth * innerHeight;
  const COUNT = Math.max(260, Math.min(1100, Math.round(area / 1700)));
  const pos = new Float32Array(COUNT * 3);
  const col = new Float32Array(COUNT * 3);
  const drift = new Float32Array(COUNT);
  // trophy gold, bronze and a warm highlight — the page's accent tokens
  const gold = new THREE.Color('#F0B63C'), bronze = new THREE.Color('#C98A2E'), warm = new THREE.Color('#FFD27A');

  for (let i = 0; i < COUNT; i++) {
    pos[i * 3] = (Math.random() - 0.5) * 1500;
    pos[i * 3 + 1] = (Math.random() - 0.5) * 900;
    pos[i * 3 + 2] = (Math.random() - 0.5) * 700 - 120;
    const t = Math.random();
    const c = t < 0.55 ? gold : t < 0.88 ? bronze : warm;
    col[i * 3] = c.r; col[i * 3 + 1] = c.g; col[i * 3 + 2] = c.b;
    drift[i] = 0.18 + Math.random() * 0.5;
  }
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  geo.setAttribute('color', new THREE.BufferAttribute(col, 3));

  const points = new THREE.Points(geo, new THREE.PointsMaterial({
    size: 3.6, vertexColors: true, transparent: true, opacity: 0.5,
    depthWrite: false, blending: THREE.AdditiveBlending, sizeAttenuation: true
  }));
  scene.add(points);

  let px = 0, py = 0, tx = 0, ty = 0;
  addEventListener('pointermove', e => {
    tx = (e.clientX / innerWidth - 0.5) * 34;
    ty = (e.clientY / innerHeight - 0.5) * -22;
  }, { passive: true });

  addEventListener('resize', () => {
    camera.aspect = innerWidth / innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(innerWidth, innerHeight);
  });

  const p = geo.attributes.position.array;
  let raf = 0, last = performance.now();

  /* Hardware here is unknown and unmeasurable from a build machine, so the
     field measures itself: if the first couple of seconds cannot hold a
     reasonable frame rate it takes itself down and leaves the CSS gradients,
     and does not try again this session. */
  let probeFrames = 0, probeStart = performance.now(), probing = true;
  function probe(now) {
    if (!probing) return;
    probeFrames++;
    const elapsed = now - probeStart;
    if (elapsed < 1800) return;
    probing = false;
    const fps = probeFrames / (elapsed / 1000);
    if (fps < 28) {
      try { sessionStorage.setItem(OFF_KEY, '1'); } catch (e) {}
      teardown();
    }
  }

  function frame(now) {
    raf = requestAnimationFrame(frame);
    probe(now);
    const dt = Math.min(64, now - last); last = now;

    for (let i = 0; i < COUNT; i++) {
      p[i * 3 + 1] += drift[i] * dt * 0.014;
      if (p[i * 3 + 1] > 460) p[i * 3 + 1] = -460;
    }
    geo.attributes.position.needsUpdate = true;

    points.rotation.y += dt * 0.000018;
    px += (tx - px) * 0.035; py += (ty - py) * 0.035;
    camera.position.x = px; camera.position.y = py;
    camera.lookAt(0, 0, 0);
    renderer.render(scene, camera);
  }
  raf = requestAnimationFrame(frame);

  function stop() { if (raf) { cancelAnimationFrame(raf); raf = 0; } }
  function teardown() {
    stop();
    geo.dispose(); points.material.dispose();
    renderer.dispose();
    host.remove();
    window.__ambient = { disabled: true, reason: 'frame rate too low' };
  }
  function go() { if (!raf) { last = performance.now(); raf = requestAnimationFrame(frame); } }
  document.addEventListener('visibilitychange', () => (document.hidden ? stop() : go()));
  renderer.domElement.addEventListener('webglcontextlost', e => { e.preventDefault(); stop(); });

  window.__ambient = { stop, go, teardown, count: COUNT };
}
