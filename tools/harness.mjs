/* Headless harness: runs map.js in a vm with a stub DOM (?demo=1 path, no fetch/SSE)
 * and exercises the pure geometry / graph functions. */
import { readFileSync } from "node:fs";
import vm from "node:vm";

const src = readFileSync(process.argv[2], "utf8");

function fakeCtx() {
	const store = {};
	return new Proxy(store, {
		get(t, k) {
			if (k === "measureText") return (s) => ({ width: String(s).length * 7 });
			if (k in t) return t[k];
			return () => {};
		},
		set(t, k, v) { t[k] = v; return true; },
		has() { return true; },
	});
}

class El {
	constructor(id) {
		this.id = id; this.style = {}; this.dataset = {}; this.value = "";
		this.textContent = ""; this.innerHTML = ""; this.children = [];
		this.clientWidth = 1600; this.clientHeight = 1000; this.width = 1600; this.height = 1000;
		this.classList = {
			_s: new Set(),
			add: (...c) => c.forEach((x) => this.classList._s.add(x)),
			remove: (...c) => c.forEach((x) => this.classList._s.delete(x)),
			toggle: (c, on) => { on ? this.classList._s.add(c) : this.classList._s.delete(c); },
			contains: (c) => this.classList._s.has(c),
		};
	}
	getContext() { return fakeCtx(); }
	addEventListener() {}
	removeEventListener() {}
	setPointerCapture() {}
	getBoundingClientRect() { return { left: 0, top: 0, width: 1600, height: 1000 }; }
	querySelector() { return new El("q"); }
	querySelectorAll() { return []; }
	appendChild(c) { this.children.push(c); return c; }
	focus() {}
	contains() { return false; }
}

const els = new Map();
const doc = {
	getElementById: (id) => { if (!els.has(id)) els.set(id, new El(id)); return els.get(id); },
	createElement: (t) => new El(t),
	querySelector: () => new El("q"),
	querySelectorAll: () => [],
	addEventListener: () => {},
	body: new El("body"),
	hidden: false,
};

const ctxObj = {
	console, document: doc, window: { innerWidth: 1600, innerHeight: 1000, devicePixelRatio: 2, addEventListener() {} },
	location: { search: "?demo=1" },
	// a REAL store, so the prefs round trip (the pairing token lives here) is testable
	localStorage: (() => { const m = new Map(); return {
		getItem: (k) => (m.has(k) ? m.get(k) : null),
		setItem: (k, v) => { m.set(k, String(v)); },
		removeItem: (k) => { m.delete(k); },
		_map: m,
	}; })(),
	Image: class { set src(v) { this._src = v; } get complete() { return false; } },
	Path2D: class { constructor(d) { this.d = d; } },
	EventSource: class { constructor() {} close() {} },
	requestAnimationFrame: () => 0,
	setInterval: () => 0,
	setTimeout: () => 0,
	clearTimeout: () => {},
	performance: { now: () => Date.now() },
	fetch: () => Promise.reject(new Error("no network in harness")),
	URLSearchParams, Date, Math, JSON, Map, Set, Promise, Object, Array, String, Number, Infinity, NaN, isNaN, parseInt, parseFloat,
};
ctxObj.globalThis = ctxObj;
ctxObj.window.innerWidth = 1600;
vm.createContext(ctxObj);
vm.runInContext(src, ctxObj, { filename: "map.js" });

const run = (expr) => vm.runInContext(expr, ctxObj);

let fails = 0;
function check(name, cond, extra) {
	const ok = !!cond;
	if (!ok) fails++;
	console.log((ok ? "PASS  " : "FAIL  ") + name + (extra !== undefined ? "   " + extra : ""));
}

/* ---- data loaded? ---- */
check("rails indexed", run("state.rails.size") === 35, run("state.rails.size"));
check("stations indexed", run("state.stations.size") === 20, run("state.stations.size"));
check("routes indexed", run("state.routes.size") === 15, run("state.routes.size"));
check("legs built", run("state.legs.size") === 37, run("state.legs.size"));

/* ---- leg chaining: multi-rail leg must be one continuous polyline, start at the
       boarding platform, end at the alighting platform ---- */
const chainInfo = run(`(() => {
  const lg = state.legs.get("rA|0");            // ng_b -> bc_b over btr_0..btr_3
  const a = state.platforms.get("ng_b").xz, b = state.platforms.get("bc_b").xz;
  let maxGap = 0;
  for (let i = 1; i < lg.pts.length; i++) maxGap = Math.max(maxGap, Math.hypot(lg.pts[i][0]-lg.pts[i-1][0], lg.pts[i][1]-lg.pts[i-1][1]));
  return { n: lg.pts.length, maxGap,
    dStart: Math.hypot(lg.pts[0][0]-a[0], lg.pts[0][1]-a[1]),
    dEnd: Math.hypot(lg.pts[lg.pts.length-1][0]-b[0], lg.pts[lg.pts.length-1][1]-b[1]),
    straight: lg.straight, rails: lg.rails.length };
})()`);
check("multi-rail leg chained (4 rails)", chainInfo.rails === 4, JSON.stringify(chainInfo));
check("chained leg has no jumps", chainInfo.maxGap < 30, "maxGap=" + chainInfo.maxGap.toFixed(2));
check("chained leg starts at boarding platform", chainInfo.dStart < 12, chainInfo.dStart.toFixed(2));
check("chained leg ends at alighting platform", chainInfo.dEnd < 12, chainInfo.dEnd.toFixed(2));

/* reversed-rail case: r4 leg 5 (grn_1) and a leg whose route runs the corridor the
   other way would need a flip; check every leg starts nearer its own from-platform */
const orient = run(`(() => {
  let bad = [];
  for (const [k, lg] of state.legs) {
    const a = state.platforms.get(lg.from).xz, b = state.platforms.get(lg.to).xz;
    const dS = Math.hypot(lg.pts[0][0]-a[0], lg.pts[0][1]-a[1]);
    const dE = Math.hypot(lg.pts[lg.pts.length-1][0]-a[0], lg.pts[lg.pts.length-1][1]-a[1]);
    if (dE + 1 < dS) bad.push(k);
  }
  return bad;
})()`);
check("every leg oriented from its boarding platform", orient.length === 0, JSON.stringify(orient));

/* ---- straight-line fallback ---- */
const fb = run(`(() => { const lg = state.legs.get("rS|0"); return { straight: lg.straight, n: lg.pts.length }; })()`);
check("empty-rails leg falls back to a straight line", fb.straight === true && fb.n === 2, JSON.stringify(fb));

/* ---- durations semantics ---- */
const dur = run(`({
  valid: state.legs.get("r4|0").seconds,
  shifted: state.legs.get("rO|0").seconds,
  shifted1: state.legs.get("rO|1").seconds,
  est: state.legs.get("rS|0").seconds
})`);
check("durationsValid uses durations[i]", Math.abs(dur.valid - 140) < 0.01, dur.valid);
check("invalid durations shift by one (leg0 -> durations[1])", Math.abs(dur.shifted - 240) < 0.01, dur.shifted);
check("invalid durations shift by one (leg1 -> durations[2])", Math.abs(dur.shifted1 - 320) < 0.01, dur.shifted1);

/* ==========================================================================
 * SCHEMATIC LINE MODEL (the 2026-08-29 rework)
 * ======================================================================== */

/* ---- A. service-label normalisation ---- */
const norm = run(`(() => {
  const t = ["2 IN", "A Inbound", "OU", "Ba", "4 OU", "7", "5 in", "A ←", "N/B", "Ridge UP",
             "", "  ", "A-IB", "SB", "C IN", "Airport OUTBOUND", "4/OU", "5 (IN)", "A."];
  const out = {};
  for (const x of t) out[x] = normalizeServiceLabel(x);
  return out;
})()`);
check('normalise "2 IN" -> "2"', norm["2 IN"] === "2", norm["2 IN"]);
check('normalise "A Inbound" -> "A"', norm["A Inbound"] === "A", norm["A Inbound"]);
check('normalise "OU" alone falls back to "OU"', norm["OU"] === "OU", norm["OU"]);
check('normalise never eats a substring ("Ba" -> "Ba")', norm["Ba"] === "Ba", norm["Ba"]);
check('normalise "4 OU" -> "4"', norm["4 OU"] === "4", norm["4 OU"]);
check('normalise leaves a plain number alone ("7" -> "7")', norm["7"] === "7", norm["7"]);
check('normalise is case-insensitive ("5 in" -> "5")', norm["5 in"] === "5", norm["5 in"]);
check('normalise strips arrows ("A ←" -> "A")', norm["A ←"] === "A", norm["A ←"]);
check('normalise splits on punctuation ("A-IB", "4/OU", "5 (IN)" all lose the suffix)',
	norm["A-IB"] === "A" && norm["4/OU"] === "4" && norm["5 (IN)"] === "5",
	JSON.stringify([norm["A-IB"], norm["4/OU"], norm["5 (IN)"]]));
check('normalise trims trailing direction punctuation ("A." -> "A")', norm["A."] === "A", norm["A."]);
check('normalise leaves a two-token name that is not a direction ("N/B" -> "N B")',
	norm["N/B"] === "N B", norm["N/B"]);
check('normalise keeps a real word ("Ridge UP" -> "Ridge")', norm["Ridge UP"] === "Ridge", norm["Ridge UP"]);
check('normalise of a bare direction keeps it ("SB" -> "SB")', norm["SB"] === "SB", norm["SB"]);
check('normalise of empty input is empty', norm[""] === "" && norm["  "] === "", JSON.stringify([norm[""], norm["  "]]));
check('normalise "Airport OUTBOUND" -> "Airport"', norm["Airport OUTBOUND"] === "Airport", norm["Airport OUTBOUND"]);

/* ---- B. the line model: colour alone defines a line ---- */
const lines = run(`(() => {
  const out = {};
  for (const [hex, l] of state.lines) out[hex] = { labels: l.serviceLabels, routes: l.routeIds.length, modes: [...l.modes] };
  return out;
})()`);
check("green: 4 routes (local + express, both directions) collapse to ONE line",
	lines["#00933c"].routes === 4, JSON.stringify(lines["#00933c"]));
check("green bullets are the normalised services 4 and 5",
	JSON.stringify(lines["#00933c"].labels) === '["4","5"]', JSON.stringify(lines["#00933c"].labels));
check("blue bullets are A and C (no IN/OU)",
	JSON.stringify(lines["#0039a6"].labels) === '["A","C"]', JSON.stringify(lines["#0039a6"].labels));
check("no line bullet anywhere still carries a direction suffix", run(`(() => {
  const bad = [];
  for (const l of state.lines.values()) for (const s of l.serviceLabels) {
    if (/\\s(IN|OU|OUT|IB|OB|NB|SB|EB|WB)$/i.test(s)) bad.push(s);
  }
  return bad;
})()`).length === 0);

/* ---- C. pair averaging: one drawn segment per (colour, part pair) ---- */
const segs = run(`(() => ({
  drawn: state.ribbons.length,
  all: state.segByPair.size,
  dupIds: state.ribbons.length - new Set(state.ribbons.map(s => s.id)).size,
  dupPairs: state.ribbons.length - new Set(state.ribbons.map(s => s.hex + "|" + s.key)).size,
  trunk: (() => { const s = state.segByPair.get("#00933c|gf:0>mh:0");
    return { src: s.sourceCount, n: s.pts.length, idx: s.idx, count: s.count, comp: s.companions }; })(),
  sorted: state.ribbons.every((s, i) => i === 0 || state.ribbons[i-1].colorInt <= s.colorInt),
}))()`);
check("every drawn segment is unique per (colour, part pair)",
	segs.dupIds === 0 && segs.dupPairs === 0, JSON.stringify(segs));
check("the green trunk's in/out pair averages into ONE segment",
	segs.trunk.src === 2 && segs.trunk.n === 32, JSON.stringify(segs.trunk));
check("drawn segments are z-ordered by colour int", segs.sorted === true);

/* the averaged centreline must sit BETWEEN the two source tracks (6 blocks apart) */
const between = run(`(() => {
  const seg = state.segByPair.get("#00933c|gf:0>mh:0");
  const a = state.rails.get("cor_1").pts, b = state.rails.get("cor_1o").pts;
  let worst = 0, maxD = 0, minD = Infinity;
  // The centroid blend now decays over up to 45% of each end (snapEnds), so only
  // the middle third of the segment is guaranteed unwarped — measure there.
  const lo = Math.floor(seg.pts.length / 3), hi = Math.ceil(seg.pts.length * 2 / 3);
  for (let i = lo; i < hi; i++) {
    const da = pointPolylineDist(seg.pts[i], a), db = pointPolylineDist(seg.pts[i], b);
    worst = Math.max(worst, Math.abs(da - db));
    maxD = Math.max(maxD, Math.max(da, db)); minD = Math.min(minD, Math.min(da, db));
  }
  return { worst, maxD, minD };
})()`);
check("averaged centreline is equidistant from both source tracks",
	between.worst < 0.5, "max |dA-dB| = " + between.worst.toFixed(3));
check("...and lies between them (~3 blocks from each of the 6-block-apart tracks)",
	between.maxD < 3.6 && between.minD > 2.4, "d in [" + between.minD.toFixed(2) + ", " + between.maxD.toFixed(2) + "]");

/* ---- D. express coverage ---- */
const exp = run(`(() => {
  const e = state.segByPair.get("#00933c|bc:0>ng:0");
  const blue = state.segByPair.get("#0039a6|ap:0>rs:0");
  return {
    green: { suppressed: e.suppressed, by: e.coveredBy, drawn: state.ribbons.includes(e) },
    blue: { suppressed: blue.suppressed, by: blue.coveredBy },
    localsKept: ["#00933c|mh:0>ng:0", "#00933c|gf:0>mh:0", "#00933c|gf:0>mu:0", "#00933c|bc:0>mu:0"]
      .every(id => state.ribbons.some(s => s.id === id)),
  };
})()`);
check("the green express ng->bc is suppressed", exp.green.suppressed === true && exp.green.drawn === false);
check("...and records the local chain that covers it",
	JSON.stringify(exp.green.by) === JSON.stringify(["#00933c|bc:0>mu:0", "#00933c|gf:0>mh:0",
		"#00933c|gf:0>mu:0", "#00933c|mh:0>ng:0"]), JSON.stringify(exp.green.by));
check("the local chain itself is never suppressed", exp.localsKept === true);
check("the blue rs->ap express is suppressed by the rs-hv-ap chain",
	exp.blue.suppressed === true
	&& JSON.stringify(exp.blue.by) === JSON.stringify(["#0039a6|ap:0>hv:0", "#0039a6|hv:0>rs:0"]),
	JSON.stringify(exp.blue));
check("a suppressed segment always leaves its two parts connected", run(`(() => {
  const bad = [];
  for (const s of state.segByPair.values()) {
    if (!s.suppressed) continue;
    const siblings = [...state.segByPair.values()].filter(o => o !== s && o.hex === s.hex && !o.suppressed);
    const adj = new Map();
    for (const o of siblings) {
      if (!adj.has(o.partA)) adj.set(o.partA, []);
      if (!adj.has(o.partB)) adj.set(o.partB, []);
      adj.get(o.partA).push(o.partB); adj.get(o.partB).push(o.partA);
    }
    const seen = new Set([s.partA]); const q = [s.partA]; let ok = false;
    while (q.length) { const c = q.shift(); if (c === s.partB) { ok = true; break; }
      for (const n of adj.get(c) || []) if (!seen.has(n)) { seen.add(n); q.push(n); } }
    if (!ok) bad.push(s.id);
  }
  return bad;
})()`).length === 0);

/* ---- E. cross-colour bundling ---- */
const bun = run(`(() => {
  const byId = new Map(state.ribbons.map(s => [s.id, s]));
  const green = byId.get("#00933c|gf:0>mh:0"), blue = byId.get("#0039a6|bc:0>ng:0");
  const selfOffset = state.ribbons.filter(s => s.companions.filter(h => h === s.hex).length !== 1);
  const sameColourPaired = state.ribbons.filter(s => s.companionSegs.some(o => o.hex === s.hex));
  return {
    green: { idx: green.idx, count: green.count, comp: green.companions },
    blue: { idx: blue.idx, count: blue.count, comp: blue.companions },
    selfOffset: selfOffset.length, sameColourPaired: sameColourPaired.length,
    symmetric: state.ribbons.every(s => s.companionSegs.every(o => o.companionSegs.includes(s))),
    lonely: byId.get("#ee352e|rv:0>wg:0").count,
  };
})()`);
check("green and blue share the trunk as a 2-colour bundle",
	bun.green.count === 2 && bun.blue.count === 2, JSON.stringify([bun.green, bun.blue]));
check("bundle slots are assigned by colour int (blue 0x0039a6 before green 0x00933c)",
	bun.blue.idx === 0 && bun.green.idx === 1, JSON.stringify([bun.blue.idx, bun.green.idx]));
check("a segment NEVER offsets against its own colour",
	bun.selfOffset === 0 && bun.sameColourPaired === 0, JSON.stringify([bun.selfOffset, bun.sameColourPaired]));
check("companionship is symmetric", bun.symmetric === true);
check("an unbundled line has count 1 (no offset at all)", bun.lonely === 1, bun.lonely);
check("offsets are stable across a rebuild", run(`(() => {
  const before = state.ribbons.map(s => s.id + ":" + s.idx + "/" + s.count).sort().join(",");
  prepareGeometry();
  const after = state.ribbons.map(s => s.id + ":" + s.idx + "/" + s.count).sort().join(",");
  return before === after;
})()`));

/* ---- F. junction snapping: every segment ends on its part's centroid ---- */
check("drawn segment endpoints snap to the station-part centroids", run(`(() => {
  let worst = 0;
  for (const s of state.ribbons) {
    const a = partCentroid(s.partA), b = partCentroid(s.partB);
    if (a) worst = Math.max(worst, Math.hypot(s.pts[0][0]-a[0], s.pts[0][1]-a[1]));
    if (b) worst = Math.max(worst, Math.hypot(s.pts[s.pts.length-1][0]-b[0], s.pts[s.pts.length-1][1]-b[1]));
  }
  return worst;
})()`) < 1e-6);

/* ---- G. interlining chips carry normalised bullets ---- */
const chips = run(`state.bundles.map(b => ({ colors: b.colors.map(c => c.hex + ":" + c.numbers.join("/")) }))`);
check("one chip per bundle signature (green+blue trunk, brown+orange crosstown)",
	chips.length === 2, JSON.stringify(chips));
check("chip bullets are normalised service labels",
	JSON.stringify(chips[0].colors) === '["#0039a6:A/C","#00933c:4/5"]', JSON.stringify(chips[0].colors));

/* ---- offsetPolyline ---- */
const off = run(`(() => {
  const line = [[0,0],[100,0],[200,0]];
  const a = offsetPolyline(line, 5);
  const corner = offsetPolyline([[0,0],[100,0],[100,100]], 5);
  return { a, cornerMid: corner[1] };
})()`);
check("offset moves a straight line by exactly d on the normal",
	Math.abs(off.a[1][1] - 5) < 1e-6 && Math.abs(off.a[1][0] - 100) < 1e-6, JSON.stringify(off.a[1]));
// right-hand normal convention: a right turn offsets to the INSIDE, magnitude d/cos(45)
check("offset miters an L corner (|v| = d/cos45)",
	Math.abs(off.cornerMid[0] - 95) < 0.01 && Math.abs(off.cornerMid[1] - 5) < 0.01
	&& Math.abs(Math.hypot(off.cornerMid[0]-100, off.cornerMid[1]-0) - 5*Math.SQRT2) < 0.01, JSON.stringify(off.cornerMid));

/* ---- glyphs: capsule where two colours meet, dots elsewhere, split station ---- */
const gl = run(`(() => {
  const m = new Map(state.glyphs.map(g => [g.partId, g]));
  return {
    n: state.glyphs.length,
    central: { capsule: m.get("bc:0").capsule, colors: m.get("bc:0").colors.length },
    garfield: { capsule: m.get("gf:0").capsule, colors: m.get("gf:0").colors.length },
    airportParts: state.glyphs.filter(g => g.stationId === "ap").map(g => g.partId),
    walks: state.walks.map(w => Math.round(w.dist)),
  };
})()`);
check("interchange part renders as a capsule", gl.central.capsule === true && gl.central.colors === 2, JSON.stringify(gl.central));
check("single-colour part renders as a dot", gl.garfield.capsule === false, JSON.stringify(gl.garfield));
check("split station has two parts", gl.airportParts.length === 2, JSON.stringify(gl.airportParts));
check("walk connector carries the 90 m distance", gl.walks.includes(90), JSON.stringify(gl.walks));

/* ---- graph seam ---- */
const g = run(`(() => {
  const gr = buildGraph();
  const t = gr.transferEdges.find(e => e.from === "ap_b" && e.to === "ap_t");
  return { nodes: gr.nodes.size, rides: gr.rideEdges.length, transfers: gr.transferEdges.length,
    ap: t ? { m: t.meters, s: Math.round(t.seconds), acc: t.accessible } : null,
    adjMh: gr.adjacency.get("mh_g").rides.map(e => e.routeNumber).sort(),
    stationsIndexed: gr.byStation.size };
})()`);
check("graph nodes = platforms", g.nodes === run("state.platforms.size"), g.nodes);
check("ride edges = legs of visible routes", g.rides === 36, g.rides);
const WALK = run("walkSpeed()");
check("the walk-speed default is Minecraft's own 4.3 m/s", WALK === 4.3, WALK);
check("transfer edge uses walkSpeed() + a 30 s buffer",
	g.ap && g.ap.m === 90 && g.ap.s === Math.round(90 / WALK + 30), JSON.stringify(g.ap) + " speed=" + WALK);
check("transfer accessibility = both ends step-free", g.ap && g.ap.acc === true, JSON.stringify(g.ap));
check("adjacency lists both green directions at Maple Heights",
	g.adjMh.join(",") === "4 IN,4 OU", JSON.stringify(g.adjMh));
check("...which the map normalises to a single bullet",
	run(`[...new Set(buildGraph().adjacency.get("mh_g").rides.map(e => normalizeServiceLabel(e.routeNumber)))].join(",")`) === "4");

/* ---- the real planner drives demo mode ---- */
check("demo mode plans with the real engine (no demoJourneys)", run("typeof demoJourneys") === "undefined");
const dj = run(`(() => {
  const js = state.plan.journeys;
  return js.map(j => ({ legs: j.legs.length, kinds: j.legs.map(l=>l.kind).join("+"),
    dur: Math.round(j.durationMs/60000), tags: j.tags,
    sane: j.arriveMs > j.departMs && j.legs.every((l,i)=> i===0 || l.depMs >= j.legs[i-1].arrMs - 1) }));
})()`);
check("demo produces 2-4 journeys", dj.length >= 2 && dj.length <= 4, JSON.stringify(dj.map(d=>d.dur)));
check("journey legs are time-ordered", dj.every((d) => d.sane), JSON.stringify(dj));
check("journey 1 is ride+walk+ride+walk", dj[0].kinds === "ride+walk+ride+walk", dj[0].kinds);
check("some journey rides the ferry (boat mode)", run(`state.plan.journeys.some(j=>j.legs.some(l=>l.mode==="boat"))`));

/* ---- selection ---- */
const sel = run(`(() => {
  selectJourney(state.plan.journeys[0]);
  const s = state.selection;
  return { ribbons: s.ribbonKeys.size, parts: [...s.partIds], routes: [...s.routeIds],
    walks: s.walks.length, origin: s.origin, dest: s.dest, board: s.boardPlatform,
    knownRibbons: state.ribbons.filter(r => s.ribbonKeys.has(r.id)).length };
})()`);
check("selection resolves real ribbon ids", sel.knownRibbons === sel.ribbons && sel.ribbons > 0, sel.ribbons + " / " + sel.knownRibbons);
check("selection covers both ridden routes", sel.routes.sort().join(",") === "r4,rA", JSON.stringify(sel.routes));
check("selection includes both airport parts", sel.parts.includes("ap:0") && sel.parts.includes("ap:1"), JSON.stringify(sel.parts));
check("selection has origin + destination anchors", !!sel.origin && !!sel.dest, JSON.stringify([sel.origin, sel.dest]));
check("selection boarding platform is the first ride's platform", sel.board === "mh_g", sel.board);
check("clearSelection resets", run(`(clearSelection(), state.selection === null)`));

/* ---- search index ---- */
const si = run(`(() => {
  const r = searchEntries("air").map(e => (e.sub ? e.label + " · " + e.sub : e.label));
  return { all: searchIndex.length, air: r };
})()`);
check("search index includes station parts", si.air.some((s) => s.includes("Terminal")), JSON.stringify(si.air));

/* ---- vehicle interpolation ---- */
const vp = run(`(() => {
  handleFrame({ schemaVersion:1, serverTime: 1000, dimension: 0, vehicles: [
    { id:"t1", x: state.rails.get("cor_1").pts[0][0], y:64, z: state.rails.get("cor_1").pts[0][1], kmh:60, rail:"cor_1", railT:0 } ]}, true);
  handleFrame({ schemaVersion:1, serverTime: 2000, dimension: 0, vehicles: [
    { id:"t1", x: state.rails.get("cor_1").pts.at(-1)[0], y:64, z: state.rails.get("cor_1").pts.at(-1)[1], kmh:60, rail:"cor_1", railT:1 } ]}, false);
  const rec = state.vehicles.get("t1");
  const mid = vehiclePos(rec, 1500);
  const half = railPoint("cor_1", 0.5);
  return { mid, half, d: Math.hypot(mid.x-half[0], mid.z-half[1]) };
})()`);
check("vehiclePos slides along the rail curve between samples", vp.d < 0.01, "delta=" + vp.d.toFixed(4));


/* ==========================================================================
 * JOURNEY HIGHLIGHTING OVER THE SCHEMATIC
 * ======================================================================== */

/* riding the EXPRESS must light the local chain that replaced it on the map */
const expressRide = run(`(() => {
  const g = state.plan.graph || (state.plan.graph = buildGraph());
  const leg = { routeId: "r5", fromPlatform: "ng_g", toPlatform: "bc_g" };
  const r = legSegmentIds(leg);
  return { ids: r.ids.sort(), fallbacks: r.fallbacks.length,
    drawn: r.ids.every(id => state.ribbons.some(s => s.id === id)) };
})()`);
check("a ride on the suppressed express lights the local chain under it",
	JSON.stringify(expressRide.ids) === JSON.stringify(["#00933c|bc:0>mu:0", "#00933c|gf:0>mh:0",
		"#00933c|gf:0>mu:0", "#00933c|mh:0>ng:0"]), JSON.stringify(expressRide.ids));
check("...and every id it returns is a segment that is actually drawn",
	expressRide.drawn === true && expressRide.fallbacks === 0, JSON.stringify(expressRide));

/* a leg whose two platforms sit in the SAME part has no segment: straight dashed */
const fallback = run(`(() => {
  const r = legSegmentIds({ routeId: "rS", fromPlatform: "hv_b", toPlatform: "he_f" });
  const same = legSegmentIds({ routeId: "rF", fromPlatform: "bf_f", toPlatform: "ap_tf" });
  return { shuttle: r.ids.length, shuttleFb: r.fallbacks.length, ferry: same.ids.length };
})()`);
check("the rail-less shuttle still resolves to a drawn segment (its parts differ)",
	fallback.shuttle === 1 && fallback.shuttleFb === 0, JSON.stringify(fallback));

/* label rule: only journey stations keep their name while a journey is selected */
const labels = run(`(() => {
  selectJourney(state.plan.journeys[0]);
  const sel = state.selection;
  const on = state.glyphs.filter(g => labelVisibleFor(g, sel)).map(g => g.partId).sort();
  const off = state.glyphs.filter(g => !labelVisibleFor(g, sel)).map(g => g.partId);
  const all = state.glyphs.every(g => labelVisibleFor(g, null));
  clearSelection();
  return { on, off, all, none: state.glyphs.every(g => labelVisibleFor(g, state.selection)) };
})()`);
check("with a journey selected only its own stations keep a label",
	labels.on.length === 8 && labels.off.length === 13,
	"on=" + labels.on.length + ", off=" + labels.off.length);
check("...every labelled part is a journey part", run(`(() => {
  selectJourney(state.plan.journeys[0]);
  const sel = state.selection;
  const bad = state.glyphs.filter(g => labelVisibleFor(g, sel) && !sel.partIds.has(g.partId)).map(g => g.partId);
  clearSelection();
  return bad;
})()`).length === 0);
check("with nothing selected every station is labelled", labels.all === true && labels.none === true);

/* ==========================================================================
 * VEHICLES RIDE THE DRAWN LINE
 * ======================================================================== */
/* the puck rides the DRAWN line (drawPts), which inside a bundle is the reference
   centreline, not the segment's own pair-averaged one */
const snap = run(`(() => {
  const seg = state.segByPair.get("#00933c|gf:0>mh:0");
  const mid = seg.drawPts[16];
  const near = snapToLine("#00933c", mid[0] + 5, mid[1], 24);      // beside the line
  const far = snapToLine("#00933c", mid[0] + 400, mid[1], 24);     // a depot move
  const wrongColour = snapToLine("#ee352e", mid[0] + 5, mid[1], 24);
  const red = state.segByPair.get("#ee352e|rv:0>wg:0");
  return {
    nearD: near ? Math.hypot(near[0]-mid[0], near[1]-mid[1]) : null,
    onLine: near ? pointPolylineDist(near, seg.drawPts) : null,
    far, wrongColour: wrongColour ? pointPolylineDist(wrongColour, red.drawPts) : null,
  };
})()`);
check("a train beside its line is snapped onto the drawn segment",
	snap.onLine !== null && snap.onLine < 1e-6, "d to line = " + snap.onLine);
check("...landing within a few blocks of where it really is", snap.nearD < 6, snap.nearD);
check("a train far from every segment keeps its raw position", snap.far === null, JSON.stringify(snap.far));
check("snapping never crosses to another line's colour", snap.wrongColour === null || snap.wrongColour < 1e-6);

/* ==========================================================================
 * SETTINGS: theme, line thickness, layers
 * ======================================================================== */
const theme = run(`(() => {
  const light = { ...PALETTE };
  applyTheme("dark");
  const dark = { ...PALETTE };
  const body = document.body.dataset.theme;
  applyTheme("light");
  const back = { ...PALETTE };
  return { light, dark, back, body, pref: state.prefs.theme };
})()`);
check("switching the theme swaps the JS palette object",
	theme.dark.paper === "#101014" && theme.light.paper === "#f6f3ec", JSON.stringify([theme.light.paper, theme.dark.paper]));
check("dark is MTA-Live: near-black land, white ink, dark water",
	theme.dark.ink === "#f2f3f5" && theme.dark.water === "#1b2a38", JSON.stringify(theme.dark));
check("dark raises the dimmed-network alpha", theme.dark.dim > theme.light.dim,
	theme.light.dim + " -> " + theme.dark.dim);
check("the theme is mirrored onto the document for the DOM side", theme.body === "dark", theme.body);
check("switching back restores the light palette exactly",
	JSON.stringify(theme.back) === JSON.stringify(theme.light));

const thick = run(`(() => {
  state.view.scale = 0.6;
  const base = ribbonWidth();
  setLineScale(1.6); const fat = ribbonWidth();
  setLineScale(0.6); const thin = ribbonWidth();
  setLineScale(9);  const clamped = state.prefs.lineScale;
  setLineScale(1);
  return { base, fat, thin, clamped };
})()`);
check("the thickness slider multiplies the ribbon width",
	Math.abs(thick.fat / thick.base - 1.6) < 1e-9 && Math.abs(thick.thin / thick.base - 0.6) < 1e-9,
	JSON.stringify(thick));
check("the thickness slider is clamped to 0.6x - 1.6x", thick.clamped === 1.6, thick.clamped);

const layers = run(`(() => {
  const before = { segs: state.ribbons.length, boats: state.ribbons.filter(s => s.mode === "boat").length,
    he: state.glyphs.some(g => g.partId === "he:0"), modes: modesPresent() };
  setModeVisible("boat", false);
  const after = { segs: state.ribbons.length, boats: state.ribbons.filter(s => s.mode === "boat").length,
    he: state.glyphs.some(g => g.partId === "he:0"), bc: state.glyphs.some(g => g.partId === "bc:0"),
    apT: state.glyphs.some(g => g.partId === "ap:1"), hidden: state.prefs.hiddenModes.slice() };
  const rides = buildGraph().rideEdges.filter(e => e.mode === "boat").length;
  setModeVisible("boat", true);
  // the mirror case: Harbor East and Bayfront's ferry berth are the parts a
  // trains-off view must keep, while the train-only trunk stations go
  setModeVisible("train", false);
  const noTrains = { trains: state.ribbons.filter(s => s.mode !== "boat").length,
    boats: state.ribbons.filter(s => s.mode === "boat").length,
    he: state.glyphs.some(g => g.partId === "he:0"), bf: state.glyphs.some(g => g.partId === "bf:0"),
    mh: state.glyphs.some(g => g.partId === "mh:0"), gf: state.glyphs.some(g => g.partId === "gf:0") };
  setModeVisible("train", true);
  const back = { segs: state.ribbons.length, boats: state.ribbons.filter(s => s.mode === "boat").length };
  return { before, after, back, rides, noTrains };
})()`);
check("the Layers list offers every mode in the data",
	JSON.stringify(layers.before.modes) === '["boat","train"]', JSON.stringify(layers.before.modes));
check("hiding Boats drops the ferry's segments", layers.before.boats === 2 && layers.after.boats === 0,
	layers.before.boats + " -> " + layers.after.boats);
check("...but a station shared with a visible mode stays (Harbor East also has the shuttle)",
	layers.before.he === true && layers.after.he === true
	&& layers.after.bc === true && layers.after.apT === true,
	JSON.stringify([layers.after.he, layers.after.bc, layers.after.apT]));
check("hiding Trains instead drops the train-only stations and keeps the ferry ones",
	layers.noTrains.trains === 0 && layers.noTrains.boats === 2
	&& layers.noTrains.mh === false && layers.noTrains.gf === false
	&& layers.noTrains.he === true && layers.noTrains.bf === true, JSON.stringify(layers.noTrains));
check("hiding a mode never touches the PLANNER graph", layers.rides === 2, layers.rides);
check("re-enabling the mode restores every segment",
	layers.back.segs === layers.before.segs && layers.back.boats === layers.before.boats,
	JSON.stringify([layers.before.segs, layers.after.segs, layers.back.segs]));
check("mode labels are human ('boat' -> 'Boats')",
	run(`modeLabel("boat") + "/" + modeLabel("train") + "/" + modeLabel("cable_car") + "/" + modeLabel("airplane")`)
	=== "Boats/Trains/Cable cars/Planes",
	run(`modeLabel("boat") + "/" + modeLabel("cable_car")`));

/* prefs round-trip through the persisted blob */
check("theme / thickness / layers all persist in the prefs blob", run(`(() => {
  const store = {};
  const real = localStorage.setItem;
  localStorage.setItem = (k, v) => { store[k] = v; };
  applyTheme("dark"); setLineScale(1.25); state.prefs.hiddenModes = ["boat"];
  savePrefs();
  localStorage.setItem = real;
  const blob = JSON.parse(store[PREFS_KEY] || "{}");
  applyTheme("light"); setLineScale(1); state.prefs.hiddenModes = [];
  return blob.theme === "dark" && Math.abs(blob.lineScale - 1.25) < 1e-9
    && JSON.stringify(blob.hiddenModes) === '["boat"]' && blob.planPrefs !== undefined;
})()`));


/* ==========================================================================
 * TRAIN CARD + FOLLOW, JOURNEY-FILTERED TRAINS, STATION PANEL
 * (the 2026-08-29 interaction pass — sections 10a / 10b of map.js)
 * ======================================================================== */

/* helper: push a clean vehicle set through the REAL frame handler (isFull drops
   whatever was there before), so every test below sees exactly its own feed */
run(`
	function _feed(list) {
		handleFrame({ schemaVersion: 1, serverTime: Date.now(), dimension: 0, vehicles: list }, true);
	}
	function _train(id, over) {
		return Object.assign({
			id, x: 636, y: 64, z: 200, kmh: 52, rev: false, rail: "cor_1", railT: 0.5,
			doors: false, dwellMs: 0, devMs: 0, manual: false, stop: 1,
			pPlat: "", nPlat: "gf_g", pFrac: 0.4,
			route: { id: "r4", name: "Baker Line||Bayfront", number: "4 IN", color: 0x00933C,
				dest: "Bayfront", nextStation: "Garfield Av" },
			consist: { cars: ["a", "b", "b", "b", "b", "a"], carLengths: [19, 19, 19, 19, 19, 19] },
		}, over || {});
	}
`);

/* ---- A. next stop resolves through nPlat, then the feed's own name, then nothing ---- */
const nx = run(`(() => {
  _feed([
    _train("t1"),
    _train("t2", { nPlat: "not_a_platform", route: { id: "r4", name: "Baker Line||Bayfront",
      number: "4 IN", color: 0x00933C, dest: "Bayfront", nextStation: "Museum|博物馆" } }),
    _train("t3", { nPlat: "", kmh: 0, doors: true, consist: null,
      route: { id: "r4", name: "Baker Line||Bayfront", number: "4 IN", color: 0x00933C, dest: "Bayfront" } }),
  ]);
  return {
    a: vehicleNextStop(state.vehicles.get("t1")),
    b: vehicleNextStop(state.vehicles.get("t2")),
    c: vehicleNextStop(state.vehicles.get("t3")),
    card: trainCardData("t1"), bare: trainCardData("t3"), missing: trainCardData("nope"),
  };
})()`);
check("next stop resolves nPlat -> station display name",
	nx.a && nx.a.name === "Garfield Av" && nx.a.source === "platform" && nx.a.stationId === "gf", JSON.stringify(nx.a));
check("...and carries the platform name with it", nx.a && nx.a.platform === "1", JSON.stringify(nx.a));
check("an unresolvable nPlat falls back to the feed's nextStation (first language only)",
	nx.b && nx.b.name === "Museum" && nx.b.source === "route", JSON.stringify(nx.b));
check("no nPlat and no nextStation shows NOTHING rather than a guess", nx.c === null, JSON.stringify(nx.c));
check("the card counts the real consist", nx.card.cars === 6, nx.card.cars);
check("the card normalises the service label and keeps the destination",
	nx.card.label === "4" && nx.card.dest === "Bayfront" && nx.card.hex === "#00933c", JSON.stringify(nx.card));
check("a train with no consist reports no car count", nx.bare.cars === 0, nx.bare.cars);
check("a stopped train with its doors open says so instead of '0 km/h'",
	nx.bare.kmh === 0 && nx.bare.doors === true, JSON.stringify([nx.bare.kmh, nx.bare.doors]));
check("an unknown vehicle id has no card", nx.missing === null, JSON.stringify(nx.missing));

/* ---- B. the card never gets clipped at a viewport edge ---- */
const anchor = run(`(() => ({
  mid: anchorFloatCard(600, 500, 232, 132, 1600, 1000),
  right: anchorFloatCard(1560, 500, 232, 132, 1600, 1000),
  top: anchorFloatCard(600, 10, 232, 132, 1600, 1000),
  bottom: anchorFloatCard(600, 995, 232, 132, 1600, 1000),
  narrow: anchorFloatCard(150, 500, 232, 132, 300, 1000),
}))()`);
check("a card beside a train opens to its right", anchor.mid.side === "right" && anchor.mid.left === 618, JSON.stringify(anchor.mid));
check("...and flips to the left near the right edge",
	anchor.right.side === "left" && anchor.right.left + 232 <= 1592, JSON.stringify(anchor.right));
check("a card near the top edge is pushed down into view", anchor.top.top === 8, JSON.stringify(anchor.top));
check("a card near the bottom edge is pulled up into view",
	anchor.bottom.top + 132 <= 992, JSON.stringify(anchor.bottom));
check("a viewport too narrow for either side still clamps the card on screen",
	anchor.narrow.left >= 8 && anchor.narrow.left + 232 <= Math.max(8, 300 - 8) + 232, JSON.stringify(anchor.narrow));

/* ---- C. follow: the state machine, driven exactly as the frame loop drives it ---- */
const fol = run(`(() => {
  const t0 = 1000000;
  const started = followReduce(null, { type: "start", id: "t1", at: t0 });
  const ticked = followReduce(started, { type: "tick", at: t0 + 1000, present: true });
  return {
    started, ticked,
    panned: followReduce(ticked, { type: "input", at: t0 + 1500 }),
    escaped: followReduce(ticked, { type: "escape", at: t0 + 1500 }),
    tapped: followReduce(ticked, { type: "user", at: t0 + 1500 }),
    filtered: followReduce(ticked, { type: "filtered", at: t0 + 1500 }),
    gone1: followReduce(ticked, { type: "tick", at: t0 + 5000, present: false }),
    gone2: followReduce(followReduce(ticked, { type: "tick", at: t0 + 5000, present: false }),
      { type: "tick", at: t0 + 10000, present: false }),
    gone3: followReduce(followReduce(followReduce(ticked, { type: "tick", at: t0 + 5000, present: false }),
      { type: "tick", at: t0 + 10000, present: false }), { type: "tick", at: t0 + 11500, present: false }),
    recovered: followReduce(followReduce(ticked, { type: "tick", at: t0 + 5000, present: false }),
      { type: "tick", at: t0 + 6000, present: true }),
    unknownEvent: followReduce(ticked, { type: "resize", at: t0 + 2000 }),
    tickWithNoFollow: followReduce(null, { type: "tick", at: t0, present: true }),
  };
})()`);
check("follow starts on the vehicle it was handed", fol.started && fol.started.vehicleId === "t1" && fol.started.fading === false, JSON.stringify(fol.started));
check("a tick with the vehicle present keeps following and refreshes lastSeenAt",
	fol.ticked && fol.ticked.lastSeenAt === 1001000 && fol.ticked.fading === false, JSON.stringify(fol.ticked));
check("a pan breaks follow", fol.panned === null, JSON.stringify(fol.panned));
check("Esc breaks follow", fol.escaped === null, JSON.stringify(fol.escaped));
check("tapping the pill breaks follow", fol.tapped === null, JSON.stringify(fol.tapped));
check("a selection that hides the line breaks follow", fol.filtered === null, JSON.stringify(fol.filtered));
check("a vehicle missing from the feed starts the pill fading but keeps following",
	fol.gone1 && fol.gone1.fading === true && fol.gone1.lastSeenAt === 1001000, JSON.stringify(fol.gone1));
check("...still following just under 10 s later", fol.gone2 && fol.gone2.fading === true, JSON.stringify(fol.gone2));
check("...and dropped once it has been gone more than 10 s", fol.gone3 === null, JSON.stringify(fol.gone3));
check("a vehicle that comes back stops the fade and follow continues",
	fol.recovered && fol.recovered.fading === false && fol.recovered.lastSeenAt === 1006000, JSON.stringify(fol.recovered));
check("an unrelated event leaves follow untouched", fol.unknownEvent === fol.ticked, JSON.stringify(fol.unknownEvent));
check("a tick with nothing being followed stays null", fol.tickWithNoFollow === null);

/* ---- D. journey filter: only the selected journey's LINE colours keep running ---- */
const filt = run(`(() => {
  const sel = { journey: { legs: [
    { kind: "ride", color: "#00933C" }, { kind: "walk" }, { kind: "ride", color: "#0039A6" },
  ] } };
  sel.lineHexes = selectionLineHexes(sel);
  const green = { route: { id: "r4", color: 0x00933C } };
  const greenOther = { route: { id: "r5o", color: 0x00933C } };   // same LINE, different route
  const blue = { route: { id: "rA", color: 0x0039A6 } };
  const red = { route: { id: "rR", color: 0xEE352E } };
  const orphan = { route: null };
  return {
    hexes: [...sel.lineHexes].sort(),
    green: vehiclePassesSelection(green, sel),
    greenOther: vehiclePassesSelection(greenOther, sel),
    blue: vehiclePassesSelection(blue, sel),
    red: vehiclePassesSelection(red, sel),
    orphan: vehiclePassesSelection(orphan, sel),
    noSelection: vehiclePassesSelection(red, null),
    walkOnly: vehiclePassesSelection(red, { journey: { legs: [{ kind: "walk" }] } }),
    recomputed: vehiclePassesSelection(red, { journey: sel.journey }),   // no cached lineHexes
  };
})()`);
check("a journey's line colours come off its ride legs",
	JSON.stringify(filt.hexes) === '["#0039a6","#00933c"]', JSON.stringify(filt.hexes));
check("trains on the journey's lines stay visible", filt.green && filt.blue, JSON.stringify(filt));
check("...including the OTHER direction/express of the same colour", filt.greenOther === true);
check("trains on any other line are hidden", filt.red === false);
check("a vehicle with no route at all is hidden under a selection", filt.orphan === false);
check("no selection hides nothing", filt.noSelection === true);
check("a journey with no ride legs hides nothing", filt.walkOnly === true);
check("the predicate works without a cached lineHexes set", filt.recomputed === false);

/* the real thing: select the demo's own journey and watch a red train disappear */
const filtLive = run(`(() => {
  _feed([
    _train("g1"),
    _train("r1", { rail: "red_1", nPlat: "fd_r",
      route: { id: "rR", name: "Ridge Line||South Yards", number: "1", color: 0xEE352E, dest: "South Yards" } }),
  ]);
  state.vehicles.get("g1").disp = { x: 636, z: 200 };
  state.vehicles.get("r1").disp = { x: 520, z: 400 };
  startFollow("r1");
  const followingBefore = !!state.follow;
  const j = state.plan.journeys[0];
  selectJourney(j);
  const out = {
    followingBefore, followingAfter: !!state.follow,
    hexes: [...state.selection.lineHexes].sort(),
    green: vehiclePassesSelection(state.vehicles.get("g1"), state.selection),
    red: vehiclePassesSelection(state.vehicles.get("r1"), state.selection),
  };
  // and following a train that STAYS visible survives the same selection
  startFollow("g1");
  selectJourney(j);
  out.greenFollowSurvives = !!state.follow && state.follow.vehicleId === "g1";
  stopFollow("stop");
  clearSelection();
  return out;
})()`);
check("selecting the demo journey keeps its own green trains running",
	filtLive.green === true && filtLive.hexes.includes("#00933c"), JSON.stringify(filtLive.hexes));
check("...and takes every other line's trains off the map", filtLive.red === false);
check("following a train whose line the new selection hides breaks follow",
	filtLive.followingBefore === true && filtLive.followingAfter === false, JSON.stringify(filtLive));
check("following a train the selection KEEPS survives it", filtLive.greenFollowSurvives === true);

/* ---- E. station panel: departure groups ---- */
const dep = run(`(() => {
  const graph = state.plan.graph || (state.plan.graph = buildGraph());
  const at = Date.now();
  _feed([_train("lv", { nPlat: "mh_g", pFrac: 0.5 })]);      // one live green train into Maple Heights
  const bc = buildDepartureGroups(graph, "bc", at);
  const mh = buildDepartureGroups(graph, "mh", at);
  const sy = buildDepartureGroups(graph, "sy", at);          // terminus of the red line only
  const he = buildDepartureGroups(graph, "he", at);
  const rf = state.routes.get("rF");
  const keep = rf.headwayMs;
  rf.headwayMs = 0;                                          // no live train, no headway
  // the headway rides on the GRAPH's ride edges, so the graph has to be rebuilt with it
  const heNoHeadway = buildDepartureGroups(buildGraph(), "he", at);
  rf.headwayMs = keep;
  state.plan.graph = null;
  return {
    bcHexes: bc.map(g => g.hex), bcLabels: bc.map(g => g.labels.join("/")),
    bcSorted: bc.map(g => g.soonestMs),
    bcRowsSorted: bc.map(g => g.rows.every((r, i) => i === 0 || r.departMs >= g.rows[i - 1].departMs)),
    bcPerDest: bc.map(g => {
      const n = {};
      for (const r of g.rows) n[r.destination] = (n[r.destination] || 0) + 1;
      return Math.max(...Object.values(n));
    }),
    bcDests: bc.map(g => [...new Set(g.rows.map(r => r.destination))].sort()),
    bcPlat: bc[0] && bc[0].rows[0] ? bc[0].rows[0].platformName : null,
    mhLive: mh.find(g => g.hex === "#00933c"),
    syCount: sy.length,
    heHexes: he.map(g => g.hex),
    heNoHeadway: heNoHeadway.length,
    withinHorizon: bc.every(g => g.rows.every(r => r.departMs - at <= 90 * 60000)),
  };
})()`);
check("departures are grouped by LINE colour, not by route",
	JSON.stringify(dep.bcHexes.slice().sort()) === '["#0039a6","#00933c"]', JSON.stringify(dep.bcHexes));
check("each group carries the normalised bullets of its routes",
	JSON.stringify(dep.bcLabels.slice().sort()) === '["4/5","A/C"]', JSON.stringify(dep.bcLabels));
check("groups are sorted by their soonest departure",
	dep.bcSorted.every((v, i) => i === 0 || v >= dep.bcSorted[i - 1]), JSON.stringify(dep.bcSorted));
check("rows inside a group are sorted by departure too", dep.bcRowsSorted.every(Boolean), JSON.stringify(dep.bcRowsSorted));
check("a group lists at most 2 departures per destination",
	dep.bcPerDest.every(n => n <= 2), JSON.stringify(dep.bcPerDest));
check("both directions of the green trunk appear as their own destinations",
	dep.bcDests.some(d => d.includes("Bayfront") && d.includes("Northgate")), JSON.stringify(dep.bcDests));
check("rows name the platform they leave from", dep.bcPlat === "1" || dep.bcPlat === "3", JSON.stringify(dep.bcPlat));
check("a streamed train inbound to the platform makes its row LIVE",
	!!dep.mhLive && dep.mhLive.rows.some(r => r.live && r.vehicleId === "lv"), JSON.stringify(dep.mhLive && dep.mhLive.rows));
check("the rest of the same group is schedule-derived, and flagged as such",
	!!dep.mhLive && dep.mhLive.rows.some(r => !r.live && r.vehicleId === null),
	JSON.stringify(dep.mhLive && dep.mhLive.rows.map(r => r.live)));
check("a platform that is a route's LAST stop offers no departure", dep.syCount === 0, dep.syCount);
check("a line with neither a live train nor a headway shows NOTHING, not a made-up time",
	dep.heHexes.length === 1 && dep.heNoHeadway === 0, JSON.stringify([dep.heHexes, dep.heNoHeadway]));
check("no row is further out than the 90-minute horizon", dep.withinHorizon === true);

/* ---- F. exits ---- */
const ex = run(`(() => ({
  bc: stationExits(state.stations.get("bc")),
  ap: stationExits(state.stations.get("ap")),
  none: stationExits(state.stations.get("mh")),
  nullStation: stationExits(null),
  junk: stationExits({ exits: [
    null, { name: "", destinations: [] }, { name: "Exit Z" },
    { name: "Exit Q|Q出口", destinations: ["Pier|码头", "   ", "Ferry hall"] },
    { name: "A1", destinations: ["Main St"] },
  ] }),
}))()`);
check("mapdata exits map to rows", ex.bc.length === 3 && ex.bc[0].text === "Exit A · Main St, Transit Museum",
	JSON.stringify(ex.bc[0]));
check("...keeping the destinations as their own list too",
	JSON.stringify(ex.bc[0].destinations) === '["Main St","Transit Museum"]', JSON.stringify(ex.bc[0].destinations));
check("a second demo station publishes exits as well", ex.ap.length === 2, JSON.stringify(ex.ap.map(e => e.name)));
check("a station with no exits field yields no rows (the section is omitted)",
	ex.none.length === 0 && ex.nullStation.length === 0, JSON.stringify(ex.none));
check("junk entries are dropped, blanks trimmed, names shown first-language only",
	ex.junk.length === 3 && ex.junk[0].text === "Exit Z"
	&& ex.junk[1].text === "Exit Q · Pier, Ferry hall", JSON.stringify(ex.junk));
check("MTR's bare exit letters get the word added (never twice)",
	ex.junk[2].text === "Exit A1 · Main St" && ex.junk[0].name === "Exit Z", JSON.stringify(ex.junk[2]));

/* ---- G. accessibility ---- */
const acc = run(`(() => ({
  ng: stationAccessibility(state.stations.get("ng")),
  mh: stationAccessibility(state.stations.get("mh")),
  gf: stationAccessibility(state.stations.get("gf")),
  none: stationAccessibility(null),
}))()`);
check("the station flag with no explicit list means ALL platforms are step-free",
	acc.mh.all === true && acc.mh.stepFree === true, JSON.stringify(acc.mh));
check("an explicit accessiblePlatforms list is listed platform by platform instead",
	acc.ng.all === false && acc.ng.stepFree === true && acc.ng.platforms.length === 2,
	JSON.stringify(acc.ng));
check("...with the right platform marked", JSON.stringify(acc.ng.platforms.map(p => p.name + ":" + p.accessible))
	=== '["1:true","2:false"]', JSON.stringify(acc.ng.platforms));
check("a station with no step-free access says so", acc.gf.stepFree === false && acc.gf.all === false, JSON.stringify(acc.gf));
check("an unknown station degrades to nothing", acc.none.stepFree === false && acc.none.platforms.length === 0);

/* ---- H. the panel's assembled payload + its planner buttons ---- */
const panel = run(`(() => {
  openStationPanel("bc", "bc:0");
  const d = stationPanelData("bc", "bc:0", Date.now());
  const before = { from: state.plan.from && state.plan.from.stationId, to: state.plan.to && state.plan.to.stationId };
  planFromPanel("to");
  const afterTo = { from: state.plan.from && state.plan.from.stationId, to: state.plan.to && state.plan.to.stationId };
  planFromPanel("from");
  const afterFrom = { from: state.plan.from && state.plan.from.stationId, to: state.plan.to && state.plan.to.stationId };
  const open = !!state.stationPanel;
  closeStationPanel();
  // restore the demo query the rest of the suite expects
  state.plan.from = { stationId: "mh", partId: null };
  state.plan.to = { stationId: "ap", partId: "ap:1" };
  replan();
  return { d, before, afterTo, afterFrom, open, closed: !!state.stationPanel,
    unknown: stationPanelData("nope", null, Date.now()) };
})()`);
check("the panel payload names the station and its part", panel.d.name === "Baker City Central" && panel.d.partId === "bc:0",
	JSON.stringify([panel.d.name, panel.d.partId]));
check("the panel lists the lines serving the station",
	JSON.stringify(panel.d.lines.map(l => l.hex).sort()) === '["#0039a6","#00933c"]', JSON.stringify(panel.d.lines));
check("the panel carries departures, exits and accessibility together",
	panel.d.groups.length === 2 && panel.d.exits.length === 3 && panel.d.access.all === true,
	JSON.stringify([panel.d.groups.length, panel.d.exits.length, panel.d.access.all]));
check("'Plan to here' fills the To field", panel.afterTo.to === "bc", JSON.stringify(panel.afterTo));
check("'Plan from here' fills the From field", panel.afterFrom.from === "bc", JSON.stringify(panel.afterFrom));
check("the panel stays open while its buttons are used, and closes on demand",
	panel.open === true && panel.closed === false, JSON.stringify([panel.open, panel.closed]));
check("an unknown station has no panel payload", panel.unknown === null);

/* ---- I. Esc unwinds the overlays before the journey selection ---- */
const esc = run(`(() => {
  _feed([_train("e1")]);
  state.vehicles.get("e1").disp = { x: 636, z: 200 };
  openTrainCard("e1");
  startFollow("e1");
  openStationPanel("bc", "bc:0");
  selectJourney(state.plan.journeys[0]);
  const before = { card: !!state.trainCard, follow: !!state.follow, panel: !!state.stationPanel, sel: !!state.selection };
  escapePressed();
  const after = { card: !!state.trainCard, follow: !!state.follow, panel: !!state.stationPanel, sel: !!state.selection };
  escapePressed();
  const twice = { sel: !!state.selection };
  state.vehicles.clear();
  return { before, after, twice };
})()`);
check("Esc closes the card, the panel and follow in one go",
	esc.after.card === false && esc.after.follow === false && esc.after.panel === false, JSON.stringify(esc.after));
check("...leaving the journey selected", esc.before.sel === true && esc.after.sel === true, JSON.stringify(esc.after));
check("a second Esc then clears the selection", esc.twice.sel === false, JSON.stringify(esc.twice));

/* ---- J. basemap mask styling (the schematic water/woodland layer) ---- */
const maskT = run(`(() => {
  const light = applyTheme("light");
  const l = [maskClassColor(1), maskClassColor(2), maskClassColor(3), maskClassColor(6), maskClassColor(0)];
  const dark = applyTheme("dark");
  const d = [maskClassColor(1), maskClassColor(2)];
  applyTheme("light");
  return { l, d, lw: light.water, dw: dark.water, rgb: hexRgb("#bcd6ea"), bad: hexRgb("nope"),
    noCtx: styleMask({}, 4) === null, demoMeta: !!demoSatmeta().mask };
})()`);
check("mask classes style to the theme's water / woodland / snow, land stays transparent",
	maskT.l[0] === maskT.lw && maskT.l[1] && maskT.l[2] && maskT.l[3] === null && maskT.l[4] === null, JSON.stringify(maskT.l));
check("...and follow the theme", maskT.d[0] === maskT.dw && maskT.d[0] !== maskT.l[0]);
check("hex colours parse to rgb triples", JSON.stringify(maskT.rgb) === "[188,214,234]" && maskT.bad === null);
check("styling without a real 2D context degrades to null (no throw)", maskT.noCtx === true);
check("the demo index advertises the class mask", maskT.demoMeta === true);

const J = (x) => JSON.stringify(x);

/* ==========================================================================
 * THE 2026-08-29 FEATURE SET: express marks, point planning, player GPS,
 * line view, satellite basemap.
 * ======================================================================== */

/* ---- K. express / local stop marks (feature 1) ---- */
const marks = run(`(() => {
  const o = {};
  for (const [k, v] of state.stopMarks) o[k] = v;
  return {
    marks: o,
    glyphs: Object.fromEntries(state.glyphs.map(g => [g.partId, g.fullService])),
    n: state.stopMarks.size,
  };
})()`);
check("every station part gets a stop mark", marks.n === 21, marks.n);
check("the green express skips Maple Heights, Garfield Av and Museum -> open rings",
	marks.marks["mh:0"] === false && marks.marks["gf:0"] === false && marks.marks["mu:0"] === false,
	J([marks.marks["mh:0"], marks.marks["gf:0"], marks.marks["mu:0"]]));
check("the express's own two ends stay solid (every green service calls there)",
	marks.marks["ng:0"] === true && marks.marks["bc:0"] === true,
	J([marks.marks["ng:0"], marks.marks["bc:0"]]));
check("the blue C service running past Harborview makes it local-only too",
	marks.marks["hv:0"] === false, marks.marks["hv:0"]);
check("Riverside is solid — every blue service calls there", marks.marks["rs:0"] === true);
check("a line with ONE service marks nothing local (the whole red line)",
	["rv:0", "wg:0", "fd:0", "sy:0"].every((k) => marks.marks[k] === true),
	J(["rv:0", "wg:0", "fd:0", "sy:0"].map((k) => marks.marks[k])));
check("a route on another branch never counts against a part (orange + ferry at the Airport)",
	marks.marks["ap:0"] === true && marks.marks["ap:1"] === true && marks.marks["he:0"] === true,
	J([marks.marks["ap:0"], marks.marks["ap:1"], marks.marks["he:0"]]));
check("the verdict is cached on the glyph the renderer reads",
	marks.glyphs["mu:0"] === false && marks.glyphs["bc:0"] === true,
	J([marks.glyphs["mu:0"], marks.glyphs["bc:0"]]));

/* the classifier must be a pure function of the built geometry: rebuilding gives
   the same answer, and the LINE VIEW reads the very same map */
const marksStable = run(`(() => {
  const before = [...state.stopMarks.entries()].sort().map(e => e.join("=")).join(",");
  prepareGeometry();
  const after = [...state.stopMarks.entries()].sort().map(e => e.join("=")).join(",");
  return before === after;
})()`);
check("the stop marks survive a geometry rebuild unchanged", marksStable === true);

/* ---- L. satellite tile maths (feature 5) ---- */
const tiles = run(`(() => {
  const m = { scale: 2, tileSamples: 256, originX: 0, originZ: 0 };
  const shifted = { scale: 2, tileSamples: 256, originX: -1024, originZ: 512 };
  const probes = [[0, 0], [1, 1], [511, 511], [512, 0], [1023, 777], [-1, -1], [-513, -513], [1535, 1023]];
  const roundTrip = probes.map(([x, z]) => {
    const t = satTileOf(m, x, z);
    const w = satWorldOf(m, t.tx, t.tz, t.px, t.pz);
    // identity up to the sample quantum: one PNG pixel IS 'scale' blocks
    const qx = Math.floor(x / 2) * 2, qz = Math.floor(z / 2) * 2;
    return { x, z, t, w, exact: w[0] === qx && w[1] === qz };
  });
  const shiftedTrip = probes.map(([x, z]) => {
    const t = satTileOf(shifted, x, z);
    const w = satWorldOf(shifted, t.tx, t.tz, t.px, t.pz);
    const qx = -1024 + Math.floor((x + 1024) / 2) * 2, qz = 512 + Math.floor((z - 512) / 2) * 2;
    return w[0] === qx && w[1] === qz;
  });
  const inRange = roundTrip.every(r => r.t.px >= 0 && r.t.px < 256 && r.t.pz >= 0 && r.t.pz < 256);
  const e00 = satTileExtent(m, 0, 0), e10 = satTileExtent(m, 1, 0), e01 = satTileExtent(m, 0, 1);
  const eNeg = satTileExtent(m, -1, -1);
  return {
    span: satTileSpan(m), roundTrip, inRange, shifted: shiftedTrip.every(Boolean),
    e00, e10, e01, eNeg,
    originPixel: satWorldOf(m, 0, 0, 0, 0),
    lastPixel: satWorldOf(m, 0, 0, 255, 255),
    changed: {
      none: satmetaChanged(null, null),
      firstReal: satmetaChanged(null, { available: true, scannedAt: 1, tiles: [[0, 0]] }),
      firstEmpty: satmetaChanged(null, { available: false, scannedAt: 0, tiles: [] }),
      same: satmetaChanged({ scannedAt: 7 }, { available: true, scannedAt: 7, tiles: [[0, 0]] }),
      rescan: satmetaChanged({ scannedAt: 7 }, { available: true, scannedAt: 8, tiles: [[0, 0]] }),
    },
  };
})()`);
check("one tile covers 512 x 512 blocks (scale 2 x 256 samples)", tiles.span === 512, tiles.span);
check("world -> tile -> world is the identity up to one sample (2 blocks)",
	tiles.roundTrip.every((r) => r.exact), J(tiles.roundTrip.filter((r) => !r.exact)));
check("...with a non-zero, negative origin too", tiles.shifted === true);
check("every pixel index lands inside its own tile (floor, never truncation)",
	tiles.inRange === true, J(tiles.roundTrip.map((r) => [r.t.tx, r.t.tz, r.t.px, r.t.pz])));
check("negative world coords floor into negative tiles",
	J(tiles.roundTrip[5].t) === J({ tx: -1, tz: -1, px: 255, pz: 255 }), J(tiles.roundTrip[5].t));
check("tile (0,0)'s pixel (0,0) is the index origin", J(tiles.originPixel) === J([0, 0]), J(tiles.originPixel));
check("tile (0,0)'s last pixel is one sample short of the next tile",
	J(tiles.lastPixel) === J([510, 510]), J(tiles.lastPixel));
check("tile extents are contiguous and half-open",
	J(tiles.e00) === J([0, 0, 512, 512]) && J(tiles.e10) === J([512, 0, 1024, 512])
	&& J(tiles.e01) === J([0, 512, 512, 1024]) && J(tiles.eNeg) === J([-512, -512, 0, 0]),
	J([tiles.e00, tiles.e10, tiles.e01, tiles.eNeg]));
check("the satmeta refetch gate mirrors the terrain one",
	tiles.changed.none === false && tiles.changed.firstReal === true && tiles.changed.firstEmpty === false
	&& tiles.changed.same === false && tiles.changed.rescan === true, J(tiles.changed));

/* the demo really does publish a scan, on the documented 512 grid */
const demoSat = run(`({
  available: state.satmeta.available, scale: state.satmeta.scale, n: state.satmeta.tileSamples,
  ox: state.satmeta.originX, oz: state.satmeta.originZ, tiles: state.satmeta.tiles,
  covers: (() => {
    const m = state.satmeta;
    const t = satTileOf(m, 640, 468);                 // Baker City Central
    return m.tiles.some(([tx, tz]) => tx === t.tx && tz === t.tz);
  })(),
})`);
check("demo mode publishes 4 satellite tiles on 512-block origins",
	demoSat.available === true && demoSat.tiles.length === 4
	&& demoSat.ox % 512 === 0 && demoSat.oz % 512 === 0, J(demoSat));
check("...and they actually cover the demo city", demoSat.covers === true);

/* ---- M. point nodes in the graph (feature 2) ---- */
const point = run(`(() => {
  const g = state.plan.graph || (state.plan.graph = buildGraph());
  const beforeAdj = (g.adjacency.get("mu_g") || { transfers: [] }).transfers.length;
  const beforeNodes = g.nodes.size;
  const pt = { id: "@from", xz: [700, 380], y: 70, label: "Dropped pin" };
  const r = injectPointNodes(g, [pt]);
  const edges = r.graph.adjacency.get("@from").transfers;
  const mu = state.platforms.get("mu_g");
  const expectM = Math.hypot(700 - mu.xz[0], 380 - mu.xz[1], 70 - (mu.y || 0));
  const back = r.graph.adjacency.get("mu_g").transfers.filter(e => e.to === "@from");
  const far = injectPointNodes(g, [{ id: "@to", xz: [-5000, -5000], label: "x" }]);
  return {
    added: r.added,
    reach: r.reach.get("@from").map(x => x.stationId),
    reachMeters: r.reach.get("@from").map(x => Math.round(x.meters)),
    edgeTargets: edges.map(e => e.to).sort(),
    muEdge: edges.find(e => e.to === "mu_g"),
    expectM, expectS: expectM / walkSpeed() + 30,
    backEdge: back.length,
    baseAdjUntouched: beforeAdj === (g.adjacency.get("mu_g") || { transfers: [] }).transfers.length,
    baseNodesUntouched: beforeNodes === g.nodes.size && !g.nodes.has("@from"),
    farAdded: far.added, farReach: far.reach.get("@to").length,
  };
})()`);
check("a dropped pin reaches at most 3 stations, nearest first",
	point.added.length === 1 && point.reach.length === 3
	&& point.reachMeters.every((m, i) => i === 0 || m >= point.reachMeters[i - 1]),
	J([point.reach, point.reachMeters]));
check("...all of them inside the 300-block walking radius",
	point.reachMeters.every((m) => m <= 300), J(point.reachMeters));
check("it links to EVERY platform of each station it reaches",
	J(point.edgeTargets) === J(["bc_b", "bc_g", "gf_g", "mu_g"]), J(point.edgeTargets));
check("walk edges use the 3D distance at walkSpeed() + a 30 s buffer",
	Math.abs(point.muEdge.meters - point.expectM) < 1e-6
	&& Math.abs(point.muEdge.seconds - point.expectS) < 1e-6,
	J([point.muEdge.meters, point.muEdge.seconds, point.expectM, point.expectS]));
check("the edge exists in both directions (walk to the pin, and from it)", point.backEdge === 1);
check("the BASE graph is never mutated by the overlay",
	point.baseAdjUntouched === true && point.baseNodesUntouched === true,
	J([point.baseAdjUntouched, point.baseNodesUntouched]));
check("a pin with no station within 300 blocks is simply not added",
	point.farAdded.length === 0 && point.farReach === 0, J([point.farAdded, point.farReach]));

const pointPlan = run(`(() => {
  const g = state.plan.graph;
  const from = { point: [700, 380], y: 70, label: "Dropped pin" };
  const res = planEndpoints(g, from, { stationId: "ap", partId: "ap:1" }, { mode: "fastest" }, Date.now());
  const j = res.journeys[0];
  const lead = j && j.legs[0];
  const nowhere = planEndpoints(g, { point: [-5000, -5000] }, { stationId: "ap" }, { mode: "fastest" }, Date.now());
  const bothPoints = planEndpoints(g, { point: [700, 380], y: 70 }, { point: [1360, 650], y: 64 }, { mode: "fastest" }, Date.now());
  const plain = planEndpoints(g, { stationId: "mh" }, { stationId: "ap", partId: "ap:1" }, { mode: "fastest" }, Date.now());
  return {
    n: res.journeys.length, err: res.error,
    leadKind: lead && lead.kind, leadFromPoint: lead && lead.fromPoint, leadToName: lead && lead.toName,
    leadMeters: lead && Math.round(lead.meters),
    chip: lead ? walkChipHtml(lead, false) : "",
    endLabel: lead ? legEndLabel(lead, "from") : "",
    nowhereErr: nowhere.error, nowhereN: nowhere.journeys.length,
    bothN: bothPoints.journeys.length,
    bothTail: bothPoints.journeys[0] ? bothPoints.journeys[0].legs[bothPoints.journeys[0].legs.length - 1] : null,
    plainN: plain.journeys.length, plainErr: plain.error,
    pointNodesAfterPlain: state.pointNodes.size,
  };
})()`);
check("a pin origin plans a real journey", pointPlan.n >= 1 && pointPlan.err === null, J([pointPlan.n, pointPlan.err]));
check("its first leg is the walk OFF the pin", pointPlan.leadKind === "walk" && pointPlan.leadFromPoint === "@from",
	J([pointPlan.leadKind, pointPlan.leadFromPoint]));
check("the walk chip names where it lands ('… to Baker City Central')",
	/Walk \d+ m · \d+ min to /.test(pointPlan.chip) && pointPlan.chip.includes(pointPlan.leadToName),
	pointPlan.chip);
check("the itinerary calls the pin 'Dropped pin'", pointPlan.endLabel === "Dropped pin", pointPlan.endLabel);
check("a pin nowhere near a station gets the walking-range empty state, not 'no journey'",
	pointPlan.nowhereN === 0 && /within walking range/.test(pointPlan.nowhereErr || ""), J(pointPlan.nowhereErr));
check("pin -> pin works, and its LAST leg is the walk onto the destination pin",
	pointPlan.bothN >= 1 && pointPlan.bothTail && pointPlan.bothTail.kind === "walk"
	&& pointPlan.bothTail.toPoint === "@to", J(pointPlan.bothTail && pointPlan.bothTail.toPoint));
check("station -> station still goes through the memoised planner untouched",
	pointPlan.plainN >= 2 && pointPlan.plainErr === null && pointPlan.pointNodesAfterPlain === 0,
	J([pointPlan.plainN, pointPlan.pointNodesAfterPlain]));

/* ---- N. cross-street transfer edges (feature 2) ---- */
const street = run(`(() => {
  const g = buildGraph();
  const edges = g.transferEdges.filter(e => e.street);
  const pairs = [...new Set(edges.map(e => {
    const a = g.nodes.get(e.from), b = g.nodes.get(e.to);
    return [a.stationId, b.stationId].sort().join("~");
  }))].sort();
  const perStation = new Map();
  for (const e of edges) {
    const a = g.nodes.get(e.from);
    if (!perStation.has(a.stationId)) perStation.set(a.stationId, new Set());
    perStation.get(a.stationId).add(g.nodes.get(e.to).stationId);
  }
  const one = edges.find(e => e.from === "rv_r");
  return {
    pairs, count: edges.length,
    maxPerStation: Math.max(0, ...[...perStation.values()].map(s => s.size)),
    maxMeters: Math.max(...edges.map(e => e.meters)),
    bothWays: edges.some(e => e.from === "rv_r" && e.to === "ng_b") && edges.some(e => e.from === "ng_b" && e.to === "rv_r"),
    secondsOk: Math.abs(one.seconds - (one.meters / walkSpeed() + 30)) < 1e-9,
    sameStation: edges.every(e => e.sameStation === false),
    adjacentExcluded: !edges.some(e => {
      const a = g.nodes.get(e.from).stationId, b = g.nodes.get(e.to).stationId;
      return (a === "mh" && b === "gf") || (a === "gf" && b === "mu") || (a === "mu" && b === "bc") || (a === "ng" && b === "mh");
    }),
  };
})()`);
check("the demo's two genuine cross-street interchanges are found",
	J(street.pairs) === J(["hc~wg", "ng~rv"]), J(street.pairs));
check("...in both directions", street.bothWays === true);
check("...never further apart than 120 blocks", street.maxMeters <= 120, street.maxMeters);
check("...capped at 3 neighbours per station", street.maxPerStation <= 3, street.maxPerStation);
check("...timed like every other walk (m / walkSpeed() + 30 s)", street.secondsOk === true);
check("...and never marked as a same-station transfer", street.sameStation === true);
check("consecutive stops of ONE line are excluded (a street walk is an interchange, not a ride)",
	street.adjacentExcluded === true);
const streetChain = run(`(() => {
  const g = state.plan.graph;
  planMemo.entries.clear();
  const js = planJourneys(g, "rv", "ap", { mode: "fastest", stepFree: false }, Date.now());
  const j = js[0];
  return {
    n: js.length,
    legs: j ? j.legs.map(l => l.kind) : [],
    walks: j ? j.legs.filter(l => l.kind === "walk").length : 0,
    firstWalkM: j ? Math.round(j.legs[0].meters) : 0,
  };
})()`);
check("a street transfer opens a journey the timetable alone cannot (Ridgeview -> Airport)",
	streetChain.n >= 1 && streetChain.legs[0] === "walk" && streetChain.firstWalkM <= 120,
	J(streetChain));

/* ---- O. who am I? (feature 3) ---- */
const self = run(`({
  exact: matchSelfPlayer(["Thomas", "Riley"], "Thomas"),
  lower: matchSelfPlayer(["Thomas", "Riley"], "thomas"),
  upper: matchSelfPlayer(["Thomas", "Riley"], "THOMAS"),
  spaced: matchSelfPlayer(["Thomas"], "  thomas  "),
  miss: matchSelfPlayer(["Thomas"], "Tom"),
  empty: matchSelfPlayer(["Thomas"], ""),
  none: matchSelfPlayer([], "Thomas"),
  nullish: matchSelfPlayer(null, null),
  canonical: matchSelfPlayer(["ThOmAs"], "thomas"),
})`);
check("?player= matches a streamed name case-insensitively",
	self.exact === "Thomas" && self.lower === "Thomas" && self.upper === "Thomas" && self.spaced === "Thomas",
	J(self));
check("...and answers with the FEED's spelling, not the rider's", self.canonical === "ThOmAs", self.canonical);
check("an unknown / empty / absent name is nobody",
	self.miss === "" && self.empty === "" && self.none === "" && self.nullish === "", J(self));

const gps = run(`(() => {
  const t = Date.now();
  handleFrame({ serverTime: t, dimension: 0, vehicles: [], players: [
    { name: "Thomas", x: 640, y: 64, z: 470 }, { name: "Riley", x: 1210, y: 64, z: 640 },
  ] }, false);
  state.prefs.selfPlayer = "thomas";
  state.selfManual = true;
  const found = resolveSelfPlayer();
  const p0 = selfPlayerPos();
  handleFrame({ serverTime: t + 250, dimension: 0, vehicles: [], players: [
    { name: "Thomas", x: 660, y: 64, z: 470 }, { name: "Riley", x: 1210, y: 64, z: 640 },
  ] }, false);
  const mid = playerPos(state.players.get("Thomas"), t + 125);
  const p1 = selfPlayerPos();
  // the locate button
  state.view.x = 0; state.view.z = 0;
  const located = locateOrFit();
  const view = { x: Math.round(state.view.x), z: Math.round(state.view.z) };
  // a stale player drops out
  handleFrame({ serverTime: t + 60000, dimension: 0, vehicles: [], players: [
    { name: "Thomas", x: 660, y: 64, z: 470 },
  ] }, false);
  const after = [...state.players.keys()];
  return { found, p0, p1, mid, located, view, after, count: state.players.size };
})()`);
check("players arrive on the stream and resolve to a self dot",
	gps.found === "Thomas" && gps.p0.x === 640 && gps.p0.z === 470, J([gps.found, gps.p0]));
check("positions interpolate between 4 Hz samples",
	Math.abs(gps.mid.x - 650) < 0.6 && gps.p1.x === 660, J([gps.mid, gps.p1]));
check("locate recentres on the rider instead of fitting the network",
	gps.located === true && gps.view.x === 660 && gps.view.z === 470, J([gps.located, gps.view]));
check("a player who stops arriving is dropped", J(gps.after) === J(["Thomas"]), J(gps.after));

const gpsPlan = run(`(() => {
  const before = { from: state.plan.from, to: state.plan.to };
  state.plan.to = { stationId: "ap", partId: "ap:1" };
  const set = planFromMyLocation();
  const first = state.plan.from.point.slice();
  // a small step must NOT re-plan…
  state.players.get("Thomas").samples.push({ t: Date.now(), x: 668, y: 64, z: 474 });
  const small = followLiveOrigin();
  // …a real walk must
  state.players.get("Thomas").samples.push({ t: Date.now(), x: 720, y: 64, z: 500 });
  const big = followLiveOrigin();
  const moved = state.plan.from.point.slice();
  const label = state.plan.from.label;
  state.plan.from = before.from; state.plan.to = before.to;
  replan();
  return { set: !!set, first, small, big, moved, label, live: true };
})()`);
check("'Plan from my location' becomes a live point origin",
	gpsPlan.set === true && gpsPlan.label === "My location" && J(gpsPlan.first) === J([660, 470]), J(gpsPlan));
check("...which ignores a few blocks of drift", gpsPlan.small === false);
check("...and follows the rider once they have moved 32 blocks",
	gpsPlan.big === true && J(gpsPlan.moved) === J([720, 500]), J(gpsPlan.moved));

/* ---- P. line view (feature 4) ---- */
const lv = run(`(() => {
  const d = lineViewData("#00933c");
  const blue = lineViewData("#0039a6");
  const red = lineViewData("#EE352E".toLowerCase());
  const sel = selectLine("#00933c");
  const greenSegs = state.ribbons.filter(s => s.hex === "#00933c").map(s => s.id).sort();
  const optionsHtml = document.getElementById("options").innerHTML;
  const kept = { kind: sel.kind, hex: sel.hex };
  const dimmed = state.ribbons.filter(s => !sel.ribbonKeys.has(s.id)).length;
  clearSelection();
  return {
    labels: d.labels, names: d.names, everyMin: d.everyMin, dominant: d.dominantRouteId,
    stops: d.stops.map(s => s.name), skips: d.stops.filter(s => s.skip).map(s => s.name),
    rides: d.stops.map(s => Math.round(s.rideSeconds)),
    services: d.services.map(s => s.label + ">" + s.dest),
    blueStops: blue.stops.map(s => s.name), blueSkips: blue.stops.filter(s => s.skip).map(s => s.name),
    redServices: red.services.length,
    kept, greenSegs, selKeys: [...sel.ribbonKeys].sort(), dimmed,
    parts: [...sel.partIds].sort(),
    lineHexes: [...sel.lineHexes],
    html: optionsHtml,
    cleared: state.selection === null,
  };
})()`);
check("line view lists the dominant route's stops IN ORDER",
	J(lv.stops) === J(["Northgate", "Maple Heights", "Garfield Av", "Museum", "Baker City Central", "Southport", "Bayfront"]),
	J(lv.stops));
check("...marking exactly the express-skipped ones", J(lv.skips) === J(["Maple Heights", "Garfield Av", "Museum"]), J(lv.skips));
check("...with the local's own leg times between adjacent stops",
	J(lv.rides) === J([140, 165, 160, 155, 190, 300, 0]), J(lv.rides));
check("the dominant route is the all-stops local, not the express", lv.dominant === "r4", lv.dominant);
check("frequency comes off the headway", lv.everyMin === 5, lv.everyMin);
check("bullets and names are the whole line, direction stripped",
	J(lv.labels) === J(["4", "5"]) && J(lv.names) === J(["Baker Line", "Baker Express"]), J([lv.labels, lv.names]));
check("per-service chips appear when a line runs more than one service",
	lv.services.length === 4 && lv.redServices === 1, J([lv.services, lv.redServices]));
check("a line whose OTHER service skips a stop marks it there too (blue past Harborview)",
	J(lv.blueSkips) === J(["Harborview"]), J(lv.blueSkips));
check("selecting a line lights every one of its segments and nothing else",
	lv.kept.kind === "line" && J(lv.selKeys) === J(lv.greenSegs) && lv.dimmed > 0,
	J([lv.kept, lv.selKeys.length, lv.greenSegs.length, lv.dimmed]));
check("...and every station it calls at", ["ng:0", "mh:0", "gf:0", "mu:0", "bc:0", "sp:0", "bf:0"]
	.every((p) => lv.parts.includes(p)), J(lv.parts));
check("...so only that line's trains keep running", J(lv.lineHexes) === J(["#00933c"]), J(lv.lineHexes));
check("the line card replaces the option list", /lv-stop/.test(lv.html) && /lv-close/.test(lv.html));
check("closing the line view restores the planner", lv.cleared === true);

const lvLife = run(`(() => {
  selectLine("#00933c");
  const openThen = !!state.selection;
  // a minute-boundary replan must not steal the map back
  replan(true);
  const survived = !!state.selection && state.selection.kind === "line";
  // …but picking a journey does replace it
  selectOption(0);
  const replaced = state.selection && state.selection.kind;
  clearSelection();
  const unknown = selectLine("#123456");
  return { openThen, survived, replaced, unknown };
})()`);
check("a line view survives the live ticker's re-plan", lvLife.openThen === true && lvLife.survived === true, J(lvLife));
check("...and is replaced the moment the rider picks a journey", lvLife.replaced === "journey", lvLife.replaced);
check("an unknown colour opens nothing", lvLife.unknown === null);

/* ---- Q. click priority (feature 2 + 4) ---- */
const hit = run(`({
  order: HIT_ORDER.slice(),
  train: pickHit({ train: 1, glyph: 1, label: 1, chip: 1, ribbon: 1, pick: 1 }),
  glyph: pickHit({ glyph: 1, label: 1, chip: 1, ribbon: 1, pick: 1 }),
  label: pickHit({ label: 1, chip: 1, ribbon: 1, pick: 1 }),
  chip: pickHit({ chip: 1, ribbon: 1, pick: 1 }),
  ribbon: pickHit({ ribbon: 1, pick: 1 }),
  pick: pickHit({ pick: 1 }),
  bare: pickHit({}),
  nothing: pickHit(null),
})`);
check("hit priority is train > glyph > label > chip > ribbon > pin",
	J(hit.order) === J(["train", "glyph", "label", "chip", "ribbon", "pick"]), J(hit.order));
check("...and each tier wins over everything under it",
	hit.train === "train" && hit.glyph === "glyph" && hit.label === "label"
	&& hit.chip === "chip" && hit.ribbon === "ribbon" && hit.pick === "pick", J(hit));
check("bare paper clears", hit.bare === "clear" && hit.nothing === "clear");

const pickFlow = run(`(() => {
  const before = { from: state.plan.from, to: state.plan.to };
  state.plan.from = null; state.plan.to = null;
  const armed = armMapPick("from");
  const cursorOn = document.getElementById("map").classList.contains("picking");
  // the map is armed, so a click on bare paper drops the pin
  const dropped = setPlanPoint("from", 700, 380, 70, null, false);
  const disarmed = state.mapPick === null;
  const cursorOff = !document.getElementById("map").classList.contains("picking");
  const fromLabel = document.getElementById("inFrom").value;
  const target1 = pointTarget();                 // From is filled -> a bare pin goes To
  setPlanPoint("to", 1360, 650, 64, null, false);
  const toLabel = document.getElementById("inTo").value;
  // Esc gives up an armed pick before anything else
  armMapPick("to");
  escapePressed();
  const escDisarmed = state.mapPick === null;
  // clearing a field drops what it held
  clearPlanField("from");
  const cleared = state.plan.from === null;
  state.plan.from = before.from; state.plan.to = before.to;
  syncFields(); replan();
  return { armed, cursorOn, dropped: dropped.point, disarmed, cursorOff, fromLabel, toLabel, target1, escDisarmed, cleared };
})()`);
check("clicking a field arms the map with a crosshair", pickFlow.armed === "from" && pickFlow.cursorOn === true, J(pickFlow));
check("the pin lands at the clicked world point and disarms",
	J(pickFlow.dropped) === J([700, 380]) && pickFlow.disarmed === true && pickFlow.cursorOff === true, J(pickFlow));
check("the fields read 'Dropped pin' / 'Dropped pin (dest)'",
	pickFlow.fromLabel === "Dropped pin" && pickFlow.toLabel === "Dropped pin (dest)",
	J([pickFlow.fromLabel, pickFlow.toLabel]));
check("a bare right-click fills From first, then To", pickFlow.target1 === "to", pickFlow.target1);
check("Esc gives up an armed pick", pickFlow.escDisarmed === true);
check("clearing a field drops the point it held", pickFlow.cleared === true);

/* ---- R. prefs round-trip for the three new settings ---- */
const prefs = run(`(() => {
  const before = { b: state.prefs.basemap, p: state.prefs.showPlayers };
  const sat = setBasemap("satellite");
  const on = satEnabled();
  const sch = setBasemap("nonsense");
  const off = satEnabled();
  setPlayersVisible(false);
  const hidden = state.prefs.showPlayers;
  setPlayersVisible(true);
  state.prefs.basemap = before.b; state.prefs.showPlayers = before.p;
  return { sat, on, sch, off, hidden };
})()`);
check("the basemap setting is satellite / schematic and nothing else",
	prefs.sat === "satellite" && prefs.on === true && prefs.sch === "schematic" && prefs.off === false, J(prefs));
check("the Players layer can be switched off", prefs.hidden === false);

const satFall = run(`(() => {
  const keep = state.satmeta;
  const on = { available: true, tiles: [[1, 0]], scale: 2, tileSamples: 256, originX: 0, originZ: 0 };
  const never = { available: false, tiles: [], scale: 2, tileSamples: 256, originX: 0, originZ: 0 };
  state.satmeta = on;   const withScan = satHasTiles();
  state.satmeta = never; const noScan = satHasTiles();
  state.satmeta = null;  const nothing = satHasTiles();
  state.satmeta = keep;
  return { withScan, noScan, nothing };
})()`);
check("satellite draws imagery only when a scan exists (water stays otherwise)",
	satFall.withScan === true && satFall.noScan === false && satFall.nothing === false, J(satFall));

/* the itinerary must not call the walk off a dropped pin a 'Transfer' */
const itin = run(`(() => {
  const g = state.plan.graph || (state.plan.graph = buildGraph());
  const res = planEndpoints(g, { point: [690, 110], y: 64 }, { point: [700, 630], y: 64 }, { mode: "fastest" }, Date.now());
  const j = res.journeys[0];
  const html = j ? itineraryHtml(j) : "";
  return {
    ok: !!j,
    transfers: (html.match(/Transfer ·/g) || []).length,
    walks: (html.match(/Walk \\d+ m · \\d+ min to /g) || []).length,
    pin: html.includes("Dropped pin"),
    dest: html.includes("Dropped pin (dest)"),
  };
})()`);
check("a pin -> pin itinerary shows two named walk rows and no bogus transfer",
	itin.ok === true && itin.walks === 2 && itin.transfers === 0, J(itin));
check("...naming both pins", itin.pin === true && itin.dest === true, J(itin));

/* ---- the whole schematic build is cheap enough to run on every network refetch ---- */
const buildPerf = run(`(() => {
  let worst = 0, total = 0;
  for (let i = 0; i < 12; i++) {
    const t0 = Date.now();
    prepareGeometry();
    const dt = Date.now() - t0;
    worst = Math.max(worst, dt); total += dt;
  }
  return { worst, avg: total / 12 };
})()`);
check("a full geometry rebuild stays well under 100 ms on the demo network",
	buildPerf.worst < 100, "worst " + buildPerf.worst + " ms, avg " + buildPerf.avg.toFixed(2) + " ms");


/* ==========================================================================
 * WALK SPEED IS A PREFERENCE (fix 1, 2026-08-30)
 * ======================================================================== */
const speed = run(`(() => {
  const before = walkSpeed();
  const edge = () => buildGraph().transferEdges.find(e => e.from === "ap_b" && e.to === "ap_t");
  const at43 = edge();
  setWalkSpeed(2);
  const at2 = edge();
  const slowPlan = state.plan.journeys.map(j => j.walkMeters);
  setWalkSpeed(9);      // out of range
  const clampedHigh = state.prefs.walkSpeed;
  setWalkSpeed(0.1);
  const clampedLow = state.prefs.walkSpeed;
  setWalkSpeed(4.3);
  const back = edge();
  return {
    before, at43: at43.seconds, at2: at2.seconds, back: back.seconds,
    clampedHigh, clampedLow, slowPlan: slowPlan.length,
    memoCleared: planMemo.entries.size >= 0,
  };
})()`);
check("walk speed defaults to 4.3 m/s (Minecraft walking)", speed.before === 4.3, speed.before);
check("every transfer edge is timed at the CURRENT pref",
	Math.abs(speed.at43 - (90 / 4.3 + 30)) < 1e-9 && Math.abs(speed.at2 - (90 / 2 + 30)) < 1e-9,
	J([speed.at43, speed.at2]));
check("...and comes back when the slider does", Math.abs(speed.back - speed.at43) < 1e-9, J([speed.at43, speed.back]));
check("the slider is clamped to 2.0 - 5.6 m/s",
	speed.clampedHigh === 5.6 && speed.clampedLow === 2, J([speed.clampedHigh, speed.clampedLow]));
check("changing it re-plans (the panel still has journeys)", speed.slowPlan > 0, speed.slowPlan);
check("changing the walk speed drops the plan memo AND the graph", run(`(() => {
  state.plan.graph = buildGraph();
  planJourneys(state.plan.graph, "mh", "ap", { mode: "fastest", stepFree: false }, Date.now(), {});
  const had = planMemo.entries.size;
  const graphBefore = state.plan.graph;
  setWalkSpeed(3.2);
  const rebuilt = state.plan.graph !== graphBefore;
  setWalkSpeed(4.3);
  return had > 0 && rebuilt;
})()`));
check("the walk speed persists in the prefs blob", run(`(() => {
  const store = {};
  const real = localStorage.setItem;
  localStorage.setItem = (k, v) => { store[k] = v; };
  setWalkSpeed(5.1); savePrefs();
  localStorage.setItem = real;
  const blob = JSON.parse(store[PREFS_KEY] || "{}");
  setWalkSpeed(4.3);
  return Math.abs(blob.walkSpeed - 5.1) < 1e-9;
})()`));

/* ==========================================================================
 * THROUGH RUNNING (fix 2) — the demo's blue A continues as the purple S
 * ======================================================================== */
const thruParse = run(`(() => {
  const keep = state.throughRuns;
  const parse = (md) => { applyMapdata(md); const out = state.throughRuns.slice(); return out; };
  const none = parse({ routes: [], stations: [] });                       // field absent
  const junk = parse({ routes: [], stations: [], throughRuns: "nope" });  // field wrong type
  const bad = parse({ routes: [], stations: [], throughRuns: [
    { from: "a" }, { from: "a", to: "a", platform: "p" }, null, { from: "a", to: "b", platform: "p" }] });
  applyMapdata(buildDemoCity().mapdata);
  prepareGeometry();
  state.plan.graph = null; planMemo.entries.clear();
  return { none, junk, bad, live: state.throughRuns, keptLen: keep.length };
})()`);
check("mapdata without throughRuns is an empty list, not an error",
	J(thruParse.none) === "[]" && J(thruParse.junk) === "[]", J([thruParse.none, thruParse.junk]));
check("half-written / self-referential through runs are dropped",
	thruParse.bad.length === 1 && thruParse.bad[0].to === "b", J(thruParse.bad));
check("the demo publishes the A -> S through run at Harborview",
	J(thruParse.live) === J([{ from: "rA", to: "rS", platform: "hv_b" }]), J(thruParse.live));

const thruGraph = run(`(() => {
  const g = buildGraph();
  const keep = state.throughRuns;
  state.throughRuns = [{ from: "rA", to: "rD", platform: "hv_b" },     // rD is hidden: not in the graph
    { from: "rA", to: "rS", platform: "nowhere" }];                    // unknown platform
  const g2 = buildGraph();
  state.throughRuns = keep;
  return { keys: [...g.through.keys()], to: [...(g.through.get("hv_b|rA") || [])], junk: g2.through.size };
})()`);
check("the graph indexes through runs by platform + arriving route",
	J(thruGraph.keys) === J(["hv_b|rA"]) && J(thruGraph.to) === J(["rS"]), J(thruGraph));
check("a through run onto a route the graph does not carry is ignored", thruGraph.junk === 0, thruGraph.junk);

const thru = run(`(() => {
  const plan = () => {
    state.plan.graph = buildGraph();
    planMemo.entries.clear();
    return planJourneys(state.plan.graph, "mh", "he", { mode: "fastest", stepFree: false }, Date.now());
  };
  const withThrough = plan();
  const keep = state.throughRuns;
  state.throughRuns = [];
  const without = plan();
  state.throughRuns = keep;
  const after = plan();
  const j = withThrough[0], old = without[0];
  const leg = j.legs.find(l => l.kind === "ride" && (l.continuations || []).length);
  // no wait at the through platform: the leg's own duration is exactly ride + dwell +
  // ride + dwell + ride, with no headway anywhere in it
  const g = state.plan.graph;
  const ride = (rt, a, b) => g.rideEdges.find(e => e.routeId === rt && e.from === a && e.to === b).seconds;
  const expect = ride("rA", "bc_b", "rs_b") + platformDwellSeconds("rs_b")
    + ride("rA", "rs_b", "hv_b") + platformDwellSeconds("hv_b") + ride("rS", "hv_b", "he_f");
  const html = itineraryHtml(j);
  const seg = legSegmentIds(leg);
  const sel = selectJourney(j) || state.selection;
  const hexes = [...state.selection.lineHexes].sort();
  const routeIds = [...state.selection.routeIds].sort();
  clearSelection();
  return {
    tThrough: j.transfers, tWithout: old.transfers,
    ridesThrough: j.legs.filter(l => l.kind === "ride").length,
    ridesWithout: old.legs.filter(l => l.kind === "ride").length,
    durThrough: j.durationMs, durWithout: old.durationMs,
    cont: leg.continuations, spans: leg.spans, stops: leg.stops, stopCount: leg.stopCount,
    legSeconds: (leg.arrMs - leg.depMs) / 1000, expect,
    headsign: leg.headsign, sig: j.rideSignature,
    html: /Continues as/.test(html), htmlBullet: /class="bullet sm" style="background:#6B3FA0"/i.test(html),
    htmlTransfers: (html.match(/Transfer ·/g) || []).length,
    segIds: seg.ids.slice().sort(), hexes, routeIds,
    stable: after.length === withThrough.length && after[0].rideSignature === j.rideSignature,
  };
})()`);
check("a through run costs the rider one transfer less than the same trip without it",
	thru.tThrough === 1 && thru.tWithout === 2, J([thru.tThrough, thru.tWithout]));
check("...and arrives sooner, because the wait at the through platform is gone",
	thru.durThrough < thru.durWithout, Math.round(thru.durThrough / 60000) + " min vs " + Math.round(thru.durWithout / 60000));
check("the two routes are ONE ride leg, not two",
	thru.ridesThrough === 2 && thru.ridesWithout === 3, J([thru.ridesThrough, thru.ridesWithout]));
check("that leg's clock is ride + dwell only — no wait at the through platform",
	Math.abs(thru.legSeconds - thru.expect) < 1.5, thru.legSeconds.toFixed(1) + " s vs " + thru.expect.toFixed(1) + " s");
check("the continuation is recorded with its own bullet, colour and headsign",
	thru.cont.length === 1 && thru.cont[0].routeId === "rS" && thru.cont[0].platform === "hv_b"
	&& thru.cont[0].fromRouteId === "rA" && thru.cont[0].station === "Harborview", J(thru.cont));
check("the leg's headsign is where the train ends up, not where it started",
	thru.headsign === "Harbor East", thru.headsign);
check("stop counts span BOTH routes", J(thru.stops) === J(["bc_b", "rs_b", "hv_b", "he_f"]) && thru.stopCount === 3, J(thru.stops));
check("the leg carries one span per route the train runs as",
	thru.spans.length === 2 && thru.spans[0].routeId === "rA" && thru.spans[1].routeId === "rS"
	&& thru.spans[0].toPlatform === "hv_b" && thru.spans[1].fromPlatform === "hv_b", J(thru.spans));
check("the ride signature keeps both spans (two options can differ only in the change point)",
	thru.sig === "r4:mh_g>bc_g|rA:bc_b>hv_b+rS:hv_b>he_f", thru.sig);
check("the itinerary draws a 'Continues as' note row for the through run",
	thru.html === true && thru.htmlBullet === true, J([thru.html, thru.htmlBullet]));
check("...and the only Transfer chip is the rider's REAL platform change",
	thru.htmlTransfers === 1, thru.htmlTransfers);
check("the highlighted map covers both routes' segments",
	thru.segIds.some((id) => id.startsWith("#0039a6")) && thru.segIds.some((id) => id.startsWith("#6b3fa0")), J(thru.segIds));
check("...and both lines' trains keep running under the selection",
	J(thru.hexes) === J(["#00933c", "#0039a6", "#6b3fa0"].sort()) && thru.routeIds.includes("rS"), J([thru.hexes, thru.routeIds]));
check("planning twice gives the same through journey", thru.stable === true);

/* ==========================================================================
 * DIMENSION SELECTOR (fix 3)
 * ======================================================================== */
const dims = run(`({
  overworld: dimensionLabel("minecraft/overworld", 0),
  nether: dimensionLabel("minecraft:the_nether", 1),
  demo: dimensionLabel("demo:overworld", 0),
  plain: dimensionLabel("skylands", 0),
  multi: dimensionLabel("mypack/deep_dark_zone", 0),
  empty: dimensionLabel("", 3),
  nullish: dimensionLabel(null, 0),
})`);
check("dimension names prettify to their last path segment",
	dims.overworld === "Overworld" && dims.nether === "The Nether" && dims.demo === "Overworld", J(dims));
check("...underscores become spaces, every word capitalised",
	dims.multi === "Deep Dark Zone" && dims.plain === "Skylands", J([dims.multi, dims.plain]));
check("an unnameable dimension falls back to its index",
	dims.empty === "Dimension 4" && dims.nullish === "Dimension 1", J([dims.empty, dims.nullish]));

const dimUi = run(`(() => {
  const keep = state.dims.slice();
  const row = document.getElementById("dimRow"), sel = document.getElementById("dimSel");
  state.dims = ["demo:overworld"];
  syncDimUi();
  const oneHidden = row.classList.contains("hidden");
  state.dims = ["minecraft/overworld", "minecraft/the_nether", "minecraft/the_end"];
  syncDimUi();
  const manyShown = !row.classList.contains("hidden");
  const html = sel.innerHTML;
  state.dims = keep;
  syncDimUi();
  return { oneHidden, manyShown, html, backHidden: row.classList.contains("hidden") };
})()`);
check("the dimension row hides itself when there is only one world", dimUi.oneHidden === true);
check("...and appears, prettified, when there are several",
	dimUi.manyShown === true && /The Nether/.test(dimUi.html) && /value="2"/.test(dimUi.html), dimUi.html);
check("...and hides again when the roster shrinks", dimUi.backHidden === true);

/* ==========================================================================
 * LIVE JOURNEY TRACKING (feature 6) — the pure reducer, driven end to end
 * ======================================================================== */
run(`
  const T0 = 1700000000000;
  /* A four-leg journey covering every phase: walk off a pin, ride, cross-platform
     transfer, ride to the destination. Real demo station ids, so the banner's labels
     are the real names. */
  const TJ = {
    signature: "test-journey",
    departMs: T0, arriveMs: T0 + 1800000, durationMs: 1800000,
    toStation: "ap", toPart: "ap:1", toPoint: null, toName: "",
    transfers: 1, walkMeters: 141, accessible: true, tags: [], legs: [
      { kind: "walk", mode: "walk", meters: 120, seconds: 58, note: "street walk",
        fromStation: null, fromPart: null, fromPlatform: "@from", fromPoint: "@from", fromName: "My location",
        toStation: "mu", toPart: "mu:0", toPlatform: "mu_g", toPoint: null, toName: "Museum",
        depMs: T0, arrMs: T0 + 58000 },
      { kind: "ride", routeId: "r4", routeName: "Baker Line", number: "4 IN", color: "#00933c", mode: "train",
        fromStation: "mu", fromPart: "mu:0", fromPlatform: "mu_g",
        toStation: "bc", toPart: "bc:0", toPlatform: "bc_g",
        stops: ["mu_g", "bc_g"], stopCount: 1, headsign: "Bayfront", spans: [], continuations: [],
        depMs: T0 + 180000, arrMs: T0 + 340000, live: false, vehicleId: null, estimated: false, departsInMin: 2 },
      { kind: "walk", mode: "walk", meters: 21, seconds: 45, note: "cross-platform",
        fromStation: "bc", fromPart: "bc:0", fromPlatform: "bc_g",
        toStation: "bc", toPart: "bc:0", toPlatform: "bc_b",
        depMs: T0 + 340000, arrMs: T0 + 385000 },
      { kind: "ride", routeId: "rA", routeName: "Airport Express", number: "A IN", color: "#0039a6", mode: "train",
        fromStation: "bc", fromPart: "bc:0", fromPlatform: "bc_b",
        toStation: "ap", toPart: "ap:0", toPlatform: "ap_b",
        stops: ["bc_b", "rs_b", "hv_b", "ap_b"], stopCount: 3, headsign: "Airport", spans: [], continuations: [],
        depMs: T0 + 420000, arrMs: T0 + 1160000, live: false, vehicleId: null, estimated: false, departsInMin: 7 },
    ],
  };
  const TJ2 = JSON.parse(JSON.stringify(TJ));
  TJ2.signature = "faster-journey";
  TJ2.arriveMs = TJ.arriveMs - 300000;
  const CTX = (o) => Object.assign({
    hasSelf: true, pos: { x: 0, z: 0 }, speed: 1.2, atLegEnd: false, distanceToEnd: 120,
    aboard: false, vehicleId: null, stopsLeft: null, nextStopIsAlight: false,
    offRoute: false, departPassed: false, departsInMs: 300000,
  }, o || {});
  function TTICK(t, o, at) { return trackReduce(t, { type: "tick", at, ctx: CTX(o) }); }
`);

const life = run(`(() => {
  const out = {};
  let t = trackReduce(null, { type: "start", journey: TJ, at: T0, geom: null });
  out.start = { phase: t.phase, step: t.stepIndex, alert: t.alert, offer: t.offer };
  out.startText = trackBannerText(t, CTX({ distanceToEnd: 120 })).text;
  // still walking
  t = TTICK(t, { distanceToEnd: 60 }, T0 + 20000);
  out.walking = t.phase;
  // reached the platform -> waiting for the 4
  t = TTICK(t, { atLegEnd: true, distanceToEnd: 4 }, T0 + 60000);
  out.wait = { phase: t.phase, step: t.stepIndex };
  out.waitText = trackBannerText(t, CTX({ departsInMs: 120000 })).text;
  // the train is a minute out: the banner goes loud
  t = TTICK(t, { departsInMs: 50000 }, T0 + 130000);
  out.boardingAlert = t.alert;
  // a train of the right route in the match radius for ONE tick is a train passing
  // through the platform, not the rider boarding it
  const passing = TTICK(t, { aboard: true, vehicleId: "vpass", stopsLeft: 5, departsInMs: 40000 }, T0 + 150000);
  out.passing = { phase: passing.phase, pending: passing.pendingVehicleId };
  out.passingGone = (() => {
    const g = TTICK(passing, { departsInMs: 30000 }, T0 + 151000);
    return { phase: g.phase, pending: g.pendingVehicleId };
  })();
  // moving at line speed with no puck to match is aboard on the spot
  out.motionAboard = TTICK(t, { aboard: true, vehicleId: null, speed: 14 }, T0 + 160000).phase;
  // aboard, three stops out — the same train two ticks running
  t = TTICK(t, { aboard: true, vehicleId: "v9", stopsLeft: 3, departsInMs: -1000 }, T0 + 189000);
  out.confirming = { phase: t.phase, pending: t.pendingVehicleId };
  t = TTICK(t, { aboard: true, vehicleId: "v9", stopsLeft: 3, departsInMs: -1000 }, T0 + 190000);
  out.ride = { phase: t.phase, vehicleId: t.vehicleId, stopsLeft: t.stopsLeft, alert: t.alert };
  out.rideText = trackBannerText(t, CTX({})).text;
  // countdown
  t = TTICK(t, { aboard: true, vehicleId: "v9", stopsLeft: 2 }, T0 + 250000);
  out.counted = { stops: t.stopsLeft, alert: t.alert };
  // next stop is ours -> the loud alert
  t = TTICK(t, { aboard: true, vehicleId: "v9", stopsLeft: 1, nextStopIsAlight: true }, T0 + 300000);
  out.alight = { alert: t.alert, phase: t.phase };
  out.alightText = trackBannerText(t, CTX({})).text;
  // off the train at the alighting platform -> the transfer
  t = TTICK(t, { aboard: false, atLegEnd: true, distanceToEnd: 8, speed: 1 }, T0 + 345000);
  out.transfer = { phase: t.phase, step: t.stepIndex, vehicleId: t.vehicleId, alert: t.alert };
  out.transferText = trackBannerText(t, CTX({})).text;
  // across the platform -> waiting for the A
  t = TTICK(t, { atLegEnd: true, distanceToEnd: 5 }, T0 + 380000);
  out.wait2 = { phase: t.phase, step: t.stepIndex };
  // aboard the A (again: the same train two ticks running)
  t = TTICK(t, { aboard: true, vehicleId: "v12", stopsLeft: 3 }, T0 + 429000);
  t = TTICK(t, { aboard: true, vehicleId: "v12", stopsLeft: 3 }, T0 + 430000);
  out.ride2 = { phase: t.phase, step: t.stepIndex };
  // and off it at the destination
  t = TTICK(t, { aboard: false, atLegEnd: true, distanceToEnd: 6, speed: 0.5 }, T0 + 1160000);
  out.arrived = { phase: t.phase, step: t.stepIndex, arrivedAt: t.arrivedAt };
  out.arrivedText = trackBannerText(t, CTX({})).text;
  // the banner lingers…
  const lingering = TTICK(t, {}, T0 + 1165000);
  out.lingers = lingering && lingering.phase;
  // …then tracking ends itself
  out.ended = TTICK(t, {}, T0 + 1175000);
  return out;
})()`);
check("tracking starts on the first leg, walking", life.start.phase === "walk" && life.start.step === 0 && !life.start.offer, J(life.start));
check("the walk banner names the destination and the distance left",
	life.startText === "Walk to Museum · 120 m", life.startText);
check("walking is not finished until the rider is AT the platform", life.walking === "walk", life.walking);
check("reaching the platform advances to waiting for the service",
	life.wait.phase === "wait" && life.wait.step === 1, J(life.wait));
check("the wait banner is the boarding instruction",
	life.waitText === "Board the 4 toward Bayfront · departs in 2 min", life.waitText);
check("a departure under a minute away makes the banner loud", life.boardingAlert === "boarding", life.boardingAlert);
check("a train that is only in range for one tick is a train PASSING, not a boarding",
	life.passing.phase === "wait" && life.passing.pending === "vpass"
	&& life.passingGone.phase === "wait" && life.passingGone.pending === null, J([life.passing, life.passingGone]));
check("moving at line speed with no puck to match is aboard immediately", life.motionAboard === "ride", life.motionAboard);
check("the same train two ticks running IS the boarding", life.confirming.phase === "wait", J(life.confirming));
check("being matched to a train switches to riding and remembers the vehicle",
	life.ride.phase === "ride" && life.ride.vehicleId === "v9" && life.ride.stopsLeft === 3, J(life.ride));
check("the ride banner counts stops to the alighting station",
	life.rideText === "On the 4 · 3 stops to Baker City Central", life.rideText);
check("the count follows the train, quietly", life.counted.stops === 2 && life.counted.alert === null, J(life.counted));
check("the train's next stop being ours raises the ALIGHT alert",
	life.alight.alert === "alight" && life.alight.phase === "ride", J(life.alight));
check("...and the banner says exactly that",
	life.alightText.indexOf("Get off at the NEXT stop") === 0, life.alightText);
check("stepping off at the alighting platform advances to the transfer",
	life.transfer.phase === "transfer" && life.transfer.step === 2 && life.transfer.vehicleId === null
	&& life.transfer.alert === null, J(life.transfer));
check("the transfer banner describes the change", life.transferText === "Transfer: cross-platform, 21 m", life.transferText);
check("finishing the transfer waits for the next service",
	life.wait2.phase === "wait" && life.wait2.step === 3, J(life.wait2));
check("the second ride tracks like the first", life.ride2.phase === "ride" && life.ride2.step === 3, J(life.ride2));
check("alighting on the last leg is arrival", life.arrived.phase === "arrived" && life.arrived.arrivedAt > 0, J(life.arrived));
check("the arrival banner names the destination", life.arrivedText === "Arrived · Airport · Terminal", life.arrivedText);
check("the arrival banner lingers ~10 s…", life.lingers === "arrived", life.lingers);
check("…and then tracking ends on its own", life.ended === null, J(life.ended));

const trackEnd = run(`(() => {
  let t = trackReduce(null, { type: "start", journey: TJ, at: T0 });
  const gps1 = TTICK(t, { hasSelf: false }, T0 + 4000);
  const gps2 = TTICK(gps1, { hasSelf: false }, T0 + 20000);
  const gps3 = TTICK(gps2, { hasSelf: false }, T0 + 40000);
  const recovered = TTICK(gps2, {}, T0 + 22000);
  return {
    gps1: { alert: gps1.alert, lostSince: gps1.lostSince },
    gps2: gps2.alert, gps3,
    recovered: { alert: recovered.alert, lost: recovered.lostSince },
    lostText: trackBannerText(gps1, CTX({ hasSelf: false })).text,
    esc: trackReduce(t, { type: "escape", at: T0 + 1000 }),
    stop: trackReduce(t, { type: "stop", at: T0 + 1000 }),
    user: trackReduce(t, { type: "user", at: T0 + 1000 }),
    dim: trackReduce(t, { type: "dimension", at: T0 + 1000 }),
    unknown: trackReduce(t, { type: "resize", at: T0 + 1000 }) === t,
    noneStart: trackReduce(null, { type: "start", journey: { legs: [] }, at: T0 }),
    tickNothing: trackReduce(null, { type: "tick", at: T0, ctx: CTX({}) }),
  };
})()`);
check("a missing self player says GPS lost before it gives up",
	trackEnd.gps1.alert === "gps" && trackEnd.gps1.lostSince === 1700000004000 && trackEnd.gps2 === "gps",
	J([trackEnd.gps1, trackEnd.gps2]));
check("...with a banner that says so", trackEnd.lostText === "GPS lost — waiting for your position", trackEnd.lostText);
check("...and ends tracking after 30 s of silence", trackEnd.gps3 === null, J(trackEnd.gps3));
check("a player who comes back clears the GPS alert",
	trackEnd.recovered.alert === null && trackEnd.recovered.lost === 0, J(trackEnd.recovered));
check("Esc / the banner's x / a dimension change all end tracking",
	trackEnd.esc === null && trackEnd.stop === null && trackEnd.user === null && trackEnd.dim === null);
check("an unrelated event leaves tracking untouched", trackEnd.unknown === true);
check("a journey with no legs cannot be tracked", trackEnd.noneStart === null);
check("a tick with nothing being tracked stays null", trackEnd.tickNothing === null);

const missed = run(`(() => {
  let t = trackReduce(null, { type: "start", journey: TJ, at: T0 });
  t = TTICK(t, { atLegEnd: true, distanceToEnd: 4 }, T0 + 60000);      // on the platform
  const waiting = t.phase;
  // the planned departure slides past with nobody picking us up
  const miss = TTICK(t, { atLegEnd: true, departPassed: true, departsInMs: -60000 }, T0 + 260000);
  // an offer arrives (the driver ran the planner) — nothing has switched
  const offered = trackReduce(miss, { type: "offer", at: T0 + 261000, journey: TJ2, reason: "invalid", savedMs: 300000 });
  const switched = trackReduce(offered, { type: "switch", at: T0 + 262000 });
  const kept = trackReduce(offered, { type: "keep", at: T0 + 262000 });
  // the same, but merely faster
  const fasterOffer = trackReduce(miss, { type: "offer", at: T0 + 261000, journey: TJ2, reason: "faster" });
  const keptFaster = trackReduce(fasterOffer, { type: "keep", at: T0 + 262000 });
  // no alternative at all: the popup still says the plan is dead
  const deadEnd = trackReduce(miss, { type: "offer", at: T0 + 261000, journey: null, reason: "invalid" });
  // off course for 10 s asks for a re-plan too
  let stray = trackReduce(null, { type: "start", journey: TJ, at: T0 });
  stray = TTICK(stray, { offRoute: true }, T0 + 1000);
  const strayEarly = stray.needsReplan;
  stray = TTICK(stray, { offRoute: true }, T0 + 8000);
  const strayStill = stray.needsReplan;
  stray = TTICK(stray, { offRoute: true }, T0 + 13000);
  const strayLate = stray.needsReplan;
  const recovered = TTICK(stray, { offRoute: false }, T0 + 14000);
  return {
    waiting, missed: miss.missed, needs: miss.needsReplan, stillWait: miss.phase,
    offer: { reason: offered.offer.reason, needs: offered.needsReplan, arrive: offered.offer.arriveMs },
    switched: { sig: switched.journey.signature, phase: switched.phase, step: switched.stepIndex, offer: switched.offer },
    kept: { offer: kept.offer, degraded: kept.degraded, journey: kept.journey.signature },
    keptFaster: keptFaster.degraded,
    deadEnd: { journey: deadEnd.offer.journey, reason: deadEnd.offer.reason },
    strayEarly, strayStill, strayLate, recoveredNeeds: recovered.needsReplan, recoveredSince: recovered.offRouteSince,
    ticksWhileOffered: (() => {
      const after = TTICK(offered, { atLegEnd: true, departPassed: true }, T0 + 280000);
      return { offer: !!after.offer, needs: after.needsReplan };
    })(),
  };
})()`);
check("waiting past the planned departure with no train counts as MISSED",
	missed.waiting === "wait" && missed.missed === true && missed.needs === true, J(missed));
check("...and does NOT switch anything by itself: the rider is still on their leg",
	missed.stillWait === "wait" && missed.offer.needs === false, J(missed.offer));
check("the offer carries the replacement and why it was raised",
	missed.offer.reason === "invalid" && missed.offer.arrive === 1700001500000, J(missed.offer));
check("Switch restarts tracking on the offered journey from its first leg",
	missed.switched.sig === "faster-journey" && missed.switched.phase === "walk"
	&& missed.switched.step === 0 && missed.switched.offer === null, J(missed.switched));
check("Keep current stays on the old journey and drops the popup",
	missed.kept.offer === null && missed.kept.journey === "test-journey", J(missed.kept));
check("...and a DEAD plan degrades to progress-only", missed.kept.degraded === true, missed.kept.degraded);
check("...while refusing a merely-faster option degrades nothing", missed.keptFaster === false);
check("with no alternative at all the popup still reports the plan is dead",
	missed.deadEnd.journey === null && missed.deadEnd.reason === "invalid", J(missed.deadEnd));
check("straying off the leg needs 10 s before it asks for a re-plan",
	missed.strayEarly === false && missed.strayStill === false && missed.strayLate === true,
	J([missed.strayEarly, missed.strayStill, missed.strayLate]));
check("...and coming back onto the plan cancels it",
	missed.recoveredNeeds === false && missed.recoveredSince === 0, J([missed.recoveredNeeds, missed.recoveredSince]));
check("only one question is asked at a time", missed.ticksWhileOffered.offer === true
	&& missed.ticksWhileOffered.needs === false, J(missed.ticksWhileOffered));

/* ---- vehicle correlation (the impure half) ---- */
const corr = run(`(() => {
  const j = state.plan.journeys[0] || null;
  // put the rider on the first ride leg of the demo's own plan
  state.plan.from = { stationId: "mh", partId: null };
  state.plan.to = { stationId: "ap", partId: "ap:1" };
  replan();
  const journey = state.plan.journeys[0];
  const leg = journey.legs.find(l => l.kind === "ride");
  const at = Date.now();
  const put = (x, z) => handleFrame({ serverTime: at, dimension: 0, vehicles: [], players: [
    { name: "Thomas", x, y: 64, z }] }, false);
  state.prefs.selfPlayer = "Thomas"; state.selfManual = true;
  put(636, 150);
  resolveSelfPlayer();
  const tracking = startTracking(journey);
  const plat = state.platforms.get(leg.fromPlatform).xz;
  put(plat[0], plat[1]);
  const idle = trackContext(state.tracking, at);
  // a train of the right route right on top of the rider = they are aboard it
  _feed([_train("ride1", { nPlat: "gf_g", pPlat: "mh_g" })]);
  state.vehicles.get("ride1").disp = { x: plat[0], z: plat[1] };
  const aboard = trackContext(state.tracking, at);
  // a train of ANOTHER route in the same place is not our train
  _feed([_train("wrong", { route: { id: "rR", name: "Ridge Line||South Yards", number: "1",
    color: 0xEE352E, dest: "South Yards" } })]);
  state.vehicles.get("wrong").disp = { x: plat[0], z: plat[1] };
  const wrongRoute = trackContext(state.tracking, at);
  // hysteresis: the matched train may drift to 2x the radius before it is let go
  _feed([_train("ride1", { nPlat: "gf_g" }), _train("ride2", { nPlat: "gf_g" })]);
  state.vehicles.get("ride1").disp = { x: plat[0] + 18, z: plat[1] };
  state.vehicles.get("ride2").disp = { x: plat[0] + 10, z: plat[1] };
  state.tracking.vehicleId = "ride1";
  const sticky = trackContext(state.tracking, at);
  state.tracking.vehicleId = null;
  const nearest = trackContext(state.tracking, at);
  // stops remaining come from the matched train's own next stop
  const stops = [
    trackStopsLeft(leg, { data: { nPlat: "gf_g" } }),
    trackStopsLeft(leg, { data: { nPlat: "bc_g" } }),
    trackStopsLeft(leg, { data: { pPlat: "gf_g" } }),
    trackStopsLeft(leg, { data: { nPlat: "sy_r" } }),
    trackStopsLeft(leg, { data: {} }),
  ];
  // wandering off
  put(200, 900);
  const off = trackContext(state.tracking, at);
  // and losing the feed altogether
  state.players.delete("Thomas");
  const lost = trackContext(state.tracking, at);
  stopTracking("stop");
  state.vehicles.clear();
  return {
    legStops: leg.stops, idle: { aboard: idle.aboard, atLegEnd: idle.atLegEnd },
    aboard: { aboard: aboard.aboard, vid: aboard.vehicleId, stopsLeft: aboard.stopsLeft },
    wrongRoute: wrongRoute.aboard, sticky: sticky.vehicleId, nearest: nearest.vehicleId,
    stops, off: { offRoute: off.offRoute, aboard: off.aboard }, lost: lost.hasSelf,
    tracked: !!tracking,
  };
})()`);
check("a rider standing on the platform with no train is not aboard",
	corr.idle.aboard === false, J(corr.idle));
check("a live train of the leg's own route on top of the rider IS their train",
	corr.aboard.aboard === true && corr.aboard.vid === "ride1", J(corr.aboard));
check("...and its next stop gives the stops remaining", corr.aboard.stopsLeft === 3, J([corr.legStops, corr.aboard.stopsLeft]));
check("a train of a different route in the same place is not a match", corr.wrongRoute === false);
check("the already-matched train keeps the match out to twice the radius",
	corr.sticky === "ride1" && corr.nearest === "ride2", J([corr.sticky, corr.nearest]));
check("stops-remaining: nPlat, then pPlat + 1, and null when the train is not on this leg",
	J(corr.stops) === J([3, 1, 2, null, null]), J(corr.stops));
check("a rider 700 blocks off the leg reads as off course", corr.off.offRoute === true && corr.off.aboard === false, J(corr.off));
check("a player who leaves the feed reads as no GPS", corr.lost === false);

const drive = run(`(() => {
  state.prefs.selfPlayer = "Thomas"; state.selfManual = true;
  const at = Date.now();
  handleFrame({ serverTime: at, dimension: 0, vehicles: [], players: [{ name: "Thomas", x: 636, y: 64, z: 150 }] }, false);
  resolveSelfPlayer();
  state.plan.from = { stationId: "mh", partId: null };
  state.plan.to = { stationId: "ap", partId: "ap:1" };
  replan();
  const j = state.plan.journeys[0];
  const started = startTracking(j);
  const banner = document.getElementById("trackBanner");
  const bannerShown = !banner.classList.contains("hidden");
  const startBtn = /opt-start/.test(optionCard(j, true));
  const noGpsBtn = (() => {
    const keep = state.selfPlayer; state.selfPlayer = "";
    const html = optionCard(j, true);
    state.selfPlayer = keep;
    return /opt-start/.test(html);
  })();
  const selected = state.selection && state.selection.kind;
  // tracking survives a re-plan and re-asserts the map selection
  clearSelection();
  tickTracking();
  const reasserted = !!state.selection;
  // the tracked journey's stations keep their labels even with names hidden
  state.prefs.hideLabels = true;
  const parts = trackedPartIds();
  const keptLabel = state.glyphs.filter(g => labelVisibleFor(g, null)).map(g => g.partId).sort();
  state.prefs.hideLabels = false;
  // the camera rides the rider while aboard, and lets go the moment they pan
  state.tracking.phase = "ride";
  state.view.x = 0; state.view.z = 0;
  const rode = trackCamera();
  const view = [Math.round(state.view.x), Math.round(state.view.z)];
  cameraTakenBack();
  const afterPan = trackCamera();
  // the missed-departure driver: force it and watch a popup appear
  state.trackCam = true;
  state.tracking.phase = "wait";
  state.tracking.stepIndex = 0;
  state.tracking.missed = true;
  state.tracking.needsReplan = true;
  const points = state.pointNodes;
  trackOfferReplan(Date.now());
  const offer = state.tracking.offer;
  const popup = document.getElementById("trackOffer");
  const popupShown = !popup.classList.contains("hidden");
  const html = popup.innerHTML;
  const pointsKept = state.pointNodes === points;
  const accepted = acceptTrackOffer();
  const afterSwitch = accepted ? accepted.journey.signature : null;
  const escaped = (escapePressed(), state.tracking);
  const bannerHidden = document.getElementById("trackBanner").classList.contains("hidden");
  return {
    started: !!started, bannerShown, startBtn, noGpsBtn, selected, reasserted,
    parts: parts ? parts.size : 0, keptLabel,
    rode, view, afterPan, offer: offer ? offer.reason : null, popupShown,
    switchBtn: /to-switch/.test(html), keepBtn: /to-keep/.test(html),
    pointsKept, afterSwitch, escaped, bannerHidden,
  };
})()`);
check("Start puts the journey under live guidance and raises the banner",
	drive.started === true && drive.bannerShown === true, J([drive.started, drive.bannerShown]));
check("the Start button only exists when there is a self player to follow",
	drive.startBtn === true && drive.noGpsBtn === false, J([drive.startBtn, drive.noGpsBtn]));
check("tracking selects the journey on the map", drive.selected === "journey", drive.selected);
check("...and re-asserts that selection if something clears it", drive.reasserted === true);
check("'Hide station names' still names the tracked journey's stations",
	drive.parts > 0 && drive.keptLabel.length === drive.parts, J([drive.parts, drive.keptLabel]));
check("the camera rides the self dot while aboard", drive.rode === true && J(drive.view) === J([636, 150]), J(drive.view));
check("...and lets go the moment the rider takes the camera", drive.afterPan === false);
check("a missed departure raises the re-plan POPUP (never a silent switch)",
	drive.offer === "invalid" && drive.popupShown === true
	&& drive.switchBtn === true && drive.keepBtn === true, J(drive));
check("the re-plan never disturbs the current plan's point nodes", drive.pointsKept === true);
check("pressing Switch moves tracking onto the offered journey", !!drive.afterSwitch, drive.afterSwitch);
check("Esc ends tracking and takes the banner down", drive.escaped === null && drive.bannerHidden === true, J([drive.escaped, drive.bannerHidden]));

check("tracking survives a stream reconnect (a full frame is not a reset)", run(`(() => {
  state.prefs.selfPlayer = "Thomas"; state.selfManual = true;
  const at = Date.now();
  handleFrame({ serverTime: at, dimension: 0, vehicles: [], players: [{ name: "Thomas", x: 636, y: 64, z: 150 }] }, false);
  resolveSelfPlayer();
  state.plan.from = { stationId: "mh", partId: null };
  state.plan.to = { stationId: "ap", partId: "ap:1" };
  replan();
  startTracking(state.plan.journeys[0]);
  const sig = state.tracking.journey.signature;
  // a reconnect: the server re-sends a FULL frame, which wipes the vehicle table
  handleFrame({ serverTime: at + 1000, dimension: 0, vehicles: [], players: [{ name: "Thomas", x: 636, y: 64, z: 152 }] }, true);
  const survived = !!state.tracking && state.tracking.journey.signature === sig;
  // …but a dimension change is a different world, and does end it
  stopTracking("dimension");
  const ended = state.tracking === null;
  return survived && ended;
})()`));
check("mapdata that never arrives does not inherit the last world's through runs", run(`(() => {
  applyNetwork(buildDemoCity().network);
  const empty = state.throughRuns.length;
  applyMapdata(buildDemoCity().mapdata);
  prepareGeometry();
  state.plan.graph = null; planMemo.entries.clear();
  return empty === 0 && state.throughRuns.length === 1;
})()`));

/* ==========================================================================
 * SETTINGS: hidden trains / hidden players / satellite brightness / labels
 * ======================================================================== */
const newSettings = run(`(() => {
  const before = { t: state.prefs.hideTrains, o: state.prefs.hideOtherPlayers,
    b: state.prefs.satBrightness, l: state.prefs.labelScale, h: state.prefs.hideLabels };
  _feed([_train("h1")]);
  state.vehicles.get("h1").screen = [100, 100];
  const visibleHit = !!trainAt(100, 100);
  startFollow("h1");
  setTrainsHidden(true);
  const hiddenHit = trainAt(100, 100);
  const followDropped = !state.follow;
  setTrainsHidden(false);
  const backHit = !!trainAt(100, 100);
  setOtherPlayersHidden(true);
  const others = state.prefs.hideOtherPlayers;
  setOtherPlayersHidden(false);
  // satellite brightness multiplies the THEME's own alpha
  applyTheme("light");
  const full = satAlpha();
  setSatBrightness(0.5);
  const half = satAlpha();
  setSatBrightness(0.01);
  const clampedLow = state.prefs.satBrightness;
  setSatBrightness(4);
  const clampedHigh = state.prefs.satBrightness;
  applyTheme("dark");
  const darkFull = satAlpha();
  applyTheme("light");
  setSatBrightness(1);
  // label size
  const sizes = [setLabelScale(0.5), setLabelScale(2), setLabelScale(1.2)];
  setLabelScale(1);
  // hide station names: only the selection (or the tracked journey) keeps a label
  state.prefs.hideLabels = true;
  const allHidden = state.glyphs.every(g => !labelVisibleFor(g, null));
  selectLine("#00933c");
  const lineKept = state.glyphs.filter(g => labelVisibleFor(g, state.selection)).length;
  clearSelection();
  state.prefs.hideLabels = false;
  const shownAgain = state.glyphs.every(g => labelVisibleFor(g, null));
  state.vehicles.clear();
  Object.assign(state.prefs, { hideTrains: before.t, hideOtherPlayers: before.o,
    satBrightness: before.b, labelScale: before.l, hideLabels: before.h });
  return { visibleHit, hiddenHit, followDropped, backHit, others, full, half,
    clampedLow, clampedHigh, darkFull, sizes, allHidden, lineKept, shownAgain };
})()`);
check("'Hide live trains' takes the pucks' hit targets with them",
	newSettings.visibleHit === true && newSettings.hiddenHit === null && newSettings.backHit === true,
	J([newSettings.visibleHit, newSettings.hiddenHit, newSettings.backHit]));
check("...and lets go of a followed train", newSettings.followDropped === true);
check("'Hide other players' is a persisted layer of its own", newSettings.others === true);
check("satellite brightness multiplies the theme's own alpha",
	Math.abs(newSettings.full - 0.85) < 1e-9 && Math.abs(newSettings.half - 0.425) < 1e-9, J([newSettings.full, newSettings.half]));
check("...and is clamped to 0.3 - 1", newSettings.clampedLow === 0.3 && newSettings.clampedHigh === 1,
	J([newSettings.clampedLow, newSettings.clampedHigh]));
check("...over the DARK theme's lower alpha too", Math.abs(newSettings.darkFull - 0.6) < 1e-9, newSettings.darkFull);
check("the label-size slider is clamped to 0.8 - 1.3", J(newSettings.sizes) === J([0.8, 1.3, 1.2]), J(newSettings.sizes));
check("'Hide station names' drops every label…", newSettings.allHidden === true);
check("…except the ones the rider is looking at", newSettings.lineKept > 0, newSettings.lineKept);
check("…and puts them all back when switched off", newSettings.shownAgain === true);
check("all six new settings persist", run(`(() => {
  const store = {};
  const real = localStorage.setItem;
  localStorage.setItem = (k, v) => { store[k] = v; };
  setWalkSpeed(3.5); setTrainsHidden(true); setOtherPlayersHidden(true);
  setSatBrightness(0.45); setLabelScale(1.15); setLabelsHidden(true);
  savePrefs();
  localStorage.setItem = real;
  const blob = JSON.parse(store[PREFS_KEY] || "{}");
  setWalkSpeed(4.3); setTrainsHidden(false); setOtherPlayersHidden(false);
  setSatBrightness(1); setLabelScale(1); setLabelsHidden(false);
  return Math.abs(blob.walkSpeed - 3.5) < 1e-9 && blob.hideTrains === true && blob.hideOtherPlayers === true
    && Math.abs(blob.satBrightness - 0.45) < 1e-9 && Math.abs(blob.labelScale - 1.15) < 1e-9
    && blob.hideLabels === true;
})()`));


/* ==========================================================================
 * RENDERING ROUND 2 — street links, folding, corner bundling, capsule axis,
 * loop truncation, label fallback, shade merging
 * ======================================================================== */

/* ---- 1. CROSS-STATION STREET TRANSFERS ARE DRAWN ---- */
const links = run(`(() => {
  const g = buildGraph();
  const edges = new Set();
  for (const e of g.transferEdges) {
    if (!e.street) continue;
    const a = g.nodes.get(e.from), b = g.nodes.get(e.to);
    edges.add([a.stationId, b.stationId].sort().join(">"));
  }
  const drawn = state.streetLinks.map(l => [l.stationA, l.stationB].sort().join(">"));
  let offCentroid = 0;
  for (const l of state.streetLinks) {
    const a = partCentroid(l.partA), b = partCentroid(l.partB);
    if (a) offCentroid = Math.max(offCentroid, Math.hypot(l.ax - a[0], l.az - b === null ? 0 : l.az - a[1]));
    if (b) offCentroid = Math.max(offCentroid, Math.hypot(l.bx - b[0], l.bz - b[1]));
  }
  return { edges: [...edges].sort(), drawn: drawn.sort(), offCentroid,
    sameStation: state.streetLinks.filter(l => l.stationA === l.stationB).length };
})()`);
check("every street transfer edge in the graph is also drawn on the map",
	J(links.edges) === J(links.drawn) && links.drawn.length > 0, J(links.drawn));
check("street links run centroid to centroid", links.offCentroid < 1e-6, links.offCentroid);
check("a street link always joins two DIFFERENT stations", links.sameStation === 0, links.sameStation);
check("the pair list is computed once and shared with the graph", run(`(() => {
  const first = state.streetPairs;
  buildGraph();                       // must NOT recompute
  const same = state.streetPairs === first;
  prepareGeometry();                  // ...but a geometry rebuild must
  return same && state.streetPairs !== first && state.streetPairs.length === first.length;
})()`));
check("street links are dropped when a Layers filter hides their stations", run(`(() => {
  const before = state.streetLinks.length;
  setModeVisible("train", false);
  const hidden = state.streetLinks.length;
  setModeVisible("train", true);
  return { before, hidden, back: state.streetLinks.length };
})()`).hidden === 0);

/* ---- 2. NO FOLDING: arrivals at a through station are collinear ---- */
const folds = run(`(() => {
  const ends = new Map();             // partId|hex -> [outgoing unit tangent]
  for (const s of state.ribbons) {
    for (const end of [0, 1]) {
      const k = (end === 0 ? s.partA : s.partB) + "|" + s.hex;
      const t = endApproachChord(s.drawPts || s.pts, end, 18);
      if (!t) continue;
      if (!ends.has(k)) ends.set(k, []);
      ends.get(k).push({ id: s.id, t });
    }
  }
  const worst = [];
  for (const [k, list] of ends) {
    if (list.length !== 2) continue;                     // through stations only
    const dot = list[0].t[0] * list[1].t[0] + list[0].t[1] * list[1].t[1];
    const deg = Math.acos(Math.max(-1, Math.min(1, dot))) * 180 / Math.PI;
    worst.push({ k, deg });
  }
  worst.sort((a, b) => a.deg - b.deg);
  return { n: worst.length, straightest: worst[0], corner: worst[worst.length - 1],
    // the two green through stations whose centroid sits ~3 blocks off the trunk
    offAxis: worst.filter(w => w.k === "mu:0|#00933c" || w.k === "gf:0|#00933c").map(w => Math.round(w.deg)) };
})()`);
check("through stations have two arrivals to compare", folds.n >= 8, folds.n);
check("the off-axis green stations (Garfield, Museum) arrive collinear — no lens",
	folds.offAxis.length === 2 && folds.offAxis.every((d) => d >= 176), J(folds.offAxis));
check("no through station folds back on itself (a fold reads as a sharp angle)",
	folds.straightest.deg > 90, J(folds.straightest));
/* the coherence guard: two segments that genuinely meet at 90 degrees must STAY at 90 */
const cornerKeep = run(`(() => {
  const mk = (pts, partA, partB) => ({ pts: pts.map(p => p.slice()), partA, partB, hex: "#123456" });
  // a real corner at "c": one arm comes from the west, the other leaves to the south
  const west = mk(resamplePolyline([[-300, 0], [-150, 0], [0, 0]], 32), "w:0", "c:0");
  const south = mk(resamplePolyline([[0, 0], [0, 150], [0, 300]], 32), "c:0", "s:0");
  const before = Math.round(Math.acos(0) * 180 / Math.PI);
  alignEndTangents([west, south]);
  const a = endApproachChord(west.pts, 1, 40), b = endApproachChord(south.pts, 0, 40);
  const deg = Math.acos(Math.max(-1, Math.min(1, a[0]*b[0] + a[1]*b[1]))) * 180 / Math.PI;
  // ...while a nearly-straight pair IS pulled into line
  const n1 = mk(resamplePolyline([[0, -300], [0, -150], [0, 0]], 32), "n:0", "m:0");
  const n2 = mk(resamplePolyline([[0, 0], [14, 150], [16, 300]], 32), "m:0", "p:0");
  // measured over the rigid window (ALIGN_HOLD): that is where the fold used to show
  const wasStraight = (() => {
    const x = endApproachChord(n1.pts, 1, 20), y = endApproachChord(n2.pts, 0, 20);
    return Math.acos(Math.max(-1, Math.min(1, x[0]*y[0] + x[1]*y[1]))) * 180 / Math.PI;
  })();
  alignEndTangents([n1, n2]);
  const x = endApproachChord(n1.pts, 1, 20), y = endApproachChord(n2.pts, 0, 20);
  const straightened = Math.acos(Math.max(-1, Math.min(1, x[0]*y[0] + x[1]*y[1]))) * 180 / Math.PI;
  return { before, deg, wasStraight, straightened };
})()`);
check("a genuine 90-degree corner station is left alone",
	Math.abs(cornerKeep.deg - 90) < 1, cornerKeep.deg.toFixed(2) + " deg");
check("...while a near-straight pair IS pulled collinear",
	cornerKeep.wasStraight < 176 && cornerKeep.straightened > 179.9,
	cornerKeep.wasStraight.toFixed(1) + " -> " + cornerKeep.straightened.toFixed(2) + " deg");

/* the pass itself: a rigid rotation, so nothing bunches */
const rot = run(`(() => {
  const line = [];
  for (let i = 0; i < 32; i++) line.push([i * 10, 0]);
  const before = [];
  for (let i = 1; i < line.length; i++) before.push(Math.hypot(line[i][0]-line[i-1][0], line[i][1]-line[i-1][1]));
  const copy = line.map(p => p.slice());
  rotateEndWindow(copy, 0, 12 * Math.PI / 180, 40, 90);
  const after = [];
  for (let i = 1; i < copy.length; i++) after.push(Math.hypot(copy[i][0]-copy[i-1][0], copy[i][1]-copy[i-1][1]));
  const chord = endApproachChord(copy, 0, 40);
  return {
    endpointMoved: Math.hypot(copy[0][0]-line[0][0], copy[0][1]-line[0][1]),
    spacing: Math.max(...after.map((v, i) => Math.abs(v - before[i]))),
    angle: Math.atan2(chord[1], chord[0]) * 180 / Math.PI,
    tailMoved: Math.hypot(copy[31][0]-line[31][0], copy[31][1]-line[31][1]),
  };
})()`);
check("the alignment rotation never moves the endpoint", rot.endpointMoved < 1e-9, rot.endpointMoved);
check("...and never bunches samples (spacing stays within 12 %)",
	rot.spacing / 10 < 0.12, (rot.spacing / 10 * 100).toFixed(1) + "%");
check("...it turns the approach chord by exactly the angle asked for",
	Math.abs(rot.angle - 12) < 0.01, rot.angle.toFixed(3));
check("...and leaves everything past its reach untouched", rot.tailMoved < 1e-9, rot.tailMoved);

/* ---- 3. REFERENCE-CENTRELINE BUNDLING: a constant gap through a corner ---- */
run(`
function _drawnScreenLine(seg) {
  const w = ribbonWidth(), gap = Math.max(0.6, w * 0.16);
  const pts = toScreenPath(seg.drawPts || seg.pts);
  return { line: offsetPolyline(pts, (seg.idx - (seg.count - 1) / 2) * (w + gap) * (seg.offSign || 1)),
           nominal: w + gap };
}
function _gapProfile(a, b) {
  const out = [];
  for (const p of a) out.push(pointPolylineDist(p, b));
  return { min: Math.min(...out), max: Math.max(...out), mean: out.reduce((x, y) => x + y, 0) / out.length };
}
`);
const corner = run(`(() => {
  const view = { ...state.view };
  state.view = { x: 1300, z: 400, scale: 2 };
  const brown = state.ribbons.find(s => s.id === "#8b5e3c|ap:1>em:0");
  const orange = state.ribbons.find(s => s.id === "#ff6319|ap:1>em:0");
  const A = _drawnScreenLine(brown), B = _drawnScreenLine(orange);
  const prof = _gapProfile(A.line, B.line);
  state.view = view;
  invalidateStatic();
  return { ref: orange.refId, nominal: A.nominal, ...prof,
    spread: (prof.max - prof.min) / prof.nominal || (prof.max - prof.min) / A.nominal };
})()`);
check("the crosstown corner is a two-colour bundle, orange on brown's centreline",
	corner.ref === "#8b5e3c|ap:1>em:0", corner.ref);
check("the drawn gap through the 90-degree corner is constant",
	corner.spread < 0.12, "spread " + (corner.spread * 100).toFixed(1) + "% of " + corner.nominal.toFixed(2) + " px");
check("...and the two lines never cross or touch",
	corner.min > corner.nominal * 0.55, "min gap " + corner.min.toFixed(2) + " px");

/* the same guarantee on a synthetic rounded 90-degree corner, straight from the maths */
const synth = run(`(() => {
  // a corner: 200 blocks east, a 60-block quarter-circle, then 200 blocks south
  const raw = [];
  for (let x = 0; x <= 200; x += 10) raw.push([x, 0]);
  for (let a = 0; a <= 90; a += 6) {
    raw.push([200 + 60 * Math.sin(a * Math.PI / 180), 60 - 60 * Math.cos(a * Math.PI / 180)]);
  }
  for (let z = 60; z <= 260; z += 10) raw.push([260, z]);
  const ref = resamplePolyline(raw, 32);
  // the companion track: the same corner, offset ~8 blocks to the inside
  const off = raw.map((p, i) => {
    const q = raw[Math.min(raw.length - 1, i + 1)], r = raw[Math.max(0, i - 1)];
    const dx = q[0] - r[0], dz = q[1] - r[1];
    const l = Math.hypot(dx, dz) || 1;
    return [p[0] + (dz / l) * 8, p[1] - (dx / l) * 8];
  });
  const own = resamplePolyline(off, 32);
  const drawn = projectOntoReference(own, ref);
  const view = { ...state.view };
  state.view = { x: 130, z: 130, scale: 2 };
  const w = ribbonWidth(), gap = Math.max(0.6, w * 0.16);
  const A = offsetPolyline(toScreenPath(ref), -(w + gap) / 2);
  const B = offsetPolyline(toScreenPath(drawn), (w + gap) / 2);
  const Bown = offsetPolyline(toScreenPath(own), (w + gap) / 2);
  const prof = _gapProfile(A, B), rawProf = _gapProfile(A, Bown);
  state.view = view;
  invalidateStatic();
  return { nominal: w + gap, prof, rawProf, projected: !!drawn };
})()`);
check("a synthetic 90-degree corner projects onto its reference", synth.projected === true);
check("bundled on the reference, the gap holds through the apex",
	(synth.prof.max - synth.prof.min) / synth.nominal < 0.1,
	"spread " + (((synth.prof.max - synth.prof.min) / synth.nominal) * 100).toFixed(1) + "%");
check("...which is measurably tighter than offsetting each own centreline",
	(synth.prof.max - synth.prof.min) < (synth.rawProf.max - synth.rawProf.min),
	"projected " + (synth.prof.max - synth.prof.min).toFixed(2) + " px vs own " + (synth.rawProf.max - synth.rawProf.min).toFixed(2) + " px");
check("a borrowed centreline still starts and ends on its OWN geometry", run(`(() => {
  let worst = 0;
  for (const s of state.ribbons) {
    if (!s.refId) continue;
    worst = Math.max(worst, Math.hypot(s.drawPts[0][0]-s.pts[0][0], s.drawPts[0][1]-s.pts[0][1]));
    const n = s.pts.length - 1;
    worst = Math.max(worst, Math.hypot(s.drawPts[n][0]-s.pts[n][0], s.drawPts[n][1]-s.pts[n][1]));
  }
  return worst;
})()`) < 1e-9);
check("a segment that does not actually run along the reference is refused", run(`(() => {
  const ref = resamplePolyline([[0,0],[400,0]], 32);
  // past the end of the reference: both ends clamp to the same place, no stretch found
  const beyond = resamplePolyline([[500,0],[560,0]], 32);
  // and one running ACROSS it rather than along it
  const across = resamplePolyline([[200,-90],[200,90]], 32);
  return projectOntoReference(beyond, ref) === null && projectOntoReference(across, ref) === null;
})()`));
check("a short link inside a long corridor DOES follow it", run(`(() => {
  const ref = resamplePolyline([[0,0],[200,0],[400,0]], 32);
  const short = resamplePolyline([[100,6],[160,6]], 32);
  const out = projectOntoReference(short, ref);
  if (!out) return false;
  // it keeps its own ends and its own 6-block lateral distance
  return Math.hypot(out[0][0]-100, out[0][1]-6) < 1e-6
    && Math.hypot(out[31][0]-160, out[31][1]-6) < 1e-6
    && out.every(p => Math.abs(p[1] - 6) < 0.05);
})()`));
check("reference choice is the lowest colour int and resolves chains", run(`(() => {
  const bad = [];
  for (const s of state.ribbons) {
    if (!s.refId) continue;
    const ref = state.ribbons.find(o => o.id === s.refId);
    if (!ref || ref.colorInt >= s.colorInt) bad.push(s.id);
  }
  return bad;
})()`).length === 0);

/* ---- 4. THE INTERCHANGE CAPSULE SPANS ACROSS THE BUNDLE ---- */
const caps = run(`(() => {
  const rec = () => {
    const calls = { rotate: null, rect: null };
    const g = {
      save() {}, restore() {}, beginPath() {}, fill() {}, stroke() {}, arc() {}, drawImage() {},
      translate() {}, rotate(a) { calls.rotate = a; },
      roundRect(x, y, w, h) { calls.rect = { x, y, w, h }; },
    };
    return { g, calls };
  };
  const probe = (partId) => {
    const gl = state.glyphs.find(x => x.partId === partId);
    const { g, calls } = rec();
    drawGlyph(g, gl, 1, 5);
    if (!calls.rect) return { partId, capsule: false };
    // the capsule is authored with its LONG axis along local +Y; rotate(t) sends +Y to
    // (-sin t, cos t), which is where the capsule actually points on screen
    const t = calls.rotate || 0;
    const axis = [-Math.sin(t), Math.cos(t)];
    const dot = Math.abs(axis[0] * gl.dir[0] + axis[1] * gl.dir[1]);
    return { partId, capsule: true, long: calls.rect.h > calls.rect.w,
      alongLineDot: dot, dir: gl.dir };
  };
  return { bc: probe("bc:0"), em: probe("em:0"), fg: probe("fg:0"), gf: probe("gf:0") };
})()`);
check("the capsule really is authored long-axis-up", caps.bc.capsule && caps.bc.long === true, J(caps.bc));
check("a capsule on the VERTICAL trunk spans horizontally (across the bundle)",
	caps.bc.alongLineDot < 0.05, "|axis . dir| = " + caps.bc.alongLineDot.toFixed(4));
check("a capsule on the HORIZONTAL crosstown spans vertically (across the bundle)",
	caps.em.capsule && caps.em.alongLineDot < 0.05 && Math.abs(caps.em.dir[0]) > 0.9,
	"|axis . dir| = " + caps.em.alongLineDot.toFixed(4) + " dir " + J(caps.em.dir.map(v => Math.round(v * 100) / 100)));
check("...and the same at the other crosstown interchange", caps.fg.alongLineDot < 0.05, caps.fg.alongLineDot.toFixed(4));
check("a single-colour stop is still a plain dot, not a capsule", caps.gf.capsule === false, J(caps.gf));

/* ---- 5. TURNING LOOPS ARE TRUNCATED ---- */
const loop = run(`(() => {
  // a straight run north, then a balloon loop right round the terminus
  const raw = [[0, 0], [0, 60], [0, 120], [2, 160], [10, 200], [40, 220], [70, 200],
    [72, 168], [52, 148], [22, 152], [2, 172]];
  const C = [0, 170], A = [0, 0];
  const cut = truncateApproaches(raw, A, C);
  // nothing may survive PAST the arrival point (i.e. out around the loop)
  const beyond = cut.filter(p => p[1] > 165).length;
  const inside = truncateApproaches([[0, 0], [12, 8], [4, 16], [0, 6]], [0, 0], [0, 6]);
  const plain = truncateApproaches([[0, 0], [0, 80], [0, 160]], [0, 0], [0, 160]);
  return {
    rawLen: Math.round(polylineLength(raw)), cutLen: Math.round(polylineLength(cut)),
    n: cut.length, beyond,
    tailToCentroid: Math.round(Math.hypot(cut[cut.length-1][0] - C[0], cut[cut.length-1][1] - C[1])),
    inside: inside.length, plainLen: Math.round(polylineLength(plain)), plainN: plain.length,
  };
})()`);
check("a balloon loop is cut off at the first approach",
	loop.cutLen < loop.rawLen * 0.6, loop.cutLen + " of " + loop.rawLen + " blocks kept");
check("...leaving no sample out around the loop", loop.beyond === 0, loop.beyond);
check("...and the surviving end is the arrival point, near the centroid",
	loop.tailToCentroid <= 28, loop.tailToCentroid + " blocks");
check("a segment entirely inside one station falls back to the straight chord",
	loop.inside === 2, loop.inside);
check("a plain approach is never truncated", loop.plainLen === 160 && loop.plainN === 3, J([loop.plainLen, loop.plainN]));
const demoLoop = run(`(() => {
  const leg = state.legs.get("rL|0");
  const seg = state.segByPair.get("#7f8200|kf:0>wl:0");
  const kf = partCentroid("kf:0"), wl = partCentroid("wl:0");
  let far = 0;
  for (const p of seg.drawPts) far = Math.max(far, pointSegmentDist(p, kf, wl));
  return { rawLen: Math.round(polylineLength(leg.pts)), drawnLen: Math.round(polylineLength(seg.drawPts)),
    src: seg.sourceCount, far: Math.round(far) };
})()`);
check("the demo's balloon-loop terminus draws as one straight approach",
	demoLoop.drawnLen < demoLoop.rawLen * 0.6 && demoLoop.far <= 4,
	demoLoop.drawnLen + " of " + demoLoop.rawLen + " blocks, max " + demoLoop.far + " off the chord");
check("...and its looped and plain tracks still pair-average into ONE segment",
	demoLoop.src === 2, demoLoop.src);

/* ---- 6. SERVICE-LABEL FALLBACK CHAIN ---- */
const labelChain = run(`(() => ({
  bareIn: routeServiceLabel({ number: "IN", display: "Kransfield Loop", name: "Kransfield Loop||Kransfield" }),
  bareOu: routeServiceLabel({ number: "OU", display: "Kransfield Loop", name: "Kransfield Loop||Willowbank" }),
  nameless: routeServiceLabel({ number: "OU", display: "", name: "" }),
  numbered: routeServiceLabel({ number: "4 IN", display: "Baker Line" }),
  nameOnly: routeServiceLabel({ number: "", display: "Airport Express" }),
  dirName: routeServiceLabel({ number: "IN", display: "Inbound" }),
  strictEmpty: stripDirectionTokens("OU"),
  strictKeeps: stripDirectionTokens("Ba"),
}))()`);
check('a direction-only number falls back to the route NAME ("IN" + Kransfield Loop -> "Kra")',
	labelChain.bareIn === "Kra", labelChain.bareIn);
check("...so both directions of that line show ONE bullet", labelChain.bareIn === labelChain.bareOu, labelChain.bareOu);
check('a route with nothing else keeps its raw label ("OU" -> "OU")', labelChain.nameless === "OU", labelChain.nameless);
check("a real number still wins", labelChain.numbered === "4", labelChain.numbered);
check("a nameless-number route uses its name", labelChain.nameOnly === "Air", labelChain.nameOnly);
check("a route named after its direction too still shows something", labelChain.dirName === "IN", labelChain.dirName);
check("stripDirectionTokens is the STRICT form (no fallback)",
	labelChain.strictEmpty === "" && labelChain.strictKeeps === "Ba", J([labelChain.strictEmpty, labelChain.strictKeeps]));
check("the demo's direction-numbered line shows exactly one bullet",
	J(run(`lineLabels("#7f8200")`)) === '["Kra"]', J(run(`lineLabels("#7f8200")`)));

/* ---- 7. NEAR-IDENTICAL COLOURS ARE ONE LINE ---- */
const shades = run(`(() => {
  const canon = (list) => {
    const m = canonicaliseRouteColors(list.map((c, i) => ({ id: "r" + i, color: c })));
    return list.map((c) => "#" + m.get(c).toString(16).padStart(6, "0"));
  };
  return {
    drift: canon([0x808000, 0x7f8200]),
    apart: canon([0x808000, 0x4060a0]),
    chain: canon([0x808000, 0x808010, 0x808020]),
    far: canon([0x808000, 0x808030]),
    demoLine: (() => { const l = state.lines.get("#7f8200"); return l ? l.routeIds.slice().sort() : null; })(),
    demoRoutes: [state.routes.get("rL").hex, state.routes.get("rLo").hex],
    demoSegs: [...state.segByPair.values()].filter(s => s.hex === "#7f8200").length,
  };
})()`);
check("two shades within 16 per channel collapse to one line",
	shades.drift[0] === shades.drift[1] && shades.drift[0] === "#7f8200", J(shades.drift));
check("genuinely different colours stay separate", shades.apart[0] !== shades.apart[1], J(shades.apart));
check("chained shades collapse together onto the lowest colour int",
	new Set(shades.chain).size === 1 && shades.chain[0] === "#808000", J(shades.chain));
check("a gap wider than the threshold does NOT merge", shades.far[0] !== shades.far[1], J(shades.far));
check("the demo's drifted olive pair is ONE line with both routes",
	J(shades.demoLine) === J(["rL", "rLo"]), J(shades.demoLine));
check("...both routes now carry the representative hex",
	shades.demoRoutes[0] === "#7f8200" && shades.demoRoutes[1] === "#7f8200", J(shades.demoRoutes));
check("...and they draw as ONE segment, not a braided pair", shades.demoSegs === 1, shades.demoSegs);



{   /* block-scoped: this section's locals must not collide with the sections above */

/* ==========================================================================
 * 8. SEND TO GAME — pairing codes, journey -> wire payload, token lifecycle
 * ========================================================================
 * All of it is exercised through the ?demo=1 stubs (demoNav), so there is no
 * server here and no fetch: navPair / navSend / navCheckStatus resolve inline.
 * ------------------------------------------------------------------------ */

console.log("\n-- send to game --");

/* helpers inside the sandbox: a plan with the memo bypassed, and a fabricated
   journey for the leg-cap tests */
run(`
	function _navPlan(from, to, opts, prefs) {
		planMemo.entries.clear();
		return planJourneys(state.plan.graph || (state.plan.graph = buildGraph()),
			from, to, prefs || { mode: "fastest", stepFree: false }, Date.now(), opts || {});
	}
	function _navFirstWith(pred, from, to, opts, prefs) {
		return _navPlan(from, to, opts, prefs).find(pred) || null;
	}
`);

/* ---- 8a. code sanitisation + validation (pure) ---- */
const code = run(`(() => { const strict = (r) => sanitiseWithAlphabet(PAIR_ALPHABET, r); return ({
	plain:   strict("abc234"),
	dashed:  strict("abc-234"),
	spaced:  strict("  a b c 2 3 4  "),
	ambig:   strict("AIOZ9"),
	capped:  strict("ABCDEFGH"),
	nul:     strict(null),
	num:     strict(234),
	zeroOne: strict("A0B1C2"),
	alphabet: PAIR_ALPHABET,
	len: PAIR_ALPHABET.length,
	valid:   pairCodeValid("ABC234"),
	lower:   pairCodeValid("abc234"),
	short:   pairCodeValid("ABC23"),
	banned:  pairCodeValid("ABC201"),
	bannedStrict: strict("ABCDEI").length === PAIR_CODE_LEN,
	long:    pairCodeValid("ABC2345"),
	dashOk:  pairCodeValid("ABC-234"),
	empty:   pairCodeValid(""),
	demoWide: sanitisePairCode("DEMO23"),
	demoStrict: strict("DEMO23"),
	keepsLU: strict("BLUE23"),
}); })()`);
check("the code alphabet mirrors NavStore.CODE_ALPHABET: A-Z minus I/O plus 2-9 (32 symbols)",
	code.len === 32 && !/[IO01]/.test(code.alphabet) && /L/.test(code.alphabet) && /U/.test(code.alphabet),
	code.alphabet);
check("a typed code is upper-cased", code.plain === "ABC234", code.plain);
check("dashes and spaces are stripped (paste-friendly)",
	code.dashed === "ABC234" && code.spaced === "ABC234", J([code.dashed, code.spaced]));
check("the ambiguous characters (I, O, 0, 1) are dropped, never guessed at",
	code.ambig === "AZ9" && code.zeroOne === "ABC2", J([code.ambig, code.zeroOne]));
check("...but L and U are NOT: the server generates codes containing them",
	code.keepsLU === "BLUE23", code.keepsLU);
check("the input can never grow past six characters", code.capped === "ABCDEF", code.capped);
check("null / a number sanitise without throwing",
	code.nul === "" && code.num === "234", J([code.nul, code.num]));
check("a full six-character code validates, in either case",
	code.valid === true && code.lower === true, J([code.valid, code.lower]));
check("a short, over-long or empty code does not", !code.short && !code.long && !code.empty,
	J([code.short, code.long, code.empty]));
check("a code carrying a character outside the alphabet does not validate",
	code.banned === false && code.bannedStrict === false, J([code.banned, code.bannedStrict]));
check("demo mode alone widens the set so the demo code DEMO23 can be typed",
	code.demoWide === "DEMO23" && code.demoStrict === "DEM23", J([code.demoWide, code.demoStrict]));
check("...but separators inside an otherwise valid code do", code.dashOk === true, code.dashOk);

const tok = run(`({
	good: navTokenValid("0123456789abcdef0123456789abcdef"),
	upper: navTokenValid("0123456789ABCDEF0123456789ABCDEF"),
	short: navTokenValid("0123456789abcdef0123456789abcde"),
	junk: navTokenValid("zzzz56789abcdef0123456789abcdef0"),
	nul: navTokenValid(null),
})`);
check("only 32 hex characters count as a token",
	tok.good && tok.upper && !tok.short && !tok.junk && !tok.nul, J(tok));

/* ---- 8b. leg -> wire payload, every leg type ---- */

/* the demo's headline query: ride, cross-platform transfer, ride, concourse transfer */
const wire = run(`(() => {
	const j = _navPlan("mh", "ap", { toPartId: "ap:1" })
		.find((x) => x.legs.length === 4 && x.legs[2].kind === "ride");
	if (!j) return null;
	const built = journeyPayload(j);
	return { built, kinds: j.legs.map((l) => l.kind), dest: j.toStation,
		stopCount: j.legs[0].stopCount, board: j.legs[0].fromPlatform };
})()`);
check("the headline journey serialises at all", wire && wire.built, wire ? "" : "no 4-leg journey");
check("its shape is ride / transfer / ride / transfer",
	J(wire.built.payload.legs.map((l) => l.type)) === J(["ride", "transfer", "ride", "transfer"]),
	J(wire.built.payload.legs.map((l) => l.type)));
check("a ride leg carries route + board + alight + stops, all decimal-string ids",
	wire.built.payload.legs[0].route === "r4" && wire.built.payload.legs[0].board === "mh_g"
	&& wire.built.payload.legs[0].alight === "bc_g" && wire.built.payload.legs[0].stops === wire.stopCount,
	J(wire.built.payload.legs[0]));
check("a ride leg with no through run carries NO via key",
	!("via" in wire.built.payload.legs[0]), J(Object.keys(wire.built.payload.legs[0])));
check("a same-station walk becomes a transfer with bare platform ids",
	wire.built.payload.legs[1].type === "transfer" && wire.built.payload.legs[1].from === "bc_g"
	&& wire.built.payload.legs[1].to === "bc_b" && Number.isInteger(wire.built.payload.legs[1].meters),
	J(wire.built.payload.legs[1]));
check("the concourse link to the Terminal is a transfer too (90 m)",
	wire.built.payload.legs[3].type === "transfer" && wire.built.payload.legs[3].meters === 90,
	J(wire.built.payload.legs[3]));
check("the destination is the itinerary's own pin label",
	wire.built.payload.destination === "Airport · Terminal", wire.built.payload.destination);
check("plannedArriveMs is an integer epoch, not a duration",
	Number.isInteger(wire.built.payload.plannedArriveMs) && wire.built.payload.plannedArriveMs > 1e12,
	wire.built.payload.plannedArriveMs);
check("nothing had to be trimmed", J(wire.built.notes) === "[]", J(wire.built.notes));

/* through run: Airport Express A continues as S at Harborview — one ride leg, one via */
const thru = run(`(() => {
	const j = _navFirstWith((x) => x.legs.some((l) => (l.continuations || []).length), "mh", "he");
	if (!j) return null;
	const p = journeyPayload(j).payload;
	const ride = p.legs.find((l) => l.type === "ride" && l.via);
	const leg = j.legs.find((l) => (l.continuations || []).length);
	return { ride, board: leg.fromPlatform, alight: leg.toPlatform,
		rides: p.legs.filter((l) => l.type === "ride").length,
		transfers: p.legs.filter((l) => l.type === "transfer").length };
})()`);
// via now carries the continuation's own name/bullet/colour/headsign too, because the
// game client cannot resolve a route it has not synced (the "? ?" bug).
check("a through run stays ONE ride leg and records the change as via",
	thru && thru.ride && thru.ride.route === "rA"
	&& thru.ride.via && thru.ride.via.length === 1
	&& thru.ride.via[0].route === "rS" && thru.ride.via[0].at === "hv_b"
	&& !!thru.ride.via[0].routeName && !!thru.ride.via[0].routeLabel, J(thru && thru.ride));
check("...boarding and alighting still span the whole run",
	thru.ride.board === thru.board && thru.ride.alight === thru.alight,
	J([thru.ride.board, thru.ride.alight]));

/* via is capped at four, and the ids are stringified */
const viaCap = run(`(() => {
	const leg = { kind: "ride", routeId: 7, fromPlatform: 1, toPlatform: 2, stopCount: 9,
		continuations: [1, 2, 3, 4, 5, 6].map((n) => ({ routeId: 100 + n, platform: 200 + n })) };
	return navLegPayload(leg);
})()`);
check("via is capped at the server's four", viaCap.via.length === run("NAV_MAX_VIA"), viaCap.via.length);
check("...and every id crosses the wire as a decimal STRING",
	viaCap.route === "7" && viaCap.board === "1" && viaCap.alight === "2"
	&& viaCap.via.every((v) => typeof v.route === "string" && typeof v.at === "string"),
	J(viaCap));
check("a fractional stop count is rounded to an integer",
	run(`navLegPayload({ kind: "ride", routeId: "r", fromPlatform: "a", toPlatform: "b", stopCount: 2.6 }).stops`) === 3,
	run(`navLegPayload({ kind: "ride", routeId: "r", fromPlatform: "a", toPlatform: "b", stopCount: 2.6 }).stops`));

/* a walk between two DIFFERENT stations keeps platform endpoints (it is not a transfer) */
const street = run(`(() => {
	const j = _navFirstWith((x) => x.legs.some((l) => l.kind === "walk" && l.fromStation
		&& l.toStation && l.fromStation !== l.toStation), "ng", "hc", {}, { mode: "walking", stepFree: false });
	if (!j) return null;
	const leg = j.legs.find((l) => l.kind === "walk" && l.fromStation !== l.toStation);
	return { transferish: navIsTransferLeg(leg), wire: navLegPayload(leg) };
})()`);
check("a street walk between two stations is a WALK, not a transfer",
	street && street.transferish === false && street.wire.type === "walk", J(street && street.wire));
check("...and both its ends are {platform:id} objects",
	street.wire.from.platform && street.wire.to.platform
	&& !("x" in street.wire.from) && !("x" in street.wire.to), J(street.wire));

/* point origin: the live GPS start becomes world coordinates */
const point = run(`(() => {
	const g = state.plan.graph || (state.plan.graph = buildGraph());
	const p0 = state.platforms.get("mh_g").xz;
	const res = planEndpoints(g, { point: [p0[0] + 40.257, p0[1] + 40], y: 71.5, label: "My location", live: true },
		{ stationId: "ap", partId: "ap:1" }, { mode: "fastest", stepFree: false }, Date.now());
	const j = (res.journeys || [])[0];
	if (!j) return null;
	const built = journeyPayload(j);
	return { first: built.payload.legs[0], want: [p0[0] + 40.257, p0[1] + 40] };
})()`);
check("a leading walk off the live GPS origin is a walk with coordinate endpoints",
	point && point.first.type === "walk" && Number.isFinite(point.first.from.x)
	&& Number.isFinite(point.first.from.z), J(point && point.first));
check("...at the point's own world x/z, rounded to 2 dp",
	point.first.from.x === Math.round(point.want[0] * 100) / 100
	&& point.first.from.z === Math.round(point.want[1] * 100) / 100, J(point.first.from));
check("...carrying the player's own y when the point has one", point.first.from.y === 71.5, point.first.from.y);
check("...and lands on a {platform:id}", !!point.first.to.platform, J(point.first.to));

/* a dropped pin has no y: it borrows the platform at the other end of the walk */
const pinY = run(`(() => {
	const g = state.plan.graph || (state.plan.graph = buildGraph());
	const p0 = state.platforms.get("mh_g").xz;
	const res = planEndpoints(g, { point: [p0[0] + 40, p0[1] + 40], label: "Dropped pin" },
		{ stationId: "ap", partId: "ap:1" }, { mode: "fastest", stepFree: false }, Date.now());
	const j = (res.journeys || [])[0];
	if (!j) return null;
	const leg = j.legs[0];
	const other = state.platforms.get(leg.toPlatform);
	return { y: navEndpoint(leg, "from").y, platformY: other ? other.y : null,
		orphan: navEndpoint({ kind: "walk", fromPoint: "@nowhere", fromPlatform: "@nowhere",
			toPlatform: "@alsonowhere", toPoint: null }, "from") };
})()`);
check("a dropped pin with no height borrows the platform's y",
	pinY && pinY.y === pinY.platformY, J(pinY));
check("with nothing to borrow from, the walk endpoint falls back to y 64",
	pinY.orphan.y === 64 && pinY.orphan.x === 0 && pinY.orphan.z === 0, J(pinY.orphan));

/* ---- 8c. the 24-leg clamp ---- */
const clamp24 = run(`(() => {
	const ride = (i) => ({ kind: "ride", routeId: "r" + i, fromPlatform: "p" + i, toPlatform: "p" + (i + 1), stopCount: 1 });
	const walk = { kind: "walk", fromPoint: "@from", fromPlatform: "@from", toPlatform: "p0",
		toPoint: null, meters: 240, fromStation: null, toStation: "s0" };
	const mk = (legs) => ({ legs, arriveMs: 1700000000000, toStation: "ap", toPart: null, toPoint: null, toName: "" });
	const exactly24 = mk([walk].concat(Array.from({ length: 23 }, (_, i) => ride(i))));
	const twentyFive = mk([walk].concat(Array.from({ length: 24 }, (_, i) => ride(i))));
	const thirty = mk([walk].concat(Array.from({ length: 29 }, (_, i) => ride(i))));
	const noLeadWalk = mk(Array.from({ length: 26 }, (_, i) => ride(i)));
	const b1 = journeyPayload(exactly24), b2 = journeyPayload(twentyFive);
	const b3 = journeyPayload(thirty), b4 = journeyPayload(noLeadWalk);
	return {
		at24: [b1.payload.legs.length, b1.notes, b1.payload.legs[0].type],
		at25: [b2.payload.legs.length, b2.notes, b2.payload.legs[0].type],
		at30: [b3.payload.legs.length, b3.notes],
		noWalk: [b4.payload.legs.length, b4.notes],
		notice25: navNoticeText(b2.notes), notice30: navNoticeText(b3.notes), noticeNone: navNoticeText(b1.notes),
		empty: journeyPayload({ legs: [] }), nul: journeyPayload(null),
	};
})()`);
check("exactly 24 legs go through untouched, leading walk and all",
	clamp24.at24[0] === 24 && J(clamp24.at24[1]) === "[]" && clamp24.at24[2] === "walk", J(clamp24.at24));
check("25 legs drop the LEADING walk first and fit",
	clamp24.at25[0] === 24 && J(clamp24.at25[1]) === J(["dropped-lead-walk"]) && clamp24.at25[2] === "ride",
	J(clamp24.at25));
check("a journey still too long after that is truncated to 24 and says so",
	clamp24.at30[0] === 24 && J(clamp24.at30[1]) === J(["dropped-lead-walk", "truncated"]), J(clamp24.at30));
check("with no leading walk to drop it truncates straight away",
	clamp24.noWalk[0] === 24 && J(clamp24.noWalk[1]) === J(["truncated"]), J(clamp24.noWalk));
check("each trim has its own rider-facing line",
	/opening walk/.test(clamp24.notice25) && /24 steps/.test(clamp24.notice30) && clamp24.noticeNone === "",
	J([clamp24.notice25, clamp24.notice30]));
check("an empty or missing journey serialises to null",
	clamp24.empty === null && clamp24.nul === null, J([clamp24.empty, clamp24.nul]));

/* the destination string is capped at 64 characters */
const destCap = run(`(() => ({
	long: navDestinationLabel({ toPoint: "@to", toName: "x".repeat(200) }),
	pin: navDestinationLabel({ toPoint: "@to", toName: "" }),
	blank: navDestinationLabel({ toStation: "nope", toPart: null }),
}))()`);
check("a runaway destination name is capped at 64 characters with an ellipsis",
	destCap.long.length === 64 && destCap.long.endsWith("…"), destCap.long.length);
check("a nameless pin still names itself", destCap.pin === "Dropped pin", destCap.pin);
check("an unresolvable destination never sends an empty string",
	destCap.blank.length > 0, destCap.blank);

/* ---- 8d. token persistence round trip ---- */
const persist = run(`(() => {
	const before = { t: state.nav.token, p: state.nav.player };
	navSetToken("0123456789abcdef0123456789abcdef", "Thomas", "Chrome on Mac");
	const raw = JSON.parse(localStorage.getItem(PREFS_KEY));
	state.nav.token = ""; state.nav.player = ""; state.nav.label = ""; state.nav.online = true;
	loadPrefs();
	const restored = { t: state.nav.token, p: state.nav.player, l: state.nav.label, on: state.nav.online };
	// a junk token in storage must not survive the read
	localStorage.setItem(PREFS_KEY, JSON.stringify(Object.assign({}, raw, { navToken: "nope" })));
	loadPrefs();
	const rejected = { t: state.nav.token, p: state.nav.player };
	// and nothing beyond token/player/label is written
	const navKeys = Object.keys(raw).filter((k) => k.indexOf("nav") === 0);
	navClearToken();
	const cleared = JSON.parse(localStorage.getItem(PREFS_KEY));
	return { raw, restored, rejected, navKeys, cleared: { t: cleared.navToken, p: cleared.navPlayer }, before };
})()`);
check("the token, player and label round-trip through sa_mapplus_prefs",
	persist.restored.t === "0123456789abcdef0123456789abcdef" && persist.restored.p === "Thomas"
	&& persist.restored.l === "Chrome on Mac", J(persist.restored));
check("a reload starts with the online state UNKNOWN, not assumed",
	persist.restored.on === null, persist.restored.on);
check("only token/player/label are persisted about the pairing",
	J(persist.navKeys.sort()) === J(["navLabel", "navPlayer", "navToken"]), J(persist.navKeys));
check("a malformed stored token is dropped on load, player and all",
	persist.rejected.t === "" && persist.rejected.p === "", J(persist.rejected));
check("Unpair wipes the stored token immediately",
	persist.cleared.t === "" && persist.cleared.p === "", J(persist.cleared));

/* ---- 8e. navstatus-driven state transitions ---- */
const status = run(`(() => {
	const out = {};
	navSetToken("0123456789abcdef0123456789abcdef", "Thomas", "L");
	out.paired = [navApplyStatus({ ok: true, player: "Thomas", online: true }), state.nav.online, state.nav.token !== ""];
	out.offline = [navApplyStatus({ ok: true, player: "Thomas", online: false }), state.nav.online, state.nav.token !== ""];
	out.renamed = [navApplyStatus({ ok: true, player: "Tom", online: true }), state.nav.player];
	// a transient failure must NOT throw the pairing away
	out.unreachable = [navApplyStatus({ ok: false, error: "Could not reach the dispatch server." }), state.nav.token !== ""];
	out.garbage = [navApplyStatus(null), state.nav.token !== ""];
	// only "unknown token" does
	out.unknown = [navApplyStatus({ ok: false, error: "unknown token" }), state.nav.token, state.nav.player, state.nav.online];
	// the server could not reach its own game thread: "we do not know", not "logged out"
	navSetToken("0123456789abcdef0123456789abcdef", "Thomas", "L");
	state.nav.online = true;
	out.stale = [navApplyStatus({ ok: true, player: "Thomas", online: false, stale: true }),
		state.nav.online, state.nav.token !== ""];
	out.expired = [(navSetToken("0123456789abcdef0123456789abcdef", "Thomas", "L"),
		navApplyStatus({ ok: false, error: "unknown or expired token" })), state.nav.token];
	return out;
})()`);
check("ok + online:true  -> paired, token kept",
	J(status.paired) === J(["paired", true, true]), J(status.paired));
check("ok + online:false -> offline, token STILL kept",
	J(status.offline) === J(["offline", false, true]), J(status.offline));
check("a renamed player is adopted from the reply", J(status.renamed) === J(["paired", "Tom"]), J(status.renamed));
check("an unreachable server leaves the pairing alone",
	J(status.unreachable) === J(["unreachable", true]) && J(status.garbage) === J(["unreachable", true]),
	J([status.unreachable, status.garbage]));
check("only 'unknown token' clears the pairing, silently",
	J(status.unknown) === J(["unknown", "", "", null]), J(status.unknown));
check("a stale reply leaves the online state UNKNOWN rather than claiming offline",
	J(status.stale) === J(["unknown-online", null, true]), J(status.stale));
check("the servlet's own 'unknown or expired token' wording also drops the pairing",
	J(status.expired) === J(["unknown", ""]), J(status.expired));

/* the same over the wire, through the demo stubs */
const liveStatus = await run(`(async () => {
	navSetToken(DEMO_NAV_TOKEN, "Demo", "harness");
	state.nav.demoOffline = false;
	const a = await navCheckStatus();
	state.nav.demoOffline = true;
	const b = await navCheckStatus();
	state.nav.demoOffline = false;
	navSetToken("0123456789abcdef0123456789abcdef", "Ghost", "harness");
	const c = await navCheckStatus();
	navClearToken();
	const d = await navCheckStatus();
	return { a, b, c, d, tokenAfterC: state.nav.token };
})()`);
check("navstatus over the stub: a live pairing reports paired", liveStatus.a === "paired", liveStatus.a);
check("...a logged-out player reports offline", liveStatus.b === "offline", liveStatus.b);
check("...a token the server never issued reports unknown and is dropped",
	liveStatus.c === "unknown" && liveStatus.tokenAfterC === "", J([liveStatus.c, liveStatus.tokenAfterC]));
check("...and with no token at all nothing is asked", liveStatus.d === "unpaired", liveStatus.d);

/* ---- 8f. pairing + sending through the demo stubs, errors surfaced ---- */
const pairing = await run(`(async () => {
	navClearToken();
	const bad = await navPair("ABC234");
	const short = await navPair("DEMO2");
	const good = await navPair("demo-23");
	return { bad, short, good, token: state.nav.token, player: state.nav.player,
		label: state.nav.label, online: state.nav.online };
})()`);
check("a wrong code comes back as the server's own message, not a throw",
	pairing.bad.ok === false && /invalid or expired code/.test(pairing.bad.error), J(pairing.bad));
check("a short code never leaves the browser",
	pairing.short.ok === false && /six-character/.test(pairing.short.error), J(pairing.short));
check("the demo code pairs (and is sanitised on the way in)",
	pairing.good.ok === true && pairing.player === "Demo", J(pairing.good));
check("pairing stores a 32-hex token and a device label",
	run(`navTokenValid(state.nav.token)`) === true && pairing.label.length > 0, pairing.label);
check("a fresh pairing is assumed online until told otherwise", pairing.online === true, pairing.online);

const sending = await run(`(async () => {
	const j = _navPlan("mh", "ap", { toPartId: "ap:1" })[0];
	navClearToken();
	const unpaired = await navSend(j);
	await navPair("DEMO23");
	state.nav.lastSendAt = 0; state.nav.demoLastAt = 0; state.nav.sentSig = "";
	const first = await navSend(j);
	const sentSig = state.nav.sentSig;
	const toast = state.nav.toast && state.nav.toast.text;
	const kind = state.nav.toast && state.nav.toast.kind;
	// the client holds the server's 1-per-3-s line itself
	const tooSoon = await navSend(j);
	// ...and the server's own limiter is there behind it
	state.nav.lastSendAt = 0;
	const serverLimited = await navSend(j);
	// player offline
	state.nav.lastSendAt = 0; state.nav.demoLastAt = 0; state.nav.demoOffline = true;
	const offline = await navSend(j);
	const offlineToast = state.nav.toast && [state.nav.toast.text, state.nav.toast.kind];
	const onlineFlag = state.nav.online;
	state.nav.demoOffline = false;
	// a token the server has forgotten clears itself out of the way
	state.nav.token = "0123456789abcdef0123456789abcdef"; state.nav.lastSendAt = 0;
	const stale = await navSend(j);
	const tokenAfterStale = state.nav.token;
	// nothing to send
	const nothing = await navSend(null);
	return { unpaired, first, sentSig, sig: j.signature, toast, kind, tooSoon, serverLimited,
		offline, offlineToast, onlineFlag, stale, tokenAfterStale, nothing };
})()`);
check("sending without a pairing reports 'unpaired' rather than posting",
	sending.unpaired.ok === false && sending.unpaired.error === "unpaired", J(sending.unpaired));
check("a paired send succeeds and records the journey's signature",
	sending.first.ok === true && sending.sentSig === sending.sig, J([sending.first, sending.sentSig === sending.sig]));
check("success raises an ok toast naming the player",
	sending.kind === "ok" && /Sent to Demo/.test(sending.toast), J([sending.kind, sending.toast]));
check("a second send inside 3 s is refused by the CLIENT",
	sending.tooSoon.ok === false && /every 3 seconds/.test(sending.tooSoon.error), J(sending.tooSoon));
check("...and the server's own rate limit surfaces verbatim",
	sending.serverLimited.ok === false && /rate limited/.test(sending.serverLimited.error), J(sending.serverLimited));
check("a player who logged out surfaces as an error toast and flips the online flag",
	sending.offline.ok === false && /offline/.test(sending.offline.error)
	&& sending.offlineToast[1] === "err" && sending.onlineFlag === false, J([sending.offline, sending.onlineFlag]));
check("an unknown token on send drops the pairing on the spot",
	sending.stale.ok === false && sending.tokenAfterStale === "", J([sending.stale, sending.tokenAfterStale]));
check("sending nothing is a plain error, never a request", sending.nothing.ok === false, J(sending.nothing));

/* what actually went on the wire */
const body = await run(`(async () => {
	const j = _navPlan("mh", "ap", { toPartId: "ap:1" })[0];
	await navPair("DEMO23");
	state.nav.lastSendAt = 0; state.nav.demoLastAt = 0;
	const built = journeyPayload(j);
	const wire = JSON.stringify({ token: state.nav.token, journey: built.payload });
	return { bytes: wire.length, keys: Object.keys(built.payload).sort(),
		legKeys: [...new Set(built.payload.legs.map((l) => Object.keys(l).sort().join(",")))].sort() };
})()`);
// Names, positions and stop lists ride along now (they are what stops the HUD saying
// "Unknown stop"), so the body is fatter — and the server's cap rose to 64 KB to match.
check("the body is well inside the 64 KB cap", body.bytes < 16 * 1024, body.bytes + " bytes");
check("the journey object carries exactly destination / plannedArriveMs / legs",
	J(body.keys) === J(["destination", "legs", "plannedArriveMs"]), J(body.keys));
// Every key must be one the servlet knows: the ids/metres it routes on, plus the
// OPTIONAL display fields (names, positions, stop lists) it sanitises and passes through.
const NAV_WIRE_KEYS = new Set([
	"type", "route", "board", "alight", "stops", "via", "from", "to", "meters",
	"routeName", "routeLabel", "routeColor", "headsign",
	"boardName", "boardPos", "alightName", "alightPos", "stopList",
	"fromName", "fromPos", "toName", "toPos",
]);
check("every leg carries only the contract's own keys",
	body.legKeys.every((k) => k.split(",").every((key) => NAV_WIRE_KEYS.has(key))), J(body.legKeys));

/* REGRESSION (reported in game): the HUD printed "Unknown stop" and "? ?" because the
   packet carried ids alone and MTR only syncs data near the player. Every ride must
   therefore ship its own names, colour, positions and stop list. */
const named = run(`(() => {
  const p = journeyPayload(state.plan.journeys[0]).payload;
  const rides = p.legs.filter((l) => l.type === "ride");
  const pos = (v) => Array.isArray(v) && v.length === 3 && v.every((n) => Number.isFinite(n));
  return {
    rides: rides.length,
    named: rides.every((r) => r.boardName && r.alightName && r.routeName),
    positioned: rides.every((r) => pos(r.boardPos) && pos(r.alightPos)),
    stopLists: rides.every((r) => Array.isArray(r.stopList) && r.stopList.length >= 2
      && r.stopList.every((s) => s.name && pos(s.pos))),
    capped: rides.every((r) => (r.stopList || []).length <= 48
      && r.routeName.length <= 48 && r.routeLabel.length <= 8),
  };
})()`);
check("every ride names its board and alight stops and its route", named.rides > 0 && named.named, J(named));
check("...and carries their world positions, so the waypoint works out of sync range",
	named.positioned, J(named));
check("...and a stop list, so the HUD counts down beyond synced data", named.stopLists, J(named));
check("...all within the server's field caps", named.capped, J(named));

/* ---- 8g. the card + modal state machine (no real DOM, just the strings) ---- */
const ui = run(`(() => {
	const j = state.plan.journeys[0];
	navSetToken(DEMO_NAV_TOKEN, "Demo", "harness");
	state.nav.sentSig = "";
	state.nav.sending = false;
	const idle = sendButtonHtml(j) + "|" + sentMarkHtml(j);
	state.nav.sentSig = j.signature;
	const sent = sendButtonHtml(j) + "|" + sentMarkHtml(j);
	state.nav.sending = true;
	const busy = sendButtonHtml(j);
	state.nav.sending = false;
	// the plan moving on takes the marker with it
	state.nav.sentSig = "some-other-journey";
	const stale = sentMarkHtml(j);
	state.nav.sentSig = "";
	const unpairedBtn = (navClearToken(), sendButtonHtml(j));
	return { idle, sent, busy, stale, unpairedBtn, actions: optionActionsHtml(j) };
})()`);
check("idle: the button offers to send and the card wears no marker",
	/Send to game/.test(ui.idle) && ui.idle.endsWith("|"), ui.idle.slice(0, 60));
check("after a send: the button offers a re-send and the card is marked",
	/send again/.test(ui.sent) && /opt-ingame/.test(ui.sent) && /Demo’s game/.test(ui.sent), "");
check("in flight: the button is disabled and says so",
	/disabled/.test(ui.busy) && /Sending…/.test(ui.busy), "");
check("a changed plan returns the card to idle", ui.stale === "", J(ui.stale));
check("unpaired the button is still offered (it opens the pairing card)",
	/Send to game/.test(ui.unpairedBtn) && /Pair this browser/.test(ui.unpairedBtn), "");
check("the actions row holds Send beside Start",
	/opt-actions/.test(ui.actions) && /opt-send/.test(ui.actions), "");

const modal = await run(`(async () => {
	navClearToken();
	const opened = !!openNavModal(null);
	const unpairedCard = navModalHtml(state.nav.modal);
	navModalSetCode("ABC23");
	const partial = navModalHtml(state.nav.modal);
	navModalSetCode("ABC234");
	const full = navModalHtml(state.nav.modal);
	// a wrong code renders INLINE, never as an alert
	state.nav.modal.code = "ZZZ234";
	const bad = await navModalSubmit();
	const errCard = navModalHtml(state.nav.modal);
	// the right one pairs and the card becomes the paired card
	state.nav.modal.code = DEMO_PAIR_CODE;
	const good = await navModalSubmit();
	const pairedCard = navModalHtml(state.nav.modal || { code: "", error: "" });
	state.nav.online = false;
	const offlineCard = navModalHtml(state.nav.modal || { code: "", error: "" });
	state.nav.online = true;
	const closed = closeNavModal();
	// Esc closes it before it touches anything else on the map
	openNavModal(null);
	escapePressed();
	const afterEsc = state.nav.modal;
	navClearToken();
	return { opened, unpairedCard, partial, full, bad, errCard, good, pairedCard, offlineCard,
		closed, afterEsc, modalAfterPair: state.nav.modal };
})()`);
// The pairing command is /navpair, NOT a /dispatch subcommand: brigadier's same-root
// merge keeps the first registration's permission predicate, so grafting it under
// /dispatch would have made pairing op-only (see NavCommand's javadoc).
check("the modal explains the /navpair step and offers a code field",
	/\/navpair\b/.test(modal.unpairedCard) && !/dispatch pair/.test(modal.unpairedCard)
	&& /nm-code/.test(modal.unpairedCard), "");
check("Pair stays disabled until six characters are in",
	/nm-pair[^>]*disabled/.test(modal.partial) && !/nm-pair[^>]*disabled/.test(modal.full), "");
check("a rejected code renders inline in the card",
	modal.bad.ok === false && /nm-error/.test(modal.errCard)
	&& /invalid or expired code/.test(modal.errCard), J(modal.bad));
check("the demo code pairs from the modal", modal.good.ok === true, J(modal.good));
check("the card then reads 'Paired as Demo' and offers Unpair",
	/Paired as Demo/.test(modal.pairedCard) && /nm-unpair/.test(modal.pairedCard), "");
check("an offline player is spelled out rather than hidden",
	/is offline right now/.test(modal.offlineCard), "");
check("closing clears the modal state", modal.closed === null, J(modal.closed));
check("Esc closes the pairing card first, and only that", modal.afterEsc === null, J(modal.afterEsc));

/* pressing Send while unpaired opens the modal and remembers what to send */
const deferred = await run(`(async () => {
	navClearToken();
	state.nav.demoLastAt = 0; state.nav.lastSendAt = 0; state.nav.sentSig = "";
	const j = state.plan.journeys[0];
	navSendPressed(j);
	const pending = state.nav.modal && state.nav.modal.pending === j;
	state.nav.modal.code = DEMO_PAIR_CODE;
	await navModalSubmit();
	return { pending, sentSig: state.nav.sentSig, sig: j.signature, modal: state.nav.modal };
})()`);
check("Send while unpaired opens the pairing card holding the journey",
	deferred.pending === true, deferred.pending);
check("...and once paired it finishes the send the rider actually asked for",
	deferred.sentSig === deferred.sig && deferred.modal === null,
	J([deferred.sentSig === deferred.sig, deferred.modal]));

/* the settings row */
const pairRow = run(`(() => {
	navClearToken();
	const off = pairRowHtml();
	navSetToken(DEMO_NAV_TOKEN, "Demo", "harness");
	state.nav.online = true;
	const on = pairRowHtml();
	state.nav.online = false;
	const away = pairRowHtml();
	navClearToken();
	return { off, on, away };
})()`);
check("Me > Game HUD offers pairing when there is none",
	/Pair with game/.test(pairRow.off), pairRow.off);
check("...and the player's name plus Unpair when there is",
	/Demo/.test(pairRow.on) && /data-act="unpair"/.test(pairRow.on), "");
check("...with a red dot while the player is offline",
	/pair-dot off/.test(pairRow.away) && !/pair-dot off/.test(pairRow.on), "");

}

console.log(fails ? `\n${fails} FAILURE(S)` : "\nall checks passed");
process.exit(fails ? 1 : 0);
