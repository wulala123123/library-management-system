import type { BookCategory } from '@/types'
import { get, post, put, del } from './request'

export interface CategoryFormData {
  id?: number
  name: string
  parentId?: number
  sortOrder?: number
  status?: number
}

export function getCategoryList() {
  return get<BookCategory[]>('/categories')
}

export function getCategoryById(id: number) {
  return get<BookCategory>(`/categories/${id}`)
}

export function createCategory(data: CategoryFormData) {
  return post<BookCategory>('/categories', data)
}

export function updateCategory(data: CategoryFormData) {
  return put<BookCategory>('/categories', data)
}

export function deleteCategory(id: number) {
  return del<void>(`/categories/${id}`)
}
