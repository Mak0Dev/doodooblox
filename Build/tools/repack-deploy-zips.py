#!/usr/bin/env python3
"""Rebuild the DoodooBlox deploy zips for doodooblox.karblox.cc.

- RobloxApp.zip: embeds the freshly patched RobloxPlayerBeta.exe and swaps the
  legacy host names in its text entries (roblox.com -> karblox.cc,
  better16.xyz / karblox.ru -> doodooblox.karblox.cc).
- content-fonts.zip: text entry swaps.
- RobloxProxy.zip / NPRobloxProxy.zip: same-length binary swaps (ANSI and
  UTF-16) inside the proxy DLLs.

The binary swap uses karblox.cc because the client's trust checks compare the
registrable domain, and the public host doodooblox.karblox.cc is a subdomain of
it. Everything else is copied byte-identically with per-entry metadata
(name, timestamps, compression method, attributes) preserved.
"""
import hashlib
import os
import shutil
import zipfile

DEPLOY = '/mnt/d/Trunk2012/Build/UploadBits/Deploy'
PLAYER_EXE = '/mnt/d/Trunk2012/Build/UploadBits/Win32-Release-RobloxPlayer/RobloxPlayerBeta.exe'
CONTENT_SETTINGS = '/mnt/d/Trunk2012/Build/content/scripts/CoreGui2014/CoreScripts/Settings.lua'
SETTINGS_ENTRY = 'content/scripts/coregui2014/corescripts/settings.lua'

TEXT_EXTS = ('.lua', '.txt', '.json', '.xml', '.rbxmx', '.rbxlx', '.js', '.cfg',
             '.ini', '.html', '.css', '.md', '.xsd', '.csv', '.yml', '.yaml',
             '.config', '.rbxm', '.rbx')
BIN_EXTS = ('.dll', '.exe', '.so')

ASCII_ROBLOX = b'roblox.com'
ASCII_KARBLOX = b'karblox.cc'
UTF16_ROBLOX = 'roblox.com'.encode('utf-16-le')
UTF16_KARBLOX = 'karblox.cc'.encode('utf-16-le')
PUBLIC_HOST = b'doodooblox.karblox.cc'


def swap_text(data: bytes) -> bytes:
    data = data.replace(ASCII_ROBLOX, ASCII_KARBLOX)
    data = data.replace(b'www.better16.xyz', PUBLIC_HOST)
    data = data.replace(b'better16.xyz', PUBLIC_HOST)
    data = data.replace(b'www.karblox.ru', PUBLIC_HOST)
    data = data.replace(b'karblox.ru', PUBLIC_HOST)
    return data


def swap_binary(data: bytes) -> bytes:
    # strictly same-length in both encodings
    data = data.replace(ASCII_ROBLOX, ASCII_KARBLOX)
    data = data.replace(UTF16_ROBLOX, UTF16_KARBLOX)
    data = data.replace(b'www.karblox.ru', b'www.karblox.cc')
    data = data.replace('www.karblox.ru'.encode('utf-16-le'), 'www.karblox.cc'.encode('utf-16-le'))
    data = data.replace(b'karblox.ru', b'karblox.cc')
    data = data.replace('karblox.ru'.encode('utf-16-le'), 'karblox.cc'.encode('utf-16-le'))
    return data


def process(zip_name, exe_entry=None):
    path = os.path.join(DEPLOY, zip_name)
    if not os.path.exists(path):
        print(f'{zip_name}: missing, skipped')
        return
    changed = []
    with zipfile.ZipFile(path) as src:
        comment = src.comment
        entries = []
        for info in src.infolist():
            name = info.filename
            data = src.read(name)
            if exe_entry and name == exe_entry:
                with open(PLAYER_EXE, 'rb') as f:
                    new = f.read()
            elif name.lower().replace('\\', '/') == SETTINGS_ENTRY:
                with open(CONTENT_SETTINGS, 'rb') as f:
                    new = swap_text(f.read())
            else:
                low = name.lower()
                if low.endswith(TEXT_EXTS):
                    new = swap_text(data)
                elif low.endswith(BIN_EXTS):
                    new = swap_binary(data)
                else:
                    new = data
            entries.append((info, new))
            if new != data:
                changed.append(name)

    if not changed:
        print(f'{zip_name}: no changes, left untouched')
        return

    bak = path + '.pre-karblox-bak'
    if not os.path.exists(bak):
        shutil.copy2(path, bak)
    tmp = path + '.tmp'
    with zipfile.ZipFile(tmp, 'w') as dst:
        dst.comment = comment
        for info, data in entries:
            zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            zi.compress_type = info.compress_type
            zi.external_attr = info.external_attr
            zi.create_system = info.create_system
            zi.internal_attr = info.internal_attr
            zi.comment = info.comment
            dst.writestr(zi, data)
    os.replace(tmp, path)

    md5 = hashlib.md5(open(path, 'rb').read()).hexdigest()
    print(f'{zip_name}: rebuilt, {len(changed)} entries changed, md5={md5}')
    for n in changed[:50]:
        print('    ' + n)


if __name__ == '__main__':
    process('RobloxApp.zip', exe_entry='RobloxPlayerBeta.exe')
    process('content-fonts.zip')
    process('RobloxProxy.zip')
    process('NPRobloxProxy.zip')
    process('Libraries.zip')
    process('content-music.zip')
    process('content-particles.zip')
    process('content-sky.zip')
    process('content-sounds.zip')
    process('content-terrain.zip')
    process('content-textures.zip')
    process('content-textures2.zip')
    process('content-textures3.zip')
    process('shaders.zip')
