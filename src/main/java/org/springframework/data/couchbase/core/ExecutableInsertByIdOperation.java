/*
 * Copyright 2012-2020 the original author or authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.couchbase.core;

import java.util.Collection;

import com.couchbase.client.core.msg.kv.DurabilityLevel;
import com.couchbase.client.java.kv.PersistTo;
import com.couchbase.client.java.kv.ReplicateTo;

public interface ExecutableInsertByIdOperation {

	<T> ExecutableInsertById<T> insertById(Class<T> domainType);

	interface TerminatingInsertById<T> {

		T one(T object);

		Collection<? extends T> all(Collection<? extends T> objects);

	}

	interface InsertByIdWithCollection<T> extends TerminatingInsertById<T> {

		TerminatingInsertById<T> inCollection(String collection);
	}

	interface InsertByIdWithDurability<T> extends InsertByIdWithCollection<T> {

		InsertByIdWithCollection<T> withDurability(DurabilityLevel durabilityLevel);

		InsertByIdWithCollection<T> withDurability(PersistTo persistTo, ReplicateTo replicateTo);

	}

	interface ExecutableInsertById<T> extends InsertByIdWithDurability<T> {}

}
