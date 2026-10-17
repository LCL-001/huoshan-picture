/**
 * T68 local HTTP E2E. Run at repo root:
 * node docs/evidence/2026-10-17-t68-http-e2e.mjs
 * Prerequisites: running latest backend :8123, existing MySQL/Redis, node >=20,
 * mysql and redis-cli on PATH, installed frontend js-yaml, local YAML secrets.
 * Only two newly registered e2e_t68_* accounts are changed. No mail/model/COS.
 * Email codes are test fixtures seeded with SET NX EX 300, not real email proof.
 * Image answers are read ONLY for captcha UUIDs returned by this run's HTTP calls.
 * No explicit DEL, SQL DELETE, cleanup, service start/stop, or secret output.
 * Accounts remain; authentication/rate-limit keys retain application TTLs.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
import { randomBytes, randomInt } from 'node:crypto';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(path.join(root, 'frontend/package.json'));
const yaml = require('js-yaml');
let passed = 0;
let requests = 0;
const fixture = `e2e_t68_${new Date().toISOString().slice(0, 10).replaceAll('-', '')}_${randomBytes(5).toString('hex')}`;
function ensure(condition, label) {
  if (!condition) throw new Error(label);
}
function pass(label) {
  passed++;
  console.log(`PASS ${passed}: ${label}`);
}
function merge(a, b) {
  for (const [k, v] of Object.entries(b ?? {})) {
    if (v && typeof v === 'object' && !Array.isArray(v)) a[k] = merge(a[k] ?? {}, v);
    else a[k] = v;
  }
  return a;
}
function configured(value) {
  return String(value ?? '').replace(/\$\{([^}:]+)(?::([^}]*))?\}/g, (_, name, fallback) => {
    const resolved = process.env[name] ?? fallback;
    ensure(resolved !== undefined, 'Required configuration environment variable is missing');
    return resolved;
  });
}
let config = {};
for (const file of ['application.yaml', 'application-local.yaml']) {
  const p = path.join(root, 'backend/src/main/resources', file);
  ensure(fs.existsSync(p), 'Required local YAML is unavailable');
  config = merge(config, yaml.load(fs.readFileSync(p, 'utf8')));
}
const base = new URL(process.env.T68_HTTP_BASE ?? 'http://127.0.0.1:8123/api/');
ensure(['localhost', '127.0.0.1', '[::1]'].includes(base.hostname), 'HTTP base must be loopback');
ensure(base.protocol === 'http:', 'This local acceptance requires HTTP loopback');
if (!base.pathname.endsWith('/')) base.pathname += '/';
const ds = config.spring.datasource;
const dbUrl = new URL(configured(process.env.SPRING_DATASOURCE_URL ?? ds.url).replace(/^jdbc:/, ''));
ensure(['localhost', '127.0.0.1', '[::1]'].includes(dbUrl.hostname), 'Database must be loopback');
const dbName = dbUrl.pathname.slice(1);
ensure(/^\w+$/.test(dbName), 'Invalid local database name');
const dbUser = configured(process.env.SPRING_DATASOURCE_USERNAME ?? ds.username);
const dbPassword = configured(process.env.SPRING_DATASOURCE_PASSWORD ?? ds.password);
const rc = config.spring.redis;
const redisHost = configured(process.env.SPRING_REDIS_HOST ?? rc.host);
ensure(['localhost', '127.0.0.1', '::1'].includes(redisHost), 'Redis must be loopback');
const redisArgs = ['--raw', '-h', redisHost, '-p', configured(process.env.SPRING_REDIS_PORT ?? rc.port ?? 6379), '-n', configured(process.env.SPRING_REDIS_DATABASE ?? rc.database ?? 0)];
const redisPassword = configured(process.env.SPRING_REDIS_PASSWORD ?? rc.password);
const tokenName = configured(config['sa-token']?.['token-name'] ?? 'satoken');
ensure(/^[\w-]+$/.test(tokenName), 'Invalid configured token name');
function command(executable, args, env, label) {
  const r = spawnSync(executable, args, { encoding: 'utf8', env: { ...process.env, ...env }, windowsHide: true, timeout: 15000 });
  ensure(!r.error && r.status === 0, `${label} command failed (details suppressed to protect credentials)`);
  return r.stdout.trim();
}
function redis(...args) {
  const out = command(process.env.T68_REDIS_CLI ?? 'redis-cli', [...redisArgs, ...args], { REDISCLI_AUTH: redisPassword }, 'Redis');
  ensure(!/^(?:ERR|NOAUTH|WRONGPASS|WRONGTYPE|READONLY)\b/.test(out), 'Redis rejected the command');
  return out;
}
function sql(query) {
  return command(process.env.T68_MYSQL_CLI ?? 'mysql', [
    '--protocol=TCP', `--host=${dbUrl.hostname}`, `--port=${dbUrl.port || 3306}`, `--user=${dbUser}`,
    '--default-character-set=utf8mb4', '--batch', '--raw', '--skip-column-names', `--database=${dbName}`, `--execute=${query}`,
  ], { MYSQL_PWD: dbPassword }, 'MySQL');
}
function dbUserRow(account) {
  ensure(account.startsWith(fixture) && /^e2e_t68_[\w]+$/.test(account), 'Refusing non-fixture SQL lookup');
  const rows = sql(`SELECT id,email,isDelete FROM user WHERE userAccount='${account}'`).split(/\r?\n/);
  ensure(rows.length === 1 && /^\d+\t/.test(rows[0]), 'Expected exactly one dedicated fixture row');
  const [id, email, deleted] = rows[0].split('\t');
  ensure(deleted === '0', 'Fixture unexpectedly soft deleted');
  return { id, email };
}
function emailKey(email) {
  ensure(email.startsWith(fixture) && email.endsWith('@example.invalid'), 'Refusing non-fixture email key');
  return `huoshantuku:auth:captcha:email:${email}`;
}
function seed(email) {
  const value = String(randomInt(100000, 1000000));
  ensure(redis('SET', emailKey(email), value, 'NX', 'EX', '300') === 'OK', 'Dedicated email fixture key collision');
  return value;
}
async function http(endpoint, body, cookies = {}) {
  const cookie = Object.entries(cookies).map(([k, v]) => `${k}=${v}`).join('; ');
  const headers = { Accept: 'application/json' };
  if (cookie) headers.Cookie = cookie;
  if (body !== undefined) headers['Content-Type'] = 'application/json; charset=utf-8';
  let response;
  try {
    response = await fetch(new URL(endpoint.replace(/^\//, ''), base), {
      method: body === undefined ? 'GET' : 'POST', headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      redirect: 'error', signal: AbortSignal.timeout(15000),
    });
  } catch {
    throw new Error(`HTTP transport failed at ${endpoint}`);
  }
  requests++;
  ensure(response.status === 200, `Unexpected HTTP status ${response.status} at ${endpoint}`);
  let data;
  try { data = await response.json(); } catch { throw new Error(`Invalid JSON at ${endpoint}`); }
  ensure(Number.isInteger(data.code), `Missing business code at ${endpoint}`);
  console.log(`HTTP ${requests}: ${endpoint} status=${response.status} business=${data.code}`);
  const next = { ...cookies };
  for (const item of response.headers.getSetCookie()) {
    const pair = item.split(';', 1)[0];
    const pos = pair.indexOf('=');
    if (pos > 0) next[pair.slice(0, pos)] = pair.slice(pos + 1);
  }
  return { data, cookies: next };
}
async function register(account, email, password) {
  const code = seed(email);
  const r = await http('/user/register', { userAccount: account, userPassword: password, checkPassword: password, email, emailCode: code });
  ensure(r.data.code === 0, 'HTTP fixture registration failed');
  const row = dbUserRow(account);
  ensure(typeof r.data.data === 'string' && r.data.data === row.id && row.email === email, 'HTTP registration and MySQL disagree');
  ensure(redis('EXISTS', emailKey(email)) === '0', 'Registration did not consume the test email code');
  console.log(`FIXTURE: account=${account} userId=${row.id}`);
  return row;
}
async function login(account, password, expected = 0) {
  const captcha = await http('/user/captcha/img');
  ensure(captcha.data.code === 0, 'HTTP image captcha failed');
  const uuid = captcha.data.data?.captchaUuid;
  ensure(typeof uuid === 'string' && /^[\w-]+$/.test(uuid), 'Invalid own HTTP captcha UUID');
  const answer = redis('GET', `huoshantuku:auth:captcha:img:${uuid}`);
  ensure(answer.length > 0, 'Own HTTP captcha answer is unavailable');
  const result = await http('/user/login', { userAccount: account, userPassword: password, captchaUuid: uuid, captchaCode: answer });
  ensure(result.data.code === expected, 'Unexpected login business result');
  ensure(redis('EXISTS', `huoshantuku:auth:captcha:img:${uuid}`) === '0', 'Image captcha not consumed');
  if (expected === 0) {
    ensure(result.cookies.SESSION && result.cookies[tokenName], 'Expected actual Spring Session and Sa-Token cookies');
    const row = dbUserRow(account);
    ensure(result.data.data.id === row.id, 'Login identity differs from MySQL');
    const key = `${tokenName}:space:token:${decodeURIComponent(result.cookies[tokenName])}`;
    const mapping = redis('GET', key);
    ensure(mapping === row.id || mapping === JSON.stringify(row.id), 'Real token mapping differs from fixture identity');
    ensure(Number(redis('TTL', key)) > 0, 'Authentication token has no finite application TTL');
  }
  return result;
}
async function refused(label, cookies, account, expectedEmail, target, expectedCode) {
  const code = seed(target);
  const response = await http('/user/email/bind', { email: target, emailCode: code }, cookies);
  ensure(response.data.code === expectedCode, `${label}: wrong refusal business code`);
  ensure(dbUserRow(account).email === expectedEmail, `${label}: MySQL email changed`);
  ensure(redis('GET', emailKey(target)) === code, `${label}: email fixture consumed or changed`);
  ensure(Number(redis('TTL', emailKey(target))) > 0, `${label}: email fixture lost TTL`);
  pass(`${label}; business=${expectedCode}; DB unchanged; Redis code unconsumed`);
}

try {
  console.log(`T68 REAL HTTP E2E fixture=${fixture}`);
  console.log('FIXTURE POLICY: seeded email codes + own HTTP captcha Redis reads; no real mail delivery');
  ensure(redis('PING') === 'PONG' && sql('SELECT 1') === '1', 'Local data-service preflight failed');
  if (process.argv.includes('--preflight')) {
    console.log('PREFLIGHT PASS: local YAML parsed; MySQL and Redis reachable; no fixture mutations');
  } else {
    const accountA = `${fixture}_a`;
    const accountB = `${fixture}_b`;
    const emailA = `${fixture}_a@example.invalid`;
    const emailB = `${fixture}_b@example.invalid`;
    const firstBoundEmail = `${fixture}_bound@example.invalid`;
    const finalEmail = `${fixture}_fresh@example.invalid`;
    const password = `E2e!${randomBytes(15).toString('hex')}`;
    const newPassword = `Reset!${randomBytes(15).toString('hex')}`;
    await register(accountA, emailA, password);
    await register(accountB, emailB, password);
    pass('Two dedicated accounts registered over real HTTP; MySQL confirmed; registration codes consumed');
    const a = await login(accountA, password);
    const b = await login(accountB, password);
    pass('Both HTTP logins establish matching real Redis token mappings with finite TTL');
    const springOnly = { SESSION: a.cookies.SESSION };
    await refused('Spring Session without token', springOnly, accountA, emailA, `${fixture}_missing@example.invalid`, 40102);
    await refused('Foreign token with A Spring Session', { ...springOnly, [tokenName]: b.cookies[tokenName] }, accountA, emailA, `${fixture}_foreign@example.invalid`, 40102);
    await refused('Token without Spring Session', { [tokenName]: a.cookies[tokenName] }, accountA, emailA, `${fixture}_no_session@example.invalid`, 40100);
    const firstCode = seed(firstBoundEmail);
    const bound = await http('/user/email/bind', { email: firstBoundEmail, emailCode: firstCode }, a.cookies);
    ensure(bound.data.code === 0 && bound.data.data === true, 'Valid same-user bind failed');
    ensure(dbUserRow(accountA).email === firstBoundEmail && redis('EXISTS', emailKey(firstBoundEmail)) === '0', 'Valid bind did not update MySQL and consume Redis code');
    pass('Valid same-user bind; business=0; MySQL changed; Redis code consumed');
    const resetCode = seed(firstBoundEmail);
    const reset = await http('/user/password/reset', { email: firstBoundEmail, emailCode: resetCode, newPassword });
    ensure(reset.data.code === 0 && reset.data.data === true, 'Actual HTTP password reset failed');
    const oldTokenKey = `${tokenName}:space:token:${decodeURIComponent(a.cookies[tokenName])}`;
    ensure(redis('EXISTS', oldTokenKey) === '0', 'Actual password reset did not revoke old token mapping');
    ensure(redis('EXISTS', emailKey(firstBoundEmail)) === '0', 'Password reset did not consume reset code');
    ensure(dbUserRow(accountA).email === firstBoundEmail, 'Password reset unexpectedly changed email');
    pass('Actual resetPassword HTTP endpoint; business=0; old token mapping revoked in Redis');
    const oldSessionRead = await http('/user/get/login', undefined, springOnly);
    ensure(oldSessionRead.data.code === 0 && oldSessionRead.data.data.userAccount === accountA, 'Expected surviving old Spring Session was not reproduced');
    const sessionId = Buffer.from(decodeURIComponent(a.cookies.SESSION), 'base64').toString('utf8');
    ensure(/^[\w-]+$/.test(sessionId), 'Unexpected Spring Session cookie encoding');
    ensure(redis('EXISTS', `spring:session:sessions:${sessionId}`) === '1', 'Surviving Spring Session not present in Redis');
    pass('Old Spring Session demonstrably survives reset; real HTTP read and Redis key agree');
    await refused('Revoked old token plus surviving Session', a.cookies, accountA, firstBoundEmail, `${fixture}_revoked@example.invalid`, 40102);
    await login(accountA, password, 40000);
    pass('Old password rejected by real HTTP login; business=40000');
    const fresh = await login(accountA, newPassword);
    pass('New password login succeeds with valid new Redis token mapping; business=0');
    const finalCode = seed(finalEmail);
    const rebind = await http('/user/email/bind', { email: finalEmail, emailCode: finalCode }, fresh.cookies);
    ensure(rebind.data.code === 0 && rebind.data.data === true, 'Fresh new-password login could not bind');
    ensure(dbUserRow(accountA).email === finalEmail && redis('EXISTS', emailKey(finalEmail)) === '0', 'Fresh bind MySQL/Redis mismatch');
    ensure(dbUserRow(accountB).email === emailB, 'Other fixture account changed unexpectedly');
    pass('New-password fresh login binds successfully; business=0; MySQL updated; code consumed; B unchanged');
    console.log(`RESULT: PASS (${passed}/${passed} groups; ${requests} real HTTP requests; 2 retained fixture accounts)`);
    console.log('RESIDUALS: 2 accounts; dedicated unconsumed codes expire within 300s; authentication and one failed-login rate key retain application TTLs');
    console.log('LIMITS: local direct backend HTTP only; no nginx/TLS/browser or real mailbox ownership/delivery proof; no model/COS');
  }
} catch (error) {
  console.error(`RESULT: FAIL after ${passed} groups and ${requests} HTTP requests: ${error.message}`);
  console.error(`Retained fixture prefix=${fixture}; no cleanup was attempted`);
  process.exitCode = 1;
}