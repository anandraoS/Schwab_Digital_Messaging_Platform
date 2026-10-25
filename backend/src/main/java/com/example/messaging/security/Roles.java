package com.example.messaging.security;

import java.util.Set;

public final class Roles {
    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";
    public static final String TENANT_ADMIN = "TENANT_ADMIN";
    public static final String TEMPLATE_AUTHOR = "TEMPLATE_AUTHOR";
    public static final String TEMPLATE_APPROVER = "TEMPLATE_APPROVER";
    public static final String OPERATOR = "OPERATOR";
    public static final String SENDER = "SENDER";
    public static final String AUDITOR = "AUDITOR";

    public static final Set<String> ALL = Set.of(PLATFORM_ADMIN, TENANT_ADMIN, TEMPLATE_AUTHOR, TEMPLATE_APPROVER,
            OPERATOR, SENDER, AUDITOR);

    public static final String ALL_TENANT_ADMINS_EXPR = "hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN')";

    private Roles() {}
}
