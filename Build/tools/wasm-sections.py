#!/usr/bin/env python3
import re

path = '/mnt/c/Users/Nicolas/Desktop/Backend/doodooblox-test/webplayer/RobloxPlayerWeb.wasm'
data = open(path, 'rb').read()
print('size:', len(data))
assert data[:4] == b'\x00asm'
pos = 8
names = {0: 'custom', 1: 'type', 2: 'import', 3: 'function', 4: 'table', 5: 'memory',
         6: 'global', 7: 'export', 8: 'start', 9: 'element', 10: 'code', 11: 'data',
         12: 'datacount'}
targets = [2693346, 12799592, 12799615, 12863154, 13021835, 13042587, 13350397, 13350434]
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
    label = names.get(sec_id, str(sec_id))
    extra = ''
    if sec_id == 0:
        # custom section name
        nlen = data[p]
        nm = data[p + 1:p + 1 + nlen]
        extra = ' name=' + repr(nm.decode('utf-8', 'replace'))
    hits = [t for t in targets if p <= t < p + size]
    if hits:
        extra += '  <<< CONTAINS %s' % hits
    print('id=%-2d %-9s payload=%d..%d (%d bytes)%s' % (sec_id, label, p, p + size, size, extra))
    pos = p + size
