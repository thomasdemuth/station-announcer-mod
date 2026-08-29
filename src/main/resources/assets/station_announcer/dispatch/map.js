"use strict";
/*
 * System Map+ — rider-facing system map and journey planner for the Station Announcer
 * MTR addon. Vanilla JS + canvas 2D, no build step, no dependencies.
 *
 * It is a sibling of app.js (the dark dispatch ops UI) and deliberately shares NO code
 * with it — only its engineering patterns: the same fetch envelope unwrap, the same SSE
 * merge + sample interpolation, the same dpr-aware canvas with a cached offscreen static
 * layer, the same pan/zoom maths. The look comes from tools/nextmap_mock/index.html.
 *
 * Endpoints (all under /dispatch/, envelope {"code":200,...,"data":{...}}):
 *   api/ping                     dimensions + stream cadence
 *   api/network?dimension=N      rails, stations, platforms, routes
 *   api/mapdata?dimension=N      routes with baked legs/durations, station parts + walks
 *   api/terrain?dimension=N      water polygons
 *   api/stream?dimension=N       SSE: full / delta vehicle frames
 *
 * ?demo=1 builds a synthetic city in JS (no fetches, no SSE) and is the acceptance
 * harness: every rendering feature below is exercisable there.
 */

/* ============================================================================
 * 1. constants + state
 * ========================================================================== */

const SCHEMA_VERSION = 1;
const API = "api";
const DEMO = new URLSearchParams(location.search).get("demo") === "1";
const PREFS_KEY = "sa_mapplus_prefs";

/**
 * PALETTES. The canvas cannot read CSS custom properties, so every colour the 2D
 * context uses lives here and the SAME values are mirrored in map.css as vars on
 * :root / :root[data-theme="dark"]. Switching the theme swaps this object and
 * invalidates the static layer; the DOM follows through the data-theme attribute.
 *
 * `dim` is the alpha the rest of the network fades to while a journey is selected —
 * higher in dark mode, because a 15 % white line on near-black disappears.
 */
const THEMES = {
	light: {
		paper: "#f6f3ec", water: "#bcd6ea", waterStroke: "#a3c2da",
		ink: "#1c1f23", ink2: "#5b6470", ink3: "#8a929c",
		accent: "#1a73e8", pin: "#d93025", live: "#0f9d58",
		chip: "#ffffff", chipInk: "#3c434b", chipShadow: "rgba(20,28,40,0.22)",
		glyphFill: "#ffffff", glyphStroke: "#1c1f23",
		leader: "#9aa2ab", rawRail: "#9aa2ab", dim: 0.15,
	},
	dark: {
		paper: "#101014", water: "#1b2a38", waterStroke: "#24384a",
		ink: "#f2f3f5", ink2: "#aab3bf", ink3: "#79838f",
		accent: "#6aa8ff", pin: "#ff5f52", live: "#3ddc84",
		chip: "#1a1b20", chipInk: "#dfe3e8", chipShadow: "rgba(0,0,0,0.55)",
		glyphFill: "#ffffff", glyphStroke: "#0b0b0f",
		leader: "#5c6672", rawRail: "#5c6672", dim: 0.22,
	},
};
let PALETTE = THEMES.light;

/** Walking speed used for transfer/walk timings (m/s) + a fixed platform-change buffer. */
const WALK_SPEED = 1.4;
const WALK_BUFFER_S = 30;

/* ---- schematic drawing constants (section 5) ---- */
/** Points every drawn segment is resampled to (pair averaging is pointwise). */
const SEG_SAMPLES = 32;
/** Blocks. "These two polylines run in the same corridor." Used by both the express
 *  coverage test and cross-colour bundling. */
const CORRIDOR_TOL = 14;
/** Fraction of an express segment that must be covered by the line's own shorter
 *  segments before it is suppressed. */
const COVER_FRACTION = 0.85;
/** Fraction of the SHORTER segment's samples that must sit inside CORRIDOR_TOL of the
 *  other before two different-colour segments count as bundle companions. */
const COMPANION_FRACTION = 0.6;
/** Blocks between the samples used for coverage / companion tests. */
const SAMPLE_STEP = 8;
/** Uniform spatial grid cell for the companion broad phase. */
const GRID_CELL = 16;
/** A drawn train is snapped onto its line's nearest schematic segment within this. */
const VEHICLE_SNAP = 24;
/** Bullets a single interlining chip will draw. */
const MAX_CHIP_BULLETS = 4;
/** Line-thickness multiplier bounds (the settings slider). */
const LINE_SCALE_MIN = 0.6;
const LINE_SCALE_MAX = 1.6;

const state = {
	/* --- server / dimension --- */
	dims: [],
	dim: 0,
	updateMillis: 333,
	status: "connecting",          // connecting | live | offline
	es: null,
	lastEventAt: 0,
	lastServerTime: 0,
	emaInterval: 0,
	lastFrameAt: 0,

	/* --- raw payloads --- */
	network: null,
	mapdata: null,
	terrain: null,                 // {polygons: [[[x,z],...], ...]}

	/* --- indexed data --- */
	rails: new Map(),              // railId -> {id, mode, pts:[[x,z]], cum, len, bbox, speed, ...}
	stations: new Map(),           // stationId -> {id, name, display, color, hex, accessible,
	                               //               bounds, parts:[], partWalks:[], platformDistances:[]}
	platforms: new Map(),          // platformId -> {id, name, stationId, partId, xz:[x,z], dir:[dx,dz],
	                               //                accessible, dwellMs, routeIds:[]}
	routes: new Map(),             // routeId -> {id, name, display, dest, number, color, hex, hidden,
	                               //             mode, platforms:[], legs:[], durations:[], headwayMs}

	/* --- derived drawing geometry (rebuilt by prepareGeometry) --- */
	legs: new Map(),               // "routeId|i" -> {routeId, i, from, to, pts, rails, straight, meters, seconds}
	parts: new Map(),              // partId -> {part, station}
	lines: new Map(),              // hex -> {hex, colorInt, routeIds:[], serviceLabels:[], modes:Set}
	ribbons: [],                   // DRAWN schematic segments (see buildSegments)
	segByPair: new Map(),          // "hex|partA>partB" -> segment (drawn OR suppressed)
	segsByColor: new Map(),        // hex -> [drawn segment]
	bundles: [],                   // interlining chips: [{x, z, nx, nz, colors:[{hex, numbers:[]}]}]
	glyphs: [],                    // [{stationId, partId, x, z, colors:[hex], capsule, dir, accessible, weight}]
	walks: [],                     // [{ax, az, bx, bz, dist}]
	networkBox: null,

	/* --- live --- */
	vehicles: new Map(),           // id -> {data, samples, route, consist, disp, screen}

	/* --- view / interaction --- */
	view: { x: 0, z: 0, scale: 1 },
	hover: { trainId: null, glyph: null, x: 0, y: 0 },
	selection: null,               // see selectJourney()

	/* --- planner --- */
	plan: {
		from: null,                // {stationId, partId|null}
		to: null,
		when: { mode: "now", at: null },       // at = "HH:MM"
		prefs: { mode: "fastest", stepFree: false },
		journeys: [],
		selectedIndex: -1,
		updatedAt: 0,
		lastBucket: -1,            // minute bucket the current plan was made for
		graph: null,
	},

	prefs: {
		showHidden: false,
		theme: "light",            // light | dark  (settings menu)
		lineScale: 1,              // ribbon width multiplier, LINE_SCALE_MIN..MAX
		hiddenModes: [],           // transport modes switched off in the Layers list
	},
};

/* ============================================================================
 * 2. helpers
 * ========================================================================== */

const $ = (id) => document.getElementById(id);
const now = () => Date.now();
/** MTR names are "English|Other" — always show the first language. */
const firstLang = (s) => (s || "").split("|")[0];
/** MTR route names are "Name||Destination". */
const routeBase = (s) => firstLang((s || "").split("||")[0]);
const routeDest = (s) => firstLang((s || "").split("||")[1] || "");
const colorHex = (c) => "#" + ((c || 0) & 0xFFFFFF).toString(16).padStart(6, "0");
/** Canvas text/marker scale for big monitors (CSS media queries handle the DOM side). */
const uiScale = () => Math.min(1.45, Math.max(1, window.innerWidth / 1600));
const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
const dist = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1]);

function esc(s) {
	return String(s == null ? "" : s).replace(/[&<>"']/g, (c) =>
		({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

/** 6:33 — no am/pm, matching the mock. */
function fmtTime(ms) {
	const d = new Date(ms);
	const h = d.getHours() % 12 || 12;
	return h + ":" + String(d.getMinutes()).padStart(2, "0");
}
function fmtMin(seconds) { return Math.max(1, Math.round(seconds / 60)) + " min"; }
function fmtMeters(m) { return Math.round(m) + " m"; }

/** Catmull-Rom resample — used by demo geometry so synthetic track reads as track. */
function smooth(pts, steps = 10) {
	if (pts.length < 3) return pts.map((p) => p.slice());
	const P = (i) => pts[clamp(i, 0, pts.length - 1)];
	const out = [];
	for (let i = 0; i < pts.length - 1; i++) {
		const p0 = P(i - 1), p1 = P(i), p2 = P(i + 1), p3 = P(i + 2);
		for (let s = 0; s < steps; s++) {
			const t = s / steps, t2 = t * t, t3 = t2 * t;
			out.push([
				0.5 * (2 * p1[0] + (-p0[0] + p2[0]) * t + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2 + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3),
				0.5 * (2 * p1[1] + (-p0[1] + p2[1]) * t + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2 + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3),
			]);
		}
	}
	out.push(pts[pts.length - 1].slice());
	return out;
}

function polylineLength(pts) {
	let d = 0;
	for (let i = 1; i < pts.length; i++) d += dist(pts[i - 1], pts[i]);
	return d;
}

/* ============================================================================
 * 3. canvas icon paths (24x24, same art as the <symbol> sheet in map.html)
 * ========================================================================== */

const P_TRAIN = new Path2D("M12 2c-4 0-8 .5-8 4v9.5C4 17.43 5.57 19 7.5 19L6 20.5v.5h2.23l2-2H14l2 2H18v-.5L16.5 19c1.93 0 3.5-1.57 3.5-3.5V6c0-3.5-4-4-8-4zm-5.5 15c-.83 0-1.5-.67-1.5-1.5S5.67 14 6.5 14s1.5.67 1.5 1.5S7.33 17 6.5 17zm4.5-7H6V6h5v4zm2 0V6h5v4h-5zm4.5 7c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5z");
const P_WALK = new Path2D("M13.5 5.5c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zM9.8 8.9 7 23h2.1l1.8-8 2.1 2v6h2v-7.5l-2.1-2 .6-3C12.8 12 14.8 13 17 13v-2c-1.9 0-3.5-1-4.3-2.4l-1-1.6c-.4-.6-1-1-1.7-1-.3 0-.5.1-.8.1L4 8.3V13h2V9.6l1.8-.7");
const P_BOAT = new Path2D("M20 21c-1.39 0-2.78-.47-4-1.32-2.44 1.71-5.56 1.71-8 0C6.78 20.53 5.39 21 4 21H2v2h2c1.38 0 2.74-.35 4-.99 2.52 1.29 5.48 1.29 8 0 1.26.65 2.62.99 4 .99h2v-2h-2zM3.95 19H4c1.6 0 3.02-.88 4-2 .98 1.12 2.4 2 4 2s3.02-.88 4-2c.98 1.12 2.4 2 4 2h.05l1.89-6.68c.08-.26.06-.54-.06-.78s-.34-.42-.6-.5L20 10.62V6c0-1.1-.9-2-2-2h-3V1H9v3H6c-1.1 0-2 .9-2 2v4.62l-1.29.42c-.26.08-.48.26-.6.5s-.15.52-.06.78L3.95 19zM6 6h12v3.97L12 8 6 9.97V6z");
/** Destination pin, authored with its tip at (0,0). */
const P_PIN = new Path2D("M0,0 C0,0 15,-10.5 15,-19.5 C15,-27.5 9,-34 0,-34 C-9,-34 -15,-27.5 -15,-19.5 C-15,-10.5 0,0 0,0 Z");

/** The step-free badge (Thomas's artwork), drawn on the map and used in the DOM. */
const ACCESS_ICON = new Image();
ACCESS_ICON.onload = () => invalidateStatic();
ACCESS_ICON.src = "access_badge.svg";
const ACCESS_IMG = '<svg class="badge-14"><use href="#i-badge"/></svg>';

function drawIcon(g, path, x, y, size, color) {
	g.save();
	g.translate(x, y);
	g.scale(size / 24, size / 24);
	g.translate(-12, -12);
	g.fillStyle = color;
	g.fill(path);
	g.restore();
}

/** White rounded chip with the mock's soft drop shadow. */
function chipRect(g, x, y, w, h, r) {
	g.save();
	g.shadowColor = PALETTE.chipShadow;
	g.shadowBlur = 6;
	g.shadowOffsetY = 2;
	g.fillStyle = PALETTE.chip;
	g.beginPath();
	g.roundRect(x, y, w, h, r);
	g.fill();
	g.restore();
}

/* ============================================================================
 * 4. boot + data loading
 * ========================================================================== */

async function boot() {
	loadPrefs();
	initSettings();          // theme first: applied before anything paints
	initUi();
	initPlanner();
	if (DEMO) { bootDemo(); return; }
	try {
		const ping = await (await fetch(`${API}/ping`)).json();
		const p = ping.data || ping;
		if (p.schemaVersion && p.schemaVersion !== SCHEMA_VERSION) {
			showBanner(`Schema mismatch: server v${p.schemaVersion}, map v${SCHEMA_VERSION} — update the mod or hard-refresh`);
		}
		state.dims = p.dimensions || [];
		state.updateMillis = p.updateMillis || 333;
		const saved = clamp(state.prefs.dim | 0, 0, Math.max(0, state.dims.length - 1));
		await loadDimension(saved);
	} catch (e) {
		setStatus("offline");
		showBanner("Dispatch backend unreachable — is the server (and its MTR webserver) running?");
		setTimeout(boot, 5000);
		return;
	}
	setInterval(refetchNetwork, 60000);
	requestAnimationFrame(frame);
	setInterval(tickLive, 1000);
}

async function loadDimension(n) {
	state.dim = n;
	state.prefs.dim = n;
	savePrefs();
	state.vehicles.clear();
	clearSelection();
	if (state.es) { state.es.close(); state.es = null; }

	// network + mapdata + terrain in parallel — mapdata and terrain are optional, the
	// map degrades to raw rails / no water rather than failing.
	const [net, md, terr] = await Promise.all([
		fetchJson(`${API}/network?dimension=${n}`),
		fetchJson(`${API}/mapdata?dimension=${n}`).catch(() => null),
		fetchJson(`${API}/terrain?dimension=${n}`).catch(() => null),
	]);
	applyNetwork(net);
	if (md) applyMapdata(md);
	if (terr) applyTerrain(terr);
	prepareGeometry();
	fitView();
	openStream();
	hideBanner();
}

async function fetchJson(url) {
	const body = await (await fetch(url)).json();
	return body.data || body;   // envelope unwrap, exactly like app.js
}

async function refetchNetwork() {
	if (DEMO || document.hidden) return;
	try {
		const [net, md] = await Promise.all([
			fetchJson(`${API}/network?dimension=${state.dim}`),
			fetchJson(`${API}/mapdata?dimension=${state.dim}`).catch(() => null),
		]);
		applyNetwork(net);
		if (md) applyMapdata(md);
		prepareGeometry();
	} catch (e) { /* transient — the stream status covers visibility */ }
}

function applyNetwork(net) {
	state.network = net;
	state.rails.clear();
	state.platforms.clear();
	state.routes.clear();
	state.stations.clear();
	if (net.dimensions) state.dims = net.dimensions;

	let minX = Infinity, minZ = Infinity, maxX = -Infinity, maxZ = -Infinity;
	for (const r of net.rails || []) {
		const pts = (r.points || []).map((p) => [p[0], p[2]]);   // drop y: this is a plan view
		if (pts.length < 2) continue;
		const cum = [0];
		let bMinX = Infinity, bMinZ = Infinity, bMaxX = -Infinity, bMaxZ = -Infinity;
		for (let i = 0; i < pts.length; i++) {
			if (i) cum.push(cum[i - 1] + dist(pts[i - 1], pts[i]));
			bMinX = Math.min(bMinX, pts[i][0]); bMaxX = Math.max(bMaxX, pts[i][0]);
			bMinZ = Math.min(bMinZ, pts[i][1]); bMaxZ = Math.max(bMaxZ, pts[i][1]);
		}
		minX = Math.min(minX, bMinX); maxX = Math.max(maxX, bMaxX);
		minZ = Math.min(minZ, bMinZ); maxZ = Math.max(maxZ, bMaxZ);
		state.rails.set(r.id, {
			id: r.id, mode: r.mode || "train", pts, cum, len: cum[cum.length - 1],
			bbox: [bMinX, bMinZ, bMaxX, bMaxZ],
			speed: Math.max(r.speedA || 0, r.speedB || 0) || 60,
			platform: !!r.platform, siding: !!r.siding,
		});
	}
	state.networkBox = maxX < minX ? null : { minX, minZ, maxX, maxZ };

	for (const r of net.routes || []) {
		state.routes.set(r.id, {
			id: r.id, name: r.name, display: routeBase(r.name), dest: routeDest(r.name),
			number: r.number || "", color: r.color || 0, hex: colorHex(r.color),
			hidden: !!r.hidden, mode: "train",
			platforms: [], legs: [], durations: [], durationsValid: false, headwayMs: 0,
		});
	}
	for (const p of net.platforms || []) {
		const dir = p.p1 && p.p2 ? [p.p2[0] - p.p1[0], p.p2[2] - p.p1[2]] : [1, 0];
		state.platforms.set(p.id, {
			id: p.id, name: firstLang(p.name), stationId: p.stationId, partId: null,
			xz: p.mid ? [p.mid[0], p.mid[2]] : [0, 0], dir,
			accessible: !!p.accessible, dwellMs: p.dwellMs || 0, routeIds: p.routeIds || [],
		});
	}
	for (const s of net.stations || []) {
		state.stations.set(s.id, {
			id: s.id, name: s.name, display: firstLang(s.name),
			color: s.color || 0, hex: colorHex(s.color),
			accessible: !!s.accessible, accessiblePlatforms: s.accessiblePlatforms || null,
			bounds: s.bounds || null, platformIds: s.platformIds || [],
			parts: [], partWalks: [], platformDistances: [],
		});
	}
	state.plan.graph = null;
}

function applyMapdata(md) {
	state.mapdata = md;
	for (const r of md.routes || []) {
		let rt = state.routes.get(r.id);
		if (!rt) {
			rt = { id: r.id, name: r.name, display: routeBase(r.name), dest: routeDest(r.name),
				number: r.number || "", color: r.color || 0, hex: colorHex(r.color), hidden: !!r.hidden };
			state.routes.set(r.id, rt);
		}
		rt.mode = r.mode || "train";
		rt.platforms = r.platforms || [];
		rt.legs = r.legs || [];
		rt.durations = r.durations || [];
		rt.durationsValid = !!r.durationsValid;
		rt.headwayMs = r.headwayMs || 0;
	}

	for (const s of md.stations || []) {
		let st = state.stations.get(s.id);
		if (!st) {
			st = { id: s.id, name: s.name, display: firstLang(s.name), color: s.color || 0,
				hex: colorHex(s.color), bounds: null, platformIds: [], parts: [], partWalks: [], platformDistances: [] };
			state.stations.set(s.id, st);
		}
		if (s.accessible !== undefined) st.accessible = !!s.accessible;
		if (s.accessiblePlatforms) st.accessiblePlatforms = s.accessiblePlatforms;
		st.partWalks = s.partWalks || [];
		st.platformDistances = s.platformDistances || [];
		st.platformDistancesTruncated = !!s.platformDistancesTruncated;

		// platforms carried by mapdata fill in anything the network payload lacked
		for (const p of s.platforms || []) {
			let pl = state.platforms.get(p.id);
			if (!pl) {
				pl = { id: p.id, name: "", stationId: s.id, xz: [0, 0], dir: [1, 0], routeIds: [] };
				state.platforms.set(p.id, pl);
			}
			pl.stationId = s.id;
			if (p.mid) pl.xz = [p.mid[0], p.mid[2]];
			if (p.accessible !== undefined) pl.accessible = !!p.accessible;
			if (p.dwellMs !== undefined) pl.dwellMs = p.dwellMs;
			if (!st.platformIds.includes(p.id)) st.platformIds.push(p.id);
		}

		st.parts = (s.parts || []).map((part, i) => ({
			id: part.id || `${s.id}:${i}`,
			index: i,
			// `name` is not in the API contract; demo data supplies it and real payloads
			// fall back to a height-delta sub-label computed in prepareGeometry().
			name: part.name || null,
			platforms: part.platforms || [],
			x: part.centroid ? part.centroid[0] : 0,
			z: part.centroid ? part.centroid[2] : 0,
			y: part.y || 0,
		}));
		for (const part of st.parts) for (const pid of part.platforms) {
			const pl = state.platforms.get(pid);
			if (pl) pl.partId = part.id;
		}
	}

	// route ids per platform: mapdata is authoritative about which route calls where
	for (const rt of state.routes.values()) {
		for (const pid of rt.platforms) {
			const pl = state.platforms.get(pid);
			if (pl && !pl.routeIds.includes(rt.id)) pl.routeIds.push(rt.id);
		}
	}
	state.plan.graph = null;
}

function applyTerrain(t) {
	const polys = (t.polygons || []).filter((p) => p && p.length >= 3);
	state.terrain = polys.length ? { polygons: polys, scannedAt: t.scannedAt || 0 } : null;
}

/* ============================================================================
 * 5. geometry preparation (legs -> ribbons -> glyphs -> labels)
 * ========================================================================== */

function prepareGeometry() {
	ensureParts();
	indexParts();
	buildLegs();
	buildLines();
	buildSegments();
	buildGlyphs();
	buildWalks();
	state.plan.graph = null;
	rebuildSearchIndex();
	renderLayerList();
	invalidateStatic();
}

/** partId -> {part, station}, so segment building can reach a part centroid by id. */
function indexParts() {
	state.parts = new Map();
	for (const st of state.stations.values()) {
		for (const p of st.parts) state.parts.set(p.id, { part: p, station: st });
	}
}

function partCentroid(partId) {
	const rec = state.parts.get(partId);
	return rec ? [rec.part.x, rec.part.z] : null;
}
function partOfPlatform(platformId) {
	const pl = state.platforms.get(platformId);
	return pl ? pl.partId : null;
}
/** Canonical (order-independent) key for the pair of station parts a leg connects. */
function pairKeyOf(a, b) { return a < b ? a + ">" + b : b + ">" + a; }

/** Every station needs at least one part; stations without mapdata get one from bounds. */
function ensureParts() {
	for (const st of state.stations.values()) {
		if (!st.parts.length) {
			const b = st.bounds;
			const pts = st.platformIds.map((id) => state.platforms.get(id)).filter(Boolean).map((p) => p.xz);
			let x, z;
			if (b) { x = (b[0] + b[3]) / 2; z = (b[2] + b[5]) / 2; }
			else if (pts.length) {
				x = pts.reduce((a, p) => a + p[0], 0) / pts.length;
				z = pts.reduce((a, p) => a + p[1], 0) / pts.length;
			} else continue;
			st.parts = [{ id: st.id + ":0", index: 0, name: null, platforms: st.platformIds.slice(), x, z, y: b ? b[1] : 0 }];
			for (const pid of st.platformIds) {
				const pl = state.platforms.get(pid);
				if (pl) pl.partId = st.parts[0].id;
			}
		}
		// the "main" part carries the station name; the others get a sub-label
		let main = st.parts[0];
		for (const p of st.parts) if (p.platforms.length > main.platforms.length) main = p;
		st.mainPartId = main.id;
		for (const p of st.parts) {
			if (p.id === main.id) { p.sub = null; continue; }
			const dy = Math.round((p.y || 0) - (main.y || 0));
			p.sub = p.name || (dy ? (dy > 0 ? "+" : "−") + Math.abs(dy) + " blocks" : "Part " + (p.index + 1));
		}
		if (main.name) main.sub = null;
	}
}

/**
 * Orient and concatenate a leg's rails into one polyline.
 *
 * A rail's `points` have their own canonical direction, which need not agree with the
 * direction the route travels. For a multi-rail leg the FIRST rail is oriented so its
 * end sits nearest one of the second rail's endpoints; every following rail is flipped
 * if its far end is the closer one. A single-rail leg is oriented from the boarding
 * platform. Finally the whole chain is reversed if it clearly runs backwards relative
 * to the boarding platform (the global sanity check — it costs one comparison and
 * catches short platform rails whose local test is ambiguous).
 */
function chainRails(railIds, startAnchor) {
	const segs = [];
	for (const id of railIds) {
		const r = state.rails.get(id);
		if (r && r.pts.length >= 2) segs.push(r.pts);
	}
	if (!segs.length) return null;

	let first = segs[0];
	if (segs.length > 1) {
		const nxt = segs[1];
		const endA = first[0], endB = first[first.length - 1];
		const dB = Math.min(dist(endB, nxt[0]), dist(endB, nxt[nxt.length - 1]));
		const dA = Math.min(dist(endA, nxt[0]), dist(endA, nxt[nxt.length - 1]));
		if (dA < dB) first = first.slice().reverse();
	} else if (startAnchor) {
		if (dist(first[first.length - 1], startAnchor) < dist(first[0], startAnchor)) first = first.slice().reverse();
	}

	const out = first.slice();
	for (let i = 1; i < segs.length; i++) {
		let s = segs[i];
		const tail = out[out.length - 1];
		if (dist(tail, s[s.length - 1]) < dist(tail, s[0])) s = s.slice().reverse();
		out.push(...(dist(tail, s[0]) < 0.75 ? s.slice(1) : s));
	}

	if (startAnchor && out.length > 1) {
		const dStart = dist(out[0], startAnchor);
		const dEnd = dist(out[out.length - 1], startAnchor);
		if (dEnd + 8 < dStart) out.reverse();
	}
	return out;
}

/** Seconds for leg `i` of a route, honouring the two documented `durations` layouts. */
function legSeconds(rt, i, meters, speedKmh) {
	const d = rt.durations || [];
	if (rt.durationsValid && d.length > i && d[i] > 0) return d[i] / 1000;
	// MTR attributes an inbound leg to slot 0 for a depot's later routes: when the array
	// is one longer than the leg count, leg i's time lives at i+1.
	if (!rt.durationsValid && d.length === (rt.platforms || []).length && d.length > i + 1 && d[i + 1] > 0) {
		return d[i + 1] / 1000;
	}
	// estimate: 60 % of the line speed, never below half a minute
	const mps = Math.max(1, (speedKmh || 60) * 0.6 / 3.6);
	return Math.max(30, meters / mps);
}

function buildLegs() {
	state.legs.clear();
	for (const rt of state.routes.values()) {
		const plats = rt.platforms || [];
		for (let i = 0; i < plats.length - 1; i++) {
			const from = state.platforms.get(plats[i]);
			const to = state.platforms.get(plats[i + 1]);
			if (!from || !to) continue;
			const railIds = (rt.legs && rt.legs[i] && rt.legs[i].rails) ? rt.legs[i].rails : [];
			let pts = railIds.length ? chainRails(railIds, from.xz) : null;
			let straight = false;
			if (!pts || pts.length < 2) {
				// no baked rails (nothing generated yet, or an unreachable stop): the leg
				// is drawn as the straight line between the two platform midpoints.
				pts = [from.xz.slice(), to.xz.slice()];
				straight = true;
			}
			let speed = 0;
			for (const id of railIds) {
				const r = state.rails.get(id);
				if (r) speed = Math.max(speed, r.speed);
			}
			const meters = polylineLength(pts);
			state.legs.set(rt.id + "|" + i, {
				routeId: rt.id, i, from: from.id, to: to.id,
				pts, rails: straight ? [] : railIds, straight,
				meters, seconds: legSeconds(rt, i, meters, speed),
			});
		}
	}
}

/** Routes we actually paint (hidden ones only when the pref is on, modes the Layers
 *  list has switched off never). */
function visibleRoutes() {
	const out = [];
	for (const rt of state.routes.values()) {
		if (rt.hidden && !state.prefs.showHidden) continue;
		if (!rt.platforms || rt.platforms.length < 2) continue;
		if (!modeVisible(rt.mode || "train")) continue;
		out.push(rt);
	}
	return out;
}

/** Routes that exist for the rider, ignoring the Layers filter (lines, mode list). */
function candidateRoutes() {
	const out = [];
	for (const rt of state.routes.values()) {
		if (rt.hidden && !state.prefs.showHidden) continue;
		if (!rt.platforms || rt.platforms.length < 2) continue;
		out.push(rt);
	}
	return out;
}

function modeVisible(mode) { return !(state.prefs.hiddenModes || []).includes(mode || "train"); }

/** Every transport mode the loaded network actually contains (drives the Layers list). */
function modesPresent() {
	const s = new Set();
	for (const rt of candidateRoutes()) s.add(rt.mode || "train");
	return [...s].sort();
}

/* ----------------------------------------------------------------------------
 * 5a. THE LINE MODEL — colour alone defines a line
 * --------------------------------------------------------------------------
 * Every MTR route is DIRECTIONAL, so one real line is two routes (often four, with
 * express services) on two or more parallel tracks. Drawing per route produced the
 * braided ribbons and the "IN"/"OU" bullets Thomas saw. So: all routes sharing a
 * colour ARE one line, and a line's bullets are the distinct NORMALISED service
 * labels of its routes — "4 IN" and "4 OU" both read "4".
 * ------------------------------------------------------------------------- */

/** Standalone words that only ever say which way a service runs. */
const DIRECTION_TOKENS = new Set([
	"IN", "OUT", "OU", "IB", "OB", "NB", "SB", "EB", "WB", "UP", "DN", "DOWN",
	"INBOUND", "OUTBOUND", "NORTHBOUND", "SOUTHBOUND", "EASTBOUND", "WESTBOUND",
	"CW", "CCW",
]);
/** Arrow glyphs (and the fullwidth variants) MTR operators use as direction marks. */
const DIRECTION_ARROWS = /[←-⇿⬀-⬑⟵-⟺]/g;
const LABEL_SEPARATORS = /[\s\-‐-―_/\\|·,.:;()\[\]{}]+/;

/**
 * Strip direction tokens from a service label.
 *
 * Only WHOLE words are stripped, case-insensitively ("2 IN" -> "2", "A Inbound" -> "A"),
 * never substrings ("Ba" stays "Ba"). If stripping empties the label the original is
 * kept ("OU" alone is somebody's actual route number, not a direction suffix).
 */
function normalizeServiceLabel(raw) {
	const src = String(raw == null ? "" : raw).trim();
	if (!src) return "";
	const words = src.replace(DIRECTION_ARROWS, " ").split(LABEL_SEPARATORS).filter(Boolean);
	const kept = words.filter((w) => !DIRECTION_TOKENS.has(w.toUpperCase()));
	const out = kept.join(" ").replace(/^[\s\-‐-―_/\\|·,.:;]+|[\s\-‐-―_/\\|·,.:;]+$/g, "").trim();
	return out || src;
}

/** The bullet text for one route: its number, else the first two letters of its name. */
function routeServiceLabel(rt) {
	const num = rt && rt.number != null ? String(rt.number).trim() : "";
	if (num) return normalizeServiceLabel(num);
	const name = (rt && (rt.display || rt.name)) || "";
	return normalizeServiceLabel(name).slice(0, 2);
}

function buildLines() {
	state.lines = new Map();
	for (const rt of candidateRoutes()) {
		let line = state.lines.get(rt.hex);
		if (!line) {
			line = { hex: rt.hex, colorInt: (rt.color || 0) & 0xFFFFFF, routeIds: [], serviceLabels: [], modes: new Set() };
			state.lines.set(rt.hex, line);
		}
		line.routeIds.push(rt.id);
		line.modes.add(rt.mode || "train");
		const label = routeServiceLabel(rt);
		if (label && !line.serviceLabels.some((l) => l.toLowerCase() === label.toLowerCase())) {
			line.serviceLabels.push(label);
		}
	}
	return state.lines;
}

/** The bullets a chip draws for one colour. */
function lineLabels(hex) {
	const line = state.lines.get(hex);
	return line ? line.serviceLabels.slice(0, MAX_CHIP_BULLETS) : [];
}

/* ----------------------------------------------------------------------------
 * 5b. polyline maths used by the schematic build
 * ------------------------------------------------------------------------- */

/** Even-arc-length resample to exactly n points (endpoints preserved). */
function resamplePolyline(pts, n) {
	const clean = [];
	for (const p of pts) {
		if (!clean.length || dist(clean[clean.length - 1], p) > 1e-9) clean.push([p[0], p[1]]);
	}
	if (!clean.length) return [];
	if (clean.length === 1) clean.push(clean[0].slice());
	const cum = [0];
	for (let i = 1; i < clean.length; i++) cum.push(cum[i - 1] + dist(clean[i - 1], clean[i]));
	const total = cum[cum.length - 1];
	const out = [];
	if (total <= 1e-9) {
		for (let i = 0; i < n; i++) out.push(clean[0].slice());
		return out;
	}
	let j = 1;
	for (let i = 0; i < n; i++) {
		const target = total * i / (n - 1);
		while (j < cum.length - 1 && cum[j] < target) j++;
		const span = cum[j] - cum[j - 1] || 1;
		const f = clamp((target - cum[j - 1]) / span, 0, 1);
		out.push([
			clean[j - 1][0] + (clean[j][0] - clean[j - 1][0]) * f,
			clean[j - 1][1] + (clean[j][1] - clean[j - 1][1]) * f,
		]);
	}
	return out;
}

/** Samples every ~`step` blocks along a polyline (coverage + companion tests). */
function sampleAlong(pts, step) {
	const len = polylineLength(pts);
	return resamplePolyline(pts, Math.max(2, Math.round(len / step) + 1));
}

function pointSegmentDist(p, a, b) {
	const vx = b[0] - a[0], vz = b[1] - a[1];
	const l2 = vx * vx + vz * vz;
	if (l2 <= 1e-12) return Math.hypot(p[0] - a[0], p[1] - a[1]);
	const t = clamp(((p[0] - a[0]) * vx + (p[1] - a[1]) * vz) / l2, 0, 1);
	return Math.hypot(p[0] - (a[0] + vx * t), p[1] - (a[1] + vz * t));
}

/** Nearest point on a polyline, with its distance. */
function nearestOnPolyline(p, pts) {
	let best = null, bestD = Infinity;
	for (let i = 1; i < pts.length; i++) {
		const a = pts[i - 1], b = pts[i];
		const vx = b[0] - a[0], vz = b[1] - a[1];
		const l2 = vx * vx + vz * vz;
		const t = l2 <= 1e-12 ? 0 : clamp(((p[0] - a[0]) * vx + (p[1] - a[1]) * vz) / l2, 0, 1);
		const q = [a[0] + vx * t, a[1] + vz * t];
		const d = Math.hypot(p[0] - q[0], p[1] - q[1]);
		if (d < bestD) { bestD = d; best = q; }
	}
	return { point: best, dist: bestD };
}

function pointPolylineDist(p, pts) {
	let best = Infinity;
	for (let i = 1; i < pts.length; i++) {
		const d = pointSegmentDist(p, pts[i - 1], pts[i]);
		if (d < best) best = d;
		if (best === 0) break;
	}
	return best;
}

function bboxOf(pts) {
	let a = Infinity, b = Infinity, c = -Infinity, d = -Infinity;
	for (const p of pts) {
		a = Math.min(a, p[0]); c = Math.max(c, p[0]);
		b = Math.min(b, p[1]); d = Math.max(d, p[1]);
	}
	return [a, b, c, d];
}

/**
 * Average N equally-sampled polylines pointwise. Each is first oriented to agree with
 * the first one — the in/out tracks of a line are usually stored in opposite
 * directions, and averaging them unflipped would fold the pair into a bowtie.
 */
function averagePolylines(list) {
	const ref = list[0];
	const n = ref.length;
	const acc = ref.map((p) => [p[0], p[1]]);
	for (let k = 1; k < list.length; k++) {
		let o = list[k];
		if (dist(ref[0], o[0]) + dist(ref[n - 1], o[n - 1]) > dist(ref[0], o[n - 1]) + dist(ref[n - 1], o[0])) {
			o = o.slice().reverse();
		}
		for (let i = 0; i < n; i++) { acc[i][0] += o[i][0]; acc[i][1] += o[i][1]; }
	}
	for (const p of acc) { p[0] /= list.length; p[1] /= list.length; }
	return acc;
}

/** Light 1-2-1 smoothing; endpoints are never moved. */
function smoothLightly(pts, passes = 1) {
	let cur = pts;
	for (let k = 0; k < passes; k++) {
		if (cur.length < 3) break;
		const out = [cur[0].slice()];
		for (let i = 1; i < cur.length - 1; i++) {
			out.push([
				(cur[i - 1][0] + 2 * cur[i][0] + cur[i + 1][0]) / 4,
				(cur[i - 1][1] + 2 * cur[i][1] + cur[i + 1][1]) / 4,
			]);
		}
		out.push(cur[cur.length - 1].slice());
		cur = out;
	}
	return cur;
}

/**
 * Pull the two ends of a segment onto the station-part centroids they connect. The
 * terminal samples are BLENDED rather than moved wholesale, so the segment bends into
 * the station instead of kinking; this is what turns a big throat (many same-colour
 * tracks fanning out across a station) into lines that meet at one point.
 */
const SNAP_WEIGHTS = [1, 0.55, 0.2];
function snapEnds(pts, a, b) {
	const n = pts.length;
	if (a) for (let i = 0; i < SNAP_WEIGHTS.length && i < n; i++) {
		const w = SNAP_WEIGHTS[i];
		pts[i][0] += (a[0] - pts[i][0]) * w;
		pts[i][1] += (a[1] - pts[i][1]) * w;
	}
	if (b) for (let i = 0; i < SNAP_WEIGHTS.length && i < n; i++) {
		const j = n - 1 - i, w = SNAP_WEIGHTS[i];
		if (j < 0) break;
		pts[j][0] += (b[0] - pts[j][0]) * w;
		pts[j][1] += (b[1] - pts[j][1]) * w;
	}
	return pts;
}

/* ----------------------------------------------------------------------------
 * 5c. SCHEMATIC SEGMENTS — one line per colour per corridor, everywhere
 * --------------------------------------------------------------------------
 * The drawing is no longer per rail. A drawn SEGMENT is (colour, unordered pair of
 * station parts):
 *
 *   1. PAIR AVERAGING. Every same-colour leg between the same two parts (the in/out
 *      track pair, plus any duplicate services) is resampled to SEG_SAMPLES points,
 *      oriented consistently and averaged pointwise into ONE centreline. Same-colour
 *      lines can therefore never braid, because there is only ever one of them.
 *   2. EXPRESS COVERAGE. A segment that spans several parts (an express A->D where the
 *      line also runs A-B-C-D) is suppressed when COVER_FRACTION of its samples sit
 *      within CORRIDOR_TOL of the line's other, shorter segments — unless dropping it
 *      would disconnect its two parts. It keeps `coveredBy`, so a journey riding the
 *      express still lights the chain underneath it.
 *   3. CROSS-COLOUR BUNDLING. Segments of DIFFERENT colours that share a corridor
 *      become companions; the sorted companion colour set gives each segment its
 *      (idx, count) for the screen-space parallel offset. Same-colour segments never
 *      offset against each other — that was the braiding bug.
 *   4. Z-ORDER. Drawn sorted by colour int, so overlap order never flickers.
 *
 * The raw per-leg polylines in state.legs are untouched: the planner and the vehicle
 * interpolation keep using REAL rail geometry. Only the drawing is schematic.
 * ------------------------------------------------------------------------- */

function buildSegments() {
	state.ribbons = [];
	state.bundles = [];
	state.segByPair = new Map();
	state.segsByColor = new Map();

	/* --- 1. collect legs per (colour, part pair) --- */
	const buckets = new Map();
	for (const rt of visibleRoutes()) {
		const line = state.lines.get(rt.hex);
		for (let i = 0; i < (rt.platforms || []).length - 1; i++) {
			const leg = state.legs.get(rt.id + "|" + i);
			if (!leg) continue;
			const a = partOfPlatform(leg.from), b = partOfPlatform(leg.to);
			if (!a || !b || a === b) continue;          // nothing to draw inside one part
			const key = pairKeyOf(a, b);
			const id = rt.hex + "|" + key;
			let bk = buckets.get(id);
			if (!bk) {
				bk = {
					id, key, hex: rt.hex, colorInt: line ? line.colorInt : ((rt.color || 0) & 0xFFFFFF),
					partA: a < b ? a : b, partB: a < b ? b : a,
					polys: [], routes: [], modes: new Set(), hidden: true, straight: true,
				};
				buckets.set(id, bk);
			}
			bk.polys.push(resamplePolyline(leg.pts, SEG_SAMPLES));
			if (!bk.routes.includes(rt)) bk.routes.push(rt);
			bk.modes.add(rt.mode || "train");
			if (!rt.hidden) bk.hidden = false;
			if (!leg.straight) bk.straight = false;
		}
	}

	/* --- 2. average each pair into one centreline, then snap it to the centroids --- */
	const all = [];
	for (const bk of buckets.values()) {
		if (!bk.polys.length) continue;
		let pts = bk.polys.length > 1
			? averagePolylines(bk.polys)
			: smoothLightly(bk.polys[0].map((p) => p.slice()), 1);
		const ca = partCentroid(bk.partA), cb = partCentroid(bk.partB);
		if (ca && cb && dist(pts[0], ca) + dist(pts[pts.length - 1], cb)
			> dist(pts[0], cb) + dist(pts[pts.length - 1], ca)) pts.reverse();
		snapEnds(pts, ca, cb);
		all.push({
			id: bk.id, key: bk.key, hex: bk.hex, colorInt: bk.colorInt,
			partA: bk.partA, partB: bk.partB,
			pts, samples: sampleAlong(pts, SAMPLE_STEP), len: polylineLength(pts), bbox: bboxOf(pts),
			mode: bk.modes.has("boat") ? "boat" : [...bk.modes][0] || "train",
			modes: [...bk.modes], routes: bk.routes, hidden: bk.hidden, straight: bk.straight,
			sourceCount: bk.polys.length,
			// Offset side must not depend on which part happened to sort first: the
			// polyline's direction is canonicalized by its dominant axis, so every
			// companion in a corridor offsets to the SAME side and a colour never
			// switches sides from one station-to-station segment to the next.
			offSign: canonicalOffsetSign(pts),
			idx: 0, count: 1, companions: [bk.hex], companionSegs: [],
			suppressed: false, coveredBy: [],
		});
	}

	/* --- 3. express coverage --- */
	const byColor = new Map();
	for (const s of all) {
		if (!byColor.has(s.hex)) byColor.set(s.hex, []);
		byColor.get(s.hex).push(s);
		state.segByPair.set(s.id, s);
	}
	for (const segs of byColor.values()) suppressCoveredSegments(segs);

	/* --- 4. cross-colour bundling over what survives --- */
	const drawn = all.filter((s) => !s.suppressed);
	bundleCompanions(drawn);
	for (const s of drawn) {
		const colors = new Map([[s.hex, s.colorInt]]);
		for (const o of s.companionSegs) colors.set(o.hex, o.colorInt);
		const list = [...colors.keys()].sort((x, y) => colors.get(x) - colors.get(y) || (x < y ? -1 : 1));
		s.companions = list;
		s.count = list.length;
		s.idx = list.indexOf(s.hex);
	}

	/* --- 5. stable paint order + per-colour index for the vehicle snap --- */
	drawn.sort((a, b) => a.colorInt - b.colorInt || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
	state.ribbons = drawn;
	for (const s of drawn) {
		if (!state.segsByColor.has(s.hex)) state.segsByColor.set(s.hex, []);
		state.segsByColor.get(s.hex).push(s);
	}
	buildBundleChips(drawn);
}

/**
 * Suppress the express segments of ONE line. Candidates are tested longest first, and
 * only STRICTLY SHORTER segments of the same line may cover them, so a chain can never
 * be swallowed by the express it covers. A segment is kept regardless if removing it
 * would leave its two parts with no other path through the line's own segments — a
 * lone long link is the connection, not a duplicate of one.
 */
function suppressCoveredSegments(segs) {
	const order = segs.slice().sort((a, b) => b.len - a.len);
	for (const s of order) {
		const bb = s.bbox;
		const others = segs.filter((o) => o !== s && !o.suppressed && o.len < s.len - 1e-6
			// a shorter segment can only cover part of `s` if their bboxes come within TOL
			&& o.bbox[2] >= bb[0] - CORRIDOR_TOL && o.bbox[0] <= bb[2] + CORRIDOR_TOL
			&& o.bbox[3] >= bb[1] - CORRIDOR_TOL && o.bbox[1] <= bb[3] + CORRIDOR_TOL);
		if (!others.length) continue;
		let covered = 0;
		const by = new Set();
		for (const p of s.samples) {
			let bestD = Infinity, bestSeg = null;
			for (const o of others) {
				const ob = o.bbox;
				if (p[0] < ob[0] - CORRIDOR_TOL || p[0] > ob[2] + CORRIDOR_TOL
					|| p[1] < ob[1] - CORRIDOR_TOL || p[1] > ob[3] + CORRIDOR_TOL) continue;
				const d = pointPolylineDist(p, o.pts);
				if (d < bestD) { bestD = d; bestSeg = o; }
				if (bestD <= 0.5) break;                     // already sitting on top of it
			}
			if (bestD <= CORRIDOR_TOL) { covered++; if (bestSeg) by.add(bestSeg.id); }
		}
		if (covered / s.samples.length < COVER_FRACTION) continue;
		if (!partsConnectedWithout(segs, s)) continue;
		s.suppressed = true;
		s.coveredBy = coveringChain(segs.filter((o) => by.has(o.id)), s.partA, s.partB, [...by]);
	}
}

/**
 * The nearest-segment scan can pick up a neighbour that merely converges on the same
 * station (it is nearest for the samples right at the end). Reduce `by` to the actual
 * chain of parts from A to B, so highlighting an express lights exactly the stops it
 * runs over and nothing hanging off them.
 */
function coveringChain(members, partA, partB, fallback) {
	const adj = new Map();
	for (const s of members) {
		if (!adj.has(s.partA)) adj.set(s.partA, []);
		if (!adj.has(s.partB)) adj.set(s.partB, []);
		adj.get(s.partA).push({ to: s.partB, seg: s });
		adj.get(s.partB).push({ to: s.partA, seg: s });
	}
	const prev = new Map([[partA, null]]);
	const queue = [partA];
	while (queue.length) {
		const cur = queue.shift();
		if (cur === partB) {
			const out = [];
			for (let p = partB; prev.get(p); p = prev.get(p).from) out.push(prev.get(p).seg.id);
			return out.sort();
		}
		for (const e of adj.get(cur) || []) {
			if (prev.has(e.to)) continue;
			prev.set(e.to, { from: cur, seg: e.seg });
			queue.push(e.to);
		}
	}
	return fallback.slice().sort();
}

/** Are `skip`'s two parts still joined by the rest of this line's live segments? */
function partsConnectedWithout(segs, skip) {
	const adj = new Map();
	const link = (a, b) => {
		if (!adj.has(a)) adj.set(a, []);
		adj.get(a).push(b);
	};
	for (const s of segs) {
		if (s === skip || s.suppressed) continue;
		link(s.partA, s.partB);
		link(s.partB, s.partA);
	}
	const seen = new Set([skip.partA]);
	const queue = [skip.partA];
	while (queue.length) {
		const cur = queue.shift();
		if (cur === skip.partB) return true;
		for (const nx of adj.get(cur) || []) if (!seen.has(nx)) { seen.add(nx); queue.push(nx); }
	}
	return false;
}

/**
 * Companion detection. A uniform spatial grid (cell GRID_CELL) over every segment's
 * ~SAMPLE_STEP-spaced samples is the broad phase; the narrow phase is an exact
 * point-to-polyline distance. Two different-colour segments are companions when
 * COMPANION_FRACTION of the SHORTER one's samples lie within CORRIDOR_TOL of the other.
 */
function bundleCompanions(drawn) {
	const grid = new Map();
	const cellOf = (x, z) => Math.floor(x / GRID_CELL) + "," + Math.floor(z / GRID_CELL);
	for (const s of drawn) {
		for (const p of s.samples) {
			const k = cellOf(p[0], p[1]);
			let cell = grid.get(k);
			if (!cell) { cell = new Set(); grid.set(k, cell); }
			cell.add(s);
		}
	}
	// a sample can be CORRIDOR_TOL away and still land up to SAMPLE_STEP further along
	const reach = Math.ceil((CORRIDOR_TOL + SAMPLE_STEP) / GRID_CELL);
	const hits = new Map();          // segment -> Map(other -> samples of `segment` near `other`)
	for (const s of drawn) {
		let mine = hits.get(s);
		if (!mine) { mine = new Map(); hits.set(s, mine); }
		for (const p of s.samples) {
			const cx = Math.floor(p[0] / GRID_CELL), cz = Math.floor(p[1] / GRID_CELL);
			const cands = new Set();
			for (let i = -reach; i <= reach; i++) for (let j = -reach; j <= reach; j++) {
				const cell = grid.get((cx + i) + "," + (cz + j));
				if (cell) for (const o of cell) cands.add(o);
			}
			for (const o of cands) {
				if (o === s || o.hex === s.hex) continue;      // NEVER offset a colour against itself
				if (pointPolylineDist(p, o.pts) <= CORRIDOR_TOL) mine.set(o, (mine.get(o) || 0) + 1);
			}
		}
	}
	const done = new Set();
	for (const [s, mine] of hits) {
		for (const o of mine.keys()) {
			const key = s.id < o.id ? s.id + "~" + o.id : o.id + "~" + s.id;
			if (done.has(key)) continue;
			done.add(key);
			// the SHORTER segment's own coverage decides — a short link inside a long
			// corridor is a companion of it, but not the other way round
			const shorter = s.len <= o.len ? s : o;
			const longer = shorter === s ? o : s;
			const near = (hits.get(shorter) || new Map()).get(longer) || 0;
			if (near / shorter.samples.length < COMPANION_FRACTION) continue;
			s.companionSegs.push(o);
			o.companionSegs.push(s);
		}
	}
}

/**
 * Interlining chips: one chip per distinct companion-colour signature, hung off the
 * longest segment carrying it, showing each line's NORMALISED service labels (so a
 * directional pair contributes one bullet, not "IN" and "OU").
 */
function buildBundleChips(drawn) {
	const best = new Map();
	for (const s of drawn) {
		if (s.count < 2) continue;
		const sig = s.companions.join(",");
		const prev = best.get(sig);
		if (!prev || s.len > prev.len) best.set(sig, s);
	}
	state.bundles = [];
	for (const s of best.values()) {
		const pts = s.pts;
		const m = Math.floor(pts.length / 2);
		const a = pts[Math.max(0, m - 1)], b = pts[Math.min(pts.length - 1, m + 1)];
		const dx = b[0] - a[0], dz = b[1] - a[1];
		const l = Math.hypot(dx, dz) || 1;
		state.bundles.push({
			x: pts[m][0], z: pts[m][1],
			nx: -dz / l, nz: dx / l,            // unit normal: where the chip hangs
			colors: s.companions.map((hex) => ({ hex, numbers: lineLabels(hex) })),
		});
	}
}

/**
 * The AXIS the drawn lines run along at one station part — what the interchange capsule
 * has to be perpendicular to.
 *
 * Averaged in DOUBLE ANGLE. The two segments of a through station leave it in opposite
 * directions, and a plain vector mean of those (however you fold them into a
 * half-plane) cancels to nothing or, worse, to the perpendicular; doubling the angle
 * first is the standard way to average undirected axes, and it makes a north-south
 * trunk read north-south.
 */
function segmentDirAtPart(partId) {
	let sx = 0, sz = 0, n = 0;
	for (const s of state.ribbons) {
		let a = null, b = null;
		// measured past SNAP_WEIGHTS' reach, or the blend into the centroid would read as
		// a sideways kink rather than the direction the line actually leaves the station
		const span = Math.min(4, s.pts.length - 1);
		if (s.partA === partId) { a = s.pts[0]; b = s.pts[span]; }
		else if (s.partB === partId) { a = s.pts[s.pts.length - 1]; b = s.pts[s.pts.length - 1 - span]; }
		else continue;
		const vx = b[0] - a[0], vz = b[1] - a[1];
		const l = Math.hypot(vx, vz);
		if (l < 0.01) continue;
		const c = vx / l, s2 = vz / l;
		sx += c * c - s2 * s2;          // cos 2t
		sz += 2 * c * s2;               // sin 2t
		n++;
	}
	if (!n || Math.hypot(sx, sz) < 1e-6) return null;
	const t = Math.atan2(sz, sx) / 2;
	const dx = Math.cos(t), dz = Math.sin(t);
	return dx < 0 || (dx === 0 && dz < 0) ? [-dx, -dz] : [dx, dz];
}

/** Station part glyphs: dot, or capsule when the part serves two or more ribbon colours. */
function buildGlyphs() {
	const shown = new Set(visibleRoutes().map((r) => r.id));
	const known = new Set(candidateRoutes().map((r) => r.id));
	state.glyphs = [];
	for (const st of state.stations.values()) {
		for (const part of st.parts) {
			const colors = new Set();
			let accessible = false;
			let dx = 0, dz = 0, n = 0;
			let servedAtAll = false;
			for (const pid of part.platforms.length ? part.platforms : st.platformIds) {
				const pl = state.platforms.get(pid);
				if (!pl) continue;
				if (pl.accessible) accessible = true;
				for (const rid of pl.routeIds) {
					const rt = state.routes.get(rid);
					if (!rt) continue;
					if (known.has(rid)) servedAtAll = true;
					if (shown.has(rid)) colors.add(rt.hex);
				}
				// dominant track direction, folded to a half-plane so opposite platform
				// vectors reinforce instead of cancelling
				let [ax, az] = pl.dir;
				const l = Math.hypot(ax, az);
				if (l > 0.01) {
					if (ax < 0 || (ax === 0 && az < 0)) { ax = -ax; az = -az; }
					dx += ax / l; dz += az / l; n++;
				}
			}
			if (!colors.size && !part.platforms.length) continue;
			// a part served ONLY by modes the Layers list has switched off goes away;
			// one shared with a visible mode stays
			if (servedAtAll && !colors.size) continue;
			const dl = Math.hypot(dx, dz);
			state.glyphs.push({
				stationId: st.id, partId: part.id,
				x: part.x, z: part.z,
				colors: [...colors],
				capsule: colors.size >= 2,
				// the capsule spans the bundle, so it must follow the SCHEMATIC line's
				// direction here, not the raw platform vectors
				dir: segmentDirAtPart(part.id) || (n && dl > 0.05 ? [dx / dl, dz / dl] : [1, 0]),
				accessible: accessible || (!!st.accessible && part.id === st.mainPartId),
				main: part.id === st.mainPartId,
				weight: (part.platforms.length || st.platformIds.length || 1),
				label: st.display,
				sub: part.sub,
			});
		}
	}
}

/** Dotted connectors between the parts of a split station, with a "90 m" walk chip. */
function buildWalks() {
	state.walks = [];
	for (const st of state.stations.values()) {
		if (st.parts.length < 2) continue;
		const byId = new Map(st.parts.map((p) => [p.id, p]));
		for (const w of st.partWalks || []) {
			const a = byId.get(w.a), b = byId.get(w.b);
			if (!a || !b) continue;
			state.walks.push({ ax: a.x, az: a.z, bx: b.x, bz: b.z, dist: w.dist || dist([a.x, a.z], [b.x, b.z]) });
		}
		// no partWalks published: still connect consecutive parts so the split reads
		if (!(st.partWalks || []).length) {
			for (let i = 1; i < st.parts.length; i++) {
				const a = st.parts[i - 1], b = st.parts[i];
				state.walks.push({ ax: a.x, az: a.z, bx: b.x, bz: b.z, dist: dist([a.x, a.z], [b.x, b.z]) });
			}
		}
	}
}

/* ============================================================================
 * 6. canvas, view maths
 * ========================================================================== */

const canvas = $("map");
const ctx = canvas.getContext("2d");
let staticCanvas = document.createElement("canvas");
let staticDirty = true;
let staticRenderedView = null;    // the view the cached layer was last drawn at

function invalidateStatic() { staticDirty = true; }

function resize() {
	const dpr = window.devicePixelRatio || 1;
	const w = canvas.clientWidth, h = canvas.clientHeight;
	if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
		canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr);
		staticCanvas.width = canvas.width; staticCanvas.height = canvas.height;
		staticRenderedView = null;    // the bitmap is gone — never stale-blit it
		invalidateStatic();
	}
	return dpr;
}

function worldToScreen(x, z) {
	const v = state.view;
	return [canvas.clientWidth / 2 + (x - v.x) * v.scale, canvas.clientHeight / 2 + (z - v.z) * v.scale];
}
function screenToWorld(sx, sy) {
	const v = state.view;
	return [v.x + (sx - canvas.clientWidth / 2) / v.scale, v.z + (sy - canvas.clientHeight / 2) / v.scale];
}

function fitView() {
	const b = state.networkBox;
	if (!b) return;
	const w = canvas.clientWidth || 1280, h = canvas.clientHeight || 800;
	// leave room for the planner card on the left, then shift the world centre right by
	// half that reserve so the network sits in the free part of the canvas
	const padL = w > 900 ? 440 : 40;
	const sx = (w - padL - 60) / Math.max(40, b.maxX - b.minX);
	const sz = (h - 120) / Math.max(40, b.maxZ - b.minZ);
	state.view.scale = clamp(Math.min(sx, sz), 0.02, 20);
	state.view.x = (b.minX + b.maxX) / 2 - (padL - 60) / 2 / state.view.scale;
	state.view.z = (b.minZ + b.maxZ) / 2;
	invalidateStatic();
}

/** World polyline -> screen polyline, dropping points closer than ~1.2 px. */
function toScreenPath(pts) {
	const out = [];
	for (let i = 0; i < pts.length; i++) {
		const p = worldToScreen(pts[i][0], pts[i][1]);
		if (i === 0 || i === pts.length - 1 || Math.abs(p[0] - out[out.length - 1][0]) + Math.abs(p[1] - out[out.length - 1][1]) > 1.2) {
			out.push(p);
		}
	}
	return out;
}

/**
 * Offset a screen polyline along its normals. Each vertex uses the averaged normal of
 * its two adjacent segments, lengthened by the miter factor 1/cos(θ/2) (capped) so a
 * bundle keeps a constant gap around corners instead of pinching.
 */
/**
 * +1 / −1 by the polyline's dominant-axis direction. Companion segments in a
 * corridor run near-parallel, so they share a dominant axis and get the same
 * sign — which is what pins each colour to one side of the bundle regardless of
 * which station-part happened to sort first into the pair key.
 */
function canonicalOffsetSign(pts) {
	const dx = pts[pts.length - 1][0] - pts[0][0];
	const dz = pts[pts.length - 1][1] - pts[0][1];
	return (Math.abs(dx) >= Math.abs(dz) ? dx : dz) >= 0 ? 1 : -1;
}

function offsetPolyline(pts, d) {
	if (!d || pts.length < 2) return pts;
	const out = [];
	for (let i = 0; i < pts.length; i++) {
		let nx = 0, ny = 0;
		if (i > 0) {
			const dx = pts[i][0] - pts[i - 1][0], dy = pts[i][1] - pts[i - 1][1];
			const l = Math.hypot(dx, dy) || 1;
			nx += -dy / l; ny += dx / l;
		}
		if (i < pts.length - 1) {
			const dx = pts[i + 1][0] - pts[i][0], dy = pts[i + 1][1] - pts[i][1];
			const l = Math.hypot(dx, dy) || 1;
			nx += -dy / l; ny += dx / l;
		}
		const nl = Math.hypot(nx, ny);
		if (nl < 1e-6) { out.push([pts[i][0], pts[i][1]]); continue; }
		nx /= nl; ny /= nl;
		let miter = 1;
		if (i > 0 && i < pts.length - 1) {
			const ax = pts[i][0] - pts[i - 1][0], ay = pts[i][1] - pts[i - 1][1];
			const bx = pts[i + 1][0] - pts[i][0], by = pts[i + 1][1] - pts[i][1];
			const la = Math.hypot(ax, ay) || 1, lb = Math.hypot(bx, by) || 1;
			const cos = clamp((ax * bx + ay * by) / (la * lb), -1, 1);
			miter = Math.min(2.5, 1 / Math.max(0.35, Math.sqrt((1 + cos) / 2)));
		}
		out.push([pts[i][0] + nx * d * miter, pts[i][1] + ny * d * miter]);
	}
	return out;
}

/**
 * Ribbon stroke width: ~4.5 px at the reference zoom, clamped at both extremes, then
 * multiplied by the settings slider (0.6x - 1.6x).
 */
function ribbonWidth() {
	const z = Math.max(0.05, state.view.scale);
	const scale = clamp(state.prefs.lineScale || 1, LINE_SCALE_MIN, LINE_SCALE_MAX);
	return clamp(4.5 * uiScale() * Math.pow(z / 0.6, 0.3), 2.6, 9) * scale;
}

function strokePath(g, pts) {
	g.beginPath();
	g.moveTo(pts[0][0], pts[0][1]);
	for (let i = 1; i < pts.length; i++) g.lineTo(pts[i][0], pts[i][1]);
	g.stroke();
}

/* ============================================================================
 * 7. static layer
 * ========================================================================== */

/**
 * Screen rects of chips/pins drawn this pass — the label declutterer seeds its
 * placed-list from these so text never lands on a walk chip, bundle chip or the
 * origin/destination markers. Reset per static render.
 */
let labelObstacles = [];

function drawStatic(dpr) {
	const g = staticCanvas.getContext("2d");
	g.setTransform(dpr, 0, 0, dpr, 0, 0);
	const W = canvas.clientWidth, H = canvas.clientHeight;
	labelObstacles = [];

	// 1. land
	g.fillStyle = PALETTE.paper;
	g.fillRect(0, 0, W, H);

	// world-space viewport + 25 % margin (the pan fast path blits this cache offset)
	const v = state.view;
	const mx = W / v.scale * 0.25 + 8, mz = H / v.scale * 0.25 + 8;
	const vp = {
		l: v.x - W / 2 / v.scale - mx, r: v.x + W / 2 / v.scale + mx,
		t: v.z - H / 2 / v.scale - mz, b: v.z + H / 2 / v.scale + mz,
	};

	drawWater(g);

	const sel = state.selection;
	const journeyKeys = sel ? sel.ribbonKeys : null;

	// 3. route ribbons (journey members are skipped here and drawn in the overlay)
	drawRibbons(g, vp, journeyKeys, sel ? PALETTE.dim : 1);

	// no mapdata at all: fall back to raw rails so the page still shows the network
	if (!state.ribbons.length) {
		g.globalAlpha = sel ? PALETTE.dim : 1;
		g.strokeStyle = PALETTE.rawRail;
		g.lineWidth = 2;
		g.lineCap = "round";
		for (const r of state.rails.values()) {
			const bb = r.bbox;
			if (bb[2] < vp.l || bb[0] > vp.r || bb[3] < vp.t || bb[1] > vp.b) continue;
			strokePath(g, toScreenPath(r.pts));
		}
		g.globalAlpha = 1;
	}

	// 4. walk connectors between split-station parts
	drawWalks(g, vp, sel ? PALETTE.dim : 1);

	// 5. station glyphs
	drawGlyphs(g, vp, sel);

	// interlining bullet clusters (only worth drawing once lines are separable)
	if (v.scale > 0.35) drawBundleChips(g, vp, sel ? PALETTE.dim : 1);

	// journey overlay: glow + full-strength ribbons, walks, glyphs, origin/destination
	if (sel) drawJourneyOverlay(g, vp);

	// 6. labels last so nothing paints over them
	drawLabels(g, vp, sel);
}

function drawWater(g) {
	if (!state.terrain) return;
	g.save();
	g.beginPath();
	for (const ring of state.terrain.polygons) {
		const pts = toScreenPath(ring);
		if (pts.length < 3) continue;
		g.moveTo(pts[0][0], pts[0][1]);
		for (let i = 1; i < pts.length; i++) g.lineTo(pts[i][0], pts[i][1]);
		g.closePath();
	}
	g.fillStyle = PALETTE.water;
	g.fill("evenodd");             // holes and islands come free from even-odd
	g.strokeStyle = PALETTE.waterStroke;
	g.lineWidth = 2;
	g.lineJoin = "round";
	g.stroke();
	g.restore();
}

function drawRibbons(g, vp, skipKeys, alpha) {
	const w = ribbonWidth();
	const gap = Math.max(0.6, w * 0.16);
	g.lineCap = "round";
	g.lineJoin = "round";
	for (const rb of state.ribbons) {
		if (skipKeys && skipKeys.has(rb.id)) continue;
		const bb = rb.bbox;
		if (bb[2] < vp.l || bb[0] > vp.r || bb[3] < vp.t || bb[1] > vp.b) continue;
		const pts = toScreenPath(rb.pts);
		if (pts.length < 2) continue;
		const off = (rb.idx - (rb.count - 1) / 2) * (w + gap) * (rb.offSign || 1);
		const line = offsetPolyline(pts, off);
		g.globalAlpha = alpha * (rb.hidden ? 0.35 : 1);
		g.strokeStyle = rb.hex;
		g.lineWidth = rb.mode === "boat" ? w * 0.72 : w;
		g.setLineDash(rb.mode === "boat" ? [1.5, w * 1.9] : []);
		strokePath(g, line);
	}
	g.setLineDash([]);
	g.globalAlpha = 1;
}

function drawWalks(g, vp, alpha) {
	if (!state.walks.length) return;
	const s = uiScale();
	g.globalAlpha = alpha;
	for (const w of state.walks) {
		if (Math.max(w.ax, w.bx) < vp.l || Math.min(w.ax, w.bx) > vp.r) continue;
		if (Math.max(w.az, w.bz) < vp.t || Math.min(w.az, w.bz) > vp.b) continue;
		const a = worldToScreen(w.ax, w.az), b = worldToScreen(w.bx, w.bz);
		g.strokeStyle = PALETTE.ink2;
		g.lineWidth = 3 * s * 0.8;
		g.lineCap = "round";
		g.setLineDash([1.5, 7]);
		g.beginPath(); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); g.stroke();
		g.setLineDash([]);
		if (state.view.scale > 0.25) drawWalkChip(g, (a[0] + b[0]) / 2, (a[1] + b[1]) / 2, fmtMeters(w.dist));
	}
	g.globalAlpha = 1;
}

function drawWalkChip(g, cx, cy, text) {
	const s = uiScale();
	const h = 24 * s, fs = 12.5 * s;
	g.font = "600 " + fs.toFixed(1) + "px " + FONT;
	const tw = g.measureText(text).width;
	const w = 8 * s + 14 * s + 4 * s + tw + 9 * s;
	const x = cx - w / 2, y = cy - h / 2;
	labelObstacles.push([x - 2, y - 2, x + w + 2, y + h + 2]);
	chipRect(g, x, y, w, h, h / 2);
	drawIcon(g, P_WALK, x + 8 * s + 7 * s, y + h / 2, 14 * s, PALETTE.ink2);
	g.fillStyle = PALETTE.chipInk;
	g.textAlign = "left";
	g.textBaseline = "middle";
	g.fillText(text, x + 8 * s + 14 * s + 4 * s, y + h / 2 + 0.5);
	g.textBaseline = "alphabetic";
}

function drawGlyphs(g, vp, sel) {
	const s = uiScale();
	const w = ribbonWidth();
	for (const gl of state.glyphs) {
		if (gl.x < vp.l || gl.x > vp.r || gl.z < vp.t || gl.z > vp.b) continue;
		const inJourney = sel && sel.partIds.has(gl.partId);
		if (sel && inJourney) continue;             // drawn full-strength in the overlay
		g.globalAlpha = sel ? PALETTE.dim : 1;
		drawGlyph(g, gl, s, w);
		g.globalAlpha = 1;
	}
}

function drawGlyph(g, gl, s, w) {
	const [sx, sy] = worldToScreen(gl.x, gl.z);
	const r = (gl.main ? 6 : 5.2) * s * (gl.weight > 3 ? 1.12 : 1);
	g.fillStyle = PALETTE.glyphFill;
	g.strokeStyle = PALETTE.glyphStroke;
	g.lineWidth = (gl.main ? 3 : 2.5) * s;
	g.lineJoin = "round";
	if (gl.capsule) {
		// elongate along the local track direction so the capsule spans the bundle
		const span = Math.max(w * (gl.colors.length - 1) * 1.35, r * 1.6);
		g.save();
		g.translate(sx, sy);
		g.rotate(Math.atan2(gl.dir[1], gl.dir[0]) + Math.PI / 2);
		g.beginPath();
		g.roundRect(-r, -(r + span / 2), r * 2, r * 2 + span, r);
		g.fill(); g.stroke();
		g.restore();
	} else {
		g.beginPath();
		g.arc(sx, sy, r, 0, Math.PI * 2);
		g.fill(); g.stroke();
	}
	// NOTE: the accessibility badge is drawn by drawLabels, inline after the
	// station name — anchoring it to the dot while the label floats produced
	// misaligned orphan badges (play-test feedback).
}

function drawBundleChips(g, vp, alpha) {
	const s = uiScale();
	g.globalAlpha = alpha;
	for (const b of state.bundles) {
		if (b.x < vp.l || b.x > vp.r || b.z < vp.t || b.z > vp.b) continue;
		const bullets = [];
		for (const c of b.colors) for (const n of c.numbers) bullets.push({ hex: c.hex, n });
		if (bullets.length < 2) continue;
		const shown = bullets.slice(0, MAX_CHIP_BULLETS);
		const [ax, ay] = worldToScreen(b.x, b.z);
		const R = 8.5 * s, pitch = 23 * s, h = 28 * s;
		const w = pitch * (shown.length - 1) + R * 2 + 17 * s;
		// hang the chip off the bundle on its normal side, with a short leader line
		const lead = 18 * s;
		const cx = ax + b.nx * (lead + w / 2), cy = ay + b.nz * (lead + h / 2);
		g.strokeStyle = PALETTE.leader;
		g.lineWidth = 1.5;
		g.beginPath();
		g.moveTo(ax + b.nx * 6 * s, ay + b.nz * 6 * s);
		g.lineTo(cx - b.nx * w / 2, cy - b.nz * h / 2);
		g.stroke();
		labelObstacles.push([cx - w / 2 - 2, cy - h / 2 - 2, cx + w / 2 + 2, cy + h / 2 + 2]);
		chipRect(g, cx - w / 2, cy - h / 2, w, h, h / 2);
		g.font = "700 " + (12 * s).toFixed(1) + "px " + FONT;
		g.textAlign = "center";
		g.textBaseline = "middle";
		shown.forEach((bl, i) => {
			const bx = cx - w / 2 + 8.5 * s + R + i * pitch;
			g.fillStyle = bl.hex;
			g.beginPath(); g.arc(bx, cy, R, 0, Math.PI * 2); g.fill();
			g.fillStyle = "#fff";      // bullets are always white-on-colour
			g.fillText(String(bl.n).slice(0, 2), bx, cy + 0.5);
		});
		g.textBaseline = "alphabetic";
	}
	g.globalAlpha = 1;
}

/* ---- labels: greedy decluttering with per-label alignment candidates ---- */

const FONT = '"Helvetica Neue", Helvetica, Arial, sans-serif';

/**
 * While a journey is selected ONLY its own stations keep their name (and sub-label);
 * every other station stays as a dim dot. Thomas's play-test: a highlighted journey
 * across a busy network was unreadable under the full label set.
 */
function labelVisibleFor(gl, sel) { return !sel || sel.partIds.has(gl.partId); }

function drawLabels(g, vp, sel) {
	const s = uiScale();
	const placed = labelObstacles.slice(); // chips + pins claimed their space first
	// journey stations claim space first, then the busiest stations
	const order = state.glyphs.slice().sort((a, b) => {
		const ja = sel && sel.partIds.has(a.partId) ? 1 : 0;
		const jb = sel && sel.partIds.has(b.partId) ? 1 : 0;
		if (ja !== jb) return jb - ja;
		if (a.main !== b.main) return a.main ? -1 : 1;
		return b.weight - a.weight;
	});

	for (const gl of order) {
		if (!labelVisibleFor(gl, sel)) continue;
		if (gl.x < vp.l || gl.x > vp.r || gl.z < vp.t || gl.z > vp.b) continue;
		const [sx, sy] = worldToScreen(gl.x, gl.z);
		if (sx < -80 || sy < -40 || sx > canvas.clientWidth + 80 || sy > canvas.clientHeight + 40) continue;
		const inJourney = sel && sel.partIds.has(gl.partId);
		const big = gl.main && gl.weight >= 3;
		const size = (big ? 15 : gl.main ? 14 : 12.5) * s;
		const text = gl.main ? gl.label : (gl.sub || gl.label);
		if (!text) continue;
		g.font = (gl.main ? "600 " : "500 ") + size.toFixed(1) + "px " + FONT;
		const tw = g.measureText(text).width;
		const th = size * 1.05;
		const pad = 7 * s;
		const r = 7 * s;
		// Accessibility badge rides INLINE after the name (mock style) so it can
		// never detach from a decluttered label; its width counts toward collision.
		const withBadge = gl.accessible && ACCESS_ICON.complete && ACCESS_ICON.naturalWidth > 0
			&& state.view.scale > 0.28;
		const bw = withBadge ? size * 0.92 : 0;
		const fullW = tw + (withBadge ? bw + 4 * s : 0);

		// Candidate anchors. Order depends on the local track direction so the text
		// starts on the side the ribbon does NOT run through: a vertical trunk gets
		// the mock's right/left placement, a horizontal or diagonal line gets
		// above/below first (a beside-label there would sit on the ribbon).
		const right = { x: sx + r + pad, y: sy + th * 0.35, align: "left" };
		const left = { x: sx - r - pad, y: sy + th * 0.35, align: "right" };
		const above = { x: sx, y: sy - r - pad, align: "center" };
		const below = { x: sx, y: sy + r + pad + th * 0.85, align: "center" };
		const horizontalTrack = Math.abs(gl.dir[0]) >= Math.abs(gl.dir[1]);
		const near = horizontalTrack ? [above, below, right, left] : [right, left, above, below];
		// Second ring: the same four sides pushed one marker-width further out, so a
		// station whose dot is covered by the destination pin or a chip (the Airport
		// case) still names itself beside the marker instead of vanishing.
		const far = 17 * s;
		const cands = near.concat(near.map((c) => ({
			x: c.x + (c.align === "left" ? far : c.align === "right" ? -far : 0),
			y: c.y + (c.align === "center" ? (c === above ? -far : far) : 0),
			align: c.align,
		})));
		let chosen = null;
		for (const c of cands) {
			const x0 = c.align === "left" ? c.x : c.align === "right" ? c.x - fullW : c.x - fullW / 2;
			const box = [x0 - 2, c.y - th, x0 + fullW + 2, c.y + th * 0.3];
			if (!placed.some((p) => box[0] < p[2] && box[2] > p[0] && box[1] < p[3] && box[3] > p[1])) {
				chosen = { c, box, x0 };
				break;
			}
		}
		if (!chosen) continue;               // no room at this zoom — drop the label
		placed.push(chosen.box);

		g.globalAlpha = sel ? (inJourney ? 1 : PALETTE.dim) : 1;
		g.textAlign = "left";                 // chosen.x0 already encodes the alignment
		g.lineJoin = "round";
		g.miterLimit = 2;
		g.lineWidth = 4 * s;
		g.strokeStyle = PALETTE.paper;                // halo, so labels survive over ribbons
		g.strokeText(text, chosen.x0, chosen.c.y);
		g.fillStyle = gl.main ? PALETTE.ink : PALETTE.ink2;
		g.fillText(text, chosen.x0, chosen.c.y);
		if (withBadge) {
			// inline after the name, centred on its cap height — never orphaned
			g.drawImage(ACCESS_ICON, chosen.x0 + tw + 4 * s, chosen.c.y - size * 0.36 - bw / 2, bw, bw);
		}

		// secondary parts of a split station also name themselves faintly
		if (gl.main && gl.sub) {
			const s2 = size * 0.82;
			g.font = "500 " + s2.toFixed(1) + "px " + FONT;
			g.lineWidth = 3.5 * s;
			g.strokeText(gl.sub, chosen.x0, chosen.c.y + s2 * 1.25);
			g.fillStyle = PALETTE.ink2;
			g.fillText(gl.sub, chosen.x0, chosen.c.y + s2 * 1.25);
		}
		g.globalAlpha = 1;
	}
	g.textAlign = "left";
}

/* ---- selected-journey overlay ---- */

function drawJourneyOverlay(g, vp) {
	const sel = state.selection;
	const s = uiScale();
	const w = ribbonWidth();
	const gap = Math.max(0.6, w * 0.16);

	// soft under-glow, then the crisp ribbon on top (Transit-app treatment)
	g.lineCap = "round";
	g.lineJoin = "round";
	for (const pass of [0, 1]) {
		for (const rb of state.ribbons) {
			if (!sel.ribbonKeys.has(rb.id)) continue;
			const pts = toScreenPath(rb.pts);
			if (pts.length < 2) continue;
			const line = offsetPolyline(pts, (rb.idx - (rb.count - 1) / 2) * (w + gap) * (rb.offSign || 1));
			if (pass === 0) {
				g.save();
				g.globalAlpha = 0.3;
				g.shadowColor = rb.hex;
				g.shadowBlur = 16 * s;
				g.strokeStyle = rb.hex;
				g.lineWidth = w * 2.4;
				strokePath(g, line);
				g.restore();
			} else {
				g.globalAlpha = 1;
				g.strokeStyle = rb.hex;
				g.lineWidth = rb.mode === "boat" ? w * 0.72 : w;
				g.setLineDash(rb.mode === "boat" ? [1.5, w * 1.9] : []);
				strokePath(g, line);
				g.setLineDash([]);
			}
		}
	}

	// ride legs with no schematic segment at all (a stop pair the network never joins,
	// e.g. a route whose mapdata dropped a leg): the straight dashed fallback
	for (const fb of sel.fallbacks || []) {
		const a = worldToScreen(fb.ax, fb.az), b = worldToScreen(fb.bx, fb.bz);
		g.strokeStyle = fb.hex;
		g.lineWidth = w * 0.8;
		g.setLineDash([w * 1.4, w * 1.4]);
		g.beginPath(); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); g.stroke();
		g.setLineDash([]);
	}

	// journey walks (part-to-part links used by the itinerary)
	for (const wk of sel.walks) {
		const a = worldToScreen(wk.ax, wk.az), b = worldToScreen(wk.bx, wk.bz);
		g.strokeStyle = PALETTE.ink2;
		g.lineWidth = 3 * s;
		g.setLineDash([1.5, 7]);
		g.beginPath(); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); g.stroke();
		g.setLineDash([]);
		drawWalkChip(g, (a[0] + b[0]) / 2, (a[1] + b[1]) / 2, fmtMeters(wk.dist));
	}

	// journey stations at full strength
	for (const gl of state.glyphs) {
		if (!sel.partIds.has(gl.partId)) continue;
		drawGlyph(g, gl, s, w);
	}

	// origin ring
	if (sel.origin) {
		const [ox, oy] = worldToScreen(sel.origin[0], sel.origin[1]);
		g.beginPath();
		g.arc(ox, oy, 13 * s, 0, Math.PI * 2);
		g.strokeStyle = PALETTE.accent;
		g.globalAlpha = 0.55;
		g.lineWidth = 2 * s;
		g.stroke();
		g.globalAlpha = 1;
	}
	// destination pin
	if (sel.dest) {
		const [dx, dy] = worldToScreen(sel.dest[0], sel.dest[1]);
		labelObstacles.push([dx - 15 * s, dy - 31 * s, dx + 15 * s, dy + 4 * s]);
		g.save();
		g.shadowColor = PALETTE.chipShadow;
		g.shadowBlur = 6; g.shadowOffsetY = 2;
		g.translate(dx, dy);
		g.scale(s, s);
		g.fillStyle = PALETTE.pin;
		g.fill(P_PIN);
		g.shadowColor = "transparent";
		g.fillStyle = "#fff";
		g.beginPath(); g.arc(0, -19.5, 5, 0, Math.PI * 2); g.fill();
		g.restore();
	}
}

/* ============================================================================
 * 8. render loop (dynamic layer: live trains, callouts, hover)
 * ========================================================================== */

function frame() {
	if (!canvas.clientWidth || !canvas.clientHeight) { requestAnimationFrame(frame); return; }
	const dpr = resize();

	// pan/zoom fast path: reuse the cached static layer with an offset/scale blit and
	// cap full redraws at ~10/s (the 25 % cull margin keeps the edges filled)
	let blitStale = false;
	if (staticDirty) {
		if (staticRenderedView && performance.now() - staticRenderedView.at < 100) {
			blitStale = true;
		} else {
			drawStatic(dpr);
			staticRenderedView = { x: state.view.x, z: state.view.z, scale: state.view.scale, at: performance.now() };
			staticDirty = false;
		}
	}
	ctx.setTransform(1, 0, 0, 1, 0, 0);
	ctx.clearRect(0, 0, canvas.width, canvas.height);
	if (blitStale && staticRenderedView) {
		const sv = staticRenderedView;
		const f = state.view.scale / sv.scale;
		const cx = canvas.width / 2, cy = canvas.height / 2;
		ctx.save();
		ctx.fillStyle = PALETTE.paper;
		ctx.fillRect(0, 0, canvas.width, canvas.height);
		ctx.translate(cx + (sv.x - state.view.x) * state.view.scale * dpr,
			cy + (sv.z - state.view.z) * state.view.scale * dpr);
		ctx.scale(f, f);
		ctx.drawImage(staticCanvas, -cx, -cy);
		ctx.restore();
	} else {
		ctx.drawImage(staticCanvas, 0, 0);
	}
	ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

	drawVehicles();

	if (!DEMO && state.lastEventAt && now() - state.lastEventAt > 5000) setStatus("connecting");
	requestAnimationFrame(frame);
}

function drawVehicles() {
	const s = uiScale();
	const sel = state.selection;
	// interpolation delay from the measured stream cadence (app.js's rule)
	const delay = state.emaInterval
		? clamp(state.emaInterval * 1.25 + 120, 250, 2000)
		: state.updateMillis * 1.5;
	const renderTime = now() - delay;
	const frameNow = now();
	const dtSec = state.lastFrameAt ? Math.min(0.1, (frameNow - state.lastFrameAt) / 1000) : 0.016;
	state.lastFrameAt = frameNow;
	const smoothing = 1 - Math.exp(-dtSec / 0.12);

	const callouts = [];
	for (const [id, rec] of state.vehicles) {
		if (!modeVisible(rec.mode || "train")) { rec.screen = null; continue; }
		const p = vehiclePos(rec, renderTime);
		if (!p) { rec.screen = null; continue; }
		if (!rec.disp || Math.hypot(p.x - rec.disp.x, p.z - rec.disp.z) > 64) rec.disp = { x: p.x, z: p.z };
		else {
			rec.disp.x += (p.x - rec.disp.x) * smoothing;
			rec.disp.z += (p.z - rec.disp.z) * smoothing;
		}
		// interpolation runs on the REAL rails, so where the tracks spread the train
		// sits beside the schematic line: pull the puck onto its own line's nearest
		// drawn segment, but only when one is genuinely close (a depot move keeps its
		// true position rather than being yanked onto the nearest passenger line).
		const ride = snapToLine(rec.route ? colorHex(rec.route.color) : null, rec.disp.x, rec.disp.z, VEHICLE_SNAP);
		rec.screen = ride ? worldToScreen(ride[0], ride[1]) : worldToScreen(rec.disp.x, rec.disp.z);
		const [sx, sy] = rec.screen;
		if (sx < -60 || sy < -60 || sx > canvas.clientWidth + 60 || sy > canvas.clientHeight + 60) continue;

		const color = rec.route ? colorHex(rec.route.color) : PALETTE.ink2;
		const onJourney = sel && rec.route && sel.routeIds.has(rec.route.id);
		ctx.globalAlpha = sel && !onJourney ? 0.2 : 1;

		const r = 11 * s;
		ctx.save();
		ctx.shadowColor = PALETTE.chipShadow;
		ctx.shadowBlur = 6; ctx.shadowOffsetY = 2;
		ctx.beginPath(); ctx.arc(sx, sy, r, 0, Math.PI * 2);
		ctx.fillStyle = PALETTE.chip; ctx.fill();
		ctx.restore();
		ctx.lineWidth = 3.2 * s;
		ctx.strokeStyle = color;
		ctx.beginPath(); ctx.arc(sx, sy, r, 0, Math.PI * 2); ctx.stroke();
		drawIcon(ctx, rec.mode === "boat" ? P_BOAT : P_TRAIN, sx, sy, 14 * s, color);
		ctx.globalAlpha = 1;

		// "due in N min" callout when this train is the one the journey boards
		if (sel && sel.boardPlatform && rec.data.nPlat === sel.boardPlatform) {
			const lbl = rec.route ? routeServiceLabel({
				number: rec.route.number, display: routeBase(rec.route.name), name: rec.route.name,
			}) : "";
			callouts.push({ sx, sy, color, text: (lbl || "Train") + " · due in " + etaMinutes(rec, sel.boardPlatform) + " min" });
		}
	}

	for (const c of callouts) drawCallout(ctx, c.sx + 21 * s, c.sy - 13 * s, c.text);
}

/**
 * Nearest point of `hex`'s nearest drawn segment, or null when nothing of that colour
 * is within `maxDist` blocks.
 */
function snapToLine(hex, x, z, maxDist) {
	if (!hex) return null;
	const segs = state.segsByColor.get(hex);
	if (!segs || !segs.length) return null;
	let best = null, bestD = maxDist;
	for (const s of segs) {
		const bb = s.bbox;
		if (x < bb[0] - maxDist || x > bb[2] + maxDist || z < bb[1] - maxDist || z > bb[3] + maxDist) continue;
		const r = nearestOnPolyline([x, z], s.pts);
		if (r.point && r.dist < bestD) { bestD = r.dist; best = r.point; }
	}
	return best;
}

/** Rough ETA: straight-line distance to the platform at the train's current speed. */
function etaMinutes(rec, platformId) {
	const pl = state.platforms.get(platformId);
	if (!pl || !rec.disp) return 1;
	const d = Math.hypot(pl.xz[0] - rec.disp.x, pl.xz[1] - rec.disp.z);
	const mps = Math.max(2, (rec.data.kmh || 0) / 3.6);
	return clamp(Math.round(d / mps / 60), 1, 30);
}

function drawCallout(g, x, y, text) {
	const s = uiScale();
	const h = 27 * s, fs = 13 * s;
	g.font = "600 " + fs.toFixed(1) + "px " + FONT;
	const tw = g.measureText(text).width;
	const w = 25 * s + tw + 12 * s;
	chipRect(g, x, y, w, h, h / 2);
	const pulse = 0.55 + 0.45 * Math.sin(now() / 320);
	g.globalAlpha = pulse;
	g.fillStyle = PALETTE.live;
	g.beginPath(); g.arc(x + 14 * s, y + h / 2, 4 * s, 0, Math.PI * 2); g.fill();
	g.globalAlpha = 1;
	g.fillStyle = PALETTE.ink;
	g.textAlign = "left";
	g.textBaseline = "middle";
	g.fillText(text, x + 25 * s, y + h / 2 + 0.5);
	g.textBaseline = "alphabetic";
}

/* ============================================================================
 * 9. live stream (SSE) + interpolation
 * ========================================================================== */

function openStream() {
	setStatus("connecting");
	const es = new EventSource(`${API}/stream?dimension=${state.dim}`);
	state.es = es;
	es.addEventListener("full", (ev) => handleFrame(JSON.parse(ev.data), true));
	es.addEventListener("delta", (ev) => handleFrame(JSON.parse(ev.data), false));
	es.onopen = () => hideBanner();
	es.onerror = () => setStatus("offline");     // EventSource retries on its own
}

function handleFrame(f, isFull) {
	if (f.dimension !== undefined && f.dimension !== state.dim && !DEMO) return;
	state.lastEventAt = now();
	setStatus("live");

	if (state.lastServerTime) {
		const d = f.serverTime - state.lastServerTime;
		if (d > 0 && d < 10000) state.emaInterval = state.emaInterval ? state.emaInterval * 0.8 + d * 0.2 : d;
	}
	state.lastServerTime = f.serverTime;

	if (isFull) {
		for (const id of [...state.vehicles.keys()]) {
			if (!(f.vehicles || []).some((v) => v.id === id)) state.vehicles.delete(id);
		}
	}
	(f.removed || []).forEach((id) => state.vehicles.delete(id));

	for (const v of f.vehicles || []) {
		let rec = state.vehicles.get(v.id);
		if (!rec) rec = { samples: [], route: null, consist: null, data: {} };
		if (v.route) rec.route = v.route;
		if (v.consist) rec.consist = v.consist;
		rec.data = { ...rec.data, ...v };
		const rail = v.rail ? state.rails.get(v.rail) : null;
		rec.mode = rail ? rail.mode : rec.mode;

		// railT measures progress along the PATH segment; the rail polyline has its own
		// canonical direction. If the mirrored parameter lands closer to the reported
		// world position, this rail runs opposite to the path — flip it (app.js's fix
		// for "glides the wrong way then jumps").
		let railT = v.railT;
		if (v.rail && railT !== undefined && railT >= 0) {
			const a = railPoint(v.rail, railT), b = railPoint(v.rail, 1 - railT);
			if (a && b && Math.hypot(b[0] - v.x, b[1] - v.z) + 0.5 < Math.hypot(a[0] - v.x, a[1] - v.z)) railT = 1 - railT;
		}
		rec.samples.push({ t: f.serverTime, x: v.x, z: v.z, rail: v.rail, railT });
		if (rec.samples.length > 4) rec.samples.shift();
		state.vehicles.set(v.id, rec);
	}
}

function railPoint(railId, t) {
	const r = state.rails.get(railId);
	if (!r || r.pts.length < 2) return null;
	const target = t * r.cum[r.cum.length - 1];
	let i = 1;
	while (i < r.cum.length - 1 && r.cum[i] < target) i++;
	const seg = r.cum[i] - r.cum[i - 1] || 1;
	const f = (target - r.cum[i - 1]) / seg;
	const a = r.pts[i - 1], b = r.pts[i];
	return [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f];
}

function vehiclePos(rec, renderTime) {
	const s = rec.samples;
	if (!s.length) return null;
	if (s.length === 1) return { x: s[0].x, z: s[0].z };
	const last = s[s.length - 1], prev = s[s.length - 2];
	if (renderTime > last.t) {
		// past the newest sample: extrapolate briefly instead of freezing
		const dt = Math.min(renderTime - last.t, 1000);
		const span = last.t - prev.t || 1;
		return { x: last.x + (last.x - prev.x) / span * dt, z: last.z + (last.z - prev.z) / span * dt };
	}
	let a = s[0], b = s[s.length - 1];
	for (let i = 1; i < s.length; i++) {
		a = s[i - 1]; b = s[i];
		if (s[i].t >= renderTime) break;
	}
	const span = b.t - a.t || 1;
	const f = clamp((renderTime - a.t) / span, 0, 1);
	// same rail on both samples → slide along the real curve
	if (a.rail && a.rail === b.rail && a.railT !== undefined && b.railT !== undefined) {
		const p = railPoint(a.rail, a.railT + (b.railT - a.railT) * f);
		if (p) return { x: p[0], z: p[1] };
	}
	return { x: a.x + (b.x - a.x) * f, z: a.z + (b.z - a.z) * f };
}

/* ============================================================================
 * 10. interaction
 * ========================================================================== */

function initUi() {
	$("zIn").onclick = () => zoomAt(canvas.clientWidth / 2, canvas.clientHeight / 2, 1.4);
	$("zOut").onclick = () => zoomAt(canvas.clientWidth / 2, canvas.clientHeight / 2, 1 / 1.4);
	$("zHome").onclick = fitView;

	let drag = null;
	canvas.addEventListener("pointerdown", (e) => {
		drag = { x: e.clientX, y: e.clientY, moved: false };
		canvas.setPointerCapture(e.pointerId);
		canvas.classList.add("dragging");
	});
	canvas.addEventListener("pointermove", (e) => {
		const rect = canvas.getBoundingClientRect();
		if (!drag) { hoverAt(e.clientX - rect.left, e.clientY - rect.top); return; }
		const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
		if (Math.abs(dx) + Math.abs(dy) > 3) drag.moved = true;   // 3 px click threshold
		state.view.x -= dx / state.view.scale;
		state.view.z -= dy / state.view.scale;
		drag.x = e.clientX; drag.y = e.clientY;
		$("mapTip").classList.add("hidden");
		invalidateStatic();
	});
	canvas.addEventListener("pointerup", (e) => {
		canvas.classList.remove("dragging");
		const rect = canvas.getBoundingClientRect();
		if (drag && !drag.moved) clickAt(e.clientX - rect.left, e.clientY - rect.top);
		drag = null;
	});
	canvas.addEventListener("pointerleave", () => $("mapTip").classList.add("hidden"));
	canvas.addEventListener("wheel", (e) => {
		e.preventDefault();
		const rect = canvas.getBoundingClientRect();
		zoomAt(e.clientX - rect.left, e.clientY - rect.top, Math.exp(-e.deltaY * 0.0015));
	}, { passive: false });

	document.addEventListener("keydown", (e) => {
		const tag = (e.target.tagName || "").toLowerCase();
		if (tag === "input" || tag === "select" || tag === "textarea") return;
		if (e.key === "Escape") clearSelection();
		else if (e.key.toLowerCase() === "f") fitView();
		else if (e.key.toLowerCase() === "h") {
			// the only way to reach the show-hidden pref (deliberately not a visible
			// control — hidden routes are depot moves riders never board)
			state.prefs.showHidden = !state.prefs.showHidden;
			savePrefs();
			prepareGeometry();
		}
	});
	window.addEventListener("resize", () => invalidateStatic());
}

function zoomAt(sx, sy, factor) {
	const v = state.view;
	const mx = sx - canvas.clientWidth / 2, my = sy - canvas.clientHeight / 2;
	const wx = v.x + mx / v.scale, wz = v.z + my / v.scale;
	v.scale = clamp(v.scale * factor, 0.02, 20);
	v.x = wx - mx / v.scale;
	v.z = wz - my / v.scale;
	invalidateStatic();
}

function glyphAt(sx, sy) {
	let best = null, bestD = 16 * uiScale();
	for (const gl of state.glyphs) {
		const [x, y] = worldToScreen(gl.x, gl.z);
		const d = Math.hypot(x - sx, y - sy);
		if (d < bestD) { best = gl; bestD = d; }
	}
	return best;
}
function trainAt(sx, sy) {
	let best = null, bestD = 16 * uiScale();
	for (const [id, rec] of state.vehicles) {
		if (!rec.screen) continue;
		const d = Math.hypot(rec.screen[0] - sx, rec.screen[1] - sy);
		if (d < bestD) { best = { id, rec }; bestD = d; }
	}
	return best;
}

function hoverAt(sx, sy) {
	const tip = $("mapTip");
	const t = trainAt(sx, sy);
	const gl = t ? null : glyphAt(sx, sy);
	canvas.classList.toggle("pointing", !!(t || gl));
	if (!t && !gl) { tip.classList.add("hidden"); return; }
	let html;
	if (t) {
		const r = t.rec.route;
		const color = r ? colorHex(r.color) : PALETTE.ink2;
		const lbl = r ? routeServiceLabel({ number: r.number, display: routeBase(r.name), name: r.name }) : "";
		const label = r ? (lbl ? lbl + " · " : "") + (routeBase(r.name) || "Train") : "Train";
		const dest = r && (r.dest || routeDest(r.name));
		html = `<span class="swatch" style="background:${color}"></span>${esc(label)}${dest ? ' <span style="color:#8a929c">to ' + esc(firstLang(dest)) + "</span>" : ""}`;
	} else {
		const st = state.stations.get(gl.stationId);
		html = `${esc(gl.label)}${gl.sub ? ' <span style="color:#8a929c">· ' + esc(gl.sub) + "</span>" : ""}`
			+ (st && st.accessible ? " " + ACCESS_IMG : "");
	}
	tip.innerHTML = html;
	tip.classList.remove("hidden");
	tip.style.left = (sx + 16) + "px";
	tip.style.top = (sy - 12) + "px";
}

function clickAt(sx, sy) {
	const gl = glyphAt(sx, sy);
	if (gl) { pickStation(gl.stationId, gl.partId); return; }
	if (trainAt(sx, sy)) return;      // clicking a train keeps the current selection
	clearSelection();
}

/* ============================================================================
 * 11. planner panel (DOM)
 * ========================================================================== */

/** Flat search index over stations and — for split stations — their parts. */
let searchIndex = [];
function rebuildSearchIndex() {
	searchIndex = [];
	for (const st of state.stations.values()) {
		const colors = new Set();
		for (const pid of st.platformIds) {
			const pl = state.platforms.get(pid);
			if (!pl) continue;
			for (const rid of pl.routeIds) {
				const rt = state.routes.get(rid);
				if (rt && (!rt.hidden || state.prefs.showHidden)) colors.add(rt.hex);
			}
		}
		searchIndex.push({ label: st.display, sub: "", stationId: st.id, partId: null, colors: [...colors], accessible: !!st.accessible });
		if (st.parts.length > 1) {
			for (const part of st.parts) {
				searchIndex.push({
					label: st.display, sub: part.sub || part.name || ("Part " + (part.index + 1)),
					stationId: st.id, partId: part.id, colors: [...colors],
					accessible: !!st.accessible,
				});
			}
		}
	}
	searchIndex.sort((a, b) => a.label.localeCompare(b.label) || (a.partId ? 1 : -1));
}

function entryText(e) { return e.sub ? e.label + " · " + e.sub : e.label; }

function searchEntries(q) {
	const s = (q || "").trim().toLowerCase();
	if (!s) return searchIndex.slice(0, 8);
	const starts = [], has = [];
	for (const e of searchIndex) {
		const t = entryText(e).toLowerCase();
		if (t.startsWith(s)) starts.push(e);
		else if (t.includes(s)) has.push(e);
	}
	return starts.concat(has).slice(0, 8);
}

function initPlanner() {
	setupCombo("inFrom", "acFrom", "from");
	setupCombo("inTo", "acTo", "to");

	$("swapBtn").onclick = () => {
		const p = state.plan;
		[p.from, p.to] = [p.to, p.from];
		syncFields();
		replan();
	};

	// departure time
	const menu = $("whenMenu");
	const openMenu = (on) => menu.classList.toggle("hidden", !on);
	$("whenBtn").onclick = (e) => { e.stopPropagation(); openMenu(menu.classList.contains("hidden")); };
	$("atBtn").onclick = (e) => { e.stopPropagation(); openMenu(true); $("whenAt").focus(); };
	menu.querySelector('[data-when="now"]').onclick = () => {
		state.plan.when = { mode: "now", at: null };
		$("whenLabel").textContent = "Leave now";
		openMenu(false);
		replan();
	};
	$("whenAt").onchange = (e) => {
		if (!e.target.value) return;
		state.plan.when = { mode: "at", at: e.target.value };
		$("whenLabel").textContent = "Leave " + e.target.value;
		openMenu(false);
		replan();
	};
	document.addEventListener("click", (e) => {
		if (!menu.contains(e.target) && e.target !== $("whenBtn") && e.target !== $("atBtn")) openMenu(false);
	});

	// preference chips: the three routing goals are exclusive, step-free is independent
	for (const chip of document.querySelectorAll(".prefs .chip")) {
		chip.onclick = () => {
			const key = chip.dataset.pref;
			if (key === "stepfree") {
				state.plan.prefs.stepFree = !state.plan.prefs.stepFree;
				chip.classList.toggle("on", state.plan.prefs.stepFree);
			} else {
				state.plan.prefs.mode = key;
				for (const c of document.querySelectorAll(".prefs .chip")) {
					if (c.dataset.pref !== "stepfree") c.classList.toggle("on", c === chip);
				}
			}
			savePrefs();
			replan();
		};
	}
	applyPrefChips();
}

function setupCombo(inputId, listId, which) {
	const input = $(inputId), list = $(listId);
	let active = -1, entries = [];
	const render = () => {
		entries = searchEntries(input.value);
		if (!entries.length) {
			list.innerHTML = '<div class="ac-empty">No matching station</div>';
		} else {
			list.innerHTML = entries.map((e, i) => `
				<div class="ac-row${i === active ? " active" : ""}" data-i="${i}">
					<span>${esc(e.label)}</span>
					${e.sub ? `<span class="sub">${esc(e.sub)}</span>` : ""}
					${e.accessible ? ACCESS_IMG : ""}
					<span class="dots">${e.colors.slice(0, 5).map((c) => `<i style="background:${c}"></i>`).join("")}</span>
				</div>`).join("");
			for (const row of list.querySelectorAll(".ac-row")) {
				row.onmousedown = (ev) => {
					ev.preventDefault();
					choose(entries[parseInt(row.dataset.i, 10)]);
				};
			}
		}
		list.classList.remove("hidden");
	};
	const choose = (e) => {
		if (!e) return;
		state.plan[which] = { stationId: e.stationId, partId: e.partId };
		input.value = entryText(e);
		list.classList.add("hidden");
		active = -1;
		replan();
	};
	input.addEventListener("focus", render);
	input.addEventListener("input", () => { active = -1; render(); });
	input.addEventListener("blur", () => setTimeout(() => list.classList.add("hidden"), 120));
	input.addEventListener("keydown", (e) => {
		if (e.key === "ArrowDown" || e.key === "ArrowUp") {
			e.preventDefault();
			active = clamp(active + (e.key === "ArrowDown" ? 1 : -1), 0, entries.length - 1);
			render();
		} else if (e.key === "Enter") {
			choose(entries[active >= 0 ? active : 0]);
		} else if (e.key === "Escape") {
			list.classList.add("hidden");
		}
	});
}

/** Clicking a station on the map fills the empty field (From first, then To). */
function pickStation(stationId, partId) {
	const p = state.plan;
	if (!p.from) p.from = { stationId, partId };
	else if (!p.to) p.to = { stationId, partId };
	else p.to = { stationId, partId };
	syncFields();
	replan();
}

function syncFields() {
	const label = (sel) => {
		if (!sel) return "";
		const st = state.stations.get(sel.stationId);
		if (!st) return "";
		if (!sel.partId) return st.display;
		const part = st.parts.find((p) => p.id === sel.partId);
		return part && (part.sub || part.name) ? st.display + " · " + (part.sub || part.name) : st.display;
	};
	$("inFrom").value = label(state.plan.from);
	$("inTo").value = label(state.plan.to);
}

function applyPrefChips() {
	for (const c of document.querySelectorAll(".prefs .chip")) {
		if (c.dataset.pref === "stepfree") c.classList.toggle("on", state.plan.prefs.stepFree);
		else c.classList.toggle("on", c.dataset.pref === state.plan.prefs.mode);
	}
}

/** Departure instant the planner should use. */
function departAtMs() {
	const w = state.plan.when;
	if (w.mode !== "at" || !w.at) return now();
	const [h, m] = w.at.split(":").map(Number);
	const d = new Date();
	d.setHours(h, m, 0, 0);
	if (d.getTime() < now() - 3600e3) d.setDate(d.getDate() + 1);   // past time = tomorrow
	return d.getTime();
}

/**
 * Re-run the planner. `keepSelection` is for the live ticker's minute-boundary replan:
 * when the option set comes back identical (same routes, same platforms) the rider's
 * expanded card stays expanded instead of snapping back to the first option.
 */
function replan(keepSelection) {
	const p = state.plan;
	const prevSig = p.journeys.map((j) => j.signature || "").join("~");
	const prevIndex = p.selectedIndex;
	p.journeys = [];
	p.selectedIndex = -1;
	clearSelection();
	if (p.from && p.to && p.from.stationId !== p.to.stationId) {
		if (!p.graph) p.graph = buildGraph();
		p.journeys = planJourneys(p.graph, p.from.stationId, p.to.stationId, p.prefs, departAtMs(),
			{ fromPartId: p.from.partId, toPartId: p.to.partId });
		if (p.journeys.length) {
			const sig = p.journeys.map((j) => j.signature || "").join("~");
			const keep = keepSelection && sig === prevSig && prevIndex >= 0 && prevIndex < p.journeys.length;
			selectOption(keep ? prevIndex : 0);
		}
	}
	p.lastBucket = Math.floor(departAtMs() / 60000);
	p.updatedAt = now();
	renderOptions();
}

function renderOptions() {
	const p = state.plan;
	const box = $("options");
	$("liveOpts").textContent = p.journeys.length + " OPTION" + (p.journeys.length === 1 ? "" : "S");
	const hint = $("plannerHint");
	if (!p.from || !p.to) {
		box.innerHTML = "";
		hint.textContent = "Pick a start and a destination — or click two stations on the map.";
		hint.classList.remove("hidden");
		return;
	}
	if (!p.journeys.length) {
		box.innerHTML = "";
		hint.textContent = p.prefs.stepFree
			? "No step-free journey found — try clearing the step-free filter."
			: "No journey found between these two stations.";
		hint.classList.remove("hidden");
		return;
	}
	hint.classList.add("hidden");
	box.innerHTML = p.journeys.map((j, i) => optionCard(j, i === p.selectedIndex)).join("");
	for (const card of box.querySelectorAll(".opt")) {
		card.onclick = () => selectOption(parseInt(card.dataset.i, 10));
	}
}

/** The bullet a planner row shows for a ride leg: normalised, direction stripped. */
function legLabel(leg) {
	return routeServiceLabel({ number: leg.number, display: leg.routeName, name: leg.routeName });
}

function optionCard(j, selected) {
	const seq = [];
	for (const leg of j.legs) {
		if (leg.kind === "ride") {
			seq.push(`<span class="bullet" style="background:${leg.color}">${esc(legLabel(leg))}</span>`);
		} else {
			const icon = leg.mode === "boat" ? "i-boat" : "i-walk";
			seq.push(`<span class="modechip"><svg class="icon sm"><use href="#${icon}"/></svg>${esc(fmtMeters(leg.meters))}</span>`);
		}
	}
	const chev = `<span class="sep"><svg class="icon sm"><use href="#i-chev"/></svg></span>`;
	const first = j.legs.find((l) => l.kind === "ride");
	const liveMin = first ? Math.max(0, Math.round((first.depMs - now()) / 60000)) : null;
	return `
	<div class="opt${selected ? " sel" : ""}" data-i="${j.index}">
		<div class="opt-head">
			<span class="opt-times">${fmtTime(j.departMs)} – ${fmtTime(j.arriveMs)}</span>
			<span class="opt-dur">${fmtMin(j.durationMs / 1000)}</span>
		</div>
		<div class="opt-route">${seq.join(chev)}</div>
		${first ? `<div class="opt-live"><span class="dot pulse"></span>${esc(legLabel(first) || first.routeName)} departs ${liveMin <= 0 ? "now" : "in " + liveMin + " min"}</div>` : ""}
		${j.tags && j.tags.length ? `<div class="opt-sub">${j.accessible ? ACCESS_IMG : ""}${esc(j.tags.join(" · "))}</div>` : ""}
		${selected ? itineraryHtml(j) : ""}
	</div>`;
}

/**
 * The expanded itinerary. A walk that sits BETWEEN two rides is folded into the next
 * boarding row as a transfer chip (the mock's Baker City Central row); a walk that ends
 * the journey gets its own row with the dotted rail.
 */
function itineraryHtml(j) {
	const rows = [];
	let pendingWalk = null;
	j.legs.forEach((leg, i) => {
		if (leg.kind === "ride") {
			const from = state.platforms.get(leg.fromPlatform);
			const badge = from && from.accessible ? ACCESS_IMG : "";
			const sub = pendingWalk
				? `<span class="livegreen">${esc(legLabel(leg) || leg.routeName)} departs ${fmtTime(leg.depMs)}</span>· Platform ${esc(from ? from.name || "—" : "—")} ${badge}`
				: `Board <b>${esc(legLabel(leg) || leg.routeName)}</b>${leg.headsign ? " toward " + esc(leg.headsign) : ""} · Platform ${esc(from ? from.name || "—" : "—")} ${badge}`;
			rows.push(`
				<div class="t">${fmtTime(leg.depMs)}</div>
				<div class="n"><span class="node" style="border-color:${leg.color}"></span><span class="rail" style="background:${leg.color}"></span></div>
				<div class="c">
					<div class="stop-name">${esc(stationLabel(leg.fromStation, leg.fromPart))}</div>
					${pendingWalk ? walkChipHtml(pendingWalk, true) : ""}
					<div class="stop-sub">${sub}</div>
					<div class="ride">${leg.stopCount} stop${leg.stopCount === 1 ? "" : "s"} · ${fmtMin((leg.arrMs - leg.depMs) / 1000)}</div>
				</div>`);
			pendingWalk = null;
		} else {
			const next = j.legs[i + 1];
			if (next && next.kind === "ride") { pendingWalk = leg; return; }
			rows.push(`
				<div class="t">${fmtTime(leg.depMs)}</div>
				<div class="n"><span class="node" style="border-color:${PALETTE.ink2}"></span><span class="rail walk"></span></div>
				<div class="c">
					<div class="stop-name">${esc(stationLabel(leg.fromStation, leg.fromPart))}</div>
					${walkChipHtml(leg, false)}
					${leg.note ? `<div class="stop-sub">${esc(leg.note)}</div>` : ""}
				</div>`);
		}
	});
	rows.push(`
		<div class="t">${fmtTime(j.arriveMs)}</div>
		<div class="n"><span class="node pin"></span></div>
		<div class="c"><div class="stop-name">${esc(stationLabel(j.toStation, j.toPart))}</div></div>`);
	return `<div class="itin">${rows.join("")}</div>`;
}

function walkChipHtml(leg, transfer) {
	const icon = leg.mode === "boat" ? "i-boat" : "i-walk";
	const label = transfer
		? `Transfer · ${fmtMeters(leg.meters)} · ${fmtMin(leg.seconds)}`
		: `Walk ${fmtMeters(leg.meters)} · ${fmtMin(leg.seconds)}${leg.note ? " · " + leg.note : ""}`;
	return `<div class="tchip"><svg class="icon"><use href="#${icon}"/></svg>${esc(label)}${leg.accessible ? ACCESS_IMG : ""}</div>`;
}

function stationLabel(stationId, partId) {
	const st = state.stations.get(stationId);
	if (!st) return stationId || "";
	if (!partId) return st.display;
	const part = st.parts.find((p) => p.id === partId);
	const sub = part && (part.name || part.sub);
	return sub ? st.display + " · " + sub : st.display;
}

function selectOption(i) {
	state.plan.selectedIndex = i;
	const j = state.plan.journeys[i];
	if (j) selectJourney(j); else clearSelection();
	renderOptions();
}

/**
 * Fade the network and light the journey. Collects the ribbon ids (key|colour) the
 * journey rides, the station parts it touches, and its origin/destination anchors.
 */
function selectJourney(journey) {
	const ribbonKeys = new Set();
	const partIds = new Set();
	const routeIds = new Set();
	const walks = [];
	const fallbacks = [];
	let origin = null, dest = null, boardPlatform = null;

	const partOf = (platformId) => {
		const pl = state.platforms.get(platformId);
		return pl ? pl.partId : null;
	};
	const posOf = (platformId, partId) => {
		const pl = state.platforms.get(platformId);
		if (pl) return pl.xz;
		for (const st of state.stations.values()) {
			const part = st.parts.find((p) => p.id === partId);
			if (part) return [part.x, part.z];
		}
		return null;
	};

	for (const leg of journey.legs) {
		if (leg.kind === "ride") {
			const rt = state.routes.get(leg.routeId);
			if (rt) routeIds.add(rt.id);
			if (!boardPlatform) boardPlatform = leg.fromPlatform;
			// every schematic segment the ride's legs traverse, in this line's colour
			const covered = legSegmentIds(leg);
			for (const key of covered.ids) ribbonKeys.add(key);
			for (const fb of covered.fallbacks) fallbacks.push(fb);
			for (const pid of leg.stops || [leg.fromPlatform, leg.toPlatform]) {
				const p = partOf(pid);
				if (p) partIds.add(p);
			}
		} else {
			const a = posOf(leg.fromPlatform, leg.fromPart), b = posOf(leg.toPlatform, leg.toPart);
			const pa = partOf(leg.fromPlatform) || leg.fromPart, pb = partOf(leg.toPlatform) || leg.toPart;
			if (pa) partIds.add(pa);
			if (pb) partIds.add(pb);
			// only concourse-scale walks get a connector on the map; a cross-platform
			// change inside one part would just stipple the station glyph (the
			// itinerary's transfer chip already tells the rider about it)
			if (a && b && pa !== pb && dist(a, b) > 0.5) {
				walks.push({ ax: a[0], az: a[1], bx: b[0], bz: b[1], dist: leg.meters });
			}
		}
	}
	const firstLeg = journey.legs[0], lastLeg = journey.legs[journey.legs.length - 1];
	if (firstLeg) origin = posOf(firstLeg.fromPlatform, firstLeg.fromPart);
	if (lastLeg) dest = posOf(lastLeg.toPlatform, lastLeg.toPart);

	state.selection = { journey, ribbonKeys, partIds, routeIds, walks, fallbacks, origin, dest, boardPlatform };
	invalidateStatic();
}

/**
 * The DRAWN segments one ride leg runs over, plus the straight fallbacks for stop pairs
 * that have no segment at all.
 *
 * Every stop pair maps to a (colour, part-pair) segment. A pair whose segment was
 * suppressed as an express lights the CHAIN underneath it instead (`coveredBy`), so a
 * rider on the express still sees a continuous highlighted line through the stations
 * the express skips.
 */
function legSegmentIds(leg) {
	const rt = state.routes.get(leg.routeId);
	if (!rt) return { ids: [], fallbacks: [] };
	const ids = [], fallbacks = [];
	const plats = rt.platforms || [];
	const fromIdx = plats.indexOf(leg.fromPlatform);
	const toIdx = plats.indexOf(leg.toPlatform);
	const lo = fromIdx >= 0 && toIdx >= 0 ? Math.min(fromIdx, toIdx) : 0;
	const hi = fromIdx >= 0 && toIdx >= 0 ? Math.max(fromIdx, toIdx) : plats.length - 1;
	for (let i = lo; i < hi; i++) {
		const lg = state.legs.get(rt.id + "|" + i);
		if (!lg) continue;
		const a = partOfPlatform(lg.from), b = partOfPlatform(lg.to);
		const seg = a && b && a !== b ? state.segByPair.get(rt.hex + "|" + pairKeyOf(a, b)) : null;
		if (seg && !seg.suppressed) { ids.push(seg.id); continue; }
		if (seg && seg.suppressed && seg.coveredBy.length) { ids.push(...seg.coveredBy); continue; }
		const pa = state.platforms.get(lg.from), pb = state.platforms.get(lg.to);
		if (pa && pb) fallbacks.push({ ax: pa.xz[0], az: pa.xz[1], bx: pb.xz[0], bz: pb.xz[1], hex: rt.hex });
	}
	return { ids, fallbacks };
}

function clearSelection() {
	if (!state.selection) return;
	state.selection = null;
	// keep the panel honest: no journey is highlighted once the map selection goes
	if (state.plan.selectedIndex >= 0) {
		state.plan.selectedIndex = -1;
		renderOptions();
	}
	invalidateStatic();
}

/* live row ticker */
function tickLive() {
	const age = state.plan.updatedAt ? Math.round((now() - state.plan.updatedAt) / 1000) : 0;
	$("liveAge").textContent = "UPDATED " + age + "S AGO";
	const row = document.querySelector(".liverow");
	const off = state.status === "offline";
	row.classList.toggle("offline", off);
	$("liveWord").textContent = off ? "OFFLINE" : state.status === "live" ? "LIVE" : "CONNECTING";
	// keep the "departs in N min" lines honest without a full re-render storm
	if (state.plan.journeys.length) {
		for (const el of document.querySelectorAll(".opt-live")) el.dataset.tick = age;
	}
	// "Leave now" walks forward with the clock: replan on each minute boundary (the
	// planner memoises by minute bucket, so this costs one search a minute, not one
	// a second) and keep the rider's expanded option if nothing actually changed.
	if (state.plan.when.mode === "now" && state.plan.from && state.plan.to) {
		const bucket = Math.floor(now() / 60000);
		if (bucket !== state.plan.lastBucket) replan(true);
	}
}

function setStatus(s) {
	if (state.status === s) return;
	state.status = s;
}
function showBanner(msg) { const b = $("banner"); b.textContent = msg; b.classList.remove("hidden"); }
function hideBanner() { $("banner").classList.add("hidden"); }

function savePrefs() {
	try {
		localStorage.setItem(PREFS_KEY, JSON.stringify({
			showHidden: !!state.prefs.showHidden,
			dim: state.prefs.dim | 0,
			planPrefs: state.plan.prefs,
			theme: state.prefs.theme === "dark" ? "dark" : "light",
			lineScale: clamp(state.prefs.lineScale || 1, LINE_SCALE_MIN, LINE_SCALE_MAX),
			hiddenModes: (state.prefs.hiddenModes || []).slice(),
		}));
	} catch (e) { /* storage unavailable — prefs just don't persist */ }
}
function loadPrefs() {
	let p = null;
	try { p = JSON.parse(localStorage.getItem(PREFS_KEY)); } catch (e) { /* ignore */ }
	if (!p) return;
	state.prefs.showHidden = !!p.showHidden;
	state.prefs.dim = p.dim | 0;
	if (p.planPrefs) Object.assign(state.plan.prefs, p.planPrefs);
	state.prefs.theme = p.theme === "dark" ? "dark" : "light";
	state.prefs.lineScale = Number.isFinite(p.lineScale)
		? clamp(p.lineScale, LINE_SCALE_MIN, LINE_SCALE_MAX) : 1;
	state.prefs.hiddenModes = Array.isArray(p.hiddenModes) ? p.hiddenModes.filter((m) => typeof m === "string") : [];
}

/* ---------------------------------------------------------------------------- 
 * 11a. settings menu (theme / line thickness / layers)
 * -------------------------------------------------------------------------- */

/** Human name for a transport mode, for the Layers list. */
function modeLabel(mode) {
	const known = { train: "Trains", boat: "Boats", airplane: "Planes", plane: "Planes", cable_car: "Cable cars" };
	if (known[mode]) return known[mode];
	const words = String(mode || "train").split(/[_\s-]+/).filter(Boolean)
		.map((w) => w.charAt(0).toUpperCase() + w.slice(1));
	const out = words.join(" ");
	return out ? (out.endsWith("s") ? out : out + "s") : "Other";
}

/**
 * Swap the canvas palette AND the DOM theme. The canvas cannot read CSS custom
 * properties, so the two live side by side: PALETTE here, :root[data-theme] in
 * map.css. The static layer is fully repainted, since every cached pixel is stale.
 */
function applyTheme(name) {
	const theme = name === "dark" ? "dark" : "light";
	state.prefs.theme = theme;
	PALETTE = THEMES[theme];
	const root = (typeof document !== "undefined" && (document.documentElement || document.body)) || null;
	if (root) {
		if (typeof root.setAttribute === "function") root.setAttribute("data-theme", theme);
		else if (root.dataset) root.dataset.theme = theme;
	}
	invalidateStatic();
	// the itinerary bakes two colours into its markup at render time
	if (state.plan.journeys.length) renderOptions();
	return PALETTE;
}

/** Layers list: switch one transport mode on or off and rebuild the geometry. */
function setModeVisible(mode, on) {
	const list = state.prefs.hiddenModes || (state.prefs.hiddenModes = []);
	const i = list.indexOf(mode);
	if (on && i >= 0) list.splice(i, 1);
	else if (!on && i < 0) list.push(mode);
	savePrefs();
	prepareGeometry();
}

function setLineScale(v) {
	state.prefs.lineScale = clamp(Number(v) || 1, LINE_SCALE_MIN, LINE_SCALE_MAX);
	savePrefs();
	invalidateStatic();
}

/** One checkbox per mode the network actually contains. Rebuilt with the geometry. */
function renderLayerList() {
	const box = $("layerList");
	if (!box) return;
	const modes = modesPresent();
	box.innerHTML = modes.map((m) => `
		<label class="layer"><input type="checkbox" data-mode="${esc(m)}"${modeVisible(m) ? " checked" : ""}>
		<span>${esc(modeLabel(m))}</span></label>`).join("")
		|| '<div class="set-hint">No routes loaded yet.</div>';
	for (const el of box.querySelectorAll("input[type=checkbox]")) {
		el.onchange = () => setModeVisible(el.dataset.mode, !!el.checked);
	}
}

function syncSettingsUi() {
	for (const b of document.querySelectorAll("#themeSeg button")) {
		b.classList.toggle("on", b.dataset.theme === state.prefs.theme);
	}
	const sl = $("lineScale");
	if (sl) sl.value = String(state.prefs.lineScale || 1);
	const out = $("lineScaleOut");
	if (out) out.textContent = (state.prefs.lineScale || 1).toFixed(2).replace(/0$/, "") + "x";
}

function initSettings() {
	applyTheme(state.prefs.theme);
	const panel = $("settings"), btn = $("gearBtn");
	const open = (on) => { if (panel && panel.classList) panel.classList.toggle("hidden", !on); };
	if (btn) btn.onclick = (e) => {
		if (e && e.stopPropagation) e.stopPropagation();
		open(panel && panel.classList ? panel.classList.contains("hidden") : true);
	};
	document.addEventListener("click", (e) => {
		if (!panel || !panel.contains || panel.contains(e.target) || e.target === btn) return;
		if (btn && btn.contains && btn.contains(e.target)) return;
		open(false);
	});
	for (const b of document.querySelectorAll("#themeSeg button")) {
		b.onclick = () => { applyTheme(b.dataset.theme); savePrefs(); syncSettingsUi(); };
	}
	const sl = $("lineScale");
	if (sl) {
		sl.min = String(LINE_SCALE_MIN);
		sl.max = String(LINE_SCALE_MAX);
		sl.step = "0.05";
		sl.oninput = () => { setLineScale(sl.value); syncSettingsUi(); };
	}
	syncSettingsUi();
	renderLayerList();
}

/* ============================================================================
 * 12. PLANNER — routing graph (12), departure model (12a), journey search (12b)
 * ========================================================================== */

/**
 * Build the routing graph from the loaded network + mapdata.
 *
 * Returns:
 *   {
 *     nodes:        Map platformId -> {
 *                       id, stationId, partId, name, stationName,
 *                       xz:[x,z], accessible:boolean, routeIds:[routeId]
 *                   },
 *     rideEdges:    [{ from, to, routeId, routeName, routeNumber, color, mode,
 *                      legKey, seconds, headwaySeconds, meters, accessible }],
 *     transferEdges:[{ from, to, meters, seconds, walk:true, accessible,
 *                      sameStation:boolean, samePart:boolean }],
 *     byStation:    Map stationId -> [platformId],
 *     adjacency:    Map platformId -> { rides:[rideEdge], transfers:[transferEdge] },
 *     stations:     Map stationId -> station record (display, parts, accessible…)
 *   }
 *
 * Ride edges come straight from mapdata legs (one edge per consecutive platform pair
 * per route), with `seconds` resolved by legSeconds() and `headwaySeconds` from the
 * route's headwayMs. Transfer edges come from each station's platformDistances at
 * WALK_SPEED (1.4 m/s) plus a WALK_BUFFER_S (30 s) buffer; when platformDistances is
 * missing or truncated, part centroids supply the distance instead. `accessible` on a
 * transfer means BOTH endpoints are step-free.
 */
function buildGraph() {
	const nodes = new Map();
	const byStation = new Map();
	for (const pl of state.platforms.values()) {
		const st = state.stations.get(pl.stationId);
		nodes.set(pl.id, {
			id: pl.id, stationId: pl.stationId, partId: pl.partId,
			name: pl.name, stationName: st ? st.display : "",
			xz: pl.xz, accessible: !!pl.accessible, routeIds: pl.routeIds.slice(),
		});
		if (!byStation.has(pl.stationId)) byStation.set(pl.stationId, []);
		byStation.get(pl.stationId).push(pl.id);
	}

	const rideEdges = [];
	for (const rt of state.routes.values()) {
		if (rt.hidden && !state.prefs.showHidden) continue;
		const plats = rt.platforms || [];
		for (let i = 0; i < plats.length - 1; i++) {
			const leg = state.legs.get(rt.id + "|" + i);
			if (!leg) continue;
			const a = nodes.get(leg.from), b = nodes.get(leg.to);
			if (!a || !b) continue;
			rideEdges.push({
				from: leg.from, to: leg.to, routeId: rt.id, routeName: rt.display,
				routeNumber: rt.number, color: rt.hex, mode: rt.mode || "train",
				legKey: rt.id + "|" + i, seconds: leg.seconds,
				headwaySeconds: (rt.headwayMs || 0) / 1000,
				meters: leg.meters, accessible: a.accessible && b.accessible,
			});
		}
	}

	const transferEdges = [];
	const pushTransfer = (aId, bId, meters) => {
		const a = nodes.get(aId), b = nodes.get(bId);
		if (!a || !b || aId === bId) return;
		transferEdges.push({
			from: aId, to: bId, meters,
			seconds: meters / WALK_SPEED + WALK_BUFFER_S,
			walk: true, accessible: a.accessible && b.accessible,
			sameStation: a.stationId === b.stationId, samePart: a.partId === b.partId,
		});
	};
	for (const st of state.stations.values()) {
		const seen = new Set();
		for (const d of st.platformDistances || []) {
			pushTransfer(d.a, d.b, d.dist);
			pushTransfer(d.b, d.a, d.dist);
			seen.add(d.a + ">" + d.b);
			seen.add(d.b + ">" + d.a);
		}
		// missing or truncated table: fall back to straight-line platform distances
		if (!(st.platformDistances || []).length || st.platformDistancesTruncated) {
			const ids = byStation.get(st.id) || [];
			for (let i = 0; i < ids.length; i++) for (let j = i + 1; j < ids.length; j++) {
				if (seen.has(ids[i] + ">" + ids[j])) continue;
				const a = nodes.get(ids[i]), b = nodes.get(ids[j]);
				if (!a || !b) continue;
				const m = dist(a.xz, b.xz);
				pushTransfer(ids[i], ids[j], m);
				pushTransfer(ids[j], ids[i], m);
			}
		}
	}

	const adjacency = new Map();
	const adj = (id) => {
		let a = adjacency.get(id);
		if (!a) { a = { rides: [], transfers: [] }; adjacency.set(id, a); }
		return a;
	};
	for (const e of rideEdges) adj(e.from).rides.push(e);
	for (const e of transferEdges) adj(e.from).transfers.push(e);

	return { nodes, rideEdges, transferEdges, byStation, adjacency, stations: state.stations };
}

/* ----------------------------------------------------------------------------
 * 12a. departure model — when does the next service leave this platform?
 * -------------------------------------------------------------------------- */

/** Deterministic 32-bit FNV-1a. Gives every (route, platform) a stable phase. */
function hash32(s) {
	let h = 0x811c9dc5;
	for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 0x01000193); }
	return h >>> 0;
}

/**
 * SCHEDULE APPROXIMATION. MTR gives us a headway but no timetable, so a route with
 * headway H is modelled as "the next train leaves `H x phase` from now", where phase
 * is a stable pseudo-random number derived from hash(routeId|platformId). Two
 * consequences we want: options never all depart at t+0 (which would make every
 * transfer look free), and the same query always gets the same answer. The band keeps
 * the wait off both extremes — a phase of 0 would be a train permanently waiting at
 * the platform, a phase of 1 the worst case on every single leg.
 * Because the wait is a constant offset it is also FIFO (a later arrival can never
 * catch an earlier train), which is what makes the label-correcting search correct.
 */
const SCHEDULE_PHASE_MIN = 0.15;
const SCHEDULE_PHASE_MAX = 0.85;
/** No headway at all (one-off / depot moves): a flat expected wait, flagged estimated. */
const UNKNOWN_HEADWAY_WAIT_S = 300;

/**
 * Per-route platform sequence + leg times, cached on the graph object. `legs[i]` runs
 * from `platforms[i]` to `platforms[i + 1]`; a route whose mapdata dropped a leg leaves
 * a hole, which the live walk-forward below treats as "cannot estimate".
 */
function routeSequence(graph, routeId) {
	let idx = graph._routeSeq;
	if (!idx) {
		idx = graph._routeSeq = new Map();
		for (const e of graph.rideEdges) {
			let s = idx.get(e.routeId);
			if (!s) {
				s = { platforms: [], platformIndex: new Map(), legs: [], headwaySeconds: e.headwaySeconds || 0 };
				idx.set(e.routeId, s);
			}
			const i = parseInt(String(e.legKey).split("|").pop(), 10);
			s.legs[Number.isFinite(i) ? i : s.legs.length] = e;
		}
		for (const s of idx.values()) {
			for (let i = 0; i < s.legs.length; i++) {
				if (!s.legs[i]) continue;
				s.platforms[i] = s.legs[i].from;
				s.platforms[i + 1] = s.legs[i].to;
			}
			for (let i = 0; i < s.platforms.length; i++) {
				if (s.platforms[i] && !s.platformIndex.has(s.platforms[i])) s.platformIndex.set(s.platforms[i], i);
			}
		}
	}
	return idx.get(routeId) || null;
}

function platformDwellSeconds(platformId) {
	const pl = state.platforms.get(platformId);
	return pl && pl.dwellMs > 0 ? pl.dwellMs / 1000 : 0;
}

/**
 * Next departure of `routeId` from `platformId` at or after `afterMs`, in three tiers:
 *
 *   LIVE      a streamed vehicle on that route whose next stop (`nPlat`) is at or
 *             before our platform in the route's stop list. Its arrival is the tail of
 *             the leg it is currently on — `(1 - pFrac)` of that leg's ride time — plus
 *             every whole leg and every dwell between there and us; its departure is
 *             that arrival plus our own platform's dwell.
 *   SCHEDULE  no live train: headway x a stable phase (see above).
 *   ESTIMATED no headway either: a flat 5-minute wait.
 *
 * @returns {{departMs:number, live:boolean, vehicleId:(string|null), estimated:boolean}}
 */
function nextDeparture(graph, platformId, routeId, afterMs) {
	const seq = routeSequence(graph, routeId);
	const target = seq ? seq.platformIndex.get(platformId) : undefined;

	if (seq && target !== undefined) {
		let best = null;
		const base = now();
		for (const [vid, rec] of state.vehicles) {
			if (!rec.route || rec.route.id !== routeId) continue;
			const d = rec.data || {};
			// where the vehicle is heading: nPlat, or the stop after pPlat
			let ni = d.nPlat ? seq.platformIndex.get(d.nPlat) : undefined;
			if (ni === undefined && d.pPlat && seq.platformIndex.has(d.pPlat)) ni = seq.platformIndex.get(d.pPlat) + 1;
			if (ni === undefined || ni > target) continue;      // unknown, or already past us
			const frac = clamp(typeof d.pFrac === "number" ? d.pFrac : 0, 0, 1);
			let secs = ni >= 1 && seq.legs[ni - 1] ? seq.legs[ni - 1].seconds * (1 - frac) : 0;
			let broken = false;
			for (let i = ni; i < target; i++) {
				if (!seq.legs[i]) { broken = true; break; }
				secs += seq.legs[i].seconds + platformDwellSeconds(seq.platforms[i]);
			}
			if (broken) continue;
			const departMs = base + (secs + platformDwellSeconds(platformId)) * 1000;
			if (departMs >= afterMs && (!best || departMs < best.departMs)) {
				best = { departMs, live: true, vehicleId: vid, estimated: false };
			}
		}
		if (best) return best;
	}

	const headway = seq ? seq.headwaySeconds : 0;
	if (headway > 0) {
		const phase = SCHEDULE_PHASE_MIN + (SCHEDULE_PHASE_MAX - SCHEDULE_PHASE_MIN)
			* (hash32(routeId + "|" + platformId) / 4294967296);
		return { departMs: afterMs + headway * phase * 1000, live: false, vehicleId: null, estimated: false };
	}
	return { departMs: afterMs + UNKNOWN_HEADWAY_WAIT_S * 1000, live: false, vehicleId: null, estimated: true };
}

/* ----------------------------------------------------------------------------
 * 12b. journey planner — time-dependent multi-criteria label-correcting search
 * -------------------------------------------------------------------------- */

/** Pareto labels kept per (platform, route-aboard) state. */
const PARETO_CAP = 8;
/** Stop expanding anything that already arrives this long after the best known arrival. */
const PLAN_SLACK_MS = 45 * 60000;
/**
 * Hard safety valve. The search is O(labels x edges) and the pareto cap already bounds
 * labels at roughly PARETO_CAP x (platforms + route-stops) — a few hundred platforms
 * comes to ~16 k — so this only ever fires on a pathological network.
 */
const PLAN_LABEL_CAP = 60000;
const PLAN_MAX_OPTIONS = 4;
/** Query cache, keyed by from/to/parts/prefs/minute — see planJourneys(). */
const planMemo = { graph: null, entries: new Map() };

const heapPush = (h, item) => {
	h.push(item);
	let i = h.length - 1;
	while (i > 0) {
		const p = (i - 1) >> 1;
		if (h[p].arrMs <= h[i].arrMs) break;
		const t = h[p]; h[p] = h[i]; h[i] = t;
		i = p;
	}
};
const heapPop = (h) => {
	const top = h[0], last = h.pop();
	if (!h.length) return top;
	h[0] = last;
	let i = 0;
	for (;;) {
		const l = i * 2 + 1, r = l + 1;
		let m = i;
		if (l < h.length && h[l].arrMs < h[m].arrMs) m = l;
		if (r < h.length && h[r].arrMs < h[m].arrMs) m = r;
		if (m === i) break;
		const t = h[m]; h[m] = h[i]; h[i] = t;
		i = m;
	}
	return top;
};

/**
 * Keep the pareto set inside PARETO_CAP. The label dropped is the latest-arriving one
 * that is not the sole holder of the best transfer count or the best walking distance —
 * dropping those would quietly delete the very alternatives the "fewest transfers" and
 * "least walking" profiles exist to find.
 */
function trimParetoSet(set) {
	while (set.length > PARETO_CAP) {
		let minB = Infinity, minW = Infinity, nB = 0, nW = 0;
		for (const l of set) {
			if (l.boardings < minB) { minB = l.boardings; nB = 1; } else if (l.boardings === minB) nB++;
			if (l.walk < minW) { minW = l.walk; nW = 1; } else if (l.walk === minW) nW++;
		}
		let victim = -1, worst = -Infinity;
		for (let i = 0; i < set.length; i++) {
			const l = set[i];
			if ((l.boardings === minB && nB === 1) || (l.walk === minW && nW === 1)) continue;
			if (l.arrMs > worst) { worst = l.arrMs; victim = i; }
		}
		if (victim < 0) {
			victim = 0;
			for (let i = 1; i < set.length; i++) if (set[i].arrMs > set[victim].arrMs) victim = i;
		}
		set.splice(victim, 1);
	}
}

/**
 * The search.
 *
 * A state is (platform, route currently aboard | null) — that pairing is what makes
 * staying on a train free: from an aboard state the only zero-cost move is the next
 * ride edge of the SAME route, which pays the intermediate platform's dwell and the
 * leg's ride time and nothing else. Alighting is implicit and free (every label may
 * also take the on-foot moves), and boarding a different route pays a real wait from
 * nextDeparture() plus one transfer.
 *
 * Labels carry (arrMs, boardings, walkMeters) and are pruned by pareto dominance per
 * state, capped at PARETO_CAP. `stepFree` is a hard constraint: boarding, alighting
 * and transferring all require an accessible platform, and transfer edges must be
 * accessible themselves — but simply riding THROUGH an inaccessible platform is fine,
 * because the rider never touches it.
 */
function planSearch(graph, originIds, targetIds, prefs, departAtMs) {
	const stepFree = !!prefs.stepFree;
	const usable = (id) => {
		const n = graph.nodes.get(id);
		return !!n && (!stepFree || n.accessible);
	};
	const best = new Map();
	const heap = [];
	const results = [];
	let labelCount = 0, bestArrival = Infinity;

	const push = (label) => {
		if (label.arrMs > bestArrival + PLAN_SLACK_MS) return;
		const key = label.node + "|" + (label.aboard || "");
		let set = best.get(key);
		if (!set) { set = []; best.set(key, set); }
		for (const o of set) {
			if (o.arrMs <= label.arrMs && o.boardings <= label.boardings && o.walk <= label.walk) return;
		}
		for (let i = set.length - 1; i >= 0; i--) {
			const o = set[i];
			if (label.arrMs <= o.arrMs && label.boardings <= o.boardings && label.walk <= o.walk) set.splice(i, 1);
		}
		set.push(label);
		if (set.length > PARETO_CAP) trimParetoSet(set);
		labelCount++;
		heapPush(heap, label);
	};

	for (const id of originIds) {
		if (!usable(id)) continue;
		push({ node: id, aboard: null, arrMs: departAtMs, boardings: 0, walk: 0, prev: null, via: null });
	}

	while (heap.length) {
		if (labelCount > PLAN_LABEL_CAP) break;
		const L = heapPop(heap);
		if (L.arrMs > bestArrival + PLAN_SLACK_MS) continue;
		const onFoot = usable(L.node);
		if (onFoot && targetIds.has(L.node) && L.via) {
			results.push(L);
			if (L.arrMs < bestArrival) bestArrival = L.arrMs;
		}
		const adj = graph.adjacency.get(L.node);
		if (!adj) continue;

		// 1. stay aboard — no wait, no transfer, just this platform's dwell
		if (L.aboard) {
			const dwellMs = platformDwellSeconds(L.node) * 1000;
			for (const e of adj.rides) {
				if (e.routeId !== L.aboard) continue;
				push({ node: e.to, aboard: e.routeId, arrMs: L.arrMs + dwellMs + e.seconds * 1000,
					boardings: L.boardings, walk: L.walk, prev: L, via: { kind: "ride", edge: e } });
			}
		}
		if (!onFoot) continue;      // step-free: the rider cannot get off here

		// 2. board a service
		for (const e of adj.rides) {
			if (e.routeId === L.aboard) continue;               // covered by "stay aboard"
			const nd = nextDeparture(graph, L.node, e.routeId, L.arrMs);
			push({ node: e.to, aboard: e.routeId, arrMs: nd.departMs + e.seconds * 1000,
				boardings: L.boardings + 1, walk: L.walk, prev: L,
				via: { kind: "ride", edge: e, depMs: nd.departMs, live: nd.live, vehicleId: nd.vehicleId, estimated: nd.estimated } });
		}

		// 3. walk
		for (const e of adj.transfers) {
			if (stepFree && !e.accessible) continue;
			if (!usable(e.to)) continue;
			push({ node: e.to, aboard: null, arrMs: L.arrMs + e.seconds * 1000,
				boardings: L.boardings, walk: L.walk + e.meters, prev: L, via: { kind: "walk", edge: e } });
		}
	}
	return results;
}

/** Turn one label chain into the Journey shape the option cards + itinerary consume. */
function assembleJourney(label) {
	const steps = [];
	for (let cur = label; cur && cur.via; cur = cur.prev) {
		steps.unshift({ via: cur.via, from: cur.prev.node, to: cur.node, startMs: cur.prev.arrMs, endMs: cur.arrMs });
	}
	if (!steps.length) return null;

	const legs = [];
	const anchor = (platformId) => {
		const pl = state.platforms.get(platformId);
		return { station: pl ? pl.stationId : null, part: pl ? pl.partId : null, accessible: !!(pl && pl.accessible) };
	};
	let accessible = true;
	let i = 0;
	while (i < steps.length) {
		if (steps[i].via.kind === "ride") {
			const routeId = steps[i].via.edge.routeId;
			const stops = [steps[i].from];
			let j = i;
			// consecutive same-route rides = one boarding; a re-boarding carries a depMs
			while (j < steps.length && steps[j].via.kind === "ride" && steps[j].via.edge.routeId === routeId
				&& (j === i || steps[j].via.depMs === undefined)) {
				stops.push(steps[j].to);
				j++;
			}
			const first = steps[i], last = steps[j - 1], e = first.via.edge;
			const rt = state.routes.get(routeId);
			const a = anchor(first.from), b = anchor(last.to);
			accessible = accessible && a.accessible && b.accessible;
			legs.push({
				kind: "ride", routeId, routeName: e.routeName, number: e.routeNumber, color: e.color, mode: e.mode,
				fromStation: a.station, fromPart: a.part, fromPlatform: first.from,
				toStation: b.station, toPart: b.part, toPlatform: last.to,
				stops, stopCount: Math.max(1, stops.length - 1), headsign: rt ? rt.dest : "",
				depMs: Math.round(first.via.depMs), arrMs: Math.round(last.endMs),
				live: !!first.via.live, vehicleId: first.via.vehicleId || null, estimated: !!first.via.estimated,
				departsInMin: Math.max(0, Math.round((first.via.depMs - now()) / 60000)),
			});
			i = j;
		} else {
			let j = i, meters = 0, seconds = 0, walkAccessible = true;
			while (j < steps.length && steps[j].via.kind === "walk") {
				meters += steps[j].via.edge.meters;
				seconds += steps[j].via.edge.seconds;
				walkAccessible = walkAccessible && !!steps[j].via.edge.accessible;
				j++;
			}
			const first = steps[i], last = steps[j - 1];
			const a = anchor(first.from), b = anchor(last.to);
			accessible = accessible && walkAccessible;
			// meters/seconds stay exact (fmtMeters/fmtMin round for display) so that
			// arrMs - depMs is always this leg's own walking time to the millisecond
			legs.push({
				kind: "walk", mode: "walk", meters, seconds, accessible: walkAccessible,
				note: a.part && a.part === b.part ? "cross-platform"
					: a.station && a.station === b.station ? "concourse link" : "street walk",
				fromStation: a.station, fromPart: a.part, fromPlatform: first.from,
				toStation: b.station, toPart: b.part, toPlatform: last.to,
				depMs: Math.round(first.startMs), arrMs: Math.round(first.startMs + seconds * 1000),
			});
			i = j;
		}
	}

	const lastLeg = legs[legs.length - 1];
	const departMs = legs[0].depMs, arriveMs = lastLeg.arrMs;
	return {
		index: 0, legs, departMs, arriveMs, durationMs: arriveMs - departMs,
		toStation: lastLeg.toStation, toPart: lastLeg.toPart,
		accessible, tags: [],
		transfers: Math.max(0, legs.filter((l) => l.kind === "ride").length - 1),
		walkMeters: legs.reduce((a, l) => a + (l.kind === "walk" ? l.meters : 0), 0),
		signature: legs.map((l) => l.kind === "ride"
			? "r:" + l.routeId + ":" + l.fromPlatform + ">" + l.toPlatform
			: "w:" + l.fromPlatform + ">" + l.toPlatform).join("|"),
		// the leg signature options are deduped on: two journeys that ride the same
		// services between the same platforms are ONE option to a rider, even when they
		// finish at different platforms of the destination station.
		rideSignature: legs.filter((l) => l.kind === "ride")
			.map((l) => l.routeId + ":" + l.fromPlatform + ">" + l.toPlatform).join("|"),
	};
}

/** Superlatives, set only when they actually distinguish this option from the others. */
function tagJourneys(list) {
	const min = (f) => list.reduce((a, j) => Math.min(a, f(j)), Infinity);
	const minArr = min((j) => j.arriveMs), minT = min((j) => j.transfers), minW = min((j) => j.walkMeters);
	const informative = (f, v) => list.length === 1 || list.some((j) => f(j) > v);
	for (const j of list) {
		const tags = [];
		if (j.arriveMs === minArr && informative((x) => x.arriveMs, minArr)) tags.push("fastest");
		if (j.transfers === minT && informative((x) => x.transfers, minT)) tags.push("fewest transfers");
		if (j.walkMeters === minW && informative((x) => x.walkMeters, minW)) tags.push("least walking");
		if (j.accessible) tags.push("Step-free");
		j.tags = tags;
	}
	return list;
}

/**
 * Journey planner.
 *
 * @param {object} graph        result of buildGraph()
 * @param {string} fromStationId
 * @param {string} toStationId
 * @param {{mode:"fastest"|"transfers"|"walking", stepFree:boolean}} prefs
 * @param {number} departAtMs   epoch ms the rider wants to leave
 * @param {{fromPartId?:string, toPartId?:string}} [opts]  narrow a split station to one part
 * @returns {Journey[]} ordered best-first; the UI renders whatever it gets.
 *
 * Journey shape the UI expects (assembleJourney() builds it):
 *   {
 *     index:      number,               // position in the returned array
 *     departMs, arriveMs, durationMs,
 *     toStation, toPart,                // destination labels
 *     accessible: boolean,
 *     transfers, walkMeters, signature, // planner metrics (not used by the DOM)
 *     tags:       string[],             // e.g. ["Step-free", "least walking"]
 *     legs: [
 *       { kind:"ride", routeId, routeName, number, color, mode,
 *         fromStation, fromPart, fromPlatform, toStation, toPart, toPlatform,
 *         stops:[platformId], stopCount, headsign, depMs, arrMs,
 *         live, vehicleId, estimated, departsInMin },
 *       { kind:"walk", mode:"walk"|"boat", meters, seconds, accessible, note,
 *         fromStation, fromPart, fromPlatform, toStation, toPart, toPlatform,
 *         depMs, arrMs },
 *     ],
 *   }
 *
 * One multi-criteria search produces the whole pareto frontier at the destination; the
 * three cost profiles are then just three different ways to read that frontier, which
 * is both cheaper and more consistent than running the search three times.
 */
function planJourneys(graph, fromStationId, toStationId, prefs, departAtMs, opts) {
	try {
		if (!graph || !graph.nodes || !graph.nodes.size || !graph.byStation) return [];
		if (!fromStationId || !toStationId || fromStationId === toStationId) return [];
		const o = opts || {};
		const p = prefs || {};
		const mode = p.mode === "transfers" || p.mode === "walking" ? p.mode : "fastest";
		const stepFree = !!p.stepFree;
		const when = Number.isFinite(departAtMs) ? departAtMs : now();

		if (planMemo.graph !== graph) { planMemo.graph = graph; planMemo.entries.clear(); }
		const key = [fromStationId, toStationId, o.fromPartId || "", o.toPartId || "",
			mode, stepFree ? 1 : 0, Math.floor(when / 60000)].join("~");
		const hit = planMemo.entries.get(key);
		if (hit) return hit.slice();

		const platformsOf = (stationId, partId) => {
			const ids = graph.byStation.get(stationId) || [];
			if (!partId) return ids;
			const narrowed = ids.filter((id) => {
				const n = graph.nodes.get(id);
				return n && n.partId === partId;
			});
			return narrowed.length ? narrowed : ids;
		};
		const originIds = platformsOf(fromStationId, o.fromPartId);
		const targetIds = new Set(platformsOf(toStationId, o.toPartId));
		let out = [];
		if (originIds.length && targetIds.size) {
			out = choosePlanOptions(planSearch(graph, originIds, targetIds, { stepFree }, when), mode);
		}
		if (planMemo.entries.size > 24) planMemo.entries.clear();
		planMemo.entries.set(key, out);
		return out.slice();
	} catch (e) {
		console.warn("planJourneys failed", e);
		return [];
	}
}

/**
 * Turn destination labels into 2–4 distinct options: the winner under each of the three
 * profiles (deduped — they are often the same journey), the requested profile's winner
 * first, everything else ordered by arrival time, then filled out with the next-best
 * distinct alternatives so the panel has something to compare against.
 */
function choosePlanOptions(labels, mode) {
	const bySig = new Map();
	for (const l of labels) {
		const j = assembleJourney(l);
		if (!j) continue;
		const prev = bySig.get(j.rideSignature);
		if (!prev || j.arriveMs < prev.arriveMs
			|| (j.arriveMs === prev.arriveMs && j.walkMeters < prev.walkMeters)) bySig.set(j.rideSignature, j);
	}
	const cands = [...bySig.values()];
	if (!cands.length) return [];

	const winner = (cmp) => cands.slice().sort(cmp)[0];
	const byFastest = winner((a, b) => a.arriveMs - b.arriveMs || a.transfers - b.transfers || a.walkMeters - b.walkMeters);
	const byTransfers = winner((a, b) => a.transfers - b.transfers || a.arriveMs - b.arriveMs || a.walkMeters - b.walkMeters);
	const byWalking = winner((a, b) => a.walkMeters - b.walkMeters || a.arriveMs - b.arriveMs || a.transfers - b.transfers);
	const preferred = mode === "transfers" ? byTransfers : mode === "walking" ? byWalking : byFastest;

	const picked = [];
	const take = (j) => {
		if (j && !picked.includes(j) && picked.length < PLAN_MAX_OPTIONS) picked.push(j);
	};
	take(preferred);
	take(byFastest); take(byTransfers); take(byWalking);
	for (const j of cands.slice().sort((a, b) => a.arriveMs - b.arriveMs)) take(j);

	const rest = picked.slice(1).sort((a, b) => a.arriveMs - b.arriveMs);
	const out = [picked[0]].concat(rest);
	out.forEach((j, i) => { j.index = i; });
	return tagJourneys(out);
}

/* ============================================================================
 * 13. demo mode — the acceptance harness
 * ========================================================================== */

function bootDemo() {
	const demo = buildDemoCity();
	state.dims = ["demo:overworld"];
	state.updateMillis = 333;
	applyNetwork(demo.network);
	applyMapdata(demo.mapdata);
	applyTerrain(demo.terrain);
	prepareGeometry();
	fitView();
	setStatus("live");

	// pre-fill the mock's query — the real planner answers it, so every card /
	// itinerary / selection feature shows without the rider typing anything
	state.plan.from = { stationId: "mh", partId: null };
	state.plan.to = { stationId: "ap", partId: "ap:1" };
	syncFields();
	replan();

	// three synthetic trains, no SSE — the same handleFrame() the real stream feeds
	const runs = [
		{ id: "v1", rails: ["cor_1", "cor_2", "cor_3"], t: 0.25, step: 0.010, kmh: 52, nPlat: "mh_g",
			route: { id: "r4", name: "Baker Line||Bayfront", number: "4 IN", color: 0x00933C, dest: "Bayfront" } },
		// the outbound working: it runs on the OTHER green track, and the drawn puck is
		// snapped onto the one line both directions share
		{ id: "v4", rails: ["cor_2o", "cor_1o", "cor_0o"], t: 0.6, step: 0.009, kmh: 50, nPlat: "gf_g",
			route: { id: "r4o", name: "Baker Line||Northgate", number: "4 OU", color: 0x00933C, dest: "Northgate" } },
		{ id: "v2", rails: ["blu_1", "blu_2"], t: 0.4, step: 0.006, kmh: 78, nPlat: "hv_b",
			route: { id: "rA", name: "Airport Express||Airport", number: "A IN", color: 0x0039A6, dest: "Airport" } },
		{ id: "v3", rails: ["red_1", "red_2"], t: 0.1, step: 0.008, kmh: 46, nPlat: "fd_r",
			route: { id: "rR", name: "Ridge Line||South Yards", number: "1", color: 0xEE352E, dest: "South Yards" } },
	];
	for (const r of runs) r.i = 0;
	setInterval(() => {
		const t = now();
		const vehicles = runs.map((r) => {
			r.t += r.step;
			if (r.t >= 1) { r.t -= 1; r.i = (r.i + 1) % r.rails.length; }
			const rail = r.rails[r.i];
			const p = railPoint(rail, r.t) || [0, 0];
			return {
				id: r.id, x: p[0], y: 64, z: p[1], kmh: r.kmh, rev: false,
				rail, railT: r.t, doors: false, dwellMs: 0, devMs: 0, manual: false, stop: r.i,
				pPlat: "", nPlat: r.nPlat, pFrac: r.t,
				route: r.route,
				consist: { cars: ["m7_a", "m7_b", "m7_b", "m7_a"], carLengths: [19.2, 19.2, 19.2, 19.2] },
			};
		});
		handleFrame({ schemaVersion: 1, serverTime: t, dimension: 0, vehicles }, false);
	}, 333);

	requestAnimationFrame(frame);
	setInterval(tickLive, 1000);
}

/**
 * A small synthetic city, laid out in the proportions of the approved mock, and built
 * to exercise the SCHEMATIC line model the way a real MTR network does:
 *   - a river + harbor (organic water polygon) with an island (even-odd hole)
 *   - a green trunk that is FOUR routes on FOUR tracks — the local pair "4 IN"/"4 OU"
 *     and the express pair "5 IN"/"5 OU" — plus a BLUE trunk ("A IN"/"A OU", "C IN")
 *     on its own track in the same corridor. What must come out of that:
 *       * one green centreline and one blue centreline, no same-colour doubling
 *       * the express ng->bc suppressed, because the local chain covers it
 *       * bullets reading "4", "5", "A", "C" — never "IN" or "OU"
 *       * green and blue bundled side by side with one hairline gap
 *   - an orange crosstown, a red north-south line, a boat ferry (dashed)
 *   - a purple shuttle whose only leg has NO rails, exercising the straight-line
 *     fallback, and a hidden depot move (only drawn with the show-hidden pref)
 *   - a split station (Airport: Lower Concourse + Terminal, 90 m walk connector)
 *   - a terminus (Bayfront), accessibility flags on some platforms
 */
function buildDemoCity() {
	const rails = [];
	const R = (id, anchors, opts = {}) => {
		const pts = smooth(anchors, opts.steps || 8);
		rails.push({
			id, mode: opts.mode || "train", length: polylineLength(pts),
			speedA: opts.speed || 80, speedB: opts.speed || 80,
			platform: false, siding: false, canAccelerate: true, canTurnBack: false,
			signalColors: [], points: pts.map((p) => [Math.round(p[0] * 100) / 100, 64, Math.round(p[1] * 100) / 100]),
		});
	};

	/* THE CORRIDOR, one track pitch (6 blocks) per track.
	 *   -12/-6 : blue trunk            (btr_*)
	 *      0/+6 : green local pair      (cor_*, cor_*o)
	 *    +12/+18: green express pair    (exp_0, exp_0o)
	 * The `o` rails are authored in the OPPOSITE direction, exactly like a real MTR
	 * outbound route's rails, so chainRails has to flip them and pair-averaging has to
	 * re-orient them. */
	const shift = (a, dx, dz) => a.map((p) => [p[0] + dx, p[1] + dz]);
	const rev = (a) => a.slice().reverse();
	const cor = [
		[[640, 70], [638, 110], [636, 150]],
		[[636, 150], [634, 205], [633, 260]],
		[[633, 260], [633, 312], [634, 365]],
		[[634, 365], [637, 415], [640, 468]],
	];
	const grn0 = [[640, 468], [641, 530], [650, 601]];
	const grn1 = [[650, 601], [690, 690], [720, 780], [748, 880]];
	cor.forEach((a, i) => R("cor_" + i, a));                       // green local, inbound
	cor.forEach((a, i) => R("cor_" + i + "o", rev(shift(a, 6, 0))));  // green local, outbound
	R("grn_0", grn0); R("grn_1", grn1);
	R("grn_0o", rev(shift(grn0, 6, 0))); R("grn_1o", rev(shift(grn1, 6, 0)));
	// green EXPRESS pair: Northgate -> Baker City Central, skipping Maple Heights,
	// Garfield Av and Museum, on its own track pair further out
	const exp = [[652, 70], [650, 110], [648, 150], [646, 205], [645, 260],
		[645, 312], [646, 365], [649, 415], [652, 468]];
	R("exp_0", exp); R("exp_0o", rev(shift(exp, 6, 0)));
	// blue trunk: the same corridor, its own track, one pitch west
	cor.forEach((a, i) => R("btr_" + i, shift(a, -6, 0)));
	// blue branch east, over the river (the outbound short-turns at Riverside)
	R("blu_0", [[634, 468], [710, 520], [810, 555]]);
	R("blu_1", [[810, 555], [960, 578], [1080, 596], [1210, 626]]);
	R("blu_2", [[1210, 626], [1275, 648], [1332, 668]]);
	R("blu_1o", rev(shift([[810, 555], [960, 578], [1080, 596], [1210, 626]], 0, 6)));
	R("blu_2o", rev(shift([[1210, 626], [1275, 648], [1332, 668]], 0, 6)));
	// orange crosstown, terminating at the airport terminal
	R("org_0", [[470, 215], [610, 206], [760, 199]]);
	R("org_1", [[760, 199], [980, 190], [1180, 184]]);
	R("org_2", [[1180, 184], [1330, 250], [1395, 430], [1400, 616]]);
	// red north-south
	R("red_0", [[518, 60], [516, 190], [518, 320]]);
	R("red_1", [[518, 320], [524, 460], [522, 600]]);
	R("red_2", [[522, 600], [525, 720], [531, 830]]);
	// harbor ferry (boat mode -> dashed ribbon)
	R("fer_0", [[748, 880], [1000, 900], [1220, 866], [1392, 806]], { mode: "boat", speed: 30 });
	R("fer_1", [[1392, 806], [1408, 720], [1400, 616]], { mode: "boat", speed: 30 });

	const P = (id, name, stationId, x, z, dx, dz, accessible) => ({
		id, name, dwellMs: 20000, stationId,
		p1: [x - dx * 12, 64, z - dz * 12], p2: [x + dx * 12, 64, z + dz * 12],
		mid: [x, 64, z], routeIds: [], accessible: !!accessible,
	});
	const platforms = [
		P("ng_g", "1", "ng", 636, 70, 0, 1, true), P("ng_b", "2", "ng", 634, 70, 0, 1, true),
		P("mh_g", "1", "mh", 636, 150, 0, 1, true),
		P("gf_g", "1", "gf", 633, 260, 0, 1, false),
		P("mu_g", "1", "mu", 634, 365, 0, 1, false),
		P("bc_g", "1", "bc", 636, 468, 0, 1, true), P("bc_b", "3", "bc", 634, 468, 0, 1, true),
		P("sp_g", "1", "sp", 650, 601, 0.4, 1, false),
		P("bf_g", "1", "bf", 748, 880, 0.3, 1, true), P("bf_f", "F", "bf", 756, 884, 1, 0.2, true),
		P("rs_b", "2", "rs", 810, 555, 1, 0.2, false),
		P("hv_b", "1", "hv", 1210, 626, 1, 0.25, true),
		P("ap_b", "1", "ap", 1332, 668, 1, 0.35, true),
		P("ap_t", "3", "ap", 1400, 616, 0.1, 1, true), P("ap_tf", "F", "ap", 1408, 622, 1, 0.3, true),
		P("he_f", "F", "he", 1392, 806, 1, 0.3, false),
		P("hc_o", "1", "hc", 470, 215, 1, 0.1, false),
		P("fg_o", "1", "fg", 760, 199, 1, 0.1, true),
		P("em_o", "1", "em", 1180, 184, 1, 0.1, true),
		P("rv_r", "1", "rv", 518, 60, 0.1, 1, false),
		P("wg_r", "1", "wg", 518, 320, 0.1, 1, false),
		P("fd_r", "1", "fd", 522, 600, 0.1, 1, true),
		P("sy_r", "1", "sy", 531, 830, 0.1, 1, false),
	];

	const S = (id, name, color, plats, accessible, x, z) => ({
		id, name, color, bounds: [x - 22, 60, z - 22, x + 22, 70, z + 22],
		platformIds: plats, accessible: !!accessible,
	});
	const stations = [
		S("ng", "Northgate", 0x00933C, ["ng_g", "ng_b"], true, 640, 70),
		S("mh", "Maple Heights", 0x00933C, ["mh_g"], true, 636, 150),
		S("gf", "Garfield Av", 0x00933C, ["gf_g"], false, 633, 260),
		S("mu", "Museum", 0x00933C, ["mu_g"], false, 634, 365),
		S("bc", "Baker City Central|贝克城", 0x1a73e8, ["bc_g", "bc_b"], true, 640, 468),
		S("sp", "Southport", 0x00933C, ["sp_g"], false, 650, 601),
		S("bf", "Bayfront", 0x00933C, ["bf_g", "bf_f"], true, 750, 881),
		S("rs", "Riverside", 0x0039A6, ["rs_b"], false, 810, 555),
		S("hv", "Harborview", 0x0039A6, ["hv_b"], true, 1210, 626),
		S("ap", "Airport", 0x0039A6, ["ap_b", "ap_t", "ap_tf"], true, 1366, 642),
		S("he", "Harbor East", 0x3F7FA8, ["he_f"], false, 1392, 806),
		S("hc", "Hillcrest", 0xFF6319, ["hc_o"], false, 470, 215),
		S("fg", "Fairgrounds", 0xFF6319, ["fg_o"], true, 760, 199),
		S("em", "East Meadow", 0xFF6319, ["em_o"], true, 1180, 184),
		S("rv", "Ridgeview", 0xEE352E, ["rv_r"], false, 518, 60),
		S("wg", "Westgate", 0xEE352E, ["wg_r"], false, 518, 320),
		S("fd", "Foundry", 0xEE352E, ["fd_r"], true, 522, 600),
		S("sy", "South Yards", 0xEE352E, ["sy_r"], false, 531, 830),
	];

	const RT = (id, name, number, color, hidden) => ({ id, name, number, color, hidden: !!hidden });
	// Route NUMBERS carry MTR-style direction suffixes; the map must normalise them
	// away ("4 IN" and "4 OU" are both the "4"), and colour alone is the line.
	const routes = [
		RT("r4", "Baker Line||Bayfront", "4 IN", 0x00933C),
		RT("r4o", "Baker Line||Northgate", "4 OU", 0x00933C),
		RT("r5", "Baker Express||Baker City Central", "5 IN", 0x00933C),
		RT("r5o", "Baker Express||Northgate", "5 OU", 0x00933C),
		RT("rA", "Airport Express||Airport", "A IN", 0x0039A6),
		RT("rAo", "Airport Express||Riverside", "A OU", 0x0039A6),
		RT("rC", "City Connector||Airport", "C IN", 0x0039A6),
		RT("rO", "Crosstown||Airport Terminal", "7", 0xFF6319),
		RT("rR", "Ridge Line||South Yards", "1", 0xEE352E),
		RT("rF", "Harbor Ferry||Airport", "F", 0x3F7FA8),
		RT("rS", "Harbour Shuttle||Harbor East", "S", 0x6B3FA0),
		RT("rD", "Depot Move||Yard", "D", 0x8A929C, true),
	];

	const leg = (...rails) => ({ rails });
	const mdRoutes = [
		// GREEN LOCAL, both directions on their own tracks: pair-averaging must fold the
		// two into one drawn centreline instead of two braided ribbons.
		{ id: "r4", name: "Baker Line||Bayfront", number: "4 IN", color: 0x00933C, hidden: false, mode: "train",
			platforms: ["ng_g", "mh_g", "gf_g", "mu_g", "bc_g", "sp_g", "bf_g"],
			legs: [leg("cor_0"), leg("cor_1"), leg("cor_2"), leg("cor_3"), leg("grn_0"), leg("grn_1")],
			durations: [140000, 165000, 160000, 155000, 190000, 300000], durationsValid: true, headwayMs: 300000 },
		{ id: "r4o", name: "Baker Line||Northgate", number: "4 OU", color: 0x00933C, hidden: false, mode: "train",
			platforms: ["bf_g", "sp_g", "bc_g", "mu_g", "gf_g", "mh_g", "ng_g"],
			legs: [leg("grn_1o"), leg("grn_0o"), leg("cor_3o"), leg("cor_2o"), leg("cor_1o"), leg("cor_0o")],
			durations: [300000, 190000, 155000, 160000, 165000, 140000], durationsValid: true, headwayMs: 300000 },
		// GREEN EXPRESS, both directions: one long ng->bc segment the local chain covers,
		// so the drawn map suppresses it (and a journey on it lights the chain instead).
		{ id: "r5", name: "Baker Express||Baker City Central", number: "5 IN", color: 0x00933C, hidden: false, mode: "train",
			platforms: ["ng_g", "bc_g"], legs: [leg("exp_0")],
			durations: [420000], durationsValid: true, headwayMs: 900000 },
		{ id: "r5o", name: "Baker Express||Northgate", number: "5 OU", color: 0x00933C, hidden: false, mode: "train",
			platforms: ["bc_g", "ng_g"], legs: [leg("exp_0o")],
			durations: [420000], durationsValid: true, headwayMs: 900000 },
		{ id: "rA", name: "Airport Express||Airport", number: "A IN", color: 0x0039A6, hidden: false, mode: "train",
			platforms: ["ng_b", "bc_b", "rs_b", "hv_b", "ap_b"],
			legs: [leg("btr_0", "btr_1", "btr_2", "btr_3"), leg("blu_0"), leg("blu_1"), leg("blu_2")],
			durations: [560000, 210000, 380000, 150000], durationsValid: true, headwayMs: 360000 },
		// the blue outbound short-turns at Riverside — the demo deliberately keeps the
		// network directed so "travel backwards" still has no path (see planner.mjs)
		{ id: "rAo", name: "Airport Express||Riverside", number: "A OU", color: 0x0039A6, hidden: false, mode: "train",
			platforms: ["ap_b", "hv_b", "rs_b"],
			legs: [leg("blu_2o"), leg("blu_1o")],
			durations: [150000, 380000], durationsValid: true, headwayMs: 360000 },
		{ id: "rC", name: "City Connector||Airport", number: "C IN", color: 0x0039A6, hidden: false, mode: "train",
			platforms: ["ng_b", "bc_b", "rs_b", "ap_b"],
			legs: [leg("btr_0", "btr_1", "btr_2", "btr_3"), leg("blu_0"), leg("blu_1", "blu_2")],
			durations: [560000, 210000, 520000], durationsValid: true, headwayMs: 600000 },
		// durationsValid FALSE with durations.length === platforms.length: leg i's time
		// lives at i+1 (the depot-inbound-leg quirk documented in the brief)
		{ id: "rO", name: "Crosstown||Airport Terminal", number: "7", color: 0xFF6319, hidden: false, mode: "train",
			platforms: ["hc_o", "fg_o", "em_o", "ap_t"],
			legs: [leg("org_0"), leg("org_1"), leg("org_2")],
			durations: [90000, 240000, 320000, 430000], durationsValid: false, headwayMs: 480000 },
		{ id: "rR", name: "Ridge Line||South Yards", number: "1", color: 0xEE352E, hidden: false, mode: "train",
			platforms: ["rv_r", "wg_r", "fd_r", "sy_r"],
			legs: [leg("red_0"), leg("red_1"), leg("red_2")],
			durations: [230000, 260000, 220000], durationsValid: true, headwayMs: 540000 },
		{ id: "rF", name: "Harbor Ferry||Airport", number: "F", color: 0x3F7FA8, hidden: false, mode: "boat",
			platforms: ["bf_f", "he_f", "ap_tf"],
			legs: [leg("fer_0"), leg("fer_1")],
			durations: [900000, 480000], durationsValid: true, headwayMs: 1800000 },
		// empty rails on the only leg -> straight-line fallback between platform mids
		{ id: "rS", name: "Harbour Shuttle||Harbor East", number: "S", color: 0x6B3FA0, hidden: false, mode: "train",
			platforms: ["hv_b", "he_f"], legs: [{ rails: [] }],
			durations: [300000], durationsValid: true, headwayMs: 900000 },
		{ id: "rD", name: "Depot Move||Yard", number: "D", color: 0x8A929C, hidden: true, mode: "train",
			platforms: ["sp_g", "bf_g"], legs: [leg("grn_1")],
			durations: [300000], durationsValid: true, headwayMs: 0 },
	];

	// station parts: only the airport is split, with a 90 m concourse link
	const partsFor = (st) => {
		if (st.id === "ap") {
			return {
				parts: [
					{ id: "ap:0", name: "Lower Concourse", platforms: ["ap_b"], centroid: [1332, 50, 668], y: 50 },
					{ id: "ap:1", name: "Terminal", platforms: ["ap_t", "ap_tf"], centroid: [1400, 64, 616], y: 64 },
				],
				partWalks: [{ a: "ap:0", b: "ap:1", dist: 90 }],
				platformDistances: [
					{ a: "ap_b", b: "ap_t", dist: 90 }, { a: "ap_b", b: "ap_tf", dist: 96 },
					{ a: "ap_t", b: "ap_tf", dist: 12 },
				],
			};
		}
		const pls = st.platformIds.map((id) => platforms.find((p) => p.id === id)).filter(Boolean);
		const cx = pls.reduce((a, p) => a + p.mid[0], 0) / Math.max(1, pls.length);
		const cz = pls.reduce((a, p) => a + p.mid[2], 0) / Math.max(1, pls.length);
		const pd = [];
		for (let i = 0; i < pls.length; i++) for (let j = i + 1; j < pls.length; j++) {
			pd.push({ a: pls[i].id, b: pls[j].id, dist: Math.round(Math.hypot(pls[i].mid[0] - pls[j].mid[0], pls[i].mid[2] - pls[j].mid[2])) + 12 });
		}
		return {
			parts: [{ id: st.id + ":0", platforms: st.platformIds.slice(), centroid: [cx, 64, cz], y: 64 }],
			partWalks: [], platformDistances: pd,
		};
	};

	const mdStations = stations.map((st) => {
		const extra = partsFor(st);
		return {
			id: st.id, name: st.name, color: st.color, accessible: st.accessible,
			platforms: st.platformIds.map((id) => {
				const p = platforms.find((q) => q.id === id);
				return { id, mid: p.mid, accessible: p.accessible, dwellMs: p.dwellMs };
			}),
			...extra,
		};
	});

	return {
		network: {
			schemaVersion: 1, dimension: "demo:overworld", dimensionIndex: 0,
			dimensions: ["demo:overworld"], rails, stations, platforms, routes,
		},
		mapdata: { schemaVersion: 1, routes: mdRoutes, stations: mdStations },
		terrain: demoTerrain(),
	};
}

/** River + harbor as one ring, plus an island ring (even-odd punches it out). */
function demoTerrain() {
	const west = smooth([[880, -20], [905, 150], [912, 330], [918, 500], [975, 660],
		[1120, 780], [1290, 872], [1350, 960], [1362, 1020]], 8);
	const east = smooth([[1000, -20], [1012, 170], [1022, 330], [1030, 470], [1075, 610],
		[1180, 700], [1330, 772], [1470, 820], [1640, 848]], 8);
	const ring = west.concat([[1420, 1020], [1660, 1020], [1660, 860]], east.slice().reverse());
	const island = smooth([[1450, 912], [1500, 900], [1528, 924], [1505, 950], [1455, 944], [1450, 912]], 6);
	return { schemaVersion: 1, dimension: "demo:overworld", scannedAt: now(), grid: 4, polygons: [ring, island] };
}

/* ---------------------------------------------------------------------------- */

boot();
