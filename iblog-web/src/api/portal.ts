import { get, post } from './request'

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
  prevId?: number
  prevTitle?: string
  nextId?: number
  nextTitle?: string
}

export interface CategoryItem {
  id: number
  name: string
  slug: string
  description: string
  articleCount?: number
}

export interface TagItem {
  id: number
  name: string
  slug: string
  articleCount?: number
}

export interface CommentItem {
  id: number
  nickname: string
  content: string
  createTime: string
}

export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
}

export interface ArticleQuery {
  pageNum?: number
  pageSize?: number
  keyword?: string
  categoryId?: number | null
  tagId?: number | null
}

export function pageArticles(params: ArticleQuery) {
  return get<PageResult<ArticleItem>>('/portal/articles', params)
}

export function getArticle(id: number) {
  return get<ArticleItem>(`/portal/articles/${id}`)
}

export function listComments(articleId: number) {
  return get<CommentItem[]>(`/portal/articles/${articleId}/comments`)
}

export function submitComment(data: { articleId: number; nickname: string; email: string; content: string }) {
  return post<void>('/portal/comments', data)
}

export function getSiteConfig() {
  return get<Record<string, string>>('/portal/site')
}

export function listCategories() {
  return get<CategoryItem[]>('/portal/categories')
}

export function listTags() {
  return get<TagItem[]>('/portal/tags')
}

export function getArchive() {
  return get<Record<string, ArticleItem[]>>('/portal/archive')
}

export function getHotArticles() {
  return get<ArticleItem[]>('/portal/hot-articles')
}
