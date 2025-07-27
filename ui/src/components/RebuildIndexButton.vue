<script lang="ts" setup>
import { consoleApiClient } from '@halo-dev/api-client'
import { Dialog, IconRefreshLine, Toast, VButton } from '@halo-dev/components'
import { useQueryClient } from '@tanstack/vue-query'

const queryClient = useQueryClient()

async function handleRebuildIndex() {
  Dialog.warning({
    title: '重建索引',
    description: '重建索引会全量同步数据到搜索引擎，短时间内可能造成大量性能消耗，是否继续？',
    confirmText: '继续',
    confirmType: 'danger',
    onConfirm: async () => {
      await consoleApiClient.content.indices.rebuildAllIndices()
      await queryClient.invalidateQueries({
        queryKey: ['plugin:meilisearch:stats'],
      })
      Toast.success('已请求重建索引')
    },
  })
}
</script>
<template>
  <VButton size="sm" @click="handleRebuildIndex">
    <template #icon>
      <IconRefreshLine />
    </template>
    重建索引
  </VButton>
</template>
