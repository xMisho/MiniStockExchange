import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { AuthProvider } from '../auth/AuthContext'
import { AccountMenu } from './AccountMenu'
import * as api from '../api/auth'

vi.mock('../api/auth')
const user = { id: 1, username: 'example', email: 'example@example.test', role: 'ADMIN' as const }
let expired: () => void
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(api.getCurrentUser).mockResolvedValue(null)
  vi.mocked(api.refreshCsrf).mockResolvedValue({ token: 'fresh', headerName: 'X-CSRF-TOKEN' })
  vi.mocked(api.onSessionExpired).mockImplementation((listener) => { expired = listener; return () => {} })
  vi.mocked(api.login).mockResolvedValue(user)
  vi.mocked(api.register).mockResolvedValue(user)
  vi.mocked(api.logout).mockResolvedValue(undefined)
})
afterEach(cleanup)
function show() { return render(<AuthProvider><AccountMenu /></AuthProvider>) }
async function openLogin() { fireEvent.click(await screen.findByRole('button', { name: 'Sign in' })) }
function fillLogin() {
  fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'example' } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'ExamplePassword123!' } })
}
it('restores username and role from the existing session', async () => {
  vi.mocked(api.getCurrentUser).mockResolvedValue(user)
  show()
  expect(await screen.findByText('example')).toBeTruthy()
  expect(screen.getByText('ADMIN')).toBeTruthy()
})
it('signs in, refreshes CSRF and displays the account in the header', async () => {
  show(); await openLogin(); fillLogin()
  fireEvent.click(screen.getByRole('button', { name: 'Sign in to account' }))
  expect(await screen.findByText('example')).toBeTruthy()
  await waitFor(() => expect(api.refreshCsrf).toHaveBeenCalledTimes(1))
  expect(screen.queryByLabelText('Password')).toBeNull()
})
it('shows invalid credentials and clears the password', async () => {
  vi.mocked(api.login).mockRejectedValue(new Error('Invalid username or password.'))
  show(); await openLogin(); fillLogin()
  fireEvent.click(screen.getByRole('button', { name: 'Sign in to account' }))
  expect(await screen.findByRole('alert')).toHaveProperty('textContent', 'Invalid username or password.')
  expect((screen.getByLabelText('Password') as HTMLInputElement).value).toBe('')
})
it('registers then asks for login without signing the user in', async () => {
  show(); fireEvent.click(await screen.findByRole('button', { name: 'Create account' }))
  fillLogin()
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'example@example.test' } })
  fireEvent.click(screen.getByRole('button', { name: 'Register account' }))
  expect(await screen.findByText('Account created. Sign in with your username.')).toBeTruthy()
  expect(api.login).not.toHaveBeenCalled()
  expect((screen.getByLabelText('Password') as HTMLInputElement).value).toBe('')
})
it('shows duplicate registration errors without losing the entered username', async () => {
  vi.mocked(api.register).mockRejectedValue(new Error('Username already exists.'))
  show(); fireEvent.click(await screen.findByRole('button', { name: 'Create account' })); fillLogin()
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'example@example.test' } })
  fireEvent.click(screen.getByRole('button', { name: 'Register account' }))
  expect(await screen.findByRole('alert')).toHaveProperty('textContent', 'Username already exists.')
  expect((screen.getByLabelText('Username') as HTMLInputElement).value).toBe('example')
})
it('logs out and refreshes the anonymous CSRF token', async () => {
  vi.mocked(api.getCurrentUser).mockResolvedValue(user)
  show(); fireEvent.click(await screen.findByRole('button', { name: 'Sign out' }))
  expect(await screen.findByRole('button', { name: 'Sign in' })).toBeTruthy()
  expect(api.refreshCsrf).toHaveBeenCalledTimes(1)
})
it('keeps successful login when the post-login token refresh fails', async () => {
  vi.mocked(api.refreshCsrf).mockRejectedValue(new Error('offline'))
  show(); await openLogin(); fillLogin(); fireEvent.click(screen.getByRole('button', { name: 'Sign in to account' }))
  expect(await screen.findByText('example')).toBeTruthy()
  expect(await screen.findByText(/security token could not refresh/)).toBeTruthy()
})
it('resets the account when a protected request reports expiration', async () => {
  vi.mocked(api.getCurrentUser).mockResolvedValue(user)
  show(); await screen.findByText('example')
  act(() => expired())
  expect(await screen.findByRole('button', { name: 'Sign in' })).toBeTruthy()
})
it('rechecks an expired session on window focus', async () => {
  vi.mocked(api.getCurrentUser).mockResolvedValueOnce(user).mockResolvedValue(null)
  show(); await screen.findByText('example')
  fireEvent.focus(window)
  expect(await screen.findByRole('button', { name: 'Sign in' })).toBeTruthy()
})
it('offers session retry on startup outage instead of claiming anonymous', async () => {
  vi.mocked(api.getCurrentUser).mockRejectedValueOnce(new Error('offline')).mockResolvedValue(user)
  show(); const notice = await screen.findByRole('status')
  fireEvent.click(within(notice).getByRole('button', { name: 'Check session' }))
  expect(await screen.findByText('example')).toBeTruthy()
})

it('does not clear the account when logout fails before confirmation', async () => {
  vi.mocked(api.getCurrentUser).mockResolvedValue(user)
  vi.mocked(api.logout).mockRejectedValue(new Error('Access denied or invalid CSRF token.'))
  show(); fireEvent.click(await screen.findByRole('button', { name: 'Sign out' }))
  expect(await screen.findByRole('alert')).toHaveProperty('textContent', 'Access denied or invalid CSRF token.')
  expect(screen.getByText('example')).toBeTruthy()
  expect(api.refreshCsrf).not.toHaveBeenCalled()
})
it('does not let an old anonymous session check overwrite successful login', async () => {
  let resolveOld!: (value: null) => void
  vi.mocked(api.getCurrentUser).mockResolvedValueOnce(null).mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve }))
  show(); await openLogin(); fillLogin()
  fireEvent.focus(window)
  fireEvent.click(screen.getByRole('button', { name: 'Sign in to account' }))
  await screen.findByText('example')
  await act(async () => resolveOld(null))
  expect(screen.getByText('example')).toBeTruthy()
})
it('keeps successful logout when the anonymous token refresh fails', async () => {
  vi.mocked(api.getCurrentUser).mockResolvedValue(user)
  vi.mocked(api.refreshCsrf).mockRejectedValue(new Error('offline'))
  show(); fireEvent.click(await screen.findByRole('button', { name: 'Sign out' }))
  expect(await screen.findByRole('button', { name: 'Sign in' })).toBeTruthy()
  expect(await screen.findByText(/security token could not refresh/)).toBeTruthy()
})
