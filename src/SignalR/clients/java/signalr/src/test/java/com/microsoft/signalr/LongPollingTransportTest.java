// Copyright (c) .NET Foundation. All rights reserved.
// Licensed under the Apache License, Version 2.0. See License.txt in the project root for license information.

package com.microsoft.signalr;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.reactivex.Single;
import io.reactivex.subjects.CompletableSubject;

public class LongPollingTransportTest {

    @Test
    public void LongPollingFailsToConnectWith404Response() {
        TestHttpClient client = new TestHttpClient()
                .on("GET", (req) -> Single.just(new HttpResponse(404, "", "")));

        Map<String, String> headers = new HashMap<>();
        LongPollingTransport transport = new LongPollingTransport(headers, client, Single.just(""));
        Throwable exception = assertThrows(RuntimeException.class, () -> transport.start("http://example.com").timeout(1, TimeUnit.SECONDS).blockingAwait());
        assertEquals(Exception.class, exception.getCause().getClass());
        assertEquals("Failed to connect.", exception.getCause().getMessage());
        assertFalse(transport.isActive());
    }

    @Test
    public void LongPollingTransportCantSendBeforeStart() {
        TestHttpClient client = new TestHttpClient()
                .on("GET", (req) -> Single.just(new HttpResponse(404, "", "")));

        Map<String, String> headers = new HashMap<>();
        LongPollingTransport transport = new LongPollingTransport(headers, client, Single.just(""));
        Throwable exception = assertThrows(RuntimeException.class, () -> transport.send("First").timeout(1, TimeUnit.SECONDS).blockingAwait());
        assertEquals(Exception.class, exception.getCause().getClass());
        assertEquals("Cannot send unless the transport is active.", exception.getCause().getMessage());
        assertFalse(transport.isActive());
    }

    @Test
    public void StatusCode204StopsLongPollingTriggersOnClosed() {
        AtomicBoolean firstPoll = new AtomicBoolean(true);
        CompletableSubject block = CompletableSubject.create();
        TestHttpClient client = new TestHttpClient()
                .on("GET", (req) -> {
                    if (firstPoll.get()) {
                        firstPoll.set(false);
                        return Single.just(new HttpResponse(200, "", ""));
                    }
                    return Single.just(new HttpResponse(204, "", ""));
                });

        Map<String, String> headers = new HashMap<>();
        LongPollingTransport transport = new LongPollingTransport(headers, client, Single.just(""));
        AtomicBoolean onClosedRan = new AtomicBoolean(false);
        transport.setOnClose((error) -> {
            onClosedRan.set(true);
            block.onComplete();
        });

        assertFalse(onClosedRan.get());
        transport.start("http://example.com").timeout(100, TimeUnit.SECONDS).blockingAwait();
        block.blockingAwait();
        assertTrue(onClosedRan.get());
        assertFalse(transport.isActive());
    }

    @Test
    public void LongPollingFailsWhenReceivingUnexpectedErrorCode() {
        AtomicBoolean firstPoll = new AtomicBoolean(true);
        CompletableSubject blocker = CompletableSubject.create();
        TestHttpClient client = new TestHttpClient()
                .on("GET", (req) -> {
                    if (firstPoll.get()) {
                        firstPoll.set(false);
                        return Single.just(new HttpResponse(200, "", ""));
                    }
                    return Single.just(new HttpResponse(999, "", ""));
                });

        Map<String, String> headers = new HashMap<>();
        LongPollingTransport transport = new LongPollingTransport(headers, client, Single.just(""));
        AtomicBoolean onClosedRan = new AtomicBoolean(false);
        transport.setOnClose((error) -> {
            onClosedRan.set(true);
            assertEquals("Unexpected response code 999.", error);
            blocker.onComplete();

        });

        transport.start("http://example.com").timeout(1, TimeUnit.SECONDS).blockingAwait();
        blocker.blockingAwait();
        assertFalse(transport.isActive());
        assertTrue(onClosedRan.get());
    }

    @Test
    public void CanSetAndTriggerOnReceive() {
        TestHttpClient client = new TestHttpClient()
                .on("GET", (req) -> Single.just(new HttpResponse(200, "", "")));

        Map<String, String> headers = new HashMap<>();
        LongPollingTransport transport = new LongPollingTransport(headers, client, Single.just(""));

        AtomicBoolean onReceivedRan = new AtomicBoolean(false);
        transport.setOnReceive((message) -> {
            onReceivedRan.set(true);
            assertEquals("TEST", message);
        });

        // The transport doesn't need to be active to trigger onReceive for the case
        // when we are handling the last outstanding poll.
        transport.onReceive("TEST");
        assertTrue(onReceivedRan.get());
    }

    @Test
    public void LongPollingTransportOnReceiveGetsCalled() {
        AtomicInteger requestCount = new AtomicInteger();
        CompletableSubject block = CompletableSubject.create();
        TestHttpClient client = new TestHttpClient()
                .on("GET", (req) -> {
                    if (requestCount.get() == 0) {
                        requestCount.incrementAndGet();
                        return Single.just(new HttpResponse(200, "", ""));
                    } else if (requestCount.get() == 1) {
                        requestCount.incrementAndGet();
                        return Single.just(new HttpResponse(200, "", "TEST"));
                    }

                    return Single.just(new HttpResponse(204, "", ""));
                });

        Map<String, String> headers = new HashMap<>();
        LongPollingTransport transport = new LongPollingTransport(headers, client, Single.just(""));

        AtomicBoolean onReceiveCalled = new AtomicBoolean(false);
        AtomicReference<String> message = new AtomicReference<>();
        transport.setOnReceive((msg -> {
            onReceiveCalled.set(true);
            message.set(msg);
            block.onComplete();
        }) );

        transport.setOnClose((error) -> {});

        transport.start("http://example.com").timeout(1, TimeUnit.SECONDS).blockingAwait();
        block.blockingAwait(1,TimeUnit.SECONDS);
        assertTrue(onReceiveCalled.get());
        assertEquals("TEST", message.get());
    }

    @Test
    public void LongPollingTransportOnReceiveGetsCalledMultipleTimes() {
        AtomicInteger requestCount = new AtomicInteger();
        CompletableSubject blocker = CompletableSubject.create();
        TestHttpClient client = new TestHttpClient()
                .on("GET", (req) -> {
                    if (requestCount.get() == 0) {
                        requestCount.incrementAndGet();
                        return Single.just(new HttpResponse(200, "", ""));
                    } else if (requestCount.get() == 1) {
                        requestCount.incrementAndGet();
                        return Single.just(new HttpResponse(200, "", "FIRST"));
                    } else if (requestCount.get() == 2) {
                        requestCount.incrementAndGet();
                        return Single.just(new HttpResponse(200, "", "SECOND"));
                    }

                    return Single.just(new HttpResponse(204, "", ""));
                });

        Map<String, String> headers = new HashMap<>();
        LongPollingTransport transport = new LongPollingTransport(headers, client, Single.just(""));

        AtomicBoolean onReceiveCalled = new AtomicBoolean(false);
        AtomicReference<String> message = new AtomicReference<>("");
        AtomicInteger messageCount = new AtomicInteger();
        transport.setOnReceive((msg) -> {
            onReceiveCalled.set(true);
            message.set(message.get() + msg);
            if (messageCount.incrementAndGet() == 2) {
                blocker.onComplete();
            }
        });

        transport.setOnClose((error) -> {});

        transport.start("http://example.com").timeout(1, TimeUnit.SECONDS).blockingAwait();
        blocker.blockingAwait(1, TimeUnit.SECONDS);
        assertTrue(onReceiveCalled.get());
        assertEquals("FIRSTSECOND", message.get());
    }

    @Test
    public void LongPollingTransportSendsHeaders() {
        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<String> headerValue = new AtomicReference<>();
        CompletableSubject close = CompletableSubject.create();
        TestHttpClient client = new TestHttpClient()
                .on("GET", (req) -> {
                    if (requestCount.get() == 0) {
                        requestCount.incrementAndGet();
                        return Single.just(new HttpResponse(200, "", ""));
                    }
                    close.blockingAwait();
                    return Single.just(new HttpResponse(204, "", ""));
                }).on("POST", (req) -> {
                    assertFalse(req.getHeaders().isEmpty());
                    headerValue.set(req.getHeaders().get("KEY"));
                    return Single.just(new HttpResponse(200, "", ""));
                });

        Map<String, String> headers = new HashMap<>();
        headers.put("KEY", "VALUE");
        LongPollingTransport transport = new LongPollingTransport(headers, client, Single.just(""));
        transport.setOnClose((error) -> {});

        transport.start("http://example.com").timeout(1, TimeUnit.SECONDS).blockingAwait();
        transport.send("TEST").blockingAwait();
        close.onComplete();
        assertEquals(headerValue.get(), "VALUE");
    }

    @Test
    public void LongPollingTransportSetsAuthorizationHeader() {
        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<String> headerValue = new AtomicReference<>();
        CompletableSubject close = CompletableSubject.create();
        TestHttpClient client = new TestHttpClient()
                .on("GET", (req) -> {
                    if (requestCount.get() == 0) {
                        requestCount.incrementAndGet();
                        return Single.just(new HttpResponse(200, "", ""));
                    }
                    close.blockingAwait();
                    return Single.just(new HttpResponse(204, "", ""));
                })
                .on("POST", (req) -> {
                    assertFalse(req.getHeaders().isEmpty());
                    headerValue.set(req.getHeaders().get("Authorization"));
                    return Single.just(new HttpResponse(200, "", ""));
                });

        Map<String, String> headers = new HashMap<>();
        Single<String> tokenProvider = Single.just("TOKEN");
        LongPollingTransport transport = new LongPollingTransport(headers, client, tokenProvider);
        transport.setOnClose((error) -> {});

        transport.start("http://example.com").timeout(100, TimeUnit.SECONDS).blockingAwait();
        transport.send("TEST").blockingAwait();
        assertEquals(headerValue.get(), "Bearer TOKEN");
        assertEquals("Bearer TOKEN", client.getSentRequests().get(2).getHeaders().get("Authorization"));
        close.onComplete();
    }
}