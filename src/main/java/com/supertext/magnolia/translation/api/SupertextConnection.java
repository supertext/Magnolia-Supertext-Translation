package com.supertext.magnolia.translation.api;

/**
 * Where and how to reach Supertext (resolved from the module settings and the environment).
 *
 * @param endpoint            API base URL, e.g. {@code https://api.supertext.com/v1/}
 * @param apiKey              the key, with or without the {@code Supertext-Auth-Key } prefix
 * @param pollIntervalMillis  time between status checks
 * @param pollTimeoutSeconds  how long to wait for one document
 * @param reviewUrl           base URL of Supertext's review service for languages with <em>Translation review</em>
 *                            on ({@code ""} = review off for every language); see {@link #forReview(boolean)}
 */
public record SupertextConnection(String endpoint, String apiKey, long pollIntervalMillis, int pollTimeoutSeconds, String reviewUrl) {

    public SupertextConnection(String endpoint, String apiKey, long pollIntervalMillis, int pollTimeoutSeconds) {
        this(endpoint, apiKey, pollIntervalMillis, pollTimeoutSeconds, "");
    }

    public SupertextConnection(String endpoint, String apiKey) {
        this(endpoint, apiKey, 2000, 300);
    }

    public SupertextConnection {
        reviewUrl = reviewUrl == null ? "" : reviewUrl.trim();
    }

    /**
     * The connection for a target language: with {@code review} the review service (same key, poll interval and
     * timeout; it speaks the same API and forwards to Supertext), else this one. Without a review URL, or with the
     * same URL as the endpoint, always this one. The SDK client built from it uploads, polls, downloads and deletes,
     * so a job is always collected through the service that received it.
     */
    public SupertextConnection forReview(boolean review) {
        if (!review || reviewUrl.isEmpty() || withSlash(reviewUrl).equals(withSlash(endpoint))) {
            return this;
        }
        return new SupertextConnection(reviewUrl, apiKey, pollIntervalMillis, pollTimeoutSeconds, "");
    }

    /** Whether translations into languages with <em>Translation review</em> on go through the review service. */
    public boolean reviewAvailable() {
        return forReview(true) != this;
    }

    private static String withSlash(String url) {
        String value = url == null ? "" : url.trim();
        return value.endsWith("/") ? value : value + "/";
    }
}
