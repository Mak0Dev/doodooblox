// Production pass over the web player's page files, run by the build in place
// in build/web: scripts are minified and their local names mangled, comments
// are dropped everywhere (in HTML too), CSS is squeezed. The sources in
// Linux/web stay as they are.
//
//   node minify.js <emscripten>/node_modules <file.js|file.html>...
//
// terser and html-minifier-terser both come with Emscripten.
'use strict';

const fs = require('fs');
const path = require('path');

const modules = process.argv[2];
const terser = require(path.join(modules, 'terser'));
const htmlMinifier = require(path.join(modules, 'html-minifier-terser'));

const jsOptions = {
  ecma: 2017,
  compress: { passes: 2 },
  mangle: true,
  format: { comments: false },
};

async function js(source) {
  return (await terser.minify(source, jsOptions)).code;
}

async function html(source) {
  return htmlMinifier.minify(source, {
    collapseWhitespace: true,
    removeComments: true,
    minifyCSS: true,
    minifyJS: jsOptions,
  });
}

(async () => {
  for (const file of process.argv.slice(3)) {
    const source = fs.readFileSync(file, 'utf8');
    const result = file.endsWith('.html') ? await html(source) : await js(source);
    fs.writeFileSync(file, result);
    console.log(`minified ${path.basename(file)}: ${source.length} -> ${result.length} bytes`);
  }
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
