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
	vehicles: new Map(),     // id -> {data, samples:[{t,x,z,railT,rail}], route, consist}
	signals: new Map(),      // railId -> {occupied:[], reserved:[]}
	selected: null,
	follow: false,
	es: null,
	lastEventAt: 0,
	view: { x: 0, z: 0, scale: 1 },  // world center + px per block
	layers: { speed: true, signals: true, stations: true, trainLabels: true },
	sort: { k: "route", asc: true },
	networkBox: null,
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
	invalidateStatic();
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

	// station areas
	for (const st of state.stations) {
		const [x1, y1] = worldToScreen(st.bounds[0], st.bounds[2]);
		const [x2, y2] = worldToScreen(st.bounds[3] + 1, st.bounds[5] + 1);
		g.fillStyle = colorHex(st.color) + "1f";
		g.strokeStyle = colorHex(st.color) + "66";
		g.lineWidth = 1;
		g.beginPath(); g.roundRect(x1, y1, x2 - x1, y2 - y1, 4); g.fill(); g.stroke();
	}

	// rails
	for (const r of state.rails.values()) {
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
	const dpr = resize();
	if (staticDirty) { drawStatic(dpr); staticDirty = false; }
	ctx.setTransform(1, 0, 0, 1, 0, 0);
	ctx.clearRect(0, 0, canvas.width, canvas.height);
	ctx.drawImage(staticCanvas, 0, 0);
	ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

	const renderTime = now() - state.updateMillis * 1.5;

	// signals
	if (state.layers.signals) {
		for (const r of state.rails.values()) {
			if (!r.signalColors || r.signalColors.length === 0) continue;
			const live = state.signals.get(r.id);
			const aspect = live && live.occupied && live.occupied.length ? "#e5484d"
				: live && live.reserved && live.reserved.length ? "#f5b942" : "#46c46e";
			const mid = railPoint(r.id, 0.5);
			if (!mid) continue;
			const [sx, sy] = worldToScreen(mid[0], mid[1]);
			ctx.beginPath(); ctx.arc(sx, sy, 3.5, 0, Math.PI * 2);
			ctx.fillStyle = aspect; ctx.fill();
			ctx.strokeStyle = "#0b0e14"; ctx.lineWidth = 1; ctx.stroke();
		}
	}

	// vehicles
	for (const [id, rec] of state.vehicles) {
		const p = vehiclePos(rec, renderTime);
		if (!p) continue;
		rec.screen = worldToScreen(p.x, p.z);
		const [sx, sy] = rec.screen;
		const angle = Math.atan2(p.hz || 0, p.hx || 1);
		const color = rec.route ? colorHex(rec.route.color) : "#9aa7bf";
		const selected = id === state.selected;
		const len = Math.max(10, Math.min(22, 6 * state.view.scale));

		if (state.follow && selected) { state.view.x = p.x; state.view.z = p.z; invalidateStatic(); }

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
	const rows = [...state.vehicles.entries()].map(([id, rec]) => boardRow(id, rec));
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

/* ---------- status ui ---------- */

function setStatus(s) {
	const el = $("connStatus");
	el.className = "status " + s;
	el.textContent = s === "live" ? "LIVE" : s === "connecting" ? "CONNECTING" : "OFFLINE";
}
function showBanner(msg) { const b = $("banner"); b.textContent = msg; b.classList.remove("hidden"); }
function hideBanner() { $("banner").classList.add("hidden"); }

/* ---------- demo mode (UI testing without a server) ---------- */

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
	line("r1", [P(0, 0), P(120, 0)], 80, { sig: true });
	line("r2", [P(120, 0), P(160, 10), P(180, 40)], 60);
	line("r3", [P(180, 40), P(180, 120)], 120, { sig: true });
	line("r4", [P(180, 120), P(160, 150), P(120, 160)], 60);
	line("r5", [P(120, 160), P(0, 160)], 80, { sig: true });
	line("r6", [P(0, 160), P(-40, 150), P(-60, 120)], 60);
	line("r7", [P(-60, 120), P(-60, 40)], 200);
	line("r8", [P(-60, 40), P(-40, 10), P(0, 0)], 60);
	line("p1", [P(30, 0), P(90, 0)], 80, { platform: true });
	line("p2", [P(30, 160), P(90, 160)], 80, { platform: true });
	line("s1", [P(-60, 80), P(-100, 80)], 30, { siding: true });
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
	setInterval(() => {
		const st = now();
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
		handleFrame({ schemaVersion: 1, serverTime: st, dimension: 0, vehicles, signals: [
			{ rail: "r1", occupied: [16711680], reserved: [] },
			{ rail: "r3", occupied: [], reserved: [16711680] },
		], signalsCleared: [] }, false);
	}, 333);
	requestAnimationFrame(frame);
	setInterval(renderBoard, 1000);
}

boot();
