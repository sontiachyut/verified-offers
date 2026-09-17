import { useEffect, useRef, type ReactNode } from 'react';
import { AlertCircle, X } from 'lucide-react';
import { timestamp } from './api';

export function ErrorNotice({ children }: { children: ReactNode }) { return <div role="alert" className="notice error"><AlertCircle size={17} aria-hidden="true" /><div>{children}</div></div>; }
export function Status({ value }: { value: string }) { return <span className={`status ${value.toLowerCase()}`}>{value.replaceAll('_', ' ')}</span>; }
export function Time({ value }: { value: string | null }) { return value ? <time dateTime={value}>{timestamp(value)}</time> : <>—</>; }
export function Modal({ title, children, onClose, busy = false }: { title: string; children: ReactNode; onClose: () => void; busy?: boolean }) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => { const previous = document.activeElement as HTMLElement | null; ref.current?.showModal(); return () => { ref.current?.close(); previous?.focus(); }; }, []);
  return <dialog ref={ref} aria-labelledby="dialog-title" onCancel={event => { event.preventDefault(); if (!busy) onClose(); }}><div className="dialog-heading"><h2 id="dialog-title">{title}</h2><button aria-label="Close dialog" disabled={busy} onClick={onClose}><X size={18} /></button></div>{children}</dialog>;
}
