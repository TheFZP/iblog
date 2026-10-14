import { marked } from 'marked'

marked.setOptions({
  gfm: true,
  breaks: true
})

/**
 * 把 Markdown 正文渲染为 HTML。
 */
export function renderMarkdown(content?: string | null): string {
  if (!content) {
    return ''
  }
  return marked.parse(content, { async: false }) as string
}
