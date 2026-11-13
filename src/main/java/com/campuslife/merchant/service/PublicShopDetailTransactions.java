package com.campuslife.merchant.service;

import com.campuslife.common.exception.BusinessException;
import com.campuslife.merchant.cache.*;
import com.campuslife.merchant.mapper.*;
import com.campuslife.merchant.mapper.model.PublicShopRow;
import com.campuslife.merchant.vo.*;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Profile("mysql")
public class PublicShopDetailTransactions {
    private final PublicShopMapper shops;
    private final BusinessHourMapper hours;
    private final PublicReviewMapper reviews;
    private final Clock clock;
    private final RatingCache cache;
    public PublicShopDetailTransactions(PublicShopMapper shops, BusinessHourMapper hours, PublicReviewMapper reviews,
                                        Clock clock, RatingCache cache) {
        this.shops = shops; this.hours = hours; this.reviews = reviews; this.clock = clock; this.cache = cache;
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ShopDetail read(String id, RatingCache.Load load) {
        long parsed = MerchantServicePolicy.id(id, "shopId");
        PublicShopRow row; long revision = 0;
        if (cache.enabled()) {
            var versioned = shops.visibleWithRatingRevision(parsed);
            row = versioned == null ? null : versioned.shop();
            if (versioned != null) { revision = versioned.ratingRevision(); }
        } else { row = shops.visible(parsed); }
        if (row == null) { throw new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "公开店铺不存在"); }
        var now = clock.instant().atZone(ZoneId.of("Asia/Shanghai"));
        var businessHours = hours.views(row.id());
        var stats = load.resolve(new RatingCacheKey(row.id(), revision), () -> RatingStats.from(reviews.ratings(List.of(row.id()))));
        boolean open = row.businessEnabled() && businessHours.stream().anyMatch(hour -> hour.weekday() == now.getDayOfWeek().getValue()
                && !now.toLocalTime().isBefore(LocalTime.parse(hour.startTime())) && now.toLocalTime().isBefore(LocalTime.parse(hour.endTime())));
        return new ShopDetail(Long.toString(row.id()), row.name(),
                new ShopCategoryView(Long.toString(row.categoryId()), row.categoryCode(), row.categoryName()),
                row.locationText(), row.businessEnabled(), open, stats.average(row.id()), stats.count(),
                row.description(), businessHours, row.campusCode());
    }
}
