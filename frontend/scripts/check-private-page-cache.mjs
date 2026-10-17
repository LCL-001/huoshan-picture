import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import { createRequire } from 'node:module'
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc'
import { transformSync } from 'esbuild'

// Compile actual SFC scripts AND templates entirely in memory. No browser, files, or services.
const require = createRequire(import.meta.url)
const Vue = require('vue')
const { createMemoryHistory, createRouter } = require('vue-router')
const { createPinia, setActivePinia } = require('pinia')
const { h, ref, nextTick, createRenderer } = Vue
const ID_A = '2111027283732324354'
const ID_B = '2111027283732324355'
const SPACE_A = '2111027283732324356'
const SPACE_B = '2111027283732324357'
const ok = (data) => ({ data: { code: 0, data } })
const denied = () => ({ data: { code: 40300, message: 'denied' } })
const deferred = () => {
  let resolve
  let reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}
const flush = async () => {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve()
    await nextTick()
  }
}

function execute(source, mocks = {}) {
  const { code } = transformSync(source, { loader: 'ts', format: 'cjs' })
  const module = { exports: {} }
  vm.runInNewContext(code, {
    module,
    exports: module.exports,
    require: (name) => Object.hasOwn(mocks, name) ? mocks[name] : require(name),
    window: { innerWidth: 1280, location: { protocol: 'https:', host: 'test.invalid' }, addEventListener() {}, removeEventListener() {} },
    console: { log() {}, error() {}, warn() {} },
  })
  return module.exports
}
const read = (path) => fs.readFileSync(new URL(path, import.meta.url), 'utf8')
function compileSfc(path, mocks) {
  const { descriptor, errors } = parse(read(path), { filename: path })
  assert.equal(errors.length, 0)
  const script = compileScript(descriptor, { id: path })
  const template = compileTemplate({
    id: path,
    filename: path,
    source: descriptor.template.content,
    compilerOptions: { bindingMetadata: script.bindings, expressionPlugins: ['typescript'] },
  })
  assert.equal(template.errors.length, 0)
  const component = execute(script.content, mocks).default
  component.render = execute(template.code, mocks).render
  return component
}

const node = (type, text = '') => ({ type, text, children: [], props: {}, parent: null })
const detach = (child) => {
  if (child.parent) {
    const siblings = child.parent.children
    siblings.splice(siblings.indexOf(child), 1)
    child.parent = null
  }
}
const renderer = createRenderer({
  createElement: (type) => node(type),
  createText: (text) => node('#text', text),
  createComment: () => node('#comment'),
  setText: (el, text) => { el.text = text },
  setElementText: (el, text) => { el.children = []; el.text = text },
  parentNode: (el) => el.parent,
  nextSibling: (el) => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null,
  patchProp: (el, key, _previous, value) => { el.props[key] = value },
  insert(el, parent, anchor = null) {
    detach(el)
    el.parent = parent
    const index = anchor ? parent.children.indexOf(anchor) : -1
    parent.children.splice(index < 0 ? parent.children.length : index, 0, el)
  },
  remove: detach,
})
const textOf = (el) => el.text + el.children.map(textOf).join('')
const passthrough = { setup: (_props, { slots }) => () => h('stub', Object.values(slots).flatMap((slot) => slot?.() ?? [])) }
const modal = {
  props: ['visible'],
  setup: (props, { slots }) => () => props.visible ? h('modal', slots.default?.()) : null,
}
const qrCode = { props: ['value'], setup: (props) => () => h('qrcode', props.value) }
const componentModule = (component) => ({ __esModule: true, default: component })
const constants = execute(read('../src/constants/space.ts'))
const permissions = Object.values(constants.SPACE_PERMISSION_ENUM)
const storeModule = execute(read('../src/stores/useLoginUserStore.ts'), {
  '@/api/userController.ts': { getLoginUserUsingGet: async () => denied() },
})

async function mount({ direct = false, spaceReply, pictureReply } = {}) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = storeModule.useLoginUserStore(pinia)
  store.setLoginUser({ id: ID_A, userName: 'A' })
  const env = {
    store,
    spaceReply: spaceReply ?? (() => ok({ id: SPACE_A, spaceName: 'private-A', permissionList: permissions })),
    pictureReply: pictureReply ?? (() => ok({ records: [{ id: 'picture-A', name: 'private-photo-A' }], total: 1 })),
    colorReply: () => ok([{ id: 'color-A', name: 'private-color-A' }]),
    calls: { space: [], pictures: [], colors: [] },
    instances: [],
    errors: [],
  }
  const request = (kind, reply, params) => {
    env.calls[kind].push({ params, identity: store.loginUser.id })
    return env[reply](params)
  }
  const mocks = {
    // Stub only CSS transitions; KeepAlive and page/component lifecycles remain real.
    vue: { ...Vue, Transition: passthrough },
    '@/stores/useLoginUserStore': storeModule,
    '@/stores/useLoginUserStore.ts': storeModule,
    '@/api/spaceController.ts': {
      getSpaceVoByIdUsingGet: (params) => request('space', 'spaceReply', params),
      deleteSpaceUsingPost: async () => denied(),
    },
    '@/api/pictureController.ts': {
      listPictureVoByPageWithCacheUsingPost: (params) => request('pictures', 'pictureReply', params),
      searchPictureByColorUsingPost: (params) => request('colors', 'colorReply', params),
      deletePictureUsingPost: async () => denied(),
      editPictureByBatchUsingPost: async () => denied(),
      listPictureTagCategoryUsingGet: async () => ok({ tagList: [], categoryList: [] }),
    },
    'ant-design-vue': { message: { error: (error) => env.errors.push(error), success() {} }, Modal: { confirm() {} } },
    '@/utils': { formatSize: () => '0' },
    '@/components/PictureSearchForm.vue': componentModule(passthrough),
    'vue3-colorpicker': { ColorPicker: passthrough },
    'vue3-colorpicker/style.css': {},
    '@ant-design/icons-vue': Object.fromEntries(['BarChartOutlined', 'DeleteOutlined', 'EditOutlined', 'TeamOutlined', 'SearchOutlined', 'ShareAltOutlined'].map((name) => [name, passthrough])),
    '../constants/space.ts': constants,
  }
  // Local sharing/form state must be real: clearing parent props does not close a modal.
  mocks['@/components/ShareModal.vue'] = componentModule(compileSfc('../src/components/ShareModal.vue', mocks))
  mocks['@/components/PictureList.vue'] = componentModule(compileSfc('../src/components/PictureList.vue', mocks))
  mocks['@/components/BatchEditPictureModal.vue'] = componentModule(compileSfc('../src/components/BatchEditPictureModal.vue', mocks))
  const SpaceDetail = compileSfc('../src/pages/SpaceDetailPage.vue', mocks)
  const PageTransition = compileSfc('../src/components/PageTransition.vue', mocks)
  const routeId = ref(SPACE_A)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/space/:id', component: SpaceDetail, props: true },
      { path: '/picture/:id', component: { name: 'PictureDetail', render: () => h('detail', 'public-detail') } },
      { path: '/', component: { name: 'HomePage', render: () => h('home', 'public-home') } },
    ],
  })
  await router.push(`/space/${SPACE_A}`)
  await router.isReady()
  const root = node('root')
  const app = renderer.createApp({ render: () => direct ? h(SpaceDetail, { id: routeId.value }) : h(PageTransition) })
  app.use(pinia)
  app.use(router)
  app.config.warnHandler = (warning) => { throw new Error(warning) }
  app.mixin({
    mounted() {
      const name = this.$options.name ?? this.$options.__name
      if (['SpaceDetailPage', 'HomePage', 'PictureList', 'ShareModal', 'BatchEditPictureModal'].includes(name)) env.instances.push(this.$)
    },
  })
  for (const name of ['a-flex', 'a-space', 'a-button', 'a-tooltip', 'a-progress', 'a-form-item', 'a-pagination', 'a-spin', 'a-card', 'a-tag', 'a-typography-link', 'a-typography-paragraph', 'a-form', 'a-auto-complete', 'a-select', 'a-input']) app.component(name, passthrough)
  app.component('a-modal', modal)
  app.component('a-qrcode', qrCode)
  app.mount(root)
  await flush()
  return Object.assign(env, {
    root,
    child: (name) => env.instances.filter((instance) => (instance.type.name ?? instance.type.__name) === name).at(-1),
    state: () => env.instances.filter((instance) => instance.type.name === 'SpaceDetailPage').at(-1).setupState,
    current: () => env.instances.filter((instance) => instance.type.name === 'SpaceDetailPage').at(-1),
    async navigate(path) { await router.push(path); await flush() },
    async identity(id) { store.setLoginUser(id ? { id, userName: id === ID_A ? 'A' : 'B' } : { userName: 'anonymous' }); await flush() },
    async changeSpace(id) { routeId.value = id; await flush() },
    close() { app.unmount() },
  })
}

function assertEmpty(env, state = env.state()) {
  assert.equal(state.dataList.length, 0, 'private pictures must be cleared')
  assert.equal(state.total, 0, 'private total must be cleared')
  assert.equal(Object.keys(state.space).length, 0, 'space metadata and old permissions must be cleared')
  for (const name of ['canUploadPicture', 'canManageSpaceUser', 'canEditPicture', 'canDeletePicture']) assert.equal(state[name], false, `${name} must be false`)
  assert.equal(state.loading, false, 'failed refresh must stop loading')
  assert.ok(!textOf(env.root).includes('private-'), 'rendered tree must not retain private content')
}
const tests = []
const test = (name, run) => tests.push({ name, run })
const withPage = async (run, options) => {
  const env = await mount(options)
  try { await run(env) } finally { env.close() }
}

test('same identity retains cached space instance and data; refreshes real permissions', () => withPage(async (env) => {
  const instance = env.current()
  const pending = deferred()
  await env.navigate('/picture/1')
  env.spaceReply = () => pending.promise
  await env.navigate(`/space/${SPACE_A}`)
  assert.equal(env.current(), instance)
  assert.equal(instance.isUnmounted, false)
  assert.equal(env.state().dataList[0].name, 'private-photo-A')
  assert.equal(env.calls.space.length, 2, 'activation must re-fetch space and permissions')
  pending.resolve(ok({ id: SPACE_A, spaceName: 'private-A', permissionList: [] }))
  await flush()
  assert.equal(env.state().canUploadPicture, false, 'same-account revocation must be reflected')
  env.store.setLoginUser({ id: ID_A, userName: 'renamed-A' })
  await flush()
  assert.equal(env.current(), instance, 'profile-only update must not destroy cache')
}))

test('logout destroys all cached instances, not just a page user key', () => withPage(async (env) => {
  await env.navigate('/')
  await env.navigate('/picture/1')
  const cached = [...env.instances]
  assert.equal(cached.filter((instance) => ['SpaceDetailPage', 'HomePage'].includes(instance.type.name)).length, 2)
  await env.identity(null)
  assert.ok(cached.every((instance) => instance.isUnmounted), 'logout must destroy both cached space and home')
  env.spaceReply = denied
  env.pictureReply = denied
  await env.identity(ID_B)
  await env.navigate(`/space/${SPACE_A}`)
  assertEmpty(env)
  assert.ok(!cached.includes(env.current()), 'B must not reuse A instance')
}))

test('identity change clears old active data synchronously before render or CSS leave', () => withPage(async (env) => {
  const state = env.state()
  env.spaceReply = denied
  env.pictureReply = denied
  env.store.setLoginUser({ id: ID_B })
  assert.equal(state.dataList.length, 0, 'old active photos must clear synchronously')
  assert.equal(Object.keys(state.space).length, 0, 'old active permissions must clear synchronously')
  await flush()
  assertEmpty(env)
}))

test('same-tick logout and same-account login still destroys cached instances', () => withPage(async (env) => {
  const old = env.current()
  await env.navigate('/picture/1')
  env.store.setLoginUser({ userName: 'anonymous' })
  env.store.setLoginUser({ id: ID_A })
  await flush()
  assert.equal(old.isUnmounted, true, 'batched identity changes must not preserve the old cache')
  await env.navigate(`/space/${SPACE_A}`)
  assert.notEqual(env.current(), old)
}))

test('direct account switch destroys active cache and switching back cannot revive it', () => withPage(async (env) => {
  const original = env.current()
  env.spaceReply = denied
  env.pictureReply = denied
  await env.identity(ID_B)
  assert.equal(original.isUnmounted, true)
  assertEmpty(env)
  await env.identity(ID_A)
  assert.notEqual(env.current(), original)
  assertEmpty(env)
}))

for (const failure of ['denied', 'missing', 'throw']) {
  test(`space refresh ${failure} clears pictures, metadata, total and permissions`, () => withPage(async (env) => {
    await env.navigate('/picture/1')
    env.spaceReply = () => failure === 'throw' ? Promise.reject(new Error('offline')) : failure === 'missing' ? ok(null) : denied()
    await env.navigate(`/space/${SPACE_A}`)
    assertEmpty(env)
  }))
}

for (const kind of ['pictures', 'colors']) {
  for (const failure of ['denied', 'throw']) {
    test(`${kind} refresh ${failure} fails closed`, () => withPage(async (env) => {
      env[kind === 'pictures' ? 'pictureReply' : 'colorReply'] = () => failure === 'throw' ? Promise.reject(new Error('offline')) : denied()
      if (kind === 'pictures') await env.state().fetchData()
      else await env.state().onColorChange('#ffffff')
      await flush()
      assertEmpty(env)
    }))
  }
}

for (const kind of ['space', 'pictures', 'colors']) {
  test(`late A ${kind} response cannot update destroyed cache or B page`, () => withPage(async (env) => {
    const old = env.current()
    const state = env.state()
    const pending = deferred()
    let work
    if (kind === 'space') {
      env.spaceReply = () => pending.promise
      work = state.fetchSpaceDetail()
    } else if (kind === 'pictures') {
      env.pictureReply = () => pending.promise
      work = state.fetchData()
    } else {
      env.colorReply = () => pending.promise
      work = state.onColorChange('#ffffff')
    }
    env.spaceReply = denied
    env.pictureReply = denied
    await env.identity(ID_B)
    assert.equal(old.isUnmounted, true)
    const previousSpace = state.space
    const previousPictures = state.dataList
    pending.resolve(ok(kind === 'space' ? { spaceName: 'late-private-A', permissionList: permissions } : kind === 'pictures' ? { records: [{ name: 'late-private-A' }], total: 1 } : [{ name: 'late-private-A' }]))
    await work
    await flush()
    assert.equal(state.space, previousSpace, 'unmounted space state must not accept late response')
    assert.equal(state.dataList, previousPictures, 'unmounted picture state must not accept late response')
    assertEmpty(env)
  }))
}

test('access rejection invalidates an earlier pending picture refresh', () => withPage(async (env) => {
  const pending = deferred()
  env.pictureReply = () => pending.promise
  const work = env.state().fetchData(true)
  env.spaceReply = denied
  await env.state().fetchSpaceDetail()
  pending.resolve(ok({ records: [{ name: 'late-private-A' }], total: 1 }))
  await work
  await flush()
  assertEmpty(env)
}))

test('late old space response cannot restore permissions after newer rejection', () => withPage(async (env) => {
  const pending = deferred()
  env.spaceReply = () => pending.promise
  const work = env.state().fetchSpaceDetail()
  env.spaceReply = denied
  await env.state().fetchSpaceDetail()
  pending.resolve(ok({ spaceName: 'late-private-A', permissionList: permissions }))
  await work
  await flush()
  assertEmpty(env)
}))

test('route context change preserves string ids and rejects old space and pictures', () => withPage(async (env) => {
  const spacePending = deferred()
  const picturePending = deferred()
  env.spaceReply = () => spacePending.promise
  env.pictureReply = () => picturePending.promise
  const oldSpace = env.state().fetchSpaceDetail()
  const oldPictures = env.state().fetchData()
  env.spaceReply = () => ok({ id: SPACE_B, spaceName: 'space-B', permissionList: [] })
  env.pictureReply = () => ok({ records: [{ id: 'B', name: 'photo-B' }], total: 1 })
  await env.changeSpace(SPACE_B)
  spacePending.resolve(ok({ id: SPACE_A, spaceName: 'late-private-A', permissionList: permissions }))
  picturePending.resolve(ok({ records: [{ name: 'late-private-A' }], total: 1 }))
  await Promise.all([oldSpace, oldPictures])
  await flush()
  assert.equal(env.state().space.id, SPACE_B)
  assert.equal(env.state().dataList[0].name, 'photo-B')
  assert.equal(env.state().canUploadPicture, false)
  assert.ok(env.calls.space.every(({ params }) => typeof params.id === 'string'))
  assert.ok(env.calls.pictures.every(({ params }) => typeof params.spaceId === 'string'))
  assert.equal(env.calls.space.at(-1).params.id, SPACE_B)
  assert.equal(env.calls.pictures.at(-1).params.spaceId, SPACE_B)
}, { direct: true }))

for (const kind of ['space', 'pictures', 'colors']) {
  test(`same-account ${kind} denial destroys real sharing and batch-edit local state`, () => withPage(async (env) => {
    const page = env.current()
    const pictureList = env.child('PictureList')
    const shareModal = env.child('ShareModal')
    const batchModal = env.child('BatchEditPictureModal')
    pictureList.setupState.doShare(env.state().dataList[0], { stopPropagation() {} })
    batchModal.setupState.openModal()
    Object.assign(batchModal.setupState.formData, { category: 'private-category-A', tags: ['private-tag-A'], nameRule: 'private-name-A' })
    await flush()
    const oldLink = pictureList.setupState.shareLink
    assert.ok(oldLink.endsWith('/picture/picture-A'))
    assert.equal(shareModal.setupState.visible, true)
    assert.equal(batchModal.setupState.visible, true)
    assert.ok(textOf(env.root).includes(oldLink), 'real share link and QR value must render before denial')
    const before = textOf(env.root)
    assert.equal(before.split(oldLink).length - 1, 2, 'link and QR stub must both expose the actual share URL')
    if (kind === 'space') {
      env.spaceReply = denied
      await env.state().fetchSpaceDetail()
    } else if (kind === 'pictures') {
      env.pictureReply = denied
      await env.state().fetchData()
    } else {
      env.colorReply = denied
      await env.state().onColorChange('#ffffff')
    }
    await flush()
    assert.equal(env.current(), page, 'same-account denial must not rely on identity cache destruction')
    assert.equal(pictureList.isUnmounted, true, 'denial must destroy PictureList local share state')
    assert.equal(shareModal.isUnmounted, true, 'denial must destroy the open real ShareModal')
    assert.equal(batchModal.isUnmounted, true, 'denial must destroy the open real batch-edit form')
    assert.equal(env.child('PictureList').setupState.shareLink, '')
    assert.equal(env.child('ShareModal').setupState.visible, false)
    const nextBatch = env.child('BatchEditPictureModal').setupState
    assert.equal(nextBatch.visible, false)
    assert.equal(nextBatch.formData.category, '')
    assert.equal(nextBatch.formData.tags.length, 0)
    assert.equal(nextBatch.formData.nameRule, '')
    assert.ok(!textOf(env.root).includes(oldLink), 'denied page must remove old share URL and QR value')
    assertEmpty(env)
  }))
}

let failures = 0
for (const { name, run } of tests) {
  try {
    await run()
    console.log(`PASS ${name}`)
  } catch (error) {
    failures++
    console.error(`FAIL ${name}: ${error.message}`)
  }
}
console.log(`Private page cache: ${tests.length - failures}/${tests.length} passed`)
if (failures) process.exitCode = 1
