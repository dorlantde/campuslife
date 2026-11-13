export function pdfFilename(disposition: string | null): string {
  let name = 'campuslife-document.pdf'
  const utf8 = disposition?.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  const plain = disposition?.match(/filename="([^"]*)"|filename=([^;]+)/i)
  try { name = utf8 ? decodeURIComponent(utf8.trim()) : (plain?.[1] ?? plain?.[2]?.trim() ?? name) } catch { /* Keep safe fallback. */ }
  name = name.replace(/[\u0000-\u001f\u007f/\\:*?"<>|]/g, '_').replace(/^\.+/, '').trim().slice(0, 180)
  if (!name) name = 'campuslife-document'
  return /\.pdf$/i.test(name) ? name : `${name}.pdf`
}
export function savePdf(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url; anchor.download = filename; anchor.rel = 'noopener'; anchor.style.display = 'none'
  document.body.append(anchor)
  try { anchor.click() } finally { anchor.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000) }
}
