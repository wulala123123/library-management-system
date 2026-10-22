import { get, post, put } from './request'

export interface ProfileStats {
  totalBorrows: number
  activeBorrows: number
  overdueBorrows: number
}

export function updateProfile(data: Record<string, string>) {
  return put<void>('/profile', data)
}

export function changePassword(data: { oldPassword: string; newPassword: string }) {
  return put<void>('/profile/password', data)
}

export function getProfileStats() {
  return get<ProfileStats>('/profile/stats')
}

export function uploadAvatar(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return post<string>('/profile/avatar', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 30000,
  })
}
