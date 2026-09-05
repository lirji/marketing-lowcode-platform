import type { User, UserManager } from 'oidc-client-ts'
import { sanitizeReturnTo } from './tenantSelection'

let inFlight: Promise<User> | undefined

export function returnToFromState(state: unknown) {
  if (typeof state === 'object' && state !== null && 'returnTo' in state) {
    return sanitizeReturnTo((state as { returnTo: unknown }).returnTo)
  }
  return '/'
}

/** React StrictMode 会把 effect 跑两遍；授权码只能换一次 ticket。 */
export function completeOidcRedirect(manager: UserManager): Promise<User> {
  inFlight ??= manager.signinRedirectCallback()
  return inFlight
}
