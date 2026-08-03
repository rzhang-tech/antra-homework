package com.example.cover;

/**
 * The object-key contract, on its own, with no AWS SDK anywhere near it.
 *
 * <p><b>Extracted because CI failed and a laptop did not.</b> {@code bookIdFrom} used to live on
 * {@link CoverProcessor}, whose static initialiser builds three SDK clients — deliberately, so a warm
 * Lambda container builds them once rather than per event (Step 9). Building any SDK client requires a
 * resolvable region, and {@code DefaultAwsRegionProviderChain} throws when it cannot find one.
 *
 * <p>So the unit test for a pure string function loaded a class that needed cloud configuration. It
 * passed on a machine with {@code ~/.aws/config} and failed on a GitHub runner with
 * {@code ExceptionInInitializerError} — the first CI run found it, which is a fair summary of what CI
 * is for. Step 9's reflection had already noted that this contract is "a string format with two
 * implementations"; what it had not noticed was that only one of them was testable anywhere.
 *
 * <p>The general shape is worth more than the fix: <b>a test that passes only because of ambient
 * machine state is not testing what it appears to.</b> Setting {@code AWS_REGION} in the workflow
 * would also have turned the build green, and would have kept the defect — a pure function that
 * cannot be exercised without credentials — exactly where it was.
 */
final class CoverKey {

    private static final String PREFIX = "covers/";

    private CoverKey() {
    }

    /**
     * {@code covers/42} to {@code 42}; anything else to {@code null}.
     *
     * <p>The reverse of book-service's {@code CoverStorageService.keyFor}, and the reason that key has
     * no extension (Step 9b): {@code covers/42.png} and {@code covers/42.jpg} would be two objects,
     * two events and two rows for one book.
     *
     * <p>Returns {@code null} rather than throwing, and that half matters more. Throwing would fail
     * the whole invocation, Lambda would retry it, and the retry would fail identically — so one stray
     * object under {@code covers/} would fill the dead letter queue with something that can never
     * succeed, while the covers behind it waited.
     */
    static String bookIdFrom(String key) {
        if (key == null || !key.startsWith(PREFIX)) {
            return null;
        }
        String id = key.substring(PREFIX.length());
        return id.matches("\\d+") ? id : null;
    }
}
