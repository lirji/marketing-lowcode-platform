import { ApiProblem } from '../../shared/api/client'

export const bundleHttp = { NETWORK_ERROR: 0, NOT_FOUND: 404, CONFLICT: 409, SERVER_ERROR: 500 } as const

export function uncertainWrite(error: unknown) {
  return error instanceof ApiProblem && (error.status === bundleHttp.NETWORK_ERROR || error.status >= bundleHttp.SERVER_ERROR)
}
