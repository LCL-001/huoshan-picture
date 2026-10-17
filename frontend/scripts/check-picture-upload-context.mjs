import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import * as vue from 'vue'
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc'
import { transformSync } from 'esbuild'

// Compile actual SFC setup AND templates entirely in memory. Only API, UI shell,
// cropper and outpainting boundaries are stubbed; both upload components are real.
const paths = ['components/PictureUpload', 'components/UrlPictureUpload', 'pages/AddPicturePage']
const compiled = new Map(
  paths.map((path) => {
    const filename = `${path}.vue`
    const { descriptor, errors } = parse(
      fs.readFileSync(new URL(`../src/${filename}`, import.meta.url), 'utf8'),
    )
    assert.deepEqual(errors, [])
    const script = compileScript(descriptor, { id: path })
    const template = compileTemplate({
      source: descriptor.template.content,
      filename,
      id: path,
      compilerOptions: { bindingMetadata: script.bindings },
    })
    assert.deepEqual(template.errors, [])
    const compile = (source) => transformSync(source, { loader: 'ts', format: 'cjs' }).code
    return [path, [compile(script.content), compile(template.code)]]
  }),
)

const ok = (data) => ({ data: { code: 0, data } })
const A = '2111027283732324354'
const B = '2111027283732324355'
const S1 = '2111027283732324356'
const S2 = '2111027283732324357'
const picture = (id) => ({
  id,
  name: `name-${id}`,
  introduction: 'intro',
  category: 'category',
  tags: ['tag'],
  url: `stub:${id}`,
})
const deferred = () => {
  let resolve
  const promise = new Promise((r) => {
    resolve = r
  })
  return { promise, resolve }
}
const flush = async () => {
  for (let i = 0; i < 6; i++) {
    await Promise.resolve()
    await vue.nextTick()
  }
}
const shell = vue.defineComponent({
  setup(_, { slots }) {
    return () => vue.h('shell', null, slots.default?.())
  },
})
const node = (type, text = '') => ({ type, text, children: [], props: {}, parent: null })
const detach = (child) => {
  if (!child.parent) return
  const children = child.parent.children
  const index = children.indexOf(child)
  if (index !== -1) children.splice(index, 1)
  child.parent = null
}
const renderer = vue.createRenderer({
  createElement: (type) => node(type),
  createText: (text) => node('text', text),
  createComment: (text) => node('comment', text),
  setText: (target, text) => {
    target.text = text
  },
  setElementText: (target, text) => {
    target.text = text
  },
  parentNode: (target) => target.parent,
  nextSibling: (target) =>
    target.parent?.children[target.parent.children.indexOf(target) + 1] ?? null,
  patchProp: (target, key, _old, value) => {
    target.props[key] = value
  },
  insert(child, parent, anchor = null) {
    detach(child)
    const index = anchor ? parent.children.indexOf(anchor) : -1
    parent.children.splice(index < 0 ? parent.children.length : index, 0, child)
    child.parent = parent
  },
  remove: detach,
})

const mount = (query = {}, options = {}) => {
  const route = vue.reactive({ query })
  const calls = { pictures: [], spaces: [], files: [], urls: [], edits: [], pushes: [] }
  const api = {
    getPictureVoByIdUsingGet(params) {
      calls.pictures.push({ ...params })
      return options.getPicture?.(params) ?? Promise.resolve(ok(picture(params.id)))
    },
    listPictureTagCategoryUsingGet: async () => ok({ tagList: [], categoryList: [] }),
    getSpaceVoByIdUsingGet(params) {
      calls.spaces.push({ ...params })
      return (
        options.getSpace?.(params) ??
        Promise.resolve(ok({ id: params.id, spaceName: `space-${params.id}` }))
      )
    },
    uploadPictureUsingPost(params, body, file) {
      calls.files.push({ params: { ...params }, body, file })
      return options.uploadFile?.(params) ?? Promise.resolve({ data: { code: 1, message: 'stub' } })
    },
    uploadPictureByUrlUsingPost(params) {
      calls.urls.push({ ...params })
      return options.uploadUrl?.(params) ?? Promise.resolve({ data: { code: 1, message: 'stub' } })
    },
    editPictureUsingPost(params) {
      calls.edits.push({ ...params })
      return options.edit?.(params) ?? Promise.resolve(ok(true))
    },
  }
  const components = new Map()
  const load = (path) => {
    if (components.has(path)) return components.get(path)
    const modules = {
      vue: { ...vue, Transition: shell },
      'vue-router': {
        useRoute: () => route,
        useRouter: () => ({ push: (to) => calls.pushes.push(to) }),
      },
      'ant-design-vue': { message: { success() {}, error() {} } },
      '@ant-design/icons-vue': Object.fromEntries(
        [
          'EditOutlined',
          'FullscreenOutlined',
          'PictureOutlined',
          'LoadingOutlined',
          'PlusOutlined',
          'LinkOutlined',
        ].map((name) => [name, shell]),
      ),
      '@/api/pictureController.ts': api,
      '@/api/spaceController.ts': api,
      '@/components/ImageCropper.vue': shell,
      '@/components/ImageOutPainting.vue': shell,
    }
    const evaluate = (code) => {
      const module = { exports: {} }
      vm.runInNewContext(code, {
        module,
        exports: module.exports,
        require(name) {
          if (name in modules) return modules[name]
          const componentPath = name.replace(/^@\//, '').replace(/\.vue$/, '')
          if (compiled.has(componentPath)) return load(componentPath)
          throw new Error(`Unexpected import: ${name}`)
        },
        console,
      })
      return module.exports
    }
    const [script, template] = compiled.get(path)
    const component = evaluate(script).default
    component.render = evaluate(template).render
    components.set(path, component)
    return component
  }
  const page = load('pages/AddPicturePage')
  const app = renderer.createApp(page)
  for (const name of [
    'a-row',
    'a-col',
    'a-tabs',
    'a-tab-pane',
    'a-space',
    'a-button',
    'a-form',
    'a-form-item',
    'a-input',
    'a-textarea',
    'a-auto-complete',
    'a-select',
    'a-upload',
  ])
    app.component(name, shell)
  app.mount(node('root'))
  const instance = app._instance
  const find = (type, vnode = instance.subTree) => {
    if (!vnode) return undefined
    if (vnode.component?.type === type) return vnode.component
    if (vnode.component) return find(type, vnode.component.subTree)
    for (const child of Array.isArray(vnode.children) ? vnode.children : []) {
      const result = find(type, child)
      if (result) return result
    }
  }
  return {
    app,
    route,
    calls,
    state: instance.setupState,
    file: () => find(components.get('components/PictureUpload')),
    url: () => find(components.get('components/UrlPictureUpload')),
    change: (next) => {
      route.query = next
    },
  }
}

const tests = []
const test = (name, run) => tests.push({ name, run })
let active
const start = async (query, options) => {
  active = mount(query, options)
  await flush()
  return active
}
const assertEmpty = (state) => {
  assert.equal(state.picture, undefined, 'old picture must be cleared synchronously')
  assert.equal(state.pictureForm.name, undefined)
  assert.equal(state.pictureForm.introduction, undefined)
  assert.equal(state.pictureForm.category, undefined)
  assert.equal(state.pictureForm.tags?.length ?? 0, 0)
}
const uploadBoth = async (page) => {
  await page.file().setupState.handleUpload({ file: { name: 'in-memory.png' } })
  page.url().setupState.fileUrl = 'stub:new-image'
  await page.url().setupState.handleUpload()
}

test('normal edit preserves exact string IDs in both actual upload requests', async () => {
  const page = await start(
    { id: A, spaceId: S1 },
    { uploadFile: async () => ok(picture(A)), uploadUrl: async () => ok(picture(A)) },
  )
  assert.equal(page.state.picture.id, A)
  await uploadBoth(page)
  assert.equal(page.calls.files[0].params.id, A)
  assert.equal(page.calls.files[0].params.spaceId, S1)
  assert.equal(page.calls.urls[0].id, A)
  assert.equal(page.calls.urls[0].spaceId, S1)
  assert.equal(page.calls.urls[0].fileUrl, 'stub:new-image')
  await page.state.handleSubmit({ name: 'normal-replacement' })
  assert.equal(page.calls.edits[0].id, A)
  assert.equal(page.calls.pushes[0].path, `/picture/${A}`)
})
test('A to new clears picture and all form fields before save can target A', async () => {
  const page = await start({ id: A })
  page.change({})
  assertEmpty(page.state)
  await page.state.handleSubmit({ name: 'new' })
  assert.equal(page.calls.edits.length, 0)
})
test('A to new omits old ID in file AND URL request assembly', async () => {
  const page = await start({ id: A })
  page.change({})
  await flush()
  await uploadBoth(page)
  assert.equal(Object.hasOwn(page.calls.files[0].params, 'id'), false)
  assert.equal(Object.hasOwn(page.calls.urls[0], 'id'), false)
})
test('A to B clears state before B fetch and blocks save while B is pending', async () => {
  const pending = deferred()
  let page
  let stateAtRequest
  page = await start(
    { id: A },
    {
      getPicture: ({ id }) => {
        if (id !== B) return Promise.resolve(ok(picture(id)))
        stateAtRequest = { picture: page.state.picture, name: page.state.pictureForm.name }
        return pending.promise
      },
    },
  )
  page.change({ id: B })
  assertEmpty(page.state)
  await page.state.handleSubmit({ name: 'must-not-save-A' })
  assert.equal(page.calls.edits.length, 0)
  await flush()
  assert.ok(stateAtRequest, 'B must be fetched on query change')
  assert.equal(stateAtRequest.picture, undefined)
  assert.equal(stateAtRequest.name, undefined)
  pending.resolve(ok(picture(B)))
  await flush()
  assert.equal(page.state.picture.id, B)
  assert.equal(page.state.pictureForm.name, picture(B).name)
  await uploadBoth(page)
  assert.equal(page.calls.files[0].params.id, B)
  assert.equal(page.calls.urls[0].id, B)
})
test('space change resets state, refetches picture and assembles new space ID', async () => {
  const pending = deferred()
  let reads = 0
  const page = await start(
    { id: A, spaceId: S1 },
    {
      getPicture: () => (++reads === 1 ? Promise.resolve(ok(picture(A))) : pending.promise),
    },
  )
  page.change({ id: A, spaceId: S2 })
  assertEmpty(page.state)
  assert.equal(page.state.space, undefined)
  await flush()
  assert.equal(page.calls.pictures.length, 2)
  assert.equal(page.calls.spaces.at(-1).id, S2)
  pending.resolve(ok(picture(A)))
  await flush()
  await uploadBoth(page)
  assert.equal(page.calls.files[0].params.spaceId, S2)
  assert.equal(page.calls.urls[0].spaceId, S2)
})
test('removing space clears old space and never sends its ID', async () => {
  const page = await start({ spaceId: S1 })
  page.change({})
  assert.equal(page.state.space, undefined)
  await flush()
  await uploadBoth(page)
  assert.equal(page.calls.files[0].params.spaceId, undefined)
  assert.equal(page.calls.urls[0].spaceId, undefined)
})
test('old picture response cannot refill new context', async () => {
  const pending = deferred()
  const page = await start({ id: A }, { getPicture: () => pending.promise })
  page.change({})
  await flush()
  pending.resolve(ok(picture(A)))
  await flush()
  assertEmpty(page.state)
})
for (const kind of ['file', 'url']) {
  test(`same-context ${kind} upload success wins over pending detail and remains the save target`, async () => {
    const pending = deferred()
    const page = await start(
      { id: A, spaceId: S1 },
      {
        getPicture: () => pending.promise,
        [kind === 'file' ? 'uploadFile' : 'uploadUrl']: async () => ok(picture(B)),
      },
    )
    const version = page.state.contextVersion
    const upload = page[kind]()
    if (kind === 'file') {
      await upload.setupState.handleUpload({ file: { name: 'new.png' } })
    } else {
      upload.setupState.fileUrl = 'stub:new-image'
      await upload.setupState.handleUpload()
    }
    await flush()
    assert.equal(page.state.picture.id, B)
    page.state.pictureForm.introduction = 'new-upload-notes'
    pending.resolve(ok(picture(A)))
    await flush()
    assert.equal(page.state.picture.id, B, 'late initialization must not replace successful upload')
    assert.equal(page.state.pictureForm.name, picture(B).name)
    assert.equal(page.state.picture.url, picture(B).url)
    const params = kind === 'file' ? page.calls.files[0].params : page.calls.urls[0]
    assert.equal(Object.hasOwn(params, 'id'), false)
    assert.equal(params.spaceId, S1)
    assert.equal(page.state.pictureForm.introduction, 'new-upload-notes')
    assert.equal(page.state.contextVersion, version, 'upload must not change route context')
    assert.equal(page[kind](), upload, 'successful upload must not remount its component')
    await page.state.handleSubmit({ name: 'saved-new-upload' })
    assert.equal(page.calls.edits[0].id, B)
    assert.equal(page.calls.pushes[0].path, `/picture/${B}`)
  })
  test(`failed ${kind} upload does not suppress pending detail initialization`, async () => {
    const pending = deferred()
    const page = await start({ id: A }, { getPicture: () => pending.promise })
    const upload = page[kind]()
    if (kind === 'file') {
      await upload.setupState.handleUpload({ file: { name: 'failed.png' } })
    } else {
      upload.setupState.fileUrl = 'stub:failed-image'
      await upload.setupState.handleUpload()
    }
    pending.resolve(ok(picture(A)))
    await flush()
    assert.equal(page.state.picture.id, A)
    assert.equal(page.state.pictureForm.name, picture(A).name)
    assert.equal(page.state.pictureForm.introduction, picture(A).introduction)
    assert.equal(page.state.pictureForm.category, picture(A).category)
    assert.deepEqual(Array.from(page.state.pictureForm.tags), picture(A).tags)
    await page.state.handleSubmit({ name: 'normal-old-picture' })
    assert.equal(page.calls.edits[0].id, A)
    assert.equal(page.calls.pushes[0].path, `/picture/${A}`)
  })
}
test('new route detail still initializes after a prior successful upload', async () => {
  const oldDetail = deferred()
  const newDetail = deferred()
  const page = await start(
    { id: A },
    {
      getPicture: ({ id }) => (id === A ? oldDetail.promise : newDetail.promise),
      uploadFile: async () => ok(picture(B)),
    },
  )
  await page.file().setupState.handleUpload({ file: { name: 'new.png' } })
  await flush()
  page.change({ id: B, spaceId: S2 })
  oldDetail.resolve(ok({ ...picture(A), name: 'old-detail' }))
  await flush()
  assertEmpty(page.state)
  newDetail.resolve(ok({ ...picture(B), name: 'fresh-detail', url: 'stub:fresh-detail' }))
  await flush()
  assert.equal(page.state.picture.id, B)
  assert.equal(page.state.pictureForm.name, 'fresh-detail')
  assert.equal(page.state.picture.url, 'stub:fresh-detail')
  assert.equal(page.state.pictureForm.introduction, picture(B).introduction)
  assert.equal(page.state.space.id, S2)
})
for (const kind of ['file', 'url']) {
  test(`old ${kind} upload success cannot invalidate the next route pending detail`, async () => {
    const oldUpload = deferred()
    const newDetail = deferred()
    const page = await start(
      { id: A },
      {
        getPicture: ({ id }) => id === B ? newDetail.promise : Promise.resolve(ok(picture(A))),
        [kind === 'file' ? 'uploadFile' : 'uploadUrl']: () => oldUpload.promise,
      },
    )
    const old = page[kind]()
    const uploading = kind === 'file'
      ? old.setupState.handleUpload({ file: { name: 'old.png' } })
      : old.setupState.handleUpload()
    page.change({ id: B })
    await flush()
    oldUpload.resolve(ok({ ...picture(A), name: 'obsolete-upload' }))
    await uploading
    await flush()
    assertEmpty(page.state)
    newDetail.resolve(ok(picture(B)))
    await flush()
    assert.equal(page.state.picture.id, B)
    assert.equal(page.state.pictureForm.name, picture(B).name)
  })
}
test('successive uploads in one context both apply without remounting', async () => {
  let uploads = 0
  const page = await start({}, {
    uploadFile: async () => ok(picture(++uploads === 1 ? A : B)),
  })
  const upload = page.file()
  const version = page.state.contextVersion
  await upload.setupState.handleUpload({ file: { name: 'first.png' } })
  await flush()
  assert.equal(page.state.picture.id, A)
  await upload.setupState.handleUpload({ file: { name: 'second.png' } })
  await flush()
  assert.equal(page.state.picture.id, B)
  assert.equal(page.state.contextVersion, version)
  assert.equal(page.file(), upload)
  await page.state.handleSubmit({ name: 'latest-upload' })
  assert.equal(page.calls.edits[0].id, B)
})
test('A to B to A ignores first A response even when query IDs match again', async () => {
  const pending = deferred()
  let readsA = 0
  const page = await start(
    { id: A },
    {
      getPicture: ({ id }) =>
        id === A && ++readsA === 1 ? pending.promise : Promise.resolve(ok(picture(id))),
    },
  )
  page.change({ id: B })
  await flush()
  assert.equal(page.state.picture.id, B)
  page.change({ id: A })
  await flush()
  pending.resolve(ok({ ...picture(A), name: 'obsolete-first-A' }))
  await flush()
  assert.equal(page.state.pictureForm.name, picture(A).name)
})
test('old space response cannot overwrite new space or refill public context', async () => {
  const pending = deferred()
  const page = await start(
    { spaceId: S1 },
    {
      getSpace: ({ id }) =>
        id === S1 ? pending.promise : Promise.resolve(ok({ id, spaceName: 'new-space' })),
    },
  )
  page.change({ spaceId: S2 })
  await flush()
  assert.equal(page.state.space.id, S2)
  pending.resolve(ok({ id: S1, spaceName: 'old-space' }))
  await flush()
  assert.equal(page.state.space.id, S2)
  page.change({})
  assert.equal(page.state.space, undefined)
})
for (const kind of ['file', 'url']) {
  test(`late ${kind} upload response cannot refill new context; local input is remounted`, async () => {
    const pending = deferred()
    const page = await start(
      { id: A, spaceId: S1 },
      {
        [kind === 'file' ? 'uploadFile' : 'uploadUrl']: () => pending.promise,
      },
    )
    const old = page[kind]()
    page.state.uploadType = 'url'
    page.url().setupState.fileUrl = 'stub:old-image'
    const uploading =
      kind === 'file'
        ? old.setupState.handleUpload({ file: { name: 'old.png' } })
        : old.setupState.handleUpload()
    page.change({ spaceId: S2 })
    await flush()
    pending.resolve(ok(picture(A)))
    await uploading
    await flush()
    assertEmpty(page.state)
    assert.equal(old.isUnmounted, true)
    assert.notEqual(page[kind](), old)
    assert.equal(page.url().setupState.fileUrl, undefined)
    assert.equal(page.state.uploadType, 'file')
  })
}
test('old crop and AI callbacks cannot refill next context', async () => {
  const page = await start({ id: A })
  const oldCrop = page.state.onCropSuccess
  const oldPainting = page.state.onImageOutPaintingSuccess
  page.change({})
  await flush()
  oldCrop(picture(A))
  oldPainting(picture(A))
  await flush()
  assertEmpty(page.state)
})
test('save success from old context does not redirect the new page', async () => {
  const pending = deferred()
  const page = await start({ id: A }, { edit: () => pending.promise })
  const saving = page.state.handleSubmit({ name: 'edited-A' })
  assert.equal(page.calls.edits[0].id, A)
  page.change({})
  await flush()
  pending.resolve(ok(true))
  await saving
  assert.equal(page.calls.pushes.length, 0)
})
test('normal create upload and save still work', async () => {
  const page = await start({}, { uploadFile: async () => ok(picture(B)) })
  await page.file().setupState.handleUpload({ file: { name: 'new.png' } })
  await flush()
  assert.equal(Object.hasOwn(page.calls.files[0].params, 'id'), false)
  assert.equal(page.state.picture.id, B)
  assert.equal(page.state.pictureForm.name, picture(B).name)
  await page.state.handleSubmit({ name: 'created' })
  assert.equal(page.calls.edits[0].id, B)
  assert.equal(page.calls.pushes[0].path, `/picture/${B}`)
})
test('unmounted page ignores late picture response', async () => {
  const pending = deferred()
  const page = await start({ id: A }, { getPicture: () => pending.promise })
  page.app.unmount()
  pending.resolve(ok(picture(A)))
  await flush()
  assertEmpty(page.state)
})

let failed = 0
for (const { name, run } of tests) {
  try {
    await run()
    console.log(`PASS ${name}`)
  } catch (error) {
    failed++
    console.error(`FAIL ${name}: ${error.message}`)
  } finally {
    active?.app.unmount()
    active = undefined
    await flush()
  }
}
console.log(`RESULT: ${failed ? 'FAIL' : 'PASS'} (${tests.length - failed}/${tests.length} groups)`)
process.exitCode = failed ? 1 : 0
