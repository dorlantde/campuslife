export type Role = 'STUDENT' | 'MERCHANT' | 'ADMIN'
export interface Account { id: string; username: string; nickname: string; role: Role; status: 'ACTIVE' | 'DISABLED'; version: string }
export interface Session { accessToken: string; expiresAt: string; account: Account }
export interface LoginView extends Session { tokenType: string }
export interface Envelope<T> { code: string; message: string; data: T; traceId: string }
export interface Category { id: string; code: string; name: string }
export interface ShopSummary { id: string; name: string; category: Category; locationText: string; businessEnabled: boolean; openNow: boolean; rating: number | null; reviewCount: number }
export interface BusinessHour { weekday: number; startTime: string; endTime: string }
export interface ShopDetail extends ShopSummary { description: string; businessHours: BusinessHour[]; campusCode: string }
export interface ShopService { id: string; serviceType: 'HAIRCUT' | 'PRINT'; name: string; description: string; priceAmount: number; durationMinutes: number | null; version: string }
export interface Review { reviewId: string; rating: number; content: string; reviewerNickname: string; createdAt: string }
export interface Page<T> { items: T[]; page: number; size: number; total: number; totalPages: number }
export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'SUSPENDED'
export interface MerchantShop { id: string; name: string; category: Category; locationText: string; description: string; campusCode: string; approvalStatus: ApprovalStatus; approvalReason: string | null; businessEnabled: boolean; memberStatus: 'PENDING' | 'ACTIVE' | 'DISABLED'; version: string; businessHours: BusinessHour[] }
export interface MerchantApplication { accountId: string; shopId: string; approvalStatus: 'PENDING' }
export interface MerchantService extends ShopService { enabled: boolean }
export interface PrintPrice { id: string; paperSize: 'A4'; colorMode: 'BW' | 'COLOR'; sideMode: 'SINGLE' | 'DOUBLE'; pricePerImpression: number; enabled: boolean; version: string }
export interface Barber { id: string; displayName: string; enabled: boolean; serviceIds: string[]; version: string }
export interface TimeInterval { startTime: string; endTime: string }
export interface Schedule { date: string; intervals: TimeInterval[]; barberVersion: string }
export interface FileAsset { id: string; originalName: string; byteSize: number; mediaType: 'application/pdf'; pageCount: number; status: 'AVAILABLE'; expiresAt: string | null }
export interface PrintSpec { serviceId: string; fileId: string; paperSize: 'A4'; colorMode: 'BW' | 'COLOR'; sideMode: 'SINGLE' | 'DOUBLE'; copies: number }
export interface PrintQuote extends PrintSpec { shopId: string; pageCount: number; sheetCount: number; priceRuleId: string; priceVersion: string; unitPriceAmount: number; originalAmount: number; discountAmount: number; payableAmount: number; currency: 'CNY' }
export interface PrintCreate extends PrintSpec { expectedPriceVersion: string; expectedPayableAmount: number; note: string }
export type OrderStatus = 'PENDING_PAYMENT' | 'PENDING_ACCEPTANCE' | 'ACCEPTED' | 'IN_SERVICE' | 'COMPLETED' | 'CLOSED'
export interface OrderSummary { id: string; orderNo: string; orderType: 'BOOKING' | 'PRINT'; status: OrderStatus; paymentStatus: 'NOT_PAID' | 'PAID' | 'REFUNDED'; shopId: string; shopName: string; serviceName: string; payableAmount: number; currency: string; createdAt: string; bookingStartAt: string | null; bookingEndAt: string | null; printingProgress: string | null }
export interface PrintingDetail { fileId: string; fileName: string; pageCount: number; copies: number; paperSize: 'A4'; colorMode: 'BW' | 'COLOR'; sideMode: 'SINGLE' | 'DOUBLE'; sheetCount: number; unitPriceAmount: number; progress: 'QUEUED' | 'PRINTING' | 'READY_FOR_PICKUP' | 'PICKED_UP' | 'ABORTED'; readyAt: string | null; pickedUpAt: string | null }
export interface OrderDetail extends Omit<OrderSummary, 'printingProgress' | 'bookingStartAt' | 'bookingEndAt'> { originalAmount: number; discountAmount: number; paymentDeadline: string; acceptanceDeadline: string | null; paidAt: string | null; acceptedAt: string | null; startedAt: string | null; completedAt: string | null; closedAt: string | null; closeReason: string | null; closeNote: string | null; note: string | null; version: string; reviewed: boolean; booking: BookingDetail | null; printing: PrintingDetail | null; payment: { paymentNo: string; amount: number; status: string; paidAt: string } | null; refund: { refundNo: string; amount: number; status: string; refundedAt: string } | null }
export interface OrderEvent { id: string; eventSeq: string; action: string; fromStatus: OrderStatus | null; toStatus: OrderStatus; actorType: string; actorId: string | null; reason: string | null; occurredAt: string }
export interface OwnReview { id: string; orderId: string; rating: number; content: string; visibility: 'VISIBLE' | 'HIDDEN'; createdAt: string }
export interface PublicBarber { id: string; displayName: string; serviceIds: string[] }
export interface BookingSlot { startAt: string; endAt: string }
export interface BookingSlots { serviceVersion: string; priceAmount: number; durationMinutes: number; slots: BookingSlot[] }
export interface BookingCreate { serviceId: string; barberId: string; startAt: string; expectedServiceVersion: string; expectedPayableAmount: number; note: string }
export interface BookingDetail { serviceId: string; serviceName: string; barberId: string; barberName: string; startAt: string; endAt: string; durationMinutes: number; occupancyStatus: 'HELD' | 'ACTIVE' | 'RELEASED' | 'CONSUMED' }
