import { createContext, useContext } from 'react'
import type { User } from '../api/auth'
export type Status = 'loading' | 'authenticated' | 'anonymous'
interface AuthState { user: User | null; status: Status; error: string | null; busy: boolean; signIn: (username: string, password: string) => Promise<void>; signOut: () => Promise<void>; restore: () => Promise<void> }
export const Context = createContext<AuthState | null>(null)

export function useAuth() { const auth = useContext(Context); if (!auth) throw new Error('AuthProvider is required.'); return auth }
