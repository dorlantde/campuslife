package com.campuslife.admin.vo;

import com.campuslife.auth.vo.AccountView;
import java.util.List;

public record AdminAccountPage(List<AccountView> items,int page,int size,long total,long totalPages) {}
