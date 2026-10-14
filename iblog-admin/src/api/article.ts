import { del, get, post, put } from './request'

export interface ArticleQuery {
  pageNum?: number
  pageSize?: number
  keyword?: string
  categoryId?: number | null
  status?: number | null
}

export interface ArticleItem {
  id: number
  title: string
  slug: string
  summary: string
  content?: string
  cover: string
  categoryId: number | null
  categoryName?: string
  tagIds?: number[]
  tagNames?: string[]
  author: string
  status: number
  isTop: number
  viewCount: number
  commentCount: number
  publishTime: string | null
  createTime?: string
}

export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
}

export function pageArticles(params: ArticleQuery) {
  return get<PageResult<ArticleItem>>('/admin/articles', params)
}

export function getArticle(id: number) {
  return get<ArticleItem>(`/admin/articles/${id}`)
}

export function createArticle(data: Partial<ArticleItem>) {
  return post<number>('/admin/articles', data)
}

export function updateArticle(data: Partial<ArticleItem>) {
  return put<void>('/admin/articles', data)
}

export function changeArticleStatus(id: number, status: number) {
  return put<void>(`/admin/articles/${id}/status?status=${status}`, null)
}

export function deleteArticle(id: number) {
  return del<void>(`/admin/articles/${id}`)
}

export function getDashboard() {
  return get<any>('/admin/dashboard')
}
