import test from 'node:test'
import assert from 'node:assert/strict'
import { build } from 'esbuild'
import path from 'node:path'

const rootDir = path.resolve(import.meta.dirname, '..')
const { isSameTabPrimaryActivation } = await build({
  entryPoints: [path.join(rootDir, 'src/lib/navigation-intent.ts')],
  bundle: true,
  platform: 'node',
  format: 'esm',
  write: false,
}).then((result) => {
  const code = result.outputFiles[0].text
  const moduleUrl = `data:text/javascript,${encodeURIComponent(code)}`
  return import(moduleUrl)
})

test('allows primary same-tab activation', () => {
  assert.equal(
    isSameTabPrimaryActivation({
      button: 0,
      metaKey: false,
      ctrlKey: false,
      shiftKey: false,
      altKey: false,
    }),
    true
  )
})

test('rejects non-primary buttons', () => {
  assert.equal(
    isSameTabPrimaryActivation({
      button: 1,
      metaKey: false,
      ctrlKey: false,
      shiftKey: false,
      altKey: false,
    }),
    false
  )
  assert.equal(
    isSameTabPrimaryActivation({
      button: 2,
      metaKey: false,
      ctrlKey: false,
      shiftKey: false,
      altKey: false,
    }),
    false
  )
})

test('rejects modified clicks', () => {
  for (const modifier of ['metaKey', 'ctrlKey', 'shiftKey', 'altKey']) {
    assert.equal(
      isSameTabPrimaryActivation({
        button: 0,
        metaKey: false,
        ctrlKey: false,
        shiftKey: false,
        altKey: false,
        [modifier]: true,
      }),
      false
    )
  }
})
