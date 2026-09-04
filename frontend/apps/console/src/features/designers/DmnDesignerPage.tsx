import { useState } from 'react'
import { FlaskConical, Plus, ShieldCheck } from 'lucide-react'
import { Badge, Button, DemoBanner, PageHeader, Panel, PanelHeader, StateBanner } from '../../components/ui'
import { api } from '../../shared/api/client'

const initialRows = [
  ['1', '< 100', '-', 'NONE', '0', '未达门槛'],
  ['2', '[100..500)', 'PLUS', 'PERCENT', '8', '会员 92 折'],
  ['3', '[100..500)', '-', 'FIXED', '30', '满 100 减 30'],
  ['4', '>= 500', 'PLUS', 'FIXED', '80', '满 500 减 80'],
]

export function DmnDesignerPage() {
  const [hitPolicy, setHitPolicy] = useState('PRIORITY')
  const [rows, setRows] = useState(initialRows)
  const [analysis, setAnalysis] = useState(true)
  return <div className="workspace dmn-page"><PageHeader eyebrow="DMN_DECISION_TABLE · Draft v4" title="家电会员优惠矩阵" description="使用标准命中策略、类型约束和 gap / overlap 静态分析，不允许任意 Java 或外部 I/O。" actions={<><Button onClick={() => setAnalysis(true)}><ShieldCheck size={15} />分析表格</Button><Button tone="primary" disabled title="DMN 求值服务尚未接入"><FlaskConical size={15} />运行测试行</Button></>} /><DemoBanner />{!api.demoMode && <StateBanner tone="info" title="本地草稿" detail="DMN 表没有独立读写接口，分析结果仅在浏览器内有效。" />}
    <Panel><div className="dmn-toolbar"><label>Hit policy<select value={hitPolicy} onChange={(event) => setHitPolicy(event.target.value)}><option>PRIORITY</option><option>FIRST</option><option>COLLECT</option><option>UNIQUE</option></select></label><span>Input 2</span><span>Output 3</span><Badge tone="info">FEEL allowlist</Badge></div><div className="dmn-scroll"><table className="dmn-table"><thead><tr><th>#</th><th className="input-column"><small>INPUT · DECIMAL</small>购物车金额（元）</th><th className="input-column"><small>INPUT · ENUM</small>会员等级</th><th className="output-column"><small>OUTPUT · ENUM</small>优惠类型</th><th className="output-column"><small>OUTPUT · DECIMAL</small>优惠值</th><th className="output-column"><small>OUTPUT · STRING</small>解释</th></tr></thead><tbody>{rows.map((row, rowIndex) => <tr key={row[0]}>{row.map((cell, columnIndex) => <td key={columnIndex}>{columnIndex === 0 ? cell : <input value={cell} aria-label={`第 ${rowIndex + 1} 行第 ${columnIndex} 列`} onChange={(event) => setRows((items) => items.map((item, index) => index === rowIndex ? item.map((value, cellIndex) => cellIndex === columnIndex ? event.target.value : value) : item))} />}</td>)}</tr>)}</tbody></table></div><Button tone="ghost" onClick={() => setRows((items) => [...items, [String(items.length + 1), '', '', '', '', '']])}><Plus size={14} />新增规则行</Button></Panel>
    <div className="dmn-bottom"><Panel><PanelHeader eyebrow="STATIC ANALYSIS" title="Gap / Overlap" />{analysis && <><StateBanner tone="warn" title="发现 1 处重叠" detail="规则 2 与规则 3 在金额 [100..500) 且会员等级 PLUS 时同时命中；PRIORITY 将选规则 2。" /><StateBanner tone="success" title="无覆盖缺口" detail="当前输入域已覆盖金额 [0..∞)；null 按 NO_MATCH 处理。" /></>}</Panel><Panel><PanelHeader eyebrow="TEST CASE" title="即时求值" /><div className="test-row"><label>金额<input defaultValue="688" /></label><label>会员<select defaultValue="PLUS"><option>PLUS</option><option>NORMAL</option></select></label><div><span>结果</span><strong>满 500 减 80</strong></div></div></Panel></div>
  </div>
}
