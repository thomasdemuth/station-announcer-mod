"use strict";
/*
 * Dispatch board frontend for the Station Announcer MTR addon.
 * Vanilla JS + canvas, no build step. Talks to the endpoints documented in
 * PROGRESS.md ("Dispatch Agent 1"): /dispatch/api/ping, /dispatch/api/network,
 * /dispatch/api/stream (SSE). Schema version 1.
 *
 * ?demo=1 renders a small synthetic network with moving trains (no server
 * needed) — used for UI testing.
 */

const SCHEMA_VERSION = 1;
const API = "api";
const DEMO = new URLSearchParams(location.search).get("demo") === "1";

/* How often the analytics aggregate is polled, and ONLY while the Analytics view or the
 * station heat mode is actually visible. The server recomputes it at most every
 * analytics.aggregateSeconds (default 30 s), so polling faster buys nothing. */
const ANALYTICS_POLL_MS = 15000;

/* ---------- state ---------- */

const state = {
	dims: [],
	dim: 0,
	updateMillis: 333,
	network: null,           // raw payload
	rails: new Map(),        // id -> {points (world), cum (cumulative lengths), ...rail}
	stations: [],
	platforms: new Map(),
	routes: new Map(),
	vehicles: new Map(),     // id -> {data, samples:[{t,x,z,railT,rail}], route, consist, disp}
	signals: new Map(),      // railId -> {occupied:[], reserved:[]}
	signalGroups: [],        // contiguous same-color signal blocks -> one dot each
	modes: new Set(),        // enabled transport modes (empty until network load)
	emaInterval: 0,          // measured ms between stream frames
	lastServerTime: 0,
	lastFrameAt: 0,
	selected: null,
	selectedStation: null,  // station id shown in the side panel (mutually exclusive with selected)
	follow: false,
	es: null,
	holds: new Set(),        // platform ids currently holding a train (SSE)
	obstructed: new Set(),   // vehicle ids with door obstructions (SSE)
	alerts: [],              // newest last; capped at 200
	alertsOpen: false,
	alertsUnread: 0,
	boardFilter: null,       // line key (route name before "||"), null = all lines
	stringline: {
		view: false,
		groups: [],          // [{key, display, number, color, routeIds:[]}]
		groupKey: null,
		axis: null,          // stringline endpoint axis payload (all routes)
		deps: [],            // departure rows for the selected group
		windowMin: 60,
		timer: null,
		fetching: false,
		hover: null,         // {trace, segIndex} under the cursor
		mouse: null,         // [x, y] canvas-local
		traces: [],          // rebuilt each draw; hit-testing reads it
		mode: "line",        // "line" | "segment" (interlined-trunk view)
		segStations: [],      // station ids picked on the axis in segment mode
		built: null,          // cached time-space trace geometry {sig, stations, platY, traces}
		layout: null,         // last draw's axis layout (label hit-testing)
		refetchWanted: false,
		show: { dwell: false, run: false, headway: false, travel: false },
		endT: null,           // scrubbed end-of-window instant; null = follow "now"
	},
	lastEventAt: 0,
	view: { x: 0, z: 0, scale: 1 },  // world center + px per block
	layers: { speed: true, signals: true, stations: true, trainLabels: true },
	sort: { k: "route", asc: true },
	networkBox: null,
	analytics: {
		data: null,          // last /api/analytics payload (envelope unwrapped)
		view: false,         // Analytics overlay visible
		heat: "off",         // off | dwell | headway — station heat on the track map
		timer: null,         // poll interval id; null whenever nothing needs the data
		fetching: false,
		stationById: new Map(),
	},
};

/* ---------- speed colour ramp ---------- */

const SPEED_BUCKETS = [
	[20,  "#3d5a80"], [40,  "#3a7ca5"], [60,  "#3fc1c9"], [80,  "#46c46e"],
	[120, "#b7cf4f"], [160, "#f5b942"], [200, "#f07b3f"], [999, "#e5484d"],
];
function speedColor(kmh) {
	for (const [max, c] of SPEED_BUCKETS) if (kmh <= max) return c;
	return SPEED_BUCKETS[SPEED_BUCKETS.length - 1][1];
}

/* ---------- helpers ---------- */

const $ = (id) => document.getElementById(id);
const firstLang = (s) => (s || "").split("|")[0];
/** Canvas text/marker scale for big screens — CSS media queries handle the DOM side. */
const uiScale = () => Math.min(1.45, Math.max(1, window.innerWidth / 1600));
const colorHex = (c) => "#" + (c & 0xFFFFFF).toString(16).padStart(6, "0");
const now = () => Date.now();

function fmtDev(devMs) {
	if (devMs === undefined || devMs === null) return ["--", ""];
	const s = Math.round(devMs / 1000);
	if (s > 15) return ["+" + s + "s", "dev-late"];
	if (s < -15) return [s + "s", "dev-early"];
	return [(s >= 0 ? "+" : "") + s + "s", "dev-ontime"];
}

/* ---------- boot ---------- */

async function boot() {
	initUi();
	if (DEMO) { bootDemo(); return; }
	try {
		const ping = await (await fetch(`${API}/ping`)).json();
		if (ping.schemaVersion !== SCHEMA_VERSION) {
			showBanner(`Schema mismatch: server v${ping.schemaVersion}, UI v${SCHEMA_VERSION} — update the mod or hard-refresh this page`);
		}
		state.dims = ping.dimensions || [];
		state.updateMillis = ping.updateMillis || 333;
		fillDimSelect();
		await loadDimension(0);
	} catch (e) {
		setStatus("offline");
		showBanner("Dispatch backend unreachable — is the server (and its MTR webserver) running?");
		setTimeout(boot, 5000);
		return;
	}
	setInterval(refetchNetwork, 60000);
	fetchAlertBacklog();
	requestAnimationFrame(frame);
	setInterval(renderBoard, 1000);
}

async function fetchAlertBacklog() {
	try {
		const body = await (await fetch(`${API}/alerts`)).json();
		const alerts = (body.data || body).alerts || [];
		const seen = new Set(state.alerts.map((a) => a.seq));
		for (const a of alerts) if (!seen.has(a.seq)) addAlert(a, true);
		renderAlerts();
	} catch (e) { /* transient */ }
}

function fillDimSelect() {
	const sel = $("dimSelect");
	sel.innerHTML = "";
	state.dims.forEach((d, i) => {
		const o = document.createElement("option");
		o.value = i;
		o.textContent = d.replace("minecraft/", "").replace("minecraft:", "");
		sel.appendChild(o);
	});
	sel.onchange = () => loadDimension(parseInt(sel.value, 10));
}

async function loadDimension(n) {
	state.dim = n;
	state.vehicles.clear();
	state.signals.clear();
	state.holds = new Set();
	state.obstructed = new Set();
	state.selectedStation = null;
	state.stringline.axis = null;
	state.stringline.deps = [];
	state.stringline.groups = [];
	state.stringline.groupKey = null;
	if (state.stringline.view) fetchStringline();
	setAnalyticsData(null);
	if (state.analytics.timer) fetchAnalytics();   // different dimension → refetch now
	state.emaInterval = 0;
	state.lastServerTime = 0;
	state.selected = null;
	updateDetail();
	if (state.es) { state.es.close(); state.es = null; }

	const body = await (await fetch(`${API}/network?dimension=${n}`)).json();
	applyNetwork(body.data || body);
	fitView();
	openStream();
}

async function refetchNetwork() {
	if (DEMO || document.hidden) return;
	try {
		const body = await (await fetch(`${API}/network?dimension=${state.dim}`)).json();
		applyNetwork(body.data || body);
	} catch (e) { /* transient — stream status covers visibility */ }
}

function applyNetwork(net) {
	state.network = net;
	state.rails.clear();
	state.platforms.clear();
	state.routes.clear();
	(net.routes || []).forEach((r) => state.routes.set(r.id, r));
	(net.platforms || []).forEach((p) => state.platforms.set(p.id, p));
	state.stations = net.stations || [];
	let minX = 1e18, minZ = 1e18, maxX = -1e18, maxZ = -1e18;
	(net.rails || []).forEach((r) => {
		const cum = [0];
		for (let i = 1; i < r.points.length; i++) {
			const dx = r.points[i][0] - r.points[i - 1][0];
			const dz = r.points[i][2] - r.points[i - 1][2];
			cum.push(cum[i - 1] + Math.hypot(dx, dz));
		}
		state.rails.set(r.id, { ...r, cum });
		r.points.forEach(([x, , z]) => {
			minX = Math.min(minX, x); maxX = Math.max(maxX, x);
			minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
		});
	});
	state.networkBox = maxX < minX ? null : { minX, minZ, maxX, maxZ };
	buildSignalGroups();
	buildModeToggles();
	invalidateStatic();
}

/**
 * Merge contiguous rails carrying the SAME signal color set into one "block"
 * so a signal block renders as a single dot instead of a dot per rail.
 */
function buildSignalGroups() {
	const parent = new Map();
	const find = (a) => { while (parent.get(a) !== a) { parent.set(a, parent.get(parent.get(a))); a = parent.get(a); } return a; };
	const union = (a, b) => parent.set(find(a), find(b));
	const sigRails = [];
	for (const r of state.rails.values()) {
		if (r.signalColors && r.signalColors.length) { sigRails.push(r); parent.set(r.id, r.id); }
	}
	const byEndpoint = new Map(); // "colors|x,z" -> first rail seen there
	for (const r of sigRails) {
		const ck = r.signalColors.slice().sort((a, b) => a - b).join(",");
		for (const p of [r.points[0], r.points[r.points.length - 1]]) {
			const key = ck + "|" + Math.round(p[0]) + "," + Math.round(p[2]);
			if (byEndpoint.has(key)) union(r.id, byEndpoint.get(key));
			else byEndpoint.set(key, r.id);
		}
	}
	const grouped = new Map();
	for (const r of sigRails) {
		const root = find(r.id);
		if (!grouped.has(root)) grouped.set(root, []);
		grouped.get(root).push(r);
	}
	state.signalGroups = [];
	for (const members of grouped.values()) {
		let sx = 0, sz = 0;
		for (const r of members) {
			const m = railPoint(r.id, 0.5) || [r.points[0][0], r.points[0][2]];
			sx += m[0]; sz += m[1];
		}
		state.signalGroups.push({
			x: sx / members.length, z: sz / members.length,
			memberIds: members.map((r) => r.id), mode: members[0].mode,
		});
	}
}

/** Transit-mode filter checkboxes; hidden when the network has a single mode. */
function buildModeToggles() {
	const modes = [...new Set([...state.rails.values()].map((r) => r.mode))].sort();
	if (state.modes.size === 0 || [...state.modes].some((m) => !modes.includes(m))) {
		state.modes = new Set(modes);
	}
	const el = $("modeToggles");
	el.innerHTML = "";
	el.style.display = modes.length > 1 ? "" : "none";
	const labels = { train: "Trains", boat: "Boats", cable_car: "Cable cars", airplane: "Planes" };
	for (const m of modes) {
		const label = document.createElement("label");
		const cb = document.createElement("input");
		cb.type = "checkbox";
		cb.checked = state.modes.has(m);
		cb.onchange = () => {
			if (cb.checked) state.modes.add(m); else state.modes.delete(m);
			invalidateStatic();
			renderBoard();
		};
		label.appendChild(cb);
		label.appendChild(document.createTextNode(" " + (labels[m] || m)));
		el.appendChild(label);
	}
}

function modeEnabled(mode) {
	return !mode || state.modes.size === 0 || state.modes.has(mode);
}

/** A vehicle's transport mode, derived from the rail it is currently on. */
function vehicleMode(rec) {
	const rail = rec.data.rail ? state.rails.get(rec.data.rail) : null;
	return rail ? rail.mode : null;
}

/* ---------- SSE ---------- */

function openStream() {
	setStatus("connecting");
	const es = new EventSource(`${API}/stream?dimension=${state.dim}`);
	state.es = es;
	es.addEventListener("full", (ev) => { handleFrame(JSON.parse(ev.data), true); });
	es.addEventListener("delta", (ev) => { handleFrame(JSON.parse(ev.data), false); });
	es.addEventListener("error", (ev) => {
		if (ev.data) showBanner(JSON.parse(ev.data).error || "stream error");
	});
	es.onopen = () => hideBanner();
	es.onerror = () => setStatus("offline"); // EventSource retries on its own
}

function handleFrame(f, isFull) {
	if (f.schemaVersion !== SCHEMA_VERSION) { showBanner(`Schema mismatch (stream v${f.schemaVersion})`); return; }
	if (f.dimension !== state.dim) return;   // stale stream after a dim switch
	state.lastEventAt = now();
	setStatus("live");

	// Measure the stream's real cadence so interpolation delay matches reality.
	if (state.lastServerTime) {
		const d = f.serverTime - state.lastServerTime;
		if (d > 0 && d < 10000) {
			state.emaInterval = state.emaInterval ? state.emaInterval * 0.8 + d * 0.2 : d;
		}
	}
	state.lastServerTime = f.serverTime;

	if (isFull) {
		for (const id of [...state.vehicles.keys()])
			if (!f.vehicles.some((v) => v.id === id)) state.vehicles.delete(id);
		state.signals.clear();
	}
	(f.removed || []).forEach((id) => state.vehicles.delete(id));

	(f.vehicles || []).forEach((v) => {
		let rec = state.vehicles.get(v.id);
		if (!rec) rec = { samples: [], route: null, consist: null, data: {} };
		if (v.route) rec.route = v.route;
		if (v.consist) rec.consist = v.consist;
		rec.data = { ...rec.data, ...v };
		// Orientation-correct railT before storing: railT measures progress along the
		// PATH segment, but the rail's polyline has its own canonical direction — a
		// train traversing the rail against it would interpolate BACKWARDS along the
		// curve and then snap at the rail boundary ("glides the wrong way then
		// jumps"). If the mirrored parameter lands closer to the sample's true world
		// position, the polyline runs opposite to the path here: flip it.
		let railT = v.railT;
		if (v.rail && railT !== undefined && railT >= 0) {
			const straight = railPoint(v.rail, railT);
			const mirrored = railPoint(v.rail, 1 - railT);
			if (straight && mirrored) {
				const dS = Math.hypot(straight[0] - v.x, straight[1] - v.z);
				const dM = Math.hypot(mirrored[0] - v.x, mirrored[1] - v.z);
				if (dM + 0.5 < dS) railT = 1 - railT;
			}
		}
		rec.samples.push({ t: f.serverTime, x: v.x, z: v.z, rail: v.rail, railT });
		if (rec.samples.length > 4) rec.samples.shift();
		state.vehicles.set(v.id, rec);
	});

	(f.signals || []).forEach((s) => state.signals.set(s.rail, s));
	(f.signalsCleared || []).forEach((id) => state.signals.delete(id));

	// Absent on deltas = unchanged; full frames always carry both.
	if (f.holds) state.holds = new Set(f.holds);
	if (f.obstructed) state.obstructed = new Set(f.obstructed);
	(f.alerts || []).forEach(addAlert);

	if (state.selected && !state.vehicles.has(state.selected)) { state.selected = null; state.follow = false; }
	updateDetail();
}

/* ---------- interpolation ---------- */

function railPoint(rail, t) {
	const r = state.rails.get(rail);
	if (!r || r.points.length < 2) return null;
	const target = t * r.cum[r.cum.length - 1];
	let i = 1;
	while (i < r.cum.length - 1 && r.cum[i] < target) i++;
	const seg = r.cum[i] - r.cum[i - 1] || 1;
	const f = (target - r.cum[i - 1]) / seg;
	const a = r.points[i - 1], b = r.points[i];
	return [a[0] + (b[0] - a[0]) * f, a[2] + (b[2] - a[2]) * f];
}

function vehiclePos(rec, renderTime) {
	const s = rec.samples;
	if (s.length === 0) return null;
	if (s.length === 1) return { x: s[0].x, z: s[0].z };
	// Past the newest sample (stream hiccup): extrapolate briefly along the last
	// heading instead of freezing, capped so a stall can't run a train away.
	const last = s[s.length - 1], prev = s[s.length - 2];
	if (renderTime > last.t) {
		const dt = Math.min(renderTime - last.t, 1000);
		const span = last.t - prev.t || 1;
		return {
			x: last.x + (last.x - prev.x) / span * dt,
			z: last.z + (last.z - prev.z) / span * dt,
			hx: last.x - prev.x, hz: last.z - prev.z,
		};
	}
	let a = s[0], b = s[s.length - 1];
	for (let i = 1; i < s.length; i++) {
		if (s[i].t >= renderTime) { a = s[i - 1]; b = s[i]; break; }
		a = s[i - 1]; b = s[i];
	}
	const span = b.t - a.t || 1;
	const f = Math.max(0, Math.min(1, (renderTime - a.t) / span));
	// same rail on both samples → slide along the real curve (railT is already
	// orientation-corrected at ingestion); expose rail+param so the consist
	// renderer can lay cars along the same curve
	if (a.rail && a.rail === b.rail && a.railT !== undefined && b.railT !== undefined) {
		const t = a.railT + (b.railT - a.railT) * f;
		const p = railPoint(a.rail, t);
		if (p) {
			return { x: p[0], z: p[1], hx: b.x - a.x, hz: b.z - a.z,
				rail: a.rail, railT: t, paramDir: Math.sign(b.railT - a.railT) };
		}
	}
	return { x: a.x + (b.x - a.x) * f, z: a.z + (b.z - a.z) * f, hx: b.x - a.x, hz: b.z - a.z,
		rail: b.rail, railT: b.railT, paramDir: 0 };
}

/**
 * Point + tangent at an absolute distance along a rail's polyline. Distances past
 * either end extend linearly along the end tangent, so a consist can hang off the
 * rail the head is on without snapping.
 */
function railDistPoint(r, dist) {
	const L = r.cum[r.cum.length - 1] || 1;
	const pts = r.points;
	if (pts.length < 2) return null;
	let i, f;
	if (dist <= 0) { i = 1; f = dist / (r.cum[1] - r.cum[0] || 1); }
	else if (dist >= L) { i = pts.length - 1; f = 1 + (dist - L) / (r.cum[i] - r.cum[i - 1] || 1); }
	else {
		i = 1;
		while (i < r.cum.length - 1 && r.cum[i] < dist) i++;
		f = (dist - r.cum[i - 1]) / (r.cum[i] - r.cum[i - 1] || 1);
	}
	const a = pts[i - 1], b = pts[i];
	const dx = b[0] - a[0], dz = b[2] - a[2];
	return { x: a[0] + dx * f, z: a[2] + dz * f, tx: dx, tz: dz };
}

/* ---------- canvas ---------- */

const canvas = $("map");
const ctx = canvas.getContext("2d");
let staticCanvas = document.createElement("canvas");
let staticDirty = true;
function invalidateStatic() { staticDirty = true; }

function resize() {
	const dpr = window.devicePixelRatio || 1;
	const w = canvas.clientWidth, h = canvas.clientHeight;
	if (canvas.width !== w * dpr || canvas.height !== h * dpr) {
		canvas.width = w * dpr; canvas.height = h * dpr;
		staticCanvas.width = w * dpr; staticCanvas.height = h * dpr;
		invalidateStatic();
	}
	return dpr;
}

function worldToScreen(x, z) {
	const v = state.view;
	return [
		canvas.clientWidth / 2 + (x - v.x) * v.scale,
		canvas.clientHeight / 2 + (z - v.z) * v.scale,
	];
}

function fitView() {
	const b = state.networkBox;
	if (!b) return;
	const w = canvas.clientWidth || 800, h = canvas.clientHeight || 600;
	state.view.x = (b.minX + b.maxX) / 2;
	state.view.z = (b.minZ + b.maxZ) / 2;
	state.view.scale = Math.min(w / Math.max(40, b.maxX - b.minX + 80), h / Math.max(40, b.maxZ - b.minZ + 80));
	invalidateStatic();
}

function drawStatic(dpr) {
	const g = staticCanvas.getContext("2d");
	g.setTransform(dpr, 0, 0, dpr, 0, 0);
	g.clearRect(0, 0, canvas.clientWidth, canvas.clientHeight);
	const v = state.view;
	const zoomedIn = v.scale > 1.2;

	// station areas — tinted by the station's own colour, or by the selected heat metric
	const heat = state.analytics.heat;
	for (const st of state.stations) {
		const [x1, y1] = worldToScreen(st.bounds[0], st.bounds[2]);
		const [x2, y2] = worldToScreen(st.bounds[3] + 1, st.bounds[5] + 1);
		const hot = heat !== "off" ? heatColor(heat, state.analytics.stationById.get(st.id)) : null;
		if (heat !== "off" && !hot) {
			// heat mode on but this station has no samples in the window: neutral grey
			g.fillStyle = "#5b698126";
			g.strokeStyle = "#5b698155";
		} else {
			g.fillStyle = (hot || colorHex(st.color)) + (hot ? "4d" : "1f");
			g.strokeStyle = (hot || colorHex(st.color)) + (hot ? "cc" : "66");
		}
		g.lineWidth = hot ? 2 : 1;
		g.beginPath(); g.roundRect(x1, y1, x2 - x1, y2 - y1, 4); g.fill(); g.stroke();
	}

	// rails
	for (const r of state.rails.values()) {
		if (!modeEnabled(r.mode)) continue;
		const kmh = Math.max(r.speedA, r.speedB);
		g.strokeStyle = r.platform ? "#8fa3c4" : r.siding ? "#3a4358" : state.layers.speed ? speedColor(kmh) : "#5b6981";
		g.lineWidth = r.platform ? 4 : 2;
		g.setLineDash(r.siding ? [4, 4] : []);
		g.beginPath();
		for (let i = 0; i < r.points.length; i++) {
			const [sx, sy] = worldToScreen(r.points[i][0], r.points[i][2]);
			i === 0 ? g.moveTo(sx, sy) : g.lineTo(sx, sy);
		}
		g.stroke();
	}
	g.setLineDash([]);

	const s = uiScale();

	// platform labels
	if (zoomedIn) {
		g.font = Math.round(10 * s) + "px " + getComputedStyle(document.body).getPropertyValue("--mono");
		g.fillStyle = "#8fa3c4";
		g.textAlign = "center";
		for (const p of state.platforms.values()) {
			const [sx, sy] = worldToScreen(p.mid[0], p.mid[2]);
			g.fillText(firstLang(p.name), sx, sy - 6);
		}
	}

	// station labels
	if (state.layers.stations) {
		g.font = "600 " + Math.round(12 * s) + "px system-ui";
		g.textAlign = "center";
		for (const st of state.stations) {
			const [sx] = worldToScreen((st.bounds[0] + st.bounds[3]) / 2, 0);
			const [, sy] = worldToScreen(0, st.bounds[2]);
			const label = firstLang(st.name);
			g.fillStyle = "#0b0e14cc";
			const tw = g.measureText(label).width;
			g.fillRect(sx - tw / 2 - 4, sy - 12 - 12 * s, tw + 8, 4 + 12 * s);
			g.fillStyle = "#e7ecf7";
			g.fillText(label, sx, sy - 12);
		}
	}
}

function frame() {
	// A full-stage overlay covers the map — don't burn frames drawing under it.
	if (state.stringline.view) { drawStringline(); requestAnimationFrame(frame); return; }
	if (state.analytics.view) { requestAnimationFrame(frame); return; }
	// Before first layout (or with the stage collapsed) the canvas is 0×0; sizing the
	// buffers then makes drawImage throw, which would kill this rAF loop for good.
	if (!canvas.clientWidth || !canvas.clientHeight) { requestAnimationFrame(frame); return; }
	const dpr = resize();
	if (staticDirty) { drawStatic(dpr); staticDirty = false; }
	ctx.setTransform(1, 0, 0, 1, 0, 0);
	ctx.clearRect(0, 0, canvas.width, canvas.height);
	ctx.drawImage(staticCanvas, 0, 0);
	ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

	// interpolation delay from the measured stream cadence (fallback: configured rate)
	const delay = state.emaInterval
		? Math.max(250, Math.min(2000, state.emaInterval * 1.25 + 120))
		: state.updateMillis * 1.5;
	const renderTime = now() - delay;
	const frameNow = now();
	const dtSec = state.lastFrameAt ? Math.min(0.1, (frameNow - state.lastFrameAt) / 1000) : 0.016;
	state.lastFrameAt = frameNow;

	// signals — one dot per contiguous same-color block, worst aspect wins
	if (state.layers.signals) {
		for (const group of state.signalGroups) {
			if (!modeEnabled(group.mode)) continue;
			let aspect = "#46c46e";
			for (const railId of group.memberIds) {
				const live = state.signals.get(railId);
				if (live && live.occupied && live.occupied.length) { aspect = "#e5484d"; break; }
				if (live && live.reserved && live.reserved.length) aspect = "#f5b942";
			}
			const [sx, sy] = worldToScreen(group.x, group.z);
			ctx.beginPath(); ctx.arc(sx, sy, 4, 0, Math.PI * 2);
			ctx.fillStyle = aspect; ctx.fill();
			ctx.strokeStyle = "#0b0e14"; ctx.lineWidth = 1; ctx.stroke();
		}
	}

	// held platforms — pulsing amber ring + HOLD tag at the platform midpoint
	if (state.holds.size) {
		const pulse = 0.5 + 0.5 * Math.sin(frameNow / 300);
		for (const platId of state.holds) {
			const p = state.platforms.get(platId);
			if (!p) continue;
			const [sx, sy] = worldToScreen(p.mid[0], p.mid[2]);
			ctx.beginPath();
			ctx.arc(sx, sy, 7 + pulse * 4, 0, Math.PI * 2);
			ctx.strokeStyle = `rgba(245,185,66,${0.45 + pulse * 0.5})`;
			ctx.lineWidth = 2.5;
			ctx.stroke();
			if (state.view.scale > 0.5) {
				ctx.font = "700 9px " + getComputedStyle(document.body).getPropertyValue("--mono");
				ctx.textAlign = "center";
				ctx.fillStyle = "#f5b942";
				ctx.fillText("HOLD", sx, sy + 22);
			}
		}
	}

	// vehicles
	const smoothing = 1 - Math.exp(-dtSec / 0.12); // critically-damped ease toward target
	for (const [id, rec] of state.vehicles) {
		if (!modeEnabled(vehicleMode(rec))) { rec.screen = null; continue; }
		// the board's line filter dims (never hides) non-matching trains on the map
		const filteredOut = state.boardFilter && (!rec.route || lineKey(rec.route.name) !== state.boardFilter);
		const p = vehiclePos(rec, renderTime);
		if (!p) { rec.screen = null; continue; }
		const targetAngle = Math.atan2(p.hz || 0, p.hx || 1);
		if (!rec.disp || Math.hypot(p.x - rec.disp.x, p.z - rec.disp.z) > 64) {
			rec.disp = { x: p.x, z: p.z, angle: targetAngle }; // teleport-scale jump: snap
		} else {
			rec.disp.x += (p.x - rec.disp.x) * smoothing;
			rec.disp.z += (p.z - rec.disp.z) * smoothing;
			// only steer when actually moving — heading is noise when stopped
			if ((p.hx || 0) * (p.hx || 0) + (p.hz || 0) * (p.hz || 0) > 0.01) {
				let da = targetAngle - rec.disp.angle;
				while (da > Math.PI) da -= 2 * Math.PI;
				while (da < -Math.PI) da += 2 * Math.PI;
				rec.disp.angle += da * smoothing;
			}
		}
		rec.screen = worldToScreen(rec.disp.x, rec.disp.z);
		const [sx, sy] = rec.screen;
		const angle = rec.disp.angle;
		const color = rec.route ? colorHex(rec.route.color) : "#9aa7bf";
		const selected = id === state.selected;
		const len = Math.max(10 * uiScale(), Math.min(26, 6 * state.view.scale * uiScale()));

		if (state.follow && selected) { state.view.x = rec.disp.x; state.view.z = rec.disp.z; invalidateStatic(); }

		// Zoomed in with a known consist and rail: draw the individual cars along the
		// curve (radar.mta.info style); otherwise the single capsule marker.
		const consistDrawn = state.view.scale >= 1.6
			&& drawConsist(rec, p, color, selected, filteredOut);
		if (!consistDrawn) {
			ctx.save();
			if (filteredOut) ctx.globalAlpha = 0.25;
			ctx.translate(sx, sy);
			ctx.rotate(angle);
			ctx.beginPath();
			ctx.roundRect(-len / 2, -4, len, 8, 4);
			ctx.fillStyle = color;
			ctx.fill();
			ctx.lineWidth = selected ? 2 : 1;
			ctx.strokeStyle = selected ? "#ffffff" : "#0b0e14";
			ctx.stroke();
			if (rec.data.doors) { ctx.fillStyle = "#fff"; ctx.fillRect(-1.5, -4, 3, 8); }
			ctx.restore();
		}

		if (state.layers.trainLabels && state.view.scale > 0.5 && rec.route && !filteredOut) {
			const ls = uiScale();
			ctx.font = "700 " + Math.round(10 * ls) + "px " + getComputedStyle(document.body).getPropertyValue("--mono");
			ctx.textAlign = "center";
			const label = rec.route.number || firstLang(rec.route.name);
			ctx.fillStyle = "#0b0e14cc";
			const tw = ctx.measureText(label).width;
			ctx.fillRect(sx - tw / 2 - 3, sy - 9 - 12 * ls, tw + 6, 12 * ls);
			ctx.fillStyle = color;
			ctx.fillText(label, sx, sy - 11);
		}
	}

	// stale-stream indicator
	if (!DEMO && state.lastEventAt && now() - state.lastEventAt > 5000) setStatus("connecting");

	requestAnimationFrame(frame);
}

/**
 * Individual cars laid back along the head's rail curve — we know every car's real
 * length from the consist. The head sits at the interpolated rail parameter; each
 * car centre steps backwards (against the travel direction in parameter space,
 * remembered across stops via rec.lastDir) and takes its angle from the local
 * tangent, so a train wraps visibly around curves. Cars hanging past the rail's
 * ends extend along the end tangent instead of snapping. Returns false when the
 * rail or consist is unknown (caller falls back to the capsule).
 */
function drawConsist(rec, p, color, selected, dimmed) {
	if (!rec.consist || !p.rail || p.railT === undefined || p.railT < 0) return false;
	const r = state.rails.get(p.rail);
	if (!r || r.points.length < 2) return false;
	const lengths = (rec.consist.carLengths && rec.consist.carLengths.length)
		? rec.consist.carLengths
		: (rec.consist.cars || []).map(() => 16);
	if (!lengths.length) return false;
	if (p.paramDir) rec.lastDir = p.paramDir;
	const dir = rec.lastDir || 1;
	const L = r.cum[r.cum.length - 1] || 1;
	const scale = state.view.scale;
	const carH = Math.max(4.5, Math.min(11, 3 * scale)) * (uiScale() > 1.2 ? 1.15 : 1);
	const headDist = p.railT * L;
	let offset = 0;
	ctx.save();
	if (dimmed) ctx.globalAlpha = 0.25;
	for (let i = 0; i < lengths.length; i++) {
		const len = lengths[i];
		const centerDist = headDist - dir * (offset + len / 2);
		offset += len + 0.8;
		const c = railDistPoint(r, centerDist);
		const fwd = railDistPoint(r, centerDist + dir * len * 0.45);
		const back = railDistPoint(r, centerDist - dir * len * 0.45);
		if (!c || !fwd || !back) continue;
		const [cx, cy] = worldToScreen(c.x, c.z);
		const carAngle = Math.atan2(fwd.z - back.z, fwd.x - back.x);
		const carW = Math.max(5, len * scale * 0.9);
		ctx.save();
		ctx.translate(cx, cy);
		ctx.rotate(carAngle);
		ctx.beginPath();
		ctx.roundRect(-carW / 2, -carH / 2, carW, carH, Math.min(3, carH / 2));
		ctx.fillStyle = color;
		ctx.fill();
		ctx.lineWidth = selected ? 2 : 1;
		ctx.strokeStyle = selected ? "#ffffff" : "#0b0e14";
		ctx.stroke();
		if (i === 0) {
			// darker nose on the leading end so the direction reads at a glance
			const nose = Math.min(5, carW * 0.2);
			ctx.fillStyle = "rgba(11,14,20,.65)";
			ctx.fillRect(carW / 2 - nose, -carH / 2 + 1, nose, carH - 2);
		}
		if (rec.data.doors) {
			ctx.fillStyle = "#fff";
			ctx.fillRect(-1.2, -carH / 2, 2.4, carH);
		}
		ctx.restore();
	}
	ctx.restore();
	return true;
}

/* ---------- interaction ---------- */

function initUi() {
	// legend
	const rows = $("legendRows");
	let prev = 0;
	for (const [max, c] of SPEED_BUCKETS) {
		const row = document.createElement("div");
		row.className = "legend-row";
		row.innerHTML = `<span class="legend-swatch" style="background:${c}"></span>${max === 999 ? ">" + prev : prev + "–" + max}`;
		rows.appendChild(row);
		prev = max;
	}

	// layer toggles
	const bind = (id, key) => {
		$(id).onchange = (e) => { state.layers[key] = e.target.checked; invalidateStatic(); };
	};
	bind("lySpeed", "speed"); bind("lySignals", "signals");
	bind("lyStations", "stations"); bind("lyTrainLabels", "trainLabels");

	$("fitBtn").onclick = fitView;
	$("boardBtn").onclick = () => { $("board").classList.toggle("hidden"); $("boardBtn").classList.toggle("active"); };
	$("analyticsBtn").onclick = () => setAnalyticsView(!state.analytics.view);
	$("analyticsClose").onclick = () => setAnalyticsView(false);
	$("heatSelect").onchange = (e) => setHeatMode(e.target.value);
	renderHeatLegend();
	$("closeDetail").onclick = () => { state.selected = null; state.selectedStation = null; state.follow = false; updateDetail(); };
	$("stringBtn").onclick = () => setStringlineView(!state.stringline.view);
	$("stringClose").onclick = () => setStringlineView(false);
	$("stringWindow").onchange = (e) => { state.stringline.windowMin = parseInt(e.target.value, 10); };
	$("stringSegBtn").onclick = () => {
		const sl = state.stringline;
		sl.mode = sl.mode === "segment" ? "line" : "segment";
		sl.segStations = [];
		sl.built = null;
		renderSegUi();
		fetchStringline();
	};
	for (const [id, key] of [["slDwell", "dwell"], ["slRun", "run"], ["slHeadway", "headway"], ["slTravel", "travel"]]) {
		$(id).onchange = (e) => { state.stringline.show[key] = e.target.checked; };
	}
	$("stringEnd").oninput = (e) => {
		const sl = state.stringline;
		const v = parseInt(e.target.value, 10);
		if (v >= 1000) { sl.endT = null; }
		else {
			const newest = now();
			const oldest = newest - (sl.windowServerMin || 60) * 60000 + sl.windowMin * 60000 / 4;
			sl.endT = oldest + (newest - oldest) * (v / 1000);
		}
		$("stringLive").classList.toggle("active", sl.endT === null);
	};
	$("stringLive").onclick = () => {
		state.stringline.endT = null;
		$("stringEnd").value = 1000;
		$("stringLive").classList.add("active");
	};
	$("alertsBtn").onclick = () => setAlertsOpen(!state.alertsOpen);
	$("alertsClose").onclick = () => setAlertsOpen(false);
	$("alertsClear").onclick = () => { state.alerts = []; renderAlerts(); };
	initStringlineCanvas();
	$("followBtn").onclick = () => { state.follow = !state.follow; $("followBtn").classList.toggle("active", state.follow); };

	// pan/zoom
	let drag = null;
	canvas.addEventListener("pointerdown", (e) => {
		drag = { x: e.clientX, y: e.clientY, moved: false };
		canvas.setPointerCapture(e.pointerId);
		canvas.classList.add("dragging");
	});
	canvas.addEventListener("pointermove", (e) => {
		if (!drag) return;
		const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
		if (Math.abs(dx) + Math.abs(dy) > 3) drag.moved = true;
		state.view.x -= dx / state.view.scale;
		state.view.z -= dy / state.view.scale;
		drag.x = e.clientX; drag.y = e.clientY;
		state.follow = false; $("followBtn").classList.remove("active");
		invalidateStatic();
	});
	canvas.addEventListener("pointerup", (e) => {
		canvas.classList.remove("dragging");
		if (drag && !drag.moved) clickAt(e.clientX, e.clientY);
		drag = null;
	});
	canvas.addEventListener("wheel", (e) => {
		e.preventDefault();
		const factor = Math.exp(-e.deltaY * 0.0015);
		const rect = canvas.getBoundingClientRect();
		const mx = e.clientX - rect.left - canvas.clientWidth / 2;
		const my = e.clientY - rect.top - canvas.clientHeight / 2;
		const v = state.view;
		const wx = v.x + mx / v.scale, wz = v.z + my / v.scale;
		v.scale = Math.max(0.02, Math.min(20, v.scale * factor));
		v.x = wx - mx / v.scale; v.z = wz - my / v.scale;
		invalidateStatic();
	}, { passive: false });

	// board sorting
	document.querySelectorAll("#boardTable th").forEach((th) => {
		th.onclick = () => {
			const k = th.dataset.k;
			if (state.sort.k === k) state.sort.asc = !state.sort.asc;
			else state.sort = { k, asc: true };
			renderBoard();
		};
	});
}

function clickAt(cx, cy) {
	const rect = canvas.getBoundingClientRect();
	const x = cx - rect.left, y = cy - rect.top;
	let best = null, bestD = 15;
	for (const [id, rec] of state.vehicles) {
		if (!rec.screen) continue;
		const d = Math.hypot(rec.screen[0] - x, rec.screen[1] - y);
		if (d < bestD) { best = id; bestD = d; }
	}
	state.selected = best;
	state.selectedStation = null;
	if (!best) {
		state.follow = false;
		// No train hit — a click inside a station area opens the station panel.
		const v = state.view;
		const wx = v.x + (x - canvas.clientWidth / 2) / v.scale;
		const wz = v.z + (y - canvas.clientHeight / 2) / v.scale;
		for (const st of state.stations) {
			const b = st.bounds;
			if (wx >= b[0] && wx <= b[3] + 1 && wz >= b[2] && wz <= b[5] + 1) {
				state.selectedStation = st.id;
				// One-shot analytics fetch so the panel can show the station's scores.
				if (!state.analytics.data) fetchAnalytics();
				break;
			}
		}
	}
	updateDetail();
	renderBoard();
}

/** True when this vehicle is sitting at a platform that is actively holding it. */
function vehicleHeld(rec) {
	const d = rec.data;
	return !!(d.pPlat && d.pPlat !== "0" && d.pFrac === 0 && (d.kmh ?? 0) === 0
		&& state.holds.has(d.pPlat));
}

function centerOn(id) {
	const rec = state.vehicles.get(id);
	if (!rec || rec.samples.length === 0) return;
	const s = rec.samples[rec.samples.length - 1];
	state.view.x = s.x; state.view.z = s.z;
	if (state.view.scale < 1) state.view.scale = 1.5;
	invalidateStatic();
}

/* ---------- detail panel ---------- */

function updateDetail() {
	const el = $("detail");
	const rec = state.selected ? state.vehicles.get(state.selected) : null;
	if (!rec && state.selectedStation) { renderStationPanel(el); return; }
	if (!rec) { el.classList.add("hidden"); return; }
	el.classList.remove("hidden");
	const r = rec.route || {};
	$("detailChip").textContent = r.number || "•";
	$("detailChip").style.background = r.color !== undefined ? colorHex(r.color) : "#5b6981";
	$("detailTitle").textContent = firstLang(r.name) || "Train";
	const d = rec.data;
	const [devTxt, devCls] = fmtDev(d.devMs);
	const dwellS = d.dwellMs > 0 ? Math.ceil(d.dwellMs / 1000) + "s" : "—";
	const heldTag = vehicleHeld(rec) ? ' <span class="badge held">HELD</span>' : "";
	const blockedTag = state.obstructed.has(state.selected) ? ' <span class="badge blocked">BLOCKED</span>' : "";
	const rows = [
		["Destination", firstLang(r.dest) || "—"],
		["Next station", firstLang(r.nextStation) || "—"],
		["Speed", (d.kmh ?? 0).toFixed(1) + " km/h"],
		["Schedule", `<span class="${devCls}">${devTxt}</span>` + heldTag],
		["Doors", (d.doors ? "OPEN" : "closed") + blockedTag],
		["Dwell left", dwellS],
		["Mode", d.manual ? "MANUAL" : "ATO"],
	];
	if (rec.consist) rows.push(["Depot", firstLang(rec.consist.depot) || "—"]);
	let html = rows.map(([k, v]) => `<div class="row"><span class="k">${k}</span><span class="v">${v}</span></div>`).join("");
	if (rec.consist && rec.consist.cars) {
		html += `<div class="consist">${rec.consist.cars.length} car(s): ${rec.consist.cars.join(" + ")}</div>`;
	}
	$("followBtn").style.display = "";
	$("detailBody").innerHTML = html;
}

/* ---------- station panel ---------- */

function renderStationPanel(el) {
	const st = state.stations.find((s) => s.id === state.selectedStation);
	if (!st) { el.classList.add("hidden"); state.selectedStation = null; return; }
	el.classList.remove("hidden");
	$("followBtn").style.display = "none";
	$("detailChip").textContent = "●";
	$("detailChip").style.background = colorHex(st.color);
	$("detailTitle").textContent = firstLang(st.name) || "Station";

	let html = "";

	// Platforms with their calling routes, dwell, and hold state.
	const platforms = [...state.platforms.values()].filter((p) => p.stationId === st.id);
	if (platforms.length) {
		html += `<div class="sta-section">PLATFORMS</div>`;
		for (const p of platforms) {
			const routeChips = (p.routeIds || []).map((rid) => {
				const r = state.routes.get(rid);
				if (!r || r.hidden) return "";
				return `<span class="chip" style="background:${colorHex(r.color)}">${escapeHtml(r.number || firstLang(r.name))}</span>`;
			}).join("");
			const held = state.holds.has(p.id) ? ' <span class="badge held">HELD</span>' : "";
			html += `<div class="sta-plat"><span class="pname">${escapeHtml(firstLang(p.name))}</span>` +
				routeChips + held +
				`<span class="dwell">dwell ${Math.round((p.dwellMs || 0) / 1000)}s</span></div>`;
		}
	}

	// Live trains whose next stop is this station.
	const inbound = [];
	for (const [id, rec] of state.vehicles) {
		const r = rec.route;
		if (!r || firstLang(r.nextStation) !== firstLang(st.name)) continue;
		inbound.push({ id, rec });
	}
	html += `<div class="sta-section">INBOUND</div>`;
	if (!inbound.length) {
		html += `<div class="sta-inbound" style="color:var(--dim)">No train heading here right now</div>`;
	}
	for (const { id, rec } of inbound) {
		const r = rec.route;
		const [devTxt, devCls] = fmtDev(rec.data.devMs);
		html += `<div class="sta-inbound" data-veh="${escapeHtml(id)}" style="cursor:pointer">` +
			`<span class="chip" style="background:${colorHex(r.color)}">${escapeHtml(r.number || firstLang(r.name))}</span>` +
			`<span>${escapeHtml(firstLang(r.dest)) || "—"}</span>` +
			`<span class="spacer"></span><span class="${devCls}">${devTxt}</span></div>`;
	}

	// The analytics window's scorecard for this station, when loaded.
	const stats = state.analytics.stationById.get(st.id);
	if (stats) {
		html += `<div class="sta-section">LAST ${state.analytics.data.windowMinutes || 60} MIN</div>`;
		const rows = [
			["Departures", stats.departures],
			["On time", `<span class="${onTimeClass(stats.onTimePct)}">${stats.onTimePct}%</span>`],
			["Avg dwell overrun", fmtSigned(stats.avgDwellOverrunMs)],
			["Headway irregularity", stats.headwaySamples >= 2 ? stats.headwayIrregularity.toFixed(2) : "—"],
		];
		html += rows.map(([k, v]) => `<div class="row"><span class="k">${k}</span><span class="v">${v}</span></div>`).join("");
	}

	$("detailBody").innerHTML = html;
	$("detailBody").querySelectorAll("[data-veh]").forEach((row) => {
		row.onclick = () => {
			state.selected = row.dataset.veh;
			state.selectedStation = null;
			centerOn(state.selected);
			updateDetail();
			renderBoard();
		};
	});
}

/* ---------- dispatch board ---------- */

function boardRow(id, rec) {
	const r = rec.route || {};
	const d = rec.data;
	const variant = firstLang((r.name || "").split("||")[1] || "");
	return {
		id,
		route: r.number || firstLang(r.name) || "?",
		lineKeyVal: lineKey(r.name),
		line: firstLang(lineKey(r.name)) || "—",
		variant,
		color: r.color !== undefined ? colorHex(r.color) : "#5b6981",
		dest: firstLang(r.dest) || "—",
		next: firstLang(r.nextStation) || "—",
		kmh: d.kmh ?? 0,
		dev: d.devMs ?? 0,
		doors: d.doors ? "OPEN" : "",
		held: vehicleHeld(rec),
		blocked: state.obstructed.has(id),
		dwell: d.dwellMs > 0 ? Math.ceil(d.dwellMs / 1000) : 0,
		mode: d.manual ? "MAN" : "ATO",
	};
}

function renderBoard() {
	if ($("board").classList.contains("hidden")) return;
	renderBoardFilter();
	const rows = [...state.vehicles.entries()]
		.filter(([, rec]) => modeEnabled(vehicleMode(rec)))
		.map(([id, rec]) => boardRow(id, rec))
		.filter((row) => !state.boardFilter || row.lineKeyVal === state.boardFilter);
	const { k, asc } = state.sort;
	rows.sort((a, b) => {
		const va = a[k], vb = b[k];
		const c = typeof va === "number" ? va - vb : String(va).localeCompare(String(vb));
		return asc ? c : -c;
	});
	document.querySelectorAll("#boardTable th").forEach((th) =>
		th.classList.toggle("sorted", th.dataset.k === k));
	const body = $("boardBody");
	body.innerHTML = "";
	for (const row of rows) {
		const tr = document.createElement("tr");
		if (row.id === state.selected) tr.classList.add("selected");
		const [devTxt, devCls] = fmtDev(row.dev);
		tr.innerHTML =
			`<td><span class="chip" style="background:${row.color}">${row.route}</span></td>` +
			`<td>${escapeHtml(row.line)}${row.variant ? ` <span class="board-variant">· ${escapeHtml(row.variant)}</span>` : ""}</td>` +
			`<td>${row.dest}</td><td>${row.next}</td>` +
			`<td class="num">${row.kmh.toFixed(0)}</td>` +
			`<td class="num ${devCls}">${devTxt}</td>` +
			`<td>${row.doors}${row.held ? '<span class="badge held">HELD</span>' : ""}${row.blocked ? '<span class="badge blocked">BLK</span>' : ""}</td>` +
			`<td class="num">${row.dwell || "—"}</td>` +
			`<td>${row.mode}</td>`;
		tr.onclick = () => { state.selected = row.id; centerOn(row.id); updateDetail(); renderBoard(); };
		body.appendChild(tr);
	}
}

/**
 * Line filter chips over the board: one per line seen among live trains, colored by
 * that line's route color. Rebuilt with the board (1 Hz) but only when the chip set
 * or selection changed, so there's no hover flicker.
 */
let lastFilterSig = "";
function renderBoardFilter() {
	const lines = new Map(); // key -> {display, number, color}
	for (const [, rec] of state.vehicles) {
		const r = rec.route;
		if (!r || !modeEnabled(vehicleMode(rec))) continue;
		const key = lineKey(r.name);
		if (!lines.has(key)) {
			lines.set(key, { display: firstLang(key) || "Line", number: r.number || "", color: r.color });
		}
	}
	if (state.boardFilter && !lines.has(state.boardFilter)) state.boardFilter = null;
	const sig = JSON.stringify([...lines.keys()]) + "|" + state.boardFilter;
	if (sig === lastFilterSig) return;
	lastFilterSig = sig;
	const wrap = $("boardFilterRow");
	wrap.innerHTML = "";
	wrap.style.display = lines.size > 1 ? "" : "none";
	const all = document.createElement("button");
	all.className = "board-chip" + (state.boardFilter === null ? " active" : "");
	all.textContent = "All lines";
	all.onclick = () => { state.boardFilter = null; renderBoard(); };
	wrap.appendChild(all);
	for (const [key, l] of lines) {
		const chip = document.createElement("button");
		chip.className = "board-chip" + (state.boardFilter === key ? " active" : "");
		chip.innerHTML = `<span class="bullet" style="background:${colorHex(l.color)}">${escapeHtml(l.number)}</span>${escapeHtml(l.display)}`;
		chip.onclick = () => { state.boardFilter = state.boardFilter === key ? null : key; renderBoard(); };
		wrap.appendChild(chip);
	}
}

/* ---------- analytics ---------- */

/**
 * Polling is strictly demand-driven: the timer exists only while the Analytics view is
 * open or the station heat mode is on, and it is torn down the moment neither is true.
 * With the map alone on screen this file makes ZERO analytics requests.
 */
function ensureAnalyticsPolling() {
	const wanted = state.analytics.view || state.analytics.heat !== "off";
	if (wanted && !state.analytics.timer) {
		fetchAnalytics();
		state.analytics.timer = setInterval(fetchAnalytics, ANALYTICS_POLL_MS);
	} else if (!wanted && state.analytics.timer) {
		clearInterval(state.analytics.timer);
		state.analytics.timer = null;
	}
}

async function fetchAnalytics() {
	if (DEMO) { setAnalyticsData(demoAnalytics()); return; }
	if (state.analytics.fetching || document.hidden) return;
	state.analytics.fetching = true;
	try {
		const body = await (await fetch(`${API}/analytics?dimension=${state.dim}`)).json();
		setAnalyticsData(body.data || body);
	} catch (e) {
		/* transient — the connection status pill already reflects backend health */
	} finally {
		state.analytics.fetching = false;
	}
}

function setAnalyticsData(data) {
	state.analytics.data = data;
	state.analytics.stationById = new Map();
	if (data && data.stations) for (const s of data.stations) state.analytics.stationById.set(s.id, s);
	if (state.analytics.heat !== "off") invalidateStatic();
	if (state.analytics.view) renderAnalytics();
}

function setAnalyticsView(on) {
	if (on && state.stringline.view) setStringlineView(false);
	state.analytics.view = on;
	$("analytics").classList.toggle("hidden", !on);
	$("analyticsBtn").classList.toggle("active", on);
	ensureAnalyticsPolling();
	if (on) renderAnalytics();
}

function setHeatMode(mode) {
	state.analytics.heat = mode;
	renderHeatLegend();
	ensureAnalyticsPolling();
	invalidateStatic();
}

/* ---------- heat scale ---------- */

/* Buckets are [upper bound, colour, label]; the last one is the catch-all. Dwell overrun
 * is in seconds, headway irregularity is a coefficient of variation (stddev / mean). */
const HEAT_SCALES = {
	dwell: {
		title: "DWELL OVERRUN (s)",
		value: (st) => st.avgDwellOverrunMs / 1000,
		buckets: [[0, "#46c46e", "on time"], [5, "#b7cf4f", "0–5"], [15, "#f5b942", "5–15"],
			[30, "#f07b3f", "15–30"], [Infinity, "#e5484d", ">30"]],
	},
	headway: {
		title: "HEADWAY IRREGULARITY",
		value: (st) => st.headwayIrregularity,
		buckets: [[0.15, "#46c46e", "<0.15"], [0.3, "#b7cf4f", "0.15–0.3"], [0.5, "#f5b942", "0.3–0.5"],
			[0.8, "#f07b3f", "0.5–0.8"], [Infinity, "#e5484d", ">0.8"]],
	},
};

function heatColor(mode, station) {
	const scale = HEAT_SCALES[mode];
	if (!scale || !station) return null;
	const v = scale.value(station);
	if (v === undefined || v === null || Number.isNaN(v)) return null;
	for (const [max, color] of scale.buckets) if (v <= max) return color;
	return scale.buckets[scale.buckets.length - 1][1];
}

function renderHeatLegend() {
	const wrap = $("heatLegend");
	const scale = HEAT_SCALES[state.analytics.heat];
	wrap.classList.toggle("hidden", !scale);
	if (!scale) return;
	$("heatLegendTitle").textContent = scale.title;
	const rows = $("heatLegendRows");
	rows.innerHTML = "";
	for (const [, color, label] of scale.buckets) {
		const row = document.createElement("div");
		row.className = "legend-row";
		row.innerHTML = `<span class="legend-swatch" style="background:${color}"></span>${label}`;
		rows.appendChild(row);
	}
}

/* ---------- analytics view rendering ---------- */

function fmtDur(ms) {
	if (ms === undefined || ms === null || ms < 0) return "—";
	const s = Math.round(ms / 1000);
	if (s < 60) return s + "s";
	const m = Math.floor(s / 60);
	return m + "m" + String(s % 60).padStart(2, "0");
}

function fmtSigned(ms) {
	if (ms === undefined || ms === null) return "—";
	const s = Math.round(ms / 1000);
	return (s > 0 ? "+" : "") + s + "s";
}

function onTimeClass(pct) { return pct >= 90 ? "ok" : pct >= 75 ? "warn" : "bad"; }

function renderAnalytics() {
	const data = state.analytics.data;
	const meta = $("analyticsMeta");
	const empty = $("analyticsEmpty");
	const cards = $("lineCards");
	const stationWrap = $("stationTableWrap");

	if (!data) { meta.textContent = "loading…"; return; }
	if (data.enabled === false) {
		meta.textContent = "";
		empty.textContent = "Timetable analytics is disabled on the server (analytics.enabled = false).";
		empty.classList.remove("hidden");
		cards.innerHTML = "";
		stationWrap.classList.add("hidden");
		return;
	}

	const age = Math.max(0, Math.round((Date.now() - data.computedAt) / 1000));
	meta.textContent = `${data.departures} departures over the last ${data.windowMinutes} min · `
		+ `on time = |dev| ≤ ${data.onTimeToleranceSeconds}s · bunching < `
		+ `${Math.round(data.bunchingFraction * 100)}% of headway · recomputed ${age}s ago`
		+ (data.dropped ? ` · ${data.dropped} events dropped` : "");

	const lines = data.lines || [];
	empty.classList.toggle("hidden", lines.length > 0);
	if (!lines.length) empty.textContent = "Waiting for the first departures…";

	cards.innerHTML = "";
	for (const line of lines) cards.appendChild(lineCard(line));

	const stations = data.stations || [];
	stationWrap.classList.toggle("hidden", stations.length === 0);
	const body = $("stationBody");
	body.innerHTML = "";
	for (const st of stations) {
		const tr = document.createElement("tr");
		const dwellColor = heatColor("dwell", st) || "#5b6981";
		const hwColor = heatColor("headway", st) || "#5b6981";
		tr.innerHTML =
			`<td>${escapeHtml(firstLang(st.name)) || "—"}</td>` +
			`<td class="num">${st.departures}</td>` +
			`<td class="num ${onTimeClass(st.onTimePct)}">${st.onTimePct}%</td>` +
			`<td class="num"><span class="heat-cell" style="background:${dwellColor}"></span>${fmtSigned(st.avgDwellOverrunMs)}</td>` +
			`<td class="num">${fmtSigned(st.maxDwellOverrunMs)}</td>` +
			`<td class="num"><span class="heat-cell" style="background:${hwColor}"></span>` +
			`${st.headwaySamples >= 2 ? st.headwayIrregularity.toFixed(2) : "—"}</td>`;
		body.appendChild(tr);
	}
}

function lineCard(line) {
	const card = document.createElement("div");
	card.className = "line-card";
	const color = colorHex(line.color);
	const head = document.createElement("div");
	head.className = "line-card-head";
	head.innerHTML =
		`<span class="chip" style="background:${color}">${escapeHtml(line.number || "•")}</span>` +
		`<span class="name">${escapeHtml(firstLang(line.name)) || "Unnamed line"}</span>` +
		`<span class="spacer"></span><span class="count">${line.departures} dep</span>`;
	card.appendChild(head);

	const stats = document.createElement("div");
	stats.className = "line-stats";
	stats.innerHTML =
		stat("On time", `<span class="${onTimeClass(line.onTimePct)}">${line.onTimePct}%</span>`,
			`${line.onTime}/${line.departures} · avg ${fmtSigned(line.avgDeviationMs)}`) +
		stat("Headway", fmtDur(line.avgHeadwayMs),
			`vs ${fmtDur(line.refHeadwayMs)} ${line.headwaySource}`) +
		stat("Dwell overrun", fmtSigned(line.avgDwellOverrunMs),
			`max ${fmtSigned(line.maxDwellOverrunMs)}`);
	card.appendChild(stats);

	const canvas = document.createElement("canvas");
	canvas.className = "line-chart";
	card.appendChild(canvas);

	const alerts = document.createElement("div");
	if (line.bunching && line.bunching.length) {
		alerts.className = "line-alerts";
		alerts.innerHTML = line.bunching.slice(0, 6).map((b) =>
			`▲ ${fmtDur(b.gapMs)} gap — ${escapeHtml(firstLang(b.station)) || "?"} plat ${escapeHtml(firstLang(b.platform)) || "?"}`
		).join("<br>");
	} else {
		alerts.className = "line-alerts none";
		alerts.textContent = "no bunching alerts";
	}
	card.appendChild(alerts);

	// The canvas has no layout size until it is in the document, so size + draw next frame.
	requestAnimationFrame(() => drawHeadwayChart(canvas, line));
	return card;
}

function stat(k, v, sub) {
	return `<div class="line-stat"><div class="k">${k}</div><div class="v">${v}</div>` +
		`<div class="sub">${sub}</div></div>`;
}

/**
 * Headway strip chart: time on x, the gap to the previous train on y, with the reference
 * headway as a dashed line and the bunching threshold shaded. Points under the threshold
 * are drawn red.
 */
function drawHeadwayChart(canvas, line) {
	const dpr = window.devicePixelRatio || 1;
	const w = canvas.clientWidth || 340, h = canvas.clientHeight || 110;
	canvas.width = w * dpr;
	canvas.height = h * dpr;
	const g = canvas.getContext("2d");
	g.setTransform(dpr, 0, 0, dpr, 0, 0);
	g.clearRect(0, 0, w, h);

	const css = getComputedStyle(document.body);
	const dim = css.getPropertyValue("--dim").trim() || "#7d8aa5";
	const border = css.getPropertyValue("--border").trim() || "#232c3f";
	const series = line.series || [];
	const pad = { l: 40, r: 8, t: 10, b: 16 };
	const plotW = Math.max(1, w - pad.l - pad.r);
	const plotH = Math.max(1, h - pad.t - pad.b);

	if (series.length < 2) {
		g.fillStyle = dim;
		g.font = "11px " + css.getPropertyValue("--mono");
		g.textAlign = "center";
		g.fillText(series.length ? "1 headway sample so far" : "no headway samples yet", w / 2, h / 2);
		return;
	}

	const ref = line.refHeadwayMs > 0 ? line.refHeadwayMs : 0;
	const t0 = series[0][0], t1 = series[series.length - 1][0];
	const span = Math.max(1, t1 - t0);
	let maxGap = 0;
	for (const [, gap] of series) maxGap = Math.max(maxGap, gap);
	const yMax = Math.max(maxGap, ref) * 1.15 || 1;
	const X = (t) => pad.l + ((t - t0) / span) * plotW;
	const Y = (v) => pad.t + plotH - (v / yMax) * plotH;

	// axes
	g.strokeStyle = border;
	g.lineWidth = 1;
	g.beginPath();
	g.moveTo(pad.l, pad.t); g.lineTo(pad.l, pad.t + plotH); g.lineTo(pad.l + plotW, pad.t + plotH);
	g.stroke();
	g.fillStyle = dim;
	g.font = "10px " + css.getPropertyValue("--mono");
	g.textAlign = "right";
	g.fillText(fmtDur(yMax), pad.l - 4, pad.t + 8);
	g.fillText("0", pad.l - 4, pad.t + plotH);

	if (ref > 0) {
		const threshold = ref * (state.analytics.data ? state.analytics.data.bunchingFraction : 0.5);
		g.fillStyle = "rgba(229,72,77,.10)";
		g.fillRect(pad.l, Y(threshold), plotW, pad.t + plotH - Y(threshold));
		g.strokeStyle = "#8fa3c4";
		g.setLineDash([4, 4]);
		g.beginPath(); g.moveTo(pad.l, Y(ref)); g.lineTo(pad.l + plotW, Y(ref)); g.stroke();
		g.setLineDash([]);
		g.textAlign = "left";
		g.fillStyle = "#8fa3c4";
		g.fillText("sched " + fmtDur(ref), pad.l + 3, Math.max(pad.t + 9, Y(ref) - 3));
	}

	g.strokeStyle = colorHex(line.color);
	g.lineWidth = 1.5;
	g.beginPath();
	series.forEach(([t, gap], i) => (i ? g.lineTo(X(t), Y(gap)) : g.moveTo(X(t), Y(gap))));
	g.stroke();

	const threshold = ref > 0 ? ref * (state.analytics.data ? state.analytics.data.bunchingFraction : 0.5) : -1;
	for (const [t, gap] of series) {
		g.beginPath();
		g.arc(X(t), Y(gap), 2.5, 0, Math.PI * 2);
		g.fillStyle = threshold > 0 && gap < threshold ? "#e5484d" : colorHex(line.color);
		g.fill();
	}
}

function escapeHtml(s) {
	return String(s === undefined || s === null ? "" : s)
		.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

/* ---------- alerts ---------- */

const ALERT_KINDS = {
	hold_cap: "HOLD CAP",
	door_backstop: "DOORS",
	late: "LATE",
	stalled: "STALLED",
};

function addAlert(a, quiet) {
	if (state.alerts.some((x) => x.seq === a.seq)) return;
	state.alerts.push(a);
	state.alerts.sort((x, y) => x.seq - y.seq);
	if (state.alerts.length > 200) state.alerts.splice(0, state.alerts.length - 200);
	if (!quiet && !state.alertsOpen) {
		state.alertsUnread++;
	}
	renderAlerts();
}

function setAlertsOpen(on) {
	state.alertsOpen = on;
	$("alertsFeed").classList.toggle("hidden", !on);
	$("alertsBtn").classList.toggle("active", on);
	if (on) state.alertsUnread = 0;
	renderAlerts();
}

function fmtClock(t) {
	const d = new Date(t);
	return String(d.getHours()).padStart(2, "0") + ":" + String(d.getMinutes()).padStart(2, "0");
}

function renderAlerts() {
	const badge = $("alertsBadge");
	badge.classList.toggle("hidden", state.alertsUnread === 0);
	badge.textContent = state.alertsUnread;
	if (!state.alertsOpen) return;
	const body = $("alertsBody");
	if (!state.alerts.length) { body.innerHTML = '<div class="an-empty">No alerts</div>'; return; }
	body.innerHTML = "";
	for (let i = state.alerts.length - 1; i >= 0; i--) {
		const a = state.alerts[i];
		const row = document.createElement("div");
		row.className = "alert-row";
		row.innerHTML =
			`<span class="dot" style="background:${a.severity === "bad" ? "var(--red)" : "var(--amber)"}"></span>` +
			`<span class="kind">${ALERT_KINDS[a.kind] || a.kind.toUpperCase()}</span>` +
			`<span class="msg">${escapeHtml(a.detail)}</span>` +
			`<span class="when">${fmtClock(a.t)}</span>`;
		row.onclick = () => {
			if (a.veh && state.vehicles.has(a.veh)) {
				state.selected = a.veh;
				state.selectedStation = null;
				setStringlineView(false);
				centerOn(a.veh);
				updateDetail();
				renderBoard();
			} else if (a.plat && state.platforms.has(a.plat)) {
				const p = state.platforms.get(a.plat);
				state.view.x = p.mid[0]; state.view.z = p.mid[2];
				if (state.view.scale < 1) state.view.scale = 1.5;
				setStringlineView(false);
				invalidateStatic();
			}
		};
		body.appendChild(row);
	}
}

/* ---------- stringlines ---------- */

/* Poll cadence for the departure history while the view is open; the live tips move
 * every frame off the SSE stream regardless. */
const STRINGLINE_POLL_MS = 15000;

/** A route's line grouping key: the part before MTR's "||" direction separator. */
function lineKey(name) {
	return (name || "").split("||")[0];
}

function setStringlineView(on) {
	state.stringline.view = on;
	$("stringline").classList.toggle("hidden", !on);
	$("stringBtn").classList.toggle("active", on);
	if (on) {
		if (state.analytics.view) setAnalyticsView(false);
		fetchStringline();
		if (!state.stringline.timer) {
			state.stringline.timer = setInterval(fetchStringline, STRINGLINE_POLL_MS);
		}
	} else if (state.stringline.timer) {
		clearInterval(state.stringline.timer);
		state.stringline.timer = null;
	}
}

async function fetchStringline() {
	const sl = state.stringline;
	if (DEMO) { applyStringline(demoStringline()); return; }
	if (sl.fetching || document.hidden) { sl.refetchWanted = true; return; }
	sl.fetching = true;
	sl.refetchWanted = false;
	try {
		const body = await (await fetch(`${API}/stringline?dimension=${state.dim}&routes=${encodeURIComponent(activeRouteIds().join(","))}`)).json();
		applyStringline(body.data || body);
	} catch (e) {
		/* transient */
	} finally {
		sl.fetching = false;
		// A selection change (or the first group pick inside applyStringline) that
		// landed while this request was in flight would otherwise be silently
		// dropped until the next poll.
		if (sl.refetchWanted) setTimeout(fetchStringline, 0);
	}
}

function applyStringline(data) {
	const sl = state.stringline;
	sl.axis = data.axis || null;
	sl.deps = data.deps || [];
	sl.windowServerMin = data.windowMinutes || 60;
	sl.analyticsEnabled = data.analyticsEnabled !== false;
	sl.built = null; // geometry cache is stale whenever new data lands

	// Group routes into lines by the "||" key; badges rebuild only when changed.
	const groups = [];
	const byKey = new Map();
	for (const r of (sl.axis?.routes || [])) {
		if (r.hidden || (r.stations || []).length < 2) continue;
		const key = lineKey(r.name);
		let g = byKey.get(key);
		if (!g) {
			g = { key, display: firstLang(key) || "Line", number: r.number, color: r.color, routeIds: [] };
			byKey.set(key, g);
			groups.push(g);
		}
		g.routeIds.push(r.id);
	}
	groups.sort((a, b) => String(a.number || a.display).localeCompare(String(b.number || b.display), undefined, { numeric: true }));
	const changed = JSON.stringify(groups.map((g) => g.key)) !== JSON.stringify(sl.groups.map((g) => g.key));
	sl.groups = groups;
	if (!sl.groupKey || !groups.some((g) => g.key === sl.groupKey)) {
		sl.groupKey = groups.length ? groups[0].key : null;
		if (sl.groupKey && !DEMO) fetchStringline(); // queued via refetchWanted if busy
	}
	if (changed) renderStringBadges();
}

function renderStringBadges() {
	const wrap = $("stringBadges");
	wrap.innerHTML = "";
	for (const g of state.stringline.groups) {
		const b = document.createElement("button");
		b.className = "sl-badge" + (g.key === state.stringline.groupKey ? " active" : "");
		b.innerHTML = `<span class="bullet" style="background:${colorHex(g.color)}">${escapeHtml(g.number || "")}</span>${escapeHtml(g.display)}`;
		b.onclick = () => {
			state.stringline.groupKey = g.key;
			state.stringline.segStations = [];
			state.stringline.built = null;
			renderStringBadges();
			renderSegUi();
			fetchStringline();
		};
		wrap.appendChild(b);
	}
}

/** Meta + hint lines; DOM writes only when the text actually changed (runs per frame). */
let lastStringMeta = "";
function renderStringMeta(extra) {
	const sl = state.stringline;
	const live = liveGroupVehicles().length;
	const text = !sl.analyticsEnabled
		? "analytics.enabled is off on the server — no departure history"
		: `${sl.deps.length} departures in the last ${sl.windowServerMin} min · ${live} live${extra ? " · " + extra : ""}`;
	if (text !== lastStringMeta) {
		lastStringMeta = text;
		$("stringMeta").textContent = text;
	}
}

function renderSegUi() {
	const sl = state.stringline;
	$("stringSegBtn").classList.toggle("active", sl.mode === "segment");
	const hint = $("stringHint");
	if (sl.mode !== "segment") {
		hint.classList.add("hidden");
	} else {
		hint.classList.remove("hidden");
		hint.textContent = sl.segStations.length < 2
			? "Click two stations on the left axis to bound the segment"
			: "Every line over this span is shown — click stations to adjust, Segment to exit";
	}
}

/**
 * The segment (interlined-trunk) selection, resolved against the current group's
 * axis route: the corridor is the axis span between the outermost selected
 * stations, and every non-hidden route serving ≥2 of the corridor's stations
 * qualifies — that is exactly the set of services interlining over the trunk.
 * Null when not in segment mode or fewer than 2 stations are picked.
 */
function segmentInfo() {
	const sl = state.stringline;
	if (sl.mode !== "segment" || sl.segStations.length < 2 || !sl.axis) return null;
	const base = axisRouteOfGroup();
	if (!base) return null;
	const indices = sl.segStations
		.map((sta) => base.stations.findIndex((s) => s.sta === sta))
		.filter((i) => i >= 0);
	if (indices.length < 2) return null;
	const from = Math.min(...indices), to = Math.max(...indices);
	const corridor = base.stations.slice(from, to + 1);
	const corridorIds = new Set(corridor.map((s) => s.sta).filter((id) => id !== "0"));
	const routes = (sl.axis.routes || []).filter((r) => {
		if (r.hidden) return false;
		let hits = 0;
		for (const s of r.stations) if (corridorIds.has(s.sta)) hits++;
		return hits >= 2;
	});
	return { corridor, corridorIds, routes };
}

/** The longest route of the selected line group (the y-axis donor). */
function axisRouteOfGroup() {
	const ids = new Set((state.stringline.groups.find((g) => g.key === state.stringline.groupKey) || {}).routeIds || []);
	const routes = (state.stringline.axis?.routes || []).filter((r) => ids.has(r.id));
	if (!routes.length) return null;
	return routes.reduce((a, b) => (b.stations.length > a.stations.length ? b : a));
}

/** Route ids the chart is currently about: the line group, or the segment's set. */
function activeRouteIds() {
	const seg = segmentInfo();
	if (seg) return seg.routes.map((r) => r.id);
	const g = state.stringline.groups.find((x) => x.key === state.stringline.groupKey);
	return g ? g.routeIds : [];
}

function liveGroupVehicles() {
	const ids = new Set(activeRouteIds());
	const out = [];
	for (const [id, rec] of state.vehicles) {
		if (rec.route && ids.has(rec.route.id)) out.push({ id, rec });
	}
	return out;
}

const slCanvas = $("slCanvas");
const slCtx = slCanvas.getContext("2d");

function initStringlineCanvas() {
	slCanvas.addEventListener("mousemove", (e) => {
		const rect = slCanvas.getBoundingClientRect();
		state.stringline.mouse = [e.clientX - rect.left, e.clientY - rect.top];
	});
	slCanvas.addEventListener("mouseleave", () => {
		state.stringline.mouse = null;
		state.stringline.hover = null;
		$("slTip").classList.add("hidden");
	});
	slCanvas.addEventListener("click", (e) => {
		const sl = state.stringline;
		const rect = slCanvas.getBoundingClientRect();
		const x = e.clientX - rect.left, y = e.clientY - rect.top;
		// Segment mode: clicks in the station gutter toggle the corridor bounds.
		if (sl.mode === "segment" && sl.layout && x < sl.layout.padL) {
			let best = null, bestD = 10;
			for (const s of sl.layout.stations) {
				const d = Math.abs(s.y - y);
				if (d < bestD && s.sta !== "0") { best = s; bestD = d; }
			}
			if (best) {
				const i = sl.segStations.indexOf(best.sta);
				if (i >= 0) sl.segStations.splice(i, 1);
				else sl.segStations.push(best.sta);
				sl.built = null;
				renderSegUi();
				fetchStringline();
			}
			return;
		}
		const hover = sl.hover;
		if (hover && hover.trace.veh && state.vehicles.has(hover.trace.veh)) {
			state.selected = hover.trace.veh;
			state.selectedStation = null;
			setStringlineView(false);
			centerOn(hover.trace.veh);
			updateDetail();
			renderBoard();
		}
	});
}

/**
 * Trace geometry in TIME-space ([t, dist] points), rebuilt only when the data or the
 * selection changes (sl.built is nulled at every invalidation point) — per frame the
 * draw loop just maps t→x / dist→y, instead of re-chaining every departure row at
 * 60 fps like the first cut did.
 *
 * Line mode: the group's longest route donates the axis; the other direction maps on
 * by station id. Segment mode: the corridor slice donates the axis (re-based to 0)
 * and EVERY route serving ≥2 corridor stations maps on — the interlined-trunk view.
 */
function buildGeometry() {
	const sl = state.stringline;
	const base = axisRouteOfGroup();
	if (!base || !sl.axis) return null;
	const seg = segmentInfo();
	let stations, routesUsed;
	if (seg) {
		const d0 = seg.corridor[0].dist;
		stations = seg.corridor.map((s) => ({ ...s, dist: s.dist - d0 }));
		routesUsed = seg.routes;
	} else {
		stations = base.stations;
		const ids = new Set((sl.groups.find((g) => g.key === sl.groupKey) || {}).routeIds || []);
		routesUsed = (sl.axis.routes || []).filter((r) => ids.has(r.id));
	}
	const platDist = new Map(), staDist = new Map();
	for (const s of stations) {
		platDist.set(s.plat, s.dist);
		if (s.sta !== "0" && !staDist.has(s.sta)) staDist.set(s.sta, s.dist);
	}
	for (const r of routesUsed) {
		for (const s of r.stations) {
			if (!platDist.has(s.plat) && staDist.has(s.sta)) platDist.set(s.plat, staDist.get(s.sta));
		}
	}

	const routeById = new Map((sl.axis.routes || []).map((r) => [r.id, r]));
	const byVeh = new Map();
	for (const row of sl.deps) {
		const veh = row[0];
		if (!byVeh.has(veh)) byVeh.set(veh, []);
		byVeh.get(veh).push(row);
	}
	const traces = [];
	for (const [veh, rows] of byVeh) {
		rows.sort((a, b) => a[2] - b[2]);
		let current = null;
		let lastStop = -1, lastT = 0;
		for (const [, plat, t, dwell, dev, stop, routeId] of rows) {
			const dist = platDist.get(plat);
			if (dist === undefined) continue; // off this axis (branch / outside the segment)
			if (!current || stop < lastStop || t - lastT > 20 * 60000) {
				const route = routeById.get(routeId);
				current = {
					veh,
					color: route ? colorHex(route.color) : "#9aa7bf",
					number: route ? (route.number || "") : "",
					routeName: route ? firstLang(lineKey(route.name)) : "",
					variant: route ? firstLang((route.name || "").split("||")[1] || "") : "",
					pts: [],
					lastDev: dev,
					lastT: t,
					live: false,
				};
				traces.push(current);
			}
			current.pts.push([t - (dwell || 0), dist]);
			current.pts.push([t, dist]);
			current.lastDev = dev;
			current.lastT = t;
			lastStop = stop;
			lastT = t;
		}
	}
	// Headways: gap between consecutive departures of the SAME route at the same
	// platform (deps are server-sorted oldest-first). Resolved to axis distance here
	// so the draw loop only maps.
	const headways = [];
	const lastDep = new Map();
	for (const [, plat, t, , , , routeId] of sl.deps) {
		const dist = platDist.get(plat);
		if (dist === undefined) continue;
		const key = routeId + "|" + plat;
		const prev = lastDep.get(key);
		if (prev !== undefined && t - prev > 0 && t - prev < 3 * 60 * 60000) {
			headways.push({ t, dist, gap: t - prev });
		}
		lastDep.set(key, t);
	}

	return {
		stations, platDist, traces, headways,
		total: Math.max(1, stations[stations.length - 1].dist),
		lineCount: new Set(routesUsed.map((r) => lineKey(r.name))).size,
		segment: !!seg,
	};
}

function drawStringline() {
	const sl = state.stringline;
	const dpr = window.devicePixelRatio || 1;
	const w = slCanvas.clientWidth, h = slCanvas.clientHeight;
	if (!w || !h) return;
	if (slCanvas.width !== w * dpr || slCanvas.height !== h * dpr) {
		slCanvas.width = w * dpr;
		slCanvas.height = h * dpr;
	}
	const g = slCtx;
	g.setTransform(dpr, 0, 0, dpr, 0, 0);
	g.clearRect(0, 0, w, h);

	const css = getComputedStyle(document.body);
	const mono = css.getPropertyValue("--mono");
	const dim = css.getPropertyValue("--dim").trim() || "#7d8aa5";
	const border = css.getPropertyValue("--border").trim() || "#232c3f";
	const accent = css.getPropertyValue("--accent").trim() || "#4da3ff";

	if (!sl.built) sl.built = buildGeometry();
	const B = sl.built;
	if (!B || B.stations.length < 2) {
		sl.layout = null;
		g.fillStyle = dim;
		g.font = "13px system-ui";
		g.textAlign = "center";
		g.fillText(sl.axis ? "No line selected" : "Loading…", w / 2, h / 2);
		return;
	}

	const us = uiScale();
	const pad = { l: Math.round(160 * us), r: 14, t: 18, b: Math.round(20 + 10 * us) };
	const plotH = h - pad.t - pad.b, plotW = w - pad.l - pad.r;
	const Y = (dist) => pad.t + (dist / B.total) * plotH;
	const tNow = now();
	const tEnd = sl.endT ?? tNow;      // scrubbed end, or live "now"
	const liveEdge = sl.endT === null;
	const t0 = tEnd - sl.windowMin * 60000;
	const X = (t) => pad.l + ((t - t0) / (tEnd - t0)) * plotW;
	sl.layout = { padL: pad.l, stations: B.stations.map((s) => ({ sta: s.sta, y: Y(s.dist) })) };

	// grid: station rows (selected corridor bounds highlighted) + time ticks
	const stationFont = Math.round(12 * us);
	g.font = stationFont + "px system-ui";
	g.textAlign = "right";
	let lastLabelY = -99;
	for (const s of B.stations) {
		const y = Y(s.dist);
		const picked = sl.mode === "segment" && sl.segStations.includes(s.sta);
		g.strokeStyle = border;
		g.lineWidth = 1;
		g.beginPath(); g.moveTo(pad.l, y); g.lineTo(w - pad.r, y); g.stroke();
		if (y - lastLabelY >= stationFont + 2) {
			g.fillStyle = picked ? accent : "#aab6cf";
			g.font = (picked ? "700 " : "") + stationFont + "px system-ui";
			let label = firstLang(s.staName) || firstLang(s.platName) || "?";
			if (label.length > 22) label = label.slice(0, 21) + "…";
			g.fillText(label, pad.l - 8, y + stationFont / 3);
			lastLabelY = y;
		}
	}
	const tickMin = sl.windowMin <= 15 ? 2 : sl.windowMin <= 30 ? 5 : sl.windowMin <= 60 ? 10 : 15;
	g.textAlign = "center";
	g.font = Math.round(11 * us) + "px " + mono;
	const firstTick = Math.ceil(t0 / (tickMin * 60000)) * tickMin * 60000;
	for (let t = firstTick; t <= tEnd; t += tickMin * 60000) {
		const x = X(t);
		g.strokeStyle = border;
		g.globalAlpha = 0.45;
		g.beginPath(); g.moveTo(x, pad.t); g.lineTo(x, pad.t + plotH); g.stroke();
		g.globalAlpha = 1;
		g.fillStyle = dim;
		g.fillText(fmtClock(t), x, h - 8);
	}

	// map cached time-space traces to px; attach live tips (live edge only)
	const drawn = [];
	const newestByVeh = new Map();
	for (const tr of B.traces) {
		if (tr.lastT < t0 && tNow - tr.lastT > 20 * 60000) continue; // fully left of window
		if (tr.pts.length && tr.pts[0][0] > tEnd) continue;          // fully right of a scrubbed window
		const px = tr.pts.map(([t, dist]) => [X(t), Y(dist)]);
		drawn.push({ tr, px, live: false, lastDev: tr.lastDev });
		const prev = newestByVeh.get(tr.veh);
		if (!prev || tr.lastT > prev.tr.lastT) newestByVeh.set(tr.veh, drawn[drawn.length - 1]);
	}
	for (const { id, rec } of liveEdge ? liveGroupVehicles() : []) {
		const d = rec.data;
		const distPrev = d.pPlat && d.pPlat !== "0" ? B.platDist.get(d.pPlat) : undefined;
		const distNext = d.nPlat && d.nPlat !== "0" ? B.platDist.get(d.nPlat) : undefined;
		let dist;
		if (distPrev !== undefined && distNext !== undefined) dist = distPrev + (distNext - distPrev) * (d.pFrac || 0);
		else if (distPrev !== undefined) dist = distPrev;
		else if (distNext !== undefined) dist = distNext;
		else continue;
		const entry = newestByVeh.get(id);
		if (entry && tNow - entry.tr.lastT <= 20 * 60000) {
			entry.px.push([X(tNow), Y(dist)]);
			entry.live = true;
			entry.lastDev = d.devMs ?? entry.lastDev;
		} else {
			const r = rec.route;
			drawn.push({
				tr: {
					veh: id,
					color: r ? colorHex(r.color) : "#9aa7bf",
					number: r ? (r.number || "") : "",
					routeName: r ? firstLang(lineKey(r.name)) : "",
					variant: r ? firstLang((r.name || "").split("||")[1] || "") : "",
					lastT: tNow,
				},
				px: [[X(tNow), Y(dist)]],
				live: true,
				lastDev: d.devMs ?? 0,
			});
		}
	}
	sl.traces = drawn;

	// hover hit-test
	sl.hover = null;
	if (sl.mouse && sl.mouse[0] >= pad.l) {
		let bestD = 7;
		for (const entry of drawn) {
			for (let i = 1; i < entry.px.length; i++) {
				const d = segDist(sl.mouse, entry.px[i - 1], entry.px[i]);
				if (d < bestD) { bestD = d; sl.hover = { trace: entry.tr, entry }; }
			}
		}
	}

	for (const entry of drawn) {
		const hovered = sl.hover && sl.hover.entry === entry;
		g.strokeStyle = entry.tr.color;
		g.lineWidth = hovered ? 3.5 : Math.max(1.8, 1.6 * us);
		g.globalAlpha = sl.hover && !hovered ? 0.35 : 1;
		g.beginPath();
		for (let i = 0; i < entry.px.length; i++) {
			i === 0 ? g.moveTo(entry.px[i][0], entry.px[i][1]) : g.lineTo(entry.px[i][0], entry.px[i][1]);
		}
		g.stroke();
		if (entry.live) {
			const tip = entry.px[entry.px.length - 1];
			g.beginPath();
			g.arc(tip[0], tip[1], hovered ? 5 : 3.5, 0, Math.PI * 2);
			g.fillStyle = entry.tr.color;
			g.fill();
			g.strokeStyle = "#0b0e14";
			g.lineWidth = 1;
			g.stroke();
		}
	}
	g.globalAlpha = 1;

	// annotation overlays (pvibien's Show Dwell / Run time / Headways / Travel time).
	// While hovering, annotations narrow to the hovered run so the numbers stay legible.
	const annFont = Math.round(10 * us);
	if (sl.show.dwell || sl.show.run || sl.show.travel) {
		g.font = annFont + "px " + mono;
		for (const entry of drawn) {
			if (!entry.tr.pts) continue; // standalone live dot
			if (sl.hover && sl.hover.entry !== entry) continue;
			const px = entry.px, pts = entry.tr.pts;
			if (sl.show.dwell) {
				g.fillStyle = "#f5b942";
				g.textAlign = "center";
				for (let i = 0; i + 1 < pts.length; i += 2) {
					const dt = pts[i + 1][0] - pts[i][0];
					if (dt <= 0 || px[i + 1][0] - px[i][0] < annFont * 2.2) continue;
					g.fillText(Math.round(dt / 1000) + "s", (px[i][0] + px[i + 1][0]) / 2, px[i][1] - 4);
				}
			}
			if (sl.show.run) {
				g.fillStyle = "#8fa3c4";
				g.textAlign = "center";
				for (let i = 1; i + 1 < pts.length; i += 2) {
					const dt = pts[i + 1][0] - pts[i][0];
					const dx = px[i + 1][0] - px[i][0], dy = px[i + 1][1] - px[i][1];
					if (dt <= 0 || Math.hypot(dx, dy) < annFont * 4) continue;
					g.fillText(fmtDur(dt), (px[i][0] + px[i + 1][0]) / 2 + 4, (px[i][1] + px[i + 1][1]) / 2 - 5);
				}
			}
			if (sl.show.travel && pts.length >= 4) {
				g.fillStyle = "#46c46e";
				g.textAlign = "left";
				const total = entry.tr.lastT - pts[0][0];
				const end = px[px.length - 1];
				g.fillText("Σ " + fmtDur(total), end[0] + 6, end[1] - 6);
			}
		}
	}
	if (sl.show.headway && B.headways) {
		g.font = annFont + "px " + mono;
		g.fillStyle = "#3fc1c9";
		g.textAlign = "center";
		const lastAt = new Map(); // dist row → last labeled x, to keep density sane
		for (const hw of B.headways) {
			if (hw.t < t0 || hw.t > tEnd) continue;
			const x = X(hw.t), y = Y(hw.dist);
			const prev = lastAt.get(hw.dist);
			if (prev !== undefined && x - prev < annFont * 3.6) continue;
			lastAt.set(hw.dist, x);
			g.fillText(fmtDur(hw.gap), x, y + annFont + 3);
		}
	}

	g.strokeStyle = border;
	g.lineWidth = 1;
	g.beginPath(); g.moveTo(pad.l, pad.t); g.lineTo(pad.l, pad.t + plotH); g.stroke();

	const tip = $("slTip");
	if (sl.hover && sl.mouse) {
		const tr = sl.hover.trace;
		const entry = sl.hover.entry;
		tip.classList.remove("hidden");
		tip.style.left = Math.min(w - 270, sl.mouse[0] + 14) + "px";
		tip.style.top = Math.min(h - 90, sl.mouse[1] + 12) + "px";
		const [devTxt, devCls] = fmtDev(entry.lastDev);
		tip.innerHTML = `<div class="t"><span class="chip" style="background:${tr.color}">${escapeHtml(tr.number)}</span> ` +
			`${escapeHtml(tr.routeName)}${tr.variant ? " · " + escapeHtml(tr.variant) : ""}</div>` +
			`<div>${entry.live ? "Live — click to follow on the map" : "Completed run"}</div>` +
			`<div>Last dev: <span class="${devCls}">${devTxt}</span></div>`;
	} else {
		tip.classList.add("hidden");
	}
	renderStringMeta(B.segment ? `segment: ${B.stations.length} stations, ${B.lineCount} line(s)` : null);
}

function segDist(p, a, b) {
	const dx = b[0] - a[0], dy = b[1] - a[1];
	const len2 = dx * dx + dy * dy || 1;
	const t = Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len2));
	return Math.hypot(p[0] - (a[0] + dx * t), p[1] - (a[1] + dy * t));
}

/* ---------- status ui ---------- */

function setStatus(s) {
	const el = $("connStatus");
	el.className = "status " + s;
	el.textContent = s === "live" ? "LIVE" : s === "connecting" ? "CONNECTING" : "OFFLINE";
}
function showBanner(msg) { const b = $("banner"); b.textContent = msg; b.classList.remove("hidden"); }
function hideBanner() { $("banner").classList.add("hidden"); }

/* ---------- demo mode (UI testing without a server) ---------- */

/** Synthetic analytics payload so the Analytics view and heat mode work with ?demo=1. */
function demoAnalytics() {
	const t = now();
	const series = (base, jitter, n) => {
		const out = [];
		for (let i = n; i > 0; i--) out.push([t - i * base, Math.max(20000, base + (Math.random() - 0.5) * jitter)]);
		return out;
	};
	const s1 = series(300000, 260000, 18);
	const s2 = series(600000, 200000, 6);
	return {
		schemaVersion: 1, enabled: true, dimension: "demo:overworld", computedAt: t - 4000,
		windowMinutes: 60, aggregateSeconds: 30, onTimeToleranceSeconds: 60, bunchingFraction: 0.5,
		departures: 24, recorded: 51, dropped: 0,
		lines: [
			{
				id: "rt1", name: "Demo Express|演示", number: "4", color: 0x00933c,
				departures: 18, onTime: 15, onTimePct: 83.3, avgDeviationMs: 21000,
				worstLateMs: 96000, worstEarlyMs: -4000, avgDwellMs: 13400,
				avgDwellOverrunMs: 3400, maxDwellOverrunMs: 21000, headwaySamples: s1.length,
				avgHeadwayMs: 305000, medianHeadwayMs: 300000, minHeadwayMs: 92000, maxHeadwayMs: 640000,
				refHeadwayMs: 300000, headwaySource: "scheduled",
				bunching: [{ atMs: t - 400000, platformId: "pl1", platform: "1", stationId: "st1", station: "Baker City Central|贝克城", gapMs: 92000 }],
				series: s1,
			},
			{
				id: "rt2", name: "Harbor Ferry", number: "F", color: 0x3fc1c9,
				departures: 6, onTime: 6, onTimePct: 100, avgDeviationMs: -2000,
				worstLateMs: 8000, worstEarlyMs: -19000, avgDwellMs: 9800,
				avgDwellOverrunMs: -200, maxDwellOverrunMs: 1500, headwaySamples: s2.length,
				avgHeadwayMs: 600000, medianHeadwayMs: 600000, minHeadwayMs: 520000, maxHeadwayMs: 690000,
				refHeadwayMs: 600000, headwaySource: "observed", bunching: [], series: s2,
			},
		],
		stations: [
			{ id: "st1", name: "Baker City Central|贝克城", departures: 14, onTimePct: 78.6, avgDwellOverrunMs: 18000, maxDwellOverrunMs: 41000, headwaySamples: 12, headwayIrregularity: 0.62 },
			{ id: "st2", name: "Harbor North", departures: 10, onTimePct: 100, avgDwellOverrunMs: 1200, maxDwellOverrunMs: 4000, headwaySamples: 8, headwayIrregularity: 0.11 },
		],
	};
}

/**
 * Synthetic stringline payload: a six-station line, both directions, departures every
 * 6 min over the last 90 min, so the chart shows crossing diagonals, flat dwells and
 * one long-dwell outlier without a server.
 */
function demoStringline() {
	const t = now();
	const staNames = ["Baker City Central|贝克城", "Canal St", "Union Sq", "Grand Ave", "Harbor North", "Airport"];
	const mkStations = (prefix, reversed) => {
		const list = [];
		const order = reversed ? [...staNames].reverse() : staNames;
		for (let i = 0; i < order.length; i++) {
			list.push({
				plat: prefix + (i + 1), platName: String(i + 1),
				sta: "st" + (reversed ? order.length - i : i + 1) + "d", staName: order[i],
				dist: i === 0 ? 0 : list[i - 1].dist + 400 + ((i * 137) % 260),
			});
		}
		return list;
	};
	const axis = {
		schemaVersion: 1, dimension: "demo:overworld",
		routes: [
			{ id: "rt1n", name: "Demo Express|演示||Northbound", number: "4", color: 0x00933c, hidden: false, stations: mkStations("pn", false) },
			{ id: "rt1s", name: "Demo Express|演示||Southbound", number: "4", color: 0x00933c, hidden: false, stations: mkStations("ps", true) },
			// interlined local sharing the Canal St–Grand Ave trunk (segment-mode demo)
			{ id: "rt7n", name: "7 Local||Northbound", number: "7", color: 0xb933ad, hidden: false, stations: [
				{ plat: "pq1", platName: "1", sta: "st2d", staName: "Canal St", dist: 0 },
				{ plat: "pq2", platName: "1", sta: "st3d", staName: "Union Sq", dist: 520 },
				{ plat: "pq3", platName: "1", sta: "st4d", staName: "Grand Ave", dist: 1030 },
			] },
		],
	};
	const deps = [];
	const runMs = 90 * 1000, dwellMs = 20 * 1000, headway = 6 * 60 * 1000;
	for (let dir = 0; dir < 2; dir++) {
		const prefix = dir === 0 ? "pn" : "ps";
		const routeId = dir === 0 ? "rt1n" : "rt1s";
		for (let n = 0; n < 16; n++) {
			const start = t - 95 * 60000 + n * headway + dir * headway / 2;
			const veh = "d" + prefix + n;
			for (let i = 0; i < 6; i++) {
				const slow = (n === 7 && i === 2) ? 90 * 1000 : 0; // one long-dwell outlier
				const depT = start + i * (runMs + dwellMs) + slow;
				if (depT > t) break;
				deps.push([veh, prefix + (i + 1), depT, dwellMs + slow, (n % 5 - 1) * 20000 + slow, i, routeId]);
			}
		}
	}
	for (let n = 0; n < 12; n++) {
		const start = t - 92 * 60000 + n * 8 * 60000;
		for (let i = 0; i < 3; i++) {
			const depT = start + i * (80 * 1000 + 15 * 1000);
			if (depT > t) break;
			deps.push(["d7_" + n, "pq" + (i + 1), depT, 15 * 1000, (n % 3) * 15000, i, "rt7n"]);
		}
	}
	deps.sort((a, b) => a[2] - b[2]);
	return { axis, deps, windowMinutes: 90, analyticsEnabled: true, now: t };
}

function bootDemo() {
	state.dims = ["demo:overworld"];
	state.updateMillis = 333;
	fillDimSelect();
	const P = (x, z) => [x, 64, z];
	const rails = [];
	const line = (id, pts, speed, opts = {}) => rails.push({
		id, mode: "train", length: 100, speedA: speed, speedB: opts.oneWay ? 0 : speed,
		platform: !!opts.platform, siding: !!opts.siding, canAccelerate: !opts.platform,
		canTurnBack: false, signalColors: opts.sig ? [16711680] : [], points: pts,
	});
	// a loop with two stations, curves, a siding stub
	// r1+r2 share a signal color AND touch at (120,0) → must consolidate to ONE dot
	line("r1", [P(0, 0), P(120, 0)], 80, { sig: true });
	line("r2", [P(120, 0), P(160, 10), P(180, 40)], 60, { sig: true });
	line("r3", [P(180, 40), P(180, 120)], 120, { sig: true });
	line("r4", [P(180, 120), P(160, 150), P(120, 160)], 60);
	line("r5", [P(120, 160), P(0, 160)], 80, { sig: true });
	line("r6", [P(0, 160), P(-40, 150), P(-60, 120)], 60);
	line("r7", [P(-60, 120), P(-60, 40)], 200);
	line("r8", [P(-60, 40), P(-40, 10), P(0, 0)], 60);
	line("p1", [P(30, 0), P(90, 0)], 80, { platform: true });
	line("p2", [P(30, 160), P(90, 160)], 80, { platform: true });
	line("s1", [P(-60, 80), P(-100, 80)], 30, { siding: true });
	// a boat line so the mode toggles appear in demo
	rails.push({ id: "b1", mode: "boat", length: 260, speedA: 30, speedB: 30, platform: false, siding: false, canAccelerate: true, canTurnBack: false, signalColors: [], points: [P(-40, 220), P(60, 240), P(160, 220)] });
	rails.push({ id: "b2", mode: "boat", length: 260, speedA: 30, speedB: 30, platform: false, siding: false, canAccelerate: true, canTurnBack: false, signalColors: [], points: [P(160, 220), P(60, 260), P(-40, 220)] });
	applyNetwork({
		schemaVersion: 1, dimension: "demo:overworld", dimensionIndex: 0, dimensions: state.dims,
		rails,
		stations: [
			{ id: "st1", name: "Baker City Central|贝克城", color: 0x4da3ff, bounds: [20, 60, -10, 100, 70, 10], platformIds: ["pl1"] },
			{ id: "st2", name: "Harbor North", color: 0xe5484d, bounds: [20, 60, 150, 100, 70, 170], platformIds: ["pl2"] },
		],
		platforms: [
			{ id: "pl1", name: "1", dwellMs: 10000, stationId: "st1", p1: [30, 64, 0], p2: [90, 64, 0], mid: [60, 64, 0], routeIds: ["rt1n"] },
			{ id: "pl2", name: "2", dwellMs: 10000, stationId: "st2", p1: [30, 64, 160], p2: [90, 64, 160], mid: [60, 64, 160], routeIds: ["rt1n", "rt1s"] },
		],
		routes: [
			{ id: "rt1n", name: "Demo Express|演示||Northbound", number: "4", color: 0x00933c, hidden: false },
			{ id: "rt1s", name: "Demo Express|演示||Southbound", number: "4", color: 0x00933c, hidden: false },
		],
	});
	fitView();
	setStatus("live");

	// two trains chasing each other around the loop
	const loop = ["r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8"];
	const trains = [
		{ id: "v1", li: 0, t: 0.2, kmh: 64, devMs: 12000 },
		{ id: "v2", li: 4, t: 0.6, kmh: 48, devMs: -22000 },
	];
	const boatLoop = ["b1", "b2"];
	const boat = { id: "v3", li: 0, t: 0.3, kmh: 22, devMs: 0 };
	let demoLastAlertSlot = -1;
	setInterval(() => {
		const st = now();
		boat.t += 0.02;
		if (boat.t >= 1) { boat.t -= 1; boat.li = (boat.li + 1) % boatLoop.length; }
		const bp = railPoint(boatLoop[boat.li], boat.t) || [0, 0];
		const boatVehicle = {
			id: boat.id, x: bp[0], y: 62, z: bp[1], kmh: boat.kmh, rev: false,
			rail: boatLoop[boat.li], railT: boat.t, doors: false, dwellMs: 0,
			devMs: 0, manual: false, stop: 0,
			route: { id: "rt2", name: "Harbor Ferry", number: "F", color: 0x3fc1c9, dest: "Harbor North", nextStation: "Harbor North" },
			consist: { sidingId: "sd2", siding: "Dock", depot: "Ferry Dock", cars: ["boat_1"], carLengths: [12] },
		};
		const vehicles = trains.map((tr, ti) => {
			tr.t += 0.04 * (tr.kmh / 60);
			if (tr.t >= 1) { tr.t -= 1; tr.li = (tr.li + 1) % loop.length; }
			const railId = loop[tr.li];
			const p = railPoint(railId, tr.t) || [0, 0];
			// stringline live tip: a phase gliding over the demo axis's pn1..pn6
			const phase = ((st / 1000 + ti * 270) % 540) / 540 * 5;
			const seg = Math.min(4, Math.floor(phase));
			return {
				id: tr.id, x: p[0], y: 64, z: p[1], kmh: tr.kmh + Math.sin(st / 3000) * 8,
				rev: false, rail: railId, railT: tr.t, doors: tr.t < 0.05, dwellMs: 0,
				devMs: tr.devMs, manual: tr.id === "v2", stop: tr.li,
				pPlat: "pn" + (seg + 1), nPlat: "pn" + (seg + 2), pFrac: Math.round((phase - seg) * 1000) / 1000,
				route: { id: "rt1n", name: "Demo Express|演示||Northbound", number: "4", color: 0x00933c, dest: "Harbor North", nextStation: tr.li < 4 ? "Harbor North" : "Baker City Central|贝克城" },
				consist: { sidingId: "sd1", siding: "S1", depot: "Demo Depot", cars: ["m7_a", "m7_b", "m7_b", "m7_a"], carLengths: [19.2, 19.2, 19.2, 19.2] },
			};
		});
		vehicles.push(boatVehicle);
		// holds pulse on platform 1 half the time; v2's doors get "obstructed" briefly
		const cycle = Math.floor(st / 20000) % 2 === 0;
		const frameObj = { schemaVersion: 1, serverTime: st, dimension: 0, vehicles, signals: [
			{ rail: "r1", occupied: [16711680], reserved: [] },
			{ rail: "r3", occupied: [], reserved: [16711680] },
		], signalsCleared: [], holds: cycle ? ["pl1"] : [], obstructed: Math.floor(st / 15000) % 3 === 0 ? ["v2"] : [] };
		if (Math.floor(st / 45000) !== demoLastAlertSlot) {
			demoLastAlertSlot = Math.floor(st / 45000);
			frameObj.alerts = [{ seq: demoLastAlertSlot, t: st, kind: ["late", "stalled", "hold_cap"][demoLastAlertSlot % 3],
				severity: demoLastAlertSlot % 3 === 1 ? "bad" : "warn", veh: "v1",
				detail: "Train 4 to Harbor North is running " + (2 + demoLastAlertSlot % 4) + "+ min behind schedule" }];
		}
		handleFrame(frameObj, false);
	}, 333);
	requestAnimationFrame(frame);
	setInterval(renderBoard, 1000);
}

boot();
