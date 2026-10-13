export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  page: number
  pages: number
}

export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

export interface Book {
  id: number
  isbn: string
  title: string
  author: string
  publisher: string
  publishDate: string
  categoryId: number
  categoryName: string
  price: number
  pages: number
  coverImage: string
  description: string
  location: string
  stockTotal: number
  stockAvailable: number
  status: number
  createTime: string
  updateTime: string
}

export interface BookCategory {
  id: number
  name: string
  parentId: number
  level: number
  sortOrder: number
  status: number
  children: BookCategory[]
}

export interface BorrowRecord {
  id: number
  userId: number
  userName: string
  bookId: number
  bookTitle: string
  borrowDate: string
  dueDate: string
  returnDate: string
  renewCount: number
  status: 'BORROWING' | 'RETURNED' | 'OVERDUE'
  fineAmount: number
  remark: string
}

export interface User {
  id: number
  username: string
  realName: string
  role: string
  phone: string
  email: string
  avatar: string
  status: number
  createTime: string
  updateTime: string
}

export interface InventoryItem {
  bookId: number
  bookTitle: string
  isbn: string
  stockTotal: number
  stockAvailable: number
  borrowRate: number
}

export interface PurchaseOrder {
  items: PurchaseItem[]
  id: number
  orderNo: string
  deptId: number
  templateId: number
  applicantId: number
  status: string
  currentNodeId: string
  deadline: string
  remark: string
  createTime: string
  updateTime: string
  deptName: string
  applicantName: string
  templateName: string
}

export interface PurchaseItem {
  id: number
  orderId: number
  bookTitle: string
  author: string
  isbn: string
  publisher: string
  categoryId: number
  quantity: number
  instockQuantity: number
  price: number
  remark: string
  createTime: string
  updateTime: string
  categoryName: string
}

export interface ApprovalRecord {
  id: number
  orderId: number
  nodeId: string
  nodeName: string
  approverId: number
  approverName: string
  action: string
  comment: string
  createTime: string
}

export interface BorrowCode {
  id: number
  code: string
  userId: number
  bookId: number
  status: string
  expireTime: string
  usedTime: string
  createTime: string
  bookTitle: string
  userName: string
}

export interface Bookshelf {
  id: number
  name: string
  code: string
  deptId: number
  location: string
  capacity: number
  status: number
  createTime: string
  updateTime: string
  deptName: string
}

export interface BookshelfStorey {
  id: number
  bookshelfId: number
  name: string
  levelNum: number
  capacity: number
  status: number
  createTime: string
  updateTime: string
}

export interface BookLocation {
  id: number
  storeyId: number
  code: string
  bookId: number
  status: string
  createTime: string
  updateTime: string
  bookTitle: string
  coverImage: string
}

export interface Notice {
  id: number
  title: string
  content: string
  type: string
  status: string
  publisherId: number
  publishTime: string
  createTime: string
  updateTime: string
  publisherName: string
}

export interface Publisher {
  id: number
  name: string
  address: string
  contactPhone: string
  contactEmail: string
  website: string
  description: string
  status: number
  createTime: string
  updateTime: string
}

export interface PermissionLog {
  id: number
  operatorId: number
  operatorName: string
  targetUserId: number
  targetUserName: string
  action: string
  oldRole: string
  newRole: string
  detail: string
  createTime: string
}

export interface BookNormalizeLog {
  id: number
  bookId: number
  oldTitle: string
  newTitle: string
  oldAuthor: string
  newAuthor: string
  oldIsbn: string
  newIsbn: string
  oldPublisher: string
  newPublisher: string
  operatorName: string
  status: string
  createTime: string
}

export interface PermissionMatrixRow {
  key: string
  name: string
  ADMIN: boolean
  LIBRARIAN: boolean
  READER: boolean
}

export interface NormalizeCandidate {
  id: number
  title: string
  author: string
  isbn: string
  publisher: string
  suggestedTitle: string
  suggestedAuthor: string
  issues: string[]
}
