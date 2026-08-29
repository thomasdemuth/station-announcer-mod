/* Shared stub-DOM rig (extracted from harness.mjs): runs map.js in a vm with a stub DOM (?demo=1 path, no fetch/SSE)
 * and exercises the pure geometry / graph functions. */
import { readFileSync } from "node:fs";
import vm from "node:vm";

const MAPJS = process.env.MAPJS || process.argv[2] || "/Users/thomasdemuth/Documents/Station Announcer Mod/src/main/resources/assets/station_announcer/dispatch/map.js";
const src = readFileSync(MAPJS, "utf8");

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

export { run, ctxObj };
