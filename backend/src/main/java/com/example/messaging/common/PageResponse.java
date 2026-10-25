package com.example.messaging.common;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages, String sort) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        String sort = page.getSort().isSorted()
                ? page.getSort().stream().map(o -> o.getProperty() + "," + o.getDirection().name().toLowerCase())
                        .reduce((a, b) -> a + ";" + b).orElse(null)
                : null;
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), sort);
    }

    public static <T> PageResponse<T> of(Page<T> page) {
        return of(page, Function.identity());
    }
}
