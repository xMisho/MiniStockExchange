import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { EmailPreferences } from './EmailPreferences'
import * as api from '../../api/auth'
vi.mock('../../api/auth')
vi.mock('../../auth/authState', () => ({ useAuth: () => ({ user: { id: 1 }, busy: false, holdSession: () => () => {} }) }))
beforeEach(() => vi.resetAllMocks())
afterEach(cleanup)
it('discloses missing configuration without offering unavailable email controls', async () => {
  vi.mocked(api.getEmailPreferences).mockResolvedValue({ configured: false, verified: false, alertEmails: false })
  render(<EmailPreferences />)
  expect(await screen.findByText(/Email delivery has not been configured/)).toBeTruthy()
  expect(screen.queryByRole('checkbox')).toBeNull()
})
it('requires verification before exposing opt-in', async () => {
  vi.mocked(api.getEmailPreferences).mockResolvedValue({ configured: true, verified: false, alertEmails: false })
  vi.mocked(api.requestEmailVerification).mockResolvedValue(undefined)
  render(<EmailPreferences />)
  fireEvent.click(await screen.findByRole('button', { name: 'Send verification link' }))
  expect(await screen.findByText(/Verification requested/)).toBeTruthy()
  expect(api.requestEmailVerification).toHaveBeenCalledOnce()
  expect(screen.queryByRole('checkbox')).toBeNull()
})
it('saves an explicit opt-in only after the user selects it', async () => {
  vi.mocked(api.getEmailPreferences).mockResolvedValue({ configured: true, verified: true, alertEmails: false })
  vi.mocked(api.setAlertEmails).mockResolvedValue(undefined)
  render(<EmailPreferences />)
  const checkbox = await screen.findByRole('checkbox', { name: /Email me when/ })
  expect(checkbox).toHaveProperty('checked', false)
  expect(api.setAlertEmails).not.toHaveBeenCalled()
  fireEvent.click(checkbox)
  await waitFor(() => expect(api.setAlertEmails).toHaveBeenCalledWith(true))
})
