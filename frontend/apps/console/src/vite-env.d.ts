/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string
  readonly VITE_DEMO_MODE?: string
  readonly VITE_AUTH_MODE?: 'DEV' | 'OIDC'
  readonly VITE_OIDC_AUTHORITY?: string
  readonly VITE_OIDC_CLIENT_ID?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

interface Window {
  __MARKETING_CONFIG__?: {
    API_BASE_URL?: string
    DEMO_MODE?: boolean | string
    AUTH_MODE?: 'DEV' | 'OIDC'
    ALLOW_DEV_AUTH?: boolean | string
    OIDC_AUTHORITY?: string
    OIDC_CLIENT_ID?: string
    OIDC_SCOPE?: string
  }
}
