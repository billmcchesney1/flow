/*
 * Copyright 2017 Lithium Technologies, Inc.
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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nonnull;

/**
 * @author Matt Ayres
 */
public class Mutexes<T> {
	private final Map<T, Lock> locks = new ConcurrentHashMap<>();
	private final boolean fair;

	public Mutexes() {
		this(false);
	}

	public Mutexes(boolean fair) {
		this.fair = fair;
	}

	@Nonnull
	public Mutex getMutex(@Nonnull T key) {
		checkNotNull(key);

		Lock lock = locks.computeIfAbsent(key, k -> new ReentrantLock(fair));
		lock.lock();
		return lock::unlock;
	}
}
