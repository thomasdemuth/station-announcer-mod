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
	follow: false,
	es: null,
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
	requestAnimationFrame(frame);
	setInterval(renderBoard, 1000);
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
		rec.samples.push({ t: f.serverTime, x: v.x, z: v.z, rail: v.rail, railT: v.railT });
		if (rec.samples.length > 4) rec.samples.shift();
		state.vehicles.set(v.id, rec);
	});

	(f.signals || []).forEach((s) => state.signals.set(s.rail, s));
	(f.signalsCleared || []).forEach((id) => state.signals.delete(id));

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
	// same rail on both samples → slide along the real curve
	if (a.rail && a.rail === b.rail && a.railT !== undefined && b.railT !== undefined) {
		const p = railPoint(a.rail, a.railT + (b.railT - a.railT) * f);
		if (p) return { x: p[0], z: p[1], hx: b.x - a.x, hz: b.z - a.z };
	}
	return { x: a.x + (b.x - a.x) * f, z: a.z + (b.z - a.z) * f, hx: b.x - a.x, hz: b.z - a.z };
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

	// platform labels
	if (zoomedIn) {
		g.font = "10px " + getComputedStyle(document.body).getPropertyValue("--mono");
		g.fillStyle = "#8fa3c4";
		g.textAlign = "center";
		for (const p of state.platforms.values()) {
			const [sx, sy] = worldToScreen(p.mid[0], p.mid[2]);
			g.fillText(firstLang(p.name), sx, sy - 6);
		}
	}

	// station labels
	if (state.layers.stations) {
		g.font = "600 12px system-ui";
		g.textAlign = "center";
		for (const st of state.stations) {
			const [sx] = worldToScreen((st.bounds[0] + st.bounds[3]) / 2, 0);
			const [, sy] = worldToScreen(0, st.bounds[2]);
			const label = firstLang(st.name);
			g.fillStyle = "#0b0e14cc";
			const tw = g.measureText(label).width;
			g.fillRect(sx - tw / 2 - 4, sy - 24, tw + 8, 16);
			g.fillStyle = "#e7ecf7";
			g.fillText(label, sx, sy - 12);
		}
	}
}

function frame() {
	// The analytics overlay covers the whole stage — don't burn frames drawing under it.
	if (state.analytics.view) { requestAnimationFrame(frame); return; }
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

	// vehicles
	const smoothing = 1 - Math.exp(-dtSec / 0.12); // critically-damped ease toward target
	for (const [id, rec] of state.vehicles) {
		if (!modeEnabled(vehicleMode(rec))) { rec.screen = null; continue; }
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
		const len = Math.max(10, Math.min(22, 6 * state.view.scale));

		if (state.follow && selected) { state.view.x = rec.disp.x; state.view.z = rec.disp.z; invalidateStatic(); }

		ctx.save();
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

		if (state.layers.trainLabels && state.view.scale > 0.5 && rec.route) {
			ctx.font = "700 10px " + getComputedStyle(document.body).getPropertyValue("--mono");
			ctx.textAlign = "center";
			const label = rec.route.number || firstLang(rec.route.name);
			ctx.fillStyle = "#0b0e14cc";
			const tw = ctx.measureText(label).width;
			ctx.fillRect(sx - tw / 2 - 3, sy - 20, tw + 6, 12);
			ctx.fillStyle = color;
			ctx.fillText(label, sx, sy - 11);
		}
	}

	// stale-stream indicator
	if (!DEMO && state.lastEventAt && now() - state.lastEventAt > 5000) setStatus("connecting");

	requestAnimationFrame(frame);
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
	$("closeDetail").onclick = () => { state.selected = null; state.follow = false; updateDetail(); };
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
	if (!best) state.follow = false;
	updateDetail();
	renderBoard();
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
	if (!rec) { el.classList.add("hidden"); return; }
	el.classList.remove("hidden");
	const r = rec.route || {};
	$("detailChip").textContent = r.number || "•";
	$("detailChip").style.background = r.color !== undefined ? colorHex(r.color) : "#5b6981";
	$("detailTitle").textContent = firstLang(r.name) || "Train";
	const d = rec.data;
	const [devTxt, devCls] = fmtDev(d.devMs);
	const dwellS = d.dwellMs > 0 ? Math.ceil(d.dwellMs / 1000) + "s" : "—";
	const rows = [
		["Destination", firstLang(r.dest) || "—"],
		["Next station", firstLang(r.nextStation) || "—"],
		["Speed", (d.kmh ?? 0).toFixed(1) + " km/h"],
		["Schedule", `<span class="${devCls}">${devTxt}</span>`],
		["Doors", d.doors ? "OPEN" : "closed"],
		["Dwell left", dwellS],
		["Mode", d.manual ? "MANUAL" : "ATO"],
	];
	if (rec.consist) rows.push(["Depot", firstLang(rec.consist.depot) || "—"]);
	let html = rows.map(([k, v]) => `<div class="row"><span class="k">${k}</span><span class="v">${v}</span></div>`).join("");
	if (rec.consist && rec.consist.cars) {
		html += `<div class="consist">${rec.consist.cars.length} car(s): ${rec.consist.cars.join(" + ")}</div>`;
	}
	$("detailBody").innerHTML = html;
}

/* ---------- dispatch board ---------- */

function boardRow(id, rec) {
	const r = rec.route || {};
	const d = rec.data;
	return {
		id,
		route: r.number || firstLang(r.name) || "?",
		color: r.color !== undefined ? colorHex(r.color) : "#5b6981",
		dest: firstLang(r.dest) || "—",
		next: firstLang(r.nextStation) || "—",
		kmh: d.kmh ?? 0,
		dev: d.devMs ?? 0,
		doors: d.doors ? "OPEN" : "",
		dwell: d.dwellMs > 0 ? Math.ceil(d.dwellMs / 1000) : 0,
		mode: d.manual ? "MAN" : "ATO",
	};
}

function renderBoard() {
	if ($("board").classList.contains("hidden")) return;
	const rows = [...state.vehicles.entries()]
		.filter(([, rec]) => modeEnabled(vehicleMode(rec)))
		.map(([id, rec]) => boardRow(id, rec));
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
			`<td>${row.dest}</td><td>${row.next}</td>` +
			`<td class="num">${row.kmh.toFixed(0)}</td>` +
			`<td class="num ${devCls}">${devTxt}</td>` +
			`<td>${row.doors}</td>` +
			`<td class="num">${row.dwell || "—"}</td>` +
			`<td>${row.mode}</td>`;
		tr.onclick = () => { state.selected = row.id; centerOn(row.id); updateDetail(); renderBoard(); };
		body.appendChild(tr);
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
			{ id: "pl1", name: "1", dwellMs: 10000, stationId: "st1", p1: [30, 64, 0], p2: [90, 64, 0], mid: [60, 64, 0], routeIds: ["rt1"] },
			{ id: "pl2", name: "2", dwellMs: 10000, stationId: "st2", p1: [30, 64, 160], p2: [90, 64, 160], mid: [60, 64, 160], routeIds: ["rt1"] },
		],
		routes: [{ id: "rt1", name: "Demo Express|演示", number: "4", color: 0x00933c, hidden: false }],
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
			consist: { sidingId: "sd2", siding: "Dock", depot: "Ferry Dock", cars: ["boat_1"] },
		};
		const vehicles = trains.map((tr) => {
			tr.t += 0.04 * (tr.kmh / 60);
			if (tr.t >= 1) { tr.t -= 1; tr.li = (tr.li + 1) % loop.length; }
			const railId = loop[tr.li];
			const p = railPoint(railId, tr.t) || [0, 0];
			return {
				id: tr.id, x: p[0], y: 64, z: p[1], kmh: tr.kmh + Math.sin(st / 3000) * 8,
				rev: false, rail: railId, railT: tr.t, doors: tr.t < 0.05, dwellMs: 0,
				devMs: tr.devMs, manual: tr.id === "v2", stop: tr.li,
				route: { id: "rt1", name: "Demo Express|演示", number: "4", color: 0x00933c, dest: "Harbor North", nextStation: tr.li < 4 ? "Harbor North" : "Baker City Central|贝克城" },
				consist: { sidingId: "sd1", siding: "S1", depot: "Demo Depot", cars: ["m7_a", "m7_b", "m7_b", "m7_a"] },
			};
		});
		vehicles.push(boatVehicle);
		handleFrame({ schemaVersion: 1, serverTime: st, dimension: 0, vehicles, signals: [
			{ rail: "r1", occupied: [16711680], reserved: [] },
			{ rail: "r3", occupied: [], reserved: [16711680] },
		], signalsCleared: [] }, false);
	}, 333);
	requestAnimationFrame(frame);
	setInterval(renderBoard, 1000);
}

boot();
