import { createContext, useCallback, useContext, useEffect, useId, useRef, useState, type ReactNode } from 'react';

export interface ConfirmData {
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  danger?: boolean;
}

type ConfirmFn = (data: ConfirmData) => Promise<boolean>;

const ConfirmContext = createContext<ConfirmFn | null>(null);

/** Returns a function that opens a confirm dialog and resolves true only when the user confirms. */
export function useConfirm(): ConfirmFn {
  const fn = useContext(ConfirmContext);
  if (!fn) {
    throw new Error('useConfirm must be used inside <ConfirmProvider>');
  }
  return fn;
}

interface Pending {
  data: ConfirmData;
  resolve: (ok: boolean) => void;
}

export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [pending, setPending] = useState<Pending | null>(null);
  const confirm = useCallback<ConfirmFn>((data) => new Promise<boolean>((resolve) => setPending({ data, resolve })), []);
  const close = (ok: boolean): void => {
    pending?.resolve(ok);
    setPending(null);
  };
  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      {pending ? <Dialog data={pending.data} onClose={close} /> : null}
    </ConfirmContext.Provider>
  );
}

function Dialog({ data, onClose }: { data: ConfirmData; onClose: (ok: boolean) => void }) {
  const titleId = useId();
  const bodyId = useId();
  const cancelRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    cancelRef.current?.focus(); // safe default: Cancel has initial focus
    return () => previous?.focus?.(); // restore focus to the trigger
  }, []);

  return (
    <div className="backdrop" onKeyDown={(e) => { if (e.key === 'Escape') onClose(false); }}>
      <div className="dialog" role="alertdialog" aria-modal="true" aria-labelledby={titleId} aria-describedby={bodyId}>
        <h2 id={titleId}>{data.title}</h2>
        <p id={bodyId}>{data.message}</p>
        <div className="toolbar-row end">
          <button type="button" className="btn" ref={cancelRef} onClick={() => onClose(false)}>{data.cancelLabel ?? 'Cancel'}</button>
          <button type="button" className={`btn ${data.danger ? 'danger' : 'primary'}`} onClick={() => onClose(true)}>
            {data.confirmLabel ?? 'Confirm'}
          </button>
        </div>
      </div>
    </div>
  );
}
