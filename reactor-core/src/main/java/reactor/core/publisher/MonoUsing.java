/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

import reactor.core.CoreSubscriber;
import reactor.core.Exceptions;
import reactor.core.Fuseable;

/**
 * Uses a resource, generated by a supplier for each individual Subscriber,
 * while streaming the values from a
 * Publisher derived from the same resource and makes sure the resource is released
 * if the sequence terminates or the Subscriber cancels.
 * <p>
 * <p>
 * Eager resource cleanup happens just before the source termination and exceptions
 * raised by the cleanup Consumer may override the terminal even. Non-eager
 * cleanup will drop any exception.
 *
 * @param <T> the value type streamed
 * @param <S> the resource type
 *
 * @see <a href="https://github.com/reactor/reactive-streams-commons">Reactive-Streams-Commons</a>
 */
/*
 * The following comment is a operator codification meant to be searchable.
 * See https://github.com/reactor/reactor-core/issues/1673 for a
 * complete description of each element codified and the associated values.
 *
 * {REQUEST_SHAPING}: NONE
 * {PREFETCH}: CALLABLE
 * {BUFFERING}: OPERATION-VALUE
 * {GEOMETRY}: SOURCE
 * {SOURCE}: NONE
 */
final class MonoUsing<T, S> extends Mono<T> implements Fuseable, SourceProducer<T>  {

	final Callable<S> resourceSupplier;

	final Function<? super S, ? extends Mono<? extends T>> sourceFactory;

	final Consumer<? super S> resourceCleanup;

	final boolean eager;

	MonoUsing(Callable<S> resourceSupplier,
			Function<? super S, ? extends Mono<? extends T>> sourceFactory,
			Consumer<? super S> resourceCleanup,
			boolean eager) {
		this.resourceSupplier =
				Objects.requireNonNull(resourceSupplier, "resourceSupplier");
		this.sourceFactory = Objects.requireNonNull(sourceFactory, "sourceFactory");
		this.resourceCleanup = Objects.requireNonNull(resourceCleanup, "resourceCleanup");
		this.eager = eager;
	}

	@Override
	@SuppressWarnings("unchecked")
	public void subscribe(CoreSubscriber<? super T> actual) {
		S resource;

		try {
			resource = resourceSupplier.call();
		}
		catch (Throwable e) {
			Operators.error(actual, Operators.onOperatorError(e, actual.currentContext()));
			return;
		}

		Mono<? extends T> p;

		try {
			p = Objects.requireNonNull(sourceFactory.apply(resource),
					"The sourceFactory returned a null value");
		}
		catch (Throwable e) {

			try {
				resourceCleanup.accept(resource);
			}
			catch (Throwable ex) {
				e = Exceptions.addSuppressed(ex, Operators.onOperatorError(e, actual.currentContext()));
			}

			Operators.error(actual, Operators.onOperatorError(e, actual.currentContext()));
			return;
		}

		if (p instanceof Fuseable) {
			p.subscribe(new FluxUsing.UsingFuseableSubscriber<>(actual,
					resourceCleanup,
					resource,
					eager));
		}
		else if (actual instanceof ConditionalSubscriber) {
			p.subscribe(new FluxUsing.UsingConditionalSubscriber<>((ConditionalSubscriber<? super
					T>) actual,
					resourceCleanup,
					resource,
					eager));
		}
		else {
			p.subscribe(new FluxUsing.UsingSubscriber<>(actual,
					resourceCleanup,
					resource,
					eager));
		}
	}

	@Override
	public Object scanUnsafe(Attr key) {
		return null; //no particular key to be represented, still useful in hooks
	}
}
