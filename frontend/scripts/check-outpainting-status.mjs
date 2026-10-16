import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import { createRequire } from 'node:module'
import { parse, compileScript } from '@vue/compiler-sfc'
import { transformSync } from 'esbuild'

// Execute the real component setup, mocking only network and notification boundaries.
const require = createRequire(import.meta.url)
const source = fs.readFileSync(
  new URL('../src/components/ImageOutPainting.vue', import.meta.url),
  'utf8',
)
const { descriptor } = parse(source)
const script = compileScript(descriptor, { id: 'outpainting-status-check' })
const { code } = transformSync(script.content, { loader: 'ts', format: 'cjs' })
let response
let creates = 0
const api = {
  getPictureOutPaintingStatusUsingGet: () => response(),
  createPictureOutPaintingTaskUsingPost: async () => {
    creates++
    return { data: { code: 1 } }
  },
}
const module = { exports: {} }
vm.runInNewContext(code, {
  module,
  exports: module.exports,
  require: (name) =>
    name.includes('pictureController')
      ? api
      : name === 'ant-design-vue'
        ? { message: { error() {} } }
        : require(name),
  console: { error() {} },
  setInterval,
  clearInterval,
})
const mount = () =>
  module.exports.default.setup({ picture: { id: '2111027283732324354' } }, { expose() {} })
const ok = (available) => ({ data: { code: 0, data: { available, message: 'maintenance' } } })
const deferred = () => {
  let resolve
  const promise = new Promise((r) => {
    resolve = r
  })
  return { promise, resolve }
}
const tests = []
const test = (name, run) => tests.push({ name, run })

test('unknown and pending status block generation', async () => {
  creates = 0
  const state = mount()
  assert.equal(state.outPaintingAvailable.value, false)
  const request = deferred()
  response = () => request.promise
  const opening = state.openModal()
  assert.equal(state.outPaintingAvailable.value, false)
  assert.equal(state.statusLoading.value, true)
  await state.createTask()
  assert.equal(creates, 0)
  request.resolve(ok(false))
  await opening
  assert.equal(state.outPaintingAvailable.value, false)
  assert.equal(state.maintenanceMessage.value, 'maintenance')
})
test('only explicit true enables generation', async () => {
  for (const value of [undefined, null, 'true', 1, false, true]) {
    const state = mount()
    response = async () => ok(value)
    await state.openModal()
    assert.equal(state.outPaintingAvailable.value, value === true)
  }
})
test('reopening resets availability and failures stay closed', async () => {
  for (const result of [null, { data: { code: 1 } }, { data: { code: 0 } }, new Error('offline')]) {
    const state = mount()
    response = async () => ok(true)
    await state.openModal()
    assert.equal(state.outPaintingAvailable.value, true)
    state.closeModal()
    const request = deferred()
    response = () => request.promise
    const opening = state.openModal()
    assert.equal(state.outPaintingAvailable.value, false)
    request.resolve(result instanceof Error ? Promise.reject(result) : result)
    await opening
    assert.equal(state.outPaintingAvailable.value, false)
    assert.ok(state.maintenanceMessage.value)
  }
})
test('explicit availability permits creation', async () => {
  const state = mount()
  response = async () => ok(true)
  await state.openModal()
  const before = creates
  await state.createTask()
  assert.equal(creates, before + 1)
})
test('older response cannot overwrite newer maintenance status', async () => {
  const state = mount()
  const old = deferred()
  response = () => old.promise
  const first = state.openModal()
  state.closeModal()
  response = async () => ok(false)
  await state.openModal()
  old.resolve(ok(true))
  await first
  assert.equal(state.outPaintingAvailable.value, false)
})
let failed = 0
for (const { name, run } of tests) {
  try {
    await run()
    console.log(`PASS ${name}`)
  } catch (error) {
    failed++
    console.error(`FAIL ${name}: ${error.message}`)
  }
}
assert.match(descriptor.template.content, /v-if="outPaintingAvailable"/)
assert.match(descriptor.template.content, /statusLoading \|\| !outPaintingAvailable/)
console.log(`RESULT: ${failed ? 'FAIL' : 'PASS'} (${tests.length} groups)`)
process.exitCode = failed ? 1 : 0
