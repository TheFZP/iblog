import { del, get, post, put } from './request'

export interface CategoryItem {
  id: number
  name: string
  slug: string
  description: string
  sort: number
  articleCount?: number
}

export interface TagItem {
  id: number
  name: string
  slug: string
  sort: number
  articleCount?: number
}

export interface CommentItem {
  id: number
  articleId: number
  articleTitle?: string
  nickname: string
  email: string
  content: string
  status: number
  ip: string
  createTime: string
}

export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
}

// ---------------- 分类 ----------------

export function listCategories() {
  return get<CategoryItem[]>('/admin/categories')
}

export function createCategory(data: Partial<CategoryItem>) {
  return post<number>('/admin/categories', data)
}

export function updateCategory(data: Partial<CategoryItem>) {
  return put<void>('/admin/categories', data)
}

export function deleteCategory(id: number) {
  return del<void>(`/admin/categories/${id}`)
}

// ---------------- 标签 ----------------

export function listTags() {
  return get<TagItem[]>('/admin/tags')
}

export function createTag(data: Partial<TagItem>) {
  return post<number>('/admin/tags', data)
}

export function updateTag(data: Partial<TagItem>) {
  return put<void>('/admin/tags', data)
}

export function deleteTag(id: number) {
  return del<void>(`/admin/tags/${id}`)
}

// ---------------- 评论 ----------------

export function pageComments(params: { pageNum?: number; pageSize?: number; status?: number | null }) {
  return get<PageResult<CommentItem>>('/admin/comments', params)
}

export function approveComment(id: number) {
  return put<void>(`/admin/comments/${id}/approve`, null)
}

export function rejectComment(id: number) {
  return put<void>(`/admin/comments/${id}/reject`, null)
}

export function deleteComment(id: number) {
  return del<void>(`/admin/comments/${id}`)
}

// ---------------- 站点配置 ----------------

export function getSiteConfig() {
  return get<Record<string, string>>('/admin/site/config')
}

export function saveSiteConfig(data: Record<string, string>) {
  return put<void>('/admin/site/config', data)
}
