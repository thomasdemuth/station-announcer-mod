"use strict";
/*
 * The dispatch STATION PAGE (STATION_PAGE_PLAN.md, Thomas 2026-10-01) and the richer side
 * panel that leads to it. A classic script on top of app.js (shares its globals: state, API,
 * DEMO, $, firstLang, colorHex, escapeHtml, ACCESS_IMG, fmtDev). Data: /api/station?id=…
 * (DispatchStation.java), polled every 10 s while the page is open. The 3D schematic lives in
 * station3d.js (an ES module on the bundled three.js), reached through window.Station3D.
 *
 * Open it: double-click a station on the map, "Open station page" in the side panel, or
 * #station=<id> in the URL. Esc closes it.
 */
const StationPage = (() => {
	const POLL_MS = 10000;
	const PANEL_TTL_MS = 15000;
	const PREFS_KEY = "sa_mapplus_prefs";
	const sp = {
		open: false, id: null, data: null, skew: 0, timer: null, tick: null, fetching: false,
		tab: "boards", view3d: null, selectedAnchor: null, layers: null, cut: 1,
		delayPlatform: "", token: "", msg: "", err: "",
		panel: new Map(),   // station id -> {at, data} for the side panel quick look
	};

	/* ------------------------------------------------------------------ data */

	async function fetchStation(id) {
		if (DEMO) return demoStation(id);
		const body = await (await fetch(`${API}/station?dimension=${state.dim}&id=${encodeURIComponent(id)}`)).json();
		return body && body.data ? body.data : body;
	}

	async function load() {
		if (!sp.open || sp.fetching) return;
		sp.fetching = true;
		try {
			const data = await fetchStation(sp.id);
			if (!sp.open) return;
			if (data && data.error) { sp.err = data.error; render(); return; }
			sp.err = "";
			const first = !sp.data || sp.data.station.id !== data.station.id;
			const geoChanged = first || JSON.stringify(sp.data.geometry || null).length !== JSON.stringify(data.geometry || null).length
				|| (sp.data.layout && sp.data.layout.scannedAt) !== (data.layout && data.layout.scannedAt);
			sp.data = data;
			buildLabels(data.routes);
			sp.skew = (data.now || Date.now()) - Date.now();
			sp.panel.set(data.station.id, { at: Date.now(), data });
			render();
			if (geoChanged) build3d();
		} catch (e) {
			sp.err = "Could not reach the dispatch server.";
			render();
		} finally {
			sp.fetching = false;
		}
	}

	const now = () => Date.now() + sp.skew;

	/* ------------------------------------------------------------- open/close */

	function open(id) {
		if (!id) return;
		ensureDom();
		sp.open = true;
		if (sp.id !== id) {
			sp.id = id;
			sp.data = null;
			sp.selectedAnchor = null;
			sp.msg = "";
			if (sp.view3d) { sp.view3d.dispose(); sp.view3d = null; }
		}
		$("stationPage").classList.remove("hidden");
		history.replaceState(null, "", "#station=" + encodeURIComponent(id));
		render();
		load();
		clearInterval(sp.timer);
		sp.timer = setInterval(load, POLL_MS);
		clearInterval(sp.tick);
		sp.tick = setInterval(tick, 1000);
	}

	function close() {
		sp.open = false;
		clearInterval(sp.timer);
		clearInterval(sp.tick);
		$("stationPage")?.classList.add("hidden");
		if (location.hash.startsWith("#station=")) history.replaceState(null, "", location.pathname + location.search);
	}

	/* ------------------------------------------------------------------- dom */

	function ensureDom() {
		if ($("stationPage")) return;
		const page = document.createElement("section");
		page.id = "stationPage";
		page.className = "hidden";
		page.innerHTML = `
			<header id="spHead"></header>
			<div id="spBody">
				<div id="sp3d">
					<div id="sp3dStage"></div>
					<div id="sp3dTools"></div>
					<div id="sp3dTip" class="hidden"></div>
					<div id="sp3dEmpty" class="hidden"></div>
				</div>
				<div id="spSide">
					<nav id="spTabs"></nav>
					<div id="spContent"></div>
				</div>
			</div>`;
		document.body.appendChild(page);
		page.addEventListener("click", onClick);
		page.addEventListener("change", onChange);
		page.addEventListener("input", onInput);
	}

	function render() {
		if (!sp.open) return;
		const d = sp.data;
		const st = d && d.station;
		const routes = d ? d.routes || [] : [];
		const bullets = uniqueLines(routes).map(bullet).join("");
		const stepFree = st && (st.accessible || Object.values(st.layoutStepFree || {}).some(Boolean));
		$("spHead").innerHTML = `
			<button class="btn small" data-act="close" title="Back to the map [Esc]">←</button>
			<span class="sp-dot" style="background:${st ? colorHex(st.color) : "#5b6981"}"></span>
			<h1>${escapeHtml(st ? firstLang(st.name) : "Loading…")}${stepFree ? " " + ACCESS_IMG : ""}</h1>
			<span class="sp-bullets">${bullets}</span>
			<span class="spacer"></span>
			${st && st.zone ? `<span class="sp-meta">Zone ${escapeHtml(String(st.zone))}</span>` : ""}
			<span class="sp-meta" id="spClock">${clock(now())}</span>
			${st ? `<a class="btn small" href="map.html?to=station:${encodeURIComponent(st.id)}" target="_blank" title="Plan a journey here on System Map+">Map+ ↗</a>` : ""}
			<button class="btn small" data-act="refresh" title="Refresh now">⟳</button>`;
		const tabs = [["boards", "Departures"], ["service", "Service"], ["access", "Exits & access"], ["ops", "Operations"]];
		$("spTabs").innerHTML = tabs.map(([k, label]) =>
			`<button class="sp-tab${sp.tab === k ? " active" : ""}" data-tab="${k}">${label}</button>`).join("");
		let html = "";
		if (sp.err) html += `<div class="sp-err">${escapeHtml(sp.err)}</div>`;
		if (!d) {
			html += `<div class="sp-empty">Loading the station…</div>`;
		} else if (sp.tab === "boards") {
			html += boardsHtml(d);
		} else if (sp.tab === "service") {
			html += serviceHtml(d);
		} else if (sp.tab === "access") {
			html += accessHtml(d);
		} else {
			html += opsHtml(d);
		}
		const content = $("spContent");
		const scroll = content.scrollTop;
		content.innerHTML = html;
		content.scrollTop = scroll;
		if (d && sp.tab === "service") drawService(d);
		render3dTools();
	}

	/** Every second: countdowns and the clock, without rebuilding the page. */
	function tick() {
		if (!sp.open) return;
		const c = $("spClock");
		if (c) c.textContent = clock(now());
		document.querySelectorAll("#stationPage [data-due]").forEach((el) => {
			el.textContent = dueText(Number(el.dataset.due));
		});
	}

	/* ------------------------------------------------------------- departures */

	function boardsHtml(d) {
		const routes = new Map((d.routes || []).map((r) => [r.id, r]));
		const byPlat = new Map();
		for (const u of d.upcoming || []) {
			if (!byPlat.has(u.platformId)) byPlat.set(u.platformId, []);
			byPlat.get(u.platformId).push(u);
		}
		let html = `<div class="sp-note">Live from MTR's timetable · updates every 10 s</div><div class="sp-boards">`;
		for (const p of d.platforms || []) {
			const list = byPlat.get(p.id) || [];
			const chips = (p.routeIds || []).map((id) => routes.get(id)).filter((r) => r && !r.hidden)
				.filter((r, i, a) => a.findIndex((x) => lineKeyOf(x) === lineKeyOf(r)) === i).map(bullet).join("");
			const access = platformStepFree(d, p.id);
			html += `<div class="sp-board">
				<div class="sp-board-head"><span class="sp-plat">${escapeHtml(firstLang(p.name) || "?")}</span>${chips}
					${access ? ACCESS_IMG : ""}${p.held ? '<span class="badge held">HOLDING</span>' : ""}
					<span class="spacer"></span><span class="sp-dim">dwell ${Math.round((p.dwellMs || 0) / 1000)} s</span></div>`;
			if (!list.length) {
				html += `<div class="sp-board-empty">No departures in the next hour</div>`;
			}
			for (const u of list.slice(0, 6)) {
				const r = routes.get(u.routeId);
				const late = u.realtime && u.deviation > 30000 ? `<span class="late">+${Math.round(u.deviation / 60000)} min</span>`
					: u.realtime && u.deviation < -30000 ? `<span class="early">${Math.round(u.deviation / 60000)} min</span>` : "";
				html += `<div class="sp-dep">
					${bullet(r || { number: u.routeNumber, name: u.routeName, color: u.color })}
					<span class="sp-dest">${escapeHtml(firstLang(u.dest) || "—")}${u.terminating ? ' <span class="sp-dim">terminates</span>' : ""}</span>
					<span class="sp-time">${clock(u.departure, true)}</span>
					<span class="sp-due" data-due="${u.departure}">${dueText(u.departure)}</span>
					${late}${u.realtime ? '<span class="rt" title="A real train is on its way">●</span>' : '<span class="rt sched" title="Scheduled — no train running it yet">○</span>'}
					${u.cars ? `<span class="sp-dim">${u.cars} car${u.cars === 1 ? "" : "s"}</span>` : ""}
				</div>`;
			}
			html += `</div>`;
		}
		html += `</div>`;
		// routes calling here
		html += `<h3>Routes calling here</h3><table class="sp-table"><thead><tr><th>Line</th><th>From</th><th>Next stop</th><th>To</th><th class="num">Scheduled every</th></tr></thead><tbody>`;
		for (const r of (d.routes || []).filter((x) => !x.hidden)) {
			html += `<tr><td>${bullet(r)} ${escapeHtml(lineName(r))}</td><td>${escapeHtml(firstLang(r.prev) || "—")}</td>
				<td>${escapeHtml(firstLang(r.next) || "— (terminus)")}</td><td>${escapeHtml(firstLang(r.terminus) || "—")}</td>
				<td class="num">${r.schedHeadwayMs > 0 ? fmtMs(r.schedHeadwayMs) : "—"}</td></tr>`;
		}
		html += `</tbody></table>`;
		return html;
	}

	/* --------------------------------------------------------------- service */

	function serviceHtml(d) {
		const routes = new Map((d.routes || []).map((r) => [r.id, r]));
		const plats = new Map((d.platforms || []).map((p) => [p.id, p]));
		let html = "";
		if (!d.analyticsEnabled) {
			html += `<div class="sp-note warn">Analytics recording is off on this server (analytics.enabled), so there is nothing to compare the timetable with.</div>`;
		}
		// --- headway now
		html += `<h3>Headway — scheduled vs actual <span class="sp-dim">(last ${d.windowMinutes} min)</span></h3>`;
		const rows = (d.headways || []).slice().sort((a, b) =>
			lineName(routes.get(a.routeId) || {}).localeCompare(lineName(routes.get(b.routeId) || {})));
		if (!rows.length) {
			html += `<div class="sp-empty">No departures recorded here in the window yet.</div>`;
		} else {
			html += `<table class="sp-table"><thead><tr><th>Line</th><th>Plat.</th><th class="num">Scheduled</th><th class="num">Actual avg</th>
				<th class="num">Shortest – longest</th><th class="num">Bunched</th><th>Gaps</th></tr></thead><tbody>`;
			rows.forEach((h, i) => {
				const r = routes.get(h.routeId) || {};
				const avg = h.averageMs;
				const off = h.scheduledMs > 0 && avg ? (avg - h.scheduledMs) / h.scheduledMs : 0;
				const cls = Math.abs(off) > 0.25 ? "bad" : Math.abs(off) > 0.1 ? "warn" : "ok";
				html += `<tr><td>${bullet(r)} <span class="sp-dim">to ${escapeHtml(firstLang(r.terminus) || "?")}</span></td>
					<td>${escapeHtml(firstLang((plats.get(h.platformId) || {}).name) || "?")}</td>
					<td class="num">${h.scheduledMs > 0 ? fmtMs(h.scheduledMs) : "—"}</td>
					<td class="num ${avg ? cls : ""}">${avg ? fmtMs(avg) : "—"}</td>
					<td class="num">${avg ? fmtMs(h.minMs) + " – " + fmtMs(h.maxMs) : "—"}</td>
					<td class="num ${h.bunched ? "bad" : ""}">${h.bunched || 0}</td>
					<td><canvas class="sp-spark" data-spark="${i}" width="140" height="26"></canvas></td></tr>`;
			});
			html += `</tbody></table>`;
		}
		// --- the strip
		html += `<h3>Timetable vs reality</h3><div class="sp-note">Ticks: when each train was due · dots: when it actually left (green on time, amber late, red very late) · hollow ticks: scheduled departures still to come.</div>
			<canvas id="spStrip" class="sp-strip"></canvas>`;
		// --- per-train delays
		const deps = d.departures || [];
		html += `<h3>Recent departures</h3>
			<div class="sp-filter">Platform <select data-filter="delayPlatform"><option value="">all</option>${(d.platforms || []).map((p) =>
				`<option value="${p.id}"${sp.delayPlatform === p.id ? " selected" : ""}>${escapeHtml(firstLang(p.name))}</option>`).join("")}</select></div>`;
		const shown = deps.filter((row) => !sp.delayPlatform || row[1] === sp.delayPlatform).slice(0, 80);
		if (!shown.length) {
			html += `<div class="sp-empty">No departures recorded yet.</div>`;
		} else {
			html += `<table class="sp-table"><thead><tr><th>Due</th><th>Left</th><th>Line</th><th>Plat.</th><th class="num">Late</th><th class="num">Dwell</th><th class="num">vs planned</th></tr></thead><tbody>`;
			for (const [t, plat, route, dev, dwell, schedDwell] of shown) {
				const r = routes.get(route) || {};
				const [devTxt, devCls] = fmtDev(dev);
				const over = dwell >= 0 && schedDwell > 0 ? dwell - schedDwell : null;
				html += `<tr><td>${clock(t - dev, true)}</td><td>${clock(t, true)}</td><td>${bullet(r)}</td>
					<td>${escapeHtml(firstLang((plats.get(plat) || {}).name) || "?")}</td>
					<td class="num ${devCls}">${devTxt}</td><td class="num">${dwell >= 0 ? Math.round(dwell / 1000) + " s" : "—"}</td>
					<td class="num ${over > 15000 ? "bad" : over > 5000 ? "warn" : ""}">${over == null ? "—" : (over >= 0 ? "+" : "") + Math.round(over / 1000) + " s"}</td></tr>`;
			}
			html += `</tbody></table>`;
		}
		return html;
	}

	function drawService(d) {
		const routes = new Map((d.routes || []).map((r) => [r.id, r]));
		const rows = (d.headways || []).slice().sort((a, b) =>
			lineName(routes.get(a.routeId) || {}).localeCompare(lineName(routes.get(b.routeId) || {})));
		document.querySelectorAll("#spContent canvas[data-spark]").forEach((cv) => {
			const h = rows[Number(cv.dataset.spark)];
			if (h) drawSpark(cv, h, (routes.get(h.routeId) || {}).color);
		});
		const strip = $("spStrip");
		if (strip) drawStrip(strip, d, routes);
	}

	/** The gaps between departures as bars, the scheduled headway as a line. */
	function drawSpark(cv, h, colour) {
		const g = cv.getContext("2d");
		const W = cv.width, H = cv.height;
		g.clearRect(0, 0, W, H);
		const gaps = h.gaps || [];
		if (!gaps.length) return;
		const max = Math.max(h.scheduledMs || 0, ...gaps.map((x) => x[1])) * 1.1;
		const bw = Math.max(2, Math.min(10, (W - 2) / gaps.length - 1));
		gaps.slice(-Math.floor(W / (bw + 1))).forEach((gap, i) => {
			const bh = Math.max(1, gap[1] / max * (H - 2));
			const bunched = h.scheduledMs > 0 && gap[1] < h.scheduledMs * (sp.data.bunchingFraction || 0.5);
			g.fillStyle = bunched ? "#e5484d" : colour !== undefined ? colorHex(colour) : "#4da3ff";
			g.fillRect(1 + i * (bw + 1), H - 1 - bh, bw, bh);
		});
		if (h.scheduledMs > 0) {
			const y = H - 1 - h.scheduledMs / max * (H - 2);
			g.strokeStyle = "#d6dceb";
			g.setLineDash([3, 2]);
			g.beginPath(); g.moveTo(0, y); g.lineTo(W, y); g.stroke();
		}
	}

	/** One row per platform: due-time ticks joined to the actual departure dots, then the future. */
	function drawStrip(cv, d, routes) {
		const plats = d.platforms || [];
		const dpr = window.devicePixelRatio || 1;
		const W = cv.clientWidth || 600;
		const rowH = 26, top = 18, left = 56;
		const H = top + plats.length * rowH + 8;
		cv.width = W * dpr; cv.height = H * dpr; cv.style.height = H + "px";
		const g = cv.getContext("2d");
		g.setTransform(dpr, 0, 0, dpr, 0, 0);
		g.clearRect(0, 0, W, H);
		const t1 = now() + 30 * 60000;
		const t0 = now() - (d.windowMinutes || 60) * 60000;
		const X = (t) => left + (t - t0) / (t1 - t0) * (W - left - 8);
		g.font = "10px " + getComputedStyle(document.body).fontFamily;
		g.fillStyle = "#7d8aa5";
		for (let t = Math.ceil(t0 / 600000) * 600000; t < t1; t += 600000) {
			g.fillRect(X(t), top - 4, 1, H - top);
			g.fillText(clock(t, true), X(t) + 3, 11);
		}
		g.fillStyle = "#4da3ff";
		g.fillRect(X(now()), top - 6, 2, H - top + 2);
		const tol = (d.onTimeToleranceSeconds || 60) * 1000;
		plats.forEach((p, i) => {
			const y = top + i * rowH + rowH / 2;
			g.fillStyle = "#d6dceb";
			g.fillText(firstLang(p.name) || "?", 6, y + 3);
			g.fillStyle = "#232c3f";
			g.fillRect(left, y, W - left - 8, 1);
			for (const [t, plat, route, dev] of d.departures || []) {
				if (plat !== p.id || t < t0) continue;
				const colour = (routes.get(route) || {}).color;
				const due = t - dev;
				g.fillStyle = colour !== undefined ? colorHex(colour) : "#8a93a6";
				g.fillRect(X(due) - 0.5, y - 8, 1.5, 16);
				g.strokeStyle = "#7d8aa5";
				g.beginPath(); g.moveTo(X(due), y); g.lineTo(X(t), y); g.stroke();
				g.fillStyle = dev > tol * 3 ? "#e5484d" : dev > tol ? "#f5b942" : "#46c46e";
				g.beginPath(); g.arc(X(t), y, 3.5, 0, Math.PI * 2); g.fill();
			}
			for (const u of d.upcoming || []) {
				if (u.platformId !== p.id || u.departure > t1) continue;
				g.strokeStyle = colorHex(u.color);
				g.strokeRect(X(u.departure) - 1.5, y - 7, 3, 14);
			}
		});
	}

	/* ------------------------------------------------------- exits & access */

	function accessHtml(d) {
		const st = d.station;
		const layout = d.layout;
		const anchors = layout ? layout.anchors || [] : [];
		const names = anchorNames(d);
		let html = "";
		// step-free summary
		html += `<h3>Step-free access</h3>`;
		const manual = st.accessible;
		html += `<table class="sp-table"><thead><tr><th>Platform</th><th>Manual flag</th><th>Layout scan</th></tr></thead><tbody>`;
		for (const p of d.platforms || []) {
			const listed = !st.accessiblePlatforms || st.accessiblePlatforms.includes(p.id);
			const scan = st.layoutStepFree ? st.layoutStepFree[p.id] : undefined;
			html += `<tr><td>${escapeHtml(firstLang(p.name))}</td>
				<td>${manual ? (listed ? '<span class="ok">step-free</span>' : '<span class="sp-dim">not listed</span>') : '<span class="sp-dim">—</span>'}</td>
				<td>${scan === undefined ? '<span class="sp-dim">not scanned</span>' : scan ? '<span class="ok">step-free</span>' : '<span class="warn">stairs only</span>'}</td></tr>`;
		}
		html += `</tbody></table>`;
		if (manual) html += `<div class="sp-note">The manual flag (MTR's station screen) wins on Map+ where it is set.</div>`;
		// exits
		html += `<h3>Exits</h3>`;
		if (!(d.exits || []).length) html += `<div class="sp-empty">No exits in MTR for this station.</div>`;
		for (const e of d.exits || []) {
			const a = anchors.find((x) => x.kind === "exit" && x.name === e.name);
			const sel = sp.selectedAnchor === "exit:" + e.name ? " selected" : "";
			html += `<div class="sp-exit${sel}" data-anchor="exit:${escapeHtml(e.name)}">
				<span class="exit-badge">${escapeHtml(e.name)}</span>
				<span>${escapeHtml((e.destinations || []).map(firstLang).join(", ") || "—")}</span>
				<span class="spacer"></span>${a ? accessChip(a) : e.pins ? '<span class="sp-dim">scan to check</span>' : '<span class="sp-dim">no Exit Marker</span>'}</div>`;
		}
		// the scan's other ways in
		const others = anchors.filter((a) => a.kind === "opening" || (a.kind === "fare" && a.entrance));
		if (others.length) {
			html += `<h3>Other ways in the scan found</h3>`;
			for (const a of others) {
				const sel = sp.selectedAnchor === a.id ? " selected" : "";
				html += `<div class="sp-exit${sel}" data-anchor="${escapeHtml(a.id)}"><span>${escapeHtml(names.get(a.id) || a.id)}</span>
					<span class="sp-dim">${Math.floor(a.pos[0])} ${Math.floor(a.pos[1])} ${Math.floor(a.pos[2])}</span><span class="spacer"></span>${accessChip(a)}</div>`;
			}
		}
		// fare control
		const fares = anchors.filter((a) => a.kind === "fare");
		if (layout) {
			html += `<h3>Fare control</h3>`;
			if (!fares.length) html += `<div class="sp-empty">No fare gates found — riders walk straight to the platforms.</div>`;
			for (const a of fares) {
				const sel = sp.selectedAnchor === a.id ? " selected" : "";
				const by = (a.usedBy || []).map((u) => names.get(u) || u);
				html += `<div class="sp-exit${sel}" data-anchor="${escapeHtml(a.id)}"><span class="fare-dot"></span><span>${escapeHtml(names.get(a.id))}</span>
					<span class="sp-dim">${a.entrance ? "the way in" : by.length ? "walked through from " + escapeHtml(by.join(", ")) : "no scanned walk passes it"}</span></div>`;
			}
		}
		// transfer matrix
		const plats = d.platforms || [];
		if (layout && plats.length > 1) {
			html += `<h3>Walking between platforms</h3><div class="sp-note">Fastest walk, and the step-free way where that one takes stairs. From the layout scan.</div>
				<div class="sp-matrix-wrap"><table class="sp-table sp-matrix"><thead><tr><th></th>${plats.map((p) => `<th>${escapeHtml(firstLang(p.name))}</th>`).join("")}</tr></thead><tbody>`;
			const links = new Map((layout.links || []).map((l) => [l.from + ">" + l.to, l]));
			for (const a of plats) {
				html += `<tr><th>${escapeHtml(firstLang(a.name))}</th>`;
				for (const b of plats) {
					if (a.id === b.id) { html += `<td class="sp-dim">·</td>`; continue; }
					const l = links.get("platform:" + a.id + ">platform:" + b.id) || links.get("platform:" + b.id + ">platform:" + a.id);
					if (!l) { html += `<td class="sp-dim">—</td>`; continue; }
					const sf = l.stepFree ? l : l.stepFreeAlt;
					html += `<td title="${escapeHtml(stepsText(l.legs))}">${Math.round(l.meters)} m${l.stepFree ? " " + ACCESS_IMG : '<span class="warn"> stairs</span>'}${
						!l.stepFree && sf ? `<div class="sp-dim">${ACCESS_IMG} ${Math.round(sf.meters)} m</div>` : ""}</td>`;
				}
				html += `</tr>`;
			}
			html += `</tbody></table></div>`;
		}
		// warnings + scan status
		html += `<h3>Layout scan</h3>`;
		if (layout) {
			const ago = Math.max(0, Math.round((now() - layout.scannedAt) / 60000));
			html += `<div class="sp-note">Scanned ${ago < 1 ? "just now" : ago < 120 ? ago + " min ago" : Math.round(ago / 60) + " h ago"} · ${layout.links.length} walks${
				layout.region ? ` · ${layout.region.max[0] - layout.region.min[0] + 1} × ${layout.region.max[2] - layout.region.min[2] + 1} blocks, y ${layout.region.min[1]}–${layout.region.max[1]}` : ""}</div>`;
			for (const w of layout.warnings || []) html += `<div class="sp-warn">${escapeHtml(w)}</div>`;
		} else {
			html += `<div class="sp-note">This station hasn't been scanned, so there is no 3D view, walk times or exit check yet.</div>`;
		}
		html += scanControls(d);
		return html;
	}

	function scanControls(d) {
		const state = d.scanState || "";
		let html = `<div class="sp-scan">`;
		if (state === "scanning" || state === "queued") {
			html += `<span class="warn">${state === "scanning" ? "Scanning now…" : "Waiting for its turn to be scanned…"}</span>`;
		} else {
			html += `<button class="btn small" data-act="scan">${d.layout ? "Scan again" : "Scan this station"}</button>
				<span class="sp-dim">Operators only — this browser needs pairing (/navpair in game).</span>`;
		}
		if (state.startsWith("failed")) html += `<div class="sp-warn">${escapeHtml(state)}</div>`;
		if (sp.msg) html += `<div class="ok">${escapeHtml(sp.msg)}</div>`;
		html += `</div>`;
		return html;
	}

	/* ------------------------------------------------------------ operations */

	function opsHtml(d) {
		const plats = new Map((d.platforms || []).map((p) => [p.id, p]));
		const routes = new Map((d.routes || []).map((r) => [r.id, r]));
		let html = `<div class="sp-note">Read-only for now — editing hold rules, dwell overrides and service posters from here comes in a later round.</div>`;
		html += `<h3>Hold rules</h3>`;
		const ruled = (d.platforms || []).filter((p) => p.holdRule);
		if (!ruled.length) html += `<div class="sp-empty">No platform here holds trains for connections.</div>`;
		for (const p of ruled) {
			const r = p.holdRule;
			html += `<div class="sp-rule"><b>Platform ${escapeHtml(firstLang(p.name))}</b>${p.held ? ' <span class="badge held">HOLDING NOW</span>' : ""}
				<div class="sp-dim">waits up to ${r.seconds} s for trains due at ${r.watched.length} platform(s), plus ${r.transferSeconds} s to change</div></div>`;
		}
		html += `<h3>Dwell overrides</h3>`;
		const over = (d.platforms || []).filter((p) => p.dwellOverrides);
		if (!over.length) html += `<div class="sp-empty">Every route uses the platform's own dwell time.</div>`;
		for (const p of over) {
			html += `<div class="sp-rule"><b>Platform ${escapeHtml(firstLang(p.name))}</b> <span class="sp-dim">(platform dwell ${Math.round(p.dwellMs / 1000)} s)</span>`;
			for (const [route, ms] of Object.entries(p.dwellOverrides)) {
				html += `<div>${bullet(routes.get(route) || {})} ${Math.round(ms / 1000)} s</div>`;
			}
			html += `</div>`;
		}
		return html;
	}

	/* ------------------------------------------------------------------- 3D */

	function build3d() {
		const d = sp.data;
		const stage = $("sp3dStage");
		const empty = $("sp3dEmpty");
		if (!d || !stage) return;
		if (!d.geometry || !(d.geometry.floors || []).length) {
			if (sp.view3d) { sp.view3d.dispose(); sp.view3d = null; }
			empty.classList.remove("hidden");
			empty.innerHTML = d.layout
				? `<div><b>No 3D model yet</b><p>This station was scanned before the 3D view existed. Scan it again to build the model.</p>${scanControls(d)}</div>`
				: `<div><b>Not scanned yet</b><p>The 3D view is drawn from the station's layout scan.</p>${scanControls(d)}</div>`;
			return;
		}
		empty.classList.add("hidden");
		const go = () => {
			if (sp.view3d) { sp.view3d.update(d); return; }
			sp.view3d = window.Station3D.createView(stage, d, { hover: on3dHover, click: on3dClick });
			for (const [key, on] of Object.entries(sp.layers || {})) sp.view3d.setLayer(key, on);
			sp.view3d.setCut(sp.cut);
			if (sp.selectedAnchor) sp.view3d.highlight(sp.selectedAnchor);
		};
		if (window.Station3D) go();
		else window.addEventListener("station3d-ready", go, { once: true });
	}

	function render3dTools() {
		const el = $("sp3dTools");
		if (!el) return;
		if (!sp.layers) {
			sp.layers = {};
			const defaults = (window.Station3D && window.Station3D.LAYERS) || DEFAULT_LAYERS;
			for (const [key] of defaults) sp.layers[key] = key !== "street";
		}
		const layers = (window.Station3D && window.Station3D.LAYERS) || DEFAULT_LAYERS;
		el.innerHTML = `<div class="s3d-title">LAYERS</div>${layers.map(([key, label]) =>
			`<label><input type="checkbox" data-layer="${key}"${sp.layers[key] ? " checked" : ""}> ${label}</label>`).join("")}
			<div class="s3d-title">CUT AWAY</div>
			<input type="range" min="0" max="100" value="${Math.round(sp.cut * 100)}" data-cut title="Hide everything above this height to look into lower levels">
			<div class="s3d-legend">
				<span><i style="background:#3a6fb0"></i>paid</span><span><i style="background:#6b7489"></i>unpaid</span>
				<span><i style="background:#f5b942"></i>stairs</span><span><i style="background:#3fc1c9"></i>escalator</span>
				<span><i style="background:#3d8bff"></i>lift</span><span><i style="background:#a05cff"></i>fare gates</span>
				<span><i style="background:#46c46e"></i>step-free walk</span><span><i style="background:#f0a020"></i>walk with stairs</span>
			</div>
			<button class="btn small" data-act="frame">Reset view</button>`;
	}

	function on3dHover(info, ev) {
		const tip = $("sp3dTip");
		if (!tip) return;
		if (!info || !info.info) { tip.classList.add("hidden"); return; }
		const r = $("sp3d").getBoundingClientRect();
		tip.textContent = info.info;
		tip.style.left = (ev.clientX - r.left + 12) + "px";
		tip.style.top = (ev.clientY - r.top + 12) + "px";
		tip.classList.remove("hidden");
	}

	function on3dClick(info) {
		const anchor = info && info.anchor ? info.anchor : info && info.kind && info.kind.startsWith && info.kind.startsWith("platform:") ? info.kind : null;
		selectAnchor(anchor && anchor === sp.selectedAnchor ? null : anchor);
		if (anchor && (anchor.startsWith("exit:") || anchor.startsWith("opening:") || anchor.startsWith("fare:"))) {
			sp.tab = "access";
			render();
		}
	}

	function selectAnchor(id) {
		sp.selectedAnchor = id;
		if (sp.view3d) sp.view3d.highlight(id);
		document.querySelectorAll("#stationPage [data-anchor]").forEach((el) => el.classList.toggle("selected", el.dataset.anchor === id));
	}

	/* --------------------------------------------------------------- events */

	function onClick(ev) {
		const t = ev.target.closest("[data-act],[data-tab],[data-anchor],[data-route]");
		if (!t) return;
		if (t.dataset.tab) { sp.tab = t.dataset.tab; render(); return; }
		if (t.dataset.anchor) { selectAnchor(sp.selectedAnchor === t.dataset.anchor ? null : t.dataset.anchor); return; }
		const act = t.dataset.act;
		if (act === "close") close();
		else if (act === "refresh") load();
		else if (act === "frame") sp.view3d && sp.view3d.frame();
		else if (act === "scan") scan();
	}

	function onChange(ev) {
		const t = ev.target;
		if (t.dataset.layer) {
			sp.layers[t.dataset.layer] = t.checked;
			if (sp.view3d) sp.view3d.setLayer(t.dataset.layer, t.checked);
		} else if (t.dataset.filter) {
			sp[t.dataset.filter] = t.value;
			render();
		}
	}

	function onInput(ev) {
		if (ev.target.dataset.cut !== undefined) {
			sp.cut = Number(ev.target.value) / 100;
			if (sp.view3d) sp.view3d.setCut(sp.cut);
		}
	}

	async function scan() {
		sp.msg = "";
		sp.err = "";
		let token = readToken();
		if (!token) {
			const code = prompt("Pair this browser first: run /navpair in game and type the six-character code here.");
			if (!code) return;
			const res = await post("pair", { code: String(code).toUpperCase().replace(/[^A-Z0-9]/g, ""), label: "Dispatch — Station page" });
			if (!res || !res.ok || !res.token) { sp.err = (res && res.error) || "Pairing failed."; render(); return; }
			writeToken(res.token, res.player);
			token = res.token;
		}
		const res = await post("stationscan", { token, station: sp.id });
		if (res && res.ok) {
			sp.msg = "Scan queued by " + res.player + " — the 3D view appears when it is done.";
			setTimeout(load, 2500);
			setTimeout(load, 8000);
		} else {
			sp.err = (res && res.error) || "Could not start the scan.";
		}
		render();
	}

	async function post(path, obj) {
		if (DEMO) return { ok: false, error: "Demo mode — there is no server to scan with." };
		try {
			const body = await (await fetch(`${API}/${path}`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(obj) })).json();
			return body && body.data ? body.data : body;
		} catch (e) { return { ok: false, error: "Could not reach the dispatch server." }; }
	}

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
	}

	document.addEventListener("keydown", (ev) => {
		if (sp.open && ev.key === "Escape" && !ev.target.closest("input,select,textarea")) { close(); ev.stopPropagation(); }
	}, true);

	/* --------------------------------------------------------- side panel */

	/**
	 * Extra rows for app.js's station side panel (the quick look): an "Open station page"
	 * button, the next departure per platform and the exits' step-free verdicts. Fetched in
	 * the background and cached 15 s; the panel re-renders when it lands.
	 */
	function panelHtml(st) {
		const cached = sp.panel.get(st.id);
		if (!cached || Date.now() - cached.at > PANEL_TTL_MS) {
			if (!cached || !cached.loading) {
				sp.panel.set(st.id, { ...(cached || {}), loading: true, at: cached ? cached.at : 0 });
				fetchStation(st.id).then((data) => {
					if (data && !data.error) sp.panel.set(st.id, { at: Date.now(), data });
					if (typeof updateDetail === "function") updateDetail();
				}).catch(() => sp.panel.set(st.id, { at: Date.now(), data: cached && cached.data }));
			}
		}
		let html = `<button class="btn sp-openbtn" data-open-station="${escapeHtml(st.id)}">Open station page ⤢</button>`;
		const d = cached && cached.data;
		if (!d) return html;
		if (!sp.open) buildLabels(d.routes);
		const routes = new Map((d.routes || []).map((r) => [r.id, r]));
		const next = new Map();
		for (const u of d.upcoming || []) if (!next.has(u.platformId)) next.set(u.platformId, u);
		if (next.size) {
			html += `<div class="sta-section">NEXT DEPARTURES</div>`;
			for (const p of d.platforms || []) {
				const u = next.get(p.id);
				if (!u) continue;
				html += `<div class="sta-plat"><span class="pname">${escapeHtml(firstLang(p.name))}</span>${bullet(routes.get(u.routeId) || { number: u.routeNumber, color: u.color })}
					<span>${escapeHtml(firstLang(u.dest))}</span><span class="dwell" data-due="${u.departure}">${dueText(u.departure)}</span></div>`;
			}
		}
		const exits = (d.layout && d.layout.anchors || []).filter((a) => a.kind === "exit" && a.stepFree);
		if (exits.length) {
			html += `<div class="sta-section">EXITS</div><div class="sp-mini-exits">${exits.map((a) =>
				`<span title="${escapeHtml(accessText(a))}"><span class="exit-badge">${escapeHtml(a.name)}</span>${a.stepFree === "all" ? ACCESS_IMG : a.stepFree === "some" ? '<span class="warn">½</span>' : '<span class="sp-dim">stairs</span>'}${a.lift ? '<span class="sp-dim">lift</span>' : ""}</span>`).join("")}</div>`;
		}
		const worst = (d.headways || []).filter((h) => h.scheduledMs > 0 && h.averageMs)
			.map((h) => ({ h, off: (h.averageMs - h.scheduledMs) / h.scheduledMs })).sort((a, b) => b.off - a.off)[0];
		if (worst && worst.off > 0.1) {
			const r = routes.get(worst.h.routeId) || {};
			html += `<div class="sta-inbound"><span class="warn">${bullet(r)} gaps ${Math.round(worst.off * 100)}% longer than scheduled</span></div>`;
		}
		return html;
	}

	// the side panel's countdowns tick too (the page has its own ticker)
	setInterval(() => {
		if (sp.open) return;
		document.querySelectorAll("#detail [data-due]").forEach((el) => { el.textContent = dueText(Number(el.dataset.due)); });
	}, 1000);

	document.addEventListener("click", (ev) => {
		const b = ev.target.closest && ev.target.closest("[data-open-station]");
		if (b) open(b.dataset.openStation);
	});

	/* ------------------------------------------------------------- helpers */

	const DEFAULT_LAYERS = [["platforms", "Platforms"], ["concourse", "Concourse (paid / unpaid)"], ["stairs", "Stairs & escalators"],
		["lifts", "Lifts"], ["fare", "Fare gates"], ["exits", "Exits & entrances"], ["walks", "Walking routes"],
		["track", "Track"], ["street", "Street level"], ["labels", "Labels"]];

	const DIRECTION_WORDS = /^(in|out|inbound|outbound|nb|sb|eb|wb|north|south|east|west|cw|ccw|up|down|loop)$/i;
	const GENERIC_WORDS = /^(line|light|rail|railway|metro|subway|tram|bus|ferry|express|local|service|the)$/i;
	let labels = new Map();   // line key -> bullet text, unique within the station

	/**
	 * Bullet text per line, like Map+: the route number when it is a real one, else the
	 * shortest prefix of the line name's first meaningful word that no other line here uses
	 * (worlds that put the DIRECTION in the number field — "IN" / "OUT" — get Br / F / V).
	 */
	function buildLabels(routes) {
		labels = new Map();
		const used = new Set();
		const lines = uniqueLines(routes || []).concat((routes || []).filter((r) => r.hidden));
		for (const r of lines) {
			const key = lineKeyOf(r);
			if (labels.has(key)) continue;
			const number = String(r.number || "").trim();
			if (number && number.length <= 3 && !DIRECTION_WORDS.test(number)) { labels.set(key, number); used.add(number); continue; }
			const word = lineName(r).split(/\s+/).find((w) => w && !GENERIC_WORDS.test(w)) || lineName(r) || "?";
			let label = word.slice(0, 1).toUpperCase();
			for (let n = 2; used.has(label) && n <= word.length; n++) label = word.slice(0, 1).toUpperCase() + word.slice(1, n).toLowerCase();
			labels.set(key, label);
			used.add(label);
		}
	}

	function bullet(r) {
		if (!r) return "";
		const label = labels.get(lineKeyOf(r)) || r.number || (firstLang(r.name) || "?").slice(0, 2);
		return `<span class="chip sp-chip" style="background:${r.color !== undefined ? colorHex(r.color) : "#5b6981"}" title="${escapeHtml(firstLang(r.name) || "")}">${escapeHtml(label)}</span>`;
	}
	function lineKeyOf(r) {
		const number = String(r.number || "").trim();
		return String(r.name || "").split("||")[0] + "#" + (DIRECTION_WORDS.test(number) ? "" : number);
	}
	function uniqueLines(routes) {
		const seen = new Set();
		return routes.filter((r) => !r.hidden && !seen.has(lineKeyOf(r)) && seen.add(lineKeyOf(r)));
	}
	function lineName(r) { return firstLang(String(r.name || "").split("||")[0]) || r.number || "?"; }
	function clock(t, short) {
		const d = new Date(t);
		const hh = String(d.getHours()).padStart(2, "0"), mm = String(d.getMinutes()).padStart(2, "0");
		return short ? hh + ":" + mm : hh + ":" + mm + ":" + String(d.getSeconds()).padStart(2, "0");
	}
	function dueText(t) {
		const s = Math.round((t - now()) / 1000);
		if (s <= 20) return "now";
		if (s < 60) return s + " s";
		return Math.round(s / 60) + " min";
	}
	function fmtMs(ms) {
		const s = Math.round(ms / 1000);
		return s < 90 ? s + " s" : Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0");
	}
	function platformStepFree(d, platformId) {
		const st = d.station;
		if (st.accessible && (!st.accessiblePlatforms || st.accessiblePlatforms.includes(platformId))) return true;
		return !!(st.layoutStepFree && st.layoutStepFree[platformId]);
	}
	function accessText(a) {
		if (a.stepFree === "all") return "step-free to every platform" + (a.lift ? ", by lift" : "");
		if (a.stepFree === "some") return "step-free to some platforms" + (a.lift ? ", by lift" : "");
		if (a.stepFree === "none") return "stairs or escalator only";
		return "reaches no platform";
	}
	function accessChip(a) {
		const cls = a.stepFree === "all" ? "ok" : a.stepFree === "some" ? "warn" : a.stepFree === "none" ? "bad" : "sp-dim";
		const icon = a.stepFree === "all" || a.stepFree === "some" ? ACCESS_IMG : "";
		return `<span class="${cls}">${icon} ${escapeHtml(accessText(a))}</span>`;
	}
	function anchorNames(d) {
		const names = new Map();
		const anchors = (d.layout && d.layout.anchors) || [];
		const count = (k) => anchors.filter((a) => a.kind === k).length;
		let opening = 0, fare = 0;
		const plats = new Map((d.platforms || []).map((p) => [p.id, p]));
		for (const a of anchors) {
			if (a.kind === "exit") names.set(a.id, "Exit " + a.name);
			else if (a.kind === "platform") names.set(a.id, "Platform " + firstLang((plats.get(a.id.slice(9)) || {}).name || a.name));
			else if (a.kind === "opening") names.set(a.id, "Street entrance" + (count("opening") > 1 ? " " + ++opening : ""));
			else if (a.kind === "fare") names.set(a.id, "Fare control" + (count("fare") > 1 ? " " + ++fare : ""));
		}
		return names;
	}
	function stepsText(legs) {
		return (legs || []).map((g) => {
			const dy = Math.round(g.dy || 0);
			const arrow = dy > 0 ? " ↑" + dy : dy < 0 ? " ↓" + (-dy) : "";
			return g.kind === "walk" ? Math.round(g.meters) + " m" : g.kind === "fare" ? "fare control"
				: g.kind === "emergency" ? "emergency door" : g.kind + arrow;
		}).join(" · ");
	}

	/* ---------------------------------------------------------------- demo */

	/** ?demo=1: a two-level station with a fare line, stairs, a lift and two exits. */
	function demoStation(id) {
		const st = state.stations.find((s) => s.id === id) || state.stations[0];
		const t = Date.now();
		const floors = [];
		const rect = (kind, x0, z0, x1, z1, y) => floors.push([kind, x0, z0, x1, z1, y]);
		// street 70, concourse 64 (unpaid west of x 50, paid east), platforms 58 either side of the track at z 0
		rect("free", 30, -8, 50, 8, 64);
		rect("fare:0", 50, -3, 51, 3, 64);
		rect("paid", 51, -8, 80, 8, 64);
		rect("platform:pl1", 30, 1.5, 90, 5, 58);
		rect("platform:pl1", 30, -5, 90, -1.5, 58);
		for (let i = 0; i < 12; i++) rect("stairs", 60 + i * 0.5, 5, 60.5 + i * 0.5, 7, 58 + i * 0.5);
		for (let i = 0; i < 12; i++) rect("stairs", 30 + i * 0.5, 9, 30.5 + i * 0.5, 11, 64 + i * 0.5);
		for (let i = 0; i < 12; i++) rect("escalator", 70 + i * 0.5, -7, 70.5 + i * 0.5, -5, 58 + i * 0.5);
		const path = (pts) => pts;
		return {
			now: t,
			station: { id: st.id, name: st.name, color: st.color, bounds: st.bounds, zone: 1, accessible: false,
				layoutStepFree: { pl1: true } },
			platforms: [{ id: "pl1", name: "1", dwellMs: 10000, mid: [60, 58, 0], routeIds: ["rt1n"], held: false,
				holdRule: { watched: ["pl2"], seconds: 45, transferSeconds: 20 }, dwellOverrides: { rt1n: 20000 } }],
			routes: [{ id: "rt1n", name: "Demo Express|演示||Northbound", number: "4", color: 0x00933c, hidden: false,
				schedHeadwayMs: 300000, prev: "Harbor North", next: "Harbor North", terminus: "Harbor North", stopIndex: 0 }],
			upcoming: [0, 1, 2, 3].map((k) => ({ platformId: "pl1", routeId: "rt1n", routeName: "Demo Express", routeNumber: "4",
				color: 0x00933c, dest: "Harbor North", arrival: t + 60000 + k * 300000, departure: t + 70000 + k * 300000,
				deviation: k === 0 ? 90000 : 0, realtime: k < 2, terminating: false, cars: 6 })),
			analyticsEnabled: true, windowMinutes: 60, onTimeToleranceSeconds: 60, bunchingFraction: 0.5,
			departures: [...Array(11)].map((_, k) => [t - 30000 - k * 290000 - (k % 3) * 60000, "pl1", "rt1n", (k % 4) * 45000, 12000 + k * 900, 10000, "v" + k, 0]),
			headways: [{ routeId: "rt1n", platformId: "pl1", scheduledMs: 300000, departures: 11, lastDeparture: t - 30000,
				averageMs: 330000, minMs: 120000, maxMs: 470000, bunched: 1,
				gaps: [...Array(10)].map((_, k) => [t - k * 300000, [300000, 360000, 120000, 470000, 310000][k % 5]]) }],
			exits: [{ name: "A", destinations: ["Main St"], pins: [[31, 70, 10]] }, { name: "B", destinations: ["Baker Plaza"], pins: [[86, 70, -6]] }],
			scanState: "",
			layout: {
				scannedAt: t - 600000, region: { min: [10, 52, -30], max: [110, 78, 30], margin: 24 },
				anchors: [
					{ id: "platform:pl1", kind: "platform", name: "1", pos: [60, 58, 0] },
					{ id: "fare:0", kind: "fare", name: "", pos: [50.5, 64, 0], usedBy: ["exit:A"] },
					{ id: "exit:A", kind: "exit", name: "A", pos: [31.5, 70, 10.5], stepFree: "none", reaches: ["pl1"] },
					{ id: "exit:B", kind: "exit", name: "B", pos: [86.5, 70, -5.5], stepFree: "all", lift: true, stepFreeTo: ["pl1"], reaches: ["pl1"] },
				],
				links: [
					{ from: "exit:A", to: "platform:pl1", meters: 48, extraSeconds: 3, stepFree: false,
						legs: [{ kind: "stairs", meters: 6, dy: -6 }, { kind: "walk", meters: 18, dy: 0 }, { kind: "fare", meters: 0, dy: 0, at: "fare:0" }, { kind: "walk", meters: 10, dy: 0 }, { kind: "stairs", meters: 6, dy: -6 }],
						path: path([[31.5, 70, 10.5], [36, 64, 10], [45, 64, 4], [50.5, 64, 0], [58, 64, 6], [60, 64, 6], [66, 58, 6], [66, 58, 3]]) },
					{ from: "exit:B", to: "platform:pl1", meters: 12, extraSeconds: 14, stepFree: true,
						legs: [{ kind: "walk", meters: 3, dy: 0 }, { kind: "lift", meters: 0, dy: -12, seconds: 14 }, { kind: "walk", meters: 4, dy: 0 }],
						path: path([[86.5, 70, -5.5], [84, 70, -3], [84, 58, -3], [80, 58, -3]]) },
				],
				warnings: ["Demo: Exit A is stairs only."],
			},
			geometry: { street: 70, floors, lifts: [{ id: "L1", x: 84, z: -3, floors: [58, 64, 70] }],
				track: [[[0, 58, -0.0], [120, 58, 0]]], rects: floors.length },
		};
	}

	// double-clicking a station on the map opens its page (the first click selected it)
	window.addEventListener("load", () => {
		const map = document.getElementById("map");
		if (map) map.addEventListener("dblclick", () => { if (state.selectedStation && !state.selected) open(state.selectedStation); });
	});

	// a #station=<id> link opens straight into the page once the network is loaded
	window.addEventListener("load", () => {
		const m = /#station=([^&]+)/.exec(location.hash);
		if (!m) return;
		const id = decodeURIComponent(m[1]);
		const tryOpen = (n) => {
			if (state.stations.some((s) => s.id === id)) open(id);
			else if (n > 0) setTimeout(() => tryOpen(n - 1), 500);
		};
		tryOpen(20);
	});

	return { open, close, panelHtml, isOpen: () => sp.open };
})();
