import { axiosInstance } from '@halo-dev/api-client'
import { MeilisearchConsoleV1alpha1Api } from './generated'

const meilisearchConsoleApiClient = {
  index: new MeilisearchConsoleV1alpha1Api(undefined, '', axiosInstance),
}

export { meilisearchConsoleApiClient }
