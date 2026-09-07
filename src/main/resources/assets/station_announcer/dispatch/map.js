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
 *   api/satmeta?dimension=N      basemap tile index; sattile?tx&tz[&kind=mask] the tiles
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
/** ?player=<username> — the in-game button opens the map with this set (feature 3). */
const PLAYER_PARAM = new URLSearchParams(location.search).get("player") || "";
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
		paper: "#f6f3ec", water: "#bcd6ea", forest: "#d9e9cc", snow: "#eef1f4",
		ink: "#1c1f23", ink2: "#5b6470", ink3: "#8a929c",
		accent: "#1a73e8", pin: "#d93025", live: "#0f9d58",
		chip: "#ffffff", chipInk: "#3c434b", chipShadow: "rgba(20,28,40,0.22)",
		glyphFill: "#ffffff", glyphStroke: "#1c1f23",
		// the express/local ring (feature 1) has NO fill, so on paper it reads in ink;
		// on the near-black dark ground the same ink would disappear — see THEMES.dark
		openStroke: "#1c1f23",
		player: "#1a73e8", playerHalo: "rgba(26,115,232,0.16)", playerOther: "#7d8792",
		// satellite imagery is drawn over the paper fill at this alpha (feature 5)
		satAlpha: 0.85,
		leader: "#9aa2ab", rawRail: "#9aa2ab", dim: 0.15,
	},
	dark: {
		paper: "#101014", water: "#1b2a38", forest: "#161f17", snow: "#1b1e23",
		ink: "#f2f3f5", ink2: "#aab3bf", ink3: "#79838f",
		accent: "#6aa8ff", pin: "#ff5f52", live: "#3ddc84",
		chip: "#1a1b20", chipInk: "#dfe3e8", chipShadow: "rgba(0,0,0,0.55)",
		glyphFill: "#ffffff", glyphStroke: "#0b0b0f",
		openStroke: "#ffffff",
		player: "#4c8dff", playerHalo: "rgba(76,141,255,0.22)", playerOther: "#9aa4b0",
		// darker in dark mode: full-brightness aerial imagery under white type is unreadable
		satAlpha: 0.6,
		leader: "#5c6672", rawRail: "#5c6672", dim: 0.22,
	},
};
let PALETTE = THEMES.light;

/**
 * Walking speed used for EVERY walk timing (m/s) + a fixed platform-change buffer.
 *
 * The default is Minecraft's own walking speed (4.3 m/s), not a pedestrian's 1.4 — the
 * rider is a player, and a 90 m concourse link that reads as "2 min" to a real commuter
 * is 21 s to somebody sprinting down it. The settings slider covers sneak-ish to
 * sprint-ish; changing it rebuilds the graph, drops the plan memo and re-plans, because
 * every transfer edge's `seconds` is derived from it.
 */
const WALK_SPEED_DEFAULT = 4.3;
const WALK_SPEED_MIN = 2.0;
const WALK_SPEED_MAX = 5.6;
const WALK_BUFFER_S = 30;

/* ---- schematic drawing constants (section 5) ---- */
/** Points every drawn segment is resampled to (pair averaging is pointwise). */
const SEG_SAMPLES = 32;
/** Blocks. "These two polylines run in the same corridor." Used by both the express
 *  coverage test and cross-colour bundling. */
const CORRIDOR_TOL = 14;
/** Blocks, express-coverage only: wider than CORRIDOR_TOL because on curves the
 *  pair-averaged local centreline cuts the corner tighter than the express track —
 *  the strands are still the same corridor even where they briefly spread (curved
 *  sections were breaking the interlined express view at 14). */
const COVER_TOL = 24;
/** Fraction of an express segment that must be covered by the line's own shorter
 *  segments before it is suppressed. */
const COVER_FRACTION = 0.8;
/** Fraction of the SHORTER segment's samples that must sit inside CORRIDOR_TOL of the
 *  other before two different-colour segments count as bundle companions. */
const COMPANION_FRACTION = 0.6;
/** Blocks between the samples used for coverage / companion tests. */
const SAMPLE_STEP = 8;
/** Uniform spatial grid cell for the companion broad phase. */
const GRID_CELL = 16;
/** A drawn train is snapped onto its line's nearest schematic segment within this. */
const VEHICLE_SNAP = 24;

/* ---- arriving at a station tangent-continuously (the "folding" fix) ---- */
/** The approach chord is measured over this much of the segment, capped in blocks; the
 *  whole of it is rotated RIGIDLY, so the arrival direction lands exactly on target. */
const ALIGN_HOLD_FRACTION = 0.15;
const ALIGN_HOLD_MAX = 20;
/** ...and the rotation eases back to nothing by here — inside the middle third, so the
 *  pair-averaged centre of the segment is never touched. */
const ALIGN_REACH_FRACTION = 0.30;
const ALIGN_REACH_MAX = 40;
/** How parallel the segments meeting at a part must already be (mean double-angle
 *  resultant) before they are forced collinear. A real corner station scores ~0 and is
 *  left alone — rounding a genuine 90-degree turn into a diagonal would be a lie. */
const ALIGN_COHERENCE = 0.6;
/** ...and no single arrival is ever turned by more than this. */
const ALIGN_MAX_TURN = 30 * Math.PI / 180;

/* ---- reference-centreline bundling (constant gap through corners) ---- */
/** A companion may only lend its geometry to a segment it actually CONTAINS: this much
 *  of the borrower's samples must sit inside the lender's corridor. */
const REF_CONTAIN_FRACTION = 0.85;
/** A borrowed centreline is drawn as a PARALLEL of the reference: the lateral offset is
 *  measured at both ends and ramped between them, which keeps the ends exactly on the
 *  segment's own stations. Nothing more to tune here. */

/* ---- street transfers (drawn) ---- */
/** Zoom at which the cross-station walk connectors appear (the overview stays clean). */
const STREET_LINK_SCALE = 0.5;
/** ...and at which their "120 m" chip appears. */
const STREET_LINK_CHIP_SCALE = 0.8;
/** Service bullets drawn after a station name before the list is cut. */
const MAX_LABEL_BULLETS = 8;
/** Below this zoom only interchanges and line ends carry bullets (the overview stays clean). */
const BULLET_ZOOM = 0.22;
/** Below this zoom trains are plain dots in their line colour, not icon pucks. */
const TRAIN_ICON_ZOOM = 0.3;
/** Line-thickness multiplier bounds (the settings slider). */
const LINE_SCALE_MIN = 0.6;
const LINE_SCALE_MAX = 1.6;
/** Satellite-brightness multiplier bounds — multiplies the THEME's own satAlpha. */
const SAT_BRIGHT_MIN = 0.3;
const SAT_BRIGHT_MAX = 1;
/** Station-label size multiplier bounds. */
const LABEL_SCALE_MIN = 0.8;
const LABEL_SCALE_MAX = 1.3;

/* ---- train card / follow / station panel (sections 10a, 10b) ---- */
/** A followed vehicle missing from the feed this long drops follow (the pill fades
 *  meanwhile, so a rider watching the map sees it let go rather than freeze). */
const FOLLOW_LOST_MS = 10000;
/** Departures a station panel lists per line-and-destination. */
const DEPARTURES_PER_DEST = 2;
/** Departures further out than this are not worth a rider's row. */
const DEPARTURE_HORIZON_MS = 90 * 60000;
/** Gap between a train puck and its floating card, in px. */
const CARD_GAP = 18;

/* ---- live journey tracking (feature 6) ---- */
/** No self player in the feed for this long ends tracking (the banner says so first). */
const TRACK_GPS_LOST_MS = 30000;
/** The "Arrived" banner lingers this long, then tracking ends on its own. */
const TRACK_ARRIVED_LINGER_MS = 10000;
/** Blocks: "the rider is AT this platform / has reached this leg's end". */
const TRACK_NEAR_PLATFORM = 24;
/** Blocks: a live vehicle on the leg's route this close to the rider IS the rider's train. */
const TRACK_VEHICLE_MATCH = 12;
/** Blocks from the leg's own geometry before the rider counts as off course… */
const TRACK_OFF_ROUTE_DIST = 80;
/** …for this long (one GPS blip must never pop a re-plan popup). */
const TRACK_OFF_ROUTE_MS = 10000;
/** A boarding/transfer this close is "imminent" and the banner goes loud. */
const TRACK_IMMINENT_MS = 60000;
/** A re-planned journey must beat the current projection by this much to be offered. */
const TRACK_BETTER_MS = 60000;
/** Blocks/second at or above which the rider is moving like a train, not walking. */
const TRACK_RIDE_SPEED = 8;
/** Blocks: how far off the ride leg's corridor the motion test still counts as aboard. */
const TRACK_CORRIDOR = 28;

/* ---- point-to-point planning (feature 2) ---- */
/** A dropped pin reaches stations no further than this (blocks). */
const POINT_WALK_RADIUS = 300;
/** …and never more than this many of them (the nearest ones win). */
const POINT_WALK_STATIONS = 3;
/** Two DIFFERENT stations whose closest platforms are within this walk to each other. */
const STREET_TRANSFER_RADIUS = 120;
/** …capped to each station's N nearest neighbours, so a dense downtown stays sparse. */
const STREET_TRANSFER_NEIGHBOURS = 3;
/** Synthetic graph node ids for the two map points. `@` cannot collide with a platform id. */
const POINT_FROM = "@from";
const POINT_TO = "@to";

/* ---- live player GPS (feature 3) ---- */
/** Player samples kept for interpolation (the feed runs at 4 Hz). */
const PLAYER_SAMPLES = 4;
/** A player missing from the feed this long is dropped. */
const PLAYER_STALE_MS = 15000;
/** Other players' name labels only appear once the map is zoomed in this far. */
const PLAYER_LABEL_SCALE = 0.55;
/** "Plan from my location" re-plans once the rider has moved this far (blocks). */
const PLAYER_REPLAN_MOVE = 32;

/* ---- satellite basemap (feature 5) ---- */
/** Tile images fetched at once; the rest queue (a 60-tile city would otherwise storm). */
const SAT_MAX_INFLIGHT = 6;
/** How long the "no scan yet" chip stays up. */
const SAT_HINT_MS = 9000;

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

	/* --- indexed data --- */
	throughRuns: [],               // [{from:routeId, to:routeId, platform:platformId}] (mapdata)
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
	glyphs: [],                    // [{stationId, partId, x, z, colors:[hex], capsule, dir, accessible, weight, fullService}]
	stopMarks: new Map(),          // partId -> full-service? (feature 1, computeStopMarks)
	walks: [],                     // [{ax, az, bx, bz, dist}]
	streetLinks: [],               // cross-STATION walk connectors: [{ax, az, bx, bz, dist, partA, partB}]
	streetPairs: null,             // cached platform pairs behind them (shared with buildGraph)
	networkBox: null,

	/* --- live --- */
	vehicles: new Map(),           // id -> {data, samples, route, consist, disp, screen}
	players: new Map(),            // name -> {name, samples:[{t,x,y,z}], disp:{x,z}, y, lastSeen, screen}
	selfPlayer: "",                // the streamed name of "me" (?player= / settings)
	selfManual: false,             // the rider picked "I am" by hand: it now beats ?player=

	/* --- satellite basemap (feature 5) --- */
	satmeta: null,                 // {available, mask, scale, tileSamples, originX, originZ, tiles, bbox, scannedAt}
	satTiles: new Map(),           // "tx,tz" -> {img, ok, failed, queued, mask, maskOk, maskFailed, maskQueued, styled, styledWith}
	satQueue: [],
	satInflight: 0,
	satHintAt: 0,                  // when the "no scan yet" chip was raised (0 = never)

	/* --- view / interaction --- */
	view: { x: 0, z: 0, scale: 1 },
	hover: { trainId: null, glyph: null, x: 0, y: 0 },
	selection: null,               // {kind:"journey"|"line", …} — selectJourney / selectLine
	trainCard: null,               // vehicle id whose floating card is open (10a)
	follow: null,                  // {vehicleId, since, lastSeenAt, fading} — followReduce
	stationPanel: null,            // {stationId, partId} of the open station panel (10b)
	labelHits: [],                 // world-space boxes of the drawn station labels
	chipHits: [],                  // world-space boxes of the bullets drawn after station names -> line view
	weakLabels: new Map(),         // name word (lower) -> the unique bullet text buildLines gave it
	mapPick: null,                 // "from" | "to" while the map is armed to drop a pin (10c)
	pointNodes: new Map(),         // POINT_FROM/POINT_TO -> {id, xz, y, label} synthetic nodes
	tracking: null,                // live journey guidance state — trackReduce (section 10d)
	trackCam: false,               // the camera is riding the self dot (broken by any pan/zoom)

	/* --- planner --- */
	plan: {
		// {stationId, partId|null} OR a map point {point:[x,z], y, label, live}
		from: null,
		to: null,
		when: { mode: "now", at: null },       // at = "HH:MM"
		prefs: { mode: "fastest", stepFree: false },
		journeys: [],
		selectedIndex: -1,
		updatedAt: 0,
		lastBucket: -1,            // minute bucket the current plan was made for
		graph: null,
	},

	/* --- "Send to game" (section 10e): this browser paired to one in-game player --- */
	nav: {
		token: "",                 // 32 hex from api/pair, persisted in sa_mapplus_prefs
		player: "",                // the paired player's name
		label: "",                 // what this browser called itself when it paired
		online: null,              // api/navstatus: true | false | null (not asked yet)
		checking: false,           // a navstatus request is in flight
		modal: null,               // {code, error, busy} while the pairing card is open
		sending: false,            // a navigate POST is in flight (the button is disabled)
		lastSendAt: 0,             // client-side mirror of the server's 1-per-3-s limit
		sentSig: "",               // signature of the journey last accepted by the HUD
		toast: null,               // {text, kind:"ok"|"err", at}
		demoOffline: false,        // ?demo=1 only: flips the stubbed player offline
		demoLastAt: 0,             // ?demo=1 only: the stub's own rate-limit clock
	},

	prefs: {
		showHidden: false,
		theme: "light",            // light | dark  (settings menu)
		lineScale: 1,              // ribbon width multiplier, LINE_SCALE_MIN..MAX
		hiddenModes: [],           // transport modes switched off in the Layers list
		basemap: "schematic",      // schematic | satellite  (feature 5)
		showPlayers: true,         // the Players layer toggle (feature 3)
		selfPlayer: "",            // remembered "I am" name; ?player= overrides and rewrites it
		walkSpeed: WALK_SPEED_DEFAULT,   // m/s, every walk timing in the planner
		hideTrains: false,         // Layers: live vehicles off (and with them their hit targets)
		hideOtherPlayers: false,   // Layers: everybody but me off
		satBrightness: 1,          // multiplies the theme's satellite alpha, 0.3..1
		labelScale: 1,             // multiplies every station label's font size, 0.8..1.3
		hideLabels: false,         // station names off (dots stay; the selection keeps its own)
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
	navBoot();               // paint the pairing controls; re-validate a stored token
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
	state.players.clear();
	// a journey in another world is not a journey the rider can follow
	stopTracking("dimension");
	clearSelection();
	stopFollow("stop");
	closeTrainCard();
	closeStationPanel();
	if (state.es) { state.es.close(); state.es = null; }

	// network + mapdata + basemap index in parallel — mapdata and the basemap are
	// optional, the map degrades to raw rails / plain paper rather than failing.
	state.satmeta = null;
	state.satTiles = new Map();
	state.satQueue = [];
	state.satInflight = 0;
	const [net, md, sat] = await Promise.all([
		fetchJson(`${API}/network?dimension=${n}`),
		fetchJson(`${API}/mapdata?dimension=${n}`).catch(() => null),
		fetchJson(`${API}/satmeta?dimension=${n}`).catch(() => null),
	]);
	applyNetwork(net);
	if (md) applyMapdata(md);
	if (sat) applySatmeta(sat);
	prepareGeometry();
	fitView();
	syncDimUi();
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
		// the basemap index rides along: a scan running while the page is open publishes
		// new tiles every batch, and this is what makes them appear without a reload
		const [net, md, sat] = await Promise.all([
			fetchJson(`${API}/network?dimension=${state.dim}`),
			fetchJson(`${API}/mapdata?dimension=${state.dim}`).catch(() => null),
			fetchJson(`${API}/satmeta?dimension=${state.dim}`).catch(() => null),
		]);
		applyNetwork(net);
		if (md) applyMapdata(md);
		// only when the index actually changed: applySatmeta throws away every cached
		// bitmap and repaints the static layer, so an identical index every 60 s must
		// never be "applied"
		if (satmetaChanged(state.satmeta, sat)) applySatmeta(sat);
		prepareGeometry();
	} catch (e) { /* transient — the stream status covers visibility */ }
}

function applyNetwork(net) {
	state.network = net;
	// through runs belong to mapdata; a dimension whose mapdata never arrives must not
	// inherit the previous one's collapsed termini
	state.throughRuns = [];
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
			// y is kept for the 3D walk distance a dropped pin / a live player uses (feature 2)
			xz: p.mid ? [p.mid[0], p.mid[2]] : [0, 0], y: p.mid ? p.mid[1] : 0, dir,
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
	// THROUGH RUNNING (feature 7). Newer mapdata publishes the collapsed termini: route
	// `from` arriving at `platform` continues as route `to` on the SAME physical train.
	// An older payload simply has no field, which is an empty list, not an error.
	state.throughRuns = (Array.isArray(md.throughRuns) ? md.throughRuns : [])
		.filter((t) => t && t.from && t.to && t.platform && t.from !== t.to)
		.map((t) => ({ from: String(t.from), to: String(t.to), platform: String(t.platform) }));
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
		// exits are a newer mapdata field: absent on older payloads, so never overwrite
		// what a previous payload gave us with nothing
		if (Array.isArray(s.exits)) st.exits = s.exits;
		st.partWalks = s.partWalks || [];
		st.platformDistances = s.platformDistances || [];
		st.platformDistancesTruncated = !!s.platformDistancesTruncated;

		// platforms carried by mapdata fill in anything the network payload lacked
		for (const p of s.platforms || []) {
			let pl = state.platforms.get(p.id);
			if (!pl) {
				pl = { id: p.id, name: "", stationId: s.id, xz: [0, 0], y: 0, dir: [1, 0], routeIds: [] };
				state.platforms.set(p.id, pl);
			}
			pl.stationId = s.id;
			if (p.mid) { pl.xz = [p.mid[0], p.mid[2]]; pl.y = p.mid[1]; }
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

/* ----------------------------------------------------------------------------
 * 4a. SATELLITE BASEMAP (feature 5) — tile index, tile maths, tile loading
 * --------------------------------------------------------------------------
 * The server scans the world into square PNG tiles and publishes an index:
 *
 *   scale        blocks per sample = blocks per PNG pixel      (2)
 *   tileSamples  pixels per tile edge                          (256)
 *   originX/Z    world coords of pixel (0,0) of tile (0,0); always multiples of 512
 *
 * so one tile covers scale x tileSamples = 512 blocks square, and
 *
 *   world -> tile:  gx = floor((worldX - originX) / scale); tx = floor(gx / tileSamples)
 *   tile  -> world: worldX = originX + (tx * tileSamples + px) * scale
 *
 * Everything below is that arithmetic and nothing else; the drawing (section 7) just
 * turns a tile extent into a screen rect. `+z` is south, i.e. down-screen, like the
 * rest of the map.
 * ------------------------------------------------------------------------- */

/** Blocks covered by one tile edge. */
function satTileSpan(meta) {
	return (meta && meta.scale ? meta.scale : 2) * (meta && meta.tileSamples ? meta.tileSamples : 256);
}

/** World point -> {tx, tz, px, pz}: which tile it is in, and where inside it. */
function satTileOf(meta, worldX, worldZ) {
	const scale = (meta && meta.scale) || 2;
	const n = (meta && meta.tileSamples) || 256;
	const ox = (meta && meta.originX) || 0, oz = (meta && meta.originZ) || 0;
	const gx = Math.floor((worldX - ox) / scale), gz = Math.floor((worldZ - oz) / scale);
	const tx = Math.floor(gx / n), tz = Math.floor(gz / n);
	return { tx, tz, px: gx - tx * n, pz: gz - tz * n };
}

/** Tile pixel -> the world coordinate of that pixel's top-left corner. */
function satWorldOf(meta, tx, tz, px, pz) {
	const scale = (meta && meta.scale) || 2;
	const n = (meta && meta.tileSamples) || 256;
	return [
		((meta && meta.originX) || 0) + (tx * n + px) * scale,
		((meta && meta.originZ) || 0) + (tz * n + pz) * scale,
	];
}

/** The world rectangle a tile covers: [x0, z0, x1, z1], half-open at x1/z1. */
function satTileExtent(meta, tx, tz) {
	const span = satTileSpan(meta);
	const [x0, z0] = satWorldOf(meta, tx, tz, 0, 0);
	return [x0, z0, x0 + span, z0 + span];
}

function satEnabled() {
	return state.prefs.basemap === "satellite";
}

/** Is there actually imagery to draw? (satellite selected but never scanned = no) */
function satHasTiles() {
	return !!(state.satmeta && state.satmeta.available && state.satmeta.tiles.length);
}

function satKey(tx, tz) { return tx + "," + tz; }

/**
 * The tile index changed? The server's own `scannedAt` stamp
 * decides, and a first payload counts as a change as soon as it carries tiles.
 */
function satmetaChanged(current, payload) {
	if (!payload) return false;
	if (!current) return !!payload.available && (payload.tiles || []).length > 0;
	return (payload.scannedAt || 0) !== (current.scannedAt || 0);
}

function applySatmeta(meta) {
	if (!meta) return;
	state.satmeta = {
		available: !!meta.available,
		dimension: meta.dimension,
		scannedAt: meta.scannedAt || 0,
		scale: meta.scale || 2,
		tileSamples: meta.tileSamples || 256,
		originX: meta.originX || 0,
		originZ: meta.originZ || 0,
		tiles: (meta.tiles || []).filter((t) => Array.isArray(t) && t.length >= 2),
		bbox: meta.bbox || null,
		mask: !!meta.mask,               // this server writes class-mask tiles
		scanning: !!meta.scanning,
	};
	// A changed index invalidates every cached bitmap: a full refresh rewrites tiles
	// under the same keys, and the only thing the client can tell apart is scannedAt.
	state.satTiles = new Map();
	state.satQueue = [];
	state.satInflight = 0;
	invalidateStatic();                  // either basemap may draw from these tiles
	maybeSatHint();
}

function satTileRecord(tx, tz) {
	const key = satKey(tx, tz);
	let rec = state.satTiles.get(key);
	if (!rec) {
		rec = { img: null, ok: false, failed: false, queued: false,
			mask: null, maskOk: false, maskFailed: false, maskQueued: false,
			styled: null, styledWith: null };
		state.satTiles.set(key, rec);
	}
	return rec;
}

/**
 * The colour (satellite) tile, or null while it is still coming / after a 404. Demo
 * mode paints its own (a canvas is a perfectly good drawImage source); the real page
 * fetches `api/sattile`, at most SAT_MAX_INFLIGHT at a time.
 */
function satTileImage(tx, tz) {
	const rec = satTileRecord(tx, tz);
	if (rec.ok) return rec.img;
	if (rec.failed || rec.queued) return null;
	if (DEMO) {
		try {
			rec.img = demoSatTile(tx, tz);
			rec.ok = !!rec.img;
		} catch (e) { rec.failed = true; }
		return rec.ok ? rec.img : null;
	}
	rec.queued = true;
	state.satQueue.push([tx, tz, "color"]);
	pumpSatQueue();
	return null;
}

/**
 * The class-mask tile STYLED for the current theme (a canvas), or null while it loads.
 * Styling happens once per tile per theme: the mask's red channel is a class code
 * (1 water, 2 woodland, 3 snow — the server's CLASS_* constants), painted with the
 * palette's fills; every other class stays transparent so the paper shows through.
 */
function maskTileCanvas(tx, tz) {
	const rec = satTileRecord(tx, tz);
	if (rec.maskOk) {
		if (!rec.styled || rec.styledWith !== PALETTE) {
			rec.styled = styleMask(rec.mask, state.satmeta ? state.satmeta.tileSamples : 256);
			rec.styledWith = PALETTE;
		}
		return rec.styled;
	}
	if (rec.maskFailed || rec.maskQueued) return null;
	if (DEMO) {
		try {
			rec.mask = demoMaskTile(tx, tz);
			rec.maskOk = !!rec.mask;
		} catch (e) { rec.maskFailed = true; }
		return rec.maskOk ? maskTileCanvas(tx, tz) : null;
	}
	rec.maskQueued = true;
	state.satQueue.push([tx, tz, "mask"]);
	pumpSatQueue();
	return null;
}

const MASK_WATER = 1, MASK_FOREST = 2, MASK_SNOW = 3;

/** The theme fill for one mask class, or null for "paper shows through". */
function maskClassColor(cls) {
	if (cls === MASK_WATER) return PALETTE.water;
	if (cls === MASK_FOREST) return PALETTE.forest;
	if (cls === MASK_SNOW) return PALETTE.snow;
	return null;
}

function hexRgb(hex) {
	const h = String(hex || "").replace("#", "");
	if (h.length !== 6) return null;
	return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)];
}

/** Mask image → styled canvas (null when the environment has no real 2D context). */
function styleMask(img, n) {
	if (!img || typeof document === "undefined") return null;
	const cv = document.createElement("canvas");
	cv.width = n; cv.height = n;
	const g = cv.getContext("2d");
	if (!g || typeof g.getImageData !== "function") return null;
	g.drawImage(img, 0, 0, n, n);
	const data = g.getImageData(0, 0, n, n);
	if (!data || !data.data) return null;
	const d = data.data;
	const fills = [];
	for (let c = 0; c < 8; c++) fills.push(hexRgb(maskClassColor(c)));
	for (let i = 0; i < d.length; i += 4) {
		const f = d[i + 3] ? fills[d[i] < fills.length ? d[i] : 0] : null;
		if (!f) { d[i + 3] = 0; continue; }
		d[i] = f[0]; d[i + 1] = f[1]; d[i + 2] = f[2]; d[i + 3] = 255;
	}
	g.putImageData(data, 0, 0);
	return cv;
}

function pumpSatQueue() {
	while (state.satInflight < SAT_MAX_INFLIGHT && state.satQueue.length) {
		const [tx, tz, kind] = state.satQueue.shift();
		const rec = state.satTiles.get(satKey(tx, tz));
		if (!rec) continue;
		const isMask = kind === "mask";
		if (isMask) rec.maskQueued = false; else rec.queued = false;
		state.satInflight++;
		const img = new Image();
		img.onload = () => {
			if (isMask) { rec.mask = img; rec.maskOk = true; rec.styled = null; }
			else { rec.img = img; rec.ok = true; }
			state.satInflight--;
			pumpSatQueue();
			invalidateStatic();
		};
		img.onerror = () => {
			if (isMask) rec.maskFailed = true; else rec.failed = true;   // 404 = never scanned; never retried
			state.satInflight--;
			pumpSatQueue();
		};
		img.src = `${API}/sattile?dimension=${state.dim}&tx=${tx}&tz=${tz}` + (isMask ? "&kind=mask" : "");
	}
}

function maybeSatHint() {
	const el = $("satHint");
	if (!el || !el.classList) return;
	const need = satEnabled() && !(state.satmeta && state.satmeta.available);
	if (!need) { el.classList.add("hidden"); return; }
	if (state.satHintAt) return;               // already shown once this session
	state.satHintAt = now();
	el.textContent = "No basemap scan yet — run /dispatch basemap scan";
	el.classList.remove("hidden");
	setTimeout(() => { if (el.classList) el.classList.add("hidden"); }, SAT_HINT_MS);
}

/* ============================================================================
 * 5. geometry preparation (legs -> ribbons -> glyphs -> labels)
 * ========================================================================== */

function prepareGeometry() {
	ensureParts();
	indexParts();
	buildLegs();
	buildLines();
	state.streetPairs = null;      // recomputed once below, then reused by buildGraph
	buildSegments();
	buildGlyphs();
	buildWalks();
	buildStreetLinks();
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
			// a rider reads levels, not block offsets: the part above the main one is
			// "Upper level", the one below "Lower level"
			p.sub = p.name || (dy ? (dy > 0 ? "Upper level" : "Lower level") : "Part " + (p.index + 1));
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
 * Strip direction tokens from a label, STRICTLY: "" when nothing survives.
 *
 * Only WHOLE words go, case-insensitively ("2 IN" -> "2", "A Inbound" -> "A"), never
 * substrings ("Ba" stays "Ba").
 */
function stripDirectionTokens(raw) {
	const src = String(raw == null ? "" : raw).trim();
	if (!src) return "";
	const words = src.replace(DIRECTION_ARROWS, " ").split(LABEL_SEPARATORS).filter(Boolean);
	const kept = words.filter((w) => !DIRECTION_TOKENS.has(w.toUpperCase()));
	return kept.join(" ").replace(/^[\s\-‐-―_/\\|·,.:;]+|[\s\-‐-―_/\\|·,.:;]+$/g, "").trim();
}

/** Strip direction tokens, keeping the original when that would leave nothing. */
function normalizeServiceLabel(raw) {
	const src = String(raw == null ? "" : raw).trim();
	return stripDirectionTokens(src) || src;
}

/** Characters of a route NAME used as a bullet when buildLines has not assigned one. */
const NAME_BULLET_CHARS = 3;

/** Words of a route name that say what it is rather than which one it is. */
const GENERIC_NAME_WORDS = new Set(["LINE", "ROUTE", "RAIL", "RAILWAY", "LIGHT", "LRT", "FERRY",
	"SHUTTLE", "EXPRESS", "LOCAL", "SERVICE", "BUS", "TRAM", "CABLE", "CAR", "LOOP", "METRO",
	"SUBWAY", "TRAIN", "THE", "LIMITED"]);

/** The word of a route name a bullet should be built from ("Village Light Rail" -> "Village"). */
function labelSourceWord(name) {
	const words = stripDirectionTokens(name).split(/\s+/).filter(Boolean);
	if (!words.length) return "";
	const specific = words.find((w) => !GENERIC_NAME_WORDS.has(w.toUpperCase()) && !/^\d+$/.test(w));
	return specific || words[0];
}

/**
 * The bullet text for one route, and whether it is a REAL service letter.
 *
 * number -> short destination -> name word. The number wins when it is not merely a
 * direction ("IN"/"OU" are how some operators name their two directions). A one- or
 * two-character destination ("BakerLink||D") is the letter the operator typed in the
 * wrong box and counts as real too. Otherwise the route's NAME supplies a word, and
 * buildLines turns each such word into the SHORTEST prefix no other line uses ("Village"
 * -> "V", "Bridge" -> "Br" when B is taken) so unnumbered lines get a bullet that is
 * unique across the map instead of a three-letter stub. Both directions share the word,
 * so they collapse to ONE bullet.
 */
function routeLabelInfo(rt) {
	const num = rt && rt.number != null ? String(rt.number).trim() : "";
	const fromNum = stripDirectionTokens(num);
	if (fromNum) return { label: fromNum, strong: true, word: "" };
	const dest = stripDirectionTokens(routeDest(rt && rt.name));
	if (dest && dest.length <= 2 && /^[A-Za-z0-9]+$/.test(dest)) return { label: dest, strong: true, word: "" };
	const name = (rt && (rt.display || rt.name)) || "";
	const word = labelSourceWord(routeBase(name) || name);
	if (word) {
		const assigned = state.weakLabels.get(word.toLowerCase());
		return { label: assigned || word.slice(0, NAME_BULLET_CHARS), strong: false, word };
	}
	return { label: num || name || "", strong: false, word: "" };
}

/** The bullet text for one route (see routeLabelInfo). */
function routeServiceLabel(rt) {
	return routeLabelInfo(rt).label;
}

/** Express services are written "4*" (a diamond on the map); this is the number inside it. */
const bulletText = (label) => String(label || "").replace(/\*$/, "");
const bulletIsExpress = (label) => /\*$/.test(String(label || ""));

/** "4" before "4*" before "4S": the base service first, its express diamond next, then variants. */
function compareServiceLabels(a, b) {
	const ba = bulletText(a), bb = bulletText(b);
	if (ba !== bb) return ba.localeCompare(bb, undefined, { numeric: true, sensitivity: "base" });
	return (bulletIsExpress(a) ? 1 : 0) - (bulletIsExpress(b) ? 1 : 0);
}

/* ---- near-identical colours are ONE line (feature: shade drift) ------------
 * A user who makes an inbound and an outbound route by hand often ends up with two
 * colours a few RGB points apart. Colour IS the line, so two shades that close have to
 * resolve to one, or the pair braids down the corridor as two "lines". Union-find over
 * the (tiny) set of route colours, joined when every channel is within
 * COLOR_MERGE_DELTA; the representative is the LOWEST colour int in the cluster, which
 * makes the choice deterministic. Merging is applied to the ROUTES themselves at
 * buildLines time, so segments, bullets, chips, the line view, planner leg colours and
 * vehicle pucks all agree without a single call site having to remember. */
const COLOR_MERGE_DELTA = 16;

function canonicaliseRouteColors(routes) {
	const ints = [...new Set(routes.map((rt) => (rt.color || 0) & 0xFFFFFF))].sort((a, b) => a - b);
	const parent = new Map(ints.map((v) => [v, v]));
	const find = (v) => {
		while (parent.get(v) !== v) { parent.set(v, parent.get(parent.get(v))); v = parent.get(v); }
		return v;
	};
	const near = (a, b) => Math.max(
		Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)),
		Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)),
		Math.abs((a & 255) - (b & 255))) <= COLOR_MERGE_DELTA;
	for (let i = 0; i < ints.length; i++) {
		for (let j = i + 1; j < ints.length; j++) {
			if (!near(ints[i], ints[j])) continue;
			const ra = find(ints[i]), rb = find(ints[j]);
			if (ra !== rb) parent.set(Math.max(ra, rb), Math.min(ra, rb));
		}
	}
	const canon = new Map();
	for (const v of ints) canon.set(v, find(v));
	return canon;
}

function buildLines() {
	state.lines = new Map();
	// resolve shade drift FIRST, over every route the payload knows (not just the
	// visible ones), so toggling a layer can never re-cluster the network's colours
	const canon = canonicaliseRouteColors([...state.routes.values()]);
	state.colorCanon = canon;
	for (const rt of state.routes.values()) {
		const rep = canon.get((rt.color || 0) & 0xFFFFFF);
		if (rep === undefined) continue;
		rt.color = rep;
		rt.hex = colorHex(rep);
	}
	state.weakLabels = new Map();
	for (const rt of candidateRoutes()) {
		let line = state.lines.get(rt.hex);
		if (!line) {
			line = { hex: rt.hex, colorInt: (rt.color || 0) & 0xFFFFFF, routeIds: [], serviceLabels: [],
				modes: new Set(), nameWords: new Set() };
			state.lines.set(rt.hex, line);
		}
		line.routeIds.push(rt.id);
		line.modes.add(rt.mode || "train");
		const info = routeLabelInfo(rt);
		if (info.strong) {
			if (!line.serviceLabels.some((l) => l.toLowerCase() === info.label.toLowerCase())) {
				line.serviceLabels.push(info.label);
			}
		} else if (info.word) {
			line.nameWords.add(info.word);
		}
	}
	// Name-derived bullets: only for lines with NO real service letter (a line whose
	// other route is lettered never grows a name stub beside it), each the shortest
	// prefix of its word that no bullet on the map already uses. Deterministic order so
	// the same network always yields the same letters.
	const taken = new Set();
	for (const line of state.lines.values()) for (const l of line.serviceLabels) taken.add(bulletText(l).toLowerCase());
	const ordered = [...state.lines.values()].sort((a, b) => a.colorInt - b.colorInt || (a.hex < b.hex ? -1 : 1));
	for (const line of ordered) {
		if (line.serviceLabels.length) continue;
		for (const word of [...line.nameWords].sort()) {
			let label = word;
			for (let n = 1; n <= word.length; n++) {
				if (!taken.has(word.slice(0, n).toLowerCase())) { label = word.slice(0, n); break; }
			}
			taken.add(label.toLowerCase());
			state.weakLabels.set(word.toLowerCase(), label);
			line.serviceLabels.push(label);
		}
	}
	for (const line of state.lines.values()) line.serviceLabels.sort(compareServiceLabels);
	return state.lines;
}

/** The bullets one colour shows ("4", "4*", "4S" …). */
function lineLabels(hex) {
	const line = state.lines.get(hex);
	return line ? line.serviceLabels.slice(0, MAX_LABEL_BULLETS) : [];
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
/**
 * Blend a segment's ends onto the part centroids WITHOUT hooks: each end's full
 * correction delta is applied at the endpoint and decays smoothly (cosine) over
 * up to 45% of the segment's arc length. Translating by a decaying DELTA keeps
 * the polyline's direction monotone — the old approach lerped samples toward the
 * centroid POINT, which bunched them there and made the line double back on
 * itself at every station whose platforms sit laterally off the through track
 * (the "line goes back on itself" play-test report).
 */
function snapEnds(pts, a, b) {
	const n = pts.length;
	if (n < 2) return pts;
	const cum = [0];
	for (let i = 1; i < n; i++) {
		cum.push(cum[i - 1] + Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]));
	}
	const total = cum[n - 1] || 1;
	// 35% keeps the two end windows disjoint AND leaves the middle third of the
	// segment unwarped (each endpoint still lands EXACTLY on its centroid);
	// 48 blocks caps how far a long segment feels the station pull.
	const reach = Math.max(1e-6, Math.min(total * 0.35, 48));
	const ease = (t) => t >= 1 ? 0 : (Math.cos(Math.PI * Math.max(0, t)) + 1) / 2;
	if (a) {
		const dx = a[0] - pts[0][0], dz = a[1] - pts[0][1];
		for (let i = 0; i < n; i++) {
			const w = ease(cum[i] / reach);
			if (w <= 0) break;
			pts[i][0] += dx * w;
			pts[i][1] += dz * w;
		}
	}
	if (b) {
		const dx = b[0] - pts[n - 1][0], dz = b[1] - pts[n - 1][1];
		for (let i = n - 1; i >= 0; i--) {
			const w = ease((total - cum[i]) / reach);
			if (w <= 0) break;
			pts[i][0] += dx * w;
			pts[i][1] += dz * w;
		}
	}
	return pts;
}

/* ----------------------------------------------------------------------------
 * 5b-i. HAIRPIN / TURNING-LOOP TRUNCATION
 * --------------------------------------------------------------------------
 * Real track at a terminus turns the train round: a balloon loop, or a hairpin into a
 * relief siding. Drawn literally, the line runs past the station, loops the loop over
 * the glyph and comes back — the tangle in the mega-hub screenshot.
 *
 * A schematic line approaches its stop ONCE, straight in. So per end: walk in from the
 * OTHER end and find the first sample that comes within APPROACH_R of this end's
 * centroid — the moment the line first arrives. If the geometry after that point wanders
 * far further than the straight run in (arc length > APPROACH_OVERSHOOT x that straight
 * distance) it is a loop, not an approach, and it is cut off. The remaining end is then
 * snapped to the centroid by the usual blend.
 * ------------------------------------------------------------------------- */

/** Blocks: "the line has arrived at this station". */
const APPROACH_R = 28;
/** Arc length past the arrival point, as a multiple of the straight distance from it to
 *  the centroid, above which the tail is a loop rather than an approach. */
const APPROACH_OVERSHOOT = 1.6;

/**
 * Cut any turning loop off each end of a raw leg polyline. `ca`/`cb` are the centroids
 * of the parts at pts[0] and pts[n-1]. Returns a polyline (possibly the straight chord
 * when the whole thing sits inside one station's area).
 */
function truncateApproaches(pts, ca, cb) {
	let cur = pts.map((p) => [p[0], p[1]]);
	if (cur.length < 3) return cur;

	// the tail at the FAR end (pts[n-1] / cb)
	const cutTail = (line, c) => {
		if (!c) return line;
		const n = line.length;
		let k = -1;
		for (let i = 0; i < n; i++) if (dist(line[i], c) < APPROACH_R) { k = i; break; }
		if (k < 0 || k >= n - 1) return line;              // never gets near, or arrives last
		if (k < 2) return null;                            // the whole line is in the station
		let arc = 0;
		for (let i = k; i < n - 1; i++) arc += dist(line[i], line[i + 1]);
		const straight = dist(line[k], c);
		if (arc <= Math.max(4, straight * APPROACH_OVERSHOOT)) return line;
		return line.slice(0, k + 1);
	};

	let out = cutTail(cur, cb);
	if (out === null) return ca && cb ? [[ca[0], ca[1]], [cb[0], cb[1]]] : cur;
	// the head is the same problem seen from the other side
	out = cutTail(out.slice().reverse(), ca);
	if (out === null) return ca && cb ? [[ca[0], ca[1]], [cb[0], cb[1]]] : cur;
	out = out.slice().reverse();
	if (out.length < 2 || polylineLength(out) < 1) {
		return ca && cb ? [[ca[0], ca[1]], [cb[0], cb[1]]] : cur;
	}
	return out;
}

/* ----------------------------------------------------------------------------
 * 5b-ii. TANGENT-CONTINUOUS ARRIVALS — the station "folding" fix
 * --------------------------------------------------------------------------
 * snapEnds pulls each segment's end onto its station-part centroid independently, so
 * the two segments of a through station arrive from whatever direction their own track
 * happened to have. Where the centroid sits a few blocks off the through line that
 * leaves a small lens — the dark strand doubling back beside the glyph, and the hump on
 * an otherwise straight line, in the play-test shots.
 *
 * Fix: per (part, colour) the arrivals share ONE axis (double-angle mean of their
 * approach chords) and each terminal stretch is ROTATED about its endpoint until its
 * chord lies on that axis. Rotation is an isometry, so nothing bunches; the endpoint
 * itself never moves; and consecutive segments therefore leave the station collinear.
 * ------------------------------------------------------------------------- */

/**
 * Unit vector from one end of a polyline out along EXACTLY `reach` blocks of it
 * (interpolated within the sample it lands in — walking to the next sample instead
 * would overshoot the rigid part of the rotation window and leave the alignment short).
 */
function endApproachChord(pts, end, reach) {
	const n = pts.length;
	if (n < 2) return null;
	const start = end === 0 ? 0 : n - 1;
	const step = end === 0 ? 1 : -1;
	let acc = 0, i = start;
	let tip = pts[start];
	while (i + step >= 0 && i + step < n) {
		const j = i + step;
		const d = dist(pts[i], pts[j]);
		if (acc + d >= reach && d > 1e-9) {
			const f = (reach - acc) / d;
			tip = [pts[i][0] + (pts[j][0] - pts[i][0]) * f, pts[i][1] + (pts[j][1] - pts[i][1]) * f];
			break;
		}
		acc += d; i = j; tip = pts[j];
	}
	const vx = tip[0] - pts[start][0], vz = tip[1] - pts[start][1];
	const l = Math.hypot(vx, vz);
	return l < 1e-6 ? null : [vx / l, vz / l];
}

/**
 * Rotate a segment's terminal window about its endpoint: rigidly over the first `hold`
 * blocks (so the approach chord lands exactly on target) then easing to zero by `reach`.
 * Arc lengths are measured BEFORE anything moves, or the window would walk.
 */
function rotateEndWindow(pts, end, delta, hold, reach) {
	if (!delta || Math.abs(delta) < 1e-9 || reach <= hold) return;
	const n = pts.length;
	const order = [], acc = [];
	let a = 0;
	if (end === 0) {
		for (let i = 0; i < n; i++) { if (i) a += dist(pts[i - 1], pts[i]); order.push(i); acc.push(a); }
	} else {
		for (let i = n - 1; i >= 0; i--) { if (i < n - 1) a += dist(pts[i + 1], pts[i]); order.push(i); acc.push(a); }
	}
	const ox = pts[order[0]][0], oz = pts[order[0]][1];
	for (let k = 0; k < order.length; k++) {
		const d = acc[k];
		if (d >= reach) break;
		const w = d <= hold ? 1 : (Math.cos(Math.PI * (d - hold) / (reach - hold)) + 1) / 2;
		const ang = delta * w, c = Math.cos(ang), sn = Math.sin(ang);
		const p = pts[order[k]];
		const dx = p[0] - ox, dz = p[1] - oz;
		p[0] = ox + dx * c - dz * sn;
		p[1] = oz + dx * sn + dz * c;
	}
}

function alignEndTangents(drawn) {
	const groups = new Map();          // partId|hex -> [{seg, end}]
	for (const s of drawn) {
		for (const end of [0, 1]) {
			const k = (end === 0 ? s.partA : s.partB) + "|" + s.hex;
			let list = groups.get(k);
			if (!list) { list = []; groups.set(k, list); }
			list.push({ seg: s, end });
		}
	}
	for (const list of groups.values()) {
		if (list.length < 2) continue;                 // a terminus keeps its own approach
		const items = [];
		let sx = 0, sz = 0;
		for (const it of list) {
			const total = polylineLength(it.seg.pts);
			const hold = Math.min(total * ALIGN_HOLD_FRACTION, ALIGN_HOLD_MAX);
			const t = endApproachChord(it.seg.pts, it.end, hold);
			if (!t) continue;
			items.push({ seg: it.seg, end: it.end, total, hold, t });
			sx += t[0] * t[0] - t[1] * t[1];           // cos 2a
			sz += 2 * t[0] * t[1];                     // sin 2a
		}
		if (items.length < 2) continue;
		// how much the arrivals already agree on ONE axis: ~1 for a through station,
		// ~0 where the line genuinely turns a corner (which must stay a corner)
		if (Math.hypot(sx, sz) / items.length < ALIGN_COHERENCE) continue;
		const axis = Math.atan2(sz, sx) / 2;
		const ux = Math.cos(axis), uz = Math.sin(axis);
		for (const it of items) {
			const sgn = (it.t[0] * ux + it.t[1] * uz) >= 0 ? 1 : -1;
			let d = Math.atan2(uz * sgn, ux * sgn) - Math.atan2(it.t[1], it.t[0]);
			while (d > Math.PI) d -= 2 * Math.PI;
			while (d < -Math.PI) d += 2 * Math.PI;
			if (Math.abs(d) > ALIGN_MAX_TURN) continue;
			// rotate rigidly one sample PAST the chord, so the chord itself — which ends
			// mid-sample — is turned by the full angle and lands exactly on the axis
			const spacing = it.total / Math.max(1, it.seg.pts.length - 1);
			const rigid = it.hold + spacing;
			rotateEndWindow(it.seg.pts, it.end, d, rigid,
				Math.max(rigid * 1.15, Math.min(it.total * ALIGN_REACH_FRACTION, ALIGN_REACH_MAX)));
		}
	}
}

/* ----------------------------------------------------------------------------
 * 5b-iii. REFERENCE-CENTRELINE BUNDLING — a constant gap through corners
 * --------------------------------------------------------------------------
 * Each colour used to offset its OWN pair-averaged centreline. On a curve the two
 * centrelines disagree slightly (different tracks, different corner radii), so the
 * offsets fought and the bundle kinked and wobbled at the apex.
 *
 * Now, within a companion group, ONE geometry is the reference — the longest segment of
 * the lowest colour int, which is deterministic — and every companion it CONTAINS is
 * redrawn as a PARALLEL of that reference (see projectOntoReference): same shape, its
 * own lateral distance, its own endpoints. Two members of a bundle then share a shape,
 * so the gap between them can only change as slowly as their lateral offsets do —
 * corners included — while each still meets its own stations.
 *
 * References resolve in ascending colour int, so a chain (orange follows green follows
 * blue) lands everyone on the same geometry without any cycle risk.
 * ------------------------------------------------------------------------- */

function arcTable(pts) {
	const cum = [0];
	for (let i = 1; i < pts.length; i++) cum.push(cum[i - 1] + dist(pts[i - 1], pts[i]));
	return cum;
}

/** Arc-length parameter of the point on `pts` nearest `p`, with that distance. */
function paramOfNearest(p, pts, cum) {
	let best = Infinity, at = 0;
	for (let i = 1; i < pts.length; i++) {
		const a = pts[i - 1], b = pts[i];
		const vx = b[0] - a[0], vz = b[1] - a[1];
		const l2 = vx * vx + vz * vz;
		const t = l2 <= 1e-12 ? 0 : clamp(((p[0] - a[0]) * vx + (p[1] - a[1]) * vz) / l2, 0, 1);
		const d = Math.hypot(p[0] - (a[0] + vx * t), p[1] - (a[1] + vz * t));
		if (d < best) { best = d; at = cum[i - 1] + t * Math.sqrt(l2); }
	}
	return { dist: best, at };
}

function pointAtArc(pts, cum, target) {
	const total = cum[cum.length - 1];
	const t = clamp(target, 0, total);
	let i = 1;
	while (i < cum.length - 1 && cum[i] < t) i++;
	const span = cum[i] - cum[i - 1] || 1;
	const f = clamp((t - cum[i - 1]) / span, 0, 1);
	return [pts[i - 1][0] + (pts[i][0] - pts[i - 1][0]) * f, pts[i - 1][1] + (pts[i][1] - pts[i - 1][1]) * f];
}

/** Unit tangent of a polyline at an arc-length position. */
function tangentAtArc(pts, cum, target) {
	const total = cum[cum.length - 1];
	const h = Math.max(1e-3, Math.min(2, total / 64));
	const a = pointAtArc(pts, cum, clamp(target - h, 0, total));
	const b = pointAtArc(pts, cum, clamp(target + h, 0, total));
	const vx = b[0] - a[0], vz = b[1] - a[1];
	const l = Math.hypot(vx, vz);
	return l < 1e-9 ? [1, 0] : [vx / l, vz / l];
}

/**
 * Re-express `pts` as a PARALLEL of `refPts`.
 *
 * The segment's two ends are projected onto the reference (a nearest point is always a
 * perpendicular foot, so the vector from it to the end is a pure lateral offset). The
 * drawn geometry is then the reference walked at matching arc length, pushed out along
 * the reference's own normal by that offset, ramped linearly between the two ends.
 *
 * What that buys, in order of how much it mattered:
 *   - the drawn line has the REFERENCE's shape, so a bundle's members can never disagree
 *     around a corner: the gap is whatever the offsets say and it changes only slowly;
 *   - both endpoints land EXACTLY on the segment's own ends, so a line still meets its
 *     own station glyphs and consecutive segments still join;
 *   - a line that stops where the reference does not keeps its lateral distance instead
 *     of doglegging in to touch the reference and back out again.
 *
 * Returns null when the match is not like-for-like — the stretch found on the reference
 * is far shorter or longer than the segment itself — so nothing is ever stretched over a
 * corridor it does not actually run along.
 */
function projectOntoReference(pts, refPts) {
	const n = pts.length;
	if (n < 3 || !refPts || refPts.length < 2) return null;
	const cum = arcTable(refPts);
	if (cum[cum.length - 1] < 1e-6) return null;
	const p0 = paramOfNearest(pts[0], refPts, cum);
	const p1 = paramOfNearest(pts[n - 1], refPts, cum);
	const span = Math.abs(p1.at - p0.at);
	const own = polylineLength(pts);
	if (own < 1e-6 || span < own * 0.55 || span > own * 1.8) return null;
	const lateral = (p, at) => {
		const q = pointAtArc(refPts, cum, at);
		const t = tangentAtArc(refPts, cum, at);
		return (p[0] - q[0]) * -t[1] + (p[1] - q[1]) * t[0];       // signed, on the normal
	};
	const d0 = lateral(pts[0], p0.at), d1 = lateral(pts[n - 1], p1.at);
	if (Math.abs(d0) > CORRIDOR_TOL * 2 || Math.abs(d1) > CORRIDOR_TOL * 2) return null;
	const out = [];
	for (let i = 0; i < n; i++) {
		const u = i / (n - 1);
		const at = p0.at + (p1.at - p0.at) * u;
		const q = pointAtArc(refPts, cum, at);
		const t = tangentAtArc(refPts, cum, at);
		const d = d0 + (d1 - d0) * u;
		out.push([q[0] - t[1] * d, q[1] + t[0] * d]);
	}
	// the ends are perpendicular feet, so this is exact — but pin them anyway, because
	// "the line meets its own station" is the one property that must never drift
	out[0] = [pts[0][0], pts[0][1]];
	out[n - 1] = [pts[n - 1][0], pts[n - 1][1]];
	return out;
}

/** Resolve every drawn segment's DRAWN geometry (`drawPts`); `pts` stays the segment's
 *  own pair-averaged centreline, which is what coverage and hit tests reason about. */
function buildDrawGeometry(all) {
	// Canonical form: uniform arc-length sampling. A borrower reads its reference at
	// uniform positions, so the two coincide EXACTLY only if the reference is uniform
	// too — and after the end-tangent rotation `pts` no longer is.
	for (const s of all) {
		s.drawPts = resamplePolyline(s.pts, SEG_SAMPLES);
		s.drawBbox = bboxOf(s.drawPts);
		s.refId = null;
	}
	const order = all.filter((s) => !s.suppressed)
		.sort((a, b) => a.colorInt - b.colorInt || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
	for (const s of order) {
		if (s.count < 2) continue;
		let ref = null;
		for (const o of s.companionSegs) {
			if (o.suppressed || o.colorInt >= s.colorInt) continue;
			if (((s.nearFrac && s.nearFrac.get(o)) || 0) < REF_CONTAIN_FRACTION) continue;
			if (!ref || o.colorInt < ref.colorInt || (o.colorInt === ref.colorInt && o.len > ref.len)) ref = o;
		}
		if (!ref) continue;
		const pts = projectOntoReference(s.drawPts, ref.drawPts);
		if (!pts) continue;
		s.drawPts = pts;
		s.drawBbox = bboxOf(pts);
		s.refId = ref.id;
	}
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
			// cut turning loops off BEFORE averaging: a looped inbound track and a plain
			// outbound one must not be averaged together, or the pair folds into a bowtie
			bk.polys.push(resamplePolyline(
				truncateApproaches(leg.pts, partCentroid(a), partCentroid(b)), SEG_SAMPLES));
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

	/* --- 4. arrive at every shared station along ONE axis (no folding) --- */
	const drawn = all.filter((s) => !s.suppressed);
	alignEndTangents(drawn);
	for (const s of drawn) {
		s.samples = sampleAlong(s.pts, SAMPLE_STEP);
		s.len = polylineLength(s.pts);
		s.bbox = bboxOf(s.pts);
		s.offSign = canonicalOffsetSign(s.pts);
	}

	/* --- 5. cross-colour bundling over what survives --- */
	bundleCompanions(drawn);
	for (const s of drawn) {
		const colors = new Map([[s.hex, s.colorInt]]);
		for (const o of s.companionSegs) colors.set(o.hex, o.colorInt);
		const list = [...colors.keys()].sort((x, y) => colors.get(x) - colors.get(y) || (x < y ? -1 : 1));
		s.companions = list;
		s.count = list.length;
		s.idx = list.indexOf(s.hex);
	}

	/* --- 6. one reference centreline per bundle, so the gap is constant --- */
	buildDrawGeometry(all);

	/* --- 7. stable paint order + per-colour index for the vehicle snap --- */
	drawn.sort((a, b) => a.colorInt - b.colorInt || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
	state.ribbons = drawn;
	for (const s of drawn) {
		if (!state.segsByColor.has(s.hex)) state.segsByColor.set(s.hex, []);
		state.segsByColor.get(s.hex).push(s);
	}
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
			&& o.bbox[2] >= bb[0] - COVER_TOL && o.bbox[0] <= bb[2] + COVER_TOL
			&& o.bbox[3] >= bb[1] - COVER_TOL && o.bbox[1] <= bb[3] + COVER_TOL);
		if (!others.length) continue;
		let covered = 0;
		const by = new Set();
		for (const p of s.samples) {
			let bestD = Infinity, bestSeg = null;
			for (const o of others) {
				const ob = o.bbox;
				if (p[0] < ob[0] - COVER_TOL || p[0] > ob[2] + COVER_TOL
					|| p[1] < ob[1] - COVER_TOL || p[1] > ob[3] + COVER_TOL) continue;
				const d = pointPolylineDist(p, o.pts);
				if (d < bestD) { bestD = d; bestSeg = o; }
				if (bestD <= 0.5) break;                     // already sitting on top of it
			}
			if (bestD <= COVER_TOL) { covered++; if (bestSeg) by.add(bestSeg.id); }
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
	// keep the DIRECTIONAL containment fraction: "this much of me runs inside that one",
	// which is what decides whether a companion may lend me its geometry
	for (const [s, mine] of hits) {
		s.nearFrac = new Map();
		for (const [o, c] of mine) s.nearFrac.set(o, c / Math.max(1, s.samples.length));
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
		// measured a few samples in, past the worst of the centroid blend, or the
		// direction would read as the sideways kink rather than how the line leaves
		const pts = s.drawPts || s.pts;
		const span = Math.min(4, pts.length - 1);
		if (s.partA === partId) { a = pts[0]; b = pts[span]; }
		else if (s.partB === partId) { a = pts[pts.length - 1]; b = pts[pts.length - 1 - span]; }
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

/* ----------------------------------------------------------------------------
 * 5d. EXPRESS / LOCAL STOP MARKS (feature 1) — the NYC convention
 * --------------------------------------------------------------------------
 * On an NYC map a station where EVERY service of the lines serving it stops is a
 * solid dot; a station some service runs past is a smaller OPEN ring ("local only").
 *
 * The test is per (part, line). A route counts against a part only when its corridor
 * actually RUNS THROUGH that part — an express whose own long segment was suppressed
 * by the local chain (section 5c) runs over that chain, and the chain is exactly the
 * list of parts it passes; a route on a different branch never comes near and is
 * therefore irrelevant. So:
 *
 *   part P is full-service  <=>  for every line L touching P,
 *                                every route of L whose covered segments touch P
 *                                also stops at P.
 * ------------------------------------------------------------------------- */

function computeStopMarks() {
	/* 1. the segments incident to each part (drawn AND suppressed — the suppressed
	      express is what we resolve THROUGH, never what we test against) */
	const segsAtPart = new Map();
	for (const seg of state.segByPair.values()) {
		for (const p of [seg.partA, seg.partB]) {
			let s = segsAtPart.get(p);
			if (!s) { s = new Set(); segsAtPart.set(p, s); }
			s.add(seg.id);
		}
	}

	/* 2. per route: which corridor segments it runs over, and which parts it stops at */
	const byLine = new Map();          // hex -> [{covers:Set, stops:Set}]
	const stopsAt = new Map();         // partId -> Set(hex) of lines that CALL there
	for (const rt of visibleRoutes()) {
		const covers = new Set(), stops = new Set();
		const plats = rt.platforms || [];
		for (const pid of plats) {
			const part = partOfPlatform(pid);
			if (!part) continue;
			stops.add(part);
			let hexes = stopsAt.get(part);
			if (!hexes) { hexes = new Set(); stopsAt.set(part, hexes); }
			hexes.add(rt.hex);
		}
		for (let i = 0; i < plats.length - 1; i++) {
			const lg = state.legs.get(rt.id + "|" + i);
			if (!lg) continue;
			const a = partOfPlatform(lg.from), b = partOfPlatform(lg.to);
			if (!a || !b || a === b) continue;
			const seg = state.segByPair.get(rt.hex + "|" + pairKeyOf(a, b));
			if (!seg) continue;
			if (seg.suppressed && seg.coveredBy.length) for (const id of seg.coveredBy) covers.add(id);
			else covers.add(seg.id);
		}
		if (!byLine.has(rt.hex)) byLine.set(rt.hex, []);
		byLine.get(rt.hex).push({ covers, stops });
	}

	/* 3. the verdict per part */
	const marks = new Map();
	for (const [partId, hexes] of stopsAt) {
		const incident = segsAtPart.get(partId);
		let full = true;
		for (const hex of hexes) {
			for (const svc of byLine.get(hex) || []) {
				if (svc.stops.has(partId)) continue;                       // it calls here
				if (!incident) continue;
				let through = false;
				for (const id of svc.covers) if (incident.has(id)) { through = true; break; }
				if (through) { full = false; break; }                      // runs past: local only
			}
			if (!full) break;
		}
		marks.set(partId, full);
	}
	return marks;
}

/** Station part glyphs: dot, or capsule when the part serves two or more ribbon colours. */
function buildGlyphs() {
	state.stopMarks = computeStopMarks();
	const shown = new Set(visibleRoutes().map((r) => r.id));
	const known = new Set(candidateRoutes().map((r) => r.id));
	state.glyphs = [];
	// how many drawn segments of each colour touch each part: exactly one = the line
	// ends there (its terminal bullet)
	const degree = new Map();
	for (const sg of state.ribbons) {
		for (const pid of [sg.partA, sg.partB]) {
			const k = pid + "|" + sg.hex;
			degree.set(k, (degree.get(k) || 0) + 1);
		}
	}
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
			const terminals = new Set();
			for (const hex of colors) if (degree.get(part.id + "|" + hex) === 1) terminals.add(hex);
			state.glyphs.push({
				stationId: st.id, partId: part.id,
				x: part.x, z: part.z,
				colors: [...colors],
				terminals,
				capsule: colors.size >= 2,
				// the capsule spans the bundle, so it must follow the SCHEMATIC line's
				// direction here, not the raw platform vectors
				dir: segmentDirAtPart(part.id) || (n && dl > 0.05 ? [dx / dl, dz / dl] : [1, 0]),
				accessible: accessible || (!!st.accessible && part.id === st.mainPartId),
				// cached on the glyph: the drawn ring is picked from this every frame
				fullService: state.stopMarks.get(part.id) !== false,
				main: part.id === st.mainPartId,
				weight: (part.platforms.length || st.platformIds.length || 1),
				label: st.display,
				sub: part.sub,
			});
		}
	}
}

/* ----------------------------------------------------------------------------
 * 5e. CROSS-STREET TRANSFERS (feature 2) — one source, two consumers
 * --------------------------------------------------------------------------
 * Two different stations close enough to walk between are a real interchange even when
 * MTR knows nothing about it — the 14 St / 6 Av case. Each station keeps only its
 * STREET_TRANSFER_NEIGHBOURS nearest neighbours inside STREET_TRANSFER_RADIUS, measured
 * between the closest platform of each, so a dense downtown adds a handful of links
 * rather than a clique; a link counts for BOTH stations whenever EITHER side picked it.
 *
 * Two stations that are ADJACENT STOPS on some service are deliberately excluded. A
 * street transfer exists to reach a line you cannot otherwise reach; between consecutive
 * stops the service itself IS the connection, and on a network whose stops sit ~100
 * blocks apart an unfiltered radius turns every trunk into a footpath and the planner
 * starts telling riders to walk the line instead of riding it.
 *
 * The result is computed ONCE per geometry rebuild and shared: buildGraph turns it into
 * walk edges, buildStreetLinks turns it into the dotted connectors on the map. They can
 * therefore never disagree about which stations are walkable.
 * ------------------------------------------------------------------------- */
function streetTransferPairs() {
	if (state.streetPairs) return state.streetPairs;
	const byStation = new Map();
	for (const pl of state.platforms.values()) {
		if (!pl.stationId) continue;
		if (!byStation.has(pl.stationId)) byStation.set(pl.stationId, []);
		byStation.get(pl.stationId).push(pl);
	}
	const rideAdjacent = new Set();
	for (const rt of state.routes.values()) {
		if (rt.hidden && !state.prefs.showHidden) continue;
		const plats = rt.platforms || [];
		for (let i = 0; i < plats.length - 1; i++) {
			if (!state.legs.get(rt.id + "|" + i)) continue;
			const a = state.platforms.get(plats[i]), b = state.platforms.get(plats[i + 1]);
			if (!a || !b || a.stationId === b.stationId) continue;
			rideAdjacent.add(a.stationId + ">" + b.stationId);
			rideAdjacent.add(b.stationId + ">" + a.stationId);
		}
	}
	const closestPair = (aList, bList) => {
		let best = null;
		for (const a of aList) for (const b of bList) {
			const m = dist(a.xz, b.xz);
			if (!best || m < best.meters) {
				best = { a: a.id, b: b.id, meters: m, stationA: a.stationId, stationB: b.stationId };
			}
		}
		return best;
	};
	const ids = [...byStation.keys()];
	const out = [], seen = new Set();
	for (const sa of ids) {
		const cands = [];
		for (const sb of ids) {
			if (sa === sb || rideAdjacent.has(sa + ">" + sb)) continue;
			const pair = closestPair(byStation.get(sa) || [], byStation.get(sb) || []);
			if (pair && pair.meters <= STREET_TRANSFER_RADIUS) cands.push(pair);
		}
		cands.sort((x, y) => x.meters - y.meters);
		for (const pair of cands.slice(0, STREET_TRANSFER_NEIGHBOURS)) {
			const key = pair.a < pair.b ? pair.a + ">" + pair.b : pair.b + ">" + pair.a;
			if (seen.has(key)) continue;
			seen.add(key);
			out.push(pair);
		}
	}
	state.streetPairs = out;
	return out;
}

/**
 * The drawable form: a dotted connector between the two stations' NEAREST part
 * centroids, so it starts and ends exactly where the two glyphs are. A link whose
 * station part is not drawn (a Layers filter took its only mode away) is dropped rather
 * than left dangling in space.
 */
function buildStreetLinks() {
	state.streetLinks = [];
	const drawnParts = new Set(state.glyphs.map((g) => g.partId));
	for (const pair of streetTransferPairs()) {
		const pa = partOfPlatform(pair.a), pb = partOfPlatform(pair.b);
		if (pa && pb && (!drawnParts.has(pa) || !drawnParts.has(pb))) continue;
		const pla = state.platforms.get(pair.a), plb = state.platforms.get(pair.b);
		const a = (pa && partCentroid(pa)) || (pla && pla.xz);
		const b = (pb && partCentroid(pb)) || (plb && plb.xz);
		if (!a || !b) continue;
		state.streetLinks.push({
			ax: a[0], az: a[1], bx: b[0], bz: b[1],
			dist: pair.meters, partA: pa, partB: pb,
			stationA: pair.stationA, stationB: pair.stationB,
		});
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
	state.chipHits = [];

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

	// 2. basemap, UNDER everything else, over the paper fill (which stays visible
	//    wherever the scan has no tile). Satellite = the colour tiles; schematic = the
	//    same scan's class mask styled as water / woodland / snow. With satellite
	//    SELECTED but nothing scanned the styled mask is drawn instead — a blank page
	//    would be strictly less map than the rider had a moment ago.
	if (satEnabled() && satHasTiles()) drawSatellite(g, vp);
	else drawMaskMap(g, vp);

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

	// 4. walk connectors between split-station parts, then the cross-STATION street
	//    transfers (they mean the same thing to a rider, so they look the same)
	drawWalks(g, vp, sel ? PALETTE.dim : 1);
	drawStreetLinks(g, vp, sel ? PALETTE.dim : 1);

	// 5. station glyphs
	drawGlyphs(g, vp, sel);

	// journey overlay: glow + full-strength ribbons, walks, glyphs, origin/destination
	if (sel) drawJourneyOverlay(g, vp);

	// 6. labels last so nothing paints over them
	drawLabels(g, vp, sel);
}

/**
 * Aerial imagery under the network (feature 5).
 *
 * Each tile's world extent becomes a screen rect through the same worldToScreen the rest
 * of the map uses, so imagery and lines can never drift apart. Smoothing is OFF — at high
 * zoom a Minecraft top-down scan should read as crisp blocks, not as mush — and the whole
 * layer is drawn at PALETTE.satAlpha over the paper fill, which is what keeps white type
 * and saturated ribbons readable on top (0.85 on paper, 0.6 on the near-black ground).
 */
function drawSatellite(g, vp) {
	const meta = state.satmeta;
	if (!meta || !meta.tiles.length) return;
	const span = satTileSpan(meta);
	g.save();
	g.imageSmoothingEnabled = false;
	g.globalAlpha = satAlpha();          // theme alpha x the rider's brightness slider
	for (const t of meta.tiles) {
		const tx = t[0], tz = t[1];
		const [x0, z0] = satWorldOf(meta, tx, tz, 0, 0);
		const x1 = x0 + span, z1 = z0 + span;
		if (x1 < vp.l || x0 > vp.r || z1 < vp.t || z0 > vp.b) continue;      // viewport cull
		const img = satTileImage(tx, tz);
		if (!img) continue;
		const a = worldToScreen(x0, z0), b = worldToScreen(x1, z1);
		// round outward by a hair so neighbouring tiles never show a sub-pixel seam
		const px = Math.floor(a[0]), py = Math.floor(a[1]);
		g.drawImage(img, px, py, Math.ceil(b[0]) - px, Math.ceil(b[1]) - py);
	}
	g.restore();
}

/**
 * The schematic basemap: water, woodland and snow from the scan's class mask, styled
 * in the theme's flat fills. Smoothing stays ON here (unlike the satellite) so a
 * 2-block mask reads as soft shorelines at every zoom rather than as a staircase.
 */
function drawMaskMap(g, vp) {
	const meta = state.satmeta;
	if (!meta || !meta.available || !meta.mask || !meta.tiles.length) return;
	const span = satTileSpan(meta);
	g.save();
	g.imageSmoothingEnabled = true;
	for (const t of meta.tiles) {
		const tx = t[0], tz = t[1];
		const [x0, z0] = satWorldOf(meta, tx, tz, 0, 0);
		const x1 = x0 + span, z1 = z0 + span;
		if (x1 < vp.l || x0 > vp.r || z1 < vp.t || z0 > vp.b) continue;
		const cv = maskTileCanvas(tx, tz);
		if (!cv) continue;
		const a = worldToScreen(x0, z0), b = worldToScreen(x1, z1);
		const px = Math.floor(a[0]), py = Math.floor(a[1]);
		g.drawImage(cv, px, py, Math.ceil(b[0]) - px, Math.ceil(b[1]) - py);
	}
	g.restore();
}

function drawRibbons(g, vp, skipKeys, alpha) {
	const w = ribbonWidth();
	const gap = Math.max(0.6, w * 0.16);
	g.lineCap = "round";
	g.lineJoin = "round";
	for (const rb of state.ribbons) {
		if (skipKeys && skipKeys.has(rb.id)) continue;
		const bb = rb.drawBbox || rb.bbox;
		if (bb[2] < vp.l || bb[0] > vp.r || bb[3] < vp.t || bb[1] > vp.b) continue;
		const pts = toScreenPath(rb.drawPts || rb.pts);
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

/**
 * Cross-station street transfers (feature 2). Same dotted line + walk chip as the
 * split-station connectors — to a rider they are the same instruction — but they only
 * appear from mid-zoom up: at network scale a hundred of them would be noise, and the
 * overview is exactly where they matter least. Under a selection they dim with the rest
 * of the network; the one the journey actually walks is repainted at full strength by
 * the journey overlay.
 */
function drawStreetLinks(g, vp, alpha) {
	if (!state.streetLinks.length || state.view.scale < STREET_LINK_SCALE) return;
	const s = uiScale();
	g.globalAlpha = alpha;
	for (const w of state.streetLinks) {
		if (Math.max(w.ax, w.bx) < vp.l || Math.min(w.ax, w.bx) > vp.r) continue;
		if (Math.max(w.az, w.bz) < vp.t || Math.min(w.az, w.bz) > vp.b) continue;
		const a = worldToScreen(w.ax, w.az), b = worldToScreen(w.bx, w.bz);
		g.strokeStyle = PALETTE.ink2;
		g.lineWidth = 2.4 * s;
		g.lineCap = "round";
		g.setLineDash([1.5, 7]);
		g.beginPath(); g.moveTo(a[0], a[1]); g.lineTo(b[0], b[1]); g.stroke();
		g.setLineDash([]);
		if (state.view.scale > STREET_LINK_CHIP_SCALE) {
			drawWalkChip(g, (a[0] + b[0]) / 2, (a[1] + b[1]) / 2, fmtMeters(w.dist));
		}
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
	// EXPRESS/LOCAL (feature 1): a stop some service of its own line runs past is drawn
	// smaller, as a thinner ring with NO fill — the line shows through it, which is
	// exactly the NYC "local only" mark. Everything else keeps the solid white glyph.
	const open = gl.fullService === false;
	const r = (gl.main ? 6 : 5.2) * s * (gl.weight > 3 ? 1.12 : 1) * (open ? 0.76 : 1);
	g.fillStyle = PALETTE.glyphFill;
	g.strokeStyle = open ? PALETTE.openStroke : PALETTE.glyphStroke;
	g.lineWidth = (gl.main ? 3 : 2.5) * s * (open ? 0.68 : 1);
	g.lineJoin = "round";
	const paint = () => { if (!open) g.fill(); g.stroke(); };
	if (gl.capsule && gl.colors.length >= 3) {
		// Big interchange: a large plain circle (NYC-map style). A capsule stretched
		// across 3+ bundle slots grew enormous and diagonal at hub stations and
		// buried its own label — the play-test "tons of lines just break" shot.
		g.beginPath();
		g.arc(sx, sy, r * 1.45, 0, Math.PI * 2);
		paint();
	} else if (gl.capsule) {
		// elongate along the local track direction so the capsule spans the bundle,
		// but never further than the bundle can actually be wide
		const span = Math.min(Math.max(w * (gl.colors.length - 1) * 1.35, r * 1.6), r * 2.6);
		g.save();
		g.translate(sx, sy);
		// The capsule is authored with its long axis along local +Y, and rotate(t) maps
		// +Y to the PERPENDICULAR of dir — across the bundle, covering both parallel
		// ribbons. (It used to add PI/2, which laid the capsule ALONG the line.)
		g.rotate(Math.atan2(gl.dir[1], gl.dir[0]));
		g.beginPath();
		g.roundRect(-r, -(r + span / 2), r * 2, r * 2 + span, r);
		paint();
		g.restore();
	} else {
		g.beginPath();
		g.arc(sx, sy, r, 0, Math.PI * 2);
		paint();
	}
	// NOTE: the accessibility badge is drawn by drawLabels, inline after the
	// station name — anchoring it to the dot while the label floats produced
	// misaligned orphan badges (play-test feedback).
}

/**
 * One service bullet: a disc in the line colour with the service letter/number in
 * white, or — for an express service written "4*" — the NYC diamond.
 */
function drawBullet(g, x, y, R, hex, label) {
	const express = bulletIsExpress(label);
	const text = bulletText(label).slice(0, 2);
	g.fillStyle = hex;
	g.beginPath();
	if (express) {
		const d = R * 1.25;
		g.moveTo(x, y - d); g.lineTo(x + d, y); g.lineTo(x, y + d); g.lineTo(x - d, y);
		g.closePath();
	} else {
		g.arc(x, y, R, 0, Math.PI * 2);
	}
	g.fill();
	g.fillStyle = "#fff";                      // bullets are always white-on-colour
	g.font = "700 " + (R * (text.length > 1 ? 1.05 : 1.3)).toFixed(1) + "px " + FONT;
	g.textAlign = "center";
	g.textBaseline = "middle";
	g.fillText(text, x, y + R * 0.05);
	g.textBaseline = "alphabetic";
	g.textAlign = "left";
}

/**
 * The bullets that follow one station's name: every service of every line calling
 * there, in colour order. At overview zoom only interchanges and line ends carry
 * them (the MTA convention — a plain local stop's colour is the line it sits on);
 * zoomed in, every stop lists its services. A line that ENDS here gets a bigger
 * bullet, the terminal marker.
 */
function labelBullets(gl) {
	if (!gl.colors || !gl.colors.length) return [];
	const zoomed = state.view.scale >= BULLET_ZOOM;
	const interchange = gl.colors.length >= 2;
	const colorInt = (hex) => { const l = state.lines.get(hex); return l ? l.colorInt : 0; };
	const out = [];
	for (const hex of gl.colors.slice().sort((a, b) => colorInt(a) - colorInt(b) || (a < b ? -1 : 1))) {
		const terminal = !!(gl.terminals && gl.terminals.has(hex));
		if (!zoomed && !interchange && !terminal) continue;
		for (const label of lineLabels(hex)) out.push({ hex, label, terminal });
	}
	return out.slice(0, MAX_LABEL_BULLETS);
}

/* ---- labels: greedy decluttering with per-label alignment candidates ---- */

const FONT = '"Helvetica Neue", Helvetica, Arial, sans-serif';

/**
 * While a journey is selected ONLY its own stations keep their name (and sub-label);
 * every other station stays as a dim dot. Thomas's play-test: a highlighted journey
 * across a busy network was unreadable under the full label set.
 */
function labelVisibleFor(gl, sel) {
	// "Hide station names" keeps the dots and drops the names — except for the stations
	// the rider is actually looking at: the selected journey or line, and the journey
	// being tracked, which must stay legible whatever the layer says.
	if (state.prefs.hideLabels) {
		if (sel && sel.partIds.has(gl.partId)) return true;
		const tracked = trackedPartIds();
		return !!(tracked && tracked.has(gl.partId));
	}
	return !sel || sel.partIds.has(gl.partId);
}

function drawLabels(g, vp, sel) {
	const s = uiScale();
	const placed = labelObstacles.slice(); // chips + pins claimed their space first
	// Label hit boxes are kept in WORLD units: the static layer can be blitted at a
	// slightly different view for up to 100 ms, and a world box survives that (and any
	// pan) where a screen box would drift.
	state.labelHits = [];
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
		const size = (big ? 15 : gl.main ? 14 : 12.5) * s * labelScale();
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
		// Service bullets ride inline after the name (and badge), so they can never
		// detach from a decluttered label and their width counts toward collision.
		const bullets = labelBullets(gl);
		const R = size * 0.44;
		const pitch = R * 2.2;
		const bulletW = (b) => b.terminal ? R * 2.7 : pitch;
		const bulletsW = bullets.length ? 3 * s + bullets.reduce((w, b) => w + bulletW(b), 0) : 0;
		const fullW = tw + (withBadge ? bw + 4 * s : 0) + bulletsW;

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
		const wA = screenToWorld(chosen.box[0], chosen.box[1]);
		const wB = screenToWorld(chosen.box[2], chosen.box[3]);
		state.labelHits.push({
			stationId: gl.stationId, partId: gl.partId,
			box: [Math.min(wA[0], wB[0]), Math.min(wA[1], wB[1]), Math.max(wA[0], wB[0]), Math.max(wA[1], wB[1])],
		});

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
		if (bullets.length) {
			let bx = chosen.x0 + tw + (withBadge ? bw + 4 * s : 0) + 3 * s;
			const by = chosen.c.y - size * 0.36;
			for (const b of bullets) {
				const w = bulletW(b);
				const cx = bx + w / 2;
				drawBullet(g, cx, by, b.terminal ? R * 1.3 : R, b.hex, b.label);
				// each bullet is a line-view target (feature 4), kept in WORLD units like
				// the label boxes so a stale blit cannot move the hit box
				const wa = screenToWorld(cx - w / 2, by - R * 1.4), wb = screenToWorld(cx + w / 2, by + R * 1.4);
				state.chipHits.push({
					hex: b.hex,
					box: [Math.min(wa[0], wb[0]), Math.min(wa[1], wb[1]), Math.max(wa[0], wb[0]), Math.max(wa[1], wb[1])],
				});
				bx += w;
			}
			g.font = (gl.main ? "600 " : "500 ") + size.toFixed(1) + "px " + FONT;
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
			const pts = toScreenPath(rb.drawPts || rb.pts);
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

	followCamera();          // recentre BEFORE the pucks are drawn at this view
	trackCamera();           // …and while tracking a journey, ride the rider's own dot
	drawPlayers();           // under the trains: a puck a rider tapped must stay on top
	drawVehicles();
	positionTrainCard();     // the card rides its puck, so it moves every frame

	if (!DEMO && state.lastEventAt && now() - state.lastEventAt > 5000) setStatus("connecting");
	requestAnimationFrame(frame);
}

/** Interpolation instant: one measured stream interval behind the wall clock. */
function interpTime() {
	const delay = state.emaInterval
		? clamp(state.emaInterval * 1.25 + 120, 250, 2000)
		: state.updateMillis * 1.5;
	return now() - delay;
}

/**
 * LIVE PLAYERS (feature 3).
 *
 * "Me" is the Google-Maps blue dot: a soft accuracy halo, a white ring and a solid
 * blue core that pulses. Everybody else is a small neutral dot — this is a transit map,
 * not a player tracker — and their names only appear once the view is zoomed in enough
 * that a crowd cannot turn into a wall of text.
 *
 * Positions are interpolated between the feed's 4 Hz samples and then smoothed
 * exponentially, exactly like the train pucks, so walking reads as walking.
 */
function drawPlayers() {
	if (!state.prefs.showPlayers || !state.players.size) return;
	const s = uiScale();
	const renderTime = interpTime();
	const frameNow = now();
	const smoothing = 1 - Math.exp(-Math.min(0.1, (frameNow - (state.lastFrameAt || frameNow - 16)) / 1000) / 0.12);
	const labels = [];
	for (const [name, rec] of state.players) {
		// "Hide other players": the self dot is the rider's own position and always stays
		if (state.prefs.hideOtherPlayers && name !== state.selfPlayer) { rec.screen = null; continue; }
		const p = playerPos(rec, renderTime);
		if (!p) { rec.screen = null; continue; }
		if (!rec.disp || Math.hypot(p.x - rec.disp.x, p.z - rec.disp.z) > 48) rec.disp = { x: p.x, z: p.z };
		else {
			rec.disp.x += (p.x - rec.disp.x) * smoothing;
			rec.disp.z += (p.z - rec.disp.z) * smoothing;
		}
		const [sx, sy] = worldToScreen(rec.disp.x, rec.disp.z);
		rec.screen = [sx, sy];
		if (sx < -40 || sy < -40 || sx > canvas.clientWidth + 40 || sy > canvas.clientHeight + 40) continue;
		const me = name === state.selfPlayer;
		if (me) {
			const r = 7.5 * s;
			ctx.fillStyle = PALETTE.playerHalo;
			ctx.beginPath(); ctx.arc(sx, sy, r * 3.4, 0, Math.PI * 2); ctx.fill();
			ctx.globalAlpha = 0.55 + 0.45 * Math.sin(frameNow / 520);
			ctx.beginPath(); ctx.arc(sx, sy, r * 2.1, 0, Math.PI * 2); ctx.fill();
			ctx.globalAlpha = 1;
			ctx.beginPath(); ctx.arc(sx, sy, r, 0, Math.PI * 2);
			ctx.fillStyle = PALETTE.player; ctx.fill();
			ctx.lineWidth = 2.6 * s; ctx.strokeStyle = "#ffffff"; ctx.stroke();
		} else {
			ctx.beginPath(); ctx.arc(sx, sy, 4.2 * s, 0, Math.PI * 2);
			ctx.fillStyle = PALETTE.playerOther; ctx.fill();
			ctx.lineWidth = 1.6 * s; ctx.strokeStyle = PALETTE.chip; ctx.stroke();
			if (state.view.scale > PLAYER_LABEL_SCALE) labels.push({ sx, sy, name });
		}
	}
	if (!labels.length) return;
	ctx.font = "600 " + (11 * s).toFixed(1) + "px " + FONT;
	ctx.textAlign = "center";
	ctx.lineJoin = "round";
	ctx.miterLimit = 2;
	ctx.lineWidth = 3.5 * s;
	for (const l of labels) {
		ctx.strokeStyle = PALETTE.paper;
		ctx.strokeText(l.name, l.sx, l.sy - 8 * s);
		ctx.fillStyle = PALETTE.ink2;
		ctx.fillText(l.name, l.sx, l.sy - 8 * s);
	}
	ctx.textAlign = "left";
}

function drawVehicles() {
	// "Hide live trains" is a rendering layer only: the planner's live departures and the
	// station panel still read state.vehicles, they just no longer appear on the map.
	if (state.prefs.hideTrains) {
		for (const rec of state.vehicles.values()) rec.screen = null;
		state.lastFrameAt = now();
		return;
	}
	const s = uiScale();
	const sel = state.selection;
	// interpolation delay from the measured stream cadence (app.js's rule)
	const renderTime = interpTime();
	const frameNow = now();
	const dtSec = state.lastFrameAt ? Math.min(0.1, (frameNow - state.lastFrameAt) / 1000) : 0.016;
	state.lastFrameAt = frameNow;
	const smoothing = 1 - Math.exp(-dtSec / 0.12);

	const callouts = [];
	for (const [id, rec] of state.vehicles) {
		if (!modeVisible(rec.mode || "train")) { rec.screen = null; continue; }
		// while a journey is selected the map shows ONLY that journey's lines running
		// (section 10a): a dimmed puck on an unrelated line still reads as traffic
		if (!vehiclePassesSelection(rec, sel)) { rec.screen = null; continue; }
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
		const ride = snapToLine(vehicleLineHex(rec), rec.disp.x, rec.disp.z, VEHICLE_SNAP);
		rec.screen = ride ? worldToScreen(ride[0], ride[1]) : worldToScreen(rec.disp.x, rec.disp.z);
		const [sx, sy] = rec.screen;
		if (sx < -60 || sy < -60 || sx > canvas.clientWidth + 60 || sy > canvas.clientHeight + 60) continue;

		const color = vehicleLineHex(rec) || PALETTE.ink2;
		const followed = !!(state.follow && state.follow.vehicleId === id);
		const carded = state.trainCard === id;

		// Zoomed out, a train is a small dot in its line colour riding the line — fifty
		// routes' worth of icon pucks buried every station of the overview. The puck
		// with the mode icon comes back once the lines are far enough apart to read.
		const dots = state.view.scale < TRAIN_ICON_ZOOM;
		const r = (dots ? 4 : 11) * s;
		if (dots) {
			ctx.beginPath(); ctx.arc(sx, sy, r, 0, Math.PI * 2);
			ctx.fillStyle = color; ctx.fill();
			ctx.lineWidth = 1.5 * s;
			ctx.strokeStyle = PALETTE.chip;
			ctx.stroke();
		} else {
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
		}
		// the puck the card is anchored to (or the camera is riding) wears a halo ring,
		// so a rider never loses which train they picked
		if (followed || carded) {
			ctx.lineWidth = 2 * s;
			ctx.strokeStyle = color;
			ctx.globalAlpha = followed ? 0.55 + 0.35 * Math.sin(now() / 320) : 0.5;
			ctx.beginPath(); ctx.arc(sx, sy, r + 5 * s, 0, Math.PI * 2); ctx.stroke();
		}
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
 * The LINE colour of a streamed route object. The stream carries the route's raw colour,
 * which may be one of two drifted shades the map has merged into one line, so the loaded
 * route record wins whenever we have it.
 */
function lineHexOfRoute(r) {
	if (!r) return null;
	const known = r.id ? state.routes.get(r.id) : null;
	return known ? known.hex : colorHex(r.color);
}
function vehicleLineHex(rec) { return rec && rec.route ? lineHexOfRoute(rec.route) : null; }

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
		const bb = s.drawBbox || s.bbox;
		if (x < bb[0] - maxDist || x > bb[2] + maxDist || z < bb[1] - maxDist || z > bb[3] + maxDist) continue;
		const r = nearestOnPolyline([x, z], s.drawPts || s.pts);
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

	// players ride BOTH frame kinds (full and delta) as the whole current set for this
	// dimension, so an absent name means "gone", not "unchanged"
	if (Array.isArray(f.players)) applyPlayers(f.players, f.serverTime);
}

/* ---- live player GPS (feature 3) ---- */

/**
 * Which streamed name is "me"? `?player=` (and the settings "I am" row) are matched
 * CASE-INSENSITIVELY against the names the feed actually carries, and the FEED's spelling
 * is what comes back — Minecraft usernames are case-preserving but riders type them
 * however they like, and the URL is written by an in-game button we do not control.
 *
 * Pure: the harness drives it directly.
 */
function matchSelfPlayer(names, wanted) {
	const want = String(wanted == null ? "" : wanted).trim().toLowerCase();
	if (!want) return "";
	for (const n of names || []) {
		if (String(n == null ? "" : n).trim().toLowerCase() === want) return n;
	}
	return "";
}

/** Merge one frame's player list; drop anybody who has stopped arriving. */
function applyPlayers(list, serverTime) {
	const t = Number.isFinite(serverTime) ? serverTime : now();
	const seen = new Set();
	for (const p of list) {
		if (!p || !p.name) continue;
		seen.add(p.name);
		let rec = state.players.get(p.name);
		if (!rec) { rec = { name: p.name, samples: [], disp: null, y: p.y || 0, screen: null }; state.players.set(p.name, rec); }
		rec.y = Number.isFinite(p.y) ? p.y : rec.y;
		rec.lastSeen = t;
		rec.samples.push({ t, x: p.x, y: p.y, z: p.z });
		if (rec.samples.length > PLAYER_SAMPLES) rec.samples.shift();
	}
	for (const [name, rec] of state.players) {
		if (!seen.has(name) && t - (rec.lastSeen || 0) > PLAYER_STALE_MS) state.players.delete(name);
	}
	resolveSelfPlayer();
	// the settings "I am" list only needs rebuilding when the roster itself changes
	const sig = [...state.players.keys()].sort().join("");
	if (sig !== playerRosterKey) { playerRosterKey = sig; syncSelfUi(); }
}
let playerRosterKey = "";

/**
 * Re-resolve "me" against the current name set. The URL parameter WINS over the stored
 * preference and rewrites it, so opening the map from the in-game button re-points the
 * dot at whoever pressed it; the settings row is the fallback for a bare URL.
 */
function resolveSelfPlayer() {
	const names = [...state.players.keys()];
	// the URL wins at load; an explicit pick in the settings row takes it back
	const wanted = (state.selfManual ? state.prefs.selfPlayer : (PLAYER_PARAM || state.prefs.selfPlayer)) || "";
	const found = matchSelfPlayer(names, wanted);
	const changed = found !== state.selfPlayer;
	state.selfPlayer = found;
	if (found && PLAYER_PARAM && state.prefs.selfPlayer !== found) {
		state.prefs.selfPlayer = found;
		savePrefs();
	}
	if (changed) { syncSelfUi(); renderOptions(); }
	return found;
}

/** The self player's live world position, or null. */
function selfPlayerPos() {
	const rec = state.selfPlayer ? state.players.get(state.selfPlayer) : null;
	if (!rec || !rec.samples.length) return null;
	const s = rec.samples[rec.samples.length - 1];
	return { x: s.x, y: Number.isFinite(s.y) ? s.y : rec.y || 0, z: s.z };
}

/** Same two-sample interpolation the vehicles use, without the rail curve. */
function playerPos(rec, renderTime) {
	const s = rec.samples;
	if (!s.length) return null;
	if (s.length === 1) return { x: s[0].x, z: s[0].z };
	const last = s[s.length - 1], prev = s[s.length - 2];
	if (renderTime > last.t) {
		const dt = Math.min(renderTime - last.t, 600);
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
	return { x: a.x + (b.x - a.x) * f, z: a.z + (b.z - a.z) * f };
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

/**
 * The rider drove the camera themselves. Both automatic cameras let go on exactly the
 * same gestures: a followed train (section 10a) and the tracked journey's self dot
 * (10d). Neither ever comes back on its own — the rider re-arms it.
 */
function cameraTakenBack() {
	stopFollow("input");
	releaseTrackCamera();
}

function initUi() {
	// every deliberate camera control also lets go of a followed train: the rider took
	// the wheel back (follow keeps the zoom it was handed, it never drives it)
	$("zIn").onclick = () => { cameraTakenBack(); zoomAt(canvas.clientWidth / 2, canvas.clientHeight / 2, 1.4); };
	$("zOut").onclick = () => { cameraTakenBack(); zoomAt(canvas.clientWidth / 2, canvas.clientHeight / 2, 1 / 1.4); };
	// LOCATE (feature 3): with a live self player this recentres on the rider; without
	// one it stays the fit-the-network button it has always been.
	$("zHome").onclick = () => { cameraTakenBack(); locateOrFit(); };
	syncSelfUi();

	let drag = null;
	let pressTimer = 0;
	const cancelPress = () => { if (pressTimer) { clearTimeout(pressTimer); pressTimer = 0; } };
	canvas.addEventListener("pointerdown", (e) => {
		drag = { x: e.clientX, y: e.clientY, moved: false };
		canvas.setPointerCapture(e.pointerId);
		canvas.classList.add("dragging");
		// LONG-PRESS drops a pin (the touch equivalent of the right-click below)
		const rect = canvas.getBoundingClientRect();
		const sx = e.clientX - rect.left, sy = e.clientY - rect.top;
		cancelPress();
		pressTimer = setTimeout(() => {
			pressTimer = 0;
			if (drag && !drag.moved) { drag.moved = true; dropMapPoint(pointTarget(), sx, sy); }
		}, 520);
	});
	canvas.addEventListener("pointermove", (e) => {
		const rect = canvas.getBoundingClientRect();
		if (!drag) { hoverAt(e.clientX - rect.left, e.clientY - rect.top); return; }
		const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
		if (Math.abs(dx) + Math.abs(dy) > 3) { drag.moved = true; cancelPress(); cameraTakenBack(); }   // 3 px click threshold
		state.view.x -= dx / state.view.scale;
		state.view.z -= dy / state.view.scale;
		drag.x = e.clientX; drag.y = e.clientY;
		$("mapTip").classList.add("hidden");
		invalidateStatic();
	});
	canvas.addEventListener("pointerup", (e) => {
		canvas.classList.remove("dragging");
		cancelPress();
		const rect = canvas.getBoundingClientRect();
		if (drag && !drag.moved) clickAt(e.clientX - rect.left, e.clientY - rect.top);
		drag = null;
	});
	// RIGHT-CLICK drops a pin straight onto the map, with no arming step
	canvas.addEventListener("contextmenu", (e) => {
		e.preventDefault();
		const rect = canvas.getBoundingClientRect();
		dropMapPoint(pointTarget(), e.clientX - rect.left, e.clientY - rect.top);
	});
	canvas.addEventListener("pointerleave", () => $("mapTip").classList.add("hidden"));
	canvas.addEventListener("wheel", (e) => {
		e.preventDefault();
		cameraTakenBack();
		const rect = canvas.getBoundingClientRect();
		zoomAt(e.clientX - rect.left, e.clientY - rect.top, Math.exp(-e.deltaY * 0.0015));
	}, { passive: false });

	document.addEventListener("keydown", (e) => {
		const tag = (e.target.tagName || "").toLowerCase();
		if (tag === "input" || tag === "select" || tag === "textarea") return;
		if (e.key === "Escape") escapePressed();
		else if (e.key.toLowerCase() === "f") { cameraTakenBack(); fitView(); }
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
	if (state.prefs.hideTrains) return null;      // hidden implies untouchable
	let best = null, bestD = (state.view.scale < TRAIN_ICON_ZOOM ? 9 : 16) * uiScale();
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
	// a line under the pointer is a click target too (feature 4)
	const rb = t || gl ? null : (chipAt(sx, sy) || ribbonAt(sx, sy));
	canvas.classList.toggle("pointing", !!(t || gl || rb));
	if (!t && !gl) {
		if (!rb) { tip.classList.add("hidden"); return; }
		const labels = lineLabels(rb.hex);
		const line = state.lines.get(rb.hex);
		const names = [];
		for (const id of (line && line.routeIds) || []) {
			const n = routeBase((state.routes.get(id) || {}).name);
			if (n && !names.includes(n)) names.push(n);
		}
		tip.innerHTML = labels.map((l) => `<span class="bullet sm" style="background:${rb.hex}">${esc(l)}</span>`).join("")
			+ `<span>${esc(names[0] || "Line")}</span>`;
		tip.classList.remove("hidden");
		tip.style.left = (sx + 16) + "px";
		tip.style.top = (sy - 12) + "px";
		return;
	}
	let html;
	if (t) {
		const r = t.rec.route;
		const color = (r && lineHexOfRoute(r)) || PALETTE.ink2;
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

/**
 * A station NAME is a click target too — the glyph is 11 px of dot and the label beside
 * it is what a rider actually aims at. drawLabels records the boxes it placed in world
 * units (they survive a stale blit); this walks them back to front.
 */
function labelAt(sx, sy) {
	const [wx, wz] = screenToWorld(sx, sy);
	for (let i = state.labelHits.length - 1; i >= 0; i--) {
		const h = state.labelHits[i];
		if (wx >= h.box[0] && wx <= h.box[2] && wz >= h.box[1] && wz <= h.box[3]) return h;
	}
	return null;
}

/** A service bullet after a station name (world boxes, recorded by drawLabels). */
function chipAt(sx, sy) {
	const [wx, wz] = screenToWorld(sx, sy);
	for (let i = state.chipHits.length - 1; i >= 0; i--) {
		const h = state.chipHits[i];
		if (wx >= h.box[0] && wx <= h.box[2] && wz >= h.box[1] && wz <= h.box[3]) return h;
	}
	return null;
}

/**
 * The drawn line under the pointer (feature 4). Tolerance is the ribbon's own stroke plus
 * however far its bundle is offset from the shared centreline, so clicking the visible
 * stroke of an offset colour finds THAT colour rather than the corridor's centre.
 */
function ribbonAt(sx, sy) {
	if (!state.ribbons.length) return null;
	const [wx, wz] = screenToWorld(sx, sy);
	const w = ribbonWidth();
	const gap = Math.max(0.6, w * 0.16);
	let best = null, bestD = Infinity;
	for (const rb of state.ribbons) {
		const spread = Math.max(0, rb.count - 1) / 2 * (w + gap);
		const tol = (w / 2 + spread + 4) / Math.max(1e-6, state.view.scale);
		const bb = rb.drawBbox || rb.bbox;
		if (wx < bb[0] - tol || wx > bb[2] + tol || wz < bb[1] - tol || wz > bb[3] + tol) continue;
		const d = pointPolylineDist([wx, wz], rb.drawPts || rb.pts);
		if (d <= tol && d < bestD) { bestD = d; best = rb; }
	}
	return best;
}

/**
 * CLICK PRIORITY, as one pure decision (the harness drives it directly):
 *   train -> station glyph -> station label -> line chip -> line ribbon -> map point / clear.
 * A line is deliberately BELOW every station target: a rider aiming at a stop on a line
 * must never get the line instead.
 */
const HIT_ORDER = ["train", "glyph", "label", "chip", "ribbon", "pick"];
function pickHit(hits) {
	for (const k of HIT_ORDER) if (hits && hits[k]) return k;
	return "clear";
}

/**
 * Click priority matches the hover tooltip: train, then station glyph, then station
 * label. A click on bare paper closes whatever is open and drops the journey selection.
 * Note a plain station click no longer FILLS the planner fields — the panel's
 * "Plan from here" / "Plan to here" buttons do that now (they call pickStation).
 */
function clickAt(sx, sy) {
	const hits = {
		train: trainAt(sx, sy),
		glyph: glyphAt(sx, sy) || labelAt(sx, sy),
		label: null,                                   // folded into `glyph` above
		chip: chipAt(sx, sy),
		ribbon: ribbonAt(sx, sy),
		pick: state.mapPick ? { which: state.mapPick } : null,
	};
	switch (pickHit(hits)) {
		case "train": openTrainCard(hits.train.id); return;
		case "glyph": openStationPanel(hits.glyph.stationId, hits.glyph.partId); return;
		case "chip": selectLine(hits.chip.hex); return;
		case "ribbon": selectLine(hits.ribbon.hex); return;
		case "pick": dropMapPoint(hits.pick.which, sx, sy); return;
		default:
			closeTrainCard();
			closeStationPanel();
			clearSelection();
	}
}

/* ---- map point picking (feature 2) ---- */

/** Arm "click the map": crosshair cursor + a hint pill saying what the click will do. */
function armMapPick(which) {
	state.mapPick = which === "to" ? "to" : "from";
	if (canvas.classList) canvas.classList.add("picking");
	const el = $("mapHint");
	if (el) {
		el.textContent = state.mapPick === "to"
			? "Click the map to set your destination — Esc to cancel"
			: "Click the map to set your start — Esc to cancel";
		if (el.classList) el.classList.remove("hidden");
	}
	return state.mapPick;
}

function disarmMapPick() {
	state.mapPick = null;
	if (canvas.classList) canvas.classList.remove("picking");
	const el = $("mapHint");
	if (el && el.classList) el.classList.add("hidden");
}

/** Which field a bare right-click / long-press fills: the armed one, else the empty one. */
function pointTarget() {
	if (state.mapPick) return state.mapPick;
	return state.plan.from ? "to" : "from";
}

/** Put an arbitrary world point into the planner. `live` = it follows the player. */
function setPlanPoint(which, x, z, y, label, live) {
	const key = which === "to" ? "to" : "from";
	state.plan[key] = {
		stationId: null, partId: null, point: [x, z],
		y: Number.isFinite(y) ? y : undefined,
		label: label || (key === "to" ? "Dropped pin (dest)" : "Dropped pin"),
		live: !!live,
	};
	disarmMapPick();
	syncFields();
	replan();
	return state.plan[key];
}

function dropMapPoint(which, sx, sy) {
	const [wx, wz] = screenToWorld(sx, sy);
	return setPlanPoint(which, wx, wz, undefined, null, false);
}

/** "Plan from my location" — the live position, re-planned as the rider walks. */
function planFromMyLocation() {
	const p = selfPlayerPos();
	if (!p) return null;
	return setPlanPoint("from", p.x, p.z, p.y, "My location", true);
}

/** Clearing a field drops whatever it held (station or pin) and disarms the map. */
function clearPlanField(which) {
	const key = which === "to" ? "to" : "from";
	if (state.mapPick === key) disarmMapPick();
	if (!state.plan[key]) return;
	state.plan[key] = null;
	replan();
}

/** Esc unwinds the overlays one keystroke at a time, then the selection. */
function escapePressed() {
	let handled = false;
	// the pairing card is modal: while it is up Esc means "close it", nothing else
	if (state.nav.modal) { closeNavModal(); return; }
	// an armed map pick is the most transient thing on screen: it goes first
	if (state.mapPick) { disarmMapPick(); handled = true; }
	// the re-plan question is next: Esc means "keep what I am on"
	if (state.tracking && state.tracking.offer) { refuseTrackOffer(); return; }
	if (state.follow) { stopFollow("escape"); handled = true; }
	if (state.trainCard) { closeTrainCard(); handled = true; }
	if (state.stationPanel) { closeStationPanel(); handled = true; }
	// tracking is the last thing to give up: an open card is what the rider meant
	if (!handled && state.tracking) { stopTracking("escape"); return; }
	if (!handled) clearSelection();
}

/**
 * The locate button. With a live self player it parks the camera on the rider at the
 * current zoom; without one it falls back to fitView(), which is what the button has
 * always done. The tooltip follows, so it never promises something it cannot do.
 */
function locateOrFit() {
	const p = selfPlayerPos();
	if (!p) { fitView(); return false; }
	state.view.x = p.x;
	state.view.z = p.z;
	if (state.view.scale < 0.6) state.view.scale = 0.9;      // "where am I" wants street zoom
	invalidateStatic();
	return true;
}

/** Keep the locate tooltip, the "I am" select and the From dropdown honest. */
function syncSelfUi() {
	const btn = $("zHome");
	if (btn) btn.title = state.selfPlayer ? "Centre on " + state.selfPlayer : "Recenter";
	const sel = $("selfSel");
	if (sel) {
		const names = [...state.players.keys()].sort();
		const html = ['<option value="">Nobody</option>']
			.concat(names.map((n) => `<option value="${esc(n)}"${n === state.selfPlayer ? " selected" : ""}>${esc(n)}</option>`))
			.join("");
		if (sel.innerHTML !== html) sel.innerHTML = html;
		sel.value = state.selfPlayer || "";
	}
	const row = $("selfRow");
	if (row && row.classList) row.classList.toggle("hidden", state.players.size === 0);
}

/* ============================================================================
 * 10a. train card + follow mode
 * ==========================================================================
 * Clicking a puck opens a small floating card beside it (line bullet, destination,
 * consist length, next stop, speed) with a Follow button. Follow parks the camera on
 * the vehicle's interpolated position every frame at the rider's own zoom, and lets go
 * the moment the rider pans, zooms, presses Esc, taps the pill, selects a journey that
 * hides the line, or the vehicle stops arriving in the feed.
 */

/** The stop a vehicle is running to: `nPlat` resolved to a station, else the feed's own
 *  `route.nextStation` string, else nothing (never a guess). */
function vehicleNextStop(rec) {
	const d = (rec && rec.data) || {};
	const pl = d.nPlat ? state.platforms.get(d.nPlat) : null;
	if (pl) {
		const st = state.stations.get(pl.stationId);
		if (st) return { name: st.display, platform: pl.name || "", stationId: st.id, source: "platform" };
	}
	const named = rec && rec.route && rec.route.nextStation;
	if (named) return { name: firstLang(named), platform: "", stationId: null, source: "route" };
	return null;
}

/** Everything the card (and the follow pill) shows about one vehicle. */
function trainCardData(id) {
	const rec = state.vehicles.get(id);
	if (!rec) return null;
	const rt = rec.route || null;
	const cars = rec.consist && (rec.consist.cars || rec.consist.carLengths);
	const kmh = rec.data && Number.isFinite(rec.data.kmh) ? Math.round(rec.data.kmh) : null;
	return {
		id,
		hex: rt ? colorHex(rt.color) : PALETTE.ink2,
		label: rt ? routeServiceLabel({ number: rt.number, display: routeBase(rt.name), name: rt.name }) : "",
		routeName: rt ? (routeBase(rt.name) || "Train") : "Train",
		dest: rt ? firstLang(rt.dest || routeDest(rt.name)) : "",
		cars: Array.isArray(cars) ? cars.length : 0,
		kmh,
		doors: !!(rec.data && rec.data.doors),
		nextStop: vehicleNextStop(rec),
	};
}

/**
 * Where a floating card anchored to a point goes. Right of the point by default; it
 * flips to the left when the card would run off the right edge (and stays right when
 * neither side fits, then clamps), and its top is clamped into the viewport — so a card
 * is never clipped, whatever corner its train is in.
 */
function anchorFloatCard(sx, sy, w, h, vw, vh, gap) {
	const g = gap === undefined ? CARD_GAP : gap;
	const fitsRight = sx + g + w <= vw - 8;
	const fitsLeft = sx - g - w >= 8;
	const side = fitsRight || !fitsLeft ? "right" : "left";
	const left = clamp(side === "right" ? sx + g : sx - g - w, 8, Math.max(8, vw - w - 8));
	const top = clamp(sy - h / 2, 8, Math.max(8, vh - h - 8));
	return { left, top, side };
}

function openTrainCard(id) {
	state.trainCard = id;
	renderTrainCard();
}

function closeTrainCard() {
	if (!state.trainCard) return;
	state.trainCard = null;
	trainCardKey = "";
	const el = $("trainCard");
	if (el && el.classList) el.classList.add("hidden");
}

function trainCardHtml(d, following) {
	const bullet = d.label
		? `<span class="bullet sm" style="background:${d.hex}">${esc(d.label)}</span>`
		: `<span class="fc-swatch" style="background:${d.hex}"></span>`;
	const head = d.dest ? "→ " + d.dest : d.routeName;
	const facts = [];
	if (d.cars) facts.push(`${d.cars} car${d.cars === 1 ? "" : "s"}`);
	if (d.kmh !== null) facts.push(d.doors && d.kmh === 0 ? "doors open" : d.kmh + " km/h");
	return `
		<div class="fc-head">
			${bullet}
			<div class="fc-title">
				<div class="fc-dest">${esc(head)}</div>
				<div class="fc-line">${esc(d.routeName)}</div>
			</div>
			<button class="fc-close" type="button" title="Close" aria-label="Close">×</button>
		</div>
		${d.nextStop ? `<div class="fc-next"><span class="fc-key">Next stop</span><b>${esc(d.nextStop.name)}</b>${
			d.nextStop.platform ? `<span class="fc-plat">Plat ${esc(d.nextStop.platform)}</span>` : ""}</div>` : ""}
		${facts.length ? `<div class="fc-facts">${facts.map((f) => esc(f)).join(" · ")}</div>` : ""}
		<button class="fc-follow${following ? " on" : ""}" type="button">${following ? "Following — stop" : "Follow"}</button>`;
}

let trainCardKey = "";
function renderTrainCard() {
	const el = $("trainCard");
	if (!el) return;
	if (!state.trainCard) { trainCardKey = ""; if (el.classList) el.classList.add("hidden"); return; }
	const d = trainCardData(state.trainCard);
	if (!d) { closeTrainCard(); return; }
	const html = trainCardHtml(d, !!(state.follow && state.follow.vehicleId === d.id));
	// the live ticker re-renders this every second: only touch the DOM when something a
	// rider can see actually changed, so a hovered button never blinks out under them
	// (keyed by vehicle too — two trains of one service can render identical markup)
	const key = d.id + "|" + html;
	if (key === trainCardKey) { positionTrainCard(); return; }
	trainCardKey = key;
	el.innerHTML = html;
	if (el.classList) el.classList.remove("hidden");
	const close = el.querySelector ? el.querySelector(".fc-close") : null;
	if (close) close.onclick = (e) => { if (e && e.stopPropagation) e.stopPropagation(); closeTrainCard(); };
	const follow = el.querySelector ? el.querySelector(".fc-follow") : null;
	if (follow) {
		follow.onclick = (e) => {
			if (e && e.stopPropagation) e.stopPropagation();
			if (state.follow && state.follow.vehicleId === d.id) stopFollow("user");
			else startFollow(d.id);
		};
	}
	positionTrainCard();
}

function positionTrainCard() {
	const el = $("trainCard");
	if (!el || !state.trainCard || !el.style) return;
	const rec = state.vehicles.get(state.trainCard);
	if (!rec || !rec.screen) { if (el.classList) el.classList.add("hidden"); return; }
	if (el.classList) el.classList.remove("hidden");
	const w = el.offsetWidth || 232, h = el.offsetHeight || 132;
	const a = anchorFloatCard(rec.screen[0], rec.screen[1], w, h,
		canvas.clientWidth || 1280, canvas.clientHeight || 800);
	el.style.left = Math.round(a.left) + "px";
	el.style.top = Math.round(a.top) + "px";
	if (el.classList) { el.classList.toggle("flip", a.side === "left"); }
}

/* ---- follow state machine (pure: the harness drives it directly) ---- */

/**
 * @param follow  current follow state or null
 * @param ev      {type, id, at, present}
 *                start     begin following `id` at `at`
 *                tick      one animation frame; `present` = the vehicle is in the feed
 *                          AND currently drawable (mode layer on, not filtered out)
 *                input | escape | user | filtered | stop   let go
 * @returns the next follow state, or null when follow has ended.
 */
function followReduce(follow, ev) {
	const at = ev && Number.isFinite(ev.at) ? ev.at : 0;
	switch (ev && ev.type) {
		case "start":
			return { vehicleId: ev.id, since: at, lastSeenAt: at, fading: false };
		case "tick":
			if (!follow) return null;
			if (ev.present) return follow.fading ? { ...follow, lastSeenAt: at, fading: false } : { ...follow, lastSeenAt: at };
			// gone from the feed: the pill fades while we wait, then follow lets go
			if (at - follow.lastSeenAt > FOLLOW_LOST_MS) return null;
			return follow.fading ? follow : { ...follow, fading: true };
		case "input": case "escape": case "user": case "filtered": case "stop":
			return null;
		default:
			return follow;
	}
}

function startFollow(id) {
	if (!state.vehicles.has(id)) return;
	state.follow = followReduce(state.follow, { type: "start", id, at: now() });
	renderFollowPill();
	renderTrainCard();
}

function stopFollow(reason) {
	if (!state.follow) return;
	state.follow = followReduce(state.follow, { type: reason || "stop", at: now() });
	renderFollowPill();
	if (state.trainCard) renderTrainCard();
}

/** Is the followed vehicle currently drawable? (feed + layer + journey filter) */
function followTargetVisible() {
	const f = state.follow;
	if (!f) return false;
	const rec = state.vehicles.get(f.vehicleId);
	return !!(rec && rec.disp && !state.prefs.hideTrains && modeVisible(rec.mode || "train")
		&& vehiclePassesSelection(rec, state.selection));
}

/** One frame of follow: advance the state machine, then park the camera. */
function followCamera() {
	const f = state.follow;
	if (!f) return;
	const present = followTargetVisible();
	const next = followReduce(f, { type: "tick", at: now(), present });
	const changed = next !== f;
	state.follow = next;
	if (!next) { renderFollowPill(); if (state.trainCard) renderTrainCard(); return; }
	if (present) {
		const rec = state.vehicles.get(next.vehicleId);
		if (Math.abs(state.view.x - rec.disp.x) > 0.01 || Math.abs(state.view.z - rec.disp.z) > 0.01) {
			state.view.x = rec.disp.x;
			state.view.z = rec.disp.z;
			invalidateStatic();
		}
	}
	if (changed && next.fading !== f.fading) renderFollowPill();
}

function followPillText(d) {
	if (!d) return "Following this train · tap to stop";
	const who = (d.label ? d.label + " " : "") + (d.dest ? "→ " + d.dest : d.routeName);
	return "Following " + who.trim() + " · tap to stop";
}

let followPillKey = "";
function renderFollowPill() {
	const el = $("followPill");
	if (!el) return;
	const f = state.follow;
	if (!f) {
		followPillKey = "";
		if (el.classList) { el.classList.add("hidden"); el.classList.remove("fading"); }
		return;
	}
	const d = trainCardData(f.vehicleId);
	const key = f.vehicleId + "|" + followPillText(d);
	if (key !== followPillKey) {
		followPillKey = key;
		el.innerHTML = `<span class="fp-dot" style="background:${d ? d.hex : PALETTE.accent}"></span>${esc(followPillText(d))}`;
		el.onclick = () => stopFollow("user");
	}
	if (el.classList) {
		el.classList.remove("hidden");
		el.classList.toggle("fading", !!f.fading);
	}
}

/* ---- journey filter (feature 2) ---- */

/** The LINE colours a selected journey rides (its ride legs carry `#rrggbb`). */
function selectionLineHexes(selection) {
	const out = new Set();
	const legs = selection && selection.journey ? selection.journey.legs || [] : [];
	for (const leg of legs) {
		if (leg.kind !== "ride") continue;
		if (leg.color) out.add(String(leg.color).toLowerCase());
		// a through run can hand the rider to a different-coloured service without them
		// leaving their seat: that colour's trains must stay on the map too
		for (const c of leg.continuations || []) if (c.color) out.add(String(c.color).toLowerCase());
	}
	return out;
}

/**
 * Should this vehicle be drawn under the current selection? No selection: yes (the mode
 * layers still apply upstream). A selection: only vehicles whose route's LINE colour is
 * one of the journey's — the rider asked about those trains, everything else is noise.
 * A selection that somehow rides no coloured line hides nothing.
 */
function vehiclePassesSelection(rec, selection) {
	if (!selection) return true;
	const hexes = selection.lineHexes instanceof Set ? selection.lineHexes : selectionLineHexes(selection);
	if (!hexes.size) return true;
	if (!rec || !rec.route) return false;
	return hexes.has((vehicleLineHex(rec) || "").toLowerCase());
}

/* ============================================================================
 * 10d. LIVE JOURNEY TRACKING (feature 6)
 * ==========================================================================
 * "Start" on the selected option hands the rider turn-by-turn guidance: a banner at the
 * top of the map saying what to do next, driven by their own GPS dot and by the live
 * vehicle feed.
 *
 * The whole lifecycle is a PURE REDUCER, `trackReduce(tracking, event)`, exactly like
 * followReduce — every decision is taken from a snapshot of derived facts (`ctx`) rather
 * than by reaching into state, so the harness can drive start -> walk -> wait -> board ->
 * ride -> alight -> transfer -> arrive, and every failure branch, with no DOM and no map.
 *
 * The impure half is `trackContext()`, which measures those facts: how far the rider is
 * from the leg's end, whether a live vehicle on the leg's route is within
 * TRACK_VEHICLE_MATCH blocks of them (the correlation), how many stops that vehicle has
 * left before the alighting platform, and whether they have wandered off the plan.
 *
 * Nothing here ever switches the rider's journey on its own. Going off course sets
 * `needsReplan`; the driver re-runs the planner from the CURRENT position and, only if
 * the answer is meaningfully better (or the current plan is dead), raises an `offer`
 * which the rider accepts or refuses in a popup.
 * ------------------------------------------------------------------------- */

/** What the rider is doing during leg `i`: walking to it, waiting for it, or riding it. */
function trackLegPhase(journey, i) {
	const legs = (journey && journey.legs) || [];
	const leg = legs[i];
	if (!leg) return "arrived";
	if (leg.kind === "ride") return "wait";
	const prev = legs[i - 1], next = legs[i + 1];
	// a walk sandwiched between two rides is an interchange, not a stroll
	return prev && prev.kind === "ride" && next && next.kind === "ride" ? "transfer" : "walk";
}

/** Return `t` unchanged when the patch says nothing new — the banner never re-renders. */
function trackSettle(t, patch) {
	let changed = false;
	for (const k in patch) if (t[k] !== patch[k]) { changed = true; break; }
	return changed ? { ...t, ...patch } : t;
}

function trackFresh(journey, at, geom) {
	return {
		journey, geom: geom || null, stepIndex: 0, phase: trackLegPhase(journey, 0),
		startedAt: at, lastGoodAt: at, updatedAt: at,
		vehicleId: null, pendingVehicleId: null, stopsLeft: null, alert: null, offer: null,
		needsReplan: false, missed: false, offRouteSince: 0, lostSince: 0, arrivedAt: 0, degraded: false,
	};
}

/**
 * @param tracking current tracking state or null
 * @param ev  {type, at, …}
 *            start   {journey, geom}   begin tracking
 *            tick    {ctx}             one second of guidance (ctx from trackContext)
 *            offer   {journey, reason} a re-plan is available — ASK, never switch
 *            switch                    the rider accepted the offer
 *            keep                      the rider refused it (a dead plan degrades)
 *            clearReplan               the driver looked and found nothing better
 *            stop | escape | user      end tracking
 * @returns the next tracking state, or null when tracking has ended.
 */
function trackReduce(tracking, ev) {
	const at = ev && Number.isFinite(ev.at) ? ev.at : 0;
	const type = ev && ev.type;
	if (type === "start") {
		if (!ev.journey || !((ev.journey.legs || []).length)) return null;
		return trackFresh(ev.journey, at, ev.geom);
	}
	if (!tracking) return null;
	switch (type) {
		case "stop": case "escape": case "user": case "dimension":
			return null;
		case "offer": {
			if (!ev.journey && ev.reason !== "invalid") return trackSettle(tracking, { needsReplan: false });
			return { ...tracking, needsReplan: false, offer: {
				journey: ev.journey || null, reason: ev.reason === "invalid" ? "invalid" : "faster",
				at, arriveMs: ev.journey ? ev.journey.arriveMs : 0, savedMs: Number(ev.savedMs) || 0,
			} };
		}
		case "switch": {
			const j = (tracking.offer && tracking.offer.journey) || ev.journey || null;
			if (!j) return trackSettle(tracking, { offer: null, needsReplan: false });
			return trackFresh(j, at, ev.geom);
		}
		case "keep":
			return { ...tracking, offer: null, needsReplan: false, offRouteSince: 0,
				degraded: tracking.degraded || !!(tracking.offer && tracking.offer.reason === "invalid") };
		case "clearReplan":
			return trackSettle(tracking, { needsReplan: false, offRouteSince: 0 });
		case "tick":
			return trackTick(tracking, ev.ctx || {}, at);
		default:
			return tracking;
	}
}

function trackTick(t, c, at) {
	// ARRIVED lingers so the rider can read it, then tracking ends itself
	if (t.phase === "arrived") {
		return at - (t.arrivedAt || t.updatedAt) >= TRACK_ARRIVED_LINGER_MS ? null : t;
	}
	// GPS: the banner says so first, and only a full 30 s of silence ends tracking
	if (!c.hasSelf) {
		const since = t.lostSince || at;
		if (at - since >= TRACK_GPS_LOST_MS) return null;
		return trackSettle(t, { lostSince: since, alert: "gps", updatedAt: at });
	}

	const legs = t.journey.legs || [];
	let i = t.stepIndex;
	let phase = t.phase;
	let vehicleId = t.vehicleId;
	let pendingVehicleId = t.pendingVehicleId || null;
	let stopsLeft = t.stopsLeft;
	let arrivedAt = t.arrivedAt;
	let alert = null;

	const advance = () => {
		i += 1;
		if (i >= legs.length) { phase = "arrived"; arrivedAt = at; i = legs.length - 1; }
		else phase = trackLegPhase(t.journey, i);
		vehicleId = null;
		pendingVehicleId = null;
		stopsLeft = null;
	};

	if (phase === "walk" || phase === "transfer") {
		if (c.atLegEnd) advance();
	} else if (phase === "wait") {
		/* Boarding needs the SAME train two ticks running. A service that merely passes
		 * through the platform is inside the match radius for about a second, and a rider
		 * waiting on that platform must not be declared aboard it; a train they actually
		 * board dwells there. A motion-only match (moving at line speed along the
		 * corridor, with no puck to match) is already unambiguous and counts at once. */
		if (c.aboard && (!c.vehicleId || pendingVehicleId === c.vehicleId)) {
			phase = "ride";
			vehicleId = c.vehicleId || null;
			stopsLeft = Number.isFinite(c.stopsLeft) ? c.stopsLeft : null;
			pendingVehicleId = null;
		} else if (c.aboard && c.vehicleId) {
			pendingVehicleId = c.vehicleId;
			if (Number.isFinite(c.departsInMs) && c.departsInMs <= TRACK_IMMINENT_MS) alert = "boarding";
		} else {
			pendingVehicleId = null;
			if (Number.isFinite(c.departsInMs) && c.departsInMs <= TRACK_IMMINENT_MS) alert = "boarding";
		}
	} else if (phase === "ride") {
		if (c.aboard) {
			vehicleId = c.vehicleId || vehicleId;
			if (Number.isFinite(c.stopsLeft)) stopsLeft = c.stopsLeft;
			if (c.nextStopIsAlight || stopsLeft === 1) alert = "alight";
		} else if (c.atLegEnd) {
			advance();
		}
	}

	/* OFF COURSE. Two ways to fall off a plan: the train left without the rider (the
	 * planned departure is past and nothing has picked them up), or their dot has been
	 * well away from this leg's own geometry for a while. Either one asks the driver for
	 * a fresh plan; neither one changes anything by itself. */
	let offRouteSince = t.offRouteSince;
	let needsReplan = t.needsReplan;
	const missed = phase === "wait" && !c.aboard && !!c.departPassed;
	if (c.offRoute) {
		if (!offRouteSince) offRouteSince = at;
		if (at - offRouteSince >= TRACK_OFF_ROUTE_MS) needsReplan = true;
	} else {
		// back on the plan: the question is withdrawn before it is ever asked
		offRouteSince = 0;
		if (!missed) needsReplan = false;
	}
	if (missed) needsReplan = true;
	if (t.offer) needsReplan = false;          // one question at a time

	return trackSettle(t, {
		stepIndex: i, phase, vehicleId, pendingVehicleId, stopsLeft, arrivedAt, alert,
		offRouteSince, needsReplan, missed, lostSince: 0, lastGoodAt: at, updatedAt: at,
	});
}

/* ---- the impure half: measuring what the rider is actually doing ---- */

/** Blocks per second between the two newest GPS samples (0 with nothing to measure). */
function playerSpeed(rec) {
	const s = (rec && rec.samples) || [];
	if (s.length < 2) return 0;
	const a = s[s.length - 2], b = s[s.length - 1];
	const dt = (b.t - a.t) / 1000;
	if (!(dt > 0)) return 0;
	return Math.hypot(b.x - a.x, b.z - a.z) / dt;
}

/** A platform's (or a plan point's) world position. */
function trackPointOf(platformId) {
	const pl = state.platforms.get(platformId);
	if (pl) return [pl.xz[0], pl.xz[1]];
	const pt = state.pointNodes.get(platformId);
	if (pt) return [pt.xz[0], pt.xz[1]];
	for (const key of ["from", "to"]) {
		const sel = state.plan[key];
		if (sel && sel.point && ((key === "from" && platformId === POINT_FROM) || (key === "to" && platformId === POINT_TO))) {
			return [sel.point[0], sel.point[1]];
		}
	}
	return null;
}

/**
 * Snapshot the geometry of every leg at the moment tracking starts: leg ends, the
 * polyline the rider should be near, and each intermediate stop's position. Taken ONCE,
 * because the plan's point nodes are rewritten by every later query.
 */
function trackGeometry(journey) {
	return (journey.legs || []).map((leg) => {
		const from = trackPointOf(leg.fromPlatform), to = trackPointOf(leg.toPlatform);
		let pts = [];
		if (leg.kind === "ride") {
			const spans = leg.spans && leg.spans.length
				? leg.spans
				: [{ routeId: leg.routeId, fromPlatform: leg.fromPlatform, toPlatform: leg.toPlatform }];
			for (const sp of spans) {
				const rt = state.routes.get(sp.routeId);
				if (!rt) continue;
				const plats = rt.platforms || [];
				const a = plats.indexOf(sp.fromPlatform), b = plats.indexOf(sp.toPlatform);
				if (a < 0 || b < 0) continue;
				for (let k = Math.min(a, b); k < Math.max(a, b); k++) {
					const lg = state.legs.get(rt.id + "|" + k);
					if (lg && lg.pts) for (const p of lg.pts) pts.push([p[0], p[1]]);
				}
			}
		}
		if (pts.length < 2) pts = from && to ? [from, to] : [];
		return { from, to, pts, stops: (leg.stops || []).map(trackPointOf) };
	});
}

/**
 * VEHICLE CORRELATION. The rider is aboard a specific train, and that train's own
 * pPlat/nPlat is a far better countdown than any dead reckoning — so find it: a streamed
 * vehicle whose route is one this leg is aboard for (the boarded route OR any route a
 * through run hands it on to) sitting within TRACK_VEHICLE_MATCH blocks of the rider.
 * The already-matched vehicle keeps the match out to twice that, so a puck drifting a
 * few blocks while its samples interpolate does not hand the rider to the train behind.
 */
function trackMatchVehicle(leg, pos, preferId) {
	const routes = legRouteIds(leg);
	let best = null;
	for (const [id, rec] of state.vehicles) {
		if (!rec.route || !routes.includes(rec.route.id)) continue;
		const p = rec.disp || (rec.data && Number.isFinite(rec.data.x) ? { x: rec.data.x, z: rec.data.z } : null);
		if (!p) continue;
		const d = Math.hypot(p.x - pos.x, p.z - pos.z);
		const limit = id === preferId ? TRACK_VEHICLE_MATCH * 2 : TRACK_VEHICLE_MATCH;
		if (d > limit) continue;
		const score = id === preferId ? d - TRACK_VEHICLE_MATCH : d;   // hysteresis
		if (!best || score < best.score) best = { id, rec, dist: d, score };
	}
	return best;
}

/**
 * Stops still to go before the rider gets off, read from the matched vehicle: 1 means
 * "the next stop is yours". Falls back to the stop it just left when nPlat is missing,
 * and returns null when the train is not on this leg's stop list at all.
 */
function trackStopsLeft(leg, rec) {
	const stops = leg.stops || [];
	if (stops.length < 2) return null;
	const last = stops.length - 1;
	const d = (rec && rec.data) || {};
	let idx = d.nPlat ? stops.indexOf(d.nPlat) : -1;
	if (idx < 0 && d.pPlat) {
		const p = stops.indexOf(d.pPlat);
		if (p >= 0) idx = p + 1;
	}
	if (idx < 0 || idx > last) return null;
	return Math.max(0, last - idx + 1);
}

/** Same count, estimated from the rider's own position when no puck matched. */
function trackStopsLeftByPosition(geom, leg, pos) {
	const pts = (geom && geom.stops) || [];
	const stops = leg.stops || [];
	if (pts.length !== stops.length || stops.length < 2) return null;
	let nearest = 0, bestD = Infinity;
	for (let i = 0; i < pts.length; i++) {
		if (!pts[i]) continue;
		const d = Math.hypot(pts[i][0] - pos.x, pts[i][1] - pos.z);
		if (d < bestD) { bestD = d; nearest = i; }
	}
	return Math.max(1, stops.length - 1 - nearest);
}

/** Everything trackReduce needs to know about the world right now. */
function trackContext(tracking, at) {
	const legs = (tracking && tracking.journey && tracking.journey.legs) || [];
	const leg = legs[tracking.stepIndex] || null;
	const geom = tracking.geom ? tracking.geom[tracking.stepIndex] : null;
	const rec = state.selfPlayer ? state.players.get(state.selfPlayer) : null;
	const pos = selfPlayerPos();
	const ctx = {
		hasSelf: !!(rec && pos), pos, speed: 0, atLegEnd: false, distanceToEnd: null,
		aboard: false, vehicleId: null, stopsLeft: null, nextStopIsAlight: false,
		offRoute: false, departPassed: false, departsInMs: null,
	};
	if (!ctx.hasSelf || !leg) return ctx;
	ctx.speed = playerSpeed(rec);
	if (geom && geom.to) {
		ctx.distanceToEnd = Math.hypot(pos.x - geom.to[0], pos.z - geom.to[1]);
		ctx.atLegEnd = ctx.distanceToEnd <= TRACK_NEAR_PLATFORM;
	}
	const corridor = geom && geom.pts && geom.pts.length >= 2
		? pointPolylineDist([pos.x, pos.z], geom.pts) : 0;
	ctx.offRoute = corridor > TRACK_OFF_ROUTE_DIST;

	if (leg.kind === "ride") {
		ctx.departsInMs = leg.depMs - at;
		ctx.departPassed = at > leg.depMs + 15000;
		const m = trackMatchVehicle(leg, pos, tracking.vehicleId);
		if (m) {
			ctx.vehicleId = m.id;
			ctx.aboard = true;
			const sl = trackStopsLeft(leg, m.rec);
			if (Number.isFinite(sl)) ctx.stopsLeft = sl;
		} else if (ctx.speed >= TRACK_RIDE_SPEED && corridor <= TRACK_CORRIDOR) {
			ctx.aboard = true;                       // moving like a train, no puck to match
		}
		if (ctx.aboard && !Number.isFinite(ctx.stopsLeft)) {
			const est = trackStopsLeftByPosition(geom, leg, pos);
			if (Number.isFinite(est)) ctx.stopsLeft = est;
		}
		ctx.nextStopIsAlight = ctx.stopsLeft === 1 || ctx.stopsLeft === 0;
		// standing still at the alighting platform IS getting off, however well the puck
		// still matches — the train dwells there with the rider stepping out of it
		if (ctx.aboard && ctx.atLegEnd && ctx.speed < 2) ctx.aboard = false;
	}
	return ctx;
}

/* ---- what the banner says ---- */

/**
 * The banner's content as plain values (pure — the harness asserts `text` directly).
 * `bullet`/`hex` let the DOM draw a real line bullet where the text has its label.
 */
function trackBannerText(tracking, ctx) {
	const c = ctx || {};
	const legs = (tracking && tracking.journey && tracking.journey.legs) || [];
	const leg = legs[tracking.stepIndex] || null;
	const out = { phase: tracking ? tracking.phase : "", alert: (tracking && tracking.alert) || null,
		pre: "", bullet: "", hex: "", post: "", detail: "", text: "" };
	const finish = () => {
		const head = out.pre + out.bullet + out.post;
		out.text = out.detail ? head + " · " + out.detail : head;
		return out;
	};
	if (!tracking) return finish();
	if (tracking.phase === "arrived") {
		out.pre = "Arrived";
		out.detail = tracking.journey.toPoint
			? (tracking.journey.toName || "your destination")
			: stationLabel(tracking.journey.toStation, tracking.journey.toPart);
		return finish();
	}
	if (tracking.alert === "gps") {
		out.pre = "GPS lost — waiting for your position";
		return finish();
	}
	if (!leg) return finish();

	const endName = leg.toPoint ? (leg.toName || "your destination") : stationLabel(leg.toStation, leg.toPart);
	if (tracking.phase === "walk") {
		out.pre = "Walk to " + endName;
		out.detail = fmtMeters(Number.isFinite(c.distanceToEnd) ? c.distanceToEnd : leg.meters || 0);
		return finish();
	}
	if (tracking.phase === "transfer") {
		out.pre = "Transfer: " + (leg.note || "change platforms") + ", " + fmtMeters(leg.meters || 0);
		return finish();
	}
	if (tracking.phase === "wait") {
		const mins = Number.isFinite(c.departsInMs) ? Math.round(c.departsInMs / 60000) : leg.departsInMin;
		out.pre = "Board the ";
		out.bullet = legLabel(leg) || leg.routeName || "train";
		out.hex = leg.color || "";
		out.post = leg.headsign ? " toward " + firstLang(leg.headsign) : "";
		out.detail = !Number.isFinite(mins) ? "" : mins <= 0 ? "departing now" : "departs in " + mins + " min";
		return finish();
	}
	if (tracking.phase === "ride") {
		if (tracking.alert === "alight") {
			out.pre = "Get off at the NEXT stop";
			out.detail = endName;
			return finish();
		}
		const left = Number.isFinite(tracking.stopsLeft) ? tracking.stopsLeft : leg.stopCount;
		out.pre = "On the ";
		out.bullet = legLabel(leg) || leg.routeName || "train";
		out.hex = leg.color || "";
		out.detail = Number.isFinite(left)
			? left + " stop" + (left === 1 ? "" : "s") + " to " + endName
			: "to " + endName;
		return finish();
	}
	return finish();
}

/* ---- driver: start / stop / tick / the re-plan offer ---- */

/** The station parts of the journey being tracked (labels stay on while it runs). */
function trackedPartIds() {
	const t = state.tracking;
	if (!t || !t.journey) return null;
	if (t.partIds) return t.partIds;
	const set = new Set();
	for (const leg of t.journey.legs || []) {
		for (const pid of leg.stops || [leg.fromPlatform, leg.toPlatform]) {
			const pl = state.platforms.get(pid);
			if (pl && pl.partId) set.add(pl.partId);
		}
		if (leg.fromPart) set.add(leg.fromPart);
		if (leg.toPart) set.add(leg.toPart);
	}
	t.partIds = set;
	return set;
}

function startTracking(journey) {
	if (!journey || !((journey.legs || []).length)) return null;
	state.tracking = trackReduce(null, { type: "start", journey, at: now(), geom: trackGeometry(journey) });
	state.trackCam = true;
	selectJourney(journey);
	renderTrackBanner();
	renderTrackOffer();
	renderOptions();
	return state.tracking;
}

function stopTracking(reason) {
	if (!state.tracking) return null;
	state.tracking = trackReduce(state.tracking, { type: reason || "stop", at: now() });
	state.trackCam = false;
	renderTrackBanner();
	renderTrackOffer();
	renderOptions();
	return state.tracking;
}

/** The rider took the camera back (any deliberate pan/zoom), exactly like follow. */
function releaseTrackCamera() { state.trackCam = false; }

/** Where the tracked journey ends, as a planner endpoint. */
function trackDestination(journey) {
	const last = (journey.legs || [])[(journey.legs || []).length - 1];
	if (!last) return null;
	if (last.toPoint) {
		const p = trackPointOf(last.toPoint);
		return p ? { point: [p[0], p[1]], label: last.toName || "Destination" } : null;
	}
	if (!last.toStation) return null;
	return { stationId: last.toStation, partId: last.toPart || null };
}

/** When the rider now expects to arrive on the plan they are following. */
function trackProjectedArrival(tracking, at) {
	const legs = (tracking.journey.legs || []);
	const leg = legs[tracking.stepIndex];
	// running late on the current leg pushes the whole tail of the journey back
	const behind = leg && tracking.phase === "wait" ? Math.max(0, at - leg.depMs) : 0;
	return tracking.journey.arriveMs + behind;
}

/**
 * The rider has fallen off the plan. Re-run the planner FROM WHERE THEY ARE and, if the
 * answer is worth it, ask. This never switches anything: the popup does, and only when
 * the rider presses Switch.
 */
function trackOfferReplan(at) {
	const t = state.tracking;
	if (!t) return null;
	const pos = selfPlayerPos();
	const dest = trackDestination(t.journey);
	const viable = !t.missed;
	if (!pos || !dest) {
		state.tracking = trackReduce(t, { type: "clearReplan", at });
		return null;
	}
	if (!state.plan.graph) state.plan.graph = buildGraph();
	// planEndpoints rewrites state.pointNodes for its own query — the tracked journey's
	// anchors must not be collateral damage
	const keepPoints = state.pointNodes;
	let cand = null;
	try {
		const res = planEndpoints(state.plan.graph,
			{ point: [pos.x, pos.z], y: pos.y, label: "My location", live: true }, dest, state.plan.prefs, at);
		cand = (res.journeys || [])[0] || null;
	} catch (e) {
		cand = null;
	}
	state.pointNodes = keepPoints;
	const projected = trackProjectedArrival(t, at);
	const better = cand && cand.arriveMs < projected - TRACK_BETTER_MS;
	if (cand && (better || !viable)) {
		state.tracking = trackReduce(t, {
			type: "offer", at, journey: cand, reason: viable ? "faster" : "invalid",
			savedMs: Math.max(0, projected - cand.arriveMs),
		});
	} else if (!viable) {
		state.tracking = trackReduce(t, { type: "offer", at, journey: null, reason: "invalid" });
	} else {
		state.tracking = trackReduce(t, { type: "clearReplan", at });
	}
	renderTrackOffer();
	renderTrackBanner();
	return state.tracking;
}

/** One second of guidance. Called from tickLive, which runs whether or not SSE is alive. */
function tickTracking() {
	const t = state.tracking;
	if (!t) return null;
	const at = now();
	const ctx = trackContext(t, at);
	const next = trackReduce(t, { type: "tick", at, ctx });
	const changed = next !== t;
	state.tracking = next;
	if (!next) {
		state.trackCam = false;
		renderTrackBanner();
		renderTrackOffer();
		renderOptions();
		return null;
	}
	// the map keeps the journey's fade for as long as the rider is on it
	if (!state.selection) selectJourney(next.journey);
	if (next.needsReplan && !next.offer) trackOfferReplan(at);
	renderTrackBanner(ctx);
	if (changed && next.phase !== t.phase) renderOptions();
	return state.tracking;
}

/** Ride the self dot while aboard, on the same terms follow rides a train. */
function trackCamera() {
	const t = state.tracking;
	if (!t || !state.trackCam || t.phase !== "ride" || state.follow) return false;
	const p = selfPlayerPos();
	if (!p) return false;
	const rec = state.players.get(state.selfPlayer);
	const at = (rec && rec.disp) || p;
	if (Math.abs(state.view.x - at.x) > 0.01 || Math.abs(state.view.z - at.z) > 0.01) {
		state.view.x = at.x;
		state.view.z = at.z;
		invalidateStatic();
	}
	return true;
}

/* ---- the banner + the offer popup ---- */

let trackBannerKey = "";
function renderTrackBanner(ctx) {
	const el = $("trackBanner");
	if (!el) return;
	const t = state.tracking;
	if (!t) {
		trackBannerKey = "";
		if (el.classList) { el.classList.add("hidden"); el.classList.remove("alert"); }
		return;
	}
	const d = trackBannerText(t, ctx || null);
	const loud = t.alert === "alight" || t.alert === "boarding";
	const html = `<span class="tb-body">${esc(d.pre)}${
		d.bullet ? `<span class="bullet sm" style="background:${d.hex || PALETTE.accent}">${esc(d.bullet)}</span>` : ""
	}${esc(d.post)}${d.detail ? `<span class="tb-detail">${esc(d.detail)}</span>` : ""}</span>`
		+ `<button class="tb-close" type="button" title="Stop tracking" aria-label="Stop tracking">×</button>`;
	const key = t.phase + "|" + (t.alert || "") + "|" + html;
	if (key !== trackBannerKey) {
		trackBannerKey = key;
		el.innerHTML = html;
		const close = el.querySelector ? el.querySelector(".tb-close") : null;
		if (close) close.onclick = (e) => { if (e && e.stopPropagation) e.stopPropagation(); stopTracking("user"); };
	}
	if (el.classList) {
		el.classList.remove("hidden");
		el.classList.toggle("alert", loud);
		el.classList.toggle("degraded", !!t.degraded);
	}
}

/** The popup. A rider is ASKED; a route is never swapped under them. */
function trackOfferHtml(offer) {
	const invalid = offer.reason === "invalid";
	const saved = offer.savedMs > 60000 ? Math.round(offer.savedMs / 60000) + " min earlier" : "";
	const title = invalid
		? (offer.journey ? "This journey is no longer valid" : "This journey is no longer valid")
		: "A faster route is available";
	const body = offer.journey
		? (invalid
			? "You missed the connection. A replacement gets you there at " + fmtTime(offer.journey.arriveMs) + "."
			: "Switching gets you in at " + fmtTime(offer.journey.arriveMs) + (saved ? " — " + saved + "." : "."))
		: "There is no other way there right now — keep going and the map will follow your progress.";
	return `
		<div class="to-card">
			<div class="to-title">${esc(title)}</div>
			<div class="to-body">${esc(body)}</div>
			<div class="to-actions">
				${offer.journey ? '<button class="to-switch" type="button">Switch</button>' : ""}
				<button class="to-keep" type="button">Keep current</button>
			</div>
		</div>`;
}

let trackOfferKey = "";
function renderTrackOffer() {
	const el = $("trackOffer");
	if (!el) return;
	const offer = state.tracking && state.tracking.offer;
	if (!offer) {
		trackOfferKey = "";
		if (el.classList) el.classList.add("hidden");
		return;
	}
	const html = trackOfferHtml(offer);
	if (html !== trackOfferKey) {
		trackOfferKey = html;
		el.innerHTML = html;
		const sw = el.querySelector ? el.querySelector(".to-switch") : null;
		if (sw) sw.onclick = () => acceptTrackOffer();
		const keep = el.querySelector ? el.querySelector(".to-keep") : null;
		if (keep) keep.onclick = () => refuseTrackOffer();
	}
	if (el.classList) el.classList.remove("hidden");
}

function acceptTrackOffer() {
	const t = state.tracking;
	if (!t || !t.offer || !t.offer.journey) return null;
	const j = t.offer.journey;
	state.tracking = trackReduce(t, { type: "switch", at: now(), journey: j, geom: trackGeometry(j) });
	state.trackCam = true;
	selectJourney(j);
	renderTrackBanner();
	renderTrackOffer();
	renderOptions();
	return state.tracking;
}

function refuseTrackOffer() {
	if (!state.tracking || !state.tracking.offer) return null;
	state.tracking = trackReduce(state.tracking, { type: "keep", at: now() });
	renderTrackBanner();
	renderTrackOffer();
	return state.tracking;
}

/* ============================================================================
 * 10e. send to game — pairing + navigate
 * ==========================================================================
 * The rider plans on the map and pushes the result into the game: the selected option
 * card grows a "Send to game" button, and the in-game HUD walks them through it.
 *
 * The browser has no idea who the player is, so it PAIRS first: the player runs
 * `/navpair` in game (its own root, NOT a `/dispatch` subcommand — brigadier's same-root
 * merge keeps the first registration's permission predicate, which would have made
 * pairing op-only), the server prints a six-character code, and this page trades
 * that code for a token it keeps in the same prefs blob as everything else. Nothing but
 * {token, player, label} is stored — the token IS the identity, so a stale one is
 * dropped silently the moment api/navstatus says it is unknown.
 *
 *   POST api/pair       {code, label}      -> {ok, token, player} | {ok:false, error}
 *   GET  api/navstatus  ?token=            -> {ok, player, online} | {ok:false, error}
 *   POST api/navigate   {token, journey}   -> {ok} | {ok:false, error}
 *
 * The journey wire shape is deliberately NOT this file's Journey object: the HUD needs
 * ids and metres, not display strings, so journeyPayload() flattens it (see there for
 * the leg-by-leg mapping and the caps the server enforces).
 *
 * The whole section is stubbed in ?demo=1 (demoNav), so the modal, a successful pairing
 * with the code DEMO23, the send flow and the toast are all exercisable with no server.
 * ------------------------------------------------------------------------- */

/**
 * The unambiguous code alphabet — A–Z without I or O, plus 2–9 (no 0 or 1 either).
 *
 * This MIRRORS `NavStore.CODE_ALPHABET` on the server, which is what actually generates
 * the codes: a sanitiser narrower than the generator would silently eat characters out of
 * a perfectly good code, so if that constant ever changes, change this one with it.
 */
const PAIR_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const PAIR_CODE_LEN = 6;
/** Caps the server enforces — mirrored here so a body is never sent that it will reject. */
const NAV_MAX_LEGS = 24;
const NAV_MAX_VIA = 4;
const NAV_DEST_MAX = 64;
/** Station/headsign names, route names and bullet labels sent alongside the ids. */
const NAV_NAME_MAX = 64;
const NAV_ROUTE_NAME_MAX = 48;
const NAV_ROUTE_LABEL_MAX = 8;
/** Intermediate stops per ride, so the HUD counts down beyond MTR's synced area. */
const NAV_MAX_STOP_LIST = 48;
/** 64 KB, not 16: the names and stop lists that keep the HUD readable are what fill it. */
const NAV_MAX_BODY = 64 * 1024;
/** The server allows one send per 3 s; the button holds the same line locally. */
const NAV_SEND_COOLDOWN_MS = 3000;
const NAV_TOAST_MS = 4200;
/** A walk endpoint with no y anywhere to borrow: sea level, the same default the HUD uses. */
const NAV_DEFAULT_Y = 64;
/**
 * ?demo=1 fixtures. The demo code spells a word, so it contains an O — a letter the real
 * alphabet deliberately excludes. Demo mode therefore widens the ACCEPTED set by exactly
 * the two excluded letters so the code can be typed; the production sanitiser
 * (sanitiseWithAlphabet over PAIR_ALPHABET) is untouched.
 */
const DEMO_PAIR_CODE = "DEMO23";
const DEMO_NAV_TOKEN = "deadbeefdeadbeefdeadbeefdeadbeef";
const codeAlphabet = () => (DEMO ? PAIR_ALPHABET + "IO" : PAIR_ALPHABET);

const round2 = (v) => Math.round((Number(v) || 0) * 100) / 100;

/* ---- code + token hygiene (pure) ---- */

/**
 * Paste-friendly code sanitiser: upper-cases, throws away everything outside the
 * alphabet (spaces, dashes, the "code:" a rider pastes with it, and the ambiguous
 * letters the alphabet deliberately excludes) and stops at six characters.
 */
function sanitiseWithAlphabet(alphabet, raw) {
	const s = String(raw == null ? "" : raw).toUpperCase();
	let out = "";
	for (const ch of s) {
		if (alphabet.indexOf(ch) >= 0) out += ch;
		if (out.length === PAIR_CODE_LEN) break;
	}
	return out;
}

function sanitisePairCode(raw) { return sanitiseWithAlphabet(codeAlphabet(), raw); }

function pairCodeValid(code) {
	return sanitisePairCode(code).length === PAIR_CODE_LEN
		&& String(code || "").toUpperCase().replace(/[^A-Z0-9]/g, "").length === PAIR_CODE_LEN;
}

/** A token is only worth storing if it looks like the 32 hex the server hands out. */
function navTokenValid(token) {
	return /^[0-9a-f]{32}$/i.test(String(token || ""));
}

/** Best-effort name for this browser, for the player's "paired devices" list. */
function browserLabel() {
	let ua = "";
	try { ua = String((typeof navigator !== "undefined" && navigator.userAgent) || ""); } catch (e) { /* no DOM */ }
	const browser = /Edg\//.test(ua) ? "Edge" : /OPR\/|Opera/.test(ua) ? "Opera"
		: /Firefox\//.test(ua) ? "Firefox" : /Chrome\//.test(ua) ? "Chrome"
			: /Safari\//.test(ua) ? "Safari" : "Browser";
	const os = /Mac OS X|Macintosh/.test(ua) ? "Mac" : /Windows/.test(ua) ? "Windows"
		: /Android/.test(ua) ? "Android" : /iPhone|iPad|iPod/.test(ua) ? "iOS"
			: /Linux/.test(ua) ? "Linux" : "";
	return os ? browser + " on " + os : browser;
}

/* ---- journey -> wire payload (pure) ---- */

/**
 * A walk leg is a TRANSFER when both its ends are real platforms of the SAME station —
 * exactly the walks the itinerary folds into the next boarding row as a transfer chip.
 * A walk between two different stations is still a walk (it happens on the street), and
 * so is anything touching a dropped pin or the live GPS origin.
 */
function navIsTransferLeg(leg) {
	return !!(leg && leg.kind === "walk" && !leg.fromPoint && !leg.toPoint
		&& leg.fromStation && leg.fromStation === leg.toStation);
}

/** Where a virtual point node actually is — the live plan first, the selection as backup. */
function navPointOf(pointId) {
	const pt = state.pointNodes.get(pointId);
	if (pt && pt.xz) return { xz: pt.xz, y: pt.y };
	const sel = pointId === POINT_TO ? state.plan.to : state.plan.from;
	if (sel && sel.point) return { xz: sel.point, y: sel.y };
	return null;
}

/**
 * The station a platform belongs to, and where it is in the world.
 *
 * BOTH travel on the wire, because the game client can only resolve a platform id that
 * MTR has synced to it — everything outside the player's synced area came out as
 * "Unknown stop", and the in-world waypoint had no position to point at. The client
 * still prefers its own live lookup; these are the fallback.
 */
function navPlatformInfo(platformId) {
	const p = state.platforms.get(platformId);
	if (!p) return null;
	const name = stationLabel(p.stationId, p.partId) || p.name || "";
	const xz = p.xz || [0, 0];
	return { name: String(name).slice(0, NAV_NAME_MAX), pos: [round2(xz[0]),
		round2(Number.isFinite(p.y) ? p.y : NAV_DEFAULT_Y), round2(xz[1])] };
}

/**
 * One end of a walk leg on the wire: `{platform:id}` for a real platform, world
 * coordinates for a dropped pin / the live GPS origin. A point carries no height of its
 * own unless it came from a player, so the y is borrowed from the platform at the OTHER
 * end of the same walk, and only then falls back to 64. Either shape may carry `name`,
 * and a platform end also carries `pos` (see navPlatformInfo).
 */
function navEndpoint(leg, which) {
	const point = which === "to" ? leg.toPoint : leg.fromPoint;
	const id = which === "to" ? leg.toPlatform : leg.fromPlatform;
	const label = String((which === "to" ? leg.toName : leg.fromName) || "").slice(0, NAV_NAME_MAX);
	if (!point) {
		const info = navPlatformInfo(id);
		const out = { platform: String(id) };
		const name = label || (info && info.name) || "";
		if (name) out.name = name;
		if (info) out.pos = info.pos;
		return out;
	}
	const pt = navPointOf(point) || navPointOf(id);
	const xz = (pt && pt.xz) || [0, 0];
	const other = state.platforms.get(which === "to" ? leg.fromPlatform : leg.toPlatform);
	const y = pt && Number.isFinite(pt.y) ? pt.y
		: other && Number.isFinite(other.y) ? other.y : NAV_DEFAULT_Y;
	const out = { x: round2(xz[0]), y: round2(y), z: round2(xz[1]) };
	if (label) out.name = label;
	return out;
}

/** One leg on the wire. Ride / transfer / walk — the only three shapes the HUD reads. */
function navLegPayload(leg) {
	if (!leg) return null;
	if (leg.kind === "ride") {
		const board = navPlatformInfo(leg.fromPlatform), alight = navPlatformInfo(leg.toPlatform);
		const out = {
			type: "ride", route: String(leg.routeId),
			board: String(leg.fromPlatform), alight: String(leg.toPlatform),
			stops: Math.max(0, Math.round(leg.stopCount || 0)),
			// Everything below is what the game client cannot look up beyond MTR's synced
			// area; it falls back to these rather than printing "Unknown stop" / "?".
			routeName: String(leg.routeName || "").slice(0, NAV_ROUTE_NAME_MAX),
			routeLabel: String(legLabel(leg) || "").slice(0, NAV_ROUTE_LABEL_MAX),
			routeColor: leg.color || "",
			headsign: String(firstLang(leg.headsign || "")).slice(0, NAV_NAME_MAX),
			boardName: String(leg.fromName || (board && board.name) || "").slice(0, NAV_NAME_MAX),
			alightName: String(leg.toName || (alight && alight.name) || "").slice(0, NAV_NAME_MAX),
		};
		if (board) out.boardPos = board.pos;
		if (alight) out.alightPos = alight.pos;
		// The stops the rider passes, so the HUD can count down out of sync range.
		const stopList = (leg.stops || []).slice(0, NAV_MAX_STOP_LIST)
			.map((id) => navPlatformInfo(id)).filter(Boolean)
			.map((info) => ({ name: info.name, pos: info.pos }));
		if (stopList.length) out.stopList = stopList;
		// a through run is the same train changing route under the rider: the HUD needs
		// to know so it does not tell them to get off (capped at the server's 4)
		const via = (leg.continuations || []).slice(0, NAV_MAX_VIA).map((c) => {
			const rt = state.routes.get(c.routeId);
			return {
				route: String(c.routeId), at: String(c.platform),
				routeName: String(c.routeName || (rt && rt.display) || "").slice(0, NAV_ROUTE_NAME_MAX),
				routeLabel: String(c.number || (rt && rt.number) || "").slice(0, NAV_ROUTE_LABEL_MAX),
				routeColor: c.color || (rt && rt.hex) || "",
				headsign: String(firstLang(c.headsign || (rt && rt.dest) || "")).slice(0, NAV_NAME_MAX),
			};
		});
		if (via.length) out.via = via;
		return out;
	}
	const meters = Math.max(0, Math.round(leg.meters || 0));
	if (navIsTransferLeg(leg)) {
		const from = navPlatformInfo(leg.fromPlatform), to = navPlatformInfo(leg.toPlatform);
		const out = { type: "transfer", from: String(leg.fromPlatform), to: String(leg.toPlatform), meters };
		const fromName = String(leg.fromName || (from && from.name) || "").slice(0, NAV_NAME_MAX);
		const toName = String(leg.toName || (to && to.name) || "").slice(0, NAV_NAME_MAX);
		if (fromName) out.fromName = fromName;
		if (toName) out.toName = toName;
		if (from) out.fromPos = from.pos;
		if (to) out.toPos = to.pos;
		return out;
	}
	return { type: "walk", from: navEndpoint(leg, "from"), to: navEndpoint(leg, "to"), meters };
}

/** What the HUD calls the destination — the same string the itinerary's pin row shows. */
function navDestinationLabel(journey) {
	let s = journey && journey.toPoint
		? (journey.toName || "Dropped pin")
		: stationLabel(journey && journey.toStation, journey && journey.toPart);
	s = String(s == null ? "" : s).trim() || "Destination";
	if (s.length > NAV_DEST_MAX) s = s.slice(0, NAV_DEST_MAX - 1) + "…";
	return s;
}

/**
 * Flatten a Journey into the wire shape.
 *
 * @returns {{payload, notes:string[]}|null} `notes` records what had to give to fit the
 *          server's 24-leg cap: the LEADING walk goes first (a rider standing at their
 *          own start does not need to be told to walk out of their front door), and only
 *          if that is still not enough is the tail truncated — which the caller says out
 *          loud rather than pretending the whole journey went.
 */
function journeyPayload(journey) {
	if (!journey || !journey.legs || !journey.legs.length) return null;
	let legs = journey.legs.map(navLegPayload).filter(Boolean);
	if (!legs.length) return null;
	const notes = [];
	while (legs.length > NAV_MAX_LEGS && legs[0].type === "walk") {
		legs = legs.slice(1);
		notes.push("dropped-lead-walk");
	}
	if (legs.length > NAV_MAX_LEGS) {
		legs = legs.slice(0, NAV_MAX_LEGS);
		notes.push("truncated");
	}
	return {
		payload: {
			destination: navDestinationLabel(journey),
			plannedArriveMs: Math.round(journey.arriveMs) || 0,
			legs,
		},
		notes,
	};
}

/** The line the toast adds when the payload had to be trimmed. */
function navNoticeText(notes) {
	if (!notes || !notes.length) return "";
	if (notes.indexOf("truncated") >= 0) return "Too long for the HUD — only the first " + NAV_MAX_LEGS + " steps were sent.";
	if (notes.indexOf("dropped-lead-walk") >= 0) return "The opening walk was dropped to fit the HUD.";
	return "";
}

/* ---- transport ---- */

/** ?demo=1 has no server: these three stubs stand in for it, errors and all. */
function demoNav(path, init) {
	let body = {};
	try { body = init && init.body ? JSON.parse(init.body) : {}; } catch (e) { body = {}; }
	if (path.indexOf("pair") === 0) {
		return Promise.resolve(sanitisePairCode(body.code) === DEMO_PAIR_CODE
			? { ok: true, token: DEMO_NAV_TOKEN, player: "Demo" }
			: { ok: false, error: "invalid or expired code" });
	}
	if (path.indexOf("navstatus") === 0) {
		const token = (String(path).split("token=")[1] || "").split("&")[0];
		return Promise.resolve(token === DEMO_NAV_TOKEN
			? { ok: true, player: "Demo", online: !state.nav.demoOffline }
			: { ok: false, error: "unknown token" });
	}
	if (path.indexOf("navigate") === 0) {
		if (body.token !== DEMO_NAV_TOKEN) return Promise.resolve({ ok: false, error: "unknown token" });
		if (state.nav.demoOffline) return Promise.resolve({ ok: false, error: "player offline" });
		const j = body.journey;
		if (!j || !Array.isArray(j.legs) || !j.legs.length || j.legs.length > NAV_MAX_LEGS) {
			return Promise.resolve({ ok: false, error: "malformed journey" });
		}
		if (now() - (state.nav.demoLastAt || 0) < NAV_SEND_COOLDOWN_MS) {
			return Promise.resolve({ ok: false, error: "rate limited — wait a moment" });
		}
		state.nav.demoLastAt = now();
		return Promise.resolve({ ok: true });
	}
	return Promise.resolve({ ok: false, error: "unknown endpoint" });
}

/**
 * One request. Never throws: a backend that has not shipped this feature (404, HTML,
 * connection refused) comes back as a normal {ok:false, error} the modal can render.
 */
async function navHttp(path, init) {
	try {
		if (DEMO) return await demoNav(path, init);
		const res = await fetch(`${API}/${path}`, init || undefined);
		const body = await res.json();
		const out = body && body.data ? body.data : body;
		if (!out || typeof out !== "object") return { ok: false, error: "Unexpected reply from the server." };
		return out;
	} catch (e) {
		return { ok: false, error: "Could not reach the dispatch server." };
	}
}

const navJson = (obj) => ({
	method: "POST",
	headers: { "Content-Type": "application/json" },
	body: JSON.stringify(obj),
});

/* ---- token lifecycle ---- */

function navSetToken(token, player, label) {
	state.nav.token = String(token || "");
	state.nav.player = String(player || "");
	state.nav.label = String(label || state.nav.label || "");
	savePrefs();
	return state.nav;
}

/** Forget the pairing. Called by Unpair AND by any server reply that disowns the token. */
function navClearToken() {
	state.nav.token = "";
	state.nav.player = "";
	state.nav.online = null;
	state.nav.sentSig = "";
	savePrefs();
	return state.nav;
}

/**
 * Fold an api/navstatus reply into state.
 * @returns {"paired"|"offline"|"unknown"|"unreachable"}
 */
function navApplyStatus(res) {
	if (!res || typeof res !== "object") return "unreachable";
	if (res.ok === true) {
		if (res.player) state.nav.player = String(res.player);
		// `stale` means the server thread could not be asked in time — that is "we do not
		// know", not "logged out", and the rider must not be told the wrong thing
		if (res.stale) { state.nav.online = null; return "unknown-online"; }
		state.nav.online = res.online !== false;
		return state.nav.online ? "paired" : "offline";
	}
	// "unknown token" is the ONE error that means the pairing is gone: anything else
	// (offline server, rate limit, a stray 500) leaves the token alone
	if (/unknown token|invalid token|expired/i.test(String(res.error || ""))) {
		navClearToken();
		return "unknown";
	}
	return "unreachable";
}

/** Re-validate the stored token. Silent by design — it runs on every boot. */
async function navCheckStatus() {
	if (!state.nav.token) return "unpaired";
	state.nav.checking = true;
	const res = await navHttp("navstatus?token=" + encodeURIComponent(state.nav.token));
	state.nav.checking = false;
	const outcome = navApplyStatus(res);
	renderPairRow();
	renderNavModal();
	renderOptions();
	return outcome;
}

/** Trade a code for a token. Errors come back as strings for the modal, never thrown. */
async function navPair(code) {
	const clean = sanitisePairCode(code);
	if (clean.length !== PAIR_CODE_LEN) return { ok: false, error: "Enter the six-character code the game showed you." };
	const label = browserLabel();
	const res = await navHttp("pair", navJson({ code: clean, label }));
	if (res && res.ok === true && navTokenValid(res.token)) {
		navSetToken(res.token, res.player, label);
		state.nav.online = true;
		return { ok: true, player: state.nav.player };
	}
	if (res && res.ok === true) return { ok: false, error: "The server sent back an unusable token." };
	return { ok: false, error: String((res && res.error) || "Pairing failed.") };
}

/* ---- sending ---- */

/**
 * Push the selected journey at the paired player's HUD.
 * @returns {{ok:boolean, error?:string, notice?:string}}
 */
async function navSend(journey) {
	if (!journey) return { ok: false, error: "Nothing to send." };
	if (!state.nav.token) return { ok: false, error: "unpaired" };
	if (state.nav.sending) return { ok: false, error: "Already sending." };
	const built = journeyPayload(journey);
	if (!built) return { ok: false, error: "That journey has no steps to send." };
	const since = now() - (state.nav.lastSendAt || 0);
	if (state.nav.lastSendAt && since < NAV_SEND_COOLDOWN_MS) {
		return { ok: false, error: "One send every " + (NAV_SEND_COOLDOWN_MS / 1000) + " seconds — try again in a moment." };
	}
	const init = navJson({ token: state.nav.token, journey: built.payload });
	if (init.body.length > NAV_MAX_BODY) return { ok: false, error: "That journey is too large to send." };

	state.nav.sending = true;
	renderOptions();
	const res = await navHttp("navigate", init);
	state.nav.sending = false;

	if (res && res.ok === true) {
		state.nav.lastSendAt = now();
		state.nav.online = true;
		state.nav.sentSig = journey.signature || "";
		const notice = navNoticeText(built.notes);
		navShowToast("Sent to " + (state.nav.player || "your") + "’s HUD" + (notice ? " · " + notice : ""), "ok");
		renderOptions();
		renderPairRow();
		return { ok: true, notice };
	}
	const error = String((res && res.error) || "The game did not accept the journey.");
	if (/unknown token|invalid token|expired/i.test(error)) navClearToken();
	if (/offline/i.test(error)) state.nav.online = false;
	navShowToast(error, "err");
	renderOptions();
	renderPairRow();
	return { ok: false, error };
}

/** The button's click: pair first if we have to, otherwise send. */
function navSendPressed(journey) {
	if (!state.nav.token) { openNavModal(journey); return null; }
	return navSend(journey);
}

/* ---- toast ---- */

let navToastTimer = 0;
function navShowToast(text, kind) {
	state.nav.toast = { text: String(text || ""), kind: kind === "err" ? "err" : "ok", at: now() };
	renderNavToast();
	if (navToastTimer) clearTimeout(navToastTimer);
	navToastTimer = setTimeout(() => {
		state.nav.toast = null;
		navToastTimer = 0;
		renderNavToast();
	}, NAV_TOAST_MS);
	return state.nav.toast;
}

let navToastKey = "";
function renderNavToast() {
	const el = $("navToast");
	if (!el) return;
	const t = state.nav.toast;
	if (!t) {
		navToastKey = "";
		if (el.classList) el.classList.add("hidden");
		return;
	}
	const html = `<svg class="icon sm"><use href="#${t.kind === "err" ? "i-alert" : "i-send"}"/></svg><span>${esc(t.text)}</span>`;
	if (html !== navToastKey) { navToastKey = html; el.innerHTML = html; }
	if (el.classList) {
		el.classList.remove("hidden");
		el.classList.toggle("err", t.kind === "err");
	}
}

/* ---- the pairing modal ---- */

function openNavModal(pending) {
	state.nav.modal = { code: "", error: "", busy: false, pending: pending || null };
	const set = $("settings");
	if (set && set.classList) set.classList.add("hidden");
	if (state.nav.token) navCheckStatus();
	renderNavModal();
	const input = $("navCode");
	if (input && input.focus) input.focus();
	return state.nav.modal;
}

function closeNavModal() {
	state.nav.modal = null;
	renderNavModal();
	return null;
}

function navModalHtml(m) {
	const paired = !!state.nav.token;
	if (paired) {
		const who = state.nav.player || "your player";
		const status = state.nav.online === false
			? esc(who) + " is offline right now — the directions will be refused until they log back in."
			: state.nav.online === null
				? "Checking the game connection…"
				: "Directions you send land on their in-game HUD straight away.";
		return `
			<div class="nm-card" role="dialog" aria-modal="true" aria-label="Game pairing">
				<div class="nm-title">Paired as ${esc(who)}</div>
				<div class="nm-body">${esc(status)}</div>
				${state.nav.label ? `<div class="nm-meta">This browser: ${esc(state.nav.label)}</div>` : ""}
				${m.error ? `<div class="nm-error">${esc(m.error)}</div>` : ""}
				<div class="nm-actions">
					<button class="nm-unpair" type="button">Unpair</button>
					<button class="nm-done" type="button">Done</button>
				</div>
			</div>`;
	}
	return `
		<div class="nm-card" role="dialog" aria-modal="true" aria-label="Pair with the game">
			<div class="nm-title">Send directions to your game</div>
			<div class="nm-body">In Minecraft, run <b>/navpair</b>. The game answers with a six-character code — type it here and this browser can push journeys straight to your HUD. In game, <b>/navpair list</b> shows your paired browsers and <b>/navpair revoke &lt;n&gt;</b> removes one.</div>
			${DEMO ? `<div class="nm-meta">Demo mode — there is no server; the code is <b>${DEMO_PAIR_CODE}</b>.</div>` : ""}
			<input id="navCode" class="nm-code" type="text" maxlength="${PAIR_CODE_LEN}" placeholder="ABC234"
				autocomplete="off" autocapitalize="characters" spellcheck="false" aria-label="Pairing code"
				value="${esc(m.code || "")}">
			${m.error ? `<div class="nm-error">${esc(m.error)}</div>` : ""}
			<div class="nm-actions">
				<button class="nm-cancel" type="button">Cancel</button>
				<button class="nm-pair" type="button"${m.busy || !pairCodeValid(m.code) ? " disabled" : ""}>${m.busy ? "Pairing…" : "Pair"}</button>
			</div>
		</div>`;
}

let navModalKey = "";
function renderNavModal() {
	const el = $("navModal");
	if (!el) return;
	const m = state.nav.modal;
	if (!m) {
		navModalKey = "";
		if (el.classList) el.classList.add("hidden");
		return;
	}
	const html = navModalHtml(m);
	if (html !== navModalKey) {
		navModalKey = html;
		el.innerHTML = html;
		wireNavModal(el);
	}
	if (el.classList) el.classList.remove("hidden");
}

function wireNavModal(el) {
	if (!el.querySelector) return;
	const q = (sel) => el.querySelector(sel);
	const cancel = q(".nm-cancel");
	if (cancel) cancel.onclick = () => closeNavModal();
	const done = q(".nm-done");
	if (done) done.onclick = () => closeNavModal();
	const unpair = q(".nm-unpair");
	if (unpair) unpair.onclick = () => {
		navClearToken();
		navModalKey = "";           // paired -> unpaired is a different card entirely
		renderNavModal();
		renderPairRow();
		renderOptions();
	};
	const pair = q(".nm-pair");
	if (pair) pair.onclick = () => navModalSubmit();
	const input = q("#navCode") || $("navCode");
	if (input && input.addEventListener) {
		input.addEventListener("input", () => {
			const clean = sanitisePairCode(input.value);
			input.value = clean;
			navModalSetCode(clean);
		});
		input.addEventListener("keydown", (e) => {
			if (!e) return;
			if (e.key === "Enter") { if (e.preventDefault) e.preventDefault(); navModalSubmit(); }
			else if (e.key === "Escape") { if (e.preventDefault) e.preventDefault(); closeNavModal(); }
		});
	}
}

/** Typing only re-renders the Pair button's enabled-ness, never the input under a caret. */
function navModalSetCode(code) {
	const m = state.nav.modal;
	if (!m) return null;
	m.code = code;
	m.error = "";
	const btn = $("navModal") && $("navModal").querySelector ? $("navModal").querySelector(".nm-pair") : null;
	if (btn) btn.disabled = m.busy || !pairCodeValid(code);
	const err = $("navModal") && $("navModal").querySelector ? $("navModal").querySelector(".nm-error") : null;
	if (err && err.classList) err.classList.add("hidden");
	return m;
}

async function navModalSubmit() {
	const m = state.nav.modal;
	if (!m || m.busy || state.nav.token) return null;
	if (!pairCodeValid(m.code)) {
		m.error = "Enter the six-character code the game showed you.";
		navModalKey = "";
		renderNavModal();
		return m;
	}
	m.busy = true;
	navModalKey = "";
	renderNavModal();
	const res = await navPair(m.code);
	const still = state.nav.modal;
	if (res.ok) {
		const pending = still && still.pending;
		if (still) { still.busy = false; still.error = ""; }
		navModalKey = "";
		renderNavModal();
		renderPairRow();
		renderOptions();
		navShowToast("Paired as " + (state.nav.player || "your player"), "ok");
		// the rider pressed "Send to game" and got a pairing card instead: finish the
		// job they actually asked for
		if (pending) { closeNavModal(); await navSend(pending); }
		return res;
	}
	if (still) { still.busy = false; still.error = res.error; }
	navModalKey = "";
	renderNavModal();
	return res;
}

/* ---- the settings row (Me > Game HUD) ---- */

function pairRowHtml() {
	if (!state.nav.token) {
		return `<button class="pair-btn" type="button" data-act="pair">Pair with game</button>`;
	}
	const dotClass = state.nav.online === false ? "off" : state.nav.online === null ? "unknown" : "";
	return `<span class="pair-who"><i class="pair-dot ${dotClass}"></i>${esc(state.nav.player || "Paired")}</span>`
		+ `<button class="pair-btn" type="button" data-act="unpair">Unpair</button>`;
}

let pairRowKey = "";
function renderPairRow() {
	const el = $("pairCtl");
	if (!el) return;
	const html = pairRowHtml();
	if (html === pairRowKey) return;
	pairRowKey = html;
	el.innerHTML = html;
	const btns = el.querySelectorAll ? el.querySelectorAll(".pair-btn") : [];
	for (const b of btns) {
		b.onclick = (e) => {
			if (e && e.stopPropagation) e.stopPropagation();
			if (b.dataset.act === "unpair") {
				navClearToken();
				renderPairRow();
				renderOptions();
			} else {
				openNavModal(null);
			}
		};
	}
}

/** Boot: paint the pairing controls and quietly re-validate whatever token we kept. */
function navBoot() {
	renderPairRow();
	renderNavToast();
	renderNavModal();
	// clicking the scrim (never the card on it) dismisses, the same as Esc
	const el = $("navModal");
	if (el && el.addEventListener) {
		el.addEventListener("click", (e) => { if (e && e.target === el) closeNavModal(); });
	}
	if (state.nav.token) navCheckStatus();
	return state.nav;
}

/* ============================================================================
 * 10b. station panel
 * ==========================================================================
 * Clicking a station glyph or its name opens the right-hand card: who serves it, when
 * the next trains go, where its exits come out, which platforms are step-free, and the
 * two buttons that put it into the planner.
 */

/** Distinct lines serving a station, with their normalised bullets. */
function stationLines(st) {
	const out = new Map();
	for (const pid of (st && st.platformIds) || []) {
		const pl = state.platforms.get(pid);
		if (!pl) continue;
		for (const rid of pl.routeIds || []) {
			const rt = state.routes.get(rid);
			if (!rt || (rt.hidden && !state.prefs.showHidden) || !modeVisible(rt.mode || "train")) continue;
			let e = out.get(rt.hex);
			if (!e) { e = { hex: rt.hex, labels: [] }; out.set(rt.hex, e); }
			const label = routeServiceLabel(rt);
			if (label && !e.labels.includes(label)) e.labels.push(label);
		}
	}
	return [...out.values()];
}

/** The destination a rider reads off a departure row. */
function routeHeadsign(rt) {
	if (!rt) return "";
	return firstLang(rt.dest || routeDest(rt.name) || "") || routeBase(rt.name) || "";
}

/**
 * LIVE DEPARTURES for one station, grouped by LINE and sorted soonest-first.
 *
 * Each (platform, route) contributes up to `perDest` departures through nextDeparture():
 * a streamed train inbound to that platform is `live: true`; otherwise the headway model
 * fills in and the row is flagged schedule-derived. A route with NEITHER (no live train
 * and no headway — nextDeparture's `estimated` tier) contributes NOTHING: a fabricated
 * "in 5 min" on a departure board is worse than an empty board. A platform that is the
 * route's LAST stop contributes nothing either — nobody departs from a terminus arrival.
 *
 * @returns [{hex, labels:[], routeIds:[], soonestMs, rows:[{destination, label, routeId,
 *           platformId, platformName, departMs, waitMs, live, vehicleId}]}]
 */
function buildDepartureGroups(graph, stationId, atMs, opts) {
	const st = state.stations.get(stationId);
	if (!st || !graph) return [];
	const perDest = (opts && opts.perDest) || DEPARTURES_PER_DEST;
	const horizon = (opts && opts.horizonMs) || DEPARTURE_HORIZON_MS;
	const at = Number.isFinite(atMs) ? atMs : now();
	const groups = new Map();

	for (const pid of st.platformIds || []) {
		const pl = state.platforms.get(pid);
		if (!pl) continue;
		for (const rid of pl.routeIds || []) {
			const rt = state.routes.get(rid);
			if (!rt || (rt.hidden && !state.prefs.showHidden) || !modeVisible(rt.mode || "train")) continue;
			const seq = routeSequence(graph, rid);
			const idx = seq ? seq.platformIndex.get(pid) : undefined;
			if (!seq || idx === undefined || idx >= seq.platforms.length - 1) continue;   // terminus arrival

			let g = groups.get(rt.hex);
			if (!g) { g = { hex: rt.hex, labels: [], routeIds: [], rows: [], soonestMs: Infinity }; groups.set(rt.hex, g); }
			const label = routeServiceLabel(rt);
			if (label && !g.labels.includes(label)) g.labels.push(label);
			if (!g.routeIds.includes(rid)) g.routeIds.push(rid);

			let prev = null;
			for (let k = 0; k < perDest; k++) {
				let d;
				if (!prev) d = nextDeparture(graph, pid, rid, at);
				else if (prev.live) d = nextDeparture(graph, pid, rid, prev.departMs + 1000);
				// a second SCHEDULED departure is simply one headway after the first —
				// re-asking nextDeparture would re-apply the phase and drift
				else if (rt.headwayMs > 0) d = { departMs: prev.departMs + rt.headwayMs, live: false, vehicleId: null, estimated: false };
				else break;
				if (!d || d.estimated || d.departMs - at > horizon) break;
				g.rows.push({
					routeId: rid, label, destination: routeHeadsign(rt),
					platformId: pid, platformName: pl.name || "",
					departMs: d.departMs, waitMs: Math.max(0, d.departMs - at),
					live: !!d.live, vehicleId: d.vehicleId || null,
				});
				prev = d;
			}
		}
	}

	const out = [];
	for (const g of groups.values()) {
		g.rows.sort((a, b) => a.departMs - b.departMs || String(a.destination).localeCompare(String(b.destination)));
		// two routes can share a destination (a local and an express to the same place):
		// the cap is per DESTINATION, which is what the rider is choosing between
		const seen = new Map();
		g.rows = g.rows.filter((r) => {
			const n = (seen.get(r.destination) || 0) + 1;
			seen.set(r.destination, n);
			return n <= perDest;
		});
		if (!g.rows.length) continue;
		g.labels.sort();
		g.soonestMs = g.rows[0].departMs;
		out.push(g);
	}
	out.sort((a, b) => a.soonestMs - b.soonestMs || a.hex.localeCompare(b.hex));
	return out;
}

/**
 * EXITS. mapdata may carry `exits: [{name, destinations:[…]}]`; older payloads have no
 * such field at all, which is why the panel omits the whole section rather than drawing
 * an empty one. Rows read "Exit A · Main St, Transit Museum".
 */
function stationExits(station) {
	const out = [];
	for (const e of (station && station.exits) || []) {
		if (!e) continue;
		const name = firstLang(String(e.name == null ? "" : e.name)).trim();
		const destinations = (e.destinations || [])
			.map((d) => firstLang(String(d == null ? "" : d)).trim())
			.filter(Boolean);
		if (!name && !destinations.length) continue;
		// MTR's own exit editor stores bare letters ("A", "B1"), so the word is ours to
		// add — but never twice, for a server that already spells it out
		const label = !name ? "Exit" : /exit/i.test(name) ? name : "Exit " + name;
		out.push({ name: label, destinations, text: destinations.length ? label + " · " + destinations.join(", ") : label });
	}
	return out;
}

/**
 * ACCESSIBILITY. `accessiblePlatforms` is the server's explicit list; when the station
 * flag is set and that list is empty, EVERY platform is step-free and the panel says so
 * in one line instead of listing them. Otherwise each platform is listed with its mark.
 */
function stationAccessibility(station) {
	if (!station) return { stepFree: false, all: false, platforms: [] };
	const listed = Array.isArray(station.accessiblePlatforms) ? station.accessiblePlatforms : null;
	const platforms = (station.platformIds || []).map((id) => {
		const pl = state.platforms.get(id);
		return {
			id, name: (pl && pl.name) || id,
			accessible: !!((pl && pl.accessible) || (listed && listed.includes(id))),
		};
	});
	const all = !!station.accessible && !(listed && listed.length);
	return { stepFree: !!station.accessible || platforms.some((p) => p.accessible), all, platforms };
}

/** Everything the station panel renders, as plain values. */
function stationPanelData(stationId, partId, atMs) {
	const st = state.stations.get(stationId);
	if (!st) return null;
	if (!state.plan.graph) state.plan.graph = buildGraph();
	const part = partId ? st.parts.find((p) => p.id === partId) : null;
	return {
		stationId, partId: partId || null,
		name: st.display,
		partName: part ? (part.name || part.sub || "") : "",
		accessible: !!st.accessible,
		lines: stationLines(st),
		groups: buildDepartureGroups(state.plan.graph, stationId, Number.isFinite(atMs) ? atMs : now()),
		exits: stationExits(st),
		access: stationAccessibility(st),
	};
}

function departureWaitText(waitMs) {
	const m = Math.round(waitMs / 60000);
	return m <= 0 ? "now" : "in " + m + " min";
}

function departuresHtml(data) {
	if (!data.groups.length) {
		return '<div class="st-empty">No departures to show from here right now.</div>';
	}
	return data.groups.map((g) => `
		<div class="st-group">
			<div class="st-grouphead" style="--line:${g.hex}">
				${g.labels.map((l) => `<span class="bullet sm" style="background:${g.hex}">${esc(l)}</span>`).join("")
					|| `<span class="fc-swatch" style="background:${g.hex}"></span>`}
			</div>
			${g.rows.map((r) => `
				<div class="st-dep${r.live ? " live" : " sched"}">
					<span class="st-dest">${esc(r.destination || "—")}</span>
					<span class="st-plat">${r.platformName ? "Plat " + esc(r.platformName) : ""}</span>
					<span class="st-when">${r.live ? '<span class="dot pulse"></span>' : ""}${esc(departureWaitText(r.waitMs))}</span>
				</div>`).join("")}
		</div>`).join("");
}

function stationPanelHtml(data) {
	// each bullet opens that line's view (feature 4)
	const bullets = data.lines.map((l) => (l.labels.length ? l.labels : [""])
		.map((lb) => `<span class="bullet sm line-bullet" role="button" tabindex="0" title="Show this line"
			data-line="${esc(l.hex)}" style="background:${l.hex}">${esc(lb)}</span>`).join("")).join("");
	const acc = data.access;
	const accBody = acc.all
		? `<div class="st-accline">${ACCESS_IMG}All platforms step-free</div>`
		: acc.platforms.length
			? `<div class="st-platlist">${acc.platforms.map((p) => `
				<div class="st-platrow${p.accessible ? " yes" : ""}">
					<span>Platform ${esc(p.name)}</span>
					${p.accessible ? ACCESS_IMG : '<span class="st-no">not step-free</span>'}
				</div>`).join("")}</div>`
			: `<div class="st-accline">No step-free information published.</div>`;
	return `
		<div class="st-head">
			<div class="st-title">
				<h2>${esc(data.name)}${data.accessible ? " " + ACCESS_IMG : ""}</h2>
				${data.partName ? `<div class="st-part">${esc(data.partName)}</div>` : ""}
				<div class="st-bullets">${bullets}</div>
			</div>
			<button class="st-close" type="button" title="Close" aria-label="Close">×</button>
		</div>
		<div class="st-sec">
			<div class="st-sectitle">Live departures</div>
			<div class="st-departures">${departuresHtml(data)}</div>
		</div>
		${data.exits.length ? `
		<div class="st-sec">
			<div class="st-sectitle">Exits</div>
			${data.exits.map((e) => `<div class="st-exit"><b>${esc(e.name)}</b>${
				e.destinations.length ? ' <span class="st-exitdest">· ' + esc(e.destinations.join(", ")) + "</span>" : ""}</div>`).join("")}
		</div>` : ""}
		<div class="st-sec">
			<div class="st-sectitle">Accessibility</div>
			${accBody}
		</div>
		<div class="st-actions">
			<button class="st-btn" type="button" data-plan="from">Plan from here</button>
			<button class="st-btn" type="button" data-plan="to">Plan to here</button>
		</div>`;
}

function openStationPanel(stationId, partId) {
	if (!state.stations.has(stationId)) return;
	state.stationPanel = { stationId, partId: partId || null, openedAt: now() };
	renderStationPanel();
}

function closeStationPanel() {
	if (!state.stationPanel) return;
	state.stationPanel = null;
	const el = $("stationPanel");
	if (el && el.classList) el.classList.add("hidden");
}

function renderStationPanel() {
	const el = $("stationPanel");
	if (!el) return;
	const sp = state.stationPanel;
	if (!sp) { if (el.classList) el.classList.add("hidden"); return; }
	const data = stationPanelData(sp.stationId, sp.partId, now());
	if (!data) { closeStationPanel(); return; }
	el.innerHTML = stationPanelHtml(data);
	if (el.classList) el.classList.remove("hidden");
	const close = el.querySelector ? el.querySelector(".st-close") : null;
	if (close) close.onclick = () => closeStationPanel();
	const buttons = el.querySelectorAll ? el.querySelectorAll("[data-plan]") : [];
	for (const b of buttons) {
		b.onclick = () => planFromPanel(b.dataset ? b.dataset.plan : "from");
	}
	for (const b of (el.querySelectorAll ? el.querySelectorAll(".line-bullet") : [])) {
		b.onclick = () => { if (b.dataset && b.dataset.line) selectLine(b.dataset.line); };
	}
}

/** The panel's two planner buttons — the only thing that fills the fields now. */
function planFromPanel(which) {
	const sp = state.stationPanel;
	if (!sp) return;
	pickStation(sp.stationId, sp.partId, which === "to" ? "to" : "from");
	// replan() clears the selection, which re-renders the panel's live rows honestly
	renderStationPanel();
}

/** Called by the live ticker: departures age out every second. */
function refreshStationDepartures() {
	const el = $("stationPanel");
	if (!el || !state.stationPanel) return;
	const box = el.querySelector ? el.querySelector(".st-departures") : null;
	if (!box) return;
	const data = stationPanelData(state.stationPanel.stationId, state.stationPanel.partId, now());
	if (data) box.innerHTML = departuresHtml(data);
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
	/** Standing actions above the station matches: pick on the map, use my location. */
	const actions = () => {
		const rows = [{ action: "map", text: which === "to" ? "Choose destination on the map" : "Choose start on the map", icon: "i-pin" }];
		if (which === "from" && state.selfPlayer && selfPlayerPos()) {
			rows.unshift({ action: "me", text: "Plan from my location", icon: "i-locate" });
		}
		return rows;
	};
	const render = () => {
		entries = searchEntries(input.value);
		const acts = actions();
		const head = acts.map((a) => `
			<div class="ac-row ac-action" data-action="${a.action}">
				<svg class="icon sm"><use href="#${a.icon}"/></svg><span>${esc(a.text)}</span>
			</div>`).join("");
		if (!entries.length) {
			list.innerHTML = head + '<div class="ac-empty">No matching station</div>';
		} else {
			list.innerHTML = head + entries.map((e, i) => `
				<div class="ac-row${i === active ? " active" : ""}" data-i="${i}">
					<span>${esc(e.label)}</span>
					${e.sub ? `<span class="sub">${esc(e.sub)}</span>` : ""}
					${e.accessible ? ACCESS_IMG : ""}
					<span class="dots">${e.colors.slice(0, 5).map((c) => `<i style="background:${c}"></i>`).join("")}</span>
				</div>`).join("");
		}
		for (const row of list.querySelectorAll(".ac-row")) {
			row.onmousedown = (ev) => {
				ev.preventDefault();
				const act = row.dataset ? row.dataset.action : "";
				if (act === "me") { list.classList.add("hidden"); planFromMyLocation(); return; }
				if (act === "map") { list.classList.add("hidden"); armMapPick(which); return; }
				choose(entries[parseInt(row.dataset.i, 10)]);
			};
		}
		list.classList.remove("hidden");
	};
	const choose = (e) => {
		if (!e) return;
		disarmMapPick();
		state.plan[which] = { stationId: e.stationId, partId: e.partId };
		input.value = entryText(e);
		list.classList.add("hidden");
		active = -1;
		replan();
	};
	// clicking the FIELD arms "click the map"; typing in it goes straight back to search
	input.addEventListener("focus", () => { armMapPick(which); render(); });
	input.addEventListener("input", () => {
		active = -1;
		if (state.mapPick === which) disarmMapPick();
		if (!input.value.trim()) clearPlanField(which);
		render();
	});
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

/**
 * Put a station into the planner. `which` ("from" / "to") comes from the station panel's
 * two buttons; without it the original fill-the-empty-field-first rule applies (From
 * first, then To), which is what the station-panel buttons replaced on the map itself.
 */
function pickStation(stationId, partId, which) {
	const p = state.plan;
	disarmMapPick();
	if (which === "from") p.from = { stationId, partId };
	else if (which === "to") p.to = { stationId, partId };
	else if (!p.from) p.from = { stationId, partId };
	else p.to = { stationId, partId };
	syncFields();
	replan();
}

function syncFields() {
	const label = (sel) => {
		if (!sel) return "";
		if (sel.point) return sel.label || "Dropped pin";
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
	p.planError = null;
	// a line view is not a journey selection and must survive a re-plan (the minute
	// ticker calls replan while the rider is reading a line)
	if (state.selection && state.selection.kind !== "line") clearSelection();
	if (p.from && p.to) {
		if (!p.graph) p.graph = buildGraph();
		const res = planEndpoints(p.graph, p.from, p.to, p.prefs, departAtMs());
		p.journeys = res.journeys;
		p.planError = res.error;
		// while a line is open the map belongs to that line: fill the option list but do
		// not steal the map back for a journey the rider did not just ask for
		if (p.journeys.length && !(state.selection && state.selection.kind === "line")) {
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
	// LINE VIEW (feature 4) takes the option list over while it is open; the planner is
	// still there underneath and comes straight back when the line is closed.
	if (state.selection && state.selection.kind === "line") { renderLineCard(); return; }
	$("liveOpts").textContent = p.journeys.length + " OPTION" + (p.journeys.length === 1 ? "" : "S");
	const hint = $("plannerHint");
	if (!p.from || !p.to) {
		box.innerHTML = "";
		hint.textContent = "Pick a start and a destination — or click a station on the map and use Plan from here.";
		hint.classList.remove("hidden");
		return;
	}
	if (!p.journeys.length) {
		box.innerHTML = "";
		hint.textContent = p.planError ? p.planError
			: p.prefs.stepFree
				? "No step-free journey found — try clearing the step-free filter."
				: "No journey found between these two points.";
		hint.classList.remove("hidden");
		return;
	}
	hint.classList.add("hidden");
	box.innerHTML = p.journeys.map((j, i) => optionCard(j, i === p.selectedIndex)).join("");
	for (const card of box.querySelectorAll(".opt")) {
		card.onclick = () => selectOption(parseInt(card.dataset.i, 10));
		const start = card.querySelector ? card.querySelector(".opt-start") : null;
		if (start) {
			start.onclick = (e) => {
				if (e && e.stopPropagation) e.stopPropagation();
				const j = p.journeys[parseInt(card.dataset.i, 10)];
				if (state.tracking && state.tracking.journey && j && state.tracking.journey.signature === j.signature) {
					stopTracking("user");
				} else if (j) {
					startTracking(j);
				}
			};
		}
		const send = card.querySelector ? card.querySelector(".opt-send") : null;
		if (send) {
			send.onclick = (e) => {
				if (e && e.stopPropagation) e.stopPropagation();
				const j = p.journeys[parseInt(card.dataset.i, 10)];
				if (j) navSendPressed(j);
			};
		}
	}
}

/** The bullet a planner row shows for a ride leg: normalised, direction stripped. */
function legLabel(leg) {
	return routeServiceLabel({ number: leg.number, display: leg.routeName, name: leg.routeName });
}

/** The same, for the service a through run hands the rider on to. */
function continuationLabel(c) {
	return routeServiceLabel({ number: c.number, display: c.routeName, name: c.routeName });
}

/**
 * The through-run note rows of one ride leg (feature 7): "Continues as ⑤ toward X",
 * drawn INSIDE the boarding row, because the rider does nothing at all here.
 */
function throughRowsHtml(leg) {
	return (leg.continuations || []).map((c) => `
		<div class="thru">
			<span class="thru-key">Continues as</span>
			<span class="bullet sm" style="background:${c.color}">${esc(continuationLabel(c))}</span>
			<span class="thru-dest">${c.headsign ? "toward " + esc(firstLang(c.headsign)) : esc(c.routeName || "")}</span>
			${c.station ? `<span class="thru-at">at ${esc(c.station)}</span>` : ""}
		</div>`).join("");
}

function optionCard(j, selected) {
	const seq = [];
	for (const leg of j.legs) {
		if (leg.kind === "ride") {
			// a through run shows both bullets joined by a hairline "stays aboard" link,
			// never by the transfer chevron — the rider does not get off
			seq.push([`<span class="bullet" style="background:${leg.color}">${esc(legLabel(leg))}</span>`]
				.concat((leg.continuations || []).map((c) =>
					`<span class="thru-join" title="stays aboard">·</span><span class="bullet" style="background:${c.color}">${
						esc(continuationLabel(c))}</span>`)).join(""));
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
		${sentMarkHtml(j)}
		${selected ? itineraryHtml(j) : ""}
		${selected ? optionActionsHtml(j) : ""}
	</div>`;
}

/**
 * The "in game" marker (section 10e). It rides the CARD, not the button, so it is still
 * there after the rider expands a different option — and it disappears on its own when
 * the plan changes, because a new plan has new signatures and none of them match.
 */
function sentMarkHtml(j) {
	if (!state.nav.sentSig || !j || j.signature !== state.nav.sentSig) return "";
	return `<div class="opt-ingame"><svg class="icon sm"><use href="#i-send"/></svg>In ${
		esc(state.nav.player ? state.nav.player + "’s" : "your")} game</div>`;
}

/**
 * "Start" turns the selected option into live guidance (feature 6). It only appears when
 * there is a self player to follow — without GPS there is nothing to track — and reads
 * "Stop" while this very journey is the one being tracked.
 */
function startButtonHtml(j) {
	if (!state.selfPlayer || !selfPlayerPos()) return "";
	const tracked = !!(state.tracking && state.tracking.journey
		&& state.tracking.journey.signature === j.signature);
	return `<button class="opt-start${tracked ? " on" : ""}" type="button">${
		tracked ? "Stop guidance" : "Start"}</button>`;
}

/**
 * "Send to game" (section 10e) sits BESIDE Start: tracking the journey on the map and
 * being walked through it in game are complementary, so both are always available.
 * Unpaired, the button opens the pairing card rather than hiding itself — the rider
 * should be able to find the feature before they have set it up.
 */
function sendButtonHtml(j) {
	const sent = !!state.nav.sentSig && j.signature === state.nav.sentSig;
	const busy = !!state.nav.sending;
	const label = busy ? "Sending…" : sent ? "Sent · send again" : "Send to game";
	return `<button class="opt-send${sent ? " sent" : ""}" type="button"${busy ? " disabled" : ""}
		title="${state.nav.token ? "Send these directions to " + esc(state.nav.player || "your game") : "Pair this browser with your game"}"
		aria-label="Send directions to my game"><svg class="icon sm"><use href="#i-send"/></svg>${label}</button>`;
}

/** The selected card's button row. */
function optionActionsHtml(j) {
	return `<div class="opt-actions">${startButtonHtml(j)}${sendButtonHtml(j)}</div>`;
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
					<div class="stop-name">${esc(legEndLabel(leg, "from"))}</div>
					${pendingWalk ? walkChipHtml(pendingWalk, true) : ""}
					<div class="stop-sub">${sub}</div>
					${throughRowsHtml(leg)}
					<div class="ride">${leg.stopCount} stop${leg.stopCount === 1 ? "" : "s"} · ${fmtMin((leg.arrMs - leg.depMs) / 1000)}</div>
				</div>`);
			pendingWalk = null;
		} else {
			const next = j.legs[i + 1];
			// a walk BETWEEN two rides is a transfer and folds into the next boarding row;
			// the walk off a dropped pin is not a transfer — it is the rider leaving home,
			// and it keeps its own row and its own "Walk 166 m · 2 min to Riverside" chip
			if (next && next.kind === "ride" && !leg.fromPoint) { pendingWalk = leg; return; }
			rows.push(`
				<div class="t">${fmtTime(leg.depMs)}</div>
				<div class="n"><span class="node" style="border-color:${PALETTE.ink2}"></span><span class="rail walk"></span></div>
				<div class="c">
					<div class="stop-name">${esc(legEndLabel(leg, "from"))}</div>
					${walkChipHtml(leg, false)}
					${leg.note && !leg.fromPoint && !leg.toPoint ? `<div class="stop-sub">${esc(leg.note)}</div>` : ""}
				</div>`);
		}
	});
	rows.push(`
		<div class="t">${fmtTime(j.arriveMs)}</div>
		<div class="n"><span class="node pin"></span></div>
		<div class="c"><div class="stop-name">${esc(j.toPoint ? (j.toName || "Dropped pin") : stationLabel(j.toStation, j.toPart))}</div></div>`);
	return `<div class="itin">${rows.join("")}</div>`;
}

/** A leg end's display name — a station (with its part) or a map point (feature 2). */
function legEndLabel(leg, which) {
	const point = which === "to" ? leg.toPoint : leg.fromPoint;
	if (point) return (which === "to" ? leg.toName : leg.fromName) || "Dropped pin";
	return which === "to" ? stationLabel(leg.toStation, leg.toPart) : stationLabel(leg.fromStation, leg.fromPart);
}

function walkChipHtml(leg, transfer) {
	const icon = leg.mode === "boat" ? "i-boat" : "i-walk";
	// a walk that starts or ends at a map point names where it lands — "Walk 240 m ·
	// 3 min to Museum" is the whole point of the leading/trailing leg
	const dest = leg.fromPoint || leg.toPoint ? legEndLabel(leg, "to") : "";
	const label = transfer
		? `Transfer · ${fmtMeters(leg.meters)} · ${fmtMin(leg.seconds)}`
		: dest
			? `Walk ${fmtMeters(leg.meters)} · ${fmtMin(leg.seconds)} to ${dest}`
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
		const pt = state.pointNodes.get(platformId);
		if (pt) return pt.xz;
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
			// a through-running leg is aboard several routes: all of them belong to the
			// selection, or the map would fade out the very train the rider is on
			for (const rid of legRouteIds(leg)) if (state.routes.get(rid)) routeIds.add(rid);
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

	// the LINE colours this journey rides: everything else's trains come off the map
	// (feature 2), which also means a followed train on another line is let go
	const lineHexes = selectionLineHexes({ journey });

	state.selection = { kind: "journey", journey, ribbonKeys, partIds, routeIds, walks, fallbacks, origin, dest, boardPlatform, lineHexes };
	if (state.follow) {
		const rec = state.vehicles.get(state.follow.vehicleId);
		if (!vehiclePassesSelection(rec, state.selection)) stopFollow("filtered");
	}
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
	// a through-running leg is several routes' worth of track under one boarding: light
	// each span with the same rule, then merge
	const spans = leg.spans && leg.spans.length > 1 ? leg.spans : null;
	if (spans) {
		const ids = [], fallbacks = [];
		for (const s of spans) {
			const r = legSegmentIds({ routeId: s.routeId, fromPlatform: s.fromPlatform, toPlatform: s.toPlatform });
			for (const id of r.ids) if (!ids.includes(id)) ids.push(id);
			fallbacks.push(...r.fallbacks);
		}
		return { ids, fallbacks };
	}
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

/* ============================================================================
 * 11c. LINE VIEW (feature 4)
 * ==========================================================================
 * Clicking a ribbon, an interlining bullet or a station-panel bullet asks "what IS this
 * line?". The answer reuses the journey machinery wholesale — a selection with
 * `kind: "line"` fades the rest of the network exactly the same way — and replaces the
 * option list with a line card: bullets, frequency, per-service chips, and the stop list
 * in order with the express-skipped stops carrying the same open ring the map draws.
 */

/**
 * Everything the line card renders, as plain values.
 *
 * The stop ORDER comes from the line's DOMINANT route — the service calling at the most
 * stops, which on a local/express pair is always the local, i.e. the full stop list. Ride
 * times are that route's own leg durations, so they are the times a rider on the all-stops
 * service actually experiences.
 */
function lineViewData(hex) {
	const line = state.lines.get(hex);
	if (!line) return null;
	const routes = line.routeIds.map((id) => state.routes.get(id))
		.filter((rt) => rt && (!rt.hidden || state.prefs.showHidden) && (rt.platforms || []).length >= 2);
	if (!routes.length) return null;
	const dominant = routes.slice().sort((a, b) =>
		(b.platforms || []).length - (a.platforms || []).length || (a.id < b.id ? -1 : 1))[0];

	const headways = routes.map((r) => r.headwayMs || 0).filter((h) => h > 0);
	const headwayMs = headways.length ? Math.min(...headways) : 0;

	const names = [];
	for (const rt of routes) {
		const n = routeBase(rt.name);
		if (n && !names.includes(n)) names.push(n);
	}
	// per-service chips: one per distinct (bullet, destination), so "4 IN"/"4 OU" read as
	// two directions of one service rather than as two lines
	const services = [], seen = new Set();
	for (const rt of routes) {
		const s = {
			routeId: rt.id, label: routeServiceLabel(rt), name: routeBase(rt.name),
			dest: firstLang(rt.dest || routeDest(rt.name) || ""), headwayMs: rt.headwayMs || 0,
			stops: (rt.platforms || []).length,
		};
		const k = s.label + "|" + s.dest;
		if (seen.has(k)) continue;
		seen.add(k);
		services.push(s);
	}

	const plats = dominant.platforms || [];
	const stops = [];
	for (let i = 0; i < plats.length; i++) {
		const pl = state.platforms.get(plats[i]);
		if (!pl) continue;
		const st = state.stations.get(pl.stationId);
		const part = st && pl.partId ? st.parts.find((p) => p.id === pl.partId) : null;
		const lg = i < plats.length - 1 ? state.legs.get(dominant.id + "|" + i) : null;
		stops.push({
			stationId: pl.stationId, partId: pl.partId || null, platformId: pl.id,
			name: st ? st.display : (pl.name || pl.id),
			sub: part && (part.name || part.sub) ? (part.name || part.sub) : "",
			// the SAME classifier the map's open rings come from (feature 1)
			skip: state.stopMarks.get(pl.partId) === false,
			accessible: !!pl.accessible,
			rideSeconds: lg ? lg.seconds : 0,
		});
	}

	return {
		hex, labels: line.serviceLabels.slice(), names, headwayMs,
		everyMin: headwayMs ? Math.max(1, Math.round(headwayMs / 60000)) : 0,
		services, dominantRouteId: dominant.id, stops,
	};
}

/** Open the line view: the line at full strength, everything else at PALETTE.dim. */
function selectLine(hex) {
	const data = lineViewData(hex);
	if (!data) return null;
	const ribbonKeys = new Set(), partIds = new Set(), routeIds = new Set();
	for (const seg of state.ribbons) {
		if (seg.hex !== hex) continue;
		ribbonKeys.add(seg.id);
		partIds.add(seg.partA);
		partIds.add(seg.partB);
	}
	// a station the line calls at whose segment was suppressed still belongs to the line
	for (const gl of state.glyphs) if (gl.colors.includes(hex)) partIds.add(gl.partId);
	for (const s of data.stops) if (s.partId) partIds.add(s.partId);
	const line = state.lines.get(hex);
	for (const id of (line && line.routeIds) || []) routeIds.add(id);

	state.selection = {
		kind: "line", hex, data, ribbonKeys, partIds, routeIds,
		walks: [], fallbacks: [], origin: null, dest: null, boardPlatform: null,
		lineHexes: new Set([String(hex).toLowerCase()]),
	};
	if (state.follow) {
		const rec = state.vehicles.get(state.follow.vehicleId);
		if (!vehiclePassesSelection(rec, state.selection)) stopFollow("filtered");
	}
	invalidateStatic();
	renderOptions();
	return state.selection;
}

function closeLineView() {
	if (state.selection && state.selection.kind === "line") clearSelection();
}

function lineCardHtml(d) {
	const bullets = (d.labels.length ? d.labels : [""])
		.map((l) => `<span class="bullet" style="background:${d.hex}">${esc(l)}</span>`).join("");
	const chips = d.services.length > 1
		? `<div class="lv-services">${d.services.map((s) => `
			<span class="lv-svc"><span class="bullet sm" style="background:${d.hex}">${esc(s.label)}</span>${
				s.dest ? "to " + esc(s.dest) : esc(s.name)}</span>`).join("")}</div>`
		: "";
	const stops = d.stops.map((s, i) => `
		<div class="lv-stop" data-station="${esc(s.stationId)}" data-part="${esc(s.partId || "")}">
			<div class="lv-mark">
				<span class="lv-dot${s.skip ? " open" : ""}" style="border-color:${d.hex}"></span>
				${i < d.stops.length - 1 ? `<span class="lv-rail" style="background:${d.hex}"></span>` : ""}
			</div>
			<div class="lv-body">
				<div class="lv-name">${esc(s.name)}${s.sub ? ` <span class="lv-sub">· ${esc(s.sub)}</span>` : ""}${
					s.accessible ? " " + ACCESS_IMG : ""}${s.skip ? ' <span class="lv-skip">local only</span>' : ""}</div>
				${s.rideSeconds ? `<div class="lv-ride">${esc(fmtMin(s.rideSeconds))}</div>` : ""}
			</div>
		</div>`).join("");
	return `
		<div class="lv">
			<div class="lv-head">
				<div class="lv-bullets">${bullets}</div>
				<div class="lv-title">
					<div class="lv-names">${esc(d.names.join(" · ") || "Line")}</div>
					<div class="lv-freq">${d.everyMin ? "every ~" + d.everyMin + " min" : "frequency not published"}</div>
				</div>
				<button class="lv-close" type="button" title="Back to the planner" aria-label="Back to the planner">×</button>
			</div>
			${chips}
			<div class="lv-sectitle">Stops</div>
			<div class="lv-stops">${stops}</div>
		</div>`;
}

function renderLineCard() {
	const sel = state.selection;
	if (!sel || sel.kind !== "line") return;
	const box = $("options");
	const hint = $("plannerHint");
	if (hint && hint.classList) hint.classList.add("hidden");
	const opts = $("liveOpts");
	if (opts) opts.textContent = (sel.data.stops.length || 0) + " STOP" + (sel.data.stops.length === 1 ? "" : "S");
	if (!box) return;
	box.innerHTML = lineCardHtml(sel.data);
	const close = box.querySelector ? box.querySelector(".lv-close") : null;
	if (close) close.onclick = () => closeLineView();
	for (const row of (box.querySelectorAll ? box.querySelectorAll(".lv-stop") : [])) {
		row.onclick = () => {
			const st = row.dataset ? row.dataset.station : "";
			const part = row.dataset && row.dataset.part ? row.dataset.part : null;
			if (st) openStationPanel(st, part);
		};
	}
}

function clearSelection() {
	if (!state.selection) return;
	const wasLine = state.selection.kind === "line";
	state.selection = null;
	// keep the panel honest: no journey is highlighted once the map selection goes.
	// Closing a LINE always rebuilds the panel too — the line card IS the option list
	// while it is open, so the planner has to be put back.
	if (state.plan.selectedIndex >= 0) {
		state.plan.selectedIndex = -1;
		renderOptions();
	} else if (wasLine) {
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
	// the two floating overlays age on the same tick: the card's speed/next stop and the
	// station panel's countdowns (only its departure rows are rebuilt, not the panel)
	if (state.trainCard) renderTrainCard();
	if (state.stationPanel) refreshStationDepartures();
	// "Leave now" walks forward with the clock: replan on each minute boundary (the
	// planner memoises by minute bucket, so this costs one search a minute, not one
	// a second) and keep the rider's expanded option if nothing actually changed.
	if (state.plan.when.mode === "now" && state.plan.from && state.plan.to) {
		const bucket = Math.floor(now() / 60000);
		if (bucket !== state.plan.lastBucket) replan(true);
	}
	followLiveOrigin();
	tickTracking();          // live journey guidance (feature 6)
}

/**
 * "Plan from my location" tracks the rider (feature 3). Re-planning on every 4 Hz sample
 * would be pointless churn, so the origin only moves — and the search only re-runs — once
 * the rider is PLAYER_REPLAN_MOVE blocks from where the current plan was made.
 */
function followLiveOrigin() {
	const from = state.plan.from;
	if (!from || !from.live || !from.point) return false;
	const p = selfPlayerPos();
	if (!p) return false;
	if (Math.hypot(p.x - from.point[0], p.z - from.point[1]) < PLAYER_REPLAN_MOVE) return false;
	from.point = [p.x, p.z];
	from.y = p.y;
	replan(true);
	return true;
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
			basemap: state.prefs.basemap === "satellite" ? "satellite" : "schematic",
			showPlayers: state.prefs.showPlayers !== false,
			selfPlayer: String(state.prefs.selfPlayer || ""),
			walkSpeed: walkSpeed(),
			hideTrains: !!state.prefs.hideTrains,
			hideOtherPlayers: !!state.prefs.hideOtherPlayers,
			satBrightness: clamp(state.prefs.satBrightness || 1, SAT_BRIGHT_MIN, SAT_BRIGHT_MAX),
			labelScale: labelScale(),
			hideLabels: !!state.prefs.hideLabels,
			// the game pairing (section 10e): the token IS the identity, so it is the one
			// thing here worth keeping — nothing else about the player is stored
			navToken: navTokenValid(state.nav.token) ? state.nav.token : "",
			navPlayer: String(state.nav.player || ""),
			navLabel: String(state.nav.label || ""),
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
	state.prefs.basemap = p.basemap === "satellite" ? "satellite" : "schematic";
	state.prefs.showPlayers = p.showPlayers !== false;
	// ?player= wins over the remembered name and rewrites it once the feed confirms it
	state.prefs.selfPlayer = PLAYER_PARAM || (typeof p.selfPlayer === "string" ? p.selfPlayer : "");
	state.prefs.walkSpeed = Number.isFinite(p.walkSpeed)
		? clamp(p.walkSpeed, WALK_SPEED_MIN, WALK_SPEED_MAX) : WALK_SPEED_DEFAULT;
	state.prefs.hideTrains = !!p.hideTrains;
	state.prefs.hideOtherPlayers = !!p.hideOtherPlayers;
	state.prefs.satBrightness = Number.isFinite(p.satBrightness)
		? clamp(p.satBrightness, SAT_BRIGHT_MIN, SAT_BRIGHT_MAX) : 1;
	state.prefs.labelScale = Number.isFinite(p.labelScale)
		? clamp(p.labelScale, LABEL_SCALE_MIN, LABEL_SCALE_MAX) : 1;
	state.prefs.hideLabels = !!p.hideLabels;
	// a malformed token is no token: it would only earn an "unknown token" round trip
	state.nav.token = navTokenValid(p.navToken) ? String(p.navToken) : "";
	state.nav.player = state.nav.token && typeof p.navPlayer === "string" ? p.navPlayer : "";
	state.nav.label = typeof p.navLabel === "string" ? p.navLabel : "";
	state.nav.online = null;
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

/** Basemap: the schematic paper ground, or the server's aerial scan (feature 5). */
function setBasemap(name) {
	const basemap = name === "satellite" ? "satellite" : "schematic";
	if (state.prefs.basemap === basemap) return basemap;
	state.prefs.basemap = basemap;
	savePrefs();
	invalidateStatic();
	maybeSatHint();
	return basemap;
}

function setPlayersVisible(on) {
	state.prefs.showPlayers = !!on;
	savePrefs();
}

/**
 * WALK SPEED. Every walk timing in the planner is derived from it, so a change is a
 * graph change: rebuild it, drop the memo (its keys do not mention the speed) and
 * re-plan, keeping the rider's expanded option when the answer comes back the same.
 */
function setWalkSpeed(v) {
	const next = clamp(Number(v) || WALK_SPEED_DEFAULT, WALK_SPEED_MIN, WALK_SPEED_MAX);
	if (next === state.prefs.walkSpeed) return next;
	state.prefs.walkSpeed = next;
	savePrefs();
	state.plan.graph = null;
	planMemo.graph = null;
	planMemo.entries.clear();
	replan(true);
	return next;
}

/** Layers: live vehicles off. Their hit targets go with them (hover, click, follow). */
function setTrainsHidden(on) {
	state.prefs.hideTrains = !!on;
	savePrefs();
	if (state.prefs.hideTrains) {
		closeTrainCard();
		stopFollow("filtered");
	}
	return state.prefs.hideTrains;
}

/** Layers: everybody except me. The self dot is never hidden by this. */
function setOtherPlayersHidden(on) {
	state.prefs.hideOtherPlayers = !!on;
	savePrefs();
	return state.prefs.hideOtherPlayers;
}

/** Satellite brightness: a multiplier over the THEME's own alpha, never a replacement. */
function setSatBrightness(v) {
	state.prefs.satBrightness = clamp(Number(v) || 1, SAT_BRIGHT_MIN, SAT_BRIGHT_MAX);
	savePrefs();
	if (satEnabled()) invalidateStatic();
	return state.prefs.satBrightness;
}

/** The alpha the satellite layer is actually drawn at. */
function satAlpha() {
	return clamp(PALETTE.satAlpha * clamp(Number(state.prefs.satBrightness) || 1, SAT_BRIGHT_MIN, SAT_BRIGHT_MAX), 0.05, 1);
}

function setLabelScale(v) {
	state.prefs.labelScale = clamp(Number(v) || 1, LABEL_SCALE_MIN, LABEL_SCALE_MAX);
	savePrefs();
	invalidateStatic();
	return state.prefs.labelScale;
}

function labelScale() {
	return clamp(Number(state.prefs.labelScale) || 1, LABEL_SCALE_MIN, LABEL_SCALE_MAX);
}

function setLabelsHidden(on) {
	state.prefs.hideLabels = !!on;
	savePrefs();
	invalidateStatic();
	return state.prefs.hideLabels;
}

/** "minecraft/the_nether" -> "The Nether"; "demo:overworld" -> "Overworld". */
function dimensionLabel(name, index) {
	const raw = String(name == null ? "" : name);
	const tail = raw.split(/[/:]/).filter(Boolean).pop() || "";
	const out = tail.replace(/_+/g, " ").trim()
		.split(/\s+/).filter(Boolean)
		.map((w) => w.charAt(0).toUpperCase() + w.slice(1))
		.join(" ");
	return out || ("Dimension " + ((index | 0) + 1));
}

/**
 * The dimension row: only worth showing when the server actually has more than one.
 * Switching goes through the ordinary loadDimension() flow, which drops the vehicles,
 * the selection and any live tracking before it fetches.
 */
function syncDimUi() {
	const row = $("dimRow"), sel = $("dimSel");
	const many = (state.dims || []).length > 1;
	if (row && row.classList) row.classList.toggle("hidden", !many);
	if (!sel) return;
	const html = (state.dims || []).map((d, i) =>
		`<option value="${i}"${i === state.dim ? " selected" : ""}>${esc(dimensionLabel(d, i))}</option>`).join("");
	if (sel.innerHTML !== html) sel.innerHTML = html;
	sel.value = String(state.dim);
}

/** One checkbox per mode the network actually contains, plus the players layer. */
function renderLayerList() {
	const box = $("layerList");
	if (!box) return;
	const modes = modesPresent();
	const modeRows = modes.map((m) => `
		<label class="layer"><input type="checkbox" data-mode="${esc(m)}"${modeVisible(m) ? " checked" : ""}>
		<span>${esc(modeLabel(m))}</span></label>`).join("")
		|| '<div class="set-hint">No routes loaded yet.</div>';
	box.innerHTML = modeRows + `
		<label class="layer"><input type="checkbox" data-layer="players"${state.prefs.showPlayers !== false ? " checked" : ""}>
		<span>Players</span></label>
		<label class="layer"><input type="checkbox" data-layer="hidetrains"${state.prefs.hideTrains ? " checked" : ""}>
		<span>Hide live trains</span></label>
		<label class="layer"><input type="checkbox" data-layer="hideothers"${state.prefs.hideOtherPlayers ? " checked" : ""}>
		<span>Hide other players</span></label>
		<label class="layer"><input type="checkbox" data-layer="hidelabels"${state.prefs.hideLabels ? " checked" : ""}>
		<span>Hide station names</span></label>`;
	for (const el of box.querySelectorAll("input[type=checkbox]")) {
		el.onchange = () => {
			const layer = el.dataset ? el.dataset.layer : "";
			if (layer === "players") setPlayersVisible(!!el.checked);
			else if (layer === "hidetrains") setTrainsHidden(!!el.checked);
			else if (layer === "hideothers") setOtherPlayersHidden(!!el.checked);
			else if (layer === "hidelabels") setLabelsHidden(!!el.checked);
			else setModeVisible(el.dataset.mode, !!el.checked);
		};
	}
}

function syncSettingsUi() {
	for (const b of document.querySelectorAll("#themeSeg button")) {
		b.classList.toggle("on", b.dataset.theme === state.prefs.theme);
	}
	for (const b of document.querySelectorAll("#basemapSeg button")) {
		b.classList.toggle("on", b.dataset.basemap === state.prefs.basemap);
	}
	const sl = $("lineScale");
	if (sl) sl.value = String(state.prefs.lineScale || 1);
	const out = $("lineScaleOut");
	if (out) out.textContent = (state.prefs.lineScale || 1).toFixed(2).replace(/0$/, "") + "x";

	const ws = $("walkSpeed");
	if (ws) ws.value = String(walkSpeed());
	const wsOut = $("walkSpeedOut");
	if (wsOut) wsOut.textContent = walkSpeed().toFixed(1) + " m/s";

	const sb = $("satBright");
	if (sb) sb.value = String(state.prefs.satBrightness || 1);
	const sbOut = $("satBrightOut");
	if (sbOut) sbOut.textContent = Math.round((state.prefs.satBrightness || 1) * 100) + "%";

	const ls = $("labelScale");
	if (ls) ls.value = String(labelScale());
	const lsOut = $("labelScaleOut");
	if (lsOut) lsOut.textContent = labelScale().toFixed(2).replace(/0$/, "") + "x";

	syncDimUi();
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
	for (const b of document.querySelectorAll("#basemapSeg button")) {
		b.onclick = () => { setBasemap(b.dataset.basemap); syncSettingsUi(); };
	}
	const self = $("selfSel");
	if (self) {
		self.onchange = () => {
			state.selfManual = true;
			state.prefs.selfPlayer = self.value || "";
			savePrefs();
			resolveSelfPlayer();
			syncSelfUi();
			renderOptions();
		};
	}
	const dim = $("dimSel");
	if (dim) {
		dim.onchange = () => {
			const n = parseInt(dim.value, 10);
			if (!Number.isFinite(n) || n === state.dim) return;
			if (DEMO) { syncDimUi(); return; }        // the demo city is one world
			loadDimension(n);
		};
	}
	const slider = (id, min, max, step, apply) => {
		const el = $(id);
		if (!el) return;
		el.min = String(min);
		el.max = String(max);
		el.step = String(step);
		el.oninput = () => { apply(el.value); syncSettingsUi(); };
	};
	slider("lineScale", LINE_SCALE_MIN, LINE_SCALE_MAX, 0.05, setLineScale);
	slider("walkSpeed", WALK_SPEED_MIN, WALK_SPEED_MAX, 0.1, setWalkSpeed);
	slider("satBright", SAT_BRIGHT_MIN, SAT_BRIGHT_MAX, 0.05, setSatBrightness);
	slider("labelScale", LABEL_SCALE_MIN, LABEL_SCALE_MAX, 0.05, setLabelScale);
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
 * walkSpeed() (the rider's own setting, 4.3 m/s by default) plus a WALK_BUFFER_S (30 s)
 * buffer; when platformDistances is
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
			xz: pl.xz, y: pl.y || 0, accessible: !!pl.accessible, routeIds: pl.routeIds.slice(),
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
	const pushTransfer = (aId, bId, meters, street) => {
		const a = nodes.get(aId), b = nodes.get(bId);
		if (!a || !b || aId === bId) return;
		transferEdges.push({
			from: aId, to: bId, meters,
			seconds: walkSeconds(meters),
			walk: true, accessible: a.accessible && b.accessible,
			sameStation: a.stationId === b.stationId, samePart: a.partId === b.partId,
			street: !!street,
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

	// CROSS-STREET TRANSFERS (feature 2): the same pairs the map draws as dotted
	// connectors, computed once with the geometry (see streetTransferPairs).
	for (const pair of streetTransferPairs()) {
		pushTransfer(pair.a, pair.b, pair.meters, true);
		pushTransfer(pair.b, pair.a, pair.meters, true);
	}

	const adjacency = new Map();
	const adj = (id) => {
		let a = adjacency.get(id);
		if (!a) { a = { rides: [], transfers: [] }; adjacency.set(id, a); }
		return a;
	};
	for (const e of rideEdges) adj(e.from).rides.push(e);
	for (const e of transferEdges) adj(e.from).transfers.push(e);

	/* THROUGH RUNNING (feature 7). "platform|fromRouteId" -> Set(toRouteId): a train of
	 * `from` standing at `platform` carries on as `to`. Only pairs whose routes are both
	 * in the graph count — a through run onto a hidden depot move is not a service a
	 * rider can stay aboard for. */
	const through = new Map();
	const routeInGraph = new Set(rideEdges.map((e) => e.routeId));
	for (const t of state.throughRuns || []) {
		if (!nodes.has(t.platform) || !routeInGraph.has(t.from) || !routeInGraph.has(t.to)) continue;
		const key = t.platform + "|" + t.from;
		let set = through.get(key);
		if (!set) { set = new Set(); through.set(key, set); }
		set.add(t.to);
	}

	return { nodes, rideEdges, transferEdges, byStation, adjacency, through, stations: state.stations };
}

/* ----------------------------------------------------------------------------
 * 12-b. POINT-TO-POINT (feature 2) — an arbitrary map point as an endpoint
 * --------------------------------------------------------------------------
 * A dropped pin (or the rider's live position) becomes a virtual graph node joined by
 * WALK edges to the platforms of its nearest stations. The graph is NOT mutated: the
 * search gets a shallow overlay that shares every untouched Map entry, so the cached
 * base graph (the station panel, the memoised station-to-station queries) stays clean
 * and a re-plan after the rider moves 32 blocks costs one small overlay, not a rebuild.
 * ------------------------------------------------------------------------- */

/** 3D walk distance: a stairs-only interchange is not a 0 m walk. */
function walkDistance3(a, b) {
	const dy = Number.isFinite(a.y) && Number.isFinite(b.y) ? a.y - b.y : 0;
	return Math.hypot(a.xz[0] - b.xz[0], a.xz[1] - b.xz[1], dy);
}

/** The rider's walking speed in m/s (settings slider, clamped, never 0). */
function walkSpeed() {
	const v = Number(state.prefs.walkSpeed);
	return Number.isFinite(v) ? clamp(v, WALK_SPEED_MIN, WALK_SPEED_MAX) : WALK_SPEED_DEFAULT;
}

/** Seconds a walk of `meters` takes: the same rule every transfer edge already uses. */
function walkSeconds(meters) { return meters / walkSpeed() + WALK_BUFFER_S; }

/**
 * The stations a point can reach on foot: nearest first, at most POINT_WALK_STATIONS of
 * them, none further than POINT_WALK_RADIUS. Distance to a station is the distance to
 * its closest platform.
 */
function stationsNearPoint(graph, point) {
	const out = [];
	for (const [stationId, ids] of graph.byStation) {
		let best = null;
		for (const id of ids) {
			const n = graph.nodes.get(id);
			if (!n) continue;
			const m = walkDistance3(point, n);
			if (!best || m < best.meters) best = { stationId, platformId: id, meters: m };
		}
		if (best && best.meters <= POINT_WALK_RADIUS) out.push(best);
	}
	out.sort((a, b) => a.meters - b.meters || (a.stationId < b.stationId ? -1 : 1));
	return out.slice(0, POINT_WALK_STATIONS);
}

/**
 * Overlay `points` ({id, xz, y, label}) onto a graph.
 *
 * @returns {{graph, added:[pointId], reach:Map(pointId -> [{stationId, platformId, meters}])}}
 *          `graph` is the overlay (share-everything, copy-on-write); a point with no
 *          station in range is simply absent from `added`, which is what the empty
 *          state ("no stations within walking range") keys off.
 */
function injectPointNodes(graph, points) {
	const list = (points || []).filter(Boolean);
	if (!list.length) return { graph, added: [], reach: new Map() };
	const nodes = new Map(graph.nodes);
	const byStation = new Map(graph.byStation);
	const adjacency = new Map(graph.adjacency);
	const added = [], reach = new Map();
	const adjOf = (id) => {
		let a = adjacency.get(id);
		// copy-on-write: never push into the base graph's own arrays
		const fresh = { rides: a ? a.rides : [], transfers: a ? a.transfers.slice() : [] };
		adjacency.set(id, fresh);
		return fresh;
	};

	for (const pt of list) {
		const near = stationsNearPoint(graph, pt);
		reach.set(pt.id, near);
		if (!near.length) continue;
		const node = {
			id: pt.id, stationId: pt.id, partId: null, name: pt.label || "Map point",
			stationName: pt.label || "Map point", xz: pt.xz.slice(),
			y: pt.y, accessible: true, routeIds: [], point: true,
		};
		nodes.set(pt.id, node);
		byStation.set(pt.id, [pt.id]);
		const mine = adjOf(pt.id);
		for (const hit of near) {
			for (const platformId of graph.byStation.get(hit.stationId) || []) {
				const n = graph.nodes.get(platformId);
				if (!n) continue;
				const meters = walkDistance3(pt, n);
				const edge = {
					from: pt.id, to: platformId, meters, seconds: walkSeconds(meters),
					walk: true, accessible: !!n.accessible, sameStation: false, samePart: false, point: true,
				};
				mine.transfers.push(edge);
				adjOf(platformId).transfers.push({ ...edge, from: platformId, to: pt.id });
			}
		}
		added.push(pt.id);
	}

	const overlay = {
		nodes, byStation, adjacency,
		rideEdges: graph.rideEdges, transferEdges: graph.transferEdges,
		through: graph.through,
		stations: graph.stations, _routeSeq: graph._routeSeq, _base: graph,
	};
	// routeSequence() memoises on the object it is handed; share the base's cache so the
	// overlay never rebuilds it (and writes back, so the first build is not wasted)
	if (!graph._routeSeq) {
		Object.defineProperty(overlay, "_routeSeq", {
			get() { return graph._routeSeq; },
			set(v) { graph._routeSeq = v; },
			configurable: true,
		});
	}
	return { graph: overlay, added, reach };
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
			// …and THROUGH RUNNING (feature 7): at a collapsed terminus the same physical
			// train carries on as another route, so its ride edges are reachable on exactly
			// the same terms — dwell only, no wait, no boarding counted.
			const cont = graph.through ? graph.through.get(L.node + "|" + L.aboard) : null;
			for (const e of adj.rides) {
				const same = e.routeId === L.aboard;
				if (!same && !(cont && cont.has(e.routeId))) continue;
				push({ node: e.to, aboard: e.routeId, arrMs: L.arrMs + dwellMs + e.seconds * 1000,
					boardings: L.boardings, walk: L.walk, prev: L,
					via: same
						? { kind: "ride", edge: e }
						: { kind: "ride", edge: e, through: { fromRouteId: L.aboard, platform: L.node } } });
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
			// a street transfer is an INTERCHANGE, never a leg of a walking tour: it may
			// not follow another walk, so the planner can never chain its way across town
			if (e.street && L.via && L.via.kind === "walk") continue;
			push({ node: e.to, aboard: null, arrMs: L.arrMs + e.seconds * 1000,
				boardings: L.boardings, walk: L.walk + e.meters, prev: L, via: { kind: "walk", edge: e } });
		}
	}
	return results;
}

/**
 * The route spans of one ride leg, as a signature fragment. A plain leg is one span; a
 * through-running leg is one span per route the same train runs as, so two journeys that
 * differ only in where the train changes identity are still two distinct options.
 */
function rideSpanKey(leg) {
	const spans = leg.spans && leg.spans.length
		? leg.spans
		: [{ routeId: leg.routeId, fromPlatform: leg.fromPlatform, toPlatform: leg.toPlatform }];
	return spans.map((s) => s.routeId + ":" + s.fromPlatform + ">" + s.toPlatform).join("+");
}

/** Every route id one ride leg is aboard for (the boarded route plus its through runs). */
function legRouteIds(leg) {
	if (!leg || leg.kind !== "ride") return [];
	const out = [leg.routeId];
	for (const c of leg.continuations || []) if (!out.includes(c.routeId)) out.push(c.routeId);
	return out;
}

/** Turn one label chain into the Journey shape the option cards + itinerary consume. */
function assembleJourney(label) {
	const steps = [];
	for (let cur = label; cur && cur.via; cur = cur.prev) {
		steps.unshift({ via: cur.via, from: cur.prev.node, to: cur.node, startMs: cur.prev.arrMs, endMs: cur.arrMs });
	}
	if (!steps.length) return null;

	const legs = [];
	// a leg end is a platform OR one of the two virtual map points (feature 2); a point is
	// a place on the street, so it is always reachable and carries its own display name
	const anchor = (platformId) => {
		const pt = state.pointNodes.get(platformId);
		if (pt) return { station: null, part: null, accessible: true, point: platformId, name: pt.label };
		const pl = state.platforms.get(platformId);
		const st = pl ? state.stations.get(pl.stationId) : null;
		return {
			station: pl ? pl.stationId : null, part: pl ? pl.partId : null,
			accessible: !!(pl && pl.accessible), point: null, name: st ? st.display : "",
		};
	};
	let accessible = true;
	let i = 0;
	while (i < steps.length) {
		if (steps[i].via.kind === "ride") {
			const routeId = steps[i].via.edge.routeId;
			const stops = [steps[i].from];
			/* One boarding = one leg. That covers consecutive same-route rides (a
			 * re-boarding carries its own depMs and breaks the run) AND a THROUGH RUN
			 * (feature 7), where the train changes route without the rider moving: the
			 * continuation is recorded as a note on this leg, never as a transfer. */
			const spans = [];
			const continuations = [];
			let spanRoute = routeId, spanFrom = steps[i].from;
			let j = i;
			while (j < steps.length && steps[j].via.kind === "ride") {
				const via = steps[j].via;
				if (j > i) {
					const thru = via.through && via.through.fromRouteId === spanRoute && via.edge.routeId !== spanRoute;
					if (thru) {
						const rtC = state.routes.get(via.edge.routeId);
						spans.push({ routeId: spanRoute, fromPlatform: spanFrom, toPlatform: steps[j].from });
						continuations.push({
							platform: steps[j].from, atStopIndex: stops.length - 1,
							fromRouteId: spanRoute, routeId: via.edge.routeId,
							routeName: via.edge.routeName, number: via.edge.routeNumber,
							color: via.edge.color, headsign: rtC ? rtC.dest : "",
							station: anchor(steps[j].from).name,
						});
						spanRoute = via.edge.routeId;
						spanFrom = steps[j].from;
					} else if (via.edge.routeId !== spanRoute || via.depMs !== undefined) {
						break;
					}
				}
				stops.push(steps[j].to);
				j++;
			}
			const first = steps[i], last = steps[j - 1], e = first.via.edge;
			spans.push({ routeId: spanRoute, fromPlatform: spanFrom, toPlatform: last.to });
			const rt = state.routes.get(spanRoute);
			const a = anchor(first.from), b = anchor(last.to);
			accessible = accessible && a.accessible && b.accessible;
			legs.push({
				kind: "ride", routeId, routeName: e.routeName, number: e.routeNumber, color: e.color, mode: e.mode,
				fromStation: a.station, fromPart: a.part, fromPlatform: first.from,
				toStation: b.station, toPart: b.part, toPlatform: last.to,
				fromPoint: a.point, toPoint: b.point, fromName: a.name, toName: b.name,
				// `stops` spans the whole ride, through runs included, so the stop count a
				// rider counts down is the one they actually experience
				stops, stopCount: Math.max(1, stops.length - 1),
				// the headsign is the FINAL route's destination: that is where this train
				// is going by the time the rider gets off
				headsign: rt ? rt.dest : "",
				spans, continuations,
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
				fromPoint: a.point, toPoint: b.point, fromName: a.name, toName: b.name,
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
		toPoint: lastLeg.toPoint || null, toName: lastLeg.toName || "",
		fromPoint: legs[0].fromPoint || null, fromName: legs[0].fromName || "",
		accessible, tags: [],
		transfers: Math.max(0, legs.filter((l) => l.kind === "ride").length - 1),
		walkMeters: legs.reduce((a, l) => a + (l.kind === "walk" ? l.meters : 0), 0),
		signature: legs.map((l) => l.kind === "ride"
			? "r:" + rideSpanKey(l)
			: "w:" + l.fromPlatform + ">" + l.toPlatform).join("|"),
		// the leg signature options are deduped on: two journeys that ride the same
		// services between the same platforms are ONE option to a rider, even when they
		// finish at different platforms of the destination station.
		rideSignature: legs.filter((l) => l.kind === "ride").map(rideSpanKey).join("|"),
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

/** The platforms a station-or-part endpoint offers, narrowed to a part when it has one. */
function endpointPlatforms(graph, stationId, partId) {
	const ids = graph.byStation.get(stationId) || [];
	if (!partId) return ids;
	const narrowed = ids.filter((id) => {
		const n = graph.nodes.get(id);
		return n && n.partId === partId;
	});
	return narrowed.length ? narrowed : ids;
}

/** A plan selection carrying a map point -> the {id, xz, y, label} injectPointNodes wants. */
function endpointPoint(sel, id) {
	if (!sel || !sel.point) return null;
	return {
		id, xz: [sel.point[0], sel.point[1]],
		y: Number.isFinite(sel.y) ? sel.y : undefined,
		label: sel.label || (id === POINT_TO ? "Dropped pin (dest)" : "Dropped pin"),
	};
}

/**
 * The planner the UI actually calls: either endpoint may be a station/part OR an
 * arbitrary map point (feature 2).
 *
 * Station-to-station is delegated to planJourneys() unchanged, memo and all. As soon as a
 * point is involved the search runs over an overlay graph (injectPointNodes) with the
 * virtual node as origin and/or target, so the leading and trailing walks fall out of the
 * same label chain as every other walk — no special-casing in assembleJourney.
 *
 * @returns {{journeys: Journey[], error: string|null}} `error` is the rider-facing empty
 *          state ("no stations within walking range …"), which is NOT the same thing as
 *          "no journey found".
 */
function planEndpoints(graph, from, to, prefs, departAtMs) {
	state.pointNodes = new Map();
	if (!graph || !from || !to) return { journeys: [], error: null };
	const p = prefs || {};
	const mode = p.mode === "transfers" || p.mode === "walking" ? p.mode : "fastest";
	const when = Number.isFinite(departAtMs) ? departAtMs : now();

	const fromPt = endpointPoint(from, POINT_FROM), toPt = endpointPoint(to, POINT_TO);
	if (!fromPt && !toPt) {
		if (!from.stationId || !to.stationId || from.stationId === to.stationId) return { journeys: [], error: null };
		return {
			journeys: planJourneys(graph, from.stationId, to.stationId, p, when,
				{ fromPartId: from.partId, toPartId: to.partId }),
			error: null,
		};
	}

	const pts = [fromPt, toPt].filter(Boolean);
	for (const pt of pts) state.pointNodes.set(pt.id, pt);
	const inj = injectPointNodes(graph, pts);
	const missing = [];
	if (fromPt && !inj.added.includes(POINT_FROM)) missing.push("start");
	if (toPt && !inj.added.includes(POINT_TO)) missing.push("destination");
	if (missing.length) {
		return { journeys: [], error: "No stations within walking range of the " + missing.join(" or ") + "." };
	}

	const g = inj.graph;
	const originIds = fromPt ? [POINT_FROM] : endpointPlatforms(g, from.stationId, from.partId);
	const targetIds = new Set(toPt ? [POINT_TO] : endpointPlatforms(g, to.stationId, to.partId));
	if (!originIds.length || !targetIds.size) return { journeys: [], error: null };
	try {
		const out = choosePlanOptions(planSearch(g, originIds, targetIds, { stepFree: !!p.stepFree }, when), mode);
		return { journeys: out, error: null };
	} catch (e) {
		console.warn("planEndpoints failed", e);
		return { journeys: [], error: null };
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
	// the basemap index the real server publishes, with origins on the documented
	// 512-block grid: four tiles covering the demo city (feature 5). The demo river
	// comes from the same class mask the real scanner writes (demoMaskTile).
	applySatmeta(demoSatmeta());
	prepareGeometry();
	fitView();
	setStatus("live");

	// pre-fill the mock's query — the real planner answers it, so every card /
	// itinerary / selection feature shows without the rider typing anything
	state.plan.from = { stationId: "mh", partId: null };
	state.plan.to = { stationId: "ap", partId: "ap:1" };
	syncFields();
	replan();

	// four synthetic trains, no SSE — the same handleFrame() the real stream feeds.
	// Consists differ per working (4 / 6 / 8 / 3 cars) so the train card's "N cars" line
	// is exercised properly, and one route carries `nextStation` so the card's fallback
	// path (no resolvable nPlat) has data behind it too.
	const runs = [
		{ id: "v1", rails: ["cor_1", "cor_2", "cor_3"], t: 0.25, step: 0.010, kmh: 52, nPlat: "mh_g",
			cars: ["m7_a", "m7_b", "m7_b", "m7_a"],
			route: { id: "r4", name: "Baker Line||Bayfront", number: "4 IN", color: 0x00933C, dest: "Bayfront", nextStation: "Maple Heights" } },
		// the outbound working: it runs on the OTHER green track, and the drawn puck is
		// snapped onto the one line both directions share
		{ id: "v4", rails: ["cor_2o", "cor_1o", "cor_0o"], t: 0.6, step: 0.009, kmh: 50, nPlat: "gf_g",
			cars: ["m7_a", "m7_b", "m7_b", "m7_b", "m7_b", "m7_a"],
			route: { id: "r4o", name: "Baker Line||Northgate", number: "4 OU", color: 0x00933C, dest: "Northgate", nextStation: "Garfield Av" } },
		{ id: "v2", rails: ["blu_1", "blu_2"], t: 0.4, step: 0.006, kmh: 78, nPlat: "hv_b",
			cars: ["m7_a", "m7_b", "m7_b", "m7_b", "m7_b", "m7_b", "m7_b", "m7_a"],
			route: { id: "rA", name: "Airport Express||Airport", number: "A IN", color: 0x0039A6, dest: "Airport", nextStation: "Harborview" } },
		{ id: "v3", rails: ["red_1", "red_2"], t: 0.1, step: 0.008, kmh: 46, nPlat: "fd_r",
			cars: ["r62_a", "r62_b", "r62_a"],
			route: { id: "rR", name: "Ridge Line||South Yards", number: "1", color: 0xEE352E, dest: "South Yards", nextStation: "Foundry" } },
	];
	// TWO synthetic players (feature 3): "Demo" walks a loop through the city centre and
	// is the self player (?player=Demo, matched case-insensitively), "Riley" idles.
	const loop = [[636, 200], [700, 240], [720, 330], [660, 400], [600, 360], [590, 260]];
	let walk = 0;
	for (const r of runs) r.i = 0;
	// …unless the rider is SCRIPTED (?demo=1&player=Demo), in which case Demo actually
	// travels the first planned option and the guidance banner can be watched end to end
	const scripted = /^demo$/i.test(PLAYER_PARAM);
	const rider = demoRider();
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
				consist: { cars: r.cars.slice(), carLengths: r.cars.map(() => 19.2) },
			};
		});
		walk = (walk + 0.004) % 1;
		const seg = walk * loop.length;
		const i0 = Math.floor(seg), f = seg - i0;
		const a = loop[i0], b = loop[(i0 + 1) % loop.length];
		let me = { name: "Demo", x: a[0] + (b[0] - a[0]) * f, y: 64, z: a[1] + (b[1] - a[1]) * f };
		if (scripted) {
			const step = rider.step(t, 0.333);
			if (step.pos) me = { name: "Demo", x: step.pos[0], y: 64, z: step.pos[1] };
			if (step.vehicle) vehicles.push(step.vehicle);
		}
		const players = [me, { name: "Riley", x: 1210, y: 64, z: 640 }];
		handleFrame({ schemaVersion: 1, serverTime: t, dimension: 0, vehicles, players }, false);
		if (scripted) rider.after();
	}, 333);

	requestAnimationFrame(frame);
	setInterval(tickLive, 1000);
}

/* ----------------------------------------------------------------------------
 * 13a. the scripted demo rider (?demo=1&player=Demo) — live tracking, watchable
 * --------------------------------------------------------------------------
 * Demo walks to the first platform, waits, rides (a synthetic train is published at
 * their own position on the leg's route, so the tracker's vehicle correlation has
 * something real to lock on to), changes, rides again, arrives — then loops.
 *
 * `&miss=1` makes them dawdle on the platform instead of boarding, so the planned
 * departure slides past and the missed-departure -> re-plan -> POPUP path runs.
 * ------------------------------------------------------------------------- */

/** Point `f` of the way along a polyline, plus the fraction as a stop index. */
function polylineAt(pts, f) {
	if (!pts || pts.length < 2) return pts && pts.length ? pts[0].slice() : null;
	const total = polylineLength(pts);
	if (!(total > 0)) return pts[0].slice();
	let want = clamp(f, 0, 1) * total, acc = 0;
	for (let i = 1; i < pts.length; i++) {
		const d = dist(pts[i - 1], pts[i]);
		if (acc + d >= want) {
			const k = d > 0 ? (want - acc) / d : 0;
			return [pts[i - 1][0] + (pts[i][0] - pts[i - 1][0]) * k, pts[i - 1][1] + (pts[i][1] - pts[i - 1][1]) * k];
		}
		acc += d;
	}
	return pts[pts.length - 1].slice();
}

function demoRider() {
	const DAWDLE = new URLSearchParams(location.search).get("miss") === "1";
	const WAIT_TICKS = 12;          // ~4 s of platform wait before the train shows up
	const RIDE_BOOST = 3;           // a 5-minute leg in 100 s: watchable, still sane
	const r = { journey: null, geom: null, leg: 0, f: 0, wait: 0, hold: 0, pos: null, pending: false };

	const arm = () => {
		const j = (state.plan.journeys || [])[0];
		if (!j) return false;
		r.journey = j;
		r.geom = trackGeometry(j);
		r.leg = 0; r.f = 0; r.wait = WAIT_TICKS; r.hold = 0;
		r.pending = true;                 // guidance re-arms once per loop, not per tick
		const g = r.geom[0];
		r.pos = (g && g.from) ? g.from.slice() : null;
		return !!r.pos;
	};

	const routeIdAt = (leg, stopIdx) => {
		let id = leg.routeId;
		for (const c of leg.continuations || []) if (stopIdx >= c.atStopIndex) id = c.routeId;
		return id;
	};

	return {
		/** One 333 ms step: where the rider is, and the train they are on (if any). */
		step(tNow, dt) {
			if (!r.journey && !arm()) return { pos: null, vehicle: null };
			const legs = r.journey.legs || [];
			const leg = legs[r.leg];
			const geom = r.geom[r.leg];
			if (!leg || !geom) { r.journey = null; return { pos: r.pos, vehicle: null }; }
			if (r.hold > 0) { r.hold--; if (!r.hold) arm(); return { pos: r.pos, vehicle: null }; }

			const pts = geom.pts && geom.pts.length >= 2 ? geom.pts : (geom.from && geom.to ? [geom.from, geom.to] : null);
			if (!pts) { r.leg++; r.f = 0; r.wait = WAIT_TICKS; return { pos: r.pos, vehicle: null }; }
			const len = Math.max(1, polylineLength(pts));

			if (leg.kind === "walk") {
				r.f = Math.min(1, r.f + (walkSpeed() * dt) / len);
			} else {
				// wait on the platform first — and with ?miss=1, keep waiting until the
				// planner has offered a way out
				if (r.wait > 0 && !(DAWDLE && state.tracking && state.tracking.offer)) {
					if (!DAWDLE) r.wait--;
					r.pos = polylineAt(pts, 0);
					// dawdling means dawdling NEAR the platform, not on it: far enough that
					// no passing train correlates as "they got on", close enough to still be
					// on the plan's own corridor
					if (DAWDLE) r.pos = [r.pos[0] + 30, r.pos[1] + 30];
					return { pos: r.pos, vehicle: null };
				}
				r.wait = 0;
				const secs = Math.max(30, (leg.arrMs - leg.depMs) / 1000);
				r.f = Math.min(1, r.f + (dt * RIDE_BOOST) / secs);
			}
			r.pos = polylineAt(pts, r.f);

			let vehicle = null;
			if (leg.kind === "ride") {
				const stops = leg.stops || [];
				const last = Math.max(0, stops.length - 1);
				const passed = Math.min(last, Math.floor(r.f * last));
				const nextIdx = Math.min(last, passed + 1);
				const rt = state.routes.get(routeIdAt(leg, passed)) || state.routes.get(leg.routeId);
				vehicle = {
					id: "demo_ride", x: r.pos[0], y: 64, z: r.pos[1], kmh: 55, rev: false,
					rail: "", railT: -1, doors: false, dwellMs: 0, devMs: 0, manual: false, stop: passed,
					pPlat: stops[passed] || "", nPlat: stops[nextIdx] || "", pFrac: r.f,
					route: rt ? { id: rt.id, name: rt.name, number: rt.number, color: rt.color, dest: rt.dest } : null,
					consist: { cars: ["m7_a", "m7_b", "m7_a"], carLengths: [19.2, 19.2, 19.2] },
				};
			}
			if (r.f >= 1) {
				r.leg++;
				r.f = 0;
				r.wait = WAIT_TICKS;
				if (r.leg >= legs.length) { r.hold = 30; r.leg = legs.length - 1; }   // 10 s, then loop
			}
			return { pos: r.pos, vehicle };
		},
		/**
		 * Arm the tracker on whatever the rider is travelling — ONCE per loop, so
		 * dismissing the banner keeps it dismissed until the rider starts over.
		 */
		after() {
			if (!r.journey || !r.pending || state.tracking) return;
			if (!state.selfPlayer || !selfPlayerPos()) return;
			r.pending = false;
			startTracking(r.journey);
		},
	};
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
	// A SECOND colour along the crosstown's 90-degree corner: the Meadow Link runs beside
	// the orange on its own track, ~8 blocks out, so the corner is a real two-colour
	// bundle — the case where each colour offsetting its OWN centreline used to kink and
	// wobble at the apex (reference-centreline bundling is what makes it constant).
	R("mdw_0", [[768, 207], [988, 198], [1188, 192]]);
	R("mdw_1", [[1188, 192], [1324, 256], [1387, 430], [1392, 616]]);
	// harbor ferry (boat mode -> dashed ribbon)
	R("fer_0", [[748, 880], [1000, 900], [1220, 866], [1392, 806]], { mode: "boat", speed: 30 });
	R("fer_1", [[1392, 806], [1408, 720], [1400, 616]], { mode: "boat", speed: 30 });
	/* THE MEGA-HUB REPRODUCTION (Thomas's Albany screenshot), off on its own so it
	 * changes no journey: Kransfield is a terminus with a real BALLOON LOOP — the
	 * inbound track runs past the platform, loops right round and comes back — and its
	 * two services are a shade-drifted pair (#808000 / #7f8200) whose numbers are the
	 * bare direction words "IN" and "OU". Drawn naively that is a loop-the-loop over the
	 * glyph, two olive lines braiding, and a chip reading "IN OU". */
	R("oli_0", [[300, 640], [300, 700], [300, 760], [301, 800], [306, 840], [330, 858],
		[354, 840], [356, 812], [336, 796], [312, 802], [300, 818]]);
	R("oli_0o", [[306, 810], [306, 760], [306, 700], [306, 640]]);

	const P = (id, name, stationId, x, z, dx, dz, accessible) => ({
		id, name, dwellMs: 20000, stationId,
		p1: [x - dx * 12, 64, z - dz * 12], p2: [x + dx * 12, 64, z + dz * 12],
		mid: [x, 64, z], routeIds: [], accessible: !!accessible,
	});
	const platforms = [
		// Northgate is the demo's MIXED-accessibility station: platform 1 is step-free,
		// platform 2 is not, and the station publishes the explicit accessiblePlatforms
		// list — which is what makes the station panel list platforms one by one instead
		// of saying "All platforms step-free".
		P("ng_g", "1", "ng", 636, 70, 0, 1, true), P("ng_b", "2", "ng", 634, 70, 0, 1, false),
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
		P("wl_o", "1", "wl", 300, 640, 0.1, 1, false),
		P("kf_o", "1", "kf", 300, 820, 0.1, 1, true),
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
		S("wl", "Willowbank", 0x808000, ["wl_o"], false, 300, 640),
		S("kf", "Kransfield", 0x808000, ["kf_o"], true, 300, 820),
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
		RT("rM", "Meadow Link||Airport Terminal", "M", 0x8B5E3C),
		// the mega-hub pair: two shades of the same olive, numbered by DIRECTION ONLY
		RT("rL", "Kransfield Loop||Kransfield", "IN", 0x808000),
		RT("rLo", "Kransfield Loop||Willowbank", "OU", 0x7F8200),
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
		{ id: "rM", name: "Meadow Link||Airport Terminal", number: "M", color: 0x8B5E3C, hidden: false, mode: "train",
			platforms: ["fg_o", "em_o", "ap_t"],
			legs: [leg("mdw_0"), leg("mdw_1")],
			durations: [240000, 320000], durationsValid: true, headwayMs: 600000 },
		{ id: "rL", name: "Kransfield Loop||Kransfield", number: "IN", color: 0x808000, hidden: false, mode: "train",
			platforms: ["wl_o", "kf_o"], legs: [leg("oli_0")],
			durations: [240000], durationsValid: true, headwayMs: 600000 },
		{ id: "rLo", name: "Kransfield Loop||Willowbank", number: "OU", color: 0x7F8200, hidden: false, mode: "train",
			platforms: ["kf_o", "wl_o"], legs: [leg("oli_0o")],
			durations: [240000], durationsValid: true, headwayMs: 600000 },
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

	// EXITS: the newer mapdata field. Only two demo stations publish one, so the panel's
	// "omit the section entirely when absent" path is exercised by every other station.
	const exits = {
		bc: [
			{ name: "Exit A", destinations: ["Main St", "Transit Museum"] },
			{ name: "Exit B", destinations: ["City Hall", "Baker Plaza"] },
			{ name: "Exit C", destinations: ["Bus terminal"] },
		],
		ap: [
			{ name: "Exit 1", destinations: ["Terminal A departures", "Car rental"] },
			{ name: "Exit 2", destinations: ["Terminal B", "Long-stay parking"] },
		],
	};
	// Northgate's explicit step-free list (see the platform table above)
	const accessiblePlatforms = { ng: ["ng_g"] };

	const mdStations = stations.map((st) => {
		const extra = partsFor(st);
		return {
			id: st.id, name: st.name, color: st.color, accessible: st.accessible,
			...(exits[st.id] ? { exits: exits[st.id] } : {}),
			...(accessiblePlatforms[st.id] ? { accessiblePlatforms: accessiblePlatforms[st.id] } : {}),
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
		mapdata: {
			schemaVersion: 1, routes: mdRoutes, stations: mdStations,
			/* THROUGH RUNNING (feature 7). The blue A's Harborview working carries on as
			 * the purple S shuttle to Harbor East: same train, same seat, different
			 * bullet — which is exactly the case the planner must not price as a
			 * transfer, and the itinerary must draw as a "Continues as S" note. */
			throughRuns: [{ from: "rA", to: "rS", platform: "hv_b" }],
		},
	};
}

/**
 * The demo's satellite index (feature 5). Four 512-block tiles on the documented grid
 * (origins are multiples of 512) covering x 512..1536, z 0..1024 — which is the demo
 * city's own footprint, so switching Basemap to Satellite in ?demo=1 really does put
 * imagery under the network.
 */
function demoSatmeta() {
	return {
		available: true, dimension: "demo:overworld", scannedAt: now(),
		scale: 2, tileSamples: 256, originX: 0, originZ: 0,
		tiles: [[1, 0], [2, 0], [1, 1], [2, 1]],
		bbox: [512, 0, 1536, 1024],
		mask: true,
	};
}

/** Where the demo river runs: its centre line as a function of z. */
const demoRiverX = (z) => 940 + 60 * Math.sin(z / 260) + z * 0.30;

/**
 * One synthetic CLASS-MASK tile in the server's exact encoding (class code in the red
 * channel, opaque where scanned): water along the demo river, an island in the harbor,
 * a few woodland patches. Styled by the same maskTileCanvas() path the real tiles use.
 */
function demoMaskTile(tx, tz) {
	const meta = state.satmeta;
	if (!meta) return null;
	const n = meta.tileSamples;
	const cv = document.createElement("canvas");
	cv.width = n; cv.height = n;
	const g = cv.getContext("2d");
	if (!g || typeof g.createImageData !== "function") return null;
	const img = g.createImageData(n, n);
	if (!img || !img.data) return null;
	const d = img.data;
	for (let pz = 0; pz < n; pz++) {
		for (let px = 0; px < n; px++) {
			const [wx, wz] = satWorldOf(meta, tx, tz, px, pz);
			const o = (pz * n + px) * 4;
			const dx = Math.abs(wx - demoRiverX(wz));
			const island = Math.hypot(wx - 1490, wz - 925) < 34;
			let cls = 6;                                                   // plain land
			if (dx < 46 && !island) cls = 1;                                // water
			else if (((wx * 0.004) | 0) % 5 === 0 && ((wz * 0.004) | 0) % 3 === 0) cls = 2; // woodland
			d[o] = cls; d[o + 1] = 0; d[o + 2] = 0; d[o + 3] = 255;
		}
	}
	g.putImageData(img, 0, 0);
	return cv;
}

/**
 * One synthetic tile, painted through the SAME satWorldOf() the renderer places it with —
 * so if the tile maths were wrong the demo's river would visibly disagree with the
 * schematic basemap's own mask tile of the same river. Vanilla-ish greens and browns, a river
 * running where demoMaskTile()'s water runs, and a canvas returned directly (a canvas is a
 * valid drawImage source, so nothing has to round-trip through a data URL).
 *
 * Lazy: only ever called while the satellite basemap is actually being drawn.
 */
function demoSatTile(tx, tz) {
	const meta = state.satmeta;
	if (!meta) return null;
	const n = meta.tileSamples;
	const cv = document.createElement("canvas");
	cv.width = n; cv.height = n;
	const g = cv.getContext("2d");
	if (!g || typeof g.createImageData !== "function") return null;
	const img = g.createImageData(n, n);
	if (!img || !img.data) return null;
	const d = img.data;
	const riverX = demoRiverX;
	for (let pz = 0; pz < n; pz++) {
		for (let px = 0; px < n; px++) {
			const [wx, wz] = satWorldOf(meta, tx, tz, px, pz);
			const o = (pz * n + px) * 4;
			const grain = ((Math.sin(wx * 0.37) + Math.sin(wz * 0.29) + Math.sin((wx + wz) * 0.11)) / 3) * 16;
			const dx = Math.abs(wx - riverX(wz));
			let r, gg, b;
			if (dx < 46) { r = 48; gg = 84; b = 122; }                       // water
			else if (dx < 60) { r = 176; gg = 162; b = 118; }                // sand
			else if (((wx * 0.013) | 0) % 7 === 0 || ((wz * 0.013) | 0) % 9 === 0) {
				r = 122; gg = 118; b = 112;                                    // roads
			} else if (((wx * 0.006) | 0) % 3 === 0 && ((wz * 0.006) | 0) % 4 === 0) {
				r = 138; gg = 124; b = 104;                                    // built-up
			} else { r = 86; gg = 118; b = 62; }                             // grass
			d[o] = clamp(r + grain, 0, 255);
			d[o + 1] = clamp(gg + grain, 0, 255);
			d[o + 2] = clamp(b + grain, 0, 255);
			d[o + 3] = 255;
		}
	}
	g.putImageData(img, 0, 0);
	return cv;
}

/* ---------------------------------------------------------------------------- */

boot();
