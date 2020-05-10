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

import com.couchbase.client.java.json.JsonArray;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.json.JsonValue;
import org.springframework.data.couchbase.core.support.TemplateUtils;
import org.springframework.data.couchbase.repository.query.StringBasedN1qlQueryParser;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.data.couchbase.core.query.Query;
import org.springframework.data.couchbase.core.query.QueryCriteria;

import com.couchbase.client.java.query.QueryOptions;
import com.couchbase.client.java.query.QueryScanConsistency;
import com.couchbase.client.java.query.ReactiveQueryResult;

/**
 * @author Michael Nitschinger
 * @author Michael Reiche
 */
public class ReactiveFindByQueryOperationSupport implements ReactiveFindByQueryOperation {

	private static final Query ALL_QUERY = new Query();

	private final ReactiveCouchbaseTemplate template;

	public ReactiveFindByQueryOperationSupport(final ReactiveCouchbaseTemplate template) {
		this.template = template;
	}

	@Override
	public <T> ReactiveFindByQuery<T> findByQuery(final Class<T> domainType) {
		return new ReactiveFindByQuerySupport<>(template, domainType, ALL_QUERY, QueryScanConsistency.NOT_BOUNDED);
	}

	static class ReactiveFindByQuerySupport<T> implements ReactiveFindByQuery<T> {

		private final ReactiveCouchbaseTemplate template;
		private final Class<T> domainType;
		private final Query query;
		private final QueryScanConsistency scanConsistency;

		ReactiveFindByQuerySupport(final ReactiveCouchbaseTemplate template, final Class<T> domainType, final Query query,
				final QueryScanConsistency scanConsistency) {
			this.template = template;
			this.domainType = domainType;
			this.query = query;
			this.scanConsistency = scanConsistency;
		}

		@Override
		public TerminatingFindByQuery<T> matching(Query query) {
			return new ReactiveFindByQuerySupport<>(template, domainType, query, scanConsistency);
		}

		@Override
		public TerminatingFindByQuery<T> raw(String queryString) {
			return null; // new ReactiveFindByQuerySupport<>(template, domainType, query, scanConsistency);
		}

		@Override
		public FindByQueryWithQuery<T> consistentWith(QueryScanConsistency scanConsistency) {
			return new ReactiveFindByQuerySupport<>(template, domainType, query, scanConsistency);
		}

		@Override
		public Mono<T> one() {
			return all().single();
		}

		@Override
		public Mono<T> first() {
			return all().next();
		}

		@Override
		public Flux<T> all() {
			return Flux.defer(() -> {
				String statement = assembleEntityQuery(false);
				return template.getCouchbaseClientFactory().getCluster().reactive().query(statement, buildQueryOptions())
						.onErrorMap(throwable -> {
							if (throwable instanceof RuntimeException) {
								return template.potentiallyConvertRuntimeException((RuntimeException) throwable);
							} else {
								return throwable;
							}
						}).flatMapMany(ReactiveQueryResult::rowsAsObject).map(row -> {
							String id = row.getString(TemplateUtils.SELECT_ID);
							long cas = row.getLong(TemplateUtils.SELECT_CAS);
							row.removeKey(TemplateUtils.SELECT_ID);
							row.removeKey(TemplateUtils.SELECT_CAS);
							return template.support().decodeEntity(id, row.toString(), cas, domainType);
						});
			});
		}

		@Override
		public Mono<Long> count() {
			return Mono.defer(() -> {
				String statement = assembleEntityQuery(true);
				return template.getCouchbaseClientFactory().getCluster().reactive().query(statement, buildQueryOptions())
						.onErrorMap(throwable -> {
							if (throwable instanceof RuntimeException) {
								return template.potentiallyConvertRuntimeException((RuntimeException) throwable);
							} else {
								return throwable;
							}
						}).flatMapMany(ReactiveQueryResult::rowsAsObject).map(row -> row.getLong(TemplateUtils.SELECT_COUNT)).next();
			});
		}

		@Override
		public Mono<Boolean> exists() {
			return count().map(count -> count > 0);
		}

		private String assembleEntityQuery(final boolean count) {

			String typeKey = template.getConverter().getTypeKey();
			String typeValue = template.support().getJavaNameForEntity(domainType);

			final StringBuilder statement = new StringBuilder();
			StringBasedN1qlQueryParser.N1qlSpelValues n1ql =
					StringBasedN1qlQueryParser.createN1qlSpelValues( template.getBucketName(), typeKey, typeValue, count );

			if(query.hasInlineN1qlQuery()) {
				query.appendInlineN1qlStatement(statement); // apply the string statement
			} else {
				query.appendString(statement,n1ql.selectEntity);
			}
			query.appendWhereString(statement,n1ql.filter); // typeKey = typeValue

			// To use generated parameters for literals
			// we need to figure out if we must use positional or named parameters
			// If we are using positional parameters, we need to start where
			// the inlineN1ql left off.
			int[] paramIndexPtr=null;
			JsonValue params=query.getParameters();
			if(params instanceof JsonArray) { // positional parameters
				if(query.hasInlineN1qlQuery()) {// could be some params in n1ql query string
					paramIndexPtr = new int[] { ((JsonArray) params).size() };
				} else {
					paramIndexPtr = new int[] { 0 };
				}
			} else { // named parameters or no parameters, no index required
				paramIndexPtr = new int[]{-1};
			}
			if(!query.hasInlineN1qlQuery()) // dont' use parameterization for non-inlineN1ql
				paramIndexPtr = null;

			query.appendWhere(statement, paramIndexPtr);
			query.appendSort(statement);
			query.appendSkipAndLimit(statement);
			return statement.toString();
		}

		private QueryOptions buildQueryOptions() {
			final QueryOptions options = QueryOptions.queryOptions();
			if(query.getParameters() != null) {
				if (query.getParameters() instanceof JsonArray) {
					options.parameters((JsonArray) query.getParameters());
				} else {
					options.parameters((JsonObject) query.getParameters());
				}
			}
			if (scanConsistency != null) {
				options.scanConsistency(scanConsistency);
			}

			return options;
		}
	}

}
