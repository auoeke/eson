package net.auoeke.eson;

import net.auoeke.eson.element.EsonElement;

public interface EsonAdapter<A, B extends EsonElement> extends ToEsonAdapter<A, B>, FromEsonAdapter<A, B> {
    @Override default boolean accept(Class<?> type) {
        return false;
    }
}
