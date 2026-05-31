<template>
  <div class="vm-config">
    <v-skeleton-loader v-if="loading && !model" type="card, table" class="mb-4" />

    <template v-if="model">
      <div class="d-flex align-start flex-wrap ga-2 mb-4">
        <div>
          <h1 class="text-h5 d-flex align-center ga-2">
            <v-icon>mdi-cloud</v-icon>
            <span>{{ model.node?.name || t('service:vm') }}</span>
          </h1>
          <p class="text-body-2 text-medium-emphasis mt-1">
            <code v-if="model.node?.id">{{ model.node.id }}</code>
          </p>
        </div>
        <v-spacer />
        <v-btn variant="text" prepend-icon="mdi-arrow-left" :to="projectHref">
          {{ t('common.cancel') }}
        </v-btn>
      </div>

      <v-tabs v-model="tab" class="mb-4">
        <v-tab value="schedule">{{ t('service:vm:schedule') }}</v-tab>
        <v-tab v-if="supportSnapshot" value="snapshot">{{ t('service:vm:snapshot') }}</v-tab>
      </v-tabs>

      <v-window v-model="tab">
        <!-- Schedules -->
        <v-window-item value="schedule">
          <v-alert type="info" variant="tonal" density="comfortable" class="mb-4">
            {{ t('service:vm:operation-executed-help') }}
          </v-alert>

          <div class="d-flex flex-wrap align-center mb-3 ga-2">
            <v-spacer />
            <v-menu>
              <template #activator="{ props: menuProps }">
                <v-btn v-bind="menuProps" variant="text" prepend-icon="mdi-download" append-icon="mdi-menu-down">
                  {{ t('service:vm:schedule:reports') }}
                </v-btn>
              </template>
              <v-list density="compact">
                <v-list-item
                  :href="executionsReportHref"
                  :title="t('service:vm:schedule:reports:executions')"
                  prepend-icon="mdi-history"
                />
                <v-list-item
                  v-for="node in reportNodes"
                  :key="node.id"
                  :href="schedulesReportHref(node)"
                  :title="t('service:vm:schedule:reports:schedules', { name: node.name })"
                  prepend-icon="mdi-calendar-export"
                />
              </v-list>
            </v-menu>
            <v-btn color="primary" prepend-icon="mdi-plus" @click="openCreateSchedule">
              {{ t('service:vm:schedule') }}
            </v-btn>
          </div>

          <LigojDataTable
            filename="schedules.csv"
            :headers="scheduleHeaders"
            :items="schedules"
            item-value="id"
            density="comfortable"
            :no-data-text="t('vm.noSchedules')"
          >
            <template #item.operation="{ item }">
              <v-icon size="small" class="mr-1">{{ operationIcon(item.operation) }}</v-icon>
              {{ t(`service:vm:${String(item.operation).toLowerCase()}`) }}
            </template>
            <template #item.actions="{ item }">
              <v-btn icon size="small" variant="text" :title="t('common.edit')" @click="openEditSchedule(item)">
                <v-icon size="small">mdi-pencil</v-icon>
              </v-btn>
              <v-btn icon size="small" variant="text" color="error" :title="t('common.delete')" @click="askDeleteSchedule(item)">
                <v-icon size="small">mdi-delete</v-icon>
              </v-btn>
            </template>
          </LigojDataTable>
        </v-window-item>

        <!-- Snapshots -->
        <v-window-item v-if="supportSnapshot" value="snapshot">
          <div class="d-flex flex-wrap align-center mb-3 ga-2">
            <v-spacer />
            <v-menu>
              <template #activator="{ props: menuProps }">
                <v-btn v-bind="menuProps" color="primary" :loading="snapshotBusy" prepend-icon="mdi-camera-plus" append-icon="mdi-menu-down">
                  {{ t('service:vm:snapshot-create') }}
                </v-btn>
              </template>
              <v-list density="compact">
                <v-list-item
                  :title="t('service:vm:snapshot-create-no-stop')"
                  :subtitle="t('service:vm:snapshot-create-no-stop-help')"
                  @click="createSnapshot(false)"
                />
                <v-list-item
                  :title="t('service:vm:snapshot-create-stop')"
                  :subtitle="t('service:vm:snapshot-create-stop-help')"
                  @click="createSnapshot(true)"
                />
              </v-list>
            </v-menu>
          </div>

          <LigojDataTable
            filename="snapshots.csv"
            :headers="snapshotHeaders"
            :items="snapshots"
            item-value="id"
            density="comfortable"
            :loading="snapshotsLoading"
            :no-data-text="t('vm.noSnapshots')"
          >
            <template #item.date="{ item }">
              {{ formatDate(item.date) }}
            </template>
            <template #item.volumes="{ item }">
              {{ formatVolumes(item.volumes) }}
            </template>
            <template #item.statusText="{ item }">
              <v-icon
                size="small"
                class="mr-1"
                :color="snapshotStatusColor(item)"
              >
                {{ snapshotStatusIcon(item) }}
              </v-icon>
              {{ snapshotStatusLabel(item) }}
            </template>
            <template #item.actions="{ item }">
              <v-btn
                v-if="!item.pending"
                icon size="small" variant="text" color="error"
                :title="t('service:vm:snapshot-delete')"
                @click="askDeleteSnapshot(item)"
              >
                <v-icon size="small">mdi-delete</v-icon>
              </v-btn>
            </template>
          </LigojDataTable>
        </v-window-item>
      </v-window>
    </template>

    <!-- Schedule create/edit dialog -->
    <ScheduleEditDialog
      v-model="scheduleDialog"
      :schedule="editingSchedule"
      :saving="savingSchedule"
      @save="saveSchedule"
    />

    <!-- Delete schedule confirmation -->
    <LigojConfirmDialog
      v-model="confirmScheduleDelete"
      :title="t('service:vm:schedule')"
      :message="scheduleDeleteMessage"
      :confirm-label="t('common.delete')"
      confirm-color="error"
      :loading="deletingSchedule"
      @confirm="deleteSchedule"
    />

    <!-- Delete snapshot confirmation -->
    <LigojConfirmDialog
      v-model="confirmSnapshotDelete"
      :title="t('service:vm:snapshot')"
      :message="snapshotDeleteMessage"
      :confirm-label="t('common.delete')"
      confirm-color="error"
      :loading="deletingSnapshot"
      @confirm="deleteSnapshot"
    />
  </div>
</template>

<script setup>
/*
 * Per-subscription VM configuration view — the Vue port of the legacy
 * `service/vm/vm.html` + `configure()` in vm.js. Two tabs:
 *
 *   - Schedule: CRUD over cron-scheduled power operations
 *     (`service/vm/:sub/schedule`), plus CSV report downloads for the
 *     execution history and each node level in the refined chain.
 *   - Snapshot (only when the tool `supportSnapshot`): list + create
 *     (with/without stop) + delete, with polling of the running
 *     snapshot task (`service/vm/:sub/snapshot/task`).
 *
 * Data comes from `rest/subscription/:id/configuration`, whose
 * `configuration` block is the VmConfigurationVo `{ schedules,
 * supportSnapshot }`.
 */
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  useApi, useAppStore, useI18nStore, useErrorStore,
  LigojDataTable, LigojConfirmDialog, APP_BASE,
} from '@ligoj/host'
import ScheduleEditDialog from './ScheduleEditDialog.vue'
import { VM_OPERATIONS } from '../service.js'

const route = useRoute()
const api = useApi()
const appStore = useAppStore()
const errorStore = useErrorStore()
const { t } = useI18nStore()

const subscriptionId = computed(() => route.params.id)

const model = ref(null)
const loading = ref(false)
const tab = ref('schedule')

const schedules = computed(() => model.value?.configuration?.schedules || [])
const supportSnapshot = computed(() => !!model.value?.configuration?.supportSnapshot)

const projectId = computed(() => model.value?.project?.id ?? model.value?.project ?? null)
const projectHref = computed(() =>
  projectId.value ? `/home/project/${projectId.value}` : '/home/project',
)

const scheduleHeaders = computed(() => [
  { title: t('service:vm:operation'), key: 'operation' },
  { title: t('service:vm:cron'), key: 'cron' },
  { title: t('service:vm:schedule-next'), key: 'next' },
  { title: '', key: 'actions', sortable: false, align: 'end', width: '96px' },
])

const snapshotHeaders = computed(() => [
  { title: t('service:vm:snapshot-date'), key: 'date' },
  { title: t('id'), key: 'id' },
  { title: t('service:vm:snapshot-volumes'), key: 'volumes' },
  { title: t('service:vm:snapshot-status'), key: 'statusText' },
  { title: '', key: 'actions', sortable: false, align: 'end', width: '64px' },
])

function operationIcon(op) {
  return VM_OPERATIONS.find((o) => o.id === String(op).toUpperCase())?.icon || 'mdi-cog'
}

// --- CSV reports -------------------------------------------------------
// The refined chain (node → tool → service) drives one "node schedules"
// report per level, mirroring the legacy generateReportButtons().
const reportNodes = computed(() => {
  const out = []
  let node = model.value?.node
  while (node) {
    out.push({ id: node.id, name: node.name || node.id })
    node = node.refined
  }
  return out
})
const executionsReportHref = computed(() =>
  `${APP_BASE}rest/service/vm/${subscriptionId.value}/execution/executions-${subscriptionId.value}.csv`,
)
function schedulesReportHref(node) {
  return `${APP_BASE}rest/service/vm/${node.id}/schedules-${node.id.replace(/:/g, '-')}.csv`
}

// --- schedule CRUD -----------------------------------------------------
const scheduleDialog = ref(false)
const editingSchedule = ref(null)
const savingSchedule = ref(false)

function openCreateSchedule() {
  editingSchedule.value = null
  scheduleDialog.value = true
}
function openEditSchedule(item) {
  editingSchedule.value = { ...item }
  scheduleDialog.value = true
}
async function saveSchedule(payload) {
  savingSchedule.value = true
  try {
    const base = `rest/service/vm/${subscriptionId.value}/schedule`
    if (payload.id) {
      await api.put(base, payload)
    } else {
      await api.post(base, payload)
    }
    scheduleDialog.value = false
    errorStore.success(t('vm.scheduleSaved'))
    await load()
  } finally {
    savingSchedule.value = false
  }
}

const confirmScheduleDelete = ref(false)
const deletingSchedule = ref(false)
const scheduleToDelete = ref(null)
const scheduleDeleteMessage = computed(() =>
  scheduleToDelete.value
    ? t('vm.scheduleDeleteConfirm', { operation: t(`service:vm:${String(scheduleToDelete.value.operation).toLowerCase()}`) })
    : '',
)
function askDeleteSchedule(item) {
  scheduleToDelete.value = item
  confirmScheduleDelete.value = true
}
async function deleteSchedule() {
  deletingSchedule.value = true
  try {
    await api.del(`rest/service/vm/${subscriptionId.value}/schedule/${scheduleToDelete.value.id}`)
    confirmScheduleDelete.value = false
    errorStore.success(t('vm.scheduleDeleted'))
    await load()
  } finally {
    deletingSchedule.value = false
  }
}

// --- snapshots ---------------------------------------------------------
const snapshots = ref([])
const snapshotsLoading = ref(false)
const snapshotBusy = ref(false)
let snapshotPoll = null

async function loadSnapshots() {
  if (!supportSnapshot.value) return
  snapshotsLoading.value = true
  try {
    const data = await api.get(`rest/service/vm/${subscriptionId.value}/snapshot`)
    snapshots.value = Array.isArray(data) ? data : []
    if (snapshots.value.some(isPending)) startSnapshotPoll()
  } finally {
    snapshotsLoading.value = false
  }
}
function isPending(s) {
  return s?.pending || s?.operation === 'delete'
}
async function createSnapshot(stop) {
  snapshotBusy.value = true
  try {
    await api.post(`rest/service/vm/${subscriptionId.value}/snapshot?stop=${stop}`)
    errorStore.success(t('vm.snapshotCreated'))
    startSnapshotPoll()
    await loadSnapshots()
  } finally {
    snapshotBusy.value = false
  }
}

const confirmSnapshotDelete = ref(false)
const deletingSnapshot = ref(false)
const snapshotToDelete = ref(null)
const snapshotDeleteMessage = computed(() =>
  snapshotToDelete.value ? t('vm.snapshotDeleteConfirm', { id: snapshotToDelete.value.id }) : '',
)
function askDeleteSnapshot(item) {
  snapshotToDelete.value = item
  confirmSnapshotDelete.value = true
}
async function deleteSnapshot() {
  deletingSnapshot.value = true
  try {
    await api.del(`rest/service/vm/${subscriptionId.value}/snapshot/${encodeURIComponent(snapshotToDelete.value.id)}`)
    confirmSnapshotDelete.value = false
    errorStore.success(t('vm.snapshotDeleted'))
    startSnapshotPoll()
    await loadSnapshots()
  } finally {
    deletingSnapshot.value = false
  }
}

// Poll the running snapshot task until it finishes, then refresh the
// list — mirrors the legacy `synchronizeSnapshot` setInterval loop.
function startSnapshotPoll() {
  stopSnapshotPoll()
  snapshotPoll = setInterval(async () => {
    try {
      const status = await api.get(`rest/service/vm/${subscriptionId.value}/snapshot/task`)
      if (!status || status.finishedRemote) {
        stopSnapshotPoll()
        await loadSnapshots()
      }
    } catch {
      stopSnapshotPoll()
    }
  }, 5000)
}
function stopSnapshotPoll() {
  if (snapshotPoll) clearInterval(snapshotPoll)
  snapshotPoll = null
}

function snapshotStatusLabel(item) {
  const key = `service:vm:snapshot-status-${item.statusText}`
  const label = t(key)
  return label === key ? (item.statusText || '') : label
}
function snapshotStatusIcon(item) {
  if (isPending(item)) return 'mdi-loading mdi-spin'
  return item.available ? 'mdi-check' : 'mdi-close'
}
function snapshotStatusColor(item) {
  if (isPending(item)) return 'info'
  return item.available ? 'success' : 'error'
}

// --- formatting --------------------------------------------------------
function formatDate(value) {
  if (!value) return ''
  const d = new Date(value)
  return Number.isNaN(d.getTime()) ? String(value) : d.toLocaleString()
}
function formatVolumes(volumes) {
  if (!volumes || !volumes.length) return t('service:vm:snapshot-no-volume')
  const total = volumes.reduce((sum, v) => sum + (v.size || 0), 0)
  return `${total} GB (${volumes.length})`
}

// --- load --------------------------------------------------------------
async function load() {
  loading.value = true
  try {
    const data = await api.get(`rest/subscription/${encodeURIComponent(subscriptionId.value)}/configuration`)
    model.value = data || null
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await load()
  if (supportSnapshot.value) await loadSnapshots()
  appStore.setBreadcrumbs([
    { title: t('nav.home'), to: '/' },
    { title: t('nav.projects'), to: '/home/project' },
    ...(projectId.value ? [{ title: String(projectId.value), to: projectHref.value }] : []),
    { title: model.value?.node?.name || t('service:vm') },
  ], { refresh: load })
})

onBeforeUnmount(stopSnapshotPoll)

watch(() => route.params.id, async (id) => {
  if (id) {
    await load()
    if (supportSnapshot.value) await loadSnapshots()
  }
})
</script>

<style scoped>
.vm-config {
  padding: 0.5rem 0;
}
</style>
