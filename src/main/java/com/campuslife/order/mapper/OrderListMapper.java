package com.campuslife.order.mapper;

import com.campuslife.order.mapper.model.OrderSummaryRow;
import java.util.List;
import org.apache.ibatis.annotations.*;

public interface OrderListMapper {
    String FROM_OWN_BOOKINGS="""
            FROM service_order o JOIN booking_detail b ON b.order_id=o.id
            WHERE o.student_id=#{student} AND o.order_type='BOOKING'
              AND (#{status} IS NULL OR o.status=#{status})
            """;

    String FROM_OWN="""
            FROM service_order o
            LEFT JOIN booking_detail b ON b.order_id=o.id AND o.order_type='BOOKING'
            LEFT JOIN print_detail p ON p.order_id=o.id AND o.order_type='PRINT'
            WHERE o.student_id=#{student} AND (b.order_id IS NOT NULL OR p.order_id IS NOT NULL)
              AND (#{status} IS NULL OR o.status=#{status})
            """;
    String OWN_COLUMNS="""
            SELECT o.id,o.order_no,o.order_type,o.status,o.payment_status,o.shop_id,
                   o.shop_name_snapshot AS shop_name,COALESCE(b.service_name_snapshot,p.service_name_snapshot) AS service_name,
                   o.payable_amount,o.currency,o.created_at,b.start_at AS booking_start_at,b.end_at AS booking_end_at,
                   p.progress AS printing_progress
            """;
    @Select("SELECT COUNT(*) " + FROM_OWN)
    long countOwn(@Param("student") long student,@Param("status") String status);

    @Select(OWN_COLUMNS + FROM_OWN + " ORDER BY o.created_at DESC,o.id DESC LIMIT #{size} OFFSET #{offset}")
    List<OrderSummaryRow> pageOwn(@Param("student") long student,@Param("status") String status,
                                 @Param("offset") long offset,@Param("size") int size);

    @Select("SELECT COUNT(*) " + FROM_OWN + " AND o.order_type=#{type}")
    long countOwnType(@Param("student") long student,@Param("type") String type,@Param("status") String status);
    @Select(OWN_COLUMNS + FROM_OWN + " AND o.order_type=#{type} ORDER BY o.created_at DESC,o.id DESC LIMIT #{size} OFFSET #{offset}")
    List<OrderSummaryRow> pageOwnType(@Param("student") long student,@Param("type") String type,@Param("status") String status,@Param("offset") long offset,@Param("size") int size);

    String FROM_SHOP="""
            FROM service_order o
            LEFT JOIN booking_detail b ON b.order_id=o.id AND o.order_type='BOOKING'
            LEFT JOIN print_detail p ON p.order_id=o.id AND o.order_type='PRINT'
            WHERE o.shop_id=#{shop} AND (b.order_id IS NOT NULL OR p.order_id IS NOT NULL)
              AND (#{status} IS NULL OR o.status=#{status})
            """;

    @Select("SELECT COUNT(*) " + FROM_SHOP)
    long countShop(@Param("shop") long shop,@Param("status") String status);

    @Select(OWN_COLUMNS + FROM_SHOP + " ORDER BY o.created_at DESC,o.id DESC LIMIT #{size} OFFSET #{offset}")
    List<OrderSummaryRow> pageShop(@Param("shop") long shop,@Param("status") String status,
                                  @Param("offset") long offset,@Param("size") int size);
    @Select("SELECT COUNT(*) " + FROM_SHOP + " AND o.order_type=#{type}")
    long countShopType(@Param("shop") long shop,@Param("type") String type,@Param("status") String status);
    @Select(OWN_COLUMNS + FROM_SHOP + " AND o.order_type=#{type} ORDER BY o.created_at DESC,o.id DESC LIMIT #{size} OFFSET #{offset}")
    List<OrderSummaryRow> pageShopType(@Param("shop") long shop,@Param("type") String type,@Param("status") String status,@Param("offset") long offset,@Param("size") int size);
}
