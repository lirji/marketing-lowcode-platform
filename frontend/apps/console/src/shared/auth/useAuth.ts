import { useContext } from 'react'
import { AuthStateContext } from './auth-context'

export function useAuth() {
  return useContext(AuthStateContext)
}
