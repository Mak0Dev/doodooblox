// Starts the web player. The loading screen itself is index.html; this file
// only talks to it through these elements, all optional:
//
//   #canvas   the game (required)
//   #overlay  shown while loading, hidden once the game starts
//   #bar      a container whose first child's width is set to the download
//             progress (only while the files are not in the cache yet)
//   #error    why the game could not start
//   #hint     shown while the game runs without the mouse captured
//
// Classes set on <body>: "loading", "downloading", "running", "failed",
// "mouse-captured".
(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };
  var canvas = $('canvas');
  var barEl = $('bar') && $('bar').firstElementChild;
  var errorEl = $('error');
  var overlay = $('overlay');
  var hint = $('hint');

  function setProgress(fraction) { if (barEl) barEl.style.width = (100 * Math.min(1, fraction)) + '%'; }
  function setState(state) {
    document.body.classList.remove('loading', 'downloading', 'running', 'failed');
    document.body.classList.add(state);
  }
  function fail(message) {
    setState('failed');
    if (overlay) overlay.hidden = false;
    if (errorEl) errorEl.textContent = message;
  }

  setState('loading');

  // ?placeId=1818[&jobId=...][#key=<launch ticket>]. The ticket is optional:
  // every request the engine makes goes to this origin, so the site's session
  // cookie reaches PlaceLauncher by itself and a plain ?placeId= link is enough
  // for a signed-in player. A ticket, when there is one, is single-use: take it
  // out of the address bar so a reload does not try to spend it twice.
  var query = new URLSearchParams(location.search);
  var hash = new URLSearchParams(location.hash.replace(/^#/, ''));
  var placeId = query.get('placeId') || query.get('placeid') || query.get('placeID');
  var jobId = query.get('jobId');
  var key = hash.get('key') || '';
  if (location.hash)
    history.replaceState(null, '', location.pathname + location.search);

  // Testing aids, for a page served from this machine only. Anywhere else a
  // link could use them on whoever opens it: ?flog= sets any FastFlag, and
  // ?file= opens a place from any URL in the visitor's signed-in session.
  var devHost = location.hostname === 'localhost' || location.hostname === '127.0.0.1';
  // A place to open with no server: a path in the virtual filesystem
  // (/content/...), or a URL relative to this page, fetched first.
  var file = devHost ? query.get('file') : null;
  var flog = devHost ? query.get('flog') : null;

  // These end up inside the PlaceLauncher URL the engine builds, so anything
  // but an id would be query-string injection.
  if ((placeId && !/^\d{1,12}$/.test(placeId)) || (jobId && !/^[A-Za-z0-9-]{1,64}$/.test(jobId)) ||
      !/^[A-Za-z0-9-]{0,128}$/.test(key)) {
    fail('This game link is not valid.');
    return;
  }

  if (!placeId && !file) {
    fail('8');
    return;
  }

  // The tab reads "<place name> - Better16 WebPlayer" with the game's icon.
  // Both come from the site's own API, which answers on this host too.
  var TITLE = 'WebPlayer better16.wtf';
  // SDL names the page after its window when the engine starts; the place's
  // name is put back after that (postRun) and whenever it arrives.
  var pageTitle = TITLE;
  function setTitle(title) {
    pageTitle = title;
    document.title = title;
  }
  function setIcon(href) {
    var link = document.querySelector('link[rel="icon"]');
    if (!link) {
      link = document.createElement('link');
      link.rel = 'icon';
      document.head.appendChild(link);
    }
    link.type = 'image/png';
    link.href = href;
  }
  if (placeId) {
    setIcon('/thumbnails/gameicon/' + placeId);
    fetch('/v1/games/' + placeId, { credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (game) {
        if (game && game.name) setTitle(game.name + ' - ' + TITLE);
      }, function () {});
  } else {
    setTitle(file.replace(/^.*\//, '').replace(/\.rbxl?x?$/i, '') + ' - ' + TITLE);
  }

  if (!self.crossOriginIsolated) {
    fail('This page must be served over HTTPS (or from localhost) with\n' +
         'Cross-Origin-Opener-Policy and Cross-Origin-Embedder-Policy headers:\n' +
         'the game runs on threads.');
    return;
  }

  if (!document.createElement('canvas').getContext('webgl2')) {
    fail('Your browser does not support WebGL 2.');
    return;
  }

  // play.<domain> is the player; the site itself is www.<domain>. The engine
  // builds its URLs from this base, and every request to the site's domain is
  // sent back to this origin (App/util/Web/HttpPlatformImpl.cpp).
  var site = location.hostname.indexOf('play.') === 0
    ? location.protocol + '//www.' + location.hostname.substring(5) + '/'
    : location.origin + '/';

  var args = ['--base-url', site, '--content', '/content',
              '--width', String(innerWidth), '--height', String(innerHeight)];
  var fileUrl = null;
  if (file && file.charAt(0) !== '/') {
    fileUrl = file;
    file = '/tmp/' + file.replace(/^.*\//, '').replace(/[?#].*$/, '');
  }
  if (file) {
    args.push('--file', file);
  } else {
    var link = 'better16://placeId=' + encodeURIComponent(placeId) + '&key=' + encodeURIComponent(key);
    if (jobId) link += '&jobId=' + encodeURIComponent(jobId);
    args.push(link);
  }
  if (flog) args.push('--flog', flog);

  // The frame rate cap (the in-game "Maximum Frame Rate"; above 60 unlocks
  // the frame rate from the display's refresh). ?fps=60|90|120|180|240|unlimited
  // picks one; otherwise the engine's last choice, which it keeps here.
  var FPS_CAPS = ['60', '90', '120', '180', '240', 'unlimited'];
  var fpsCap = FPS_CAPS.indexOf((query.get('fps') || '').toLowerCase());
  if (fpsCap < 0) {
    try { fpsCap = parseInt(localStorage.getItem('b16.fpsCap'), 10); } catch (e) { fpsCap = -1; }
  }
  if (fpsCap >= 0 && fpsCap < FPS_CAPS.length) args.push('--fps-cap', String(fpsCap));

  // The in-game "Voice Chat" switch, which the engine also keeps here.
  var hearVoice = null;
  try { hearVoice = localStorage.getItem('b16.hearVoice'); } catch (e) {}
  if (hearVoice === '0' || hearVoice === '1') args.push('--hear-voice', hearVoice);

  // RakNet talks UDP; Emscripten carries each socket over a WebSocket to
  // ws(s)://<address>:<port>/. Nothing listens there, so point it at this
  // server's relay, which forwards the datagrams to the game server.
  //
  // ?s= names this page's session with that game server. A WebSocket can drop
  // for reasons that have nothing to do with the game (a proxy or tunnel
  // restarting, the network blinking); Emscripten opens a new one on the next
  // datagram, and with the same session the relay sends it from the same UDP
  // port as before. Without it the game server saw a stranger and the player
  // was left to time out: "Lost connection".
  var NativeWebSocket = self.WebSocket;
  var relayBase = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/relay/';
  var relaySession = Array.prototype.map.call(crypto.getRandomValues(new Uint8Array(16)),
    function (b) { return ('0' + b.toString(16)).slice(-2); }).join('');
  function RelayWebSocket(url, protocols) {
    var m = /^wss?:\/\/([^/:]+):(\d+)\/?$/.exec(url);
    if (m) url = relayBase + encodeURIComponent(m[1]) + '/' + m[2] + '?s=' + relaySession;
    return new NativeWebSocket(url, protocols);
  }
  RelayWebSocket.prototype = NativeWebSocket.prototype;
  ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'].forEach(function (k) { RelayWebSocket[k] = NativeWebSocket[k]; });
  self.WebSocket = RelayWebSocket;

  // The mouse: a click on the game captures it (pointer lock), as in any
  // browser game, and Esc lets it go. The engine draws its own cursor either
  // way, so the page's is hidden over the canvas (see index.html).
  var started = false;
  function updateCapture() {
    var captured = document.pointerLockElement === canvas;
    document.body.classList.toggle('mouse-captured', captured);
    if (hint) hint.hidden = !started || captured;
  }
  canvas.addEventListener('mousedown', function () {
    if (started && document.pointerLockElement !== canvas && canvas.requestPointerLock) {
      var request = canvas.requestPointerLock();
      if (request && request.catch) request.catch(function () {});
    }
    focusKeyboard();
  });

  // Typing. The engine gets keys and their text from SDL (keydown, then the
  // layout's character from keypress - Cyrillic included). What never comes
  // as a keypress - an emoji picker (Win+.), an input method, a paste - lands
  // in this invisible field, which holds the keyboard focus while the game
  // runs, and is handed over as text. Keypresses SDL has taken never reach it.
  var typing = document.createElement('textarea');
  typing.setAttribute('aria-hidden', 'true');
  typing.setAttribute('autocomplete', 'off');
  typing.setAttribute('autocapitalize', 'off');
  typing.setAttribute('spellcheck', 'false');
  typing.tabIndex = -1;
  typing.style.cssText = 'position:fixed;left:0;top:0;width:1px;height:1px;opacity:0;border:0;padding:0;resize:none;pointer-events:none;';
  document.body.appendChild(typing);
  function focusKeyboard() {
    try { typing.focus({ preventScroll: true }); } catch (e) { canvas.focus(); }
  }
  function sendTyped() {
    var text = typing.value;
    typing.value = '';
    // Newlines are Enter's business; the engine already had that key.
    text = text.replace(/[\r\n]+/g, ' ');
    if (text && started && Module.ccall) Module.ccall('rbxTypeText', null, ['string'], [text]);
  }
  var composing = false;
  typing.addEventListener('compositionstart', function () { composing = true; });
  typing.addEventListener('compositionend', function () { composing = false; sendTyped(); });
  typing.addEventListener('input', function () { if (!composing) sendTyped(); });
  // SDL cancels every Ctrl key's default action, paste included: keep Ctrl+V
  // (Cmd+V) from it so the browser pastes into the field.
  window.addEventListener('keydown', function (e) {
    if ((e.ctrlKey || e.metaKey) && !e.altKey && e.code === 'KeyV') e.stopImmediatePropagation();
  }, true);

  // Browsers start audio suspended until the page is clicked or typed into;
  // SDL's context is resumed on the first of either.
  function resumeAudio() {
    var sdl = self.Module && Module.SDL2;
    if (sdl && sdl.audioContext && sdl.audioContext.state === 'suspended') sdl.audioContext.resume();
  }
  ['mousedown', 'keydown', 'touchend'].forEach(function (type) {
    window.addEventListener(type, resumeAudio, true);
  });
  document.addEventListener('pointerlockchange', updateCapture);
  canvas.addEventListener('contextmenu', function (e) { e.preventDefault(); });

  var Module = self.Module = {
    arguments: args,
    canvas: canvas,
    preRun: [function () {
      if (!fileUrl) return;
      Module.addRunDependency('test-place');
      fetch(fileUrl)
        .then(function (r) { if (!r.ok) throw new Error(r.status + ' ' + fileUrl); return r.arrayBuffer(); })
        .then(function (data) {
          Module.FS.mkdirTree('/tmp');
          Module.FS.writeFile(file, new Uint8Array(data));
          Module.removeRunDependency('test-place');
        })
        .catch(function (e) { fail('Could not load ' + fileUrl + ': ' + e.message); });
    }],
    postRun: [function () { document.title = pageTitle; }],
    // The relay takes whatever subprotocol it is given.
    websocket: { subprotocol: null },
    print: function (text) { console.log(text); },
    printErr: function (text) { console.warn(text); },
    onRuntimeInitialized: function () {
      started = true;
      setState('running');
      setProgress(1);
      if (overlay) overlay.hidden = true;
      focusKeyboard();
      updateCapture();
    },
    onAbort: function (what) {
      fail('The game stopped: ' + what);
    },
    // The player left the game (Leave in the menu).
    onPlayerExit: function () {
      location.href = placeId ? site + 'games/' + encodeURIComponent(placeId) : site;
    }
  };

  function start(files) {
    if (files) {
      // Hand the engine what is already in memory instead of letting it
      // fetch the same files again. The module goes back to Emscripten too:
      // it passes it on to the worker threads.
      Module.instantiateWasm = function (imports, done) {
        WebAssembly.instantiate(files.wasm, imports).then(function (result) {
          done(result.instance, result.module);
        }, function (e) { fail('Could not start the player: ' + e); });
        return {};
      };
      Module.getPreloadedPackage = function () { return files.data; };
    }
    var script = document.createElement('script');
    // Named by build: a cache between here and the server (Cloudflare keeps
    // .js for hours) must not pair an older engine script with this .wasm.
    // The engine's worker threads load the same URL.
    script.src = 'RobloxPlayerWeb.js' + (files ? '?b=' + encodeURIComponent(files.build) : '');
    script.onerror = function () { fail('Could not load the player.'); };
    document.body.appendChild(script);
  }

  // Usually the site's Play dialog has already put the files in the cache
  // (preload.html) and this is quick; opened directly, this downloads them.
  function load() {
    if (typeof PlayerCache === 'undefined') {
      start(null);
      return;
    }
    PlayerCache.get(function (loaded, total) {
      document.body.classList.add('downloading');
      setProgress(total ? loaded / total : 0);
    }).then(start, function (e) {
      if (e && e.stale) {
        console.warn(e);
        fail('The player is being updated. Reload the page in a minute.');
        return;
      }
      console.warn('Player cache unavailable, loading directly:', e);
      start(null);
    });
  }

  function needSignIn() {
    fail('Sign in on the site to play. ');
    if (!errorEl) return;
    var link = document.createElement('a');
    link.href = site + 'login';
    link.textContent = 'Sign in';
    errorEl.appendChild(link);
  }

  // Without a ticket the player is whoever the session cookie says. Ask first,
  // rather than download the whole player only for PlaceLauncher to refuse an
  // anonymous visitor. Anything but a clear "not signed in" goes ahead.
  if (file || key) {
    load();
  } else {
    fetch('/session/v1', { credentials: 'same-origin' }).then(function (response) {
      if (response.status === 401) needSignIn();
      else load();
    }, load);
  }
})();
