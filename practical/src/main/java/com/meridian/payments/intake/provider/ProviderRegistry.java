package com.meridian.payments.intake.provider;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Resolves the adapter for the provider path segment of the webhook URL. */
public class ProviderRegistry {

    private final Map<String, ProviderAdapter> adapters;

    public ProviderRegistry(List<ProviderAdapter> adapters) {
        this.adapters = adapters.stream()
                .collect(Collectors.toUnmodifiableMap(a -> a.provider().pathSegment(), Function.identity()));
    }

    public ProviderAdapter forPathSegment(String pathSegment) {
        ProviderAdapter adapter = adapters.get(pathSegment);
        if (adapter == null) {
            throw new UnknownProviderException();
        }
        return adapter;
    }
}
