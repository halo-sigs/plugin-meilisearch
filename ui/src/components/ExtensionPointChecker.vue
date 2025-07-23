<script lang="ts" setup>
import { consoleApiClient } from '@halo-dev/api-client'
import { Toast, VAlert, VButton } from '@halo-dev/components'
import { useQuery } from '@tanstack/vue-query'
import { computed, ref } from 'vue'

const EXTENSION_POINT_ENABLED_GROUP = 'extensionPointEnabled'
const EXTENSION_POINT_NAME = 'search-engine'
const MEILISEARCH_EXTENSION_DEFINITION_NAME = 'search-engine-meilisearch-x'

const {
  data: value,
  isLoading,
  refetch,
} = useQuery({
  queryKey: ['plugin:meilisearch:extension-point', EXTENSION_POINT_NAME],
  queryFn: async () => {
    const { data: extensionPointEnabled } =
      await consoleApiClient.configMap.system.getSystemConfigByGroup({
        group: EXTENSION_POINT_ENABLED_GROUP,
      })

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const extensionPointValue = (extensionPointEnabled as any)?.[EXTENSION_POINT_NAME]

    // check is array
    if (Array.isArray(extensionPointValue)) {
      return extensionPointValue[0]
    }

    return null
  },
})

const isMeilisearchEnabled = computed(() => {
  return value.value === MEILISEARCH_EXTENSION_DEFINITION_NAME
})

const switching = ref(false)

async function handleEnableMeilisearch() {
  switching.value = true

  try {
    const { data: extensionPointEnabled } =
      await consoleApiClient.configMap.system.getSystemConfigByGroup({
        group: EXTENSION_POINT_ENABLED_GROUP,
      })

    await consoleApiClient.configMap.system.updateSystemConfigByGroup({
      group: EXTENSION_POINT_ENABLED_GROUP,
      body: {
        ...extensionPointEnabled,
        [EXTENSION_POINT_NAME]: [MEILISEARCH_EXTENSION_DEFINITION_NAME],
      },
    })

    Toast.success('切换成功')
    await refetch()
  } catch (error) {
    console.error('Failed to switch search engine', error)
    Toast.error('切换搜索引擎失败，请重试')
  } finally {
    switching.value = false
  }
}
</script>
<template>
  <div class=":uno: w-full sm:w-96">
    <VAlert
      v-if="!isMeilisearchEnabled && !isLoading"
      title="提示"
      description="检测到当前已启用的搜索引擎不是 Meilisearch"
    >
      <template #actions>
        <VButton @click="handleEnableMeilisearch" size="sm" :loading="switching">
          切换为 Meilisearch
        </VButton>
      </template>
    </VAlert>
  </div>
</template>
