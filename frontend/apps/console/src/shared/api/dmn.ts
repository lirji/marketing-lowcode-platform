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
