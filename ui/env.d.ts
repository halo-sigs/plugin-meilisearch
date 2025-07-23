/// <reference types="@rsbuild/core/types" />
/// <reference types="unplugin-icons/types/vue" />

export {}

declare module 'axios' {
  export interface AxiosRequestConfig {
    mute?: boolean
  }
}
