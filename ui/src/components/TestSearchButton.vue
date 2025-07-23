<script lang="ts" setup>
import { IconSearch, Toast, VButton } from '@halo-dev/components'
import { useScriptTag } from '@vueuse/core'
import { onUnmounted, ref } from 'vue'

const { load, unload } = useScriptTag(
  `/plugins/PluginSearchWidget/assets/static/search-widget.iife.js?version=${Date.now()}`,
  () => {},
  { manual: true },
)

const loadingSearchWidget = ref(false)

async function handleTestSearch() {
  try {
    loadingSearchWidget.value = true
    await load()

    const searchModalElement = document.createElement('search-modal')

    document.body.append(searchModalElement)

    // @ts-expect-error search-modal is a custom element, and we don't need to type it for now
    searchModalElement.open = true
  } catch (error) {
    console.error('Failed to load search widget', error)
    Toast.error('加载搜索组件失败，请检查是否已正常安装搜索组件')
  } finally {
    loadingSearchWidget.value = false
  }
}

onUnmounted(() => {
  unload()
})
</script>
<template>
  <VButton size="sm" @click="handleTestSearch" :loading="loadingSearchWidget">
    <template #icon>
      <IconSearch />
    </template>
    测试搜索
  </VButton>
</template>
