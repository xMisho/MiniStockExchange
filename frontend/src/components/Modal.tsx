import { useEffect, useId, useRef } from 'react'
import type { ReactNode } from 'react'

interface Props { title: string; description?: string; busy?: boolean; onClose: () => void; children: ReactNode }
export function Modal({ title, description, busy = false, onClose, children }: Props) {
  const dialog = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  const descriptionId = useId()
  useEffect(() => {
    const element = dialog.current
    if (!element) return
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    if (typeof element.showModal === 'function') element.showModal()
    else element.setAttribute('open', '')
    const firstControl = element.querySelector<HTMLElement>('input, select, textarea') ?? element.querySelector<HTMLElement>('button')
    firstControl?.focus()
    return () => {
      if (typeof element.close === 'function') element.close()
      document.body.style.overflow = overflow
      if (opener?.isConnected) opener.focus()
    }
  }, [])
  return <dialog ref={dialog} className="modal" aria-labelledby={titleId} aria-describedby={description ? descriptionId : undefined}
    onCancel={(event) => { event.preventDefault(); if (!busy) onClose() }}>
    <div className="modal-heading"><div><p className="eyebrow">VERDEX</p><h2 id={titleId}>{title}</h2></div>
      <button type="button" className="text-button" disabled={busy} onClick={onClose} aria-label="Close dialog">Close</button></div>
    {description && <p className="modal-description" id={descriptionId}>{description}</p>}
    {children}
  </dialog>
}
