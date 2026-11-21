#!/usr/bin/env python3
path = '/mnt/c/Users/Nicolas/Desktop/Backend/doodooblox-test/webplayer/RobloxPlayerWeb.wasm'
data = open(path, 'rb').read()

off = 2693346
start = off - 48
end = off + 48
chunk = data[start:end]
for row in range(0, len(chunk), 16):
    lo = start + row
    b = chunk[row:row + 16]
    hexs = ' '.join('%02X' % x for x in b)
    asci = ''.join(chr(x) if 32 <= x <= 126 else '.' for x in b)
    mark = '  <<< match starts here' if lo <= off < lo + 16 else ''
    print('%9d  %s  |%s|%s' % (lo, hexs, asci, mark))
