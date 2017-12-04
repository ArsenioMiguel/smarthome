/**
 * Copyright (c) 2014,2017 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.smarthome.core.internal.common;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.common.ThreadPoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the execution of safe-calls.
 *
 * It therefore tracks the executions in order to detect parallel execution and offers some helper methods for the
 * invocation handlers.
 *
 * @author Simon Kaufmann - initial contribution and API.
 *
 */
@NonNullByDefault
public class SafeCallManagerImpl implements SafeCallManager {

    private final Logger logger = LoggerFactory.getLogger(SafeCallManagerImpl.class);

    private final Map<Object, @Nullable Queue<Invocation>> queues = new HashMap<>();
    private final Map<Object, @Nullable TrackingCallable> activeIdentifiers = new HashMap<>();
    private final Map<Object, @Nullable Invocation> activeAsyncInvocations = new HashMap<>();

    private final ScheduledExecutorService watcher;
    private final String threadPoolName;
    private boolean enforceSingleThreadPerIdentifier;

    public SafeCallManagerImpl(ScheduledExecutorService watcher, String threadPoolName,
            boolean enforceSingleThreadPerIdentifier) {
        this.watcher = watcher;
        this.threadPoolName = threadPoolName;
        this.enforceSingleThreadPerIdentifier = enforceSingleThreadPerIdentifier;
    }

    @Override
    public void recordCallStart(Invocation invocation, TrackingCallable wrapper) {
        synchronized (activeIdentifiers) {
            TrackingCallable otherWrapper = activeIdentifiers.get(invocation.getIdentifier());
            if (enforceSingleThreadPerIdentifier && otherWrapper != null) {
                // another call to the same identifier is (still) running,
                // therefore queue it instead for async execution later on.
                // Inform the caller about the timeout by means of the exception.
                enqueue(invocation);
                throw new DuplicateExecutionException(otherWrapper);
            }
            activeIdentifiers.put(invocation.getIdentifier(), wrapper);
        }
        if (invocation.getInvocationHandler() instanceof InvocationHandlerAsync) {
            watch(invocation);
        }
    }

    @Override
    public void recordCallEnd(Invocation invocation, TrackingCallable wrapper) {
        synchronized (activeIdentifiers) {
            activeIdentifiers.remove(invocation.getIdentifier());
        }
        synchronized (activeAsyncInvocations) {
            activeAsyncInvocations.remove(invocation.getIdentifier());
        }
        logger.trace("Finished {}", invocation);
        trigger(invocation.getIdentifier());
    }

    @Override
    public void enqueue(Invocation call) {
        synchronized (queues) {
            Queue<Invocation> queue = queues.get(call.getIdentifier());
            if (queue == null) {
                queue = new LinkedList<>();
                queues.put(call.getIdentifier(), queue);
            }
            queue.add(call);
        }
        trigger(call.getIdentifier());
    }

    private void trigger(Object identifier) {
        logger.trace("Triggering submissions for '{}'", identifier);
        synchronized (activeIdentifiers) {
            if (enforceSingleThreadPerIdentifier && activeIdentifiers.containsKey(identifier)) {
                logger.trace("Identifier '{}' is already running", identifier);
                return;
            }
        }
        synchronized (activeAsyncInvocations) {
            if (activeAsyncInvocations.containsKey(identifier)) {
                logger.trace("Identifier '{}' is already scheduled for asynchronous execution", identifier);
                return;
            }
            Invocation next = dequeue(identifier);
            if (next != null) {
                logger.trace("Scheduling {} for asynchronous execution", next);
                activeAsyncInvocations.put(identifier, next);
                TrackingCallable wrapper = new TrackingCallable(next);
                getScheduler().submit(wrapper);
                logger.trace("Submitted {} for asynchronous execution", next);
            }
        }
    }

    private void handlePotentialTimeout(Invocation invocation) {
        Object identifier = invocation.getIdentifier();
        Invocation currentInvocation = activeAsyncInvocations.get(identifier);
        if (currentInvocation == invocation) {
            TrackingCallable currentWrapper = activeIdentifiers.get(identifier);
            if (currentWrapper != null) {
                invocation.handleTimeout(currentWrapper);
            }
        }
    }

    @Nullable
    public Invocation dequeue(Object identifier) {
        synchronized (queues) {
            Queue<Invocation> queue = queues.get(identifier);
            if (queue != null) {
                return queue.poll();
            }
        }
        return null;
    }

    @Override
    public boolean isSafeContext() {
        return Thread.currentThread().getName().startsWith(threadPoolName + "-");
    }

    @Override
    public ExecutorService getScheduler() {
        return ThreadPoolManager.getPool(threadPoolName);
    }

    private void watch(Invocation invocation) {
        watcher.schedule(() -> {
            handlePotentialTimeout(invocation);
        }, invocation.getTimeout(), TimeUnit.MILLISECONDS);
        logger.trace("Scheduling timeout watcher in {}ms", invocation.getTimeout());
    }

    public void setEnforceSingleThreadPerIdentifier(boolean enforceSingleThreadPerIdentifier) {
        this.enforceSingleThreadPerIdentifier = enforceSingleThreadPerIdentifier;
    }

}