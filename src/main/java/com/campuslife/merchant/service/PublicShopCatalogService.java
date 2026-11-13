package com.campuslife.merchant.service;

import com.campuslife.common.exception.BusinessException;
import com.campuslife.merchant.mapper.BusinessHourMapper;
import com.campuslife.merchant.mapper.PublicShopMapper;
import com.campuslife.merchant.mapper.PublicBarberMapper;
import com.campuslife.merchant.mapper.PublicReviewMapper;
import com.campuslife.merchant.mapper.ServiceItemMapper;
import com.campuslife.merchant.mapper.model.PublicShopRow;
import com.campuslife.merchant.mapper.model.PublicBarberRow;
import com.campuslife.merchant.vo.*;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("mysql")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PublicShopCatalogService {
    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Shanghai");
    private final PublicShopMapper shops;
    private final BusinessHourMapper hours;
    private final ServiceItemMapper services;
    private final Clock clock;
    private final PublicBarberMapper barbers;
    private final PublicReviewMapper reviews;
    private final PublicShopDetailTransactions details;
    private final com.campuslife.merchant.cache.RatingCache ratingCache;

    public PublicShopCatalogService(PublicShopMapper shops, BusinessHourMapper hours, ServiceItemMapper services, Clock clock, PublicBarberMapper barbers, PublicReviewMapper reviews,
            PublicShopDetailTransactions details, com.campuslife.merchant.cache.RatingCache ratingCache) {
        this.shops = shops; this.hours = hours; this.services = services; this.clock = clock;
        this.barbers = barbers;
        this.reviews = reviews;
        this.details = details; this.ratingCache = ratingCache;
    }

    public ShopPage list(String keyword, String categoryId, String pageText, String sizeText) {
        String pattern = keywordPattern(keyword);
        Long category = categoryId == null ? null : MerchantServicePolicy.id(categoryId, "categoryId");
        int page = positiveInt(pageText, 1, Integer.MAX_VALUE, "page");
        int size = positiveInt(sizeText, 20, 100, "size");
        var now = clock.instant().atZone(CAMPUS_ZONE);
        long total = shops.count(category, pattern);
        // Only the categorized first page has a proven ordered-index path; keep search/later pages unchanged.
        var rows = category != null && pattern == null && page == 1
                ? shops.categoryFirstPage(category, size)
                : shops.page(category, pattern, ((long) page - 1) * size, size);
        var byShop = new HashMap<Long, List<BusinessHourView>>();
        if (!rows.isEmpty()) {
            for (var hour : hours.batchRows(rows.stream().map(PublicShopRow::id).toList())) {
                byShop.computeIfAbsent(hour.shopId(), ignored -> new ArrayList<>()).add(hour.view());
            }
        }
        var ratings=new HashMap<Long,PublicReviewMapper.RatingRow>();
        if(!rows.isEmpty()){
            for(var rating:reviews.ratings(rows.stream().map(PublicShopRow::id).toList())){ratings.put(rating.shopId(),rating);}
        }
        var items = rows.stream().map(row -> summary(row, open(row.businessEnabled(), byShop.getOrDefault(row.id(), List.of()), now),ratings.get(row.id()))).toList();
        return new ShopPage(items, page, size, total, total / size + (total % size == 0 ? 0 : 1));
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ShopDetail detail(String id) {
        try (var load = ratingCache.request()) {
            var result = details.read(id, load);
            load.commit(); // The RR method has committed and released its connection before Redis writes.
            return result;
        }
    }

    public PublicReviewPage reviews(String id,String pageText,String sizeText){
        // Parse every parameter before the visibility query, matching the existing public directory.
        long shop=MerchantServicePolicy.id(id,"shopId");
        int page=positiveInt(pageText,1,Integer.MAX_VALUE,"page");int size=positiveInt(sizeText,20,100,"size");
        if(shops.visible(shop)==null){throw new BusinessException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","公开店铺不存在");}
        long total=reviews.count(shop);
        var items=reviews.page(shop,size,((long)page-1)*size).stream().map(PublicReviewMapper.ReviewRow::view).toList();
        return new PublicReviewPage(items,page,size,total,total/size+(total%size==0?0:1));
    }

    public List<ServiceView> services(String id, String type) {
        if (type != null && !Set.of("HAIRCUT", "PRINT").contains(type)) { throw MerchantShopPolicy.invalid("serviceType 须为 HAIRCUT 或 PRINT"); }
        var row = visible(id);
        return services.listOwn(row.id(), type, true).stream().map(service -> new ServiceView(Long.toString(service.getId()),
                service.getServiceType(), service.getName(), service.getDescription(), service.getPriceAmount(), service.getDurationMinutes(),
                Long.toString(service.getVersion()))).toList();
    }

    public List<PublicBarberView> barbers(String id, String serviceId) {
        Long selected = serviceId == null ? null : MerchantServicePolicy.id(serviceId, "serviceId");
        var shop = visible(id);
        if (selected != null) {
            if (!"HAIRCUT".equals(shop.categoryCode())) { throw missingBarberService(); }
            var service = services.findOwn(selected, shop.id());
            if (service == null || !Boolean.TRUE.equals(service.getEnabled()) || !"HAIRCUT".equals(service.getServiceType())) {
                throw missingBarberService();
            }
        }
        if (!"HAIRCUT".equals(shop.categoryCode())) { return List.of(); }
        var people = new LinkedHashMap<Long, List<PublicBarberRow>>();
        for (var row : barbers.rows(shop.id(), selected)) {
            people.computeIfAbsent(row.barberId(), ignored -> new ArrayList<>()).add(row);
        }
        return people.entrySet().stream().map(entry -> new PublicBarberView(Long.toString(entry.getKey()),
                entry.getValue().getFirst().displayName(), entry.getValue().stream().map(row -> Long.toString(row.serviceId())).toList())).toList();
    }

    private static BusinessException missingBarberService() {
        return new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "公开理发服务不存在");
    }

    private PublicShopRow visible(String id) {
        long parsed = MerchantServicePolicy.id(id, "shopId");
        var row = shops.visible(parsed);
        if (row == null) { throw new BusinessException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "公开店铺不存在"); }
        return row;
    }
    private static ShopCategoryView category(PublicShopRow row) {
        return new ShopCategoryView(Long.toString(row.categoryId()), row.categoryCode(), row.categoryName());
    }
    private static ShopSummary summary(PublicShopRow row, boolean open,PublicReviewMapper.RatingRow rating) {
        return new ShopSummary(Long.toString(row.id()), row.name(), category(row), row.locationText(), row.businessEnabled(), open, rating==null?null:rating.average(), rating==null?0:rating.reviewCount());
    }
    private static boolean open(boolean enabled, List<BusinessHourView> hours, ZonedDateTime now) {
        if (!enabled) { return false; }
        var time = now.toLocalTime();
        return hours.stream().anyMatch(hour -> hour.weekday() == now.getDayOfWeek().getValue()
                && !time.isBefore(LocalTime.parse(hour.startTime())) && time.isBefore(LocalTime.parse(hour.endTime())));
    }
    private static String keywordPattern(String value) {
        if (value == null) { return null; }
        String normalized = MerchantShopPolicy.text(value, 0, 80, "keyword");
        if (normalized.isEmpty()) { return null; }
        // Use an explicit escape character: backslash remains a literal regardless of MySQL SQL modes.
        return "%" + normalized.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
    private static int positiveInt(String value, int fallback, int max, String field) {
        if (value == null) { return fallback; }
        try {
            if (!value.matches("[0-9]+")) { throw new NumberFormatException(); }
            int number = Integer.parseInt(value);
            if (number < 1 || number > max) { throw new NumberFormatException(); }
            return number;
        } catch (NumberFormatException ex) { throw MerchantShopPolicy.invalid(field + " 须为 1～" + max + " 的整数"); }
    }
}
