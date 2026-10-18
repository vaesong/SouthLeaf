// Requires miniprogram-automator installed under the isolated Trellis runtime directory.
const automator = require('../../.trellis/.runtime/wechat-automation/node_modules/miniprogram-automator');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
(async () => {
  const mini = await automator.connect({ wsEndpoint: 'ws://127.0.0.1:9420' });
  const output = path.resolve(__dirname, '../../frontend/dist/wechat-native-render');
  fs.mkdirSync(output, { recursive: true });
  const fingerprints = new Map();
  const capture = async (name) => {
    const file = path.join(output, name + '.png');
    await mini.screenshot({ path: file });
    const hash = crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
    assert.ok(!fingerprints.has(hash), `Identical screens ${name} / ${fingerprints.get(hash)}; check for a native overlay before trusting screenshots`);
    fingerprints.set(hash, name);
  };
  try {
    const screens = [
      ['home', 'switchTab', '/pages/index/index'],
      ['works', 'switchTab', '/pages/works/works'],
      ['services', 'switchTab', '/pages/package/package'],
      ['about', 'switchTab', '/pages/about/about'],
      ['work-detail', 'navigateTo', '/pages/works/workdetail?workId=demo-work-family'],
      ['package-detail', 'redirectTo', '/pages/package/pkgdetail?packageId=demo-package-family'],
    ];
    const only = (process.env.SOUTHLEAF_RENDER_SCREENS || "").split(",").filter(Boolean);
    for (const [name, method, url] of screens.filter(([name]) => !only.length || only.includes(name))) {
      await mini[method](url);
      let state;
      for (let i = 0; i < 40; i++) {
        state = await mini.evaluate(() => {
          const pages = getCurrentPages(); const page = pages[pages.length - 1];
          return { route: page.route, status: page.data.status,
            mode: page.data.dataMode };
        });
        if (state.status === 'success') break;
        if (['error', 'unavailable'].includes(state.status)) throw new Error(JSON.stringify(state));
        await wait(250);
      }
      if (state.status !== 'success') throw new Error('Render timeout: ' + name);
      const details = await mini.evaluate(async (name) => {
        const pages = getCurrentPages(); const page=pages[pages.length-1]; const vm=page.data;
        if (name === 'home') return { curated: vm.home.businesses.map((b) => b.id), packages: vm.home.packages.length };
        if (name === 'services') return { groups: vm.serviceGroups.length, packages: vm.packages.length };
        if (name === 'about') return { platforms: vm.channels.map((c) => c.platform), roles: vm.team.map((m) => m.role), qr: vm.channels.filter((c) => c.qrCodeMedia && c.qrCodeStatus === 'PLACEHOLDER').length };
        if (name === 'works') { await page.selectSort('newest'); page.selectLayout('grid'); return { layout: vm.layout, sort: vm.sort }; }
        if (name === 'work-detail') { page.selectLayout('album'); return { media: vm.availableMedia.length }; }
        return {};
      }, name);
      if (name === 'home') { assert.deepEqual(details.curated, ['family', 'portrait', 'wedding']); assert.equal(details.packages, 0); }
      if (name === 'services') { assert.equal(details.groups, 6); assert.equal(details.packages, 6); }
      if (name === 'about') { assert.deepEqual(details.platforms, ['wechat', 'douyin', 'xiaohongshu']); assert.deepEqual(details.roles, ['摄影师', '化妆师']); assert.equal(details.qr, 3); }
      if (name === 'work-detail') assert.equal(details.media, 3);
      await wait(700);
      await capture(name);
      console.log(JSON.stringify({ screen: name, ...state, details }));
      if (name === 'works') {
        const page = await mini.currentPage();
        console.log('SORT_CHECK opening'); await (await page.$('.sort-toggle')).tap(); await wait(350); console.log('SORT_CHECK opened');
        assert.ok(await page.$('.sort-popover')); console.log('SORT_CHECK capturing'); await capture('works-sort-menu'); console.log('SORT_CHECK captured');
        console.log('SORT_CHECK choosing oldest'); await (await page.$$('.sort-choice'))[1].tap(); await wait(350); console.log('SORT_CHECK oldest dispatched');
        let sorted;
        for (let i = 0; i < 40; i++) {
          sorted = await mini.evaluate(() => { const vm = getCurrentPages()[0].data; return { status: vm.status, sort: vm.sort, layout: vm.layout, category: vm.selectedBusinessId, open: vm.showSortMenu, dates: vm.works.map((w) => Date.parse(w.publishedAt)) }; });
          if (sorted.status === 'success' && sorted.sort === 'oldest') break; await wait(250);
        }
        assert.equal(sorted.status, 'success'); assert.equal(sorted.sort, 'oldest'); assert.equal(sorted.layout, 'grid'); assert.equal(sorted.category, ''); assert.equal(sorted.open, false);
        assert.equal(sorted.dates.length, 6); assert.ok(sorted.dates.every((value, i, all) => Number.isFinite(value) && (i === 0 || value >= all[i - 1])));
        await wait(500); await capture('works-oldest');
        await (await page.$('.sort-toggle')).tap(); await wait(150); await (await page.$$('.sort-choice'))[0].tap();
        for (let i = 0; i < 40; i++) {
          sorted = await mini.evaluate(() => { const vm = getCurrentPages()[0].data; return { status: vm.status, sort: vm.sort, dates: vm.works.map((w) => Date.parse(w.publishedAt)) }; });
          if (sorted.status === 'success' && sorted.sort === 'newest') break; await wait(250);
        }
        assert.equal(sorted.status, 'success'); assert.equal(sorted.sort, 'newest'); assert.equal(sorted.dates.length, 6); assert.ok(sorted.dates.every((value, i, all) => Number.isFinite(value) && (i === 0 || value <= all[i - 1])));
        console.log('SORT_TAP_PASS oldest/newest; date order verified; category/layout preserved');
      }
      if (name === 'about') {
        await mini.callWxMethod('pageScrollTo', { selector: '.find-us', duration: 0 });
        await wait(450); await capture('about-contact'); await mini.pageScrollTo(0);
      }
      if (['works', 'work-detail'].includes(name)) {
        const steps = name === 'works' ? ['editorial', 'list', 'grid'] : ['overview', 'album'];
        for (const expected of steps) {
          const page = await mini.currentPage(); const control = await page.$('.layout-toggle');
          assert.ok(control, 'Direct layout control must be rendered'); await control.tap(); await wait(350);
          const layout = await mini.evaluate(() => { const pages = getCurrentPages(); return pages[pages.length - 1].data.layout; });
          assert.equal(layout, expected);
          if (expected === 'editorial') await capture('works-album');
          if (expected === 'list') await capture('works-list');
          if (expected === 'overview') await capture('work-overview');
          console.log('DIRECT_LAYOUT_TAP_PASS screen=' + name + ' layout=' + layout);
        }
      }
    }
    await mini.switchTab('/pages/about/about');
  } finally { await mini.disconnect(); }
})().catch((error) => { console.error(error); process.exit(1); });
