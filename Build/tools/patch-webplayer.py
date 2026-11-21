#!/usr/bin/env python3
"""Patch the Better16 webplayer build for the doodooblox.karblox.cc deployment.

- RobloxPlayerWeb.wasm: same-length host swaps. The karblox.cc DNS zone has a
  wildcard, and the web client's routeUrl() treats every *.karblox.cc host as
  the page's own origin, so the replacement hosts are short karblox.cc
  subdomains sized to keep the byte length identical (the wasm strings are C
  strings inside a binary):
      test.public.ecs.better16.xyz -> test-public-ecs12.karblox.cc
      logging.service.better16.xyz -> logging-service22.karblox.cc
      assetgame.better16.xyz       -> assetgame12.karblox.cc
      www.better16.xyz             -> www12.karblox.cc
      better16.xyz (rest)         -> w.karblox.cc
- RobloxPlayerWeb.data / RobloxConsole.data: unpacked per the emscripten file
  pack metadata, text entries get full doodooblox.karblox.cc rewrites (length
  may change), binary entries get same-length swaps, then repacked with the
  metadata rewritten (file ranges + remote_package_size).
- player.js: the site's IIS front door exposes the relay at /relay.ashx/,
  which its RelayHandler forwards to the backend's /relay/.
- player-files.json: sizes + sha256 refreshed, build id set to BUILD_ID
  (invalidates the browsers' cached copies).
- .gz always regenerated; .br regenerated when the brotli module is present,
  otherwise deleted (the server falls back to .gz / identity).
"""
import gzip
import hashlib
import json
import os
import re
import sys

W = '/mnt/c/Users/Nicolas/Desktop/Backend/doodooblox-test/webplayer'

try:
    import brotli
except ImportError:
    brotli = None

ASCII_ROBLOX = b'roblox.com'
ASCII_KARBLOX = b'karblox.cc'
UTF16_ROBLOX = 'roblox.com'.encode('utf-16-le')
UTF16_KARBLOX = 'karblox.cc'.encode('utf-16-le')

PUBLIC_HOST = b'doodooblox.karblox.cc'
BUILD_ID = os.environ.get('DOODOO_BUILD_ID', 'doodoo-20261022-title-fps-domain')
KEEP_DOMAINS = '--keep-domains' in sys.argv

WASM_RULES = [
    (b'test.public.ecs.better16.xyz', b'test-public-ecs12.karblox.cc'),
    (b'logging.service.better16.xyz', b'logging-service22.karblox.cc'),
    (b'assetgame.better16.xyz', b'assetgame12.karblox.cc'),
    (b'www.better16.xyz', b'www12.karblox.cc'),
    (b'better16.xyz', b'w.karblox.cc'),
]

TEXT_EXTS = ('.lua', '.txt', '.json', '.xml', '.rbxlx', '.cfg', '.ini', '.csv',
             '.md', '.js', '.html', '.css', '.xsd', '.yml', '.yaml', '.rbxmx')


def swap_text(data):
    data = data.replace(ASCII_ROBLOX, ASCII_KARBLOX)
    data = data.replace(b'www.better16.xyz', PUBLIC_HOST)
    data = data.replace(b'better16.xyz', PUBLIC_HOST)
    return data


def swap_bin(data):
    data = data.replace(ASCII_ROBLOX, ASCII_KARBLOX)
    data = data.replace(UTF16_ROBLOX, UTF16_KARBLOX)
    return data


def patch_wasm():
    path = os.path.join(W, 'RobloxPlayerWeb.wasm')
    data = open(path, 'rb').read()

    # section layout, to prove the string bytes live in Data sections
    pos = 8
    spans = []
    while pos < len(data):
        sec_id = data[pos]
        size = 0
        shift = 0
        p = pos + 1
        while True:
            b = data[p]
            size |= (b & 0x7F) << shift
            shift += 7
            p += 1
            if not (b & 0x80):
                break
        spans.append((sec_id, p, p + size))
        pos = p + size

    hits = [m.start() for m in re.finditer(b'better16\\.xyz', data)]
    bad = []
    v128_consts = 0
    for h in hits:
        sec = next((sid for sid, s, e in spans if s <= h < e), None)
        if sec == 11:
            continue                      # ordinary data-section string
        if sec == 10:
            # v128.const (opcode FD 0C) embeds a 16-byte immediate in code;
            # the match may start a few bytes into that immediate.
            ok = False
            for i in range(max(0, h - 12), h - 1):
                if data[i:i + 2] == b'\xfd\x0c' and h + 12 <= i + 18:
                    ok = True
                    break
            if ok:
                v128_consts += 1
                continue
        bad.append((h, sec))
    print(f'wasm: {len(hits)} better16.xyz hits '
          f'({v128_consts} in v128.const immediates), {len(bad)} unexplained')
    if bad:
        raise SystemExit(f'wasm hits outside expected locations: {bad} - aborting')

    for old, new in WASM_RULES:
        assert len(old) == len(new), (old, new)
        n = data.count(old)
        if n:
            data = data.replace(old, new)
            print(f'  wasm: {old.decode()} x{n} -> {new.decode()}')
    assert data.count(b'better16') == 0
    assert data.count(b'roblox.com') == 0
    open(path, 'wb').write(data)
    return path


def repack_player_pack():
    """RobloxPlayerWeb.js (minified) + RobloxPlayerWeb.data."""
    js_path = os.path.join(W, 'RobloxPlayerWeb.js')
    data_path = os.path.join(W, 'RobloxPlayerWeb.data')
    js = open(js_path, 'rb').read().decode('utf-8')
    blob = open(data_path, 'rb').read()

    m = re.search(r'loadPackage\(\{files:\[(?P<files>.*?)\],remote_package_size:(?P<size>\d+)\}\)', js, re.S)
    if not m:
        raise SystemExit('player pack metadata not found in RobloxPlayerWeb.js')
    entries = [(fn, int(s), int(e)) for fn, s, e in
               re.findall(r'\{filename:"(.*?)",start:(\d+),end:(\d+)\}', m.group('files'))]
    size = int(m.group('size'))
    if not entries:
        raise SystemExit('no player pack entries parsed')
    if entries[0][1] != 0 or entries[-1][2] != size or size != len(blob):
        raise SystemExit(f'player pack mismatch: {entries[0][1]}..{entries[-1][2]} size={size} blob={len(blob)}')

    out = bytearray()
    new_entries = []
    pos = 0
    patched = 0
    for fn, s, e in entries:
        if s < pos or s > len(blob):
            raise SystemExit(f'bad range for {fn}')
        out += blob[pos:s]          # preserve any gap bytes
        chunk = blob[s:e]
        low = fn.lower()
        if low.endswith(TEXT_EXTS):
            new = swap_text(chunk)
        else:
            new = swap_bin(chunk)
        if new != chunk:
            patched += 1
        start = len(out)
        out += new
        new_entries.append((fn, start, len(out)))
        pos = e
    out += blob[pos:]

    files_txt = ','.join('{filename:"%s",start:%d,end:%d}' % (fn, s, e) for fn, s, e in new_entries)
    new_call = 'loadPackage({files:[%s],remote_package_size:%d})' % (files_txt, len(out))
    js = js[:m.start()] + new_call + js[m.end():]

    open(js_path, 'wb').write(js.encode('utf-8'))
    open(data_path, 'wb').write(bytes(out))
    print(f'player pack: {len(entries)} entries, {patched} patched, '
          f'data {len(blob)} -> {len(out)} bytes')
    return js_path, data_path


def repack_console_pack():
    """RobloxConsole.data.js (pretty printed) + RobloxConsole.data."""
    js_path = os.path.join(W, 'RobloxConsole.data.js')
    data_path = os.path.join(W, 'RobloxConsole.data')
    js = open(js_path, 'rb').read().decode('utf-8')
    blob = open(data_path, 'rb').read()

    m = re.search(r'loadPackage\((\{"files": \[.*?\], "remote_package_size": \d+\})\)', js, re.S)
    if not m:
        raise SystemExit('console pack metadata not found')
    entries = [(fn, int(s), int(e)) for fn, s, e in
               re.findall(r'\{"filename": "(.*?)", "start": (\d+), "end": (\d+)\}', m.group(1))]
    size = int(re.search(r'"remote_package_size": (\d+)', m.group(1)).group(1))
    if not entries:
        raise SystemExit('no console pack entries parsed')
    if entries[0][1] != 0 or entries[-1][2] != size or size != len(blob):
        raise SystemExit(f'console pack mismatch: {entries[-1][2]} size={size} blob={len(blob)}')

    out = bytearray()
    new_entries = []
    pos = 0
    patched = 0
    for fn, s, e in entries:
        if s < pos or s > len(blob):
            raise SystemExit(f'bad range for {fn}')
        out += blob[pos:s]
        chunk = blob[s:e]
        low = fn.lower()
        if low.endswith(TEXT_EXTS):
            new = swap_text(chunk)
        else:
            new = swap_bin(chunk)
        if new != chunk:
            patched += 1
        start = len(out)
        out += new
        new_entries.append((fn, start, len(out)))
        pos = e
    out += blob[pos:]

    files_txt = ', '.join('{"filename": "%s", "start": %d, "end": %d}' % (fn, s, e)
                          for fn, s, e in new_entries)
    new_call = 'loadPackage({"files": [%s], "remote_package_size": %d})' % (files_txt, len(out))
    js = js[:m.start()] + new_call + js[m.end():]

    open(js_path, 'wb').write(js.encode('utf-8'))
    open(data_path, 'wb').write(bytes(out))
    print(f'console pack: {len(entries)} entries, {patched} patched, '
          f'data {len(blob)} -> {len(out)} bytes')
    return js_path, data_path


def patch_player_js():
    """Point the browser player at the IIS relay handler."""
    path = os.path.join(W, 'player.js')
    data = open(path, 'rb').read()
    if b'/relay.ashx/' in data:
        print('player.js: relay already /relay.ashx/')
    elif b'"/relay/"' in data:
        data = data.replace(b'"/relay/"', b'"/relay.ashx/"')
        open(path, 'wb').write(data)
        print('player.js: relay /relay/ -> /relay.ashx/')
    else:
        raise SystemExit('player.js relay path not found')
    if b'DooDooBlox' not in data:
        raise SystemExit('player.js title is not DooDooBlox')
    return path


def refresh_precompressed(paths):
    for p in paths:
        data = open(p, 'rb').read()
        with open(p + '.gz', 'wb') as f:
            f.write(gzip.compress(data, 9, mtime=0))
        if brotli is not None:
            with open(p + '.br', 'wb') as f:
                f.write(brotli.compress(data, quality=11))
        elif os.path.exists(p + '.br'):
            os.remove(p + '.br')
            print(f'  (brotli module missing: removed {os.path.basename(p)}.br)')
        print(f'  refreshed {os.path.basename(p)} .gz/.br')


def refresh_manifest():
    path = os.path.join(W, 'player-files.json')
    manifest = json.load(open(path))
    for name in list(manifest['files'].keys()):
        p = os.path.join(W, name)
        if os.path.exists(p):
            data = open(p, 'rb').read()
            manifest['files'][name] = len(data)
            manifest['sha256'][name] = hashlib.sha256(data).hexdigest()
    manifest['build'] = BUILD_ID
    with open(path, 'w') as f:
        json.dump(manifest, f, indent=2)
    print(f'manifest refreshed, build={manifest["build"]}')


def main():
    if KEEP_DOMAINS:
        # The native Better16 host names are intentional for this build.
        # Only the relay path is deployment-specific.
        print('keep-domains: leaving better16.xyz / native URLs untouched')
        wasm = os.path.join(W, 'RobloxPlayerWeb.wasm')
        p_js = os.path.join(W, 'RobloxPlayerWeb.js')
        p_data = os.path.join(W, 'RobloxPlayerWeb.data')
        c_data = os.path.join(W, 'RobloxConsole.data')
    else:
        wasm = patch_wasm()
        p_js, p_data = repack_player_pack()
        c_js, c_data = repack_console_pack()
    player_js = patch_player_js()
    refresh_precompressed([wasm, p_js, p_data, c_data])
    refresh_manifest()

    # final sanity
    for name in ('RobloxPlayerWeb.wasm', 'RobloxPlayerWeb.data', 'RobloxConsole.data'):
        data = open(os.path.join(W, name), 'rb').read()
        print(f'{name}: better16={data.count(b"better16")} roblox.com={data.count(b"roblox.com")} '
              f'karblox.cc={data.count(b"karblox.cc")} public={data.count(PUBLIC_HOST)}')
    print(f'player.js: relay.ashx={open(player_js, "rb").read().count(b"/relay.ashx/")}')


if __name__ == '__main__':
    main()
