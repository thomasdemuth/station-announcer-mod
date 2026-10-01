"use strict";
/*
 * Interlining — the dispatch screen's panel for shared sections (INTERLINE_PLAN.md).
 *
 * Loaded by index.html after app.js and wrapped in an IIFE: it reads app.js's globals
 * (state, canvas, worldToScreen, invalidateStatic, API, DEMO, $) and exposes only
 *   window.ilDrawOverlay(ctx)  — called by app.js's frame loop: dims the network and draws
 *                                the selected section's real track (Map+ legs), stations and
 *                                suggested platform holds on the live map;
 *   window.ilFocusRoutes       — the section's route ids; app.js dims every other train.
 *
 * Data: GET api/interline (analysis, built on the simulator thread), GET api/interlinesuggest
 * (the in-game solver), GET api/mapdata (rails between stops, for the overlay),
 * POST api/pair + GET api/interlineauth + POST api/interlineapply (Apply from the web: a
 * browser paired with /navpair whose player has the edit permission level).
 *
 * The timetable sheet and the strips replay MTR 4.0.1's departure writer in JS
 * (Timetable.java does the same in Java): per nominal hour with slider f > 0, interval
 * 14 400 000 / f, departures from max(hourStart, last + interval), each n × day / 86 400 000.
 */
(() => {
	const PREFS_KEY = "sa_mapplus_prefs"; // shared with Map+: one pairing serves both
	const HOLD = "#f5b942";
	const il = {
		open: false,
		dim: -1,
		data: null,
		error: "",
		tab: "sections",
		section: null,
		depot: null,
		filter: "",
		form: null,
		suggestion: null,
		candidate: 0,
		busy: false,
		mapLegs: null,     // routeId -> {platforms:[], legs:[[railId…]…]}
		sheetShow: "both",
		sheetZoom: 1,
		sheetTraces: [],
		applyMsg: "",
		applyErr: "",
		auth: null,        // {player} once the token is known good
		authErr: "",
		delayInput: {},
		token: "",
	};

	/* ------------------------------------------------------------ utils */

	function esc(s) {
		return String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
	}
	function hex(color) { return "#" + ((Number(color) >>> 0) & 0xffffff).toString(16).padStart(6, "0"); }
	function inkOn(color) {
		const c = (Number(color) >>> 0) & 0xffffff;
		return (0.299 * (c >> 16) + 0.587 * ((c >> 8) & 255) + 0.114 * (c & 255)) > 150 ? "#111" : "#fff";
	}
	function dur(ms) {
		if (!ms || ms <= 0) return "0s";
		const s = ms / 1000;
		if (s < 60) return (s < 10 || Math.abs(s - Math.round(s)) > 0.05 ? s.toFixed(1) : Math.round(s)) + "s";
		const w = Math.round(s);
		return Math.floor(w / 60) + "m " + (w % 60) + "s";
	}
	function parseDuration(text) {
		let s = String(text || "").trim().toLowerCase().replace(/\s+/g, "");
		if (!s) return -1;
		if (s.includes(":")) {
			const [m, sec] = s.split(":");
			const v = (parseFloat(m) * 60 + parseFloat(sec)) * 1000;
			return isFinite(v) && v >= 0 ? Math.round(v) : -1;
		}
		let total = 0;
		const mi = s.indexOf("m");
		if (mi >= 0) { total += parseFloat(s.slice(0, mi)) * 60; s = s.slice(mi + 1); }
		if (s.endsWith("s")) s = s.slice(0, -1);
		if (s) total += parseFloat(s);
		return isFinite(total) && total >= 0 ? Math.round(total * 1000) : -1;
	}
	function evenClass(st) {
		if (!st || st.count < 2) return "il-none";
		return st.evenness < 0.1 ? "il-even" : st.evenness < 0.3 ? "il-uneven" : "il-bunched";
	}
	function evenLabel(e) { return (e < 0.1 ? "even" : e < 0.3 ? "uneven" : "bunched") + " ±" + Math.round(e * 100) + "%"; }
	function byId(list) { const m = new Map(); for (const x of list || []) m.set(x.id, x); return m; }
	function chip(r) {
		if (!r) return "";
		const label = r.number && r.number.trim() ? r.number : r.name;
		return `<span class="il-chip" style="background:${hex(r.color)};color:${inkOn(r.color)}" title="${esc(r.name)}">${esc(label)}</span>`;
	}
	const route = (id) => il.routes && il.routes.get(id);
	const depot = (id) => il.depots && il.depots.get(id);
	const section = (id) => il.sections && il.sections.get(id);
	const day = () => (il.data && il.data.gameMillisPerDay) || 0;

	/* ------------------------------------------------------- timetable replay */

	function departures(eff, dayMs) {
		const out = [];
		let last = -Infinity;
		for (let h = 0; h < 24; h++) {
			const f = eff[h];
			if (!(f > 0)) continue;
			const interval = Math.floor(14400000 / f);
			const min = 3600000 * h, max = 3600000 * (h + 1);
			for (;;) {
				const next = Math.max(min, last + interval);
				if (next >= max) break;
				out.push(Math.floor(next * dayMs / 86400000));
				last = next;
			}
		}
		return out;
	}

	/** Every scheduled run of the depots feeding a section: [{feed, depot, base}]; base + offset = time. */
	function runs(sec, ov) {
		const dayMs = day();
		const out = [];
		for (const feed of sec.feeds) {
			const d = depot(feed.depot);
			if (!d || !d.tunable || feed.travelMs < 0) continue;
			const f = ov && ov.freq && ov.freq[feed.depot];
			const eff = f ? new Array(24).fill(f) : d.effFreq;
			const delay = ov && ov.delays && feed.depot in ov.delays ? ov.delays[feed.depot] : d.delayMs;
			let pad = 0;
			for (const p of (ov && ov.pads) || []) {
				const pos = d.routes.indexOf(p.route);
				if (pos < 0 || (p.stopIndex === 0 && (d.mergedStarts || []).includes(p.route))) continue;
				if (pos < feed.routePos || (pos === feed.routePos && p.stopIndex < feed.entryIndex)) pad += p.extraMs;
			}
			const shift = (delay % dayMs) + pad;
			for (const dep of departures(eff, dayMs)) out.push({ feed, depot: d, base: dep + shift });
		}
		return out;
	}

	function arrivals(sec, ov) {
		const dayMs = day();
		return runs(sec, ov).map((r) => ({ t: ((r.base + r.feed.travelMs) % dayMs + dayMs) % dayMs, route: r.feed.route }))
			.sort((a, b) => a.t - b.t);
	}

	function statsOf(times) {
		const dayMs = day();
		const n = times.length;
		if (n < 2) return null;
		const gaps = [];
		for (let i = 0; i < n - 1; i++) gaps.push(times[i + 1] - times[i]);
		gaps.push(times[0] + dayMs - times[n - 1]);
		const cut = Math.max(1, dayMs / n) * 4; // service breaks vs the day's AVERAGE gap (see Timetable.stats)
		const kept = gaps.filter((g) => g <= cut);
		const mean = kept.reduce((a, b) => a + b, 0) / kept.length;
		const rms = Math.sqrt(kept.reduce((a, g) => a + (g - mean) ** 2, 0) / kept.length);
		return { count: n, meanMs: mean, minMs: Math.min(...kept), maxMs: Math.max(...kept), evenness: mean > 0 ? rms / mean : 0 };
	}

	function overridesFrom(sug) {
		if (!sug || !sug.ok || sug.unchanged) return null;
		const cand = sug.direction === "options" && il.candidate > 0 ? sug.candidates[il.candidate] : null;
		return {
			delays: (cand || sug).delays || {},
			freq: sug.frequencies || {},
			pads: cand ? [] : (sug.pads || []).map((p) => ({ route: p.route, stopIndex: p.stopIndex, extraMs: p.extraMs })),
		};
	}

	/** The suggestion's overrides when it belongs to this section or its opposite direction. */
	function overridesFor(sec) {
		const sug = il.suggestion;
		if (!sug || !sec || (sug.section !== sec.id && sug.reverse !== sec.id)) return null;
		return overridesFrom(sug);
	}

	/* ------------------------------------------------------------- data */

	async function load() {
		il.dim = state.dim;
		if (DEMO) { setData(DEMO_DATA.analysis); return; }
		try {
			const body = await (await fetch(`${API}/interline?dimension=${state.dim}`)).json();
			const data = body.data || body;
			if (!data.ok) { il.error = data.error || "Analysis failed"; il.data = null; render(); return; }
			setData(data);
		} catch (e) {
			il.error = "Could not load the analysis: " + e;
			render();
		}
		loadLegs();
	}

	async function loadLegs() {
		if (DEMO) return;
		try {
			const body = await (await fetch(`${API}/mapdata?dimension=${state.dim}`)).json();
			const data = body.data || body;
			const legs = new Map();
			for (const r of data.routes || []) {
				legs.set(String(r.id), { platforms: (r.platforms || []).map(String), legs: (r.legs || []).map((l) => (l && l.rails) || []) });
			}
			il.mapLegs = legs;
		} catch (e) { /* the overlay falls back to straight lines */ }
	}

	function setData(data) {
		const firstLoad = !il.data;
		il.data = data;
		il.error = "";
		il.depots = byId(data.depots);
		il.routes = byId(data.routes);
		il.sections = byId(data.sections);
		if (!il.section || !il.sections.has(il.section)) il.section = data.sections[0] ? data.sections[0].id : null;
		if (!il.depot || !il.depots.has(il.depot)) il.depot = data.depots[0] ? data.depots[0].id : null;
		if (il.suggestion && !il.sections.has(il.suggestion.section)) il.suggestion = null;
		$("ilBuilt").textContent = "built " + new Date(data.builtAt).toLocaleTimeString();
		updateFocus();
		render();
		if (firstLoad) fitSection();
	}

	async function suggest(extra) {
		const sec = section(il.section);
		const form = il.form;
		if (!sec || !form) return;
		const params = new URLSearchParams({
			dimension: state.dim,
			section: sec.id,
			mode: form.mode,
			targetMs: form.mode === "target" ? Math.max(0, parseDuration(form.target)) : 0,
			direction: form.direction,
			levers: form.levers,
			depots: [...form.depots].join(","),
			weights: [...form.depots].map((id) => id + ":" + (form.weights[id] || 1)).join(","),
			...(extra || {}),
		});
		il.busy = true;
		render();
		try {
			let data;
			if (DEMO) {
				data = DEMO_DATA.suggestions[form.mode === "target" ? "target" : form.levers === "holds" ? "holds" : form.direction]
					|| DEMO_DATA.suggestions.balance;
				await new Promise((r) => setTimeout(r, 150));
			} else {
				const body = await (await fetch(`${API}/interlinesuggest?${params}`)).json();
				data = body.data || body;
			}
			il.suggestion = data;
			il.candidate = 0;
			il.applyMsg = "";
			il.applyErr = "";
		} catch (e) {
			il.suggestion = { ok: false, section: sec.id, error: "Request failed: " + e };
		}
		il.busy = false;
		render();
	}

	/* -------------------------------------------------------- pairing + apply */

	function readToken() {
		try { const p = JSON.parse(localStorage.getItem(PREFS_KEY)) || {}; return /^[0-9a-f]{32}$/.test(p.navToken || "") ? p.navToken : ""; }
		catch (e) { return ""; }
	}
	function writeToken(token, player) {
		try {
			const p = JSON.parse(localStorage.getItem(PREFS_KEY)) || {};
			p.navToken = token;
			if (player) p.navPlayer = player;
			localStorage.setItem(PREFS_KEY, JSON.stringify(p));
		} catch (e) { /* private mode: pairing lasts for this page only */ }
		il.token = token;
	}
	async function http(path, init) {
		if (DEMO) return { ok: false, error: "Demo mode — nothing to apply to." };
		try {
			const body = await (await fetch(`${API}/${path}`, init)).json();
			return body && body.data ? body.data : body;
		} catch (e) { return { ok: false, error: "Could not reach the dispatch server." }; }
	}
	const postJson = (obj) => ({ method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(obj) });

	async function checkAuth() {
		il.token = il.token || readToken();
		if (!il.token) { il.auth = null; il.authErr = ""; render(); return; }
		const res = await http("interlineauth?token=" + encodeURIComponent(il.token));
		il.auth = res && res.ok ? { player: res.player } : null;
		il.authErr = res && res.ok ? "" : String((res && res.error) || "Not allowed");
		render();
	}

	async function pair(code) {
		const clean = String(code || "").toUpperCase().replace(/[^A-Z0-9]/g, "");
		if (clean.length !== 6) { il.authErr = "Enter the six-character code /navpair showed you."; render(); return; }
		const res = await http("pair", postJson({ code: clean, label: "Dispatch — Interlining" }));
		if (res && res.ok && res.token) {
			writeToken(res.token, res.player);
			await checkAuth();
		} else {
			il.authErr = String((res && res.error) || "Pairing failed.");
			render();
		}
	}

	async function apply(changes) {
		if (!il.auth) return;
		il.applyMsg = "Applying…";
		il.applyErr = "";
		render();
		const res = await http("interlineapply", postJson({ token: il.token, dimension: state.dim, ...changes }));
		if (res && res.ok) {
			il.applyMsg = res.message || "Applied.";
			il.suggestion = null;
			setTimeout(load, 3500);
			setTimeout(load, 12000); // a dwell change regenerates the depot first
		} else {
			il.applyMsg = "";
			il.applyErr = String((res && res.error) || "Apply failed.");
		}
		render();
	}

	function suggestionChanges(sug) {
		const cand = sug.direction === "options" && il.candidate > 0 ? sug.candidates[il.candidate] : null;
		return {
			delays: { ...((cand || sug).delays || {}) },
			frequencies: { ...(sug.frequencies || {}) },
			dwell: cand ? [] : (sug.pads || []).map((p) => ({ platform: p.platform, route: p.route, dwellMs: p.dwellMs })),
		};
	}

	/* ------------------------------------------------------------- panel */

	function setOpen(on) {
		il.open = on;
		$("ilPanel").classList.toggle("hidden", !on);
		$("ilSheet").classList.toggle("hidden", !on);
		$("interlineBtn").classList.toggle("active", on);
		document.body.classList.toggle("il-open", on);
		if (on) {
			if (!il.data || il.dim !== state.dim) load(); else { render(); fitSection(); }
			checkAuth();
		}
		updateFocus();
		invalidateStatic();
	}

	function updateFocus() {
		const sec = il.open && section(il.section);
		if (!sec) { window.ilFocusRoutes = null; return; }
		const rev = section(sec.reverse);
		window.ilFocusRoutes = new Set([...sec.routes, ...(rev ? rev.routes : [])]);
	}

	function selectSection(id) {
		il.section = id;
		updateFocus();
		render();
		fitSection();
		$("ilDetail").scrollTop = 0;
	}

	function render() {
		if (!il.open) return;
		$("ilTabSections").classList.toggle("active", il.tab === "sections");
		$("ilTabDepots").classList.toggle("active", il.tab === "depots");
		const pick = $("ilPick"), detail = $("ilDetail");
		if (!il.data) {
			pick.innerHTML = "";
			detail.innerHTML = `<div class="${il.error ? "il-err" : "il-empty"}">${esc(il.error || "Loading…")}</div>`;
			drawSheet();
			return;
		}
		if (il.tab === "sections") {
			renderPick(pick);
			renderSection(detail);
		} else {
			renderDepotPick(pick);
			renderDepot(detail);
		}
		drawSheet();
	}

	function renderPick(pick) {
		const q = il.filter.toLowerCase();
		const rows = il.data.sections.filter((s) => !q || s.name.toLowerCase().includes(q)
			|| s.stations.some((n) => n.toLowerCase().includes(q))
			|| s.routes.some((id) => (route(id)?.name || "").toLowerCase().includes(q)));
		let html = `<input class="il-search" id="ilFilter" placeholder="Filter sections by station or line" value="${esc(il.filter)}"><div class="il-rows">`;
		if (!il.data.sections.length) html += `<div class="il-empty">No two lines share consecutive platforms in the same order.</div>`;
		for (const s of rows) {
			html += `<div class="il-row${s.id === il.section ? " selected" : ""}" data-id="${esc(s.id)}">
				<span class="il-dot ${evenClass(s.scheduled)}" title="${s.scheduled.count >= 2 ? evenLabel(s.scheduled.evenness) : "no trains"}"></span>
				<span class="il-name">${esc(s.name)}</span>${s.routes.map((id) => chip(route(id))).join("")}</div>`;
		}
		pick.innerHTML = html + `</div>`;
		const filter = $("ilFilter");
		filter.oninput = () => {
			il.filter = filter.value;
			const pos = filter.selectionStart;
			renderPick(pick);
			const f = $("ilFilter");
			f.focus();
			f.setSelectionRange(pos, pos);
		};
		pick.querySelectorAll(".il-row").forEach((row) => row.onclick = () => selectSection(row.dataset.id));
		const selected = pick.querySelector(".il-row.selected");
		if (selected && selected.scrollIntoView) selected.scrollIntoView({ block: "nearest" });
	}

	function feedingDepots(sec, rev) {
		const ids = [];
		for (const s of [sec, rev]) {
			if (!s) continue;
			for (const f of s.feeds) if (!ids.includes(f.depot)) ids.push(f.depot);
		}
		return ids;
	}

	function ensureForm(sec) {
		if (il.form && (il.form.section === sec.id || il.form.section === sec.reverse)) {
			il.form.section = sec.id;
			return;
		}
		const rev = section(sec.reverse);
		const depots = new Set(feedingDepots(sec, rev).filter((id) => depot(id)?.tunable));
		il.form = { section: sec.id, mode: "even", target: "", direction: rev ? "balance" : "forward", levers: "both", depots, weights: {}, group: "" };
	}

	function seg(name, options, value) {
		return `<span class="il-seg" data-seg="${name}">${options.map(([v, label, tip]) =>
			`<button data-v="${v}" class="${v === value ? "on" : ""}"${tip ? ` title="${esc(tip)}"` : ""}>${label}</button>`).join("")}</span>`;
	}

	function renderSection(detail) {
		const sec = section(il.section);
		if (!sec) { detail.innerHTML = `<div class="il-empty">Pick a section above.</div>`; return; }
		ensureForm(sec);
		const rev = section(sec.reverse);
		const form = il.form;
		const sug = il.suggestion && il.suggestion.section === sec.id ? il.suggestion : null;
		const ov = overridesFor(sec);
		let h = `<div class="il-title-row"><h2 class="il-h">${esc(sec.name)}</h2>${rev ? `<button class="btn small" data-go="${esc(rev.id)}" title="The same stations the other way">⇄ Other way</button>` : ""}</div>
			<div class="il-schematic">${schematic(sec)}</div>`;

		// Arrivals, now vs suggested — the quick read; the sheet under the map has the detail.
		h += `<h3 class="il-h">TRAINS REACHING ${esc((sec.stations[0] || "THE SECTION").toUpperCase())}</h3><div class="il-strip">${strips(sec, rev, ov)}</div>`;

		// Feeds.
		h += `<h3 class="il-h">DEPOTS</h3><table class="il-table"><tr><th>Depot</th><th>Line</th><th>Every</th><th>To entry</th><th>Delay</th></tr>`;
		for (const feed of sec.feeds) {
			const d = depot(feed.depot);
			if (!d) continue;
			const timing = !d.tunable ? `<span class="il-dim">${esc(d.reason)}</span>`
				: feed.travelMs < 0 ? `<span class="il-dim" title="Not generated yet, or its trains skip this stop">no stop here</span>`
				: dur(feed.travelMs);
			h += `<tr><td>${esc(d.name)}</td><td>${chip(route(feed.route))}</td><td class="num">${d.tunable ? dur(d.intervalMs) : "—"}</td><td class="num">${timing}</td><td class="num">+${dur(d.delayMs)}</td></tr>`;
		}
		h += `</table>`;
		const m = sec.measured;
		h += `<div class="il-dim" style="margin-top:4px">Measured by analytics: ${m.samples > 0
			? `every ${dur(m.avgMs)} (${dur(m.minMs)}–${dur(m.maxMs)}, ${m.samples} gap${m.samples === 1 ? "" : "s"})` : "no departures recorded yet"}</div>`;

		// Form.
		const feeding = feedingDepots(sec, rev);
		h += `<h3 class="il-h">SUGGEST</h3><div class="il-form">
			<div class="il-line">${seg("mode", [["even", "Even spacing"], ["target", "Target headway"]], form.mode)}
			${form.mode === "target" ? `<input type="text" id="ilTarget" placeholder="e.g. 2:00" value="${esc(form.target)}" title="Headway on the shared stretch">` : ""}</div>
			<div class="il-line">${seg("direction", rev ? [["balance", "Balance both"], ["forward", "This way"], ["reverse", "Other way"], ["options", "Options"]]
				: [["forward", "This way"], ["options", "Options"]], form.direction)}</div>
			<div class="il-line"><span class="il-dim">Adjust</span>${seg("levers", [
				["delays", "Depot delays", "Slide a depot's whole timetable (moves every line and direction it runs)"],
				["holds", "Platform holds", "Longer dwell at one platform — before the section or where the line turns back — moves one line, one direction"],
				["both", "Both"]], form.levers)}</div>
			<div class="il-line"><span class="il-dim">Depots</span>${il.data.groups.filter((g) => feeding.some((id) => g.depots.includes(id))).map((g) =>
				`<button class="btn small${form.group === g.id ? " active" : ""}" data-group="${esc(g.id)}">${esc(g.name)}</button>`).join("")}
			${feeding.map((id) => {
				const d = depot(id);
				if (!d) return "";
				return `<label${d.tunable ? "" : ` class="il-dim" title="${esc(d.reason)}"`}><input type="checkbox" data-depot="${esc(id)}" ${form.depots.has(id) ? "checked" : ""} ${d.tunable ? "" : "disabled"}> ${esc(d.name)}</label>`
					+ (form.mode === "target" && d.tunable ? `<input type="number" class="il-weight" min="1" max="9" data-weight="${esc(id)}" value="${form.weights[id] || 1}" title="Share of the trains">` : "");
			}).join("")}</div>
			<div class="il-line"><button class="btn primary" id="ilSuggest" ${il.busy ? "disabled" : ""}>${il.busy ? "Working…" : "Suggest"}</button></div>
		</div>`;

		if (sug) h += renderSuggestion(sec, rev, sug);
		else if (il.applyMsg) h += `<div class="il-ok">${esc(il.applyMsg)}</div>`;
		detail.innerHTML = h;
		wireSection(detail, sec);
	}

	function renderSuggestion(sec, rev, sug) {
		if (!sug.ok) return `<div class="il-err">${esc(sug.error)}</div>`;
		let h = `<h3 class="il-h">SUGGESTION</h3>`;
		for (const w of sug.warnings || []) h += `<div class="il-warn">⚠ ${esc(w)}</div>`;
		if ((sug.matchOptions || []).length) {
			h += `<div class="il-line il-match">${sug.matchOptions.map((mo) =>
				`<button class="btn small" data-match="${mo.headwayMs}" title="Every depot at slider ${mo.frequency} (each every ${dur(mo.depotHeadwayMs)})">All at ${mo.frequency} → every ${dur(mo.headwayMs)}</button>`).join("")}</div>`;
		}
		if (sug.target && sug.target.achievedMs) {
			h += `<div>Target ${dur(sug.target.targetMs)} → achievable <b>${dur(sug.target.achievedMs)}</b></div>`;
		}
		const pick = sug.direction === "options" && il.candidate > 0 ? sug.candidates[il.candidate] : null;
		if (sug.direction === "options" && sug.candidates.length > 1) {
			h += `<table class="il-table il-options"><tr><th>Option</th><th>This way</th><th>Other way</th></tr>`;
			sug.candidates.forEach((c, i) => {
				h += `<tr data-cand="${i}" class="${i === il.candidate ? "on" : ""}"><td>${i + 1}${i === 0 && sug.pads.length ? " <span class='il-dim'>+ holds</span>" : ""}</td>
					<td>${evenLabel(c.stats.fwd.evenness)}</td><td>${c.stats.rev ? evenLabel(c.stats.rev.evenness) : "—"}</td></tr>`;
			});
			h += `</table>`;
		}
		if (!sug.unchanged) {
			h += `<div class="il-changes">`;
			const delays = (pick || sug).delays || {};
			for (const id of sug.adjustable) {
				const d = depot(id);
				if (!d) continue;
				const nd = delays[id] ?? d.delayMs;
				const nf = sug.frequencies && id in sug.frequencies ? sug.frequencies[id] : null;
				if (nd === d.delayMs && nf == null) continue;
				h += `<div class="il-change"><span class="il-tag">DEPOT</span>${esc(d.name)}: ${nd !== d.delayMs ? `delay +${dur(d.delayMs)} → <b>+${dur(nd)}</b>` : ""}
					${nf != null ? ` frequency ${d.uniformFreq >= 0 ? d.uniformFreq : "varies"} → <b>${nf}</b> (every ${dur(sug.headways[id])}, all day)` : ""}</div>`;
			}
			if (!pick) {
				for (const p of sug.pads || []) {
					const r = route(p.route);
					const where = p.kind === "turnaround" ? `where ${esc(r ? r.name : "the line")} turns back`
						: p.kind === "origin" ? `start of ${esc(r ? r.name : "the line")}` : `${esc(r ? r.name : "")}, stop before`;
					h += `<div class="il-change"><span class="il-tag il-tag-hold">HOLD</span>${esc(p.station)} (${where}): ${dur(p.currentDwellMs)} → <b>${dur(p.dwellMs)}</b></div>`;
				}
			}
			h += `</div>`;
			h += applyBlock(() => suggestionChanges(sug), "Apply suggestion");
		}
		return h;
	}

	/** Apply button + pairing: every web write goes through a /navpair-paired browser. */
	function applyBlock(changesFn, label) {
		il.pendingChanges = changesFn;
		if (il.data && il.data.webApply === false) {
			return `<div class="il-dim il-apply">Applying from the web is off on this server — apply in game (Dashboard → Tools… → Interlining).</div>`;
		}
		let h = `<div class="il-apply">`;
		if (il.auth) {
			h += `<button class="btn primary" id="ilApply">${label}</button> <span class="il-dim">as ${esc(il.auth.player)}</span>`;
		} else {
			h += `<div class="il-dim">To apply from here, pair this browser once: run <code>/navpair</code> in game, then enter the code.</div>
				<div class="il-line"><input type="text" id="ilPairCode" maxlength="7" placeholder="ABC123" autocomplete="off"><button class="btn small" id="ilPair">Pair</button></div>`;
		}
		if (il.authErr) h += `<div class="il-err">${esc(il.authErr)}</div>`;
		if (il.applyErr) h += `<div class="il-err">${esc(il.applyErr)}</div>`;
		if (il.applyMsg) h += `<div class="il-ok">${esc(il.applyMsg)}</div>`;
		return h + `</div>`;
	}

	function wireApply(root) {
		const applyButton = root.querySelector("#ilApply");
		if (applyButton) applyButton.onclick = () => apply(il.pendingChanges());
		const pairBtn = root.querySelector("#ilPair");
		if (pairBtn) pairBtn.onclick = () => pair(root.querySelector("#ilPairCode").value);
		const code = root.querySelector("#ilPairCode");
		if (code) code.onkeydown = (e) => { if (e.key === "Enter") pair(code.value); };
	}

	function wireSection(detail, sec) {
		const form = il.form;
		detail.querySelectorAll("[data-go]").forEach((b) => b.onclick = () => selectSection(b.dataset.go));
		detail.querySelectorAll("[data-seg]").forEach((segEl) => {
			segEl.querySelectorAll("button").forEach((b) => b.onclick = () => { form[segEl.dataset.seg] = b.dataset.v; render(); });
		});
		const target = detail.querySelector("#ilTarget");
		if (target) target.oninput = () => { form.target = target.value; };
		detail.querySelectorAll("[data-depot]").forEach((box) => box.onchange = () => {
			if (box.checked) form.depots.add(box.dataset.depot); else form.depots.delete(box.dataset.depot);
			form.group = "";
		});
		detail.querySelectorAll("[data-weight]").forEach((input) => input.oninput = () => {
			form.weights[input.dataset.weight] = Math.max(1, Math.min(9, parseInt(input.value, 10) || 1));
		});
		detail.querySelectorAll("[data-group]").forEach((b) => b.onclick = () => {
			const gid = b.dataset.group;
			form.group = form.group === gid ? "" : gid;
			const group = il.data.groups.find((g) => g.id === form.group);
			form.depots = new Set(feedingDepots(sec, section(sec.reverse))
				.filter((id) => depot(id)?.tunable && (!group || group.depots.includes(id))));
			render();
		});
		detail.querySelectorAll("[data-cand]").forEach((row) => row.onclick = () => { il.candidate = parseInt(row.dataset.cand, 10); render(); });
		detail.querySelectorAll("[data-match]").forEach((b) => b.onclick = () => {
			const ms = parseInt(b.dataset.match, 10);
			form.mode = "target";
			form.target = dur(ms);
			suggest({ mode: "target", targetMs: ms });
		});
		const btn = detail.querySelector("#ilSuggest");
		if (btn) btn.onclick = () => {
			if (!form.depots.size) { il.suggestion = { ok: false, section: sec.id, error: "Tick at least one depot." }; render(); return; }
			if (form.mode === "target" && parseDuration(form.target) <= 0) { il.suggestion = { ok: false, section: sec.id, error: "Enter a target headway, e.g. 2:00." }; render(); return; }
			suggest();
		};
		wireApply(detail);
	}

	/* ------------------------------------------------------------- depots */

	function renderDepotPick(pick) {
		let html = `<div class="il-rows">`;
		for (const d of il.data.depots) {
			html += `<div class="il-row${d.id === il.depot ? " selected" : ""}" data-id="${esc(d.id)}">
				<span class="il-swatch" style="background:${hex(d.color)}"></span><span class="il-name">${esc(d.name)}</span>
				<span class="il-dim">${d.tunable ? `every ${dur(d.intervalMs)}${d.delayMs > 0 ? ` · +${dur(d.delayMs)}` : ""}` : esc(d.reason)}</span></div>`;
		}
		pick.innerHTML = html + `</div>`;
		pick.querySelectorAll(".il-row").forEach((row) => row.onclick = () => { il.depot = row.dataset.id; render(); });
	}

	function renderDepot(detail) {
		const d = depot(il.depot);
		if (!d) { detail.innerHTML = `<div class="il-empty">Pick a depot.</div>`; return; }
		let h = `<h2 class="il-h">${esc(d.name)}</h2><div class="il-meta">${d.routes.map((id) => chip(route(id))).join(" ")} · ${d.sidings} sidings</div>`;
		if (!d.tunable) {
			h += `<div class="il-dim">This depot uses a ${esc(d.reason)}, so its departures cannot be delayed.</div>`;
		} else {
			h += `<div class="il-dim">A train every <b>${dur(d.intervalMs)}</b> (slider ${d.uniformFreq >= 0 ? d.uniformFreq : "varies by hour"}).</div>
				<h3 class="il-h">DELAY DEPARTURES BY</h3>
				<div class="il-line"><input type="text" id="ilDelay" value="${esc(il.delayInput[d.id] ?? (d.delayMs > 0 ? dur(d.delayMs) : ""))}" placeholder="45, 1:30, 1m30s">
				<span class="il-dim">now +${dur(d.delayMs)} · the whole timetable slides; the gaps between this depot's own trains stay the same</span></div>`;
			h += applyBlock(() => ({ delays: { [d.id]: Math.max(0, parseDuration(($("ilDelay") || {}).value || "0")) } }), "Set delay");
			h += `<h3 class="il-h">HOURLY SLIDERS</h3><div class="il-hours">${d.freq.map((f, i) =>
				`<span title="${String(i).padStart(2, "0")}:00 — slider ${f}"><i style="height:${Math.min(100, f * 5)}%"></i></span>`).join("")}</div>`;
		}
		h += `<h3 class="il-h">SECTIONS IT FEEDS</h3>`;
		for (const s of il.data.sections) {
			if (!s.feeds.some((f) => f.depot === d.id)) continue;
			h += `<div class="il-row" data-go="${esc(s.id)}"><span class="il-dot ${evenClass(s.scheduled)}"></span><span class="il-name">${esc(s.name)}</span>${s.routes.map((id) => chip(route(id))).join("")}</div>`;
		}
		detail.innerHTML = h;
		const input = $("ilDelay");
		if (input) input.oninput = () => { il.delayInput[d.id] = input.value; };
		detail.querySelectorAll("[data-go]").forEach((row) => row.onclick = () => { il.tab = "sections"; selectSection(row.dataset.go); });
		wireApply(detail);
	}

	/* ------------------------------------------------------------- drawings */

	function schematic(sec) {
		const n = sec.stations.length;
		const routes = sec.routes.map(route).filter(Boolean);
		const W = 440, left = 150, right = 16, gap = 24;
		const H = Math.max(80, routes.length * gap + 40);
		const y = H / 2 + 6;
		const step = n > 1 ? (W - left - right) / (n - 1) : 0;
		const sug = il.suggestion;
		const pads = sug && sug.ok && !sug.unchanged && (sug.section === sec.id || sug.reverse === sec.id)
			&& !(sug.direction === "options" && il.candidate > 0) ? sug.pads || [] : [];
		let svg = `<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Section map">`;
		routes.forEach((r, i) => {
			const ry = y + (i - (routes.length - 1) / 2) * 4;
			const yIn = y + (i - (routes.length - 1) / 2) * gap;
			const prev = sec.prev.find((p) => p.route === r.id);
			svg += `<path d="M 4 ${yIn} L ${left - 50} ${yIn} L ${left} ${ry} L ${left + step * (n - 1)} ${ry}" fill="none" stroke="${hex(r.color)}" stroke-width="3.5" stroke-linejoin="round"/>`;
			svg += `<text x="6" y="${yIn - 5}" fill="#7d8aa5" font-size="10">${esc(prev ? prev.station : "starts here")}</text>`;
			const pad = pads.find((p) => p.forRoute === r.id);
			if (pad) {
				const label = "+" + dur(pad.extraMs) + (pad.kind === "before" ? " dwell" : " @ " + pad.station);
				const bw = Math.min(left - 60, label.length * 5.6 + 8);
				svg += `<rect x="${left - 54 - bw}" y="${yIn + 3}" width="${bw}" height="13" rx="3" fill="${HOLD}"/><text x="${left - 50 - bw}" y="${yIn + 13}" fill="#1a1a1a" font-size="9.5" font-weight="700">${esc(label)}</text>`;
			}
		});
		for (let i = 0; i < n; i++) {
			const x = left + step * i;
			svg += `<rect x="${x - 4}" y="${y - routes.length * 2 - 4}" width="8" height="${routes.length * 4 + 8}" rx="3" fill="#0b0e14" stroke="#e7ecf7" stroke-width="1.6"><title>${esc(sec.stations[i])}</title></rect>`;
			if (i === 0 || i === n - 1 || n <= 5) {
				svg += `<text x="${x}" y="${y + routes.length * 2 + 18}" fill="#d6dceb" font-size="10.5" text-anchor="${i === 0 ? "start" : i === n - 1 ? "end" : "middle"}">${esc(sec.stations[i])}</text>`;
			}
		}
		return svg + `</svg>`;
	}

	function strips(sec, rev, ov) {
		const dayMs = day();
		if (!dayMs) return "";
		const rows = [["This way", sec, null]];
		if (ov) rows.push(["→ suggested", sec, ov]);
		if (rev) {
			rows.push(["Other way", rev, null]);
			if (ov) rows.push(["→ suggested", rev, ov]);
		}
		let longest = 0;
		for (const [, s, o] of rows) for (const f of s.feeds) {
			const d = depot(f.depot);
			if (d && d.tunable) longest = Math.max(longest, o && o.freq[f.depot] ? dayMs / 6 / o.freq[f.depot] : d.intervalMs);
		}
		if (!longest) return `<div class="il-dim">No timed trains.</div>`;
		const windowMs = Math.min(dayMs, longest * 4), t0 = Math.floor(dayMs * 8 / 24);
		const W = 440, left = 78, right = 52, rowH = 18;
		const x = (t) => left + (t - t0) / windowMs * (W - left - right);
		let svg = `<svg viewBox="0 0 ${W} ${rows.length * rowH + 14}">`;
		rows.forEach(([label, s, o], i) => {
			const y = 2 + i * rowH;
			const list = arrivals(s, o);
			const st = statsOf(list.map((a) => a.t));
			svg += `<text x="0" y="${y + 11}" fill="${o ? "#4da3ff" : "#7d8aa5"}" font-size="10">${label}</text>`;
			svg += `<rect x="${left}" y="${y}" width="${W - left - right}" height="${rowH - 5}" fill="#121722" stroke="#232c3f"/>`;
			for (const a of list) {
				let t = a.t;
				if (t < t0) t += dayMs;
				if (t > t0 + windowMs) continue;
				const r = route(a.route);
				svg += `<rect x="${x(t) - 1.2}" y="${y + 2}" width="2.4" height="${rowH - 9}" fill="${r ? hex(r.color) : "#d6dceb"}"/>`;
			}
			if (st) {
				const color = st.evenness < 0.1 ? "#46c46e" : st.evenness < 0.3 ? "#f5b942" : "#e5484d";
				svg += `<text x="${W - right + 6}" y="${y + 11}" fill="${color}" font-size="10">±${Math.round(st.evenness * 100)}%</text>`;
			}
		});
		svg += `<text x="${left}" y="${rows.length * rowH + 11}" fill="#7d8aa5" font-size="9">+0s</text><text x="${W - right}" y="${rows.length * rowH + 11}" fill="#7d8aa5" font-size="9" text-anchor="end">+${dur(windowMs)} · one tick = one train</text>`;
		return svg + `</svg>`;
	}

	/* ------------------------------------------------------ live map overlay */

	function sectionRails(sec, routeId) {
		const legs = il.mapLegs && il.mapLegs.get(routeId);
		if (!legs) return null;
		const plats = legs.platforms;
		let idx = -1;
		for (let i = 0; i + sec.platforms.length <= plats.length; i++) {
			if (sec.platforms.every((p, k) => plats[i + k] === p)) { idx = i; break; }
		}
		if (idx < 0) return null;
		const trunk = [], approach = [];
		for (let k = 0; k < sec.platforms.length - 1; k++) trunk.push(...(legs.legs[idx + k] || []));
		if (idx > 0) approach.push(...(legs.legs[idx - 1] || []));
		return { trunk, approach };
	}

	function strokeRails(ctx, railIds) {
		ctx.beginPath();
		for (const id of railIds) {
			const r = state.rails.get(id);
			if (!r) continue;
			r.points.forEach((p, i) => {
				const [sx, sy] = worldToScreen(p[0], p[2]);
				i === 0 ? ctx.moveTo(sx, sy) : ctx.lineTo(sx, sy);
			});
		}
		ctx.stroke();
	}

	function platformMid(id) {
		const p = state.platforms.get(id);
		return p ? p.mid : null;
	}

	window.ilDrawOverlay = (ctx) => {
		if (!il.open || !il.data) return;
		if (il.dim !== state.dim) { il.dim = state.dim; load(); return; }
		const sec = section(il.section);
		if (!sec) return;
		const w = canvas.clientWidth, h = canvas.clientHeight;
		ctx.save();
		ctx.fillStyle = "rgba(8,11,17,0.62)";
		ctx.fillRect(0, 0, w, h);
		const routes = sec.routes.map(route).filter(Boolean);
		const m = routes.length;
		ctx.lineCap = "round";
		ctx.lineJoin = "round";
		// Approaches (dashed), then the shared stretch as concentric line colours.
		ctx.setLineDash([6, 5]);
		ctx.globalAlpha = 0.85;
		ctx.lineWidth = 3;
		routes.forEach((r) => {
			const rails = sectionRails(sec, r.id);
			if (!rails) return;
			ctx.strokeStyle = hex(r.color);
			strokeRails(ctx, rails.approach);
		});
		ctx.setLineDash([]);
		ctx.globalAlpha = 1;
		routes.forEach((r, i) => {
			const rails = sectionRails(sec, r.id);
			ctx.strokeStyle = hex(r.color);
			ctx.lineWidth = 4 + 4 * (m - 1 - i);
			if (rails && rails.trunk.length) {
				strokeRails(ctx, rails.trunk);
			} else {
				// No Map+ legs yet: straight lines between the platforms.
				ctx.beginPath();
				sec.platforms.forEach((pid, k) => {
					const mid = platformMid(pid);
					if (!mid) return;
					const [sx, sy] = worldToScreen(mid[0], mid[2]);
					k === 0 ? ctx.moveTo(sx, sy) : ctx.lineTo(sx, sy);
				});
				ctx.stroke();
			}
		});
		// Stations of the section.
		ctx.font = "600 12px system-ui";
		ctx.textAlign = "center";
		sec.platforms.forEach((pid, k) => {
			const mid = platformMid(pid);
			if (!mid) return;
			const [sx, sy] = worldToScreen(mid[0], mid[2]);
			ctx.beginPath();
			ctx.arc(sx, sy, 6, 0, Math.PI * 2);
			ctx.fillStyle = "#0b0e14";
			ctx.fill();
			ctx.strokeStyle = "#ffffff";
			ctx.lineWidth = 2.5;
			ctx.stroke();
			if (k === 0 || k === sec.platforms.length - 1 || state.view.scale > 0.6) {
				const label = sec.stations[k] || "";
				const tw = ctx.measureText(label).width;
				ctx.fillStyle = "#0b0e14dd";
				ctx.fillRect(sx - tw / 2 - 4, sy - 27, tw + 8, 16);
				ctx.fillStyle = "#ffffff";
				ctx.fillText(label, sx, sy - 15);
			}
		});
		// Suggested platform holds: where the change happens, on the map.
		const sug = il.suggestion;
		if (sug && sug.ok && !sug.unchanged && (sug.section === sec.id || sug.reverse === sec.id) && !(sug.direction === "options" && il.candidate > 0)) {
			const pulse = 0.5 + 0.5 * Math.sin(performance.now() / 320);
			for (const pad of sug.pads || []) {
				const mid = platformMid(pad.platform);
				if (!mid) continue;
				const [sx, sy] = worldToScreen(mid[0], mid[2]);
				ctx.beginPath();
				ctx.arc(sx, sy, 10 + pulse * 4, 0, Math.PI * 2);
				ctx.strokeStyle = `rgba(245,185,66,${0.55 + pulse * 0.45})`;
				ctx.lineWidth = 3;
				ctx.stroke();
				const label = "+" + dur(pad.extraMs) + " hold · " + pad.station;
				ctx.font = "700 11px system-ui";
				const tw = ctx.measureText(label).width;
				ctx.fillStyle = HOLD;
				ctx.beginPath();
				ctx.roundRect(sx - tw / 2 - 5, sy + 14, tw + 10, 17, 4);
				ctx.fill();
				ctx.fillStyle = "#1a1a1a";
				ctx.fillText(label, sx, sy + 26);
			}
		}
		ctx.restore();
	};

	/** Frame the section (and its approaches) in the part of the map the panels leave visible. */
	function fitSection() {
		const sec = section(il.section);
		if (!sec || !canvas.clientWidth) return;
		const pts = [];
		const add = (pid) => { const p = state.platforms.get(pid); if (p) pts.push(p.p1, p.p2); };
		sec.platforms.forEach(add);
		sec.prev.forEach((p) => add(p.platform));
		if (!pts.length) return;
		let minX = Infinity, minZ = Infinity, maxX = -Infinity, maxZ = -Infinity;
		for (const p of pts) { minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]); minZ = Math.min(minZ, p[2]); maxZ = Math.max(maxZ, p[2]); }
		const panelW = $("ilPanel").offsetWidth + 20;
		const sheetH = $("ilSheet").offsetHeight + 20;
		const visW = Math.max(200, canvas.clientWidth - panelW), visH = Math.max(160, canvas.clientHeight - sheetH);
		const scale = Math.min(visW / Math.max(60, maxX - minX + 120), visH / Math.max(60, maxZ - minZ + 120), 4);
		state.view.scale = scale;
		// Centre in the visible area: shift the world centre by half the covered strips.
		state.view.x = (minX + maxX) / 2 + panelW / 2 / scale;
		state.view.z = (minZ + maxZ) / 2 + sheetH / 2 / scale;
		invalidateStatic();
	}

	/* ------------------------------------------------ timetable stringline sheet */

	const sheetCanvas = $("ilSheetCanvas");
	const sheetCtx = sheetCanvas.getContext("2d");

	function drawSheet() {
		if (!il.open) return;
		const sec = il.data && section(il.section);
		const dpr = window.devicePixelRatio || 1;
		const cw = sheetCanvas.clientWidth, ch = sheetCanvas.clientHeight;
		if (!cw || !ch) return;
		if (sheetCanvas.width !== Math.round(cw * dpr) || sheetCanvas.height !== Math.round(ch * dpr)) {
			sheetCanvas.width = Math.round(cw * dpr);
			sheetCanvas.height = Math.round(ch * dpr);
		}
		const g = sheetCtx;
		g.setTransform(dpr, 0, 0, dpr, 0, 0);
		g.clearRect(0, 0, cw, ch);
		il.sheetTraces = [];
		const note = (text) => { g.fillStyle = "#7d8aa5"; g.font = "12px system-ui"; g.textAlign = "left"; g.fillText(text, 12, 22); };
		if (!sec) { note("Pick a section."); return; }
		const dayMs = day();
		const ov = overridesFor(sec);
		const show = ov ? il.sheetShow : "now";
		const labels = ["(stop before)", ...sec.stations];
		const left = 130, right = 16, top = 16, bottom = 22;
		const rowY = (i) => top + (ch - top - bottom) * (i / Math.max(1, labels.length - 1));
		let longest = 0;
		for (const f of sec.feeds) {
			const d = depot(f.depot);
			if (d && d.tunable) longest = Math.max(longest, ov && ov.freq[f.depot] ? dayMs / 6 / ov.freq[f.depot] : d.intervalMs);
		}
		if (!longest || !dayMs) { note("No timed trains through this section."); return; }
		const windowMs = Math.min(dayMs, longest * 4 / il.sheetZoom);
		const t0 = Math.floor(dayMs * 8 / 24);
		const x = (t) => left + (t - t0) / windowMs * (cw - left - right);
		g.font = "11px system-ui";
		g.textAlign = "right";
		labels.forEach((label, i) => {
			const y = rowY(i);
			g.strokeStyle = i === 1 ? "#34405a" : "#1c2433";
			g.lineWidth = 1;
			g.beginPath(); g.moveTo(left, y); g.lineTo(cw - right, y); g.stroke();
			g.fillStyle = i === 0 ? "#5b6981" : "#aab6cc";
			g.fillText(label.length > 18 ? label.slice(0, 17) + "…" : label, left - 8, y + 4);
		});
		g.textAlign = "center";
		g.fillStyle = "#5b6981";
		for (let k = 0; k <= 6; k++) g.fillText("+" + dur(windowMs * k / 6), x(t0 + windowMs * k / 6), ch - 6);
		g.save();
		g.beginPath();
		g.rect(left, 0, cw - left - right, ch);
		g.clip();
		const drawSet = (o, suggested) => {
			for (const run of runs(sec, o)) {
				const f = run.feed;
				if (!f.stationArr || !f.stationArr.length || f.stationArr[0] < 0) continue;
				let te = ((run.base + f.stationArr[0]) % dayMs + dayMs) % dayMs;
				if (te < t0 - windowMs * 0.3) te += dayMs;
				const rel = (off) => te + (off - f.stationArr[0]);
				const pts = [];
				if (f.approachDep >= 0) pts.push([rel(f.approachDep), 0]);
				f.stationArr.forEach((a, k) => {
					if (a >= 0) pts.push([rel(a), k + 1]);
					const dep = f.stationDep && f.stationDep[k];
					if (dep >= 0) pts.push([rel(dep), k + 1]);
				});
				if (!pts.length || pts[pts.length - 1][0] < t0 || pts[0][0] > t0 + windowMs) continue;
				const r = route(f.route);
				g.strokeStyle = r ? hex(r.color) : "#d6dceb";
				g.globalAlpha = suggested ? 1 : show === "both" ? 0.4 : 0.9;
				g.lineWidth = suggested ? 2.4 : 1.6;
				g.setLineDash(suggested || show === "now" ? [] : [5, 4]);
				g.beginPath();
				pts.forEach(([t, row], i) => { const px = x(t), py = rowY(row); i === 0 ? g.moveTo(px, py) : g.lineTo(px, py); });
				g.stroke();
				il.sheetTraces.push({ pts: pts.map(([t, row]) => [x(t), rowY(row)]), route: r, depot: run.depot, suggested });
			}
			g.setLineDash([]);
			g.globalAlpha = 1;
		};
		if (show !== "sug") drawSet(null, false);
		if (ov && show !== "now") drawSet(ov, true);
		// The gaps between trains reaching the section — the thing being fixed.
		const using = ov && show !== "now" ? ov : null;
		const list = arrivals(sec, using).map((a) => a.t);
		const st = statsOf(list);
		const at = list.map((t) => (t < t0 - windowMs * 0.3 ? t + dayMs : t)).sort((a, b) => a - b);
		g.font = "700 10px system-ui";
		for (let i = 0; i + 1 < at.length; i++) {
			const a = at[i], b = at[i + 1];
			if (b < t0 || a > t0 + windowMs) continue;
			const bad = st && (b - a) < st.meanMs * 0.6;
			g.fillStyle = bad ? "#e5484d" : "#8fa3c4";
			g.fillText(dur(b - a), (x(a) + x(b)) / 2, rowY(1) - 6);
		}
		g.restore();
		$("ilSheetMeta").textContent = sec.name
			+ (st ? ` · trains reach ${sec.stations[0]} every ${dur(st.meanMs)} (${dur(st.minMs)}–${dur(st.maxMs)}, ${evenLabel(st.evenness)})` : "")
			+ (ov ? (show === "now" ? " · showing now" : show === "sug" ? " · showing suggested" : " · dashed = now, solid = suggested") : "");
	}

	function sheetHover(e) {
		const tip = $("ilSheetTip");
		const rect = sheetCanvas.getBoundingClientRect();
		const mx = e.clientX - rect.left, my = e.clientY - rect.top;
		let best = null, bestD = 8;
		for (const tr of il.sheetTraces) {
			for (let i = 0; i + 1 < tr.pts.length; i++) {
				const [ax, ay] = tr.pts[i], [bx, by] = tr.pts[i + 1];
				const L = Math.hypot(bx - ax, by - ay) || 1;
				const u = Math.max(0, Math.min(1, ((mx - ax) * (bx - ax) + (my - ay) * (by - ay)) / (L * L)));
				const dist = Math.hypot(ax + u * (bx - ax) - mx, ay + u * (by - ay) - my);
				if (dist < bestD) { bestD = dist; best = tr; }
			}
		}
		if (!best) { tip.classList.add("hidden"); return; }
		tip.textContent = `${best.route ? best.route.name : "?"} · ${best.depot.name}${best.suggested ? " · suggested" : " · now"}`;
		tip.style.left = (mx + 12) + "px";
		tip.style.top = (my - 8) + "px";
		tip.classList.remove("hidden");
	}

	/* ------------------------------------------------------------- wiring */

	function init() {
		$("interlineBtn").onclick = () => setOpen(!il.open);
		$("ilClose").onclick = () => setOpen(false);
		$("ilRefresh").onclick = () => { il.applyMsg = ""; load(); };
		$("ilTabSections").onclick = () => { il.tab = "sections"; render(); };
		$("ilTabDepots").onclick = () => { il.tab = "depots"; render(); };
		$("ilSheetShow").querySelectorAll("button").forEach((b) => b.onclick = () => {
			il.sheetShow = b.dataset.v;
			$("ilSheetShow").querySelectorAll("button").forEach((x) => x.classList.toggle("on", x === b));
			drawSheet();
		});
		$("ilSheetZoomIn").onclick = () => { il.sheetZoom = Math.min(8, il.sheetZoom * 1.5); drawSheet(); };
		$("ilSheetZoomOut").onclick = () => { il.sheetZoom = Math.max(0.25, il.sheetZoom / 1.5); drawSheet(); };
		sheetCanvas.onmousemove = sheetHover;
		sheetCanvas.onmouseleave = () => $("ilSheetTip").classList.add("hidden");
		window.addEventListener("resize", () => drawSheet());
		document.addEventListener("keydown", (e) => {
			if (e.target && (e.target.tagName === "INPUT" || e.target.tagName === "SELECT" || e.target.tagName === "TEXTAREA")) return;
			if ((e.key === "i" || e.key === "I") && !e.metaKey && !e.ctrlKey && !e.altKey) setOpen(!il.open);
		});
		if (new URLSearchParams(location.search).get("interline") === "1") {
			// Wait for app.js to have the network before framing the section.
			const start = () => (state.network || DEMO ? setOpen(true) : setTimeout(start, 300));
			setTimeout(start, 300);
		}
	}

	/* ---------------------------------------------------------------- demo */

	/* Generated from the real solver (scratchpad DemoDump.java); section s1 sits on the dispatch demo's pl1/pl2. */
	const DEMO_DATA = {"analysis":{"ok":true,"dimension":"demo","gameMillisPerDay":1200000,"builtAt":1790000000000,"timeMoving":true,"maxFrequency":20,"maxPadMs":90000,"depots":[{"id":"2","name":"Riverside Depot","color":14758,"routes":["21","22"],"freq":[4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4],"effFreq":[4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4],"uniformFreq":4,"intervalMs":50000,"delayMs":0,"appliedMs":-1,"tunable":true,"reason":"","sidings":2,"groups":["77"]},{"id":"1","name":"Uptown Yard","color":15611182,"routes":["11","12"],"freq":[4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4],"effFreq":[4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4],"uniformFreq":4,"intervalMs":50000,"delayMs":0,"appliedMs":-1,"tunable":true,"reason":"","sidings":3,"groups":["77"]}],"routes":[{"id":"11","name":"Broadway Local","number":"1","color":15611182,"stations":["Dyckman St","Central Junction","Albany","Harbor Terminal"]},{"id":"12","name":"Broadway Local","number":"1","color":15611182,"stations":["Harbor Terminal","Albany","Central Junction","Dyckman St"]},{"id":"21","name":"Lenox Express","number":"2","color":14758,"stations":["Lenox Av","Central Junction","Albany","Harbor Terminal"]},{"id":"22","name":"Lenox Express","number":"2","color":14758,"stations":["Harbor Terminal","Albany","Central Junction","Lenox Av"]}],"groups":[{"id":"77","name":"Trunk pair","depots":["1","2"]}],"sections":[{"id":"s1","name":"Central Junction \u2192 Albany","reverse":"s2","routes":["11","21"],"platforms":["pl1","pl2"],"stations":["Central Junction","Albany"],"feeds":[{"depot":"1","route":"11","travelMs":30000,"spreadMs":2000,"routePos":0,"entryIndex":1,"stationArr":[30000,88000],"stationDep":[48000,106000],"approachDep":10000},{"depot":"2","route":"21","travelMs":40000,"spreadMs":0,"routePos":0,"entryIndex":1,"stationArr":[40000,98000],"stationDep":[58000,116000],"approachDep":16000}],"prev":[{"route":"11","platform":"100","station":"Dyckman St","dwellMs":20000,"overridden":false,"index":0},{"route":"21","platform":"200","station":"Lenox Av","dwellMs":20000,"overridden":false,"index":0}],"holds":[{"route":"11","stopIndex":0,"platform":"100","station":"Dyckman St","dwellMs":20000,"overridden":false,"kind":"before","forRoute":"11"},{"route":"21","stopIndex":0,"platform":"200","station":"Lenox Av","dwellMs":20000,"overridden":false,"kind":"before","forRoute":"21"}],"scheduled":{"count":48,"meanMs":25000,"minMs":10000,"maxMs":40000,"evenness":0.6,"breaks":0},"measured":{"samples":37,"avgMs":25400,"minMs":9800,"maxMs":41200,"irregularity":0.58}},{"id":"s2","name":"Albany \u2192 Central Junction","reverse":"s1","routes":["12","22"],"platforms":["302","301"],"stations":["Albany","Central Junction"],"feeds":[{"depot":"1","route":"12","travelMs":200000,"spreadMs":2000,"routePos":1,"entryIndex":1,"stationArr":[200000,258000],"stationDep":[218000,276000],"approachDep":180000},{"depot":"2","route":"22","travelMs":150000,"spreadMs":0,"routePos":1,"entryIndex":1,"stationArr":[150000,208000],"stationDep":[168000,226000],"approachDep":130000}],"prev":[{"route":"12","platform":"303","station":"Harbor Terminal","dwellMs":20000,"overridden":false,"index":0},{"route":"22","platform":"403","station":"Harbor Terminal","dwellMs":20000,"overridden":false,"index":0}],"holds":[{"route":"12","stopIndex":0,"platform":"303","station":"Harbor Terminal","dwellMs":20000,"overridden":false,"kind":"before","forRoute":"12"},{"route":"22","stopIndex":0,"platform":"403","station":"Harbor Terminal","dwellMs":20000,"overridden":false,"kind":"before","forRoute":"22"},{"route":"11","stopIndex":3,"platform":"103","station":"Harbor Terminal","dwellMs":30000,"overridden":false,"kind":"turnaround","forRoute":"12"},{"route":"21","stopIndex":3,"platform":"103","station":"Harbor Terminal","dwellMs":30000,"overridden":false,"kind":"turnaround","forRoute":"22"}],"scheduled":{"count":48,"meanMs":25000,"minMs":0,"maxMs":50000,"evenness":1.0,"breaks":0},"measured":{"samples":0,"avgMs":0,"minMs":0,"maxMs":0,"irregularity":0.0}}]},"suggestions":{"balance":{"unchanged":false,"ok":true,"section":"s1","reverse":"s2","mode":"even","direction":"balance","adjustable":["1","2"],"fixed":[],"delays":{"1":0,"2":15000},"headways":{"1":50000,"2":50000},"frequencies":{},"pads":[{"route":"22","platform":"403","station":"Harbor Terminal","stopIndex":0,"kind":"before","forRoute":"22","extraMs":10000,"currentDwellMs":20000,"dwellMs":30000}],"before":{"fwd":{"count":48,"meanMs":25000,"minMs":10000,"maxMs":40000,"evenness":0.6,"breaks":0},"rev":{"count":48,"meanMs":25000,"minMs":0,"maxMs":50000,"evenness":1.0,"breaks":0}},"afterNoPads":{"fwd":{"count":48,"meanMs":25000,"minMs":25000,"maxMs":25000,"evenness":0.0,"breaks":0},"rev":{"count":48,"meanMs":25000,"minMs":15000,"maxMs":35000,"evenness":0.4,"breaks":0}},"after":{"fwd":{"count":48,"meanMs":25000,"minMs":25000,"maxMs":25000,"evenness":0.0,"breaks":0},"rev":{"count":48,"meanMs":25000,"minMs":25000,"maxMs":25000,"evenness":0.0,"breaks":0}},"candidates":[{"delays":{"1":0,"2":15000},"stats":{"fwd":{"count":48,"meanMs":25000,"minMs":25000,"maxMs":25000,"evenness":0.0,"breaks":0},"rev":{"count":48,"meanMs":25000,"minMs":25000,"maxMs":25000,"evenness":0.0,"breaks":0}},"score":0.0},{"delays":{"1":0,"2":20000},"stats":{"fwd":{"count":48,"meanMs":25000,"minMs":20000,"maxMs":30000,"evenness":0.2,"breaks":0},"rev":{"count":48,"meanMs":25000,"minMs":20000,"maxMs":30000,"evenness":0.2,"breaks":0}},"score":0.264}],"mixed":false,"warnings":[]},"forward":{"unchanged":false,"ok":true,"section":"s1","reverse":"s2","mode":"even","direction":"forward","adjustable":["1","2"],"fixed":[],"delays":{"1":0,"2":15000},"headways":{"1":50000,"2":50000},"frequencies":{},"pads":[{"route":"22","platform":"403","station":"Harbor Terminal","stopIndex":0,"kind":"before","forRoute":"22","extraMs":10000,"currentDwellMs":20000,"dwellMs":30000}],"before":{"fwd":{"count":48,"meanMs":25000,"minMs":10000,"maxMs":40000,"evenness":0.6,"breaks":0},"rev":{"count":48,"meanMs":25000,"minMs":0,"maxMs":50000,"evenness":1.0,"breaks":0}},"afterNoPads":{"fwd":{"count":48,"meanMs":25000,"minMs":25000,"maxMs":25000,"evenness":0.0,"breaks":0},"rev":{"count":48,"meanMs":25000,"minMs":15000,"maxMs":35000,"evenness":0.4,"breaks":0}},"after":{"fwd":{"count":48,"meanMs":25000,"minMs":25000,"maxMs":25000,"evenness":0.0,"breaks":0},"rev":{"count":48,"meanMs":25000,"minMs":25000,"maxMs":25000,"evenness":0.0,"breaks":0}},"candidates":[{"delays":{"1":0,"2":15000},"stats":{"fwd":{"count":48,"meanMs":25000,"minMs":25000,"maxMs":25000,"evenness":0.0,"breaks":0},"rev":{"count":48,"meanMs":25000,"minMs":25000,"maxMs":25000,"evenness":0.0,"breaks":0}},"score":0.0}],"mixed":false,"warnings":[]},"reverse":{"unchanged":false,"ok":true,"section":"s1","reverse":"s2","mode":"even","direction":"reverse","adjustable":["1","2"],"fixed":[],"delays":{"1":0,"2":25000},"headways":{"1":50000,"2":50000},"frequencies":{},"pads":[],"before":{"fwd":{"count":48,"meanMs":25000,"minMs":10000,"maxMs":40000,"evenness":0.6,"breaks":0},"rev":{"count":48,"meanMs":25000,"minMs":0,"maxMs":50000,"evenness":1.0,"breaks":0}},"afterNoPads":{"fwd":{"count":48,"meanMs":25000,"minMs":15000,"maxMs":35000,"evenness":0.4,"breaks":0},"rev":{"count":48,"meanMs":25000,"minMs":25000,"maxMs":25000,"evenness":0.0,"breaks":0}},"after":{"fwd":{"count":48,"meanMs":25000,"minMs":15000,"maxMs":35000,"evenness":0.4,"breaks":0},"rev":{"count":48,"meanMs":25000,"minMs":25000,"maxMs":25000,"evenness":0.0,"breaks":0}},"candidates":[{"delays":{"1":0,"2":25000},"stats":{"fwd":{"count":48,"meanMs":25000,"minMs":15000,"maxMs":35000,"evenness":0.4,"breaks":0},"rev":{"count":48,"meanMs":25000,"minMs":25000,"maxMs":25000,"evenness":0.0,"breaks":0}},"score":0.0}],"mixed":false,"warnings":[]},"options":{"unchanged":false,"ok":true,"section":"s1","reverse":"s2","mode":"even","direction":"options","adjustable":["1","2"],"fixed":[],"delays":{"1":0,"2":15000},"headways":{"1":50000,"2":50000},"frequencies":{},"pads":[{"route":"22","platform":"403","station":"Harbor Terminal","stopIndex":0,"kind":"before","forRoute":"22","extraMs":10000,"currentDwellMs":20000,"dwellMs":30000}],"before":{"fwd":{"count":48,"meanMs":25000,"minMs":10000,"maxMs":40000,"evenness":0.6,"breaks":0},"rev":{"count":48,"meanMs":25000,"minMs":0,"maxMs":50000,"evenness":1.0,"breaks":0}},"afterNoPads":{"fwd":{"count":48,"meanMs":25000,"minMs":25000,"maxMs":25000,"evenness":0.0,"breaks":0},"rev":{"count":48,"meanMs":25000,"minMs":15000,"maxMs":35000,"evenness":0.4,"breaks":0}},"after":{"fwd":{"count":48,"meanMs":25000,"minMs":25000,"maxMs":25000,"evenness":0.0,"breaks":0},"rev":{"count":48,"meanMs":25000,"minMs":25000,"maxMs":25000,"evenness":0.0,"breaks":0}},"candidates":[{"delays":{"1":0,"2":15000},"stats":{"fwd":{"count":48,"meanMs":25000,"minMs":25000,"maxMs":25000,"evenness":0.0,"breaks":0},"rev":{"count":48,"meanMs":25000,"minMs":25000,"maxMs":25000,"evenness":0.0,"breaks":0}},"score":0.0},{"delays":{"1":0,"2":20000},"stats":{"fwd":{"count":48,"meanMs":25000,"minMs":20000,"maxMs":30000,"evenness":0.2,"breaks":0},"rev":{"count":48,"meanMs":25000,"minMs":20000,"maxMs":30000,"evenness":0.2,"breaks":0}},"score":0.264},{"delays":{"1":0,"2":25000},"stats":{"fwd":{"count":48,"meanMs":25000,"minMs":15000,"maxMs":35000,"evenness":0.4,"breaks":0},"rev":{"count":48,"meanMs":25000,"minMs":25000,"maxMs":25000,"evenness":0.0,"breaks":0}},"score":0.484}],"mixed":false,"warnings":[]},"holds":{"unchanged":false,"ok":true,"section":"s1","reverse":"s2","mode":"even","direction":"balance","adjustable":["1","2"],"fixed":[],"delays":{"1":0,"2":0},"headways":{"1":50000,"2":50000},"frequencies":{},"pads":[{"route":"21","platform":"200","station":"Lenox Av","stopIndex":0,"kind":"before","forRoute":"21","extraMs":15000,"currentDwellMs":20000,"dwellMs":35000},{"route":"12","platform":"303","station":"Harbor Terminal","stopIndex":0,"kind":"before","forRoute":"12","extraMs":40000,"currentDwellMs":20000,"dwellMs":60000}],"before":{"fwd":{"count":48,"meanMs":25000,"minMs":10000,"maxMs":40000,"evenness":0.6,"breaks":0},"rev":{"count":48,"meanMs":25000,"minMs":0,"maxMs":50000,"evenness":1.0,"breaks":0}},"afterNoPads":{"fwd":{"count":48,"meanMs":25000,"minMs":10000,"maxMs":40000,"evenness":0.6,"breaks":0},"rev":{"count":48,"meanMs":25000,"minMs":0,"maxMs":50000,"evenness":1.0,"breaks":0}},"after":{"fwd":{"count":48,"meanMs":25000,"minMs":25000,"maxMs":25000,"evenness":0.0,"breaks":0},"rev":{"count":48,"meanMs":25000,"minMs":25000,"maxMs":25000,"evenness":0.0,"breaks":0}},"candidates":[{"delays":{"1":0,"2":0},"stats":{"fwd":{"count":48,"meanMs":25000,"minMs":25000,"maxMs":25000,"evenness":0.0,"breaks":0},"rev":{"count":48,"meanMs":25000,"minMs":25000,"maxMs":25000,"evenness":0.0,"breaks":0}},"score":0.0}],"mixed":false,"warnings":[]},"target":{"unchanged":false,"ok":true,"section":"s1","reverse":"s2","mode":"target","direction":"balance","adjustable":["1","2"],"fixed":[],"delays":{"1":0,"2":2500},"headways":{"1":25000,"2":25000},"frequencies":{"1":8,"2":8},"pads":[{"route":"22","platform":"403","station":"Harbor Terminal","stopIndex":0,"kind":"before","forRoute":"22","extraMs":10000,"currentDwellMs":20000,"dwellMs":30000}],"before":{"fwd":{"count":48,"meanMs":25000,"minMs":10000,"maxMs":40000,"evenness":0.6,"breaks":0},"rev":{"count":48,"meanMs":25000,"minMs":0,"maxMs":50000,"evenness":1.0,"breaks":0}},"afterNoPads":{"fwd":{"count":96,"meanMs":12500,"minMs":12500,"maxMs":12500,"evenness":0.0,"breaks":0},"rev":{"count":96,"meanMs":12500,"minMs":2500,"maxMs":22500,"evenness":0.8,"breaks":0}},"after":{"fwd":{"count":96,"meanMs":12500,"minMs":12500,"maxMs":12500,"evenness":0.0,"breaks":0},"rev":{"count":96,"meanMs":12500,"minMs":12500,"maxMs":12500,"evenness":0.0,"breaks":0}},"candidates":[{"delays":{"1":0,"2":2500},"stats":{"fwd":{"count":96,"meanMs":12500,"minMs":12500,"maxMs":12500,"evenness":0.0,"breaks":0},"rev":{"count":96,"meanMs":12500,"minMs":12500,"maxMs":12500,"evenness":0.0,"breaks":0}},"score":0.0},{"delays":{"1":0,"2":7500},"stats":{"fwd":{"count":96,"meanMs":12500,"minMs":7500,"maxMs":17500,"evenness":0.4,"breaks":0},"rev":{"count":96,"meanMs":12500,"minMs":7500,"maxMs":17500,"evenness":0.4,"breaks":0}},"score":0.528}],"mixed":false,"target":{"targetMs":12000,"achievedMs":12500,"alternatives":[{"frequencies":{"1":8,"2":8},"headwayMs":12500},{"frequencies":{"1":9,"2":9},"headwayMs":11111},{"frequencies":{"1":10,"2":10},"headwayMs":10000},{"frequencies":{"1":9,"2":8},"headwayMs":11765}]},"warnings":[]}}};

	init();
})();
