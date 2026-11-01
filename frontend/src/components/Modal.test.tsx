import { useState } from 'react'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { Modal } from './Modal'

afterEach(cleanup)
it('focuses the first field, closes on Escape/cancel, and restores the opener and page scrolling', () => {
  function Example() {
    const [open, setOpen] = useState(false)
    return <><button onClick={() => setOpen(true)}>Open account</button>{open && <Modal title="Account" onClose={() => setOpen(false)}><label>Username<input /></label></Modal>}</>
  }
  document.body.style.overflow = 'auto'
  render(<Example />)
  const opener = screen.getByRole('button', { name: 'Open account' })
  opener.focus(); fireEvent.click(opener)
  expect(document.activeElement).toBe(screen.getByLabelText('Username'))
  expect(document.body.style.overflow).toBe('hidden')
  const cancel = new Event('cancel', { cancelable: true })
  fireEvent(screen.getByRole('dialog', { name: 'Account' }), cancel)
  expect(cancel.defaultPrevented).toBe(true)
  expect(screen.queryByRole('dialog')).toBeNull()
  expect(document.activeElement).toBe(opener)
  expect(document.body.style.overflow).toBe('auto')
  document.body.style.overflow = ''
})
it('prevents Escape or closing while the request is pending', () => {
  const close = vi.fn()
  render(<Modal title="Saving" onClose={close} busy><input aria-label="Value" /></Modal>)
  fireEvent(screen.getByRole('dialog'), new Event('cancel', { cancelable: true }))
  const button = screen.getByRole('button', { name: 'Close dialog' }) as HTMLButtonElement
  expect(button.disabled).toBe(true)
  fireEvent.click(button)
  expect(close).not.toHaveBeenCalled()
})
