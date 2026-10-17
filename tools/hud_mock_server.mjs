// Tiny static server for the manual-driving cab HUD mockups (dev only, not shipped).
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { join, extname, normalize, dirname } from "node:path";
import { fileURLToPath } from "node:url";

// Serve the mock from the repo (tools/hud_mock) so it survives across sessions.
const ROOT = join(dirname(fileURLToPath(import.meta.url)), "hud_mock");
const MIME = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".svg": "image/svg+xml", ".png": "image/png" };

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
}).listen(8793, () => console.log("hud mock on http://localhost:8793"));
