package io.github.doantrantuandat.example.accounts;

import io.crnk.core.exception.ForbiddenException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class MockAuth {
    private static final String ADMIN_ROLE = "admin";

    private MockAuth() {}

    public static String currentRole() {
        String role = header("X-Mock-Role");
        return role != null ? role : "viewer";
    }

    public static String currentUser() {
        String user = header("X-Mock-User");
        return user != null ? user : "anonymous";
    }

    public static void requireAdmin(String action) {
        String role = currentRole();
        if (!ADMIN_ROLE.equalsIgnoreCase(role)) {
            throw new ForbiddenException("only role '" + ADMIN_ROLE + "' may " + action + " (X-Mock-Role: " + role + ")");
        }
    }

    private static String header(String name) {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            return attrs.getRequest().getHeader(name);
        } catch (IllegalStateException e) {
            return null;
        }
    }
}
