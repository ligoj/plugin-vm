# plugin-vm — Vue UI

Vue source for the **vm** service-level plugin (`service:vm`,
"Virtualization"), parent of the `vm-aws` / `vm-azure` / `vm-google` /
`vm-vcloud` tool plugins. Compiled by Vite into the Maven plugin JAR at
`../src/main/resources/META-INF/resources/webjars/vm/vue/`, served by the
host at `/main/vm/vue/index.js`.

See the host's `app-ui/REWRITE_VUEJS.md` for the full plugin contract.
This plugin ships:

- **i18n** — VM operation / status / schedule / snapshot labels
  (`service:vm:*`).
- **Route** `/vm/subscription/:id` → `VmConfigView` — the per-subscription
  configuration screen: cron-scheduled power operations + snapshots + CSV
  report downloads (ported from the legacy `vm.html` + `configure()`).
- **`renderFeatures`** — the subscription-row "Configure" button (filled
  red when a schedule exists), plus tool buttons delegated from a
  `vm-<tool>` sub-plugin.
- **`renderDetailsFeatures`** — the live VM status chip (powered
  on/off/suspended, busy, deployed).
- **`renderDetailsKey`** — delegated wholesale to the tool sub-plugin.
- **`renderNetwork`** — network-list formatter callable by child plugins.

## Parent → child delegation

`renderFeatures` / `renderDetails*` resolve the `vm-<tool>` sub-plugin via
`subPluginIdFor(subscription)` (`service:vm:aws:i-1` → `vm-aws`) and merge
its VNodes in. A tool plugin (e.g. `plugin-vm-aws`) just implements those
actions; it declares `requires: ['vm']` so this parent is loaded first.

## Commands

```bash
npm install
npm run build   # → ../src/main/resources/.../webjars/vm/vue/
npm run lint
npm test        # vitest — manifest + feature + delegation contract tests
npm run dev     # standalone dev harness on :5176
```

For real integration testing, run the host's vite dev server
(`ligoj/app-ui/src/main/webapp`, `npm run dev`), which proxies
`/ligoj/main/vm/vue/*` to the freshly built bundle. The cycle: edit
source → `npm run build` here → host browser auto-reloads.
