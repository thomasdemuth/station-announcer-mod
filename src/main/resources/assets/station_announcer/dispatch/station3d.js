/*
 * The station page's 3D SCHEMATIC (STATION_PAGE_PLAN.md, Thomas 2026-10-01: "clean schematic",
 * built from the layout scan). An ES module on top of the bundled three.js (vendor/three, MIT);
 * station.js (a classic script) reaches it through window.Station3D.
 *
 * Input = the /api/station payload: `geometry` (floors as [kind, x0, z0, x1, z1, y] rectangles —
 * kinds platform:<id> / paid / free / stairs / escalator / fare:<n> / gate — lift shafts, track
 * polylines, street level), `layout` (anchors, walks with paths), `exits` (MTR exits + pins),
 * `platforms`, `routes`. World coordinates map straight onto three.js (Minecraft is right-handed
 * with y up too), re-centred on the station.
 */
import * as THREE from "./vendor/three/three.module.min.js";
import { OrbitControls } from "./vendor/three/OrbitControls.js";

const KIND_COLOURS = {
	paid: 0x3a6fb0,
	free: 0x6b7489,
	stairs: 0xf5b942,
	escalator: 0x3fc1c9,
	fare: 0xa05cff,
	gate: 0xa05cff,
};
const ACCESS_COLOURS = { all: 0x46c46e, some: 0xf2d43d, none: 0xe5484d };
const LIFT_COLOUR = 0x3d8bff;
const TRACK_COLOUR = 0x8a93a6;

/** Which layer a floor kind belongs to (the toggles). */
function layerOf(kind) {
	if (kind.startsWith("platform:")) return "platforms";
	if (kind === "paid" || kind === "free") return "concourse";
	if (kind === "stairs" || kind === "escalator") return "stairs";
	return "fare";
}

export const LAYERS = [
	["platforms", "Platforms"], ["concourse", "Concourse (paid / unpaid)"], ["stairs", "Stairs & escalators"],
	["lifts", "Lifts"], ["fare", "Fare gates"], ["exits", "Exits & entrances"], ["walks", "Walking routes"],
	["track", "Track"], ["street", "Street level"], ["labels", "Labels"],
];

export function createView(container, payload, callbacks = {}) {
	const renderer = new THREE.WebGLRenderer({ antialias: true });
	renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
	renderer.localClippingEnabled = true;
	renderer.setClearColor(0x0b0e14, 1);
	container.appendChild(renderer.domElement);
	renderer.domElement.className = "s3d-canvas";

	const labelLayer = document.createElement("div");
	labelLayer.className = "s3d-labels";
	container.appendChild(labelLayer);

	const scene = new THREE.Scene();
	scene.add(new THREE.HemisphereLight(0xdfe8ff, 0x1a1f2b, 1.1));
	const sun = new THREE.DirectionalLight(0xffffff, 1.4);
	sun.position.set(40, 80, 30);
	scene.add(sun);

	const camera = new THREE.PerspectiveCamera(45, 1, 0.1, 4000);
	const controls = new OrbitControls(camera, renderer.domElement);
	controls.enableDamping = true;
	controls.dampingFactor = 0.12;
	controls.screenSpacePanning = true;

	// the cut-away: everything above this height is clipped off (look into lower levels)
	const cut = new THREE.Plane(new THREE.Vector3(0, -1, 0), 1e6);
	const clip = [cut];

	const groups = {};
	for (const [key] of LAYERS) {
		groups[key] = new THREE.Group();
		scene.add(groups[key]);
	}
	const labels = [];          // {el, pos:Vector3, layer}
	const pickables = [];       // meshes the raycaster may hit
	const highlightGroup = new THREE.Group();
	scene.add(highlightGroup);
	let centre = new THREE.Vector3();
	let yRange = [0, 0];
	let disposed = false;
	let current = null;

	function mat(colour, opts = {}) {
		return new THREE.MeshStandardMaterial({
			color: colour, roughness: 0.75, metalness: 0.05, clippingPlanes: clip,
			transparent: !!opts.opacity, opacity: opts.opacity ?? 1, depthWrite: opts.opacity ? false : true,
			side: opts.double ? THREE.DoubleSide : THREE.FrontSide,
		});
	}
	function lineMat(colour, opts = {}) {
		return new THREE.LineBasicMaterial({ color: colour, clippingPlanes: clip, transparent: !!opts.opacity,
			opacity: opts.opacity ?? 1 });
	}
	function v(p) { return new THREE.Vector3(p[0] - centre.x, p[1] - centre.y, p[2] - centre.z); }
	function addLabel(text, p, layer, cls) {
		const el = document.createElement("div");
		el.className = "s3d-label" + (cls ? " " + cls : "");
		el.textContent = text;
		labelLayer.appendChild(el);
		labels.push({ el, pos: p.clone ? p.clone() : v(p), layer });
	}

	function clear() {
		for (const g of Object.values(groups)) {
			for (const child of [...g.children]) {
				g.remove(child);
				child.geometry?.dispose?.();
			}
		}
		highlightGroup.clear();
		pickables.length = 0;
		labels.forEach((l) => l.el.remove());
		labels.length = 0;
	}

	function build(data) {
		clear();
		current = data;
		const geo = data.geometry || {};
		const st = data.station || {};
		const floors = geo.floors || [];
		// centre: the station area, or the floors' extent
		let minX = Infinity, minZ = Infinity, maxX = -Infinity, maxZ = -Infinity, minY = Infinity, maxY = -Infinity;
		for (const f of floors) {
			minX = Math.min(minX, f[1]); maxX = Math.max(maxX, f[3]);
			minZ = Math.min(minZ, f[2]); maxZ = Math.max(maxZ, f[4]);
			minY = Math.min(minY, f[5]); maxY = Math.max(maxY, f[5]);
		}
		if (!isFinite(minX) && st.bounds) {
			[minX, minY, minZ, maxX, maxY, maxZ] = st.bounds;
		}
		if (geo.street != null) { minY = Math.min(minY, geo.street); maxY = Math.max(maxY, geo.street); }
		centre.set((minX + maxX) / 2, isFinite(minY) ? minY : 0, (minZ + maxZ) / 2);
		yRange = [isFinite(minY) ? minY : 0, isFinite(maxY) ? maxY + 4 : 8];

		// --- floors: one instanced unit box per kind (platforms get their route colour)
		const platformColour = new Map();
		const routeById = new Map((data.routes || []).map((r) => [r.id, r]));
		for (const p of data.platforms || []) {
			const r = routeById.get((p.routeIds || [])[0]);
			platformColour.set("platform:" + p.id, r ? r.color : 0x46c46e);
		}
		const byKind = new Map();
		for (const f of floors) {
			if (!byKind.has(f[0])) byKind.set(f[0], []);
			byKind.get(f[0]).push(f);
		}
		const box = new THREE.BoxGeometry(1, 1, 1);
		const m4 = new THREE.Matrix4();
		for (const [kind, list] of byKind) {
			const layer = layerOf(kind);
			const colour = kind.startsWith("platform:") ? (platformColour.get(kind) ?? 0x46c46e)
				: kind.startsWith("fare:") ? KIND_COLOURS.fare : (KIND_COLOURS[kind] ?? 0x6b7489);
			const thick = layer === "fare" ? 1.2 : 0.18;
			const mesh = new THREE.InstancedMesh(box, mat(colour, kind === "free" ? { opacity: 0.55 } : {}), list.length);
			list.forEach((f, i) => {
				const w = Math.max(0.05, f[3] - f[1]);
				const d = Math.max(0.05, f[4] - f[2]);
				const y = layer === "fare" ? f[5] + thick / 2 : f[5] - thick / 2;
				m4.makeScale(w, thick, d);
				m4.setPosition((f[1] + f[3]) / 2 - centre.x, y - centre.y, (f[2] + f[4]) / 2 - centre.z);
				mesh.setMatrixAt(i, m4);
			});
			mesh.userData = { kind, info: kindInfo(kind, data) };
			groups[layer].add(mesh);
			pickables.push(mesh);
		}

		// --- platform labels at their midpoints
		for (const p of data.platforms || []) {
			if (!p.mid) continue;
			addLabel(firstLangLocal(p.name) ? "Platform " + firstLangLocal(p.name) : "Platform", v([p.mid[0], p.mid[1] + 1.6, p.mid[2]]), "platforms", "plat");
		}

		// --- lift shafts
		for (const lift of geo.lifts || []) {
			const ys = (lift.floors || []).slice().sort((a, b) => a - b);
			if (!ys.length) continue;
			const y0 = ys[0], y1 = ys[ys.length - 1] + 2.4;
			const shaft = new THREE.Mesh(new THREE.BoxGeometry(1.6, y1 - y0, 1.6), mat(LIFT_COLOUR, { opacity: 0.35 }));
			shaft.position.set(lift.x - centre.x, (y0 + y1) / 2 - centre.y, lift.z - centre.z);
			shaft.userData = { kind: "lift", info: "Lift · " + ys.length + " floors" };
			groups.lifts.add(shaft);
			pickables.push(shaft);
			for (const y of ys) {
				const ring = new THREE.Mesh(new THREE.BoxGeometry(1.9, 0.12, 1.9), mat(LIFT_COLOUR));
				ring.position.set(lift.x - centre.x, y - centre.y + 0.06, lift.z - centre.z);
				groups.lifts.add(ring);
			}
			addLabel("Lift", v([lift.x, y1 + 0.6, lift.z]), "lifts", "lift");
		}

		// --- track
		for (const line of geo.track || []) {
			const pts = line.map((p) => v([p[0], p[1] + 0.1, p[2]]));
			groups.track.add(new THREE.Line(new THREE.BufferGeometry().setFromPoints(pts), lineMat(TRACK_COLOUR)));
		}

		// --- street level: a faint plane + grid over the floors' extent
		if (geo.street != null && isFinite(minX)) {
			const w = maxX - minX + 16, d = maxZ - minZ + 16;
			const plane = new THREE.Mesh(new THREE.PlaneGeometry(w, d), mat(0x2a3346, { opacity: 0.25, double: true }));
			plane.rotation.x = -Math.PI / 2;
			plane.position.set(0, geo.street - centre.y - 0.02, 0);
			plane.userData = { kind: "street", info: "Street level · y " + Math.round(geo.street) };
			groups.street.add(plane);
			const grid = new THREE.GridHelper(Math.max(w, d), Math.max(4, Math.round(Math.max(w, d) / 8)), 0x3a4560, 0x2a3346);
			grid.position.set(0, geo.street - centre.y, 0);
			grid.material.transparent = true;
			grid.material.opacity = 0.35;
			grid.material.clippingPlanes = clip;
			groups.street.add(grid);
		}

		// --- exits (pins) and the scan's other ways in (openings, fare entrances)
		const anchors = (data.layout && data.layout.anchors) || [];
		const exitAccess = new Map();   // "A" -> best access of its anchors
		for (const a of anchors) {
			if (a.kind === "exit" && a.stepFree) exitAccess.set(a.name, a.stepFree);
		}
		const posts = [];
		for (const e of data.exits || []) {
			for (const pin of e.pins || []) posts.push({ kind: "exit", name: e.name, pos: pin, access: exitAccess.get(e.name), lift: anchors.some((a) => a.kind === "exit" && a.name === e.name && a.lift) });
		}
		let opening = 0;
		for (const a of anchors) {
			if (a.kind === "opening") posts.push({ kind: "opening", name: "Street entrance " + (++opening), pos: a.pos, access: a.stepFree, anchor: a.id });
			if (a.kind === "fare") posts.push({ kind: "fare", name: "Fare control", pos: a.pos, access: a.entrance ? a.stepFree : null, anchor: a.id });
		}
		for (const p of posts) {
			const colour = p.kind === "exit" ? 0xf2f2f2 : p.kind === "fare" ? 0xa05cff : 0x9aa0a6;
			const post = new THREE.Mesh(new THREE.CylinderGeometry(0.18, 0.18, 2.6, 10), mat(colour));
			const base = v(p.pos);
			post.position.set(base.x, base.y + 1.3, base.z);
			const name = p.kind === "exit" ? "Exit " + p.name : p.name;
			post.userData = { kind: p.kind, info: name + (p.access ? " · " + accessText(p.access, p.lift) : ""), exit: p.kind === "exit" ? p.name : null, anchor: p.anchor || (p.kind === "exit" ? "exit:" + p.name : null) };
			groups.exits.add(post);
			pickables.push(post);
			if (p.access) {
				const cap = new THREE.Mesh(new THREE.SphereGeometry(0.42, 14, 10), mat(ACCESS_COLOURS[p.access] ?? 0xffffff));
				cap.position.set(base.x, base.y + 2.9, base.z);
				cap.userData = post.userData;
				groups.exits.add(cap);
				pickables.push(cap);
				if (p.lift) {
					const band = new THREE.Mesh(new THREE.CylinderGeometry(0.3, 0.3, 0.3, 10), mat(LIFT_COLOUR));
					band.position.set(base.x, base.y + 2.3, base.z);
					groups.exits.add(band);
				}
			}
			if (p.kind !== "fare") addLabel(p.kind === "exit" ? p.name : "↗", new THREE.Vector3(base.x, base.y + 3.7, base.z), "exits", p.kind);
		}

		// --- walking routes
		for (const link of (data.layout && data.layout.links) || []) {
			addWalk(link, link.stepFree ? 0x46c46e : 0xf0a020, 0.05);
			if (link.stepFreeAlt) addWalk(link.stepFreeAlt, 0x3d8bff, 0.18, link);
		}

		frame();
		setCut(1);
	}

	function addWalk(link, colour, lift, parent) {
		if (!link.path || link.path.length < 2) return;
		const pts = link.path.map((p) => v([p[0], p[1] + lift, p[2]]));
		const line = new THREE.Line(new THREE.BufferGeometry().setFromPoints(pts), lineMat(colour, { opacity: 0.9 }));
		line.userData = { from: (parent || link).from, to: (parent || link).to };
		groups.walks.add(line);
	}

	/** Point the camera at the whole station from the south-east, above. */
	function frame() {
		const boxAll = new THREE.Box3();
		for (const key of ["platforms", "concourse", "stairs", "lifts", "exits"]) boxAll.expandByObject(groups[key]);
		if (boxAll.isEmpty()) boxAll.setFromCenterAndSize(new THREE.Vector3(), new THREE.Vector3(40, 10, 40));
		const mid = boxAll.getCenter(new THREE.Vector3());
		const radius = Math.max(10, boxAll.getBoundingSphere(new THREE.Sphere()).radius);
		// far enough that the bounding sphere fits the narrower of the two view angles
		const vfov = THREE.MathUtils.degToRad(camera.fov) / 2;
		const hfov = Math.atan(Math.tan(vfov) * camera.aspect);
		const dist = radius / Math.sin(Math.min(vfov, hfov)) * 1.02;
		const dir = new THREE.Vector3(0.62, 0.62, 0.48).normalize();
		camera.position.copy(mid).addScaledVector(dir, dist);
		camera.near = Math.max(0.1, dist / 500);
		camera.far = dist * 20;
		camera.updateProjectionMatrix();
		controls.target.copy(mid);
		controls.update();
	}

	/** 0..1 of the station's height range: 1 = nothing cut away. */
	function setCut(fraction) {
		const y = yRange[0] + (yRange[1] - yRange[0]) * fraction;
		cut.constant = fraction >= 0.999 ? 1e6 : (y - centre.y) + 0.5;
		return Math.round(y);
	}

	function setLayer(key, on) {
		if (groups[key]) groups[key].visible = on;
		if (key === "labels") labelLayer.style.display = on ? "" : "none";
	}

	/** Light up the walks of one anchor ("exit:A", "fare:0", "platform:<id>"), dim the rest; null = clear. */
	function highlight(anchorId) {
		for (const line of groups.walks.children) {
			const hit = !anchorId || line.userData.from === anchorId || line.userData.to === anchorId;
			line.material.opacity = hit ? 1 : 0.12;
			line.material.linewidth = 1;
		}
		highlightGroup.clear();
		if (!anchorId || !current) return;
		// a pulse ring at the anchor
		const a = ((current.layout && current.layout.anchors) || []).find((x) => x.id === anchorId);
		let pos = a && a.pos;
		if (!pos && anchorId.startsWith("exit:")) {
			const e = (current.exits || []).find((x) => "exit:" + x.name === anchorId);
			pos = e && e.pins && e.pins[0];
		}
		if (!pos) return;
		const ring = new THREE.Mesh(new THREE.TorusGeometry(1.4, 0.12, 8, 32),
			new THREE.MeshBasicMaterial({ color: 0xffffff }));
		ring.rotation.x = Math.PI / 2;
		const p = v(pos);
		ring.position.set(p.x, p.y + 0.15, p.z);
		highlightGroup.add(ring);
	}

	// --- picking: hover tooltip + click callback
	const raycaster = new THREE.Raycaster();
	const mouse = new THREE.Vector2();
	let downAt = null;
	function pick(ev) {
		const r = renderer.domElement.getBoundingClientRect();
		mouse.x = ((ev.clientX - r.left) / r.width) * 2 - 1;
		mouse.y = -((ev.clientY - r.top) / r.height) * 2 + 1;
		raycaster.setFromCamera(mouse, camera);
		const visible = pickables.filter((m) => {
			for (let o = m; o; o = o.parent) if (o.visible === false) return false;
			return true;
		});
		for (const hit of raycaster.intersectObjects(visible, false)) {
			// ignore what the cut-away has removed
			if (hit.point.y > -cut.constant + 0.01 && cut.constant < 1e5) continue;
			return hit.object.userData;
		}
		return null;
	}
	renderer.domElement.addEventListener("pointermove", (ev) => {
		const info = pick(ev);
		callbacks.hover?.(info, ev);
	});
	renderer.domElement.addEventListener("pointerdown", (ev) => { downAt = [ev.clientX, ev.clientY]; });
	renderer.domElement.addEventListener("pointerup", (ev) => {
		if (!downAt || Math.hypot(ev.clientX - downAt[0], ev.clientY - downAt[1]) > 4) return;
		callbacks.click?.(pick(ev));
	});

	// --- size + loop
	function resize() {
		const w = container.clientWidth || 1, h = container.clientHeight || 1;
		renderer.setSize(w, h, false);
		renderer.domElement.style.width = w + "px";
		renderer.domElement.style.height = h + "px";
		camera.aspect = w / h;
		// the layer panel covers the left ~210 px: draw the scene centred in what is left
		const shift = w > 700 ? 105 : 0;
		if (shift) camera.setViewOffset(w, h, -shift, 0, w, h);
		else camera.clearViewOffset();
		camera.updateProjectionMatrix();
	}
	const observer = new ResizeObserver(resize);
	observer.observe(container);
	resize();

	const tmp = new THREE.Vector3();
	function loop() {
		if (disposed) return;
		requestAnimationFrame(loop);
		controls.update();
		renderer.render(scene, camera);
		if (labelLayer.style.display !== "none") {
			// declutter: nearest labels first; one that would overlap a placed one is hidden
			const w = container.clientWidth, h = container.clientHeight;
			const placed = [];
			const order = [];
			for (const l of labels) {
				const visible = groups[l.layer]?.visible !== false && !(cut.constant < 1e5 && l.pos.y > -cut.constant);
				tmp.copy(l.pos).project(camera);
				if (!visible || tmp.z > 1 || tmp.z < -1) { l.el.style.display = "none"; continue; }
				order.push({ l, x: (tmp.x + 1) / 2 * w, y: (1 - tmp.y) / 2 * h, z: tmp.z });
			}
			order.sort((a, b) => a.z - b.z);
			for (const o of order) {
				const lw = o.l.width || (o.l.width = o.l.el.offsetWidth || 60);
				const box = [o.x - lw / 2 - 2, o.y - 18, o.x + lw / 2 + 2, o.y];
				if (placed.some((p) => box[0] < p[2] && box[2] > p[0] && box[1] < p[3] && box[3] > p[1])) {
					o.l.el.style.display = "none";
					continue;
				}
				placed.push(box);
				o.l.el.style.display = "";
				o.l.el.style.transform = `translate(${o.x.toFixed(1)}px, ${o.y.toFixed(1)}px) translate(-50%, -100%)`;
			}
		}
	}
	build(payload);
	loop();

	return {
		update(data) { build(data); },
		setLayer, setCut, highlight, frame,
		dispose() {
			disposed = true;
			observer.disconnect();
			clear();
			controls.dispose();
			renderer.dispose();
			renderer.domElement.remove();
			labelLayer.remove();
		},
	};
}

function accessText(access, lift) {
	if (access === "all") return "step-free to every platform" + (lift ? ", by lift" : "");
	if (access === "some") return "step-free to some platforms" + (lift ? ", by lift" : "");
	return "stairs or escalator only";
}

function kindInfo(kind, data) {
	if (kind.startsWith("platform:")) {
		const p = (data.platforms || []).find((x) => "platform:" + x.id === kind);
		return "Platform " + (p ? firstLangLocal(p.name) : "");
	}
	if (kind.startsWith("fare:")) return "Fare control " + (Number(kind.slice(5)) + 1);
	return { paid: "Paid area (behind the gates)", free: "Unpaid area", stairs: "Stairs", escalator: "Escalator",
		gate: "Fare gate" }[kind] || kind;
}

function firstLangLocal(s) { return String(s == null ? "" : s).split("||")[0].split("|")[0]; }

window.Station3D = { createView, LAYERS };
window.dispatchEvent(new Event("station3d-ready"));
