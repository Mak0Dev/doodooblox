#!/usr/bin/env python3
"""Names the functions in a production web player stack trace.

A production build has no function names in the .wasm, so the browser shows
frames as <module>:0x55c008. The build writes RobloxPlayerWeb.js.symbols
(function index -> name) next to the .wasm; this maps each byte offset to
the function containing it.

  symbolize.py RobloxPlayerWeb.wasm RobloxPlayerWeb.js.symbols 0x55c008 0x962193
  symbolize.py RobloxPlayerWeb.wasm RobloxPlayerWeb.js.symbols < stack.txt

The .wasm and .symbols must come from the same build as the one that crashed
(player-files.json "build" tells which).
"""
import bisect
import re
import sys


def leb(b, i):
    result = shift = 0
    while True:
        byte = b[i]
        i += 1
        result |= (byte & 0x7F) << shift
        shift += 7
        if byte < 0x80:
            return result, i


def sections(b):
    i = 8
    while i < len(b):
        sid = b[i]
        size, j = leb(b, i + 1)
        yield sid, j, j + size
        i = j + size


def imported_functions(b, start):
    count, i = leb(b, start)
    functions = 0
    for _ in range(count):
        length, i = leb(b, i)
        i += length
        length, i = leb(b, i)
        i += length
        kind = b[i]
        i += 1
        if kind == 0:  # function
            _, i = leb(b, i)
            functions += 1
        elif kind in (1, 2):  # table, memory
            if kind == 1:
                i += 1
            flags = b[i]
            i += 1
            _, i = leb(b, i)
            if flags & 1:
                _, i = leb(b, i)
        elif kind == 3:  # global
            i += 2
        elif kind == 4:  # tag
            i += 1
            _, i = leb(b, i)
    return functions


def layout(b):
    """[(body start, body end, function index)] in file order."""
    imports = 0
    bodies = []
    for sid, start, _ in sections(b):
        if sid == 2:
            imports = imported_functions(b, start)
        elif sid == 10:
            count, i = leb(b, start)
            for k in range(count):
                size, j = leb(b, i)
                bodies.append((i, j + size, imports + k))
                i = j + size
    return bodies


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    wasm = open(sys.argv[1], 'rb').read()
    names = {}
    for line in open(sys.argv[2], encoding='utf-8', errors='replace'):
        index, _, name = line.rstrip('\n').partition(':')
        if index.isdigit():
            names[int(index)] = name

    bodies = layout(wasm)
    starts = [b[0] for b in bodies]

    # Chrome writes a frame either as <module>:0x55c008 (a byte offset) or,
    # in console stacks, as $func16944 (the function index).
    tokens = sys.argv[3:] or re.findall(r':0x[0-9a-fA-F]+|\$func\d+', sys.stdin.read())
    for text in tokens:
        text = text.lstrip(':')
        if text.startswith('$func'):
            index = int(text[5:])
            print(f'{text}  {names.get(index, "?")}')
            continue
        offset = int(text, 16)
        k = bisect.bisect_right(starts, offset) - 1
        if k < 0 or offset >= bodies[k][1]:
            print(f'{text}  (not in the code section)')
            continue
        start, _, index = bodies[k]
        print(f'{text}  {names.get(index, f"function {index}")} +0x{offset - start:x}')


if __name__ == '__main__':
    main()
