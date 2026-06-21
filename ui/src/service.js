/*
 * Service layer for plugin "vm" (Virtualization, service-level).
 *
 * Exposes the cross-plugin / host-callable surface. Most of the legacy
 * `service/vm/vm.js` was jQuery/DataTables orchestration that now lives
 * in the views (VmConfigView.vue). What remains here is:
 *
 *   - renderFeatures(subscription)        → the "Configure" row action,
 *     plus any tool-specific buttons a vm-<tool> sub-plugin contributes
 *     (e.g. plugin-vm-aws's AWS console links) merged in via the
 *     parent→child delegation hook.
 *   - renderDetailsFeatures(subscription) → the live VM status chip
 *     (powered on/off/suspended, busy, deployed) shown in the details
 *     column, mirroring the legacy `renderDetailsFeatures`.
 *   - VM_OPERATIONS / operation metadata  → shared by the view's power
 *     menu and the schedule editor.
 *   - renderNetwork(networks)             → formats a VM's network list;
 *     called by child plugins (legacy `current.$super('renderNetwork')`).
 *
 * Kept free of Vue SFC imports so it can be unit-tested without a DOM.
 */
import { renderServiceLink, renderDetailsChip, toolPluginId, delegateFeature, useI18nStore } from '@ligoj/host'

/**
 * VM operations in legacy declaration order, each with its mdi icon.
 * The enum sent to the backend is the UPPERCASE key (VmOperation).
 * Material Design icons replace the legacy Font Awesome ones.
 */
export const VM_OPERATIONS = [
  { id: 'OFF', icon: 'mdi-power' },
  { id: 'ON', icon: 'mdi-play' },
  { id: 'SUSPEND', icon: 'mdi-pause' },
  { id: 'SHUTDOWN', icon: 'mdi-stop' },
  { id: 'RESET', icon: 'mdi-restore' },
  { id: 'REBOOT', icon: 'mdi-sync' },
]

/** mdi icon + color per VM status (lowercased backend status string). */
export const VM_STATUS = {
  powered_on: { icon: 'mdi-play-circle', color: 'success' },
  powered_off: { icon: 'mdi-stop-circle', color: 'error' },
  suspended: { icon: 'mdi-pause-circle', color: 'warning' },
}

/**
 * Derive the sub-plugin id for a VM tool subscription. A VM node id is
 * `service:vm:<tool>[:<instance>]` — `service:vm:aws:i-1` → `vm-aws`.
 * Mirrors the legacy `$super(...)` inheritance where vm-<tool> plugins
 * extended vm.js. Returns null when there is no tool segment to delegate
 * to. The plumbing is the host's shared `toolPluginId` / `delegateFeature`
 * (identical across every service parent), re-exported for existing callers.
 */
export const subPluginIdFor = toolPluginId

/** Delegate `action` to the vm-<tool> sub-plugin; `[]` on any failure. */
export const delegateToToolPlugin = (subscription, action) => delegateFeature(subscription, action, 'vm')

const service = {
  VM_OPERATIONS,
  VM_STATUS,
  subPluginIdFor,
  delegateToToolPlugin,

  /**
   * Subscription-row action buttons. The host's `PluginFeatures` mounts
   * these next to the unsubscribe icon on ProjectDetailView rows.
   *
   * The parent contributes a single "Configure" button linking to the
   * per-subscription configuration view (schedules + snapshots); a
   * filled cog signals an existing schedule, mirroring the legacy
   * `configure-trigger.text-danger` state. Tool-specific buttons from a
   * vm-<tool> sub-plugin (e.g. AWS console links) are appended.
   */
  renderFeatures(subscription) {
    const { t } = useI18nStore()
    const hasSchedule = !!subscription?.data?.schedules
    const buttons = [
      renderServiceLink({
        icon: 'mdi-cog',
        color: hasSchedule ? 'error' : undefined,
        title: hasSchedule ? t('vm.configurePresent') : t('vm.configure'),
        to: `/vm/subscription/${subscription?.id}`,
      }),
    ]
    buttons.push(...delegateToToolPlugin(subscription, 'renderFeatures'))
    return buttons
  },

  /**
   * Live VM status chip for the subscription details column. Reads the
   * tool-populated `subscription.data.vm` (status / busy / deployed),
   * which the host fetches via `rest/subscription/status/refresh`.
   * Mirrors the legacy `renderDetailsFeatures` status icon. Tool chips
   * (e.g. AWS account) are appended via delegation.
   */
  renderDetailsFeatures(subscription) {
    const { t } = useI18nStore()
    const out = []
    const vm = subscription?.data?.vm
    if (vm?.status) {
      const status = String(vm.status).toLowerCase()
      const meta = VM_STATUS[status] || { icon: 'mdi-help-circle-outline', color: 'grey' }
      const label = t(`service:vm:${status}`)
      const parts = [label === `service:vm:${status}` ? status : label]
      if (vm.busy) parts.push(`(${t('service:vm:busy')})`)
      if (status === 'powered_off' && vm.deployed) parts.push(`[${t('service:vm:deployed')}]`)
      out.push(renderDetailsChip({ icon: meta.icon, text: parts.join(' '), title: parts.join(' '), color: meta.color }))
    }
    out.push(...delegateToToolPlugin(subscription, 'renderDetailsFeatures'))
    return out.length ? out : null
  },

  /**
   * Resource key chips for the details column — delegated wholesale to
   * the vm-<tool> sub-plugin (the parent has no generic key to show; the
   * tool owns the VM id / name / AZ presentation).
   */
  renderDetailsKey(subscription) {
    const out = delegateToToolPlugin(subscription, 'renderDetailsKey')
    return out.length ? out : null
  },

  /**
   * Format a VM's network list as a short "icon ip [dns]" string.
   * Exposed for child plugins that render VM details (legacy
   * `current.$super('renderNetwork')`). Pure string output — no DOM.
   */
  renderNetwork(networks) {
    if (!Array.isArray(networks)) return ''
    const icon = { public: '🌐', private: '🔒' }
    return networks
      .map((n) => `${icon[n.type] || ''} ${n.ip}${n.dns ? ` [${n.dns}]` : ''}`.trim())
      .join(', ')
  },
}

export default service
