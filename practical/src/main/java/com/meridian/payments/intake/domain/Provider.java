package com.meridian.payments.intake.domain;

/** Payment providers. Adding a provider adds a value here and an adapter; the shared flow does not change. */
public enum Provider {
    QUETZAL("quetzal", Country.MX, "/meridian/payments/quetzal-mx"),
    ANDINA("andina", Country.CO, "/meridian/payments/andina-co"),
    AUSTRAL("austral", Country.CL, "/meridian/payments/austral-cl");

    private final String pathSegment;
    private final Country country;
    private final String eventSource;

    Provider(String pathSegment, Country country, String eventSource) {
        this.pathSegment = pathSegment;
        this.country = country;
        this.eventSource = eventSource;
    }

    public String pathSegment() {
        return pathSegment;
    }

    public Country country() {
        return country;
    }

    public String eventSource() {
        return eventSource;
    }
}
