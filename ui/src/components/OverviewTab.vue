<script lang="ts" setup>
import { meilisearchConsoleApiClient } from '@/api'
import { VButton, VEmpty, VLoading, VSpace, VStatusDot } from '@halo-dev/components'
import { useQuery } from '@tanstack/vue-query'
import { AxiosError } from 'axios'
import { computed, markRaw } from 'vue'
import MingcuteChartBarLine from '~icons/mingcute/chart-bar-line'
import MingcuteChartPie2Line from '~icons/mingcute/chart-pie-2-line'
import MingcuteDocument2Line from '~icons/mingcute/document-2-line'
import MingcuteLightningLine from '~icons/mingcute/lightning-line'
import MingcuteServer2Line from '~icons/mingcute/server-2-line'
import ExtensionPointChecker from './ExtensionPointChecker.vue'
import RebuildIndexButton from './RebuildIndexButton.vue'
import StatCard from './StatCard.vue'
import TestSearchButton from './TestSearchButton.vue'

const {
  data: stats,
  isLoading,
  error,
  refetch,
} = useQuery({
  queryKey: ['plugin:meilisearch:stats'],
  queryFn: async () => {
    const { data } = await meilisearchConsoleApiClient.index.getMeilisearchStats({
      mute: true,
    })
    return data
  },
  retry: false,
  refetchInterval(data) {
    return data?.indexing ? 1000 : false
  },
})

const errorMessage = computed(() => {
  if (error.value instanceof AxiosError) {
    return error.value.response?.data?.detail || '未知错误'
  }
  if (error.value instanceof Error) {
    return error.value.message
  }
  return '未知错误'
})

const formatNumber = (num: number) => {
  return new Intl.NumberFormat().format(num)
}

const fieldDistributionItems = computed(() => {
  if (!stats?.value?.fieldDistribution) return []

  const total = stats.value.numberOfDocuments || 0
  return Object.entries(stats.value.fieldDistribution)
    .map(([field, count]) => ({
      field,
      count: count as number,
      percentage: total > 0 ? Math.round(((count as number) / total) * 100) : 0,
    }))
    .sort((a, b) => b.count - a.count)
})
</script>

<template>
  <div class=":uno: p-4 space-y-4">
    <VSpace v-if="!isLoading && !error">
      <RebuildIndexButton />
      <TestSearchButton />
    </VSpace>

    <ExtensionPointChecker v-if="!isLoading && !error" />

    <VLoading v-if="isLoading" />

    <VEmpty v-else-if="error" title="加载失败">
      <template #message>
        数据加载失败，请检查 Meilisearch 服务配置，错误信息：{{ errorMessage }}
      </template>
      <template #actions>
        <VSpace>
          <VButton @click="refetch"> 重试 </VButton>
          <VButton
            @click="
              $router.push({
                name: 'PluginDetail',
                params: {
                  name: 'meilisearch',
                },
                query: {
                  tab: 'basic',
                },
              })
            "
          >
            检查配置
          </VButton>
        </VSpace>
      </template>
    </VEmpty>

    <div v-else-if="stats" class=":uno: space-y-4">
      <div class=":uno: grid grid-cols-1 gap-4 lg:grid-cols-4 md:grid-cols-2">
        <StatCard
          title="文档数量"
          :value="stats.numberOfDocuments || 0"
          :icon="markRaw(MingcuteDocument2Line)"
          iconColor="text-blue-600"
          iconBgColor="bg-blue-100"
        />

        <StatCard
          title="数据库大小"
          :value="stats.rawDocumentDbSize || 0"
          :icon="markRaw(MingcuteServer2Line)"
          iconColor="text-green-600"
          iconBgColor="bg-green-100"
        />

        <StatCard
          title="平均文档大小"
          :value="stats.avgDocumentSize || 0"
          :icon="markRaw(MingcuteChartPie2Line)"
          iconColor="text-purple-600"
          iconBgColor="bg-purple-100"
        />

        <StatCard
          title="索引状态"
          :icon="markRaw(MingcuteLightningLine)"
          iconColor="text-yellow-600"
          iconBgColor="bg-yellow-100"
        >
          <template #value>
            <VStatusDot
              :animate="stats.indexing"
              :text="stats.indexing ? '索引中' : '就绪'"
              :state="stats.indexing ? 'warning' : 'success'"
            />
          </template>
        </StatCard>
      </div>

      <div class=":uno: border rounded-xl p-4">
        <h3 class=":uno: mb-4 flex items-center text-base text-gray-900 font-semibold">
          <MingcuteChartBarLine class=":uno: mr-2 size-5 text-gray-600" />
          字段分布
        </h3>

        <VEmpty v-if="fieldDistributionItems.length === 0" title="暂无数据" />

        <div v-else class=":uno: grid grid-cols-1 gap-4 lg:grid-cols-3 md:grid-cols-2">
          <div
            v-for="item in fieldDistributionItems"
            :key="item.field"
            class=":uno: rounded-lg bg-gray-50 p-4"
          >
            <div class=":uno: mb-2 flex items-center justify-between">
              <span class=":uno: text-sm text-gray-900 font-medium">{{ item.field }}</span>
              <span class=":uno: text-sm text-gray-600">{{ item.percentage }}%</span>
            </div>
            <div class=":uno: flex items-center justify-between">
              <span class=":uno: text-xs text-gray-500">{{ formatNumber(item.count) }} 个文档</span>
              <div class=":uno: h-1.5 w-16 rounded-full bg-gray-200">
                <div
                  class=":uno: h-1.5 rounded-full bg-blue-600 transition-all duration-300"
                  :style="{ width: `${item.percentage}%` }"
                ></div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <VEmpty v-else title="暂无数据" description="无法获取索引统计信息" />
  </div>
</template>
