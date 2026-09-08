import { useState } from 'react'
import { FlaskConical, Plus, ShieldCheck } from 'lucide-react'
import { Badge, Button, DemoBanner, EmptyState, PageHeader, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { api } from '../../shared/api/client'

const EMPTY_ROW = ['1', '', '', '', '', '']

export function DmnDesignerPage() {
  const [hitPolicy, setHitPolicy] = useState('PRIORITY')
  const [rows, setRows] = useState<string[][]>([EMPTY_ROW.map((cell) => cell)])
  const [analysis, setAnalysis] = useState<'idle' | 'empty' | 'unavailable'>('idle')
  const filled = rows.filter((row) => row.slice(1).some((cell) => cell.trim())).length
  const runAnalysis = () => setAnalysis(filled === 0 ? 'empty' : 'unavailable')

  return (
    <div className="workspace dmn-page">
      <PageHeader
        eyebrow="DMN_DECISION_TABLE"
        title="DMN 草稿"
        description="决策表目前没有独立读写接口。这里只是本地草稿，不会预填家电优惠矩阵，也不会假装已经求值。"
        actions={(
          <>
            <Button onClick={runAnalysis}><ShieldCheck size={15} />分析表格</Button>
            <Button tone="primary" disabled title="DMN 求值服务尚未接入"><FlaskConical size={15} />运行测试行</Button>
          </>
        )}
      />
      <DemoBanner />
      {!api.demoMode && <StateBanner tone="info" title="本地草稿" detail="保存、分析和求值都还没有控制面接口。请从空白表开始编辑，不要把示例结果当成运行时输出。" />}
      <Panel>
        <div className="dmn-toolbar">
          <label>Hit policy
            <select value={hitPolicy} onChange={(event) => setHitPolicy(event.target.value)}>
              <option>PRIORITY</option>
              <option>FIRST</option>
              <option>COLLECT</option>
              <option>UNIQUE</option>
            </select>
          </label>
          <span>Input 2</span>
          <span>Output 3</span>
          <Badge tone="info">FEEL allowlist</Badge>
        </div>
        <div className="dmn-scroll">
          <table className="dmn-table">
            <thead>
              <tr>
                <th>#</th>
                <th className="input-column"><small>INPUT · DECIMAL</small>购物车金额（元）</th>
                <th className="input-column"><small>INPUT · ENUM</small>会员等级</th>
                <th className="output-column"><small>OUTPUT · ENUM</small>优惠类型</th>
                <th className="output-column"><small>OUTPUT · DECIMAL</small>优惠值</th>
                <th className="output-column"><small>OUTPUT · STRING</small>解释</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row, rowIndex) => (
                <tr key={`${row[0]}-${rowIndex}`}>
                  {row.map((cell, columnIndex) => (
                    <td key={columnIndex}>
                      {columnIndex === 0
                        ? cell
                        : <input value={cell} aria-label={`第 ${rowIndex + 1} 行第 ${columnIndex} 列`} onChange={(event) => setRows((items) => items.map((item, index) => index === rowIndex ? item.map((value, cellIndex) => cellIndex === columnIndex ? event.target.value : value) : item))} />}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <Button tone="ghost" onClick={() => setRows((items) => [...items, [String(items.length + 1), '', '', '', '', '']])}>
          <Plus size={14} />新增规则行
        </Button>
      </Panel>
      <div className="dmn-bottom">
        <Panel>
          <PanelHeader eyebrow="STATIC ANALYSIS" title="Gap / Overlap" />
          {analysis === 'idle' && <EmptyState title="尚未分析" detail="填写规则后点击「分析表格」。没有控制面分析服务时，不会生成虚构重叠结论。" />}
          {analysis === 'empty' && <StateBanner tone="info" title="没有可分析的规则" detail="当前表是空的，无法判断 gap / overlap。" />}
          {analysis === 'unavailable' && <StateBanner tone="warn" title="分析服务尚未接入" detail="本地草稿不会调用编译或 DMN 校验接口，因此不会给出覆盖结论。" />}
        </Panel>
        <Panel>
          <PanelHeader eyebrow="TEST CASE" title="即时求值" />
          <div className="test-row">
            <label>金额<input aria-label="测试金额" /></label>
            <label>会员<select aria-label="测试会员"><option value="">未选择</option><option>PLUS</option><option>NORMAL</option></select></label>
            <div><span>结果</span><strong>求值服务尚未接入</strong></div>
          </div>
        </Panel>
      </div>
    </div>
  )
}
