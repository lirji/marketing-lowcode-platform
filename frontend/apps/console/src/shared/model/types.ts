export type Campaign = {
  id: string
  name: string
  objective: string
  status: 'DRAFT' | 'IN_REVIEW' | 'APPROVED' | 'ACTIVE' | 'PAUSED' | 'ENDED'
  organizationId?: string
  shopId?: string
  createdBy?: string
  createdAt: string
  updatedAt?: string
}

export type NodeDefinition = {
  stableTypeId: string
  displayName: string
  dialect: string
  inputPorts: string[]
  outputPorts: string[]
  configSchema: Record<string, unknown>
}

export type Problem = { code: string; message: string; pointer: string; severity: 'ERROR' | 'WARNING' }
