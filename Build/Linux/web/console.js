// Starts the console mode (xplay.<domain>): the engine with --console, which
// runs the Xbox One app (Linux/ConsoleShell.cpp) instead of one game. The
// page it lives in is console.html; see there for the elements it uses.
//
// Besides starting the engine, this is the console's own UI the app asks for:
// the on-screen keyboard (Module.consoleShowKeyboard, answered through
// rbxConsoleKeyboardClosed) and links it opens (Module.consoleOpenUri).
(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };
  var canvas = $('canvas');
  var boot = $('boot');
  var barEl = $('bar').firstElementChild;
  var statusEl = $('status');
  var errorEl = $('error');

  // The console's content, apart from the player's (RobloxConsole.data.js
  // puts it in the virtual filesystem).
  var CONSOLE_DATA = 'RobloxConsole.data';

  function setState(state) {
    document.body.classList.remove('loading', 'running', 'failed');
    document.body.classList.add(state);
  }
  function setStatus(text) { statusEl.textContent = text; }
  function setProgress(fraction) { barEl.style.width = (100 * Math.min(1, fraction)) + '%'; }
  function fail(message) {
    setState('failed');
    boot.hidden = false;
    errorEl.textContent = message;
  }

  var toastTimer = 0;
  function toast(text) {
    var el = $('toast');
    el.textContent = text;
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.hidden = true; }, 2500);
  }

  setState('loading');

  var pageTitle = document.title;

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

  // xplay.<domain> is the console; the site itself is www.<domain>. The
  // engine builds its URLs from this base, and every request to the site's
  // domain comes back to this origin (App/util/Web/HttpPlatformImpl.cpp).
  var site = /^x?play\./.test(location.hostname)
    ? location.protocol + '//www.' + location.hostname.replace(/^x?play\./, '') + '/'
    : location.origin + '/';

  // Testing aids for a page on this machine: ?flog=Name=Value, and ?file=, a
  // place (a URL relative to this page) every game on the shell opens instead
  // of joining a server.
  var devHost = location.hostname === 'localhost' || location.hostname === '127.0.0.1';
  var flog = devHost ? new URLSearchParams(location.search).get('flog') : null;
  var fileUrl = devHost ? new URLSearchParams(location.search).get('file') : null;
  var file = fileUrl ? '/tmp/' + fileUrl.replace(/[?#].*$/, '').replace(/^.*\//, '') : null;

  // The console draws a fixed 1920x1080 frame (the Xbox app is laid out for
  // it); console.html scales the canvas to the window.
  var args = ['--base-url', site, '--content', '/content', '--console'];
  if (flog) args.push('--flog', flog);
  if (file) args.push('--file', file);

  // The frame rate cap, as player.js: ?fps=60|90|120|180|240|unlimited, or
  // what the engine last remembered.
  var FPS_CAPS = ['60', '90', '120', '180', '240', 'unlimited'];
  var fpsCap = FPS_CAPS.indexOf((new URLSearchParams(location.search).get('fps') || '').toLowerCase());
  if (fpsCap < 0) {
    try { fpsCap = parseInt(localStorage.getItem('b16.fpsCap'), 10); } catch (e) { fpsCap = -1; }
  }
  if (fpsCap >= 0 && fpsCap < FPS_CAPS.length) args.push('--fps-cap', String(fpsCap));

  // The in-game "Voice Chat" switch, which the engine also keeps here.
  var hearVoice = null;
  try { hearVoice = localStorage.getItem('b16.hearVoice'); } catch (e) {}
  if (hearVoice === '0' || hearVoice === '1') args.push('--hear-voice', hearVoice);

  // Game traffic: RakNet's UDP over a WebSocket to this server's relay, as in
  // player.js - with its session (?s=), so a dropped WebSocket reopens from
  // the same UDP port.
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

  // No mouse on a console: nothing to capture, and no menu on right click.
  canvas.addEventListener('contextmenu', function (e) { e.preventDefault(); });

  // Browsers start audio suspended until the page is clicked or typed into -
  // a controller's buttons do not count. SDL's context is resumed on the
  // first click or key.
  function audioContext() {
    var sdl = self.Module && Module.SDL2;
    return sdl && sdl.audioContext;
  }
  function resumeAudio() {
    var context = audioContext();
    if (context && context.state === 'suspended') context.resume();
  }
  ['mousedown', 'keydown', 'touchend'].forEach(function (type) {
    addEventListener(type, resumeAudio, true);
  });

  // Full screen is the page's, not the engine's: the engine's would resize
  // the 1080p frame to the screen. Before the engine (SDL listens on window)
  // sees the key.
  addEventListener('keydown', function (e) {
    if (e.key !== 'F11' && !(e.key === 'Enter' && e.altKey)) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    if (document.fullscreenElement) document.exitFullscreen();
    else document.documentElement.requestFullscreen().catch(function () {});
  }, true);
  addEventListener('keyup', function (e) {
    if (e.key === 'F11') e.stopImmediatePropagation();
  }, true);

  // ---------------------------------------------------------------- gamepads

  // The engine reads controllers through navigator.getGamepads (SDL). While
  // the on-screen keyboard is up it reads none - the app behind it must not
  // move - and after it closes, none until every button is let go, or the A
  // that pressed "done" would press whatever the app has selected.
  var realGetGamepads = navigator.getGamepads ? navigator.getGamepads.bind(navigator) : function () { return []; };
  var padsHeld = false;
  function anyButtonDown() {
    var pads = realGetGamepads();
    for (var i = 0; i < pads.length; i++) {
      var pad = pads[i];
      if (!pad) continue;
      for (var b = 0; b < pad.buttons.length; b++) if (pad.buttons[b].pressed) return true;
    }
    return false;
  }
  navigator.getGamepads = function () {
    if (padsHeld && !keyboardOpen && !anyButtonDown()) padsHeld = false;
    return padsHeld ? [] : realGetGamepads();
  };

  addEventListener('gamepadconnected', function (e) {
    toast('Controller connected: ' + (e.gamepad.id.replace(/\s*\(.*$/, '') || 'gamepad'));
  });
  addEventListener('gamepaddisconnected', function () {
    toast('Controller disconnected. The keyboard still plays.');
  });

  // ------------------------------------------------------ on-screen keyboard

  var kb = $('keyboard');
  var kbInput = $('kb-input');
  var kbKeys = $('kb-keys');
  var keyboardOpen = false;
  var shifted = false;
  var focusIndex = 0;
  var buttons = [];
  // Keys pressed while the keyboard was up: their release is not the app's.
  var swallowUp = {};

  var LAYOUT = [
    '1234567890'.split(''),
    'qwertyuiop'.split(''),
    'asdfghjkl@'.split(''),
    'zxcvbnm.-_'.split(''),
    [{ label: 'Shift', action: 'shift', span: 2 }, { label: 'Space', action: 'space', span: 4 },
     { label: '⌫', action: 'back', span: 2 }, { label: 'Done', action: 'done', span: 2 }]
  ];
  var COLUMNS = 10;

  function buildKeys() {
    kbKeys.textContent = '';
    buttons = [];
    LAYOUT.forEach(function (row, r) {
      var column = 0;
      row.forEach(function (key) {
        var spec = typeof key === 'string' ? { label: key, action: 'char', span: 1 } : key;
        var el = document.createElement('button');
        el.type = 'button';
        el.tabIndex = -1;
        el.textContent = spec.label;
        if (spec.span > 1) el.className = 'wide' + spec.span;
        el.addEventListener('mousedown', function (e) { e.preventDefault(); });
        el.addEventListener('click', function () { focusIndex = buttons.indexOf(item); pressKey(item); });
        var item = { el: el, spec: spec, row: r, column: column };
        column += spec.span;
        buttons.push(item);
        kbKeys.appendChild(el);
      });
    });
    relabel();
  }

  function relabel() {
    buttons.forEach(function (b) {
      if (b.spec.action === 'char') b.el.textContent = shifted ? b.spec.label.toUpperCase() : b.spec.label;
      if (b.spec.action === 'shift') b.el.classList.toggle('on', shifted);
      b.el.classList.toggle('focus', b === buttons[focusIndex]);
    });
  }

  function insert(text) {
    var start = kbInput.selectionStart == null ? kbInput.value.length : kbInput.selectionStart;
    var end = kbInput.selectionEnd == null ? start : kbInput.selectionEnd;
    kbInput.value = kbInput.value.slice(0, start) + text + kbInput.value.slice(end);
    kbInput.selectionStart = kbInput.selectionEnd = start + text.length;
  }

  function backspace() {
    var start = kbInput.selectionStart == null ? kbInput.value.length : kbInput.selectionStart;
    var end = kbInput.selectionEnd == null ? start : kbInput.selectionEnd;
    if (start === end && start > 0) start--;
    kbInput.value = kbInput.value.slice(0, start) + kbInput.value.slice(end);
    kbInput.selectionStart = kbInput.selectionEnd = start;
  }

  function pressKey(item) {
    switch (item.spec.action) {
      case 'char':
        insert(shifted ? item.spec.label.toUpperCase() : item.spec.label);
        if (shifted) { shifted = false; }
        break;
      case 'shift': shifted = !shifted; break;
      case 'space': insert(' '); break;
      case 'back': backspace(); break;
      case 'done': closeKeyboard(true); return;
    }
    relabel();
  }

  // Moves the highlight on the grid, keeping to the same column where it can.
  function moveFocus(dx, dy) {
    var current = buttons[focusIndex];
    if (dx) {
      var next = focusIndex + dx;
      if (next >= 0 && next < buttons.length && buttons[next].row === current.row) focusIndex = next;
    } else if (dy) {
      var row = current.row + dy;
      var best = -1, bestDistance = Infinity;
      buttons.forEach(function (b, i) {
        if (b.row !== row) return;
        var distance = Math.abs(b.column + b.spec.span / 2 - (current.column + current.spec.span / 2));
        if (distance < bestDistance) { best = i; bestDistance = distance; }
      });
      if (best >= 0) focusIndex = best;
    }
    relabel();
  }

  function openKeyboard(title, description, text, isPassword) {
    keyboardOpen = true;
    padsHeld = true;
    shifted = false;
    focusIndex = 0;
    $('kb-title').textContent = title || 'Enter text';
    $('kb-description').textContent = description || '';
    kbInput.type = isPassword ? 'password' : 'text';
    kbInput.value = text || '';
    buildKeys();
    kb.hidden = false;
    document.body.classList.add('keyboard-open');
    kbInput.focus();
    kbInput.selectionStart = kbInput.selectionEnd = kbInput.value.length;
    requestAnimationFrame(pollPads);
  }

  function closeKeyboard(accepted) {
    if (!keyboardOpen) return;
    keyboardOpen = false;
    kb.hidden = true;
    document.body.classList.remove('keyboard-open');
    var text = kbInput.value;
    kbInput.value = '';
    canvas.focus();
    Module.ccall('rbxConsoleKeyboardClosed', null, ['string', 'number'], [text, accepted ? 1 : 0]);
  }

  // The keyboard's keys, before the engine (SDL listens on window) sees them.
  document.addEventListener('keydown', function (e) {
    if (!keyboardOpen) return;
    e.stopPropagation();
    swallowUp[e.code] = true;
    if (e.key === 'Enter') { e.preventDefault(); closeKeyboard(true); }
    else if (e.key === 'Escape') { e.preventDefault(); closeKeyboard(false); }
    else if (e.target !== kbInput) kbInput.focus();
  });
  document.addEventListener('keyup', function (e) {
    if (keyboardOpen || swallowUp[e.code]) {
      delete swallowUp[e.code];
      e.stopPropagation();
    }
  });
  document.addEventListener('keypress', function (e) {
    if (keyboardOpen) e.stopPropagation();
  });

  // A controller on the keyboard: the standard mapping's buttons.
  var PAD = { A: 0, B: 1, X: 2, Y: 3, LB: 4, RB: 5, BACK: 8, START: 9, UP: 12, DOWN: 13, LEFT: 14, RIGHT: 15 };
  var previous = {};
  var repeatAt = 0;
  function pollPads(time) {
    if (!keyboardOpen) { previous = {}; return; }
    var pads = realGetGamepads();
    var now = {};
    var stickX = 0, stickY = 0;
    for (var i = 0; i < pads.length; i++) {
      var pad = pads[i];
      if (!pad) continue;
      for (var name in PAD) if (pad.buttons[PAD[name]] && pad.buttons[PAD[name]].pressed) now[name] = true;
      if (Math.abs(pad.axes[0]) > 0.6) stickX = pad.axes[0] > 0 ? 1 : -1;
      if (Math.abs(pad.axes[1]) > 0.6) stickY = pad.axes[1] > 0 ? 1 : -1;
    }
    var pressed = function (name) { return now[name] && !previous[name]; };

    if (pressed('A')) pressKey(buttons[focusIndex]);
    else if (pressed('B')) closeKeyboard(false);
    else if (pressed('X')) { backspace(); relabel(); }
    else if (pressed('Y')) { insert(' '); relabel(); }
    else if (pressed('START')) closeKeyboard(true);
    else if (pressed('LB') || pressed('RB')) { shifted = !shifted; relabel(); }

    // D-pad and stick: once on press, then repeating while held.
    var dx = (now.RIGHT ? 1 : 0) - (now.LEFT ? 1 : 0) || stickX;
    var dy = (now.DOWN ? 1 : 0) - (now.UP ? 1 : 0) || stickY;
    if (dx || dy) {
      var fresh = pressed('RIGHT') || pressed('LEFT') || pressed('UP') || pressed('DOWN') || (!previous.stick && (stickX || stickY));
      if (fresh || time >= repeatAt) {
        moveFocus(dx, dx ? 0 : dy);
        repeatAt = time + (fresh ? 400 : 110);
      }
    }
    now.stick = !!(stickX || stickY);
    previous = now;
    requestAnimationFrame(pollPads);
  }

  // ------------------------------------------------------------ the engine

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
    // The relay takes whatever subprotocol it is given.
    websocket: { subprotocol: null },
    print: function (text) { console.log(text); },
    printErr: function (text) { console.warn(text); },
    onRuntimeInitialized: function () {
      setState('running');
      setProgress(1);
      boot.hidden = true;
      canvas.focus();
      // Playing on a controller alone leaves the sound off; say how to start it.
      setTimeout(function () {
        var context = audioContext();
        if (context && context.state === 'suspended') toast('Press any key or click to turn the sound on');
      }, 4000);
    },
    // SDL names the page after its window when the engine starts.
    postRun: [function () { document.title = pageTitle; }],
    onAbort: function (what) {
      fail('The console stopped: ' + what);
    },
    // Not reached from the app (it has no way out but the browser's); a game
    // that quits the player entirely lands on the site.
    onPlayerExit: function () {
      location.href = site;
    },
    consoleShowKeyboard: openKeyboard,
    consoleOpenUri: function (uri) {
      if (/^https?:\/\//.test(uri)) open(uri, '_blank', 'noopener');
    }
  };

  function loadScript(src) {
    return new Promise(function (resolve, reject) {
      var script = document.createElement('script');
      script.src = src;
      script.onload = resolve;
      script.onerror = function () { reject(new Error('Could not load ' + src)); };
      document.body.appendChild(script);
    });
  }

  function start(files) {
    if (files) {
      // What is already in memory, rather than fetched again: the engine's
      // module (Emscripten passes it on to the worker threads too) and both
      // data packages.
      Module.instantiateWasm = function (imports, done) {
        WebAssembly.instantiate(files.wasm, imports).then(function (result) {
          done(result.instance, result.module);
        }, function (e) { fail('Could not start the console: ' + e); });
        return {};
      };
      Module.getPreloadedPackage = function (name) {
        return name.indexOf(CONSOLE_DATA) >= 0 ? files.extra[CONSOLE_DATA] : files.data;
      };
    }
    setStatus('Starting…');
    // Named by build, as in player.js: a cache on the way must not pair an
    // older script with this build's files. The package loader goes first -
    // it queues its files for the engine's filesystem.
    var build = files ? '?b=' + encodeURIComponent(files.build) : '';
    loadScript(CONSOLE_DATA + '.js' + build)
      .then(function () { return loadScript('RobloxPlayerWeb.js' + build); })
      .catch(function (e) { fail(e.message); });
  }

  // The site's Play dialog may have cached everything already (preload.html
  // ?console=1); otherwise the first visit downloads it here.
  if (typeof PlayerCache === 'undefined') {
    start(null);
    return;
  }
  setStatus('Loading…');
  PlayerCache.get(function (loaded, total) {
    document.body.classList.add('downloading');
    setStatus('Downloading ' + Math.round(loaded / 1048576) + ' of ' + Math.round(total / 1048576) + ' MB');
    setProgress(total ? loaded / total : 0);
  }, [CONSOLE_DATA]).then(start, function (e) {
    if (e && e.stale) {
      console.warn(e);
      fail('The console is being updated. Reload the page in a minute.');
      return;
    }
    console.warn('Player cache unavailable, loading directly:', e);
    start(null);
  });
})();
