/*
 * Copyright 2015 Lithium Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lithium.flow.util;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;

import org.slf4j.Logger;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * @author Matt Ayres
 */
public class Threader implements AutoCloseable {
	private static final Logger log = Logs.getLogger();

	private final ListeningExecutorService service;
	private final AtomicInteger remaining = new AtomicInteger();
	private final AtomicInteger queued = new AtomicInteger();
	private volatile int retries;
	private volatile int maxQueued = Integer.MAX_VALUE;
	private volatile int needlePermits = Integer.MAX_VALUE;

	public Threader() {
		this(-1);
	}

	public Threader(int threads) {
		this(threads == -1 ? Executors.newCachedThreadPool()
				: threads == 0 ? MoreExecutors.newDirectExecutorService()
				: Executors.newFixedThreadPool(threads));
	}

	public Threader(@Nonnull ExecutorService service) {
		this.service = MoreExecutors.listeningDecorator(checkNotNull(service));
	}

	public int getRetries() {
		return retries;
	}

	@Nonnull
	public Threader setRetries(int retries) {
		this.retries = retries;
		return this;
	}

	@Nonnull
	public Threader withRetries(int retries) {
		this.retries = retries;
		return this;
	}

	public int getMaxQueued() {
		return maxQueued;
	}

	@Nonnull
	public Threader setMaxQueued(int maxQueued) {
		this.maxQueued = maxQueued;
		return this;
	}

	@Nonnull
	public Threader withMaxQueued(int maxQueued) {
		this.maxQueued = maxQueued;
		return this;
	}

	public int getNeedlePermits() {
		return needlePermits;
	}

	@Nonnull
	public Threader setNeedlePermits(int needlePermits) {
		this.needlePermits = needlePermits;
		return this;
	}

	@Nonnull
	public Threader withNeedlePermits(int needlePermits) {
		this.needlePermits = needlePermits;
		return this;
	}

	@Nonnull
	public ListenableFuture<Void> execute(@Nonnull String name, @Nonnull Executable executable) {
		return submit(name, executable);
	}

	@Nonnull
	public <T> ListenableFuture<T> submit(@Nonnull String name, @Nonnull Callable<T> callable) {
		return submit(name, callable, retries);
	}

	@Nonnull
	private <T> ListenableFuture<T> submit(@Nonnull String name, @Nonnull Callable<T> callable, int retriesLeft) {
		checkNotNull(name);
		checkNotNull(callable);

		if (retriesLeft == retries) {
			Sleep.until(() -> queued.get() < maxQueued);
		}

		remaining.incrementAndGet();
		queued.incrementAndGet();

		ListenableFuture<T> future = service.submit(() -> {
			queued.decrementAndGet();
			return callable.call();
		});

		Futures.addCallback(future, new FutureCallback<T>() {
			@Override
			public void onSuccess(T object) {
				remaining.decrementAndGet();
				log.debug("execution finished: {}", name);
			}

			@Override
			public void onFailure(@Nonnull Throwable throwable) {
				if (retriesLeft > 0) {
					submit(name, callable, retriesLeft - 1);
				}

				remaining.decrementAndGet();
				log.warn("execution failed: {} ({} retries left)", name, retriesLeft, throwable);
			}
		}, MoreExecutors.directExecutor());

		return future;
	}

	/**
	 * @deprecated Use {@link #close()} instead.
	 */
	@Deprecated
	public void finish() {
		close();
	}

	/**
	 * @deprecated Use {@link #close(long)} instead.
	 */
	@Deprecated
	public void finish(long timeout) {
		close(timeout);
	}

	@Override
	public void close() {
		close(-1);
	}

	public boolean close(long timeout) {
		long endTime = timeout == -1 ? Long.MAX_VALUE : System.currentTimeMillis() + timeout;
		Sleep.until(() -> remaining.get() == 0 || System.currentTimeMillis() >= endTime);
		service.shutdown();
		return remaining.get() == 0;
	}

	public int getRemaining() {
		return remaining.get();
	}

	public int getQueued() {
		return queued.get();
	}

	@Nonnull
	public <T> Needle<T> needle() {
		return new Needle<>(this, needlePermits);
	}

	@Nonnull
	public <T> Needle<T> needle(int permits) {
		return new Needle<>(this, permits);
	}

	@Nonnull
	public static Threader forCompute() {
		return new Threader(Runtime.getRuntime().availableProcessors());
	}

	@Nonnull
	public static Threader forDaemon() {
		return forDaemon(-1);
	}

	@Nonnull
	public static Threader forDaemon(int threads) {
		ThreadFactory defaultFactory = Executors.defaultThreadFactory();

		ThreadFactory daemonFactory = runnable -> {
			Thread thread = defaultFactory.newThread(runnable);
			thread.setDaemon(true);
			return thread;
		};

		ExecutorService service = threads == -1
				? Executors.newCachedThreadPool(daemonFactory)
				: Executors.newFixedThreadPool(threads, daemonFactory);

		return new Threader(service);
	}
}
