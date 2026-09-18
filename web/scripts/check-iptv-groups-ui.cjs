const path = require('node:path');
const fs = require('node:fs');
const http = require('node:http');
const assert = require('node:assert/strict');
const esbuild = require('esbuild');
const { chromium } = require('@playwright/test');

(async () => {
  const root = path.resolve(__dirname, '..');
  const out = path.join(root, '.iptv-groups-ui-test');
  fs.mkdirSync(out, { recursive: true });
  await esbuild.build({ entryPoints: [path.join(root, 'tests/iptv-groups-ui/entry.tsx')], bundle: true,
    outfile: path.join(out, 'app.js'), jsx: 'automatic', plugins: [{ name: 'fixture', setup(build) {
      build.onResolve({ filter: /^@\/lib\/i18n$/ }, () => ({ path: 'i18n', namespace: 'fixture' }));
      build.onLoad({ filter: /.*/, namespace: 'fixture' }, () => ({ contents: 'export const useTranslation = () => text => text;', loader: 'js' }));
    }}] });
  const server = http.createServer((req, res) => {
    if (req.url === '/app.js') { res.setHeader('content-type','application/javascript'); res.end(fs.readFileSync(path.join(out,'app.js'))); }
    else if (req.url === '/style.css') { res.setHeader('content-type','text/css'); res.end(fs.readFileSync(path.join(root,'app/globals.css'))); }
    else res.end('<html><head><meta name="viewport" content="width=device-width,initial-scale=1"/><link rel="stylesheet" href="/style.css"/></head><body><div id="root"></div><script src="/app.js"></script></body></html>');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let browser;
  try {
    browser = await chromium.launch({ channel:'chrome', headless:true });
    for (const [name,width,height] of [['desktop',1280,900],['phone',390,844]]) {
      const page = await browser.newPage({ viewport:{width,height} });
      const errors=[]; page.on('pageerror', e=>errors.push(e.message));
      await page.goto(`http://127.0.0.1:${server.address().port}`);
      const hold = page.getByRole('button',{name:'Hold to move: News',exact:true});
      await hold.click(); await page.keyboard.press('ArrowDown'); await page.keyboard.press('ArrowDown');
      assert.equal(await hold.getAttribute('aria-pressed'),'true');
      assert.equal(await page.locator('.iptv-group-name').nth(2).innerText(),'News\n1');
      await page.keyboard.press('Escape'); assert.equal(await hold.getAttribute('aria-pressed'),'false');
      await page.reload();
      assert.equal(await page.locator('.iptv-group-name').nth(2).innerText(),'News\n1');
      await page.getByRole('button',{name:'Hide all',exact:true}).click();
      assert.equal(await page.getByRole('button',{name:/^Show: /}).count(),4);
      await page.getByRole('combobox').selectOption('other');
      assert.equal(await page.getByRole('button',{name:'Hide: News',exact:true}).count(),1);
      await page.getByRole('combobox').selectOption('p');
      await page.getByRole('button',{name:'Show all',exact:true}).click();
      await page.getByRole('button',{name:'Reset order',exact:true}).click();
      assert.equal(await page.locator('.iptv-group-name').first().innerText(),'News\n1');
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
      await page.screenshot({path:path.join(out,`${name}.png`),fullPage:true});
      assert.deepEqual(errors,[]); await page.close(); console.log(`${name}: passed`);
    }
  } finally { if (browser) await browser.close(); server.close(); }
})().catch(error=>{console.error(error);process.exitCode=1;});
