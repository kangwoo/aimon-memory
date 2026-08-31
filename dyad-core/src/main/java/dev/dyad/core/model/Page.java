package dev.dyad.core.model;

import java.util.List;

/** A slice of results plus enough state to ask for the next one. */
public record Page<T>(List<T> items, int page, int size, long total) {

    public Page {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public boolean hasNext() {
        return (long) page * size + items.size() < total;
    }

    public <R> Page<R> map(java.util.function.Function<T, R> fn) {
        return new Page<>(items.stream().map(fn).toList(), page, size, total);
    }
}
