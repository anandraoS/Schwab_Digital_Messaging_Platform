package com.example.messaging.repository;

import jakarta.persistence.criteria.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/** Small generic specification building blocks; null/blank arguments yield "no filter". */
public final class Specs {

    private Specs() {}

    public static <T> Specification<T> eq(String attribute, Object value) {
        return (root, q, cb) -> value == null ? null : cb.equal(root.get(attribute), value);
    }

    public static <T> Specification<T> in(String attribute, Collection<?> values) {
        return (root, q, cb) -> values == null || values.isEmpty() ? null : root.get(attribute).in(values);
    }

    public static <T> Specification<T> from(String attribute, Instant from) {
        return (root, q, cb) -> from == null ? null : cb.greaterThanOrEqualTo(root.<Instant>get(attribute), from);
    }

    public static <T> Specification<T> to(String attribute, Instant to) {
        return (root, q, cb) -> to == null ? null : cb.lessThan(root.<Instant>get(attribute), to);
    }

    /** Case-insensitive "contains" over one or more string attributes (escaped, so '%' and '_' are literals). */
    public static <T> Specification<T> containsAny(String text, String... attributes) {
        return (root, q, cb) -> {
            if (text == null || text.isBlank()) {
                return null;
            }
            String pattern = "%" + escape(text.trim().toLowerCase(Locale.ROOT)) + "%";
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            for (String a : attributes) {
                Path<String> p = root.get(a);
                predicates.add(cb.like(cb.lower(p), pattern, '\\'));
            }
            return cb.or(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
