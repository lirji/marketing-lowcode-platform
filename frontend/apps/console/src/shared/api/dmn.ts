import { z } from 'zod'

export const DMN_TYPES = ['number', 'string', 'boolean'] as const
export type DmnType = typeof DMN_TYPES[number]
export type DmnColumn = { name: string; type: DmnType }
export type DmnTable = {
  hitPolicy: 'UNIQUE' | 'FIRST'
  inputs: DmnColumn[]
  outputs: DmnColumn[]
  rules: { inputs: string[]; outputs: string[] }[]
}
export const dmnAnalysisSchema = z.object({
  valid: z.boolean(), gaps: z.array(z.string()), overlaps: z.array(z.string()), messages: z.array(z.string()),
})
export const dmnEvaluationSchema = z.object({
  success: z.boolean(), results: z.record(z.string(), z.unknown()), messages: z.array(z.string()),
})
export type DmnAnalysis = z.infer<typeof dmnAnalysisSchema>
export type DmnEvaluation = z.infer<typeof dmnEvaluationSchema>

// 草稿允许未完成的空名称/单元格，结构仍与真实计算请求一致。
const draftColumnSchema = z.object({ name: z.string().max(32), type: z.enum(DMN_TYPES) })
const draftCellsSchema = z.array(z.string().max(256)).min(1).max(4)
export const dmnDraftSchema = z.object({
  campaignId: z.string(), version: z.number().int().positive().safe(), contentHash: z.string(),
  createdBy: z.string(), createdAt: z.string(),
  table: z.object({ hitPolicy: z.enum(['UNIQUE', 'FIRST']), inputs: z.array(draftColumnSchema).min(1).max(4),
    outputs: z.array(draftColumnSchema).min(1).max(4), rules: z.array(z.object({ inputs: draftCellsSchema, outputs: draftCellsSchema })).min(1).max(32),
  }).refine((table) => table.rules.every((row) => row.inputs.length === table.inputs.length && row.outputs.length === table.outputs.length)),
})
export type DmnDraft = z.infer<typeof dmnDraftSchema>

export const newDmnTable = (): DmnTable => ({ hitPolicy: 'UNIQUE', inputs: [{ name: '', type: 'number' }], outputs: [{ name: '', type: 'string' }], rules: [{ inputs: [''], outputs: [''] }] })
