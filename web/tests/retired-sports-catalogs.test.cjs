const test = require('node:test');
const assert = require('node:assert/strict');
const ts = require('typescript');
const fs = require('node:fs');
const vm = require('node:vm');
const code = ts.transpileModule(fs.readFileSync(require.resolve('../lib/catalogs.ts'), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
}).outputText;
const sandbox = { exports: {} };
vm.runInNewContext(code, sandbox);
const { mergeCatalogs, defaultCatalogs } = sandbox.exports;

test('retired Android sports defaults cannot return through cloud sync', () => {
  const rows = mergeCatalogs([
    { id: 'sports', title: 'Sports', sourceType: 'PREINSTALLED', isPreinstalled: true },
    { id: 'popular_live_tv', title: 'Popular Live Sports', sourceType: 'PREINSTALLED' },
    { id: 'my-sports', title: 'Sports', sourceType: 'ADDON', addonId: 'my-addon' },
  ]);
  assert.ok(!rows.some(row => ['sports', 'popular_live_tv'].includes(row.id)));
  assert.ok(rows.some(row => row.id === 'my-sports'));
  assert.ok(defaultCatalogs.every(row => !['sports', 'popular_live_tv'].includes(row.id)));
});
