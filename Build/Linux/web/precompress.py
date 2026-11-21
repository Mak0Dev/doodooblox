#!/usr/bin/env python3
"""Writes <file>.gz (and <file>.br when the brotli module is installed) next to
each file given, for servers that send precompressed files: the web player's
.data and .wasm shrink to about half and a third.

Also writes player-files.json beside them: the build's identity and each
file's size and SHA-256, which player-cache.js uses to tell a cached copy from
another build, to check a download and to show download progress.

    pip install brotli    # optional, ~10% smaller than gzip
"""
import gzip
import hashlib
import json
import os
import sys

try:
    import brotli
except ImportError:
    brotli = None

manifest = {'files': {}, 'sha256': {}}
digest = hashlib.sha1()

for path in sys.argv[1:]:
    if not os.path.isfile(path):
        continue
    with open(path, 'rb') as f:
        data = f.read()

    manifest['files'][os.path.basename(path)] = len(data)
    manifest['sha256'][os.path.basename(path)] = hashlib.sha256(data).hexdigest()
    digest.update(data)

    with open(path + '.gz', 'wb') as f:
        f.write(gzip.compress(data, compresslevel=9, mtime=0))

    if brotli is not None:
        with open(path + '.br', 'wb') as f:
            f.write(brotli.compress(data, quality=9))
    elif os.path.exists(path + '.br'):
        os.remove(path + '.br')  # stale from an earlier build

    print('%s: %d -> gz %d%s' % (os.path.basename(path), len(data), os.path.getsize(path + '.gz'),
          ', br %d' % os.path.getsize(path + '.br') if brotli is not None else ''))

if manifest['files']:
    manifest['build'] = digest.hexdigest()[:16]
    with open(os.path.join(os.path.dirname(os.path.abspath(sys.argv[1])), 'player-files.json'), 'w') as f:
        json.dump(manifest, f, indent=2)
