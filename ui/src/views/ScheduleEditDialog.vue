<template>
  <v-dialog :model-value="modelValue" max-width="520" @update:model-value="$emit('update:modelValue', $event)">
    <v-card>
      <v-card-title>{{ t('service:vm:schedule') }}</v-card-title>
      <v-card-text>
        <v-form ref="formRef" @submit.prevent="onSave">
          <v-select
            v-model="form.operation"
            :items="operationItems"
            item-title="title"
            item-value="id"
            :label="t('service:vm:operation')"
            :rules="REQUIRED_RULES"
            variant="outlined"
            density="comfortable"
          >
            <template #item="{ item, props: itemProps }">
              <v-list-item v-bind="itemProps" :title="item.raw.title" :prepend-icon="item.raw.icon" />
            </template>
          </v-select>

          <v-text-field
            v-model="form.cron"
            :label="t('service:vm:cron')"
            :rules="REQUIRED_RULES"
            :hint="t('service:vm:operation-executed-help')"
            persistent-hint
            variant="outlined"
            density="comfortable"
            class="mt-2"
          />
        </v-form>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" :disabled="saving" @click="$emit('update:modelValue', false)">
          {{ t('common.cancel') }}
        </v-btn>
        <v-btn color="primary" variant="elevated" :loading="saving" @click="onSave">
          {{ t('common.save') }}
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script setup>
/*
 * Schedule create/edit dialog. Replaces the legacy `#vm-schedules-popup`
 * Bootstrap modal + jqCron widget. The legacy UI used jqCron to build the
 * CRON visually; here we keep a plain text field (Vuetify has no CRON
 * widget) with the operation select — the backend validates the
 * expression and returns `vm-cron` / `vm-cron-second` errors which the
 * host's error toast surfaces.
 *
 * The operation enum is sent UPPERCASE (VmOperation); the parent view
 * POSTs/PUTs the emitted payload to `service/vm/:sub/schedule`.
 */
import { ref, reactive, computed, watch } from 'vue'
import { useI18nStore } from '@ligoj/host'
import { VM_OPERATIONS } from '../service.js'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  // The schedule being edited, or null for a new one.
  schedule: { type: Object, default: null },
  saving: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue', 'save'])

const { t } = useI18nStore()

// Hoisted rules array — an inline `[required]` is a fresh array each
// render and trips Vuetify 4's "Maximum recursive updates" in dialogs.
const required = (v) => (v !== null && v !== undefined && v !== '') || t('common.required')
const REQUIRED_RULES = [required]

const formRef = ref(null)
const form = reactive({ id: null, operation: 'OFF', cron: '0 0 0 * * ?' })

const operationItems = computed(() =>
  VM_OPERATIONS.map((op) => ({ id: op.id, icon: op.icon, title: t(`service:vm:${op.id.toLowerCase()}`) })),
)

// Re-seed the form whenever the dialog opens (new vs edit).
watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    const s = props.schedule
    form.id = s?.id ?? null
    // Backend serialises the operation enum UPPERCASE; normalise either way.
    form.operation = (s?.operation ? String(s.operation).toUpperCase() : 'OFF')
    form.cron = s?.cron ?? '0 0 0 * * ?'
  },
)

async function onSave() {
  const result = await formRef.value?.validate?.()
  if (result && result.valid === false) return
  emit('save', { id: form.id, operation: form.operation, cron: form.cron })
}
</script>
