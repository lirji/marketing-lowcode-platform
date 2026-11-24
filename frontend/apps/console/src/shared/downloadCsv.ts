type CsvCell = string | number | bigint

/** 外部字段按文本输出，防止表格软件将其解释为公式。 */
function csvCell(value: CsvCell) {
  let text = String(value)
  const firstVisible = [...text].find(character => character.charCodeAt(0) > 0x1f && character.trim())
  if (typeof value === 'string' && firstVisible && '=+-@'.includes(firstVisible)) text = "'" + text
  return '"' + text.replaceAll('"', '""') + '"'
}

export function formatCsv(rows: readonly (readonly CsvCell[])[]) {
  return '\uFEFF' + rows.map(row => row.map(csvCell).join(',')).join('\r\n') + '\r\n'
}

export function downloadCsv(content: string, filename: string) {
  const url = URL.createObjectURL(new Blob([content], { type: 'text/csv;charset=utf-8' }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.append(anchor)
  anchor.click()
  anchor.remove()
  // 下载消费者可能异步读取 Blob，稍后释放。
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}
