/*
 * Copyright 2016 qyh.me
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
package me.qyh.blog.core.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import me.qyh.blog.core.config.Constants;
import me.qyh.blog.core.context.Environment;
import me.qyh.blog.core.context.LockKeyContext;
import me.qyh.blog.core.entity.Lock;
import me.qyh.blog.core.entity.LockKey;
import me.qyh.blog.core.entity.LockResource;
import me.qyh.blog.core.exception.LockException;
import me.qyh.blog.core.exception.LogicException;
import me.qyh.blog.core.message.Message;
import me.qyh.blog.core.plugin.LockProviderRegistry;

/**
 * 锁管理器
 * 
 * @author Administrator
 *
 */
@Component
public class LockManager implements LockProviderRegistry {

	private final List<LockProvider> providers = new ArrayList<>();

	private static final Logger LOGGER = LoggerFactory.getLogger(LockManager.class);

	/**
	 * 解锁
	 * 
	 * @param lockResource
	 *            锁资源
	 * @throws LockException
	 *             解锁失败
	 */
	@Transactional(readOnly = true, propagation = Propagation.REQUIRED)
	public void openLock(LockResource lockResource) throws LockException {
		Objects.requireNonNull(lockResource);
		if (Environment.isLogin()) {
			return;
		}
		Optional<String> optionalLockId = lockResource.getLock();
		if (!optionalLockId.isPresent()) {
			return;
		}
		String resourceId = lockResource.getResource();
		String lockId = optionalLockId.get();
		findLock(lockId).ifPresent(lock -> {
			LockKey key = LockKeyContext.getKey(resourceId, lockId)
					.orElseThrow(() -> new LockException(lock, lockResource, null));
			try {
				lock.tryOpen(key);
			} catch (LogicException e) {
				throw new LockException(lock, lockResource, new Message("lock.update.recheck", "因为锁更新导致解锁失败，请重新解锁"));
			} catch (Exception e) {
				LOGGER.error("尝试用" + key.getKey() + "打开锁" + lock.getId() + "异常，异常信息:" + e.getMessage(), e);
				throw new LockException(lock, lockResource, Constants.SYSTEM_ERROR);
			}
		});
	}

	/**
	 * 确保锁可用
	 * 
	 * @param lockId
	 *            锁id
	 * @throws LogicException
	 *             锁不可用(不存在)
	 */
	@Transactional(readOnly = true, propagation = Propagation.REQUIRED)
	public void ensureLockvailable(String lockId) throws LogicException {
		if (lockId != null && !findLock(lockId).isPresent()) {
			throw new LogicException("lock.notexists", "锁不存在");
		}
	}

	/**
	 * 获取所有的锁
	 * 
	 * @return 所有的锁
	 */
	@Transactional(readOnly = true, propagation = Propagation.REQUIRED)
	public List<Lock> allLock() {
		return providers.stream().map(LockProvider::getAllLocks).flatMap(List::stream)
				.collect(Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList));

	}

	private Optional<Lock> findLock(String id) {
		return providers.stream().map(provider -> provider.getLock(id)).filter(Optional::isPresent).map(Optional::get)
				.findFirst();
	}

	@Override
	public LockProviderRegistry register(LockProvider provider) {
		this.providers.add(provider);
		return this;
	}

}
