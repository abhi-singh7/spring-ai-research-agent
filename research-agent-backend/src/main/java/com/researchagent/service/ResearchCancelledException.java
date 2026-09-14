package com.researchagent.service;

/**
 * Thrown by the pipeline's cooperative checkpoints when a user has cancelled an in-flight run.
 *
 * <p>Cancellation is signalled out-of-band via {@link ResearchCancellationRegistry}; this exception
 * only surfaces it at the next checkpoint (before each sub-topic round and before every LLM call
 * attempt). A blocking HTTP call already in flight runs to completion — no further work starts after
 * the signal. Catching it must NOT persist FAILED state: the cancel handler persists CANCELLED itself.</p>
 */
public class ResearchCancelledException extends RuntimeException {

    public ResearchCancelledException() {
        super("Research cancelled by user");
    }
}
