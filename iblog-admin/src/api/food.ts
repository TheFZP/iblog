import { del, get, post, put } from './request'

/** 美食探店记录 */
export interface FoodItem {
  id: number
  name: string
  cuisine: string | null
  dishes: string | null
  address: string | null
  longitude: number | null
  latitude: number | null
  poiId: string | null
  avgPrice: number | null
  rating: number | null
  cover: string | null
  remark: string | null
  visitedAt: string | null
  sort: number
  status: number
}

export function listFoods(params?: { keyword?: string; cuisine?: string }) {
  return get<FoodItem[]>('/admin/foods', params)
}

export function createFood(data: Partial<FoodItem>) {
  return post<number>('/admin/foods', data)
}

export function updateFood(data: Partial<FoodItem>) {
  return put<void>('/admin/foods', data)
}

export function deleteFood(id: number) {
  return del<void>(`/admin/foods/${id}`)
}
