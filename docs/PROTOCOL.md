# Ink bridge protocol, version 1

A note-taking plugin running in an Android WebView cannot reach the e-ink pen pipeline.
The companion app can. This protocol lets the plugin tell the companion where its ink
surface is, and lets the companion hand back each finished stroke.

## Transport

- The companion is the WebSocket **server**, on loopback only: `ws://127.0.0.1:47810/`,
  falling back to `47811` if the first port is taken.
- The plugin is the client. One client at a time; a new connection replaces the old one.
- Every frame is a JSON text frame holding one object with a `type` string.
- Unknown `type` values and unknown fields are ignored by both ends.

## Coordinates

All positions on the wire are **CSS pixels in the WebView's viewport** (what
`clientX`/`clientY` and `getBoundingClientRect()` report). The plugin never deals in
screen pixels. The companion converts using the `viewport` it is given:
`scale = screen width in px / viewport.w`.

## Plugin to companion

| type | fields | meaning |
|---|---|---|
| `hello` | `protocol`, `client`, `version`, `token` | First frame after connecting. `token` is the client's pairing secret. |
| `surface` | `rev`, `kind`, `rect`, `exclude`, `viewport`, `dpr` | Where ink may be drawn. Sent again, with a higher `rev`, whenever anything in it changes. |
| `pen` | `color`, `width` | Pen colour as `#rrggbb`, and the width to draw the line at, in CSS px. The width is the client's estimate of how wide its stored stroke will come out, which is usually less than the nib selected; the companion draws it as given. |
| `pause` | `reason` | Stop drawing and stop reporting strokes (a tool other than the pen is selected, a dialog is open). |
| `resume` | | Undo `pause`. |
| `close` | | There is no ink surface any more. |
| `landed` | `ids` | These strokes are committed and painted by the plugin. |

- `rev`: integer, strictly increasing per connection.
- `kind`: `"note"` or `"pdf"`. Informational.
- `rect`: `{x, y, w, h}`, the part of the surface that is on screen.
- `exclude`: array of `{x, y, w, h}` inside `rect` where the pen must not draw (toolbars).
- `viewport`: `{w, h}`, `window.innerWidth` and `innerHeight`.
- `dpr`: `window.devicePixelRatio`. Informational.

## Companion to plugin

| type | fields | meaning |
|---|---|---|
| `welcome` | `protocol`, `app`, `version`, `device` | Reply to `hello`. `device` is `{model, widthPx, heightPx}`. |
| `stroke-begin` | `id`, `rev` | The pen touched down inside the surface. |
| `stroke` | `id`, `rev`, `points` | The pen lifted. The whole stroke. |
| `stroke-cancel` | `id` | A stroke announced with `stroke-begin` will not arrive. |
| `denied` | `reason` | Sent in place of `welcome`, then the socket is closed. `reason` is `"pairing"` or `"token"`. |

- `id`: integer, increasing per connection.
- `rev`: the `surface` revision that was in force when the pen touched down.
  Informational. The plugin places a stroke with the page mapping it took when
  `stroke-begin` arrived, so a stroke that arrives after a scroll still lands where it
  was written.
- `points`: array of `[x, y, pressure, t]`. `x`, `y` in viewport CSS px; `pressure` in
  0..1; `t` in milliseconds since the first point of the stroke.

## Handshake and versions

The plugin sends `hello` with the highest protocol it speaks. The companion answers
`welcome` with the version it will use, which is never higher than the plugin's. If
they share no version the companion closes the socket. A plugin that receives no
`welcome` within two seconds closes the socket and treats the companion as absent.

## Who may connect

Loopback keeps the network out, but not the tablet: any installed app, and any web page
open in a browser on the tablet, can reach `127.0.0.1`. A client receives every pen stroke
and decides where the overlay goes, so the companion checks two things before it answers
`welcome`.

- **Origin.** A browser states the page's origin in the WebSocket handshake and a page
  cannot forge it. The companion refuses any handshake that names an origin other than
  the ones a note app's own WebView uses (`http://localhost` for Obsidian on Android).
  A handshake with no `Origin` header is a native client and passes this check.
- **Pairing.** The plugin makes a random secret once per device (32 to 256 characters
  from `A-Z a-z 0-9 - _`) and sends it as `token` in every `hello`. The first time the
  companion sees a secret, it answers `denied` with reason `pairing`, closes the socket,
  and asks the person holding the tablet to allow or deny the app by name. Once allowed,
  the same secret connects without asking. The companion stores only a SHA-256 of it.
  A `hello` with no usable `token` gets `denied` with reason `token`.

A connection that has not passed both checks never replaces the current client.

## Who decides what

- The **companion** decides when the firmware owns the screen. It takes the screen when
  the pen comes into range and gives it back a short time after the pen lifts, so the
  app underneath can repaint. The plugin is not involved.
- The **plugin** decides what a stroke becomes: which note or page it belongs to, its
  colour and width, undo, saving.
- While connected and not paused, the plugin must ignore ink from the pen itself, since
  the same pen also reaches the WebView.
