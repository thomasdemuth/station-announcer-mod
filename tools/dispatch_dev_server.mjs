// Tiny static server for previewing the dispatch frontend (dev only, not shipped).
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { join, extname, normalize } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("../src/main/resources/assets/station_announcer/dispatch", import.meta.url));
const MIME = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".json": "application/json", ".svg": "image/svg+xml", ".png": "image/png" };

createServer(async (req, res) => {
	try {
		let path = decodeURIComponent(new URL(req.url, "http://x").pathname);
		if (path === "/" || path === "") path = "/index.html";
		const file = normalize(join(ROOT, path));
		if (!file.startsWith(ROOT)) { res.writeHead(403); res.end(); return; }
		const data = await readFile(file);
		res.writeHead(200, { "Content-Type": MIME[extname(file)] || "application/octet-stream" });
		res.end(data);
	} catch {
		res.writeHead(404); res.end("not found");
	}
}).listen(8781, () => console.log("dispatch dev server on http://localhost:8781"));
