import { useEffect, useRef, useState } from 'react'
import { resetPassword, verifyEmail } from '../api/auth'
import { useAuth } from '../auth/authState'
import { Modal } from './Modal'

interface Link { kind: 'reset-password' | 'verify-email'; token: string }
function takeLink(): Link | null {
  const match = /^#\/(reset-password|verify-email)\?(.+)$/.exec(window.location.hash)
  if (!match) return null
  const token = new URLSearchParams(match[2]).get('token') ?? ''
  return { kind: match[1] as Link['kind'], token }
}
export function RecoveryLink() {
  const auth = useAuth()
  const [link, setLink] = useState<Link | null>(takeLink)
  const [password, setPassword] = useState(''), [confirm, setConfirm] = useState(''), [show, setShow] = useState(false)
  const [busy, setBusy] = useState(false), [error, setError] = useState<string | null>(null), [done, setDone] = useState(false)
  const sending = useRef(false)
  useEffect(() => {
    const changed = () => {
      const next = takeLink()
      if (next) { setLink(next); setDone(false); setError(null); setPassword(''); setConfirm(''); window.history.replaceState(null, '', window.location.pathname + window.location.search + '#/markets') }
    }
    // Remove the secret from browser history without persisting it in local/session storage.
    if (takeLink()) window.history.replaceState(null, '', window.location.pathname + window.location.search + '#/markets')
    window.addEventListener('hashchange', changed)
    return () => window.removeEventListener('hashchange', changed)
  }, [])
  if (!link) return null
  const reset = link.kind === 'reset-password'
  return <Modal title={reset ? 'Choose a new password' : 'Verify your email'} busy={busy || auth.busy} onClose={() => { setLink(null); setPassword(''); setConfirm('') }}>
    {done ? <p role="status">{reset ? 'Password changed. Sign in again with your new password.' : 'Email verified. You can now enable price-alert emails in Markets.'}</p> : <form className="account-form" onSubmit={event => {
      event.preventDefault()
      if (sending.current || auth.busy) return
      if (reset && password !== confirm) { setError('Passwords do not match.'); return }
      sending.current = true; setBusy(true); setError(null)
      void (reset ? resetPassword(link.token, password) : verifyEmail(link.token)).then(async () => { setDone(true); if (!reset) window.dispatchEvent(new Event('verdex:email-preferences')); setPassword(''); setConfirm(''); await auth.restore() }).catch((e: unknown) => setError(e instanceof Error ? e.message : 'The link could not be confirmed.')).finally(() => { sending.current = false; setBusy(false) })
    }}>
      {reset ? <><label>New password<input type={show ? 'text' : 'password'} autoComplete="new-password" required minLength={15} maxLength={128} value={password} disabled={busy} onChange={e => setPassword(e.target.value)} /></label><label>Confirm new password<input type={show ? 'text' : 'password'} autoComplete="new-password" required minLength={15} maxLength={128} value={confirm} disabled={busy} onChange={e => setConfirm(e.target.value)} /></label><button type="button" className="text-button" aria-pressed={show} disabled={busy} onClick={() => setShow(!show)}>{show ? 'Hide passwords' : 'Show passwords'}</button><p>Use 15–128 characters. Existing sessions and remembered logins will be revoked.</p></> : <p>Confirm that you control this inbox. Email alerts remain off until you enable them.</p>}
      {error && <p role="alert">{error}</p>}
      <button className="primary-button" disabled={busy || auth.busy}>{busy ? 'Please wait…' : reset ? 'Change password' : 'Verify email'}</button>
    </form>}
  </Modal>
}
