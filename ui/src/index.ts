import { VLoading } from '@halo-dev/components'
import { definePlugin } from '@halo-dev/console-shared'
import 'uno.css'
import { defineAsyncComponent, markRaw } from 'vue'
import SimpleIconsMeilisearch from '~icons/simple-icons/meilisearch?color=#FF5CAA'

export default definePlugin({
  routes: [
    {
      parentName: 'ToolsRoot',
      route: {
        path: 'meilisearch-overview',
        name: 'MeilisearchOverview',
        redirect: '/plugins/meilisearch?tab=overview',
        meta: {
          title: 'Meilisearch 数据概览',
          description: '查看 Meilisearch 搜索引擎的索引数据',
          searchable: true,
          permissions: [],
          menu: {
            name: 'Meilisearch 数据概览',
            icon: markRaw(SimpleIconsMeilisearch),
            priority: 0,
          },
        },
      },
    },
  ],
  extensionPoints: {
    'plugin:self:tabs:create': () => {
      return [
        {
          id: 'overview',
          label: '数据概览',
          component: defineAsyncComponent({
            loader: () => import('./components/OverviewTab.vue'),
            loadingComponent: VLoading,
          }),
          permissions: ['*'],
        },
      ]
    },
  },
})
