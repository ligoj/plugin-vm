// Load the sibling index.css at runtime. Vite's library build emits it as
// a separate file but does NOT add `import './index.css'` to the JS entry
// — so when the host dynamic-imports this bundle the stylesheet never
// loads. Injecting a <link rel="stylesheet"> resolved against
// import.meta.url keeps the approach path-agnostic.
if (typeof document !== 'undefined') {
  const id = 'ligoj-plugin-vm-css'
  if (!document.getElementById(id)) {
    const link = document.createElement('link')
    link.id = id
    link.rel = 'stylesheet'
    link.href = new URL(/* @vite-ignore */ './index.css', import.meta.url).href
    document.head.appendChild(link)
  }
}

/*
 * Plugin "vm" — Virtualization (service-level).
 *
 * Parent of the vm-<tool> plugins (vm-aws, vm-azure, vm-google,
 * vm-vcloud). It owns:
 *   - the per-subscription configuration view (`/vm/subscription/:id`):
 *     cron-scheduled power operations + snapshots + CSV reports;
 *   - the subscription-row "Configure" action and live status chip;
 *   - the parent→child delegation hook that merges a tool plugin's
 *     row features / detail chips (e.g. plugin-vm-aws's console links)
 *     into its own output.
 *
 * Authored as source — compiled to `/main/vm/vue/index.js` by Vite.
 * Shared host surface (stores, components) is imported from `@ligoj/host`
 * and kept external at build so plugin and host share the same instances.
 */
import { useI18nStore } from '@ligoj/host'
import VmPlugin from './VmPlugin.vue'
import VmConfigView from './views/VmConfigView.vue'
import enMessages from './i18n/en.js'
import frMessages from './i18n/fr.js'
import service from './service.js'

const features = {
  renderFeatures: service.renderFeatures,
  renderDetailsKey: service.renderDetailsKey,
  renderDetailsFeatures: service.renderDetailsFeatures,
  renderNetwork: service.renderNetwork,
}

const routes = [
  // Per-subscription configuration (legacy `configure(configuration)`):
  // schedules + snapshots. Opened from the subscription-row cog button.
  { path: '/vm/subscription/:id', name: 'vm-subscription', component: VmConfigView },
]

export default {
  id: 'vm',
  label: 'Virtualization',
  component: VmPlugin,
  routes,
  install({ router }) {
    for (const route of routes) router.addRoute(route)
    const i18n = useI18nStore()
    i18n.merge(enMessages, 'en')
    i18n.merge(frMessages, 'fr')
  },
  feature(action, ...args) {
    const fn = features[action]
    if (!fn) throw new Error(`Plugin "vm" has no feature "${action}"`)
    return fn(...args)
  },
  service,
  meta: { icon: 'mdi-cloud', color: 'indigo-darken-2' },
}

export { service }
