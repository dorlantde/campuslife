package com.campuslife.merchant.service;

import com.campuslife.common.exception.BusinessException;
import com.campuslife.merchant.mapper.*;
import com.campuslife.merchant.vo.BookingSlotsView;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("mysql")
public class BookingSlotsService {
    private final PublicShopMapper shops;
    private final ServiceItemMapper services;
    private final BarberMapper barbers;
    private final BarberServiceMapper bindings;
    private final BusinessHourMapper hours;
    private final BarberScheduleMapper schedules;
    private final Clock clock;
    private final com.campuslife.order.mapper.BookingMapper bookings;

    public BookingSlotsService(PublicShopMapper shops, ServiceItemMapper services, BarberMapper barbers,
                                BarberServiceMapper bindings, BusinessHourMapper hours, BarberScheduleMapper schedules, Clock clock, com.campuslife.order.mapper.BookingMapper bookings) {
        this.shops = shops; this.services = services; this.barbers = barbers; this.bindings = bindings;
        this.hours = hours; this.schedules = schedules; this.clock = clock;
        this.bookings = bookings;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BookingSlotsView query(String shopId, String serviceId, String barberId, String dateText) {
        long shopKey = MerchantServicePolicy.id(shopId, "shopId");
        long serviceKey = MerchantServicePolicy.id(serviceId, "serviceId");
        long barberKey = MerchantServicePolicy.id(barberId, "barberId");
        var now = clock.instant();
        var date = BookingSlotPolicy.date(dateText, now);
        var shop = shops.visible(shopKey);
        if (shop == null || !"HAIRCUT".equals(shop.categoryCode())) { throw missing(); }
        var service = services.findOwn(serviceKey, shopKey);
        if (service == null || !Boolean.TRUE.equals(service.getEnabled()) || !"HAIRCUT".equals(service.getServiceType())) { throw missing(); }
        var barber = barbers.findOwn(barberKey, shopKey);
        if (barber == null || !Boolean.TRUE.equals(barber.getEnabled()) || !bindings.bound(barberKey, serviceKey)) { throw missing(); }
        // Qualification failures remain 404 even when the business switch is off.
        if (!shop.businessEnabled()) {
            return new BookingSlotsView(Long.toString(service.getVersion()), service.getPriceAmount(), service.getDurationMinutes(), List.of());
        }
        var candidates = BookingSlotPolicy.candidates(date, now, service.getDurationMinutes(), hours.views(shopKey), schedules.rows(barberKey, date));
        var occupied = bookings.occupied(barberKey, date);
        var available = candidates.stream().filter(slot -> occupied.stream().noneMatch(row ->
                row.getStartAt().toInstant(java.time.ZoneOffset.UTC).isBefore(slot.endAt())
                && row.getEndAt().toInstant(java.time.ZoneOffset.UTC).isAfter(slot.startAt()))).toList();
        return new BookingSlotsView(Long.toString(service.getVersion()), service.getPriceAmount(), service.getDurationMinutes(), available);
    }

    private static BusinessException missing() {
        return new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "公开理发店铺、服务、人员或绑定不存在");
    }
}
