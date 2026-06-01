package com.codepilot.module.validation.service;

public class ReviewValidationProbeService {

    public String findUserQuery(String username) {
        return "select * from users where username = '" + username + "'";
    }
}
