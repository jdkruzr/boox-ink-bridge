// Host-side check of the companion's server, without the plugin.
//   adb forward tcp:47810 tcp:47810 && node tools/handshake.mjs [port] [seconds]
// Sends hello, a surface and a pen, prints everything received, then sends close.
const port = Number(process.argv[2] ?? 47810);
const seconds = Number(process.argv[3] ?? 5);
const ws = new WebSocket(`ws://127.0.0.1:${port}/`);
const send = (o) => { console.log(">>", JSON.stringify(o)); ws.send(JSON.stringify(o)); };
ws.onopen = () => {
	send({ type: "hello", protocol: 1, client: "handshake-tool", version: "0" });
};
ws.onmessage = (ev) => {
	const text = String(ev.data);
	const o = JSON.parse(text);
	console.log("<<", o.type === "stroke" ? `stroke id=${o.id} rev=${o.rev} points=${o.points.length} first=${JSON.stringify(o.points[0])}` : text);
	if (o.type === "welcome") {
		send({ type: "surface", rev: 1, kind: "note", rect: { x: 0, y: 225, w: 768, h: 425 }, exclude: [{ x: 182, y: 250, w: 560, h: 44 }], viewport: { w: 768, h: 1024 }, dpr: 2 });
		send({ type: "pen", color: "#1f5fd6", width: 2.4 });
		setTimeout(() => send({ type: "surface", rev: 2, kind: "note", rect: { x: 0, y: 300, w: 768, h: 350 }, exclude: [], viewport: { w: 768, h: 1024 }, dpr: 2 }), 1500);
		setTimeout(() => { send({ type: "close" }); setTimeout(() => ws.close(), 300); }, seconds * 1000);
	}
};
ws.onclose = (ev) => { console.log("closed", ev.code, ev.reason); };
ws.onerror = () => { console.log("socket error (is the port forwarded and the service running?)"); process.exitCode = 1; };
