import { useCallback, useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import * as api from '../api/auth'
import type { User } from '../api/auth'
import { Context } from './authState'
import type { Status } from './authState'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [status, setStatus] = useState<Status>('loading')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const generation = useRef(0)
  const mutation = useRef(false)
  const restore = useCallback(async () => {
    if (mutation.current) return
    const version = ++generation.current
    try {
      const current = await api.getCurrentUser()
      if (version !== generation.current) return
      setUser(current); setStatus(current ? 'authenticated' : 'anonymous'); setError(null)
    } catch {
      if (version === generation.current) setError('Couldn’t check your session. Retry when the server is available.')
    }
  }, [])
  const invalidate = useCallback(() => { generation.current++ }, [])
  useEffect(() => {
    let active = true
    void Promise.resolve().then(() => { if (active) void restore() })
    const unsubscribe = api.onSessionExpired(() => { invalidate(); setUser(null); setStatus('anonymous'); setError('Your session expired. Please sign in again.') })
    const focus = () => { if (document.visibilityState !== 'hidden') void restore() }
    window.addEventListener('focus', focus)
    document.addEventListener('visibilitychange', focus)
    return () => { active = false; invalidate(); unsubscribe(); window.removeEventListener('focus', focus); document.removeEventListener('visibilitychange', focus) }
  }, [restore, invalidate])
  const rotate = async () => {
    try { await api.refreshCsrf(); setError(null) }
    catch { setError('Your session changed, but the security token could not refresh. The next action will request a fresh token.') }
  }
  const signIn = async (username: string, password: string) => {
    if (mutation.current) return
    mutation.current = true; generation.current++; setBusy(true)
    try { const current = await api.login(username, password); setUser(current); setStatus('authenticated'); await rotate() }
    finally { mutation.current = false; setBusy(false) }
  }
  const signOut = async () => {
    if (mutation.current) return
    mutation.current = true; generation.current++; setBusy(true)
    try { await api.logout(); setUser(null); setStatus('anonymous'); await rotate() }
    finally { mutation.current = false; setBusy(false) }
  }
  return <Context.Provider value={{ user, status, error, busy, signIn, signOut, restore }}>{children}</Context.Provider>
}
