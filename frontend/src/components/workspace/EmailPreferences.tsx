import { useCallback, useEffect, useState } from 'react'
import { getEmailPreferences, requestEmailVerification, setAlertEmails } from '../../api/auth'
import { useAuth } from '../../auth/authState'
import { useSymbolResource } from '../../hooks/useSymbolResource'

export function EmailPreferences() {
  const auth = useAuth()
  const load = useCallback((_key: string, signal: AbortSignal) => getEmailPreferences(signal), [])
  const state = useSymbolResource(auth.user ? `email:${auth.user.id}` : null, load, 60000)
  const { retry } = state
  useEffect(() => {
    const refresh = () => retry()
    window.addEventListener('verdex:email-preferences', refresh)
    return () => window.removeEventListener('verdex:email-preferences', refresh)
  }, [retry])
  const [busy, setBusy] = useState(false), [message, setMessage] = useState<string | null>(null), [error, setError] = useState<string | null>(null)
  const data = state.resource.status === 'ready' ? state.resource.data : null
  async function change(action: () => Promise<void>, success: string) {
    if (busy || auth.busy) return
    setBusy(true); setError(null); const release = auth.holdSession()
    try { await action(); setMessage(success); state.retry() } catch (e) { setError(e instanceof Error ? e.message : 'Email settings could not update.') } finally { release(); setBusy(false) }
  }
  if (!auth.user) return null
  return <div className="email-preferences"><h3>Email notifications</h3>
    {data ? !data.configured ? <p>Email delivery has not been configured. In-app alerts still work.</p> : !data.verified ? <><p>Verify your account email before enabling alert emails.</p><button className="text-button" disabled={busy || auth.busy} onClick={() => { void change(requestEmailVerification, 'Verification requested. Check your inbox and spam folder.') }}>Send verification link</button></> : <label className="remember-choice"><input type="checkbox" checked={data.alertEmails} disabled={busy || auth.busy} onChange={e => { void change(() => setAlertEmails(e.target.checked), 'Email preference saved.') }} /><span>Email me when a price alert triggers</span></label> : <p>Loading email settings…</p>}
    {(error || state.errorMessage) && <p role="alert">{error ?? state.errorMessage} <button className="text-button" onClick={state.retry}>Retry email settings</button></p>}{message && <p role="status">{message}</p>}
    <p className="panel-footnote">Checks continue while the server is awake. Sleeping or unavailable hosting can delay checks or miss crossings. Provider acceptance does not guarantee inbox delivery.</p>
  </div>
}
