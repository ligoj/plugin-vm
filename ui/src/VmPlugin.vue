<template>
  <div class="plugin-vm">
    <h2>{{ t('service:vm') }}</h2>
    <p class="text-medium-emphasis">{{ t('service:vm:operation-executed-help') }}</p>
  </div>
</template>

<script setup>
// Minimal plugin shell. The plugin has no standalone landing screen of
// its own (the legacy UI was purely per-subscription configuration);
// real work happens in VmConfigView, reached from a subscription row.
import { useI18nStore } from '@ligoj/host'
const { t } = useI18nStore()
</script>

<style scoped>
.plugin-vm {
  padding: 1rem;
}
</style>
