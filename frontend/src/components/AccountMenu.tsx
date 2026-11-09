import { useEffect, useId, useState } from 'react'
import type { FormEvent } from 'react'
import { useAuth } from '../auth/authState'
import { register, forgotPassword, getAuthCapabilities } from '../api/auth'
import { Modal } from './Modal'

export function AccountMenu() {
  const auth = useAuth()
  const [recoveryAvailable, setRecoveryAvailable] = useState<boolean | 'loading' | 'error'>('loading')
  useEffect(() => {
    const controller = new AbortController()
    void getAuthCapabilities(controller.signal).then(value => { if (!controller.signal.aborted) setRecoveryAvailable(value.passwordRecovery) }).catch(() => { if (!controller.signal.aborted) setRecoveryAvailable('error') })
    return () => controller.abort()
  }, [])
  const [mode, setMode] = useState<'login' | 'register' | 'forgot' | null>(null)
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [rememberMe, setRememberMe] = useState(false)
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
      if (mode === 'forgot') {
        if (recoveryAvailable !== true) { setError('Email password recovery is unavailable on this demo.'); return }
        await forgotPassword(email); setMessage('If an account matches that address, a password-reset email will be sent. Check your inbox and spam folder.')
      } else if (mode === 'register') {
        await register(username, email, password)
        setMode('login'); setMessage('Account created. Sign in with your username or email.'); setEmail('')
      } else { await auth.signIn(username, password, rememberMe); setMode(null) }
    } catch (failure) { setError(failure instanceof Error ? failure.message : 'The account request failed.') }
    finally { setPassword(''); setShowPassword(false); setPending(false) }
  }
  const changeMode = (next: 'login' | 'register' | 'forgot' | null) => { setMode(next); setRememberMe(false); setPassword(''); setShowPassword(false); setError(null); setMessage(null) }
  return <div className="account-area">
    {auth.status === 'loading' ? <span>Connecting to your account…</span> : auth.user ? <div className="account-actions">
      <span>{auth.user.username} <span className="account-role">{auth.user.role}</span></span>
      <button type="button" disabled={auth.busy} onClick={() => { setError(null); void auth.signOut().catch((failure: unknown) => setError(failure instanceof Error ? failure.message : 'Logout failed.')) }}>{auth.busy ? 'Please wait…' : 'Sign out'}</button>
    </div> : <div className="account-actions"><button type="button" disabled={pending || auth.busy} aria-haspopup="dialog" onClick={() => changeMode('login')}>Sign in</button><button type="button" disabled={pending || auth.busy} aria-haspopup="dialog" onClick={() => changeMode('register')}>Create account</button></div>}
    {auth.error && <p className="account-notice" role="status">{auth.error} <button type="button" onClick={() => { void auth.restore() }}>Check session</button></p>}
    {error && !mode && <p className="account-notice" role="alert">{error}</p>}
    {mode && !auth.user && <Modal title={mode === 'forgot' ? 'Reset your password' : mode === 'login' ? 'Welcome back' : 'Create your account'}
      description={mode === 'forgot' ? 'Enter the email address used for your account. Reset links expire after 30 minutes.' : mode === 'login' ? 'Sign in to place simulated orders and manage your history.' : 'Join your local exchange. No real money is involved.'}
      busy={pending || auth.busy} onClose={() => changeMode(null)}>
      <form className="account-form" aria-busy={pending} onSubmit={(event) => { void submit(event) }}>
        {mode !== 'forgot' && <label>{mode === 'login' ? 'Username or email' : 'Username'}<input required autoComplete="username" value={username} onChange={(event) => setUsername(event.target.value)} disabled={pending} /></label>}
        {mode !== 'login' && <label>Email<input required type="email" autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} disabled={pending} /></label>}
        {mode !== 'forgot' && <div className="password-field"><label htmlFor={passwordId}>Password</label><div className="password-control">
          <input id={passwordId} required type={showPassword ? 'text' : 'password'} autoComplete={mode === 'register' ? 'new-password' : 'current-password'} minLength={mode === 'register' ? 15 : undefined} maxLength={128} value={password} onChange={(event) => setPassword(event.target.value)} disabled={pending} />
          <button type="button" className="password-toggle" aria-label={showPassword ? 'Hide password' : 'Show password'} aria-pressed={showPassword} aria-controls={passwordId} disabled={pending} onClick={() => setShowPassword((value) => !value)}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z" /><circle cx="12" cy="12" r="3" />{showPassword && <path d="m3 3 18 18" />}</svg>
          </button>
        </div></div>}
        {mode === 'login' && (recoveryAvailable === false ? <p className="panel-footnote">Email password recovery is unavailable on this demo.</p> : <button type="button" className="text-button" disabled={pending || auth.busy || recoveryAvailable !== true} onClick={() => changeMode('forgot')}>Forgot password?</button>)}
        {mode === 'login' && recoveryAvailable === 'error' && <p className="panel-footnote">Couldn’t check password recovery availability. Refresh to retry.</p>}
        {mode === 'forgot' && <button type="button" className="text-button" disabled={pending} onClick={() => changeMode('login')}>Back to sign in</button>}
        {mode === 'login' && <label className="remember-choice"><input type="checkbox" checked={rememberMe} disabled={pending || auth.busy} onChange={event => setRememberMe(event.target.checked)} /><span>Remember me for 14 days<small>Use only on a device you trust.</small></span></label>}
        {mode === 'register' && <p className="panel-footnote">Use 15–128 characters. Registration creates a regular user account.</p>}
        {error && <p role="alert" className="account-notice">{error}</p>}
        {message && <p role="status" className="account-notice">{message}</p>}
        <button className="primary-button" type="submit" disabled={pending || auth.busy}>{pending ? 'Please wait…' : mode === 'forgot' ? 'Send reset link' : mode === 'login' ? 'Sign in to account' : 'Register account'}</button>
      </form>
    </Modal>}
  </div>
}
