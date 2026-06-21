/*
 * Contract tests for plugin-vm (service-level Virtualization plugin).
 *
 * Covers the manifest shape, i18n merge, the subscription-row feature
 * renderers, the `subPluginIdFor` mapping, and the parent → child
 * delegation: when a `vm-aws` sub-plugin is registered, plugin-vm's
 * renderFeatures / renderDetailsFeatures append its VNodes. The sibling
 * plugin-vm-aws repo sits beside this one in the workspace.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { pluginRegistry, useI18nStore } from '@ligoj/host'
import pluginVmDef, { service } from '../index.js'
import { subPluginIdFor } from '../service.js'
import pluginVmAwsDef from '../../../../plugin-vm-aws/ui/src/index.js'

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('plugin-vm manifest', () => {
  it('exports required service-level fields', () => {
    expect(pluginVmDef.id).toBe('vm')
    expect(typeof pluginVmDef.label).toBe('string')
    expect(pluginVmDef.component).toBeTruthy()
    expect(Array.isArray(pluginVmDef.routes)).toBe(true)
    expect(pluginVmDef.routes.map((r) => r.path)).toContain('/vm/subscription/:id')
    expect(typeof pluginVmDef.install).toBe('function')
    expect(typeof pluginVmDef.feature).toBe('function')
    expect(pluginVmDef.service).toBeTypeOf('object')
    expect(pluginVmDef.meta).toMatchObject({ icon: expect.any(String), color: expect.any(String) })
  })

  it('install() registers routes and merges i18n', () => {
    const added = []
    const i18n = useI18nStore()
    pluginVmDef.install({ router: { addRoute: (r) => added.push(r) } })
    expect(added.map((r) => r.name)).toContain('vm-subscription')
    expect(i18n.t('service:vm')).toBe('Virtualization')
    expect(i18n.t('service:vm:powered_on')).toBe('Powered on')
  })

  it('feature() throws for an unknown action', () => {
    expect(() => pluginVmDef.feature('nope')).toThrow(/no feature "nope"/)
  })
})

describe('plugin-vm renderers', () => {
  beforeEach(() => {
    useI18nStore().merge({}, 'en')
    pluginVmDef.install({ router: { addRoute() {} } })
  })

  it('renderFeatures returns a Configure button linking to the config view', () => {
    const vnodes = pluginVmDef.feature('renderFeatures', {
      id: 7,
      node: { id: 'service:vm:aws:i-1' },
      data: {},
    })
    expect(Array.isArray(vnodes)).toBe(true)
    expect(vnodes[0].props.to).toBe('/vm/subscription/7')
  })

  it('renderDetailsFeatures returns a status chip from data.vm', () => {
    const vnode = pluginVmDef.feature('renderDetailsFeatures', {
      node: { id: 'service:vm:aws:i-1' },
      data: { vm: { status: 'powered_on' } },
    })
    expect(vnode).toBeTruthy()
  })

  it('renderDetailsFeatures returns null without vm status (and no tool chips)', () => {
    const vnode = pluginVmDef.feature('renderDetailsFeatures', {
      node: { id: 'service:vm:aws:i-1' },
      data: {},
    })
    expect(vnode).toBeNull()
  })

  it('renderNetwork formats the network list', () => {
    const out = service.renderNetwork([
      { type: 'public', ip: '1.2.3.4', dns: 'host.example' },
      { type: 'private', ip: '10.0.0.1' },
    ])
    expect(out).toContain('1.2.3.4')
    expect(out).toContain('[host.example]')
    expect(out).toContain('10.0.0.1')
  })
})

describe('subPluginIdFor', () => {
  it('maps a tool/instance node to vm-<tool>', () => {
    expect(subPluginIdFor({ node: { id: 'service:vm:aws:i-1' } })).toBe('vm-aws')
    expect(subPluginIdFor({ node: { id: 'service:vm:azure' } })).toBe('vm-azure')
  })
  it('returns null when there is no tool segment', () => {
    expect(subPluginIdFor({ node: { id: 'service:vm' } })).toBeNull()
    expect(subPluginIdFor({})).toBeNull()
  })
})

describe('plugin-vm → plugin-vm-aws delegation', () => {
  beforeEach(() => {
    pluginVmDef.install({ router: { addRoute() {} } })
    pluginVmAwsDef.install()
    pluginRegistry.register('vm-aws', pluginVmAwsDef)
  })
  afterEach(() => {
    pluginRegistry.remove('vm-aws')
  })

  it('appends vm-aws console links to renderFeatures output', () => {
    const result = pluginVmDef.feature('renderFeatures', {
      id: 7,
      node: { id: 'service:vm:aws:i-1' },
      data: {},
      parameters: { 'service:vm:aws:account': '012345678901' },
    })
    // 1 parent Configure button + 1 vm-aws sign-in link.
    expect(result.length).toBe(2)
    for (const node of result) expect(node.__v_isVNode).toBe(true)
  })

  it('does not delegate for a non-aws tool', () => {
    const result = pluginVmDef.feature('renderFeatures', {
      id: 7,
      node: { id: 'service:vm:azure:i-1' },
      data: {},
      parameters: {},
    })
    // Only the parent's Configure button — vm-azure is not registered.
    expect(result.length).toBe(1)
  })
})
