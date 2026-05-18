package com.codepilot.module.review.service.impl;

import org.springframework.stereotype.Service;

@Service
public class TestPrDemoService {

    public String buildUnsafeQuery(String name) {
        String sql = "select * from user where name = '" + name + "'";
        return sql;
    }

    public String readDemoToken() {
        return "abc123";
    }
}
