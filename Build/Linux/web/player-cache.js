// The web player's files, kept in the browser's Cache Storage so a game
// starts without downloading them again. Used by preload.html (the site's
// Play dialog loads it in a hidden frame and shows its progress) and by
// player.js (the game page), which run on the same origin and so share the
// cache. The console page (console.js) needs one file more, the console's
// own content (RobloxConsole.data): every call takes an optional list of
// such extra files, and the result carries them in .extra by name.
//
// player-files.json, written by the build (precompress.py), names the build
// and the size and SHA-256 of each file; a cached copy from another build is
// replaced. A download is checked against it before it is kept: while an
// update is being copied to the server, player-files.json can already name
// the new build while the old .wasm is still there, and that pair, cached
// under the new name, crashed the engine on every start until the cache was
// cleared.
var PlayerCache = (function () {
  'use strict';

  var CACHE_NAME = 'better16-player';
  var WASM = 'RobloxPlayerWeb.wasm';
  var DATA = 'RobloxPlayerWeb.data';

  function manifest() {
    return fetch('player-files.json', { cache: 'no-cache' }).then(function (r) {
      if (!r.ok) throw new Error('player-files.json: ' + r.status);
      return r.json();
    });
  }

  function open() {
    if (!self.caches) return Promise.resolve(null); // not a secure context
    return caches.open(CACHE_NAME).catch(function () { return null; });
  }

  // The files on the server are not the ones player-files.json describes.
  function stale(name) {
    var e = new Error(name + ' does not match player-files.json');
    e.stale = true;
    return e;
  }

  function hex(buffer) {
    return Array.prototype.map.call(new Uint8Array(buffer), function (b) {
      return (b < 16 ? '0' : '') + b.toString(16);
    }).join('');
  }

  function verify(files, name, bytes) {
    var size = files.files[name];
    if (size !== undefined && bytes.byteLength !== size) return Promise.reject(stale(name));
    var sha = files.sha256 && files.sha256[name];
    if (!sha || !self.crypto || !crypto.subtle) return Promise.resolve(bytes);
    return crypto.subtle.digest('SHA-256', bytes).then(function (digest) {
      if (hex(digest) !== sha) throw stale(name);
      return bytes;
    });
  }

  function allNames(extra) {
    return [WASM, DATA].concat(extra || []);
  }

  // What load() and download() resolve to: { build, wasm, data, extra: { name: ArrayBuffer } }.
  function result(build, names, buffers) {
    var files = { build: build, wasm: buffers[0], data: buffers[1], extra: {} };
    for (var i = 2; i < names.length; i++) files.extra[names[i]] = buffers[i];
    return files;
  }

  // The files of this build, if all of them are cached, else null. Takes the
  // manifest, or just the build name.
  function load(files, extra) {
    var build = typeof files === 'string' ? files : files.build;
    var sizes = typeof files === 'string' ? {} : files.files;
    var names = allNames(extra);
    return open().then(function (cache) {
      if (!cache) return null;
      return Promise.all(names.map(function (name) {
        return cache.match(name).then(function (r) {
          return r && r.headers.get('X-Build') === build ? r.arrayBuffer() : null;
        }).then(function (buffer) {
          return buffer && (sizes[name] === undefined || buffer.byteLength === sizes[name]) ? buffer : null;
        });
      })).then(function (buffers) {
        return buffers.every(Boolean) ? result(build, names, buffers) : null;
      });
    });
  }

  // Whether this build is cached, without reading it.
  function has(build, extra) {
    return open().then(function (cache) {
      if (!cache) return false;
      return Promise.all(allNames(extra).map(function (name) {
        return cache.match(name).then(function (r) { return !!r && r.headers.get('X-Build') === build; });
      })).then(function (found) { return found.every(Boolean); });
    });
  }

  // Downloads the build's files, reporting onProgress(loaded, total) in
  // unpacked bytes, stores them, and resolves to the same object as load().
  // Rejects with an error whose .stale is set if the server has other files
  // than player-files.json names (an update half copied); nothing is kept.
  function download(files, onProgress, extra) {
    var names = allNames(extra);
    return fetchAll(files, names, onProgress).then(function (buffers) {
      return result(files.build, names, buffers);
    });
  }

  // Fetches, checks and caches the named files, in order; resolves to their
  // ArrayBuffers.
  function fetchAll(files, names, onProgress) {
    var total = names.reduce(function (sum, n) { return sum + (files.files[n] || 0); }, 0);
    var loaded = 0;

    function fetchOne(name) {
      return fetch(name, { cache: 'no-cache' }).then(function (response) {
        if (!response.ok) throw new Error(name + ': ' + response.status);
        var reader = response.body.getReader();
        var chunks = [];
        var size = 0;
        function pump() {
          return reader.read().then(function (part) {
            if (part.done) return;
            chunks.push(part.value);
            size += part.value.length;
            loaded += part.value.length;
            if (onProgress) onProgress(loaded, total);
            return pump();
          });
        }
        return pump().then(function () {
          var bytes = new Uint8Array(size);
          var offset = 0;
          chunks.forEach(function (c) { bytes.set(c, offset); offset += c.length; });
          return verify(files, name, bytes.buffer);
        });
      });
    }

    // One after the other: the progress bar reads better, and two 50 MB
    // downloads at once are no faster over one connection.
    var buffers = [];
    var chain = Promise.resolve();
    names.forEach(function (name) {
      chain = chain.then(function () { return fetchOne(name); }).then(function (bytes) { buffers.push(bytes); });
    });
    return chain.then(open).then(function (cache) {
      if (!cache) return buffers;
      var headers = { 'X-Build': files.build, 'Content-Type': 'application/octet-stream' };
      return Promise.all(names.map(function (name, i) {
        return cache.put(name, new Response(buffers[i], { headers: headers }));
      })).catch(function () { /* out of quota: just don't keep them */ })
        .then(function () { return buffers; });
    });
  }

  // Cached if present, downloaded (and cached) if not. The player itself is
  // likely cached already when only the console's extra file is not (the PC
  // player was used first): then only that is downloaded.
  function get(onProgress, extra) {
    return manifest().then(function (files) {
      return load(files, extra).then(function (cached) {
        if (cached) return cached;
        if (!extra || !extra.length) return download(files, onProgress);
        return load(files).then(function (player) {
          if (!player) return download(files, onProgress, extra);
          return fetchAll(files, extra, onProgress).then(function (buffers) {
            extra.forEach(function (name, i) { player.extra[name] = buffers[i]; });
            return player;
          });
        });
      });
    });
  }

  return { manifest: manifest, has: has, load: load, download: download, get: get };
})();
