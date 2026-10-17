/**
 * Real Chrome + local HTTP acceptance for T69/T70. No production code edits.
 * Run from repo root (PowerShell):
 *   $env:PLAYWRIGHT_MODULE='<existing playwright package directory>'
 *   node docs/evidence/2026-10-17-t69-t70-browser-e2e.cjs
 * Requires current backend :8123, frontend :5173, local MySQL/Redis and CLI tools.
 * Requires installed frontend js-yaml and an existing Playwright package/Chrome.
 * Does not install dependencies, send mail, delete files/rows, or use real users.
 * Creates two accounts/spaces and synthetic picture metadata via fixture SQL.
 * Tests real HTTP upload, which may create a small COS object. Never replaces
 * an existing picture: any outgoing upload with an id is blocked before sending.
 * Delayed-response cases forward actual HTTP responses without changing bodies.
 */
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const { spawnSync } = require('node:child_process');
const { randomBytes, randomInt } = require('node:crypto');
const root = path.resolve(__dirname, '../..');
const localRequire = createRequire(path.join(root, 'frontend/package.json'));
const yaml = localRequire('js-yaml');
const playwright = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const ensure = (ok, label) => { if (!ok) throw new Error(label); };
let config = {};
function merge(a, b) {
  for (const [k, v] of Object.entries(b || {})) {
    a[k] = v && typeof v === 'object' && !Array.isArray(v) ? merge(a[k] || {}, v) : v;
  }
  return a;
}
for (const name of ['application.yaml', 'application-local.yaml']) {
  config = merge(config, yaml.load(fs.readFileSync(path.join(root, 'backend/src/main/resources', name), 'utf8')));
}
function configured(value) {
  return String(value ?? '').replace(/\$\{([^}:]+)(?::([^}]*))?\}/g, (_, key, fallback) => {
    const result = process.env[key] ?? fallback;
    ensure(result !== undefined, 'Missing configuration environment variable');
    return result;
  });
}
const db = config.spring.datasource;
const dbUrl = new URL(configured(process.env.SPRING_DATASOURCE_URL ?? db.url).replace(/^jdbc:/, ''));
const rc = config.spring.redis;
const redisHost = configured(process.env.SPRING_REDIS_HOST ?? rc.host);
const frontend = process.env.BROWSER_E2E_FRONTEND || 'http://localhost:5173';
const backend = process.env.BROWSER_E2E_BACKEND || 'http://localhost:8123/api';
for (const value of [frontend, backend, dbUrl.href]) {
  ensure(['localhost', '127.0.0.1', '[::1]'].includes(new URL(value).hostname), 'Loopback only');
}
ensure(['localhost', '127.0.0.1', '::1'].includes(redisHost), 'Redis must be loopback');
const dbName = dbUrl.pathname.slice(1);
ensure(/^\w+$/.test(dbName), 'Invalid local schema');
function command(exe, args, env, label) {
  const r = spawnSync(exe, args, { encoding: 'utf8', env: { ...process.env, ...env }, windowsHide: true, timeout: 20000 });
  const safeCode = String(r.stderr || '').match(/ERROR \d+ \([0-9A-Z]+\)/)?.[0];
  ensure(!r.error && r.status === 0, `${label} failed${safeCode ? ' ' + safeCode : ''} (details suppressed)`);
  return r.stdout.trim();
}
function sql(query) {
  return command(process.env.BROWSER_E2E_MYSQL || 'mysql', ['--protocol=TCP', `--host=${dbUrl.hostname}`, `--port=${dbUrl.port || 3306}`, `--user=${configured(process.env.SPRING_DATASOURCE_USERNAME ?? db.username)}`, '--default-character-set=utf8mb4', '--batch', '--raw', '--skip-column-names', `--database=${dbName}`, `--execute=${query}`], { MYSQL_PWD: configured(process.env.SPRING_DATASOURCE_PASSWORD ?? db.password) }, 'MySQL');
}
function redis(...args) {
  const out = command(process.env.BROWSER_E2E_REDIS || 'redis-cli', ['--raw', '-h', redisHost, '-p', configured(process.env.SPRING_REDIS_PORT ?? rc.port ?? 6379), '-n', configured(process.env.SPRING_REDIS_DATABASE ?? rc.database ?? 0), ...args], { REDISCLI_AUTH: configured(process.env.SPRING_REDIS_PASSWORD ?? rc.password) }, 'Redis');
  ensure(!/^(ERR|NOAUTH|WRONGPASS|WRONGTYPE)\b/.test(out), 'Redis rejected command');
  return out;
}
const fixture = `e2e_browser_${randomBytes(4).toString('hex')}`;
const password = randomBytes(18).toString('base64url');
const accounts = {};
const pictures = {};
function fixturePng() {
  const { deflateSync } = require('node:zlib');
  const chunk = (name, bytes) => {
    const kind = Buffer.from(name, 'ascii');
    const result = Buffer.alloc(bytes.length + 12);
    result.writeUInt32BE(bytes.length); kind.copy(result, 4); bytes.copy(result, 8);
    let crc = 0xffffffff;
    for (const byte of Buffer.concat([kind, bytes])) {
      crc ^= byte;
      for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
    }
    result.writeUInt32BE((crc ^ 0xffffffff) >>> 0, result.length - 4);
    return result;
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(32, 0); header.writeUInt32BE(32, 4); header[8] = 8; header[9] = 2;
  const pixels = Buffer.alloc(32 * (1 + 32 * 3));
  for (let y = 0; y < 32; y++) for (let x = 0; x < 32; x++) {
    const offset = y * 97 + 1 + x * 3;
    pixels[offset] = x < 16 ? 32 : 196;
    pixels[offset + 1] = y < 16 ? 32 : 196;
    pixels[offset + 2] = 128;
  }
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header), chunk('IDAT', deflateSync(pixels)), chunk('IEND', Buffer.alloc(0))]);
}
const png = fixturePng();
const imageUrl = `data:image/png;base64,${png.toString('base64')}`;
let passed = 0;
let browser;
let page;
let context;
const network = [];
const pageErrors = [];
const consoleErrors = [];
let blockedUpload = false;
let phase = 'initialization';
function pass(text) { console.log(`PASS ${++passed}: ${text}`); }
function step(text) { phase = text; console.log(`STEP: ${text}`); }
async function post(endpoint, data) {
  const response = await context.request.post(backend + endpoint, { data });
  ensure(response.status() === 200, `HTTP status at ${endpoint}`);
  const body = await response.json();
  ensure(body.code === 0, `HTTP business=${body.code} at ${endpoint}`);
  return body.data;
}
async function spa(route) {
  await page.evaluate(async (route) => {
    const router = document.querySelector('#app').__vue_app__.config.globalProperties.$router;
    await router.push(route);
  }, route);
  if (route === '/') {
    await page.locator('#homePage').waitFor();
    await page.locator('#spaceDetailPage').waitFor({ state: 'detached' });
  }
}
async function uiLogin(key, initial = false) {
  if (!initial) await page.locator('img[alt="验证码，点击刷新"]').waitFor();
  const gotCaptcha = page.waitForResponse(r => r.url().includes('/api/user/captcha/img') && r.status() === 200, { timeout: 60000 });
  if (initial) await page.goto(frontend + '/user/login');
  else await page.locator('img[alt="验证码，点击刷新"]').click();
  const uuid = (await (await gotCaptcha).json()).data.captchaUuid;
  ensure(/^[\w-]+$/.test(uuid), 'Invalid own captcha UUID');
  const code = redis('GET', 'huoshantuku:auth:captcha:img:' + uuid);
  ensure(code.length === 4, 'Own HTTP captcha missing');
  await page.getByPlaceholder('请输入账号', { exact: true }).fill(accounts[key].account);
  await page.getByPlaceholder('请输入密码', { exact: true }).fill(password);
  await page.getByPlaceholder('请输入验证码', { exact: true }).fill(code);
  const response = page.waitForResponse(r => r.url().endsWith('/api/user/login'));
  await page.locator('#userLoginPage').getByRole('button', { name: /登\s*录/ }).click();
  const login = await (await response).json();
  ensure(login.code === 0 && String(login.data.id) === accounts[key].id, `UI login ${key} failed or wrong identity`);
  await page.waitForURL(frontend + '/');
  await page.locator('#globalHeader .user-name').waitFor();
}
async function uiLogout() {
  await page.locator('#globalHeader .user-entry').hover();
  const response = page.waitForResponse(r => r.url().endsWith('/api/user/logout'));
  await page.getByRole('menuitem', { name: /退出登录/ }).click();
  ensure((await (await response).json()).code === 0, 'UI logout failed');
  await page.waitForURL(/\/user\/login/);
}
async function waitName(name) {
  await page.waitForFunction(name => document.querySelector('input[placeholder="请输入名称"]')?.value === name, name);
}
async function emptyUpload() {
  await page.locator('#addPicturePage .info-empty').waitFor();
  ensure(await page.getByPlaceholder('请输入名称', { exact: true }).count() === 0, 'Old metadata remains');
  ensure(await page.locator('.picture-upload img, .url-preview img').count() === 0, 'Old preview remains');
}
async function componentUid(selector) {
  return page.locator(selector).evaluate(el => el.__vueParentComponent?.uid);
}
function pictureRow(id) {
  ensure(/^\d+$/.test(String(id)), 'Invalid fixture picture id');
  const row = sql(`SELECT id,name,url,spaceId,userId FROM picture WHERE id=${id}`);
  ensure(row && row.split(/\r?\n/).length === 1, 'Missing unique fixture picture');
  return row;
}
async function holdRealResponse(pattern, trigger, accept = () => true) {
  let resolveEntered;
  let rejectEntered;
  let resolveFinished;
  let release;
  let request;
  let started;
  let routeError;
  const finished = new Promise(r => { resolveFinished = r; });
  const entered = new Promise((resolve, reject) => { resolveEntered = resolve; rejectEntered = reject; });
  const gate = new Promise(r => { release = r; });
  let captured = false;
  const handler = async route => {
    if (captured || !accept(route.request())) { await route.continue(); return; }
    captured = true;
    request = route.request();
    started = Date.now();
    try {
      const response = await route.fetch();
      const body = await response.json();
      ensure(body.code === 0, 'Delayed backend response is not successful');
      resolveEntered(body);
      await gate;
      await route.fulfill({ response });
    } catch (error) { routeError = error; rejectEntered(error); }
    finally { resolveFinished(); }
  };
  await page.route(pattern, handler);
  try {
    await trigger();
    const body = await Promise.race([entered, new Promise((_, reject) => setTimeout(() => reject(new Error('Delayed response not reached')), 15000))]);
    return { body, release: async () => {
      const elapsed = Date.now() - started;
      const responsePromise = page.waitForResponse(r => r.request() === request, { timeout: 5000 });
      const completedPromise = page.waitForEvent('requestfinished', { predicate: r => r === request, timeout: 5000 });
      release();
      try {
        await finished;
        ensure(!routeError, 'Delayed route could not fulfill actual response');
        const [response, completed] = await Promise.all([responsePromise, completedPromise]);
        ensure(!completed.failure(), 'Delayed request failed rather than completed');
        ensure(response.status() === 200 && JSON.stringify(await response.json()) === JSON.stringify(body), 'Delayed browser response differs from actual successful backend response');
        ensure(elapsed < 8000, 'Delayed response exceeded Axios timeout safety window');
        console.log(`DELIVERY: ${new URL(request.url()).pathname} real response completed in browser; held=${elapsed}ms (<8000); body unchanged`);
      } finally { await page.unroute(pattern, handler); }
    } };
  } catch (e) { release(); await page.unroute(pattern, handler); throw e; }
}
async function main() {
  console.log(`BROWSER E2E fixture=${fixture}`);
  browser = await playwright.chromium.launch({ executablePath: process.env.BROWSER_E2E_CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
  console.log(`BROWSER: Chrome ${browser.version()}; Playwright ${require(path.join(process.env.PLAYWRIGHT_MODULE || 'playwright', 'package.json')).version}`);
  context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  page = await context.newPage();
  page.setDefaultTimeout(20000);
  page.on('pageerror', error => pageErrors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()); });
  page.on('response', async response => {
    if (!response.url().startsWith(backend + '/')) return;
    try { const body = await response.json(); network.push({ path: new URL(response.url()).pathname, status: response.status(), code: body.code }); }
    catch { network.push({ path: new URL(response.url()).pathname, status: response.status(), code: 'not-json' }); }
  });
  // Safety guard only: valid uploads proceed to the actual backend/COS unchanged.
  await page.route('**/api/picture/upload**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    const data = request.postData() || '';
    const hasId = url.searchParams.has('id') || /name="id"|"id"\s*:/.test(data);
    if (hasId) { blockedUpload = true; await route.abort('blockedbyclient'); return; }
    await route.continue();
  });
  ensure(imageUrl.length <= 512, 'Fixture image URL exceeds current picture URL column');
  ensure((fixture + '_private_a').length <= 30, 'Fixture space name exceeds backend limit');
  console.log(`PREFLIGHT: PNG=${png.length} bytes; dataURL=${imageUrl.length}/512 chars; spaceName=${(fixture + '_private_a').length}/30 chars`);
  step('Create independent account fixtures over HTTP');
  for (const key of ['a', 'b']) {
    const account = `${fixture}_${key}`;
    const email = `${account}@example.invalid`;
    const code = String(randomInt(100000, 1000000));
    ensure(redis('SET', 'huoshantuku:auth:captcha:email:' + email, code, 'NX', 'EX', '600') === 'OK', 'Fixture email key collision');
    const id = String(await post('/user/register', { userAccount: account, userPassword: password, checkPassword: password, email, emailCode: code }));
    ensure(/^\d+$/.test(id), 'Invalid account id');
    accounts[key] = { account, email, id };
    console.log(`FIXTURE: account=${account} userId=${id}`);
  }
  step('Real UI login A, no session injection');
  await uiLogin('a', true);
  const initialDocument = await page.evaluate(() => { window.__e2eDocumentMarker = crypto.randomUUID(); return window.__e2eDocumentMarker; });
  pass('Login UI + real captcha/login/get-login HTTP establish account A');
  step('Create private A space and synthetic picture metadata');
  accounts.a.spaceId = String(await post('/space/add', { spaceName: fixture + '_private_a', spaceLevel: 0, spaceType: 0 }));
  ensure(/^\d+$/.test(accounts.a.spaceId), 'Invalid space id');
  for (const key of ['a', 'b']) {
    const name = fixture + '_picture_' + key;
    const id = sql(`INSERT INTO picture(url,name,introduction,tags,picSize,picWidth,picHeight,picFormat,userId,spaceId,reviewStatus) VALUES('${imageUrl}','${name}','${fixture}','[]',${png.length},32,32,'png',${accounts.a.id},${accounts.a.spaceId},1); SELECT LAST_INSERT_ID()`);
    ensure(/^\d+$/.test(id), 'Invalid synthetic picture id');
    pictures[key] = { id, name };
  }
  sql(`UPDATE space SET totalCount=2,totalSize=${png.length * 2} WHERE id=${accounts.a.spaceId} AND userId=${accounts.a.id}`);
  console.log(`FIXTURE: privateA=${accounts.a.spaceId} pictureA=${pictures.a.id} pictureB=${pictures.b.id}`);
  const originalA = pictureRow(pictures.a.id);
  const editA = `/add_picture?id=${pictures.a.id}&spaceId=${accounts.a.spaceId}`;
  const editB = `/add_picture?id=${pictures.b.id}&spaceId=${accounts.a.spaceId}`;
  if (process.env.BROWSER_E2E_PENDING_DETAIL_PROBE === '1') {
    step('T69 same-context pending detail vs real successful upload regression probe');
    await spa(editB);
    await waitName(pictures.b.name);
    const pending = await holdRealResponse(`**/api/picture/get/vo?id=${pictures.a.id}`, () => spa(editA));
    let uploadedId;
    try {
      ensure(pending.body.data.id === pictures.a.id, 'Wrong held detail');
      await emptyUpload();
      const requestPromise = page.waitForRequest(r => new URL(r.url()).pathname === '/api/picture/upload' && r.method() === 'POST');
      const responsePromise = page.waitForResponse(r => new URL(r.url()).pathname === '/api/picture/upload' && r.request().method() === 'POST');
      const started = Date.now();
      await page.locator('.picture-upload input[type=file]').setInputFiles({ name: fixture + '_race.png', mimeType: 'image/png', buffer: png });
      const request = await requestPromise;
      ensure(!new URL(request.url()).searchParams.has('id'), 'Race upload unexpectedly has id');
      const uploaded = await (await responsePromise).json();
      ensure(uploaded.code === 0 && uploaded.data?.id && uploaded.data.id !== pictures.a.id, `Race actual upload failed business=${uploaded.code}`);
      uploadedId = uploaded.data.id;
      await waitName(uploaded.data.name);
      ensure(Date.now() - started < 8000, 'Probe exceeded original Axios timeout safety window');
      console.log(`RACE: actual upload business=0 id=${uploadedId}; target-id=absent; displayed successful uploaded name before original detail release`);
      ensure(pictureRow(uploadedId), 'Uploaded row missing');
    } finally { await pending.release(); }
    await page.waitForTimeout(400);
    const displayedName = await page.getByPlaceholder('请输入名称', { exact: true }).inputValue();
    ensure(pictureRow(pictures.a.id) === originalA, 'Probe unexpectedly modified original A');
    ensure(!blockedUpload && pageErrors.length === 0, 'Unexpected browser failure during probe');
    if (displayedName === pictures.a.name) {
      console.log(`REGRESSION: P2 confirmed in real Chrome/HTTP; late detail A=${pictures.a.id} overwrites successful uploaded row=${uploadedId}; route context unchanged`);
      console.log('RESULT: REGRESSION DETECTED (actual HTTP upload + unchanged delayed actual detail; no edit/save performed; original A unchanged)');
      process.exitCode = 2;
    } else {
      ensure(displayedName !== '', 'Probe ended with empty unrelated state');
      console.log('RESULT: PENDING DETAIL REGRESSION NOT OBSERVED; inspect whether upload correctly remained current');
      process.exitCode = 3;
    }
    console.log(`RESIDUALS: fixture=${fixture}; 2 accounts, 1 private space, 2 synthetic pictures, 1 actual uploaded row/COS assets; no cleanup`);
    return;
  }
  step('T69 edit A -> top navigation new upload without a document reload');
  await spa(editA);
  await waitName(pictures.a.name);
  const editUid = await componentUid('#addPicturePage');
  await page.getByRole('menuitem', { name: '上传图片', exact: true }).click();
  await page.waitForURL(frontend + '/add_picture');
  await emptyUpload();
  ensure(editUid === await componentUid('#addPicturePage'), 'Test accidentally remounted the route component');
  pass('T69 A -> new: same actual route component, blank metadata and file/URL previews');
  step('T69 real multipart upload from empty context');
  const uploadRequest = page.waitForRequest(r => new URL(r.url()).pathname === '/api/picture/upload' && r.method() === 'POST');
  const uploadResponse = page.waitForResponse(r => new URL(r.url()).pathname === '/api/picture/upload' && r.request().method() === 'POST');
  await page.locator('.picture-upload input[type=file]').setInputFiles({ name: fixture + '.png', mimeType: 'image/png', buffer: png });
  const req = await uploadRequest;
  ensure(!new URL(req.url()).searchParams.has('id') && !/name="id"/.test(req.postData() || ''), 'T69 uploaded with stale id');
  const uploaded = await (await uploadResponse).json();
  ensure(uploaded.code === 0 && uploaded.data?.id && uploaded.data.id !== pictures.a.id, `Real upload failed business=${uploaded.code}`);
  ensure(pictureRow(pictures.a.id) === originalA, 'Original picture A mutated by new upload');
  console.log(`FIXTURE: realUploadedPicture=${uploaded.data.id}`);
  pass('T69 real multipart HTTP/COS upload has no old id; creates distinct DB row; A unchanged');
  step('T69 A -> B on same component');
  await spa(editA);
  await waitName(pictures.a.name);
  await spa(editB);
  await waitName(pictures.b.name);
  ensure(editUid === await componentUid('#addPicturePage'), 'A -> B did not reuse route component');
  pass('T69 A -> B query change loads B and does not retain A form');
  step('T69 delayed real picture response cannot refill new context');
  // AntD pauses toast expiry while hovered; keep real clicks unobstructed.
  await page.mouse.move(8, 800);
  await page.waitForFunction(() => !document.querySelector('.ant-message-notice'), null, { timeout: 7000 });
  await spa('/add_picture');
  await emptyUpload();
  const delayed = await holdRealResponse(`**/api/picture/get/vo?id=${pictures.a.id}`, () => spa(editA));
  ensure(delayed.body.data.id === pictures.a.id, 'Wrong delayed picture');
  await page.getByRole('menuitem', { name: '上传图片', exact: true }).click();
  await emptyUpload();
  await delayed.release();
  await page.waitForTimeout(300);
  await emptyUpload();
  pass('T69 delayed successful real HTTP picture-A reply ignored after A -> new');
  step('T69 file/URL request identity after space context changes');
  await spa(editA);
  await waitName(pictures.a.name);
  await spa(`/add_picture?spaceId=${accounts.a.spaceId}`);
  await emptyUpload();
  await page.getByRole('tab', { name: 'URL 上传' }).click();
  await page.getByPlaceholder('请输入图片地址，如 https://...').fill('http://127.0.0.1:9/' + fixture + '.png');
  const urlRequest = page.waitForRequest(r => new URL(r.url()).pathname === '/api/picture/upload/url' && r.method() === 'POST');
  const urlResponse = page.waitForResponse(r => new URL(r.url()).pathname === '/api/picture/upload/url' && r.request().method() === 'POST');
  await page.locator('.url-picture-upload').getByRole('button', { name: /提\s*交/ }).click();
  const urlReq = (await urlRequest).postDataJSON();
  ensure(!Object.hasOwn(urlReq, 'id') && urlReq.spaceId === accounts.a.spaceId, 'URL request retains old id or wrong space');
  const urlResult = await (await urlResponse).json();
  ensure(urlResult.code !== 0, 'Expected backend to reject loopback URL');
  ensure(pictureRow(pictures.a.id) === originalA, 'URL rejection changed A');
  pass('T69 actual URL-upload HTTP has no old id and correct space; backend rejects loopback; A unchanged');
  step('T70 real private page, Teleport share modal and QR canvas');
  await spa('/space/' + accounts.a.spaceId);
  await page.getByRole('heading', { name: new RegExp(fixture + '_private_a') }).waitFor();
  await page.locator(`.picture-card img[alt="${pictures.a.name}"]`).waitFor();
  const spaceUid = await componentUid('#spaceDetailPage');
  const card = page.locator('.picture-card').filter({ has: page.locator(`img[alt="${pictures.a.name}"]`) });
  await card.locator('[aria-label="share-alt"]').click();
  const share = page.getByRole('dialog', { name: '分享图片' });
  await share.waitFor();
  ensure((await share.innerText()).includes('/picture/' + pictures.a.id), 'Share link mismatch');
  const canvas = share.locator('canvas');
  ensure(await canvas.count() === 1, 'Real QR canvas missing');
  await page.waitForFunction(() => {
    const el = document.querySelector('.share-modal canvas');
    if (!el || !el.width || !el.height) return false;
    const data = el.getContext('2d').getImageData(0, 0, el.width, el.height).data;
    let dark = 0;
    let light = 0;
    for (let i = 0; i < data.length; i += 4) {
      if (data[i + 3] < 200) continue;
      if (data[i] < 100 && data[i + 1] < 100 && data[i + 2] < 100) dark++;
      if (data[i] > 200 && data[i + 1] > 200 && data[i + 2] > 200) light++;
    }
    return dark > 100 && light > 100;
  });
  const drawn = await canvas.evaluate(el => {
    const data = el.getContext('2d').getImageData(0, 0, el.width, el.height).data;
    let opaque = 0;
    for (let i = 3; i < data.length; i += 4) if (data[i] > 200) opaque++;
    return { width: el.width, height: el.height, opaque };
  });
  ensure(drawn.width > 0 && drawn.height > 0 && drawn.opaque > 200, 'QR canvas not drawn');
  console.log(`QR: actual canvas ${drawn.width}x${drawn.height}; opaquePixels=${drawn.opaque}; both dark and light pixels verified`);
  await share.getByRole('button', { name: 'Close' }).click();
  await share.waitFor({ state: 'hidden' });
  pass('T70 actual AntD Teleport share modal renders private link and nonblank QR canvas');
  step('T70 same-user KeepAlive return preserves parent and batch form');
  await page.getByRole('button', { name: /批量编辑/, exact: false }).click();
  const batch = page.getByRole('dialog', { name: '批量编辑图片' });
  await batch.waitFor();
  const oldRule = fixture + '_old_private_rule';
  await batch.getByPlaceholder('请输入命名规则，输入 {序号} 可动态生成').fill(oldRule);
  await batch.getByRole('button', { name: 'Close' }).click();
  await spa('/');
  await page.locator('#homePage').waitFor();
  await spa('/space/' + accounts.a.spaceId);
  await page.locator(`.picture-card img[alt="${pictures.a.name}"]`).waitFor();
  ensure(spaceUid === await componentUid('#spaceDetailPage'), 'Same-user return destroyed KeepAlive parent');
  await page.getByRole('button', { name: /批量编辑/ }).click();
  await batch.waitFor();
  ensure(await batch.getByPlaceholder('请输入命名规则，输入 {序号} 可动态生成').inputValue() === oldRule, 'Same-user local form was unnecessarily destroyed');
  await batch.getByRole('button', { name: 'Close' }).click();
  pass('T70 same identity return keeps actual component and non-submitted batch form');
  step('T70 same identity denied refresh destroys Teleport and old child state');
  await card.locator('[aria-label="share-alt"]').click();
  await share.waitFor();
  await spa('/');
  // Dedicated fixture only: move ownership temporarily so real backend denies A.
  sql(`UPDATE space SET userId=${accounts.b.id} WHERE id=${accounts.a.spaceId} AND userId=${accounts.a.id}`);
  try {
    const denied = page.waitForResponse(async r => r.url().includes('/api/space/get/vo?id=' + accounts.a.spaceId) && r.status() === 200);
    await spa('/space/' + accounts.a.spaceId);
    const result = await (await denied).json();
    ensure(result.code === 40101, `Expected real denied space response, got ${result.code}`);
    await page.locator('#spaceDetailPage').waitFor();
    ensure(spaceUid === await componentUid('#spaceDetailPage'), 'Denied refresh did not preserve the same cached page instance');
    await page.waitForFunction(() => document.querySelector('#spaceDetailPage') && !document.querySelector('#spaceDetailPage .picture-card') && !document.querySelector('.share-modal'));
    ensure(!await page.getByText(oldRule, { exact: true }).count(), 'Old batch rule leaked');
    ensure(await page.locator('#spaceDetailPage').getByRole('button', { name: /批量编辑/ }).count() === 0, 'Denied page retains edit permission');
    ensure(!(await page.locator('body').innerText()).includes(pictures.a.name), 'Denied page retains private name');
    ensure(!(await page.locator('body').innerText()).includes('/picture/' + pictures.a.id), 'Denied page retains private share link');
  } finally {
    sql(`UPDATE space SET userId=${accounts.a.id} WHERE id=${accounts.a.spaceId} AND userId=${accounts.b.id}`);
  }
  pass('T70 real backend 40101 on reactivation clears private cards/actions and destroys active Teleport share modal');
  await spa('/');
  await spa('/space/' + accounts.a.spaceId);
  await page.locator(`.picture-card img[alt="${pictures.a.name}"]`).waitFor();
  await page.getByRole('button', { name: /批量编辑/ }).click();
  await batch.waitFor();
  ensure(await batch.getByPlaceholder('请输入命名规则，输入 {序号} 可动态生成').inputValue() === '', 'Denied refresh retains old batch child form');
  await batch.getByRole('button', { name: 'Close' }).click();
  pass('T70 authorized retry recreates blank batch form after failed permission refresh');
  step('T70 real UI A logout -> B login without reload destroys private cache');
  const oldUid = await componentUid('#spaceDetailPage');
  await uiLogout();
  await uiLogin('b');
  ensure(await page.evaluate(() => window.__e2eDocumentMarker) === initialDocument, 'Identity test used document reload');
  accounts.b.spaceId = String(await post('/space/add', { spaceName: fixture + '_private_b', spaceLevel: 0, spaceType: 0 }));
  const bDenied = page.waitForResponse(r => r.url().includes('/api/space/get/vo?id=' + accounts.a.spaceId) && r.status() === 200);
  await spa('/space/' + accounts.a.spaceId);
  const denial = await (await bDenied).json();
  ensure(denial.code === 40101, `B unauthorized code=${denial.code}`);
  await page.locator('#spaceDetailPage').waitFor();
  ensure(oldUid !== await componentUid('#spaceDetailPage'), 'Cross-identity reused A cached parent');
  ensure(await page.locator('.picture-card').count() === 0, 'B sees A private picture');
  ensure(!(await page.locator('body').innerText()).includes(pictures.a.name), 'B sees private A name');
  ensure(!(await page.locator('body').innerText()).includes('/picture/' + pictures.a.id), 'B sees private share link');
  ensure(await page.getByRole('dialog').count() === 0, 'B inherits active private dialog');
  pass('T70 real UI logout/login A -> B, same document: fresh parent, real backend denial, zero A cards/links/dialogs');
  await spa('/space/' + accounts.b.spaceId);
  await page.getByRole('heading', { name: new RegExp(fixture + '_private_b') }).waitFor();
  ensure(!(await page.locator('body').innerText()).includes(fixture + '_private_a'), 'B own space shows old title');
  pass('T70 B own authorized private space renders correctly without A title or cards');
  step('T70 delayed real private-list response crossing logout/login');
  await uiLogout();
  await uiLogin('a');
  await spa('/');
  const lateSourceUidBefore = await componentUid('#homePage');
  const held = await holdRealResponse('**/api/picture/list/page/vo/cache', () => spa('/space/' + accounts.a.spaceId), request => request.postDataJSON()?.spaceId === accounts.a.spaceId);
  ensure(held.body.data.records.some(r => r.id === pictures.a.id), 'Delayed real list lacks A fixture');
  await page.locator('#spaceDetailPage').waitFor();
  const lateSourceUid = await componentUid('#spaceDetailPage');
  ensure(lateSourceUid !== lateSourceUidBefore, 'List probe must reach space page');
  await uiLogout();
  await uiLogin('b');
  await held.release();
  await page.waitForTimeout(300);
  ensure(await page.locator('.picture-card').filter({ has: page.locator(`img[alt="${pictures.a.name}"]`) }).count() === 0, 'Delayed private list leaked to B');
  ensure(!(await page.locator('body').innerText()).includes(pictures.a.name), 'Delayed private name leaked to B');
  const lateDenied = page.waitForResponse(r => r.url().includes('/api/space/get/vo?id=' + accounts.a.spaceId) && r.status() === 200);
  await spa('/space/' + accounts.a.spaceId);
  ensure((await (await lateDenied).json()).code === 40101, 'B must still be denied after actual delayed response delivery');
  await page.locator('#spaceDetailPage').waitFor();
  ensure(lateSourceUid !== await componentUid('#spaceDetailPage'), 'A private cached parent revived after delayed delivery');
  ensure(await page.locator('#spaceDetailPage .picture-card').count() === 0, 'Delayed private list leaked on B space re-entry');
  const lateBody = await page.locator('body').innerText();
  ensure(!lateBody.includes(pictures.a.name) && !lateBody.includes(fixture + '_private_a') && !lateBody.includes('/picture/' + pictures.a.id), 'Delayed private content exposed on B re-entry');
  pass('T70 delayed actual successful private-list reply delivered before timeout; B re-entry is denied with new parent and no A content');
  ensure(!blockedUpload, 'Safety guard caught stale id: T69 regression');
  ensure(pageErrors.length === 0, `Uncaught page errors: ${pageErrors.join(' | ')}`);
  const unexpected = consoleErrors.filter(s => !s.includes('[ant-design-vue: Modal]') && !s.includes('[ant-design-vue: Drawer]'));
  ensure(unexpected.length === 0, `Unexpected browser console error: ${unexpected.join(' | ')}`);
  pass('No uncaught browser errors, no unexpected console errors and no stale-id upload blocked');
  console.log(`RESULT: PASS (${passed} groups; ${network.length} observed browser API responses)`);
  console.log(`RESIDUALS: 2 account rows, 2 private spaces, 2 SQL fixture pictures, 1 real uploaded picture/COS object; no cleanup or deletion`);
  console.log('LIMITS: email delivery bypassed using dedicated codes; SQL image metadata fixture; local Chrome/HTTP, no nginx/TLS/production. Delayed-response cases use route.fetch+unchanged response, explicitly not a fully untouched network.');
  console.log(`BROWSER WARNINGS: ${consoleErrors.length} existing AntD deprecation console entries; PAGE ERRORS: ${pageErrors.length}`);
  console.log('HTTP SUMMARY: ' + JSON.stringify(network.reduce((a, r) => { const k = `${r.path} status=${r.status} business=${r.code}`; a[k] = (a[k] || 0) + 1; return a; }, {})));
}
main().catch(async error => {
  if (page && !page.isClosed()) {
    const image = path.join(root, 'backend/target', fixture + '-browser-failure.png');
    try { await page.screenshot({ path: image, fullPage: true }); console.error(`AUXILIARY SCREENSHOT: ${image}`); }
    catch (captureError) { console.error(`SCREENSHOT unavailable: ${captureError.name}`); }
  }
  console.error(`RESULT: FAIL phase=${phase}; ${error.message}`);
  console.error(`RESIDUALS: fixture=${fixture}; completed=${passed}; no deletion/cleanup attempted; inspect retained fixture rows and local service logs`);
  process.exitCode = 1;
}).finally(async () => {
  if (browser) await browser.close();
});