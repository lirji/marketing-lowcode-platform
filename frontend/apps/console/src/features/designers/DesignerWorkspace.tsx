import type { ComponentProps, ReactNode } from 'react'
import { DemoBanner, PageHeader } from '../../components/ui'

type Props = ComponentProps<typeof PageHeader> & { notices?: ReactNode; children?: ReactNode }

export function DesignerWorkspace({ notices, children, ...heading }: Props) {
  return <div className="workspace designer-workspace">
    <PageHeader {...heading} />
    <div className="designer-notices">
      <DemoBanner />
      {notices}
    </div>
    {children}
  </div>
}
