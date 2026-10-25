package com.example.messaging.web;

import com.example.messaging.common.ApiException;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Builds validated {@link Pageable}s: size 1..200 (default 20), whitelisted sort properties. */
public final class Pagination {

    public static final int MAX_SIZE = 200;
    public static final int DEFAULT_SIZE = 20;

    private Pagination() {}

    public static Pageable of(Integer page, Integer size, String sort, Set<String> allowedSort, String defaultProperty,
            Sort.Direction defaultDirection) {
        int p = page == null ? 0 : page;
        int s = size == null ? DEFAULT_SIZE : size;
        if (p < 0) {
            throw new ApiException.ValidationException("page", "page must be >= 0");
        }
        if (s < 1 || s > MAX_SIZE) {
            throw new ApiException.ValidationException("size", "size must be between 1 and " + MAX_SIZE);
        }
        String property = defaultProperty;
        Sort.Direction direction = defaultDirection;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            property = parts[0].trim();
            if (!allowedSort.contains(property)) {
                throw new ApiException.ValidationException("sort", "sort property must be one of " + allowedSort);
            }
            if (parts.length > 1) {
                String d = parts[1].trim();
                if ("asc".equalsIgnoreCase(d)) {
                    direction = Sort.Direction.ASC;
                } else if ("desc".equalsIgnoreCase(d)) {
                    direction = Sort.Direction.DESC;
                } else {
                    throw new ApiException.ValidationException("sort", "sort direction must be asc or desc");
                }
            }
        }
        // secondary key keeps paging stable when the primary key has ties
        Sort sorting = Sort.by(direction, property).and(Sort.by(Sort.Direction.ASC, "id"));
        return PageRequest.of(p, s, sorting);
    }
}
