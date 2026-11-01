import { useId, useState } from 'react'
import type { FormEvent } from 'react'
import { useAuth } from '../auth/authState'
import { register } from '../api/auth'
import { Modal } from './Modal'

export function AccountMenu() {
  const auth = useAuth()
  const [mode, setMode] = useState<'login' | 'register' | null>(null)
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const passwordId = useId()
  const [pending, setPending] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (pending || auth.busy) return
    setPending(true); setError(null); setMessage(null)
    try {
      if (mode === 'register') {
        await register(username, email, password)
        setMode('login'); setMessage('Account created. Sign in with your username.'); setEmail('')
      } else { await auth.signIn(username, password); setMode(null) }
    } catch (failure) { setError(failure instanceof Error ? failure.message : 'The account request failed.') }
    finally { setPassword(''); setShowPassword(false); setPending(false) }
  }
  const changeMode = (next: 'login' | 'register' | null) => { setMode(next); setPassword(''); setShowPassword(false); setError(null); setMessage(null) }
  return <div className="account-area">
    {auth.status === 'loading' ? <span>Checking session…</span> : auth.user ? <div className="account-actions">
      <span>{auth.user.username} <span className="account-role">{auth.user.role}</span></span>
      <button type="button" disabled={auth.busy} onClick={() => { setError(null); void auth.signOut().catch((failure: unknown) => setError(failure instanceof Error ? failure.message : 'Logout failed.')) }}>{auth.busy ? 'Please wait…' : 'Sign out'}</button>
    </div> : <div className="account-actions"><button type="button" disabled={pending || auth.busy} aria-haspopup="dialog" onClick={() => changeMode('login')}>Sign in</button><button type="button" disabled={pending || auth.busy} aria-haspopup="dialog" onClick={() => changeMode('register')}>Create account</button></div>}
    {auth.error && <p className="account-notice" role="status">{auth.error} <button type="button" onClick={() => { void auth.restore() }}>Check session</button></p>}
    {error && !mode && <p className="account-notice" role="alert">{error}</p>}
    {mode && !auth.user && <Modal title={mode === 'login' ? 'Welcome back' : 'Create your account'}
      description={mode === 'login' ? 'Sign in to place simulated orders and manage your history.' : 'Join your local exchange. No real money is involved.'}
      busy={pending || auth.busy} onClose={() => changeMode(null)}>
      <form className="account-form" aria-busy={pending} onSubmit={(event) => { void submit(event) }}>
        <label>Username<input required autoComplete="username" value={username} onChange={(event) => setUsername(event.target.value)} disabled={pending} /></label>
        {mode === 'register' && <label>Email<input required type="email" autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} disabled={pending} /></label>}
        <div className="password-field"><label htmlFor={passwordId}>Password</label><div className="password-control">
          <input id={passwordId} required type={showPassword ? 'text' : 'password'} autoComplete={mode === 'register' ? 'new-password' : 'current-password'} minLength={mode === 'register' ? 15 : undefined} maxLength={128} value={password} onChange={(event) => setPassword(event.target.value)} disabled={pending} />
          <button type="button" className="password-toggle" aria-label={showPassword ? 'Hide password' : 'Show password'} aria-pressed={showPassword} aria-controls={passwordId} disabled={pending} onClick={() => setShowPassword((value) => !value)}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z" /><circle cx="12" cy="12" r="3" />{showPassword && <path d="m3 3 18 18" />}</svg>
          </button>
        </div></div>
        {mode === 'register' && <p className="panel-footnote">Use 15–128 characters. Registration creates a regular user account.</p>}
        {error && <p role="alert" className="account-notice">{error}</p>}
        {message && <p role="status" className="account-notice">{message}</p>}
        <button className="primary-button" type="submit" disabled={pending || auth.busy}>{pending ? 'Please wait…' : mode === 'login' ? 'Sign in to account' : 'Register account'}</button>
      </form>
    </Modal>}
  </div>
}
