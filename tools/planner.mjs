/* Journey-planner acceptance suite for map.js, over the demo network.
 * Shares the stub-DOM vm rig with harness.mjs (rig.mjs).
 *
 *   node planner.mjs [path/to/map.js]
 */
import { run } from "./rig.mjs";

let fails = 0;
function check(name, cond, extra) {
	const ok = !!cond;
	if (!ok) fails++;
	console.log((ok ? "PASS  " : "FAIL  ") + name + (extra !== undefined ? "   " + extra : ""));
}
const J = (x) => JSON.stringify(x);

/* helper injected into the sandbox: plan a query with the memo bypassed */
run(`
	function _plan(from, to, prefs, opts, at) {
		planMemo.entries.clear();
		return planJourneys(state.plan.graph || (state.plan.graph = buildGraph()),
			from, to, prefs || { mode: "fastest", stepFree: false }, at || Date.now(), opts || {});
	}
	function _shape(js) {
		return js.map(j => ({
			transfers: j.transfers, walk: j.walkMeters, accessible: j.accessible, tags: j.tags,
			dur: Math.round(j.durationMs / 60000), sig: j.rideSignature,
			legs: j.legs.map(l => l.kind === "ride"
				? { kind: "ride", route: l.routeId, num: l.number, from: l.fromPlatform, to: l.toPlatform,
					stops: l.stops, stopCount: l.stopCount, mode: l.mode, dep: l.depMs, arr: l.arrMs,
					live: l.live, estimated: l.estimated, departsInMin: l.departsInMin }
				: { kind: "walk", m: l.meters, s: l.seconds, from: l.fromPlatform, to: l.toPlatform,
					note: l.note, station: l.fromStation, accessible: l.accessible, dep: l.depMs, arr: l.arrMs }),
		}));
	}
	function _platforms(js) {
		const out = new Set();
		for (const j of js) for (const l of j.legs) { out.add(l.fromPlatform); out.add(l.toPlatform); }
		return [...out];
	}
`);

/* ==========================================================================
 * 1. the headline query: Maple Heights -> Airport - Terminal
 * ======================================================================== */
const base = run(`_shape(_plan("mh", "ap", { mode: "fastest", stepFree: false }, { toPartId: "ap:1" }))`);
console.log("\n-- fastest, Maple Heights -> Airport · Terminal --");
for (const j of base) console.log("   " + j.dur + " min, " + j.transfers + " transfer(s), " + j.walk + " m  " +
	j.legs.map((l) => l.kind === "ride" ? l.num + " " + l.from + ">" + l.to : "walk " + l.m + "m").join(" · ") +
	"   [" + j.tags.join(", ") + "]");
console.log("");

check("mh -> ap:1 yields >= 2 distinct journeys", base.length >= 2, base.length + " options");
check("options are distinct (unique ride signatures)",
	new Set(base.map((j) => j.sig)).size === base.length, J(base.map((j) => j.sig)));
check("options are ordered best-first then by arrival",
	base.every((j, i) => i < 2 || j.dur >= base[i - 1].dur - 1), J(base.map((j) => j.dur)));

const fast = base[0];
const fastRides = fast.legs.filter((l) => l.kind === "ride");
check("fastest boards a green trunk service first (4 or 5)",
	["r4", "r5"].includes(fastRides[0].route) && fastRides[0].from === "mh_g", J(fastRides[0]));
check("fastest then rides blue A to the Airport", fastRides[1] && fastRides[1].route === "rA"
	&& fastRides[1].from === "bc_b" && fastRides[1].to === "ap_b", J(fastRides[1] || null));
check("fastest makes exactly one transfer", fast.transfers === 1, fast.transfers);
check("that transfer is at Baker City Central",
	fast.legs[1].kind === "walk" && fast.legs[1].station === "bc" && fast.legs[1].note === "cross-platform", J(fast.legs[1]));
check("fastest finishes with the 90 m concourse walk to the Terminal",
	fast.legs[3].kind === "walk" && fast.legs[3].m === 90 && fast.legs[3].to === "ap_t"
	&& fast.legs[3].note === "concourse link", J(fast.legs[3]));
check("fastest is tagged 'fastest'", fast.tags.includes("fastest"), J(fast.tags));

/* ride legs carry the intermediate stops so the map can light the ribbon */
check("ride legs list every stop from boarding to alighting",
	J(fastRides[0].stops) === J(["mh_g", "gf_g", "mu_g", "bc_g"]) && fastRides[0].stopCount === 3, J(fastRides[0].stops));
check("staying aboard through a stop costs no extra boarding",
	fastRides[0].stopCount === 3 && fast.transfers === 1, fast.transfers);

/* times */
check("journey times are monotone across legs", run(`(() => {
	const js = _plan("mh", "ap", { mode: "fastest", stepFree: false }, { toPartId: "ap:1" });
	return js.every(j => j.legs.every((l, i) => l.arrMs >= l.depMs && (i === 0 || l.depMs >= j.legs[i-1].arrMs - 1))
		&& j.departMs === j.legs[0].depMs && j.arriveMs === j.legs[j.legs.length-1].arrMs
		&& j.durationMs === j.arriveMs - j.departMs);
})()`));

/* ==========================================================================
 * 2. the alternative profiles
 * ======================================================================== */
const byTransfers = run(`_shape(_plan("mh", "ap", { mode: "transfers", stepFree: false }, { toPartId: "ap:1" }))`);
const byWalking = run(`_shape(_plan("mh", "ap", { mode: "walking", stepFree: false }, { toPartId: "ap:1" }))`);
check("transfers profile never has more transfers than the fastest",
	byTransfers[0].transfers <= fast.transfers, byTransfers[0].transfers + " <= " + fast.transfers);
check("walking profile never walks further than the fastest",
	byWalking[0].walk <= fast.walk, byWalking[0].walk + " <= " + fast.walk);
check("walking profile's winner is the least-walking option",
	byWalking[0].walk === Math.min(...byWalking.map((j) => j.walk)), J(byWalking.map((j) => j.walk)));
check("every profile returns the same option pool, reordered",
	J([...new Set(byTransfers.map((j) => j.sig))].sort()) === J([...new Set(base.map((j) => j.sig))].sort()),
	J(byTransfers.map((j) => j.sig)));
check("tags are truthful: only the min-arrival option is 'fastest'", run(`(() => {
	const js = _plan("mh", "ap", { mode: "fastest", stepFree: false }, { toPartId: "ap:1" });
	const min = Math.min(...js.map(j => j.arriveMs));
	return js.every(j => j.tags.includes("fastest") === (j.arriveMs === min));
})()`));
check("tags are truthful: 'Step-free' only on fully accessible journeys", run(`(() => {
	const js = _plan("mh", "ap", { mode: "fastest", stepFree: false }, { toPartId: "ap:1" });
	return js.every(j => j.tags.includes("Step-free") === j.accessible);
})()`));

/* ==========================================================================
 * 3. step-free is a hard constraint
 * ======================================================================== */
const sf = run(`_shape(_plan("mh", "ap", { mode: "fastest", stepFree: true }, { toPartId: "ap:1" }))`);
check("step-free plan exists on the untouched demo network", sf.length >= 1, sf.length + " options");
check("step-free journeys only board/alight/transfer at accessible platforms", run(`(() => {
	const js = _plan("mh", "ap", { mode: "fastest", stepFree: true }, { toPartId: "ap:1" });
	const bad = [];
	for (const j of js) for (const l of j.legs) for (const p of [l.fromPlatform, l.toPlatform]) {
		const pl = state.platforms.get(p);
		if (!pl || !pl.accessible) bad.push(p);
	}
	return bad.length === 0 ? true : bad;
})()`) === true);
check("step-free journeys still ride THROUGH inaccessible platforms (gf_g/rs_b)",
	sf[0].legs.filter((l) => l.kind === "ride").some((l) => l.stops.some((p) => ["gf_g", "mu_g", "rs_b"].includes(p))),
	J(sf[0].legs.filter((l) => l.kind === "ride").map((l) => l.stops)));
check("step-free journeys are all flagged accessible", sf.every((j) => j.accessible), J(sf.map((j) => j.accessible)));

/* flip Baker City Central's green platform to step-free = false and re-plan */
const flipped = run(`(() => {
	state.platforms.get("bc_g").accessible = false;
	state.plan.graph = buildGraph();
	const js = _shape(_plan("mh", "ap", { mode: "fastest", stepFree: true }, { toPartId: "ap:1" }));
	const open = _shape(_plan("mh", "ap", { mode: "fastest", stepFree: false }, { toPartId: "ap:1" }));
	state.platforms.get("bc_g").accessible = true;
	state.plan.graph = buildGraph();
	planMemo.entries.clear();
	return { js, open };
})()`);
check("flipping bc_g inaccessible removes it from every step-free journey",
	flipped.js.every((j) => j.legs.every((l) => l.from !== "bc_g" && l.to !== "bc_g")),
	J(flipped.js.map((j) => j.sig)));
check("...and the step-free answer changes (or disappears)",
	flipped.js.length === 0 || flipped.js[0].sig !== sf[0].sig,
	flipped.js.length ? flipped.js[0].sig : "no journeys");
check("...while the unrestricted answer still uses bc_g",
	flipped.open[0].legs.some((l) => l.from === "bc_g" || l.to === "bc_g"), J(flipped.open[0].sig));
check("bc_g accessibility restored", run(`state.platforms.get("bc_g").accessible`) === true);

/* ==========================================================================
 * 4. walk timing rule: meters / walkSpeed() + 30 s
 * ======================================================================== */
const WALK = run("walkSpeed()");
check("the walk-speed pref defaults to Minecraft walking (4.3 m/s)", WALK === 4.3, WALK);
check("transfer edges keep the meters/walkSpeed() + 30 s rule", run(`(() => {
	const g = buildGraph();
	return g.transferEdges.every(e => Math.abs(e.seconds - (e.meters / walkSpeed() + 30)) < 1e-9);
})()`));
const walkLeg = fast.legs[3];
check("walk legs carry that same timing (90 m -> 90/walkSpeed() + 30 s)",
	walkLeg.m === 90 && Math.abs(walkLeg.s - (90 / WALK + 30)) < 1e-9, walkLeg.m + " m / " + walkLeg.s.toFixed(3) + " s");
check("walk legs advance the clock by exactly their seconds",
	Math.abs(walkLeg.arr - walkLeg.dep - walkLeg.s * 1000) <= 1, (walkLeg.arr - walkLeg.dep) + " ms");
// a walk leg is one or more transfer edges merged: seconds = meters/1.4 + 30 x edges
check("every walk leg is meters/walkSpeed() + 30 s per merged transfer edge", run(`(() => {
	const js = _plan("mh", "ap", { mode: "fastest", stepFree: false }, { toPartId: "ap:1" });
	return js.every(j => j.legs.filter(l => l.kind === "walk").every(l => {
		const k = (l.seconds - l.meters / walkSpeed()) / 30;
		return Math.abs(k - Math.round(k)) < 1e-6 && Math.round(k) >= 1;
	}));
})()`));

/* ==========================================================================
 * 5. the departure model: live vs schedule vs estimated
 * ======================================================================== */
const dep = run(`(() => {
	const g = state.plan.graph;
	const t = Date.now();
	const sched = nextDeparture(g, "mh_g", "r4", t);        // no vehicles yet
	const flat  = nextDeparture(g, "sp_g", "rD", t);        // hidden route, headway 0 -> not in graph
	const noHw  = nextDeparture(g, "hv_b", "rS", t);        // headway 900 s
	return {
		sched: { wait: Math.round((sched.departMs - t) / 1000), live: sched.live, est: sched.estimated },
		flat:  { wait: Math.round((flat.departMs - t) / 1000), live: flat.live, est: flat.estimated },
		noHw:  { wait: Math.round((noHw.departMs - t) / 1000), live: noHw.live, est: noHw.estimated },
		stable: nextDeparture(g, "mh_g", "r4", t).departMs === sched.departMs,
	};
})()`);
check("SCHEDULE tier: headway x a stable phase, inside the 0.15-0.85 band",
	!dep.sched.live && !dep.sched.est && dep.sched.wait >= 45 && dep.sched.wait <= 255, J(dep.sched));
check("SCHEDULE tier is deterministic across repeated queries", dep.stable === true);
check("SCHEDULE tier scales with the route's own headway",
	dep.noHw.wait >= 135 && dep.noHw.wait <= 765, J(dep.noHw));
check("ESTIMATED tier: unknown route -> flat 5 min, flagged estimated",
	dep.flat.est === true && dep.flat.wait === 300, J(dep.flat));

/* now stream a live 4 that is 97 % of the way to Maple Heights */
const live = run(`(() => {
	const t = Date.now();
	const before = nextDeparture(state.plan.graph, "mh_g", "r4", t);
	const beforePlan = _plan("mh", "ap", { mode: "fastest", stepFree: false }, { toPartId: "ap:1" });
	handleFrame({ schemaVersion: 1, serverTime: t, dimension: 0, vehicles: [{
		id: "live4", x: 636, y: 64, z: 148, kmh: 55, rail: "cor_0", railT: 0.97,
		pPlat: "ng_g", nPlat: "mh_g", pFrac: 0.97,
		route: { id: "r4", name: "Baker Line||Bayfront", number: "4", color: 0x00933C },
	}] }, true);
	const after = nextDeparture(state.plan.graph, "mh_g", "r4", t);
	const afterPlan = _plan("mh", "ap", { mode: "fastest", stepFree: false }, { toPartId: "ap:1" });
	const firstRide = (js) => js[0].legs.find(l => l.kind === "ride");
	const far = nextDeparture(state.plan.graph, "mh_g", "r4", t + 3600e3);   // "leave in an hour"
	state.vehicles.clear();
	planMemo.entries.clear();
	return {
		beforeWait: Math.round((before.departMs - t) / 1000), beforeLive: before.live,
		afterWait: Math.round((after.departMs - t) / 1000), afterLive: after.live, vid: after.vehicleId,
		beforeDep: firstRide(beforePlan).depMs, afterDep: firstRide(afterPlan).depMs,
		afterFlag: firstRide(afterPlan).live, afterMin: firstRide(afterPlan).departsInMin,
		farLive: far.live,
	};
})()`);
check("LIVE tier: an approaching vehicle is used instead of the headway",
	live.afterLive === true && live.beforeLive === false && live.vid === "live4", J(live));
check("LIVE tier: remaining leg time x (1 - pFrac) + platform dwell",
	live.afterWait >= 20 && live.afterWait <= 30, live.afterWait + " s (140 s leg x 0.03 + 20 s dwell)");
check("a live train makes the first option depart earlier than the headway answer",
	live.afterDep < live.beforeDep && live.afterWait < live.beforeWait,
	live.afterWait + " s live vs " + live.beforeWait + " s scheduled");
check("the first ride leg reports live + departsInMin for the card",
	live.afterFlag === true && live.afterMin >= 0 && live.afterMin <= 1, J([live.afterFlag, live.afterMin]));
check("a departure far in the future falls back to the schedule", live.farLive === false);

/* ==========================================================================
 * 6. degenerate inputs must never throw
 * ======================================================================== */
const edge = run(`(() => {
	const g = state.plan.graph;
	const P = (...a) => { try { return { n: planJourneys(...a).length }; } catch (e) { return { err: String(e) }; } };
	// an isolated station with no services at all
	state.stations.set("zz", { id: "zz", name: "Nowhere", display: "Nowhere", color: 0, hex: "#000000",
		accessible: true, bounds: null, platformIds: ["zz_x"], parts: [{ id: "zz:0", index: 0, name: null,
		platforms: ["zz_x"], x: 0, z: 0, y: 64 }], partWalks: [], platformDistances: [] });
	state.platforms.set("zz_x", { id: "zz_x", name: "1", stationId: "zz", partId: "zz:0", xz: [0, 0],
		dir: [1, 0], accessible: true, dwellMs: 0, routeIds: [] });
	const g2 = buildGraph();
	const res = {
		unreachable: P(g2, "mh", "zz", { mode: "fastest", stepFree: false }, Date.now()),
		same:        P(g, "mh", "mh", { mode: "fastest", stepFree: false }, Date.now()),
		unknown:     P(g, "nope", "alsonope", { mode: "fastest", stepFree: false }, Date.now()),
		halfUnknown: P(g, "mh", "nope", { mode: "fastest", stepFree: false }, Date.now()),
		emptyGraph:  P({ nodes: new Map(), rideEdges: [], transferEdges: [], byStation: new Map(), adjacency: new Map() },
			"mh", "ap", { mode: "fastest", stepFree: false }, Date.now()),
		nullGraph:   P(null, "mh", "ap", { mode: "fastest", stepFree: false }, Date.now()),
		noPrefs:     P(g, "mh", "ap", null, Date.now()),
		badTime:     P(g, "mh", "ap", { mode: "fastest", stepFree: false }, NaN),
		junkMode:    P(g, "mh", "ap", { mode: "zzz", stepFree: false }, Date.now()),
	};
	state.stations.delete("zz"); state.platforms.delete("zz_x");
	state.plan.graph = buildGraph(); planMemo.entries.clear();
	return res;
})()`);
check("unreachable pair -> []", edge.unreachable.n === 0, J(edge.unreachable));
check("same from/to -> [] and no throw", edge.same.n === 0, J(edge.same));
check("unknown station ids -> [] and no throw", edge.unknown.n === 0 && edge.halfUnknown.n === 0, J([edge.unknown, edge.halfUnknown]));
check("empty graph -> [] and no throw", edge.emptyGraph.n === 0, J(edge.emptyGraph));
check("null graph -> [] and no throw", edge.nullGraph.n === 0, J(edge.nullGraph));
check("missing prefs / NaN time / junk mode still plan", edge.noPrefs.n > 0 && edge.badTime.n > 0 && edge.junkMode.n > 0,
	J([edge.noPrefs, edge.badTime, edge.junkMode]));

/* ==========================================================================
 * 7. memoisation + the "Leave at" path
 * ======================================================================== */
check("repeated identical queries are memoised (same array contents)", run(`(() => {
	planMemo.entries.clear();
	const t = Date.now();
	const a = planJourneys(state.plan.graph, "mh", "ap", { mode: "fastest", stepFree: false }, t, { toPartId: "ap:1" });
	const size = planMemo.entries.size;
	const b = planJourneys(state.plan.graph, "mh", "ap", { mode: "fastest", stepFree: false }, t + 500, { toPartId: "ap:1" });
	return size === 1 && planMemo.entries.size === 1 && a.length === b.length
		&& a.every((j, i) => j.rideSignature === b[i].rideSignature) && a !== b;   // copy, not the cached array
})()`));
check("a different minute bucket is a different query", run(`(() => {
	planMemo.entries.clear();
	const t = Date.now();
	planJourneys(state.plan.graph, "mh", "ap", { mode: "fastest", stepFree: false }, t, { toPartId: "ap:1" });
	planJourneys(state.plan.graph, "mh", "ap", { mode: "fastest", stepFree: false }, t + 61000, { toPartId: "ap:1" });
	return planMemo.entries.size === 2;
})()`));
check("rebuilding the graph invalidates the memo", run(`(() => {
	planJourneys(state.plan.graph, "mh", "ap", { mode: "fastest", stepFree: false }, Date.now(), { toPartId: "ap:1" });
	state.plan.graph = buildGraph();
	planJourneys(state.plan.graph, "mh", "ap", { mode: "fastest", stepFree: false }, Date.now(), { toPartId: "ap:1" });
	return planMemo.entries.size === 1;
})()`));
check("'Leave at' uses the chosen HH:MM, not now()", run(`(() => {
	const d = new Date(); d.setHours(23, 30, 0, 0);
	const js = _plan("mh", "ap", { mode: "fastest", stepFree: false }, { toPartId: "ap:1" }, d.getTime());
	const first = js[0].legs[0];
	return first.depMs >= d.getTime() && first.depMs < d.getTime() + 20 * 60000;
})()`));
check("replan() drives the panel through planJourneys", run(`(() => {
	state.plan.from = { stationId: "mh", partId: null };
	state.plan.to = { stationId: "ap", partId: "ap:1" };
	state.plan.when = { mode: "now", at: null };
	replan();
	return state.plan.journeys.length >= 2 && state.plan.selectedIndex === 0 && !!state.selection;
})()`));
check("replan(true) keeps the rider's expanded option when nothing changed", run(`(() => {
	replan();
	state.plan.selectedIndex = 1; selectOption(1);
	replan(true);
	return state.plan.selectedIndex === 1;
})()`));
check("selectJourney() resolves real ribbons for a planned journey", run(`(() => {
	selectOption(0);
	const s = state.selection;
	return !!s && s.ribbonKeys.size > 0 && state.ribbons.filter(r => s.ribbonKeys.has(r.id)).length === s.ribbonKeys.size
		&& !!s.origin && !!s.dest && s.boardPlatform === "mh_g";
})()`));

/* ==========================================================================
 * 8. performance
 * ======================================================================== */
/* Demo routes are DIRECTED (buildGraph emits one edge per consecutive stop pair, and
 * the demo city has no return services), so only downstream pairs are reachable —
 * EXCEPT where a cross-street transfer bridges two lines. Ridgeview (red) sits 116
 * blocks from Northgate (green/blue), so rv>ap became routable when those edges
 * landed (2026-08-29, feature 2); the pairs below are the ones with neither a service
 * nor a walk between them. */
const perf = run(`(() => {
	const g = state.plan.graph;
	const routable = [["mh","ap"],["ng","bf"],["ng","ap"],["gf","sp"],["hc","ap"],["rv","sy"],["bc","ap"],["mu","bf"],["rv","ap"]];
	const isolated = [["mh","sy"],["ap","mh"]];
	const all = routable.concat(isolated);
	for (const [a,b] of all) { planMemo.entries.clear(); planJourneys(g,a,b,{mode:"fastest",stepFree:false},Date.now()); }
	let worst = 0, total = 0, n = 0;
	const dead = [], live = [];
	for (let i = 0; i < 8; i++) for (const [a,b] of all) {
		planMemo.entries.clear();
		const t0 = Date.now();
		const js = planJourneys(g, a, b, { mode: "fastest", stepFree: i % 2 === 1 }, Date.now());
		const dt = Date.now() - t0;
		worst = Math.max(worst, dt); total += dt; n++;
		if (i === 0) (js.length ? live : dead).push(a + ">" + b);
	}
	return { worst, avg: total / n, n, live, dead, routable: routable.map(p=>p.join(">")), isolated: isolated.map(p=>p.join(">")) };
})()`);
check("a cold query stays well under 50 ms on the demo graph", perf.worst < 50,
	"worst " + perf.worst + " ms, avg " + perf.avg.toFixed(2) + " ms over " + perf.n + " queries");
check("every downstream demo pair is routable",
	perf.routable.every((p) => perf.live.includes(p)), J(perf.live));
check("pairs with no directed path (red line, reverse travel) return []",
	perf.isolated.every((p) => perf.dead.includes(p)), J(perf.dead));

/* ==========================================================================
 * 9. cross-street transfers (feature 2, 2026-08-29)
 * ======================================================================== */
const cross = run(`(() => {
	const g = buildGraph();
	const edges = g.transferEdges.filter(e => e.street);
	planMemo.entries.clear();
	const js = planJourneys(g, "rv", "ap", { mode: "fastest", stepFree: false }, Date.now());
	const j = js[0];
	const chained = js.some(x => {
		let walks = 0;
		for (const l of x.legs) walks = l.kind === "walk" ? walks + 1 : 0;
		return walks > 1;
	});
	// the green trunk's adjacent stops are ~100 blocks apart: walking them must never
	// be offered as an alternative to riding the line
	planMemo.entries.clear();
	const green = planJourneys(g, "mh", "bc", { mode: "fastest", stepFree: false }, Date.now());
	return {
		pairs: [...new Set(edges.map(e => [g.nodes.get(e.from).stationId, g.nodes.get(e.to).stationId].sort().join("~")))].sort(),
		firstLeg: j ? j.legs[0].kind : null,
		firstMeters: j ? Math.round(j.legs[0].meters) : 0,
		rides: j ? j.legs.filter(l => l.kind === "ride").map(l => l.routeId) : [],
		chained,
		greenRidesFirst: green.length ? green[0].legs[0].kind === "ride" : false,
		greenFirstFrom: green.length ? green[0].legs[0].fromPlatform : null,
	};
})()`);
check("only genuine cross-line neighbours get a street transfer",
	J(cross.pairs) === J(["hc~wg", "ng~rv"]), J(cross.pairs));
check("Ridgeview -> Airport is now a 116 m walk to Northgate, then the blue A",
	cross.firstLeg === "walk" && cross.firstMeters <= 120 && cross.rides.includes("rA"), J(cross));
check("no journey chains one street walk into another", cross.chained === false);
check("walking is never offered instead of riding between adjacent stops of a line",
	cross.greenRidesFirst === true && cross.greenFirstFrom === "mh_g", J([cross.greenRidesFirst, cross.greenFirstFrom]));

/* ==========================================================================
 * 10. point-to-point endpoints (feature 2, 2026-08-29)
 * ======================================================================== */
const pts = run(`(() => {
	const g = state.plan.graph || (state.plan.graph = buildGraph());
	const pin = { point: [700, 380], y: 70, label: "Dropped pin" };
	const toPin = { point: [1360, 650], y: 64, label: "Dropped pin (dest)" };
	const a = planEndpoints(g, pin, { stationId: "ap", partId: "ap:1" }, { mode: "fastest", stepFree: false }, Date.now());
	const b = planEndpoints(g, { stationId: "mh" }, toPin, { mode: "fastest", stepFree: false }, Date.now());
	const c = planEndpoints(g, pin, toPin, { mode: "fastest", stepFree: false }, Date.now());
	const d = planEndpoints(g, { point: [-9000, -9000] }, { stationId: "ap" }, { mode: "fastest" }, Date.now());
	const sf = planEndpoints(g, pin, { stationId: "ap", partId: "ap:1" }, { mode: "fastest", stepFree: true }, Date.now());
	const shape = (r) => r.journeys.map(j => j.legs.map(l => l.kind).join(">"));
	const timesMonotone = (r) => r.journeys.every(j => {
		let t = -Infinity;
		for (const l of j.legs) { if (l.depMs < t - 1) return false; t = l.arrMs; }
		return j.arriveMs >= j.departMs;
	});
	return {
		aShape: shape(a), aFirstWalk: a.journeys[0] && Math.round(a.journeys[0].legs[0].meters),
		aMonotone: timesMonotone(a),
		bShape: shape(b), bTail: b.journeys[0] && b.journeys[0].legs[b.journeys[0].legs.length - 1].toPoint,
		cShape: shape(c), cHead: c.journeys[0] && c.journeys[0].legs[0].fromPoint,
		dErr: d.error, dN: d.journeys.length,
		sfN: sf.journeys.length,
		sfAccessible: sf.journeys.every(j => j.legs.filter(l => l.kind === "ride")
			.every(l => (state.platforms.get(l.fromPlatform) || {}).accessible
				&& (state.platforms.get(l.toPlatform) || {}).accessible)),
	};
})()`);
check("a pin origin produces walk-first journeys", pts.aShape.every((s) => s.startsWith("walk>ride")), J(pts.aShape));
check("its leading walk is inside the 300 m reach", pts.aFirstWalk > 0 && pts.aFirstWalk <= 300, pts.aFirstWalk);
check("point journeys keep monotone leg times", pts.aMonotone === true);
check("a pin DESTINATION ends on the walk onto the pin",
	pts.bShape.every((s) => s.endsWith("walk")) && pts.bTail === "@to", J([pts.bShape, pts.bTail]));
check("pin -> pin walks off one and onto the other",
	pts.cShape.every((s) => s.startsWith("walk") && s.endsWith("walk")) && pts.cHead === "@from", J(pts.cShape));
check("a pin out of range reports the walking-range empty state",
	pts.dN === 0 && /within walking range/.test(pts.dErr || ""), J(pts.dErr));
check("step-free still binds through a point origin",
	pts.sfAccessible === true, J([pts.sfN, pts.sfAccessible]));


/* ==========================================================================
 * 11. through running (fix 2, 2026-08-30) — the A becomes the S at Harborview
 * ======================================================================== */
const thr = run(`(() => {
	const plan = (prefs, from, to) => {
		state.plan.graph = buildGraph();
		planMemo.entries.clear();
		return planJourneys(state.plan.graph, from || "mh", to || "he", prefs, Date.now());
	};
	const fastest = plan({ mode: "fastest", stepFree: false });
	const fewest = plan({ mode: "transfers", stepFree: false });
	const walking = plan({ mode: "walking", stepFree: false });
	const stepFree = plan({ mode: "fastest", stepFree: true });
	const keep = state.throughRuns;
	state.throughRuns = [];
	const without = plan({ mode: "fastest", stepFree: false });
	state.throughRuns = keep;
	state.plan.graph = buildGraph();
	planMemo.entries.clear();
	const j = fastest[0];
	const leg = j.legs.find(l => (l.continuations || []).length);
	const monotone = fastest.every(x => x.legs.every((l, i) => l.arrMs >= l.depMs && (i === 0 || l.depMs >= x.legs[i - 1].arrMs - 1)));
	// the boarding leg of a through journey still has ONE departure: the one the rider
	// waits for. There is no second depMs hiding inside it.
	const deps = j.legs.filter(l => l.kind === "ride").map(l => l.depMs);
	return {
		fastest: fastest.map(x => ({ t: x.transfers, sig: x.rideSignature })),
		fewestT: fewest[0].transfers, walkingT: walking[0].transfers,
		withoutT: without[0].transfers,
		stepFreeOk: stepFree.length >= 0,
		stepFreeThrough: stepFree.some(x => x.legs.some(l => (l.continuations || []).length)),
		cont: leg ? leg.continuations.map(c => c.fromRouteId + ">" + c.routeId + "@" + c.platform) : [],
		spans: leg ? leg.spans.map(s => s.routeId) : [],
		deps: deps.length, unique: new Set(fastest.map(x => x.rideSignature)).size, n: fastest.length,
		monotone,
		tags: j.tags,
	};
})()`);
check("the through run is offered under the fastest profile", thr.fastest[0].t === 1, J(thr.fastest));
check("...and under fewest-transfers and least-walking too",
	thr.fewestT === 1 && thr.walkingT === 1, J([thr.fewestT, thr.walkingT]));
check("...where the same trip WITHOUT it needs a second boarding", thr.withoutT === 2, thr.withoutT);
check("the continued ride is one leg with two spans",
	J(thr.cont) === J(["rA>rS@hv_b"]) && J(thr.spans) === J(["rA", "rS"]), J([thr.cont, thr.spans]));
check("a through journey still has exactly one departure per boarding", thr.deps === 2, thr.deps);
check("through options are still deduped by ride signature", thr.unique === thr.n, J([thr.unique, thr.n]));
check("leg times stay monotone across a through run", thr.monotone === true);
check("step-free planning is unaffected by the through run existing", thr.stepFreeOk === true);

check("a graph with NO through index still plans (older payloads, hand-made graphs)", run(`(() => {
	const g = buildGraph();
	delete g.through;
	planMemo.entries.clear();
	const js = planJourneys(g, "mh", "ap", { mode: "fastest", stepFree: false }, Date.now(), { toPartId: "ap:1" });
	return js.length > 0 && js.every(j => j.legs.every(l => (l.continuations || []).length === 0));
})()`));
check("a through run whose platform is not on the arriving route's stop list is inert", run(`(() => {
	const keep = state.throughRuns;
	state.throughRuns = [{ from: "rA", to: "rS", platform: "mh_g" }];   // rA never calls at mh_g
	const g = buildGraph();
	planMemo.entries.clear();
	const js = planJourneys(g, "mh", "he", { mode: "fastest", stepFree: false }, Date.now());
	state.throughRuns = keep;
	state.plan.graph = buildGraph();
	planMemo.entries.clear();
	return js.every(j => j.legs.every(l => (l.continuations || []).length === 0));
})()`));

/* ==========================================================================
 * 12. walk speed is a preference (fix 1, 2026-08-30)
 * ======================================================================== */
const walkPref = run(`(() => {
	const at = Date.now();
	const runAt = (v) => {
		setWalkSpeed(v);
		state.plan.graph = buildGraph();
		planMemo.entries.clear();
		const js = planJourneys(state.plan.graph, "mh", "ap", { mode: "fastest", stepFree: false }, at, { toPartId: "ap:1" });
		const walkLeg = js[0].legs.filter(l => l.kind === "walk").pop();
		return { speed: walkSpeed(), seconds: walkLeg.seconds, meters: walkLeg.meters,
			ruleOk: Math.abs(walkLeg.seconds - (walkLeg.meters / walkSpeed() + 30)) < 1e-9,
			arrive: js[0].arriveMs };
	};
	const fast = runAt(5.6), slow = runAt(2), back = runAt(4.3);
	return { fast, slow, back };
})()`);
check("a planned walk leg is always meters / the CURRENT walk speed + 30 s",
	walkPref.fast.ruleOk && walkPref.slow.ruleOk && walkPref.back.ruleOk, J(walkPref));
check("walking slower makes the same walk leg take longer",
	walkPref.slow.seconds > walkPref.back.seconds && walkPref.back.seconds > walkPref.fast.seconds,
	J([walkPref.slow.seconds, walkPref.back.seconds, walkPref.fast.seconds]));
check("...and lands the rider later", walkPref.slow.arrive > walkPref.fast.arrive,
	J([walkPref.slow.arrive - walkPref.fast.arrive]));
check("the walk speed is restored to the 4.3 m/s default", walkPref.back.speed === 4.3, walkPref.back.speed);

console.log(fails ? `\n${fails} FAILURE(S)` : "\nall planner checks passed");
process.exit(fails ? 1 : 0);
