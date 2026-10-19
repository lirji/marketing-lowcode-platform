import { Info } from 'lucide-react'
import type { ReactNode } from 'react'

/** 必要填写说明常驻显示，同时通过aria-describedby关联输入控件。 */
export function FieldHint({ id, children }: { id: string; children: ReactNode }) {
  return <small className="field-hint" id={id}><Info size={13} aria-hidden="true" /><span>{children}</span></small>
}
