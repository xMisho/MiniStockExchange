import { useEffect, useState } from 'react'

interface Props { state: 'loading' | 'error' | 'ready'; refreshError: boolean }
export function UpdateStatus({ state, refreshError }: Props) {
  const [online, setOnline] = useState(() => navigator.onLine)
  const [visible, setVisible] = useState(() => document.visibilityState !== 'hidden')
  useEffect(() => {
    const connectivity = () => setOnline(navigator.onLine)
    const visibility = () => setVisible(document.visibilityState !== 'hidden')
    window.addEventListener('online', connectivity); window.addEventListener('offline', connectivity)
    document.addEventListener('visibilitychange', visibility)
    return () => { window.removeEventListener('online', connectivity); window.removeEventListener('offline', connectivity); document.removeEventListener('visibilitychange', visibility) }
  }, [])
  const label = !online ? 'Offline' : !visible ? 'Updates paused' : state === 'error' || refreshError ? 'Retrying updates' : state === 'loading' ? 'Connecting' : 'Auto-refresh on'
  const tone = !online || state === 'error' || refreshError ? 'warning' : visible && state === 'ready' ? 'connected' : 'paused'
  return <span className={`update-status ${tone}`} role="status"><span aria-hidden="true" />{label}</span>
}
