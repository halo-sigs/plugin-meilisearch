<script lang="ts" setup>
import { consoleApiClient } from '@halo-dev/api-client'
import { IconRefreshLine, Toast, VButton } from '@halo-dev/components'
import { useQueryClient } from '@tanstack/vue-query'
import { ref } from 'vue'

const queryClient = useQueryClient()

const requestingRebuildIndex = ref(false)

async function handleRebuildIndex() {
  try {
    requestingRebuildIndex.value = true
    await consoleApiClient.content.indices.rebuildAllIndices()
    await queryClient.invalidateQueries({
      queryKey: ['plugin:meilisearch:stats'],
    })
    Toast.success('已请求重建索引')
  } finally {
    requestingRebuildIndex.value = false
  }
}
</script>
<template>
  <VButton size="sm" @click="handleRebuildIndex" :loading="requestingRebuildIndex">
    <template #icon>
      <IconRefreshLine />
    </template>
    重建索引
  </VButton>
</template>
