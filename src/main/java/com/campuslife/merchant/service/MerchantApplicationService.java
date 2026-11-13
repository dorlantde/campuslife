package com.campuslife.merchant.service;

import com.campuslife.auth.entity.AccountEntity;
import com.campuslife.auth.mapper.AccountMapper;
import com.campuslife.auth.service.RegistrationPolicy;
import com.campuslife.common.exception.BusinessException;
import com.campuslife.merchant.dto.MerchantApplicationRequest;
import com.campuslife.merchant.entity.ShopEntity;
import com.campuslife.merchant.mapper.ShopCategoryMapper;
import com.campuslife.merchant.mapper.ShopMapper;
import com.campuslife.merchant.mapper.ShopMemberMapper;
import com.campuslife.merchant.vo.MerchantApplicationView;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.campuslife.merchant.service.MerchantShopPolicy.categoryId;
import static com.campuslife.merchant.service.MerchantShopPolicy.invalid;
import static com.campuslife.merchant.service.MerchantShopPolicy.text;

@Service
@Profile("mysql")
public class MerchantApplicationService {
    private final AccountMapper accounts;
    private final ShopMapper shops;
    private final ShopMemberMapper members;
    private final ShopCategoryMapper categories;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transaction;

    public MerchantApplicationService(AccountMapper accounts, ShopMapper shops, ShopMemberMapper members,
                                      ShopCategoryMapper categories, PasswordEncoder passwords, PlatformTransactionManager manager) {
        this.accounts = accounts; this.shops = shops; this.members = members;
        this.categories = categories; this.passwords = passwords; this.transaction = new TransactionTemplate(manager);
    }

    public MerchantApplicationView apply(MerchantApplicationRequest request) {
        String username = RegistrationPolicy.username(request.username());
        String nickname = RegistrationPolicy.nickname(request.nickname());
        RegistrationPolicy.password(request.password());
        long categoryId = categoryId(request.categoryId());
        String name = text(request.shopName(), 1, 80, "店铺名称");
        String location = text(request.locationText(), 1, 200, "店铺位置");
        String description = text(request.description() == null ? "" : request.description(), 0, 1000, "店铺介绍");
        // BCrypt is deliberately outside the multi-row transaction and its locks.
        String hash = passwords.encode(request.password());
        try {
            return transaction.execute(status -> {
                if (categories.lockEnabled(categoryId) == null) { throw invalid("分类不存在或未启用，请重新选择"); }
                AccountEntity account = new AccountEntity();
                account.setUsername(username); account.setNickname(nickname); account.setPasswordHash(hash);
                account.setRole("MERCHANT"); account.setStatus("ACTIVE"); account.setVersion(0L);
                accounts.insert(account);
                ShopEntity shop = new ShopEntity();
                shop.setCategoryId(categoryId); shop.setName(name); shop.setLocationText(location);
                shop.setDescription(description); shop.setCampusCode("XINGHAI_MAIN");
                shop.setApprovalStatus("PENDING"); shop.setBusinessEnabled(false); shop.setVersion(0L);
                shops.insert(shop);
                if (members.createOwner(shop.getId(), account.getId()) != 1) { throw new IllegalStateException("Owner insert failed"); }
                return new MerchantApplicationView(account.getId().toString(), shop.getId().toString(), "PENDING");
            });
        } catch (DuplicateKeyException exception) {
            // Translate only the shared username constraint, after transaction rollback.
            if (!String.valueOf(exception.getMostSpecificCause().getMessage()).contains("uk_account_username")) { throw exception; }
            throw new BusinessException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已被使用");
        }
    }

}
