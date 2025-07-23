<script setup lang="ts">
import prettyBytes from 'pretty-bytes'
import type { Component, Raw } from 'vue'

defineProps<{
  title: string
  value?: number | bigint
  icon: Raw<Component>
  iconColor: string
  iconBgColor: string
}>()
</script>

<template>
  <div class=":uno: flex items-center justify-between border rounded-xl p-4">
    <div>
      <p class=":uno: mb-1 text-sm text-gray-600">{{ title }}</p>
      <slot name="value">
        <p class=":uno: text-xl text-gray-900 font-bold">
          {{ prettyBytes(value || 0) }}
        </p>
      </slot>
    </div>
    <div class=":uno: h-12 w-12 flex items-center justify-center rounded-lg" :class="iconBgColor">
      <component :is="icon" class=":uno: size-6" :class="iconColor" />
    </div>
  </div>
</template>
