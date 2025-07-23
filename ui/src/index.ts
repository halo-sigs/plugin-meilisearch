import { VLoading } from '@halo-dev/components'
import { definePlugin } from '@halo-dev/console-shared'
import 'uno.css'
import { defineAsyncComponent } from 'vue'

export default definePlugin({
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
