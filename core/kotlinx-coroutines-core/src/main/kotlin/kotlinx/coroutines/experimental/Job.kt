/*
 * Copyright 2016-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kotlinx.coroutines.experimental

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.loop
import kotlinx.coroutines.experimental.internal.LockFreeLinkedListHead
import kotlinx.coroutines.experimental.internal.LockFreeLinkedListNode
import kotlinx.coroutines.experimental.internal.OpDescriptor
import kotlinx.coroutines.experimental.internal.unwrap
import kotlinx.coroutines.experimental.intrinsics.startCoroutineUndispatched
import kotlinx.coroutines.experimental.selects.SelectClause0
import kotlinx.coroutines.experimental.selects.SelectClause1
import kotlinx.coroutines.experimental.selects.SelectInstance
import kotlinx.coroutines.experimental.selects.select
import java.util.concurrent.Future
import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.CoroutineContext
import kotlin.coroutines.experimental.intrinsics.suspendCoroutineOrReturn

// --------------- core job interfaces ---------------

/**
 * A background job. Conceptually, a job is a cancellable thing with a simple life-cycle that
 * culminates in its completion. Jobs can be arranged into parent-child hierarchies where cancellation
 * or completion of parent immediately cancels all its children.
 *
 * The most basic instances of [Job] are created with [launch] coroutine builder or with a
 * `Job()` factory function.  Other coroutine builders and primitives like
 * [Deferred] also implement [Job] interface.
 *
 * A job has the following states:
 *
 * | **State**                               | [isActive] | [isCompleted] | [isCancelled] |
 * | --------------------------------------- | ---------- | ------------- | ------------- |
 * | _New_ (optional initial state)          | `false`    | `false`       | `false`       |
 * | _Active_ (default initial state)        | `true`     | `false`       | `false`       |
 * | _Completing_ (optional transient state) | `true`     | `false`       | `false`       |
 * | _Cancelling_ (optional transient state) | `false`    | `false`       | `true`        |
 * | _Cancelled_ (final state)               | `false`    | `true`        | `true`        |
 * | _Completed normally_ (final state)      | `false`    | `true`        | `false`       |
 *
 * Usually, a job is created in _active_ state (it is created and started). However, coroutine builders
 * that provide an optional `start` parameter create a coroutine in _new_ state when this parameter is set to
 * [CoroutineStart.LAZY]. Such a job can be made _active_ by invoking [start] or [join].
 *
 * A job can be _cancelled_ at any time with [cancel] function that forces it to transition to
 * _cancelling_ state immediately. Job that is not backed by a coroutine and does not have
 * [children][attachChild] becomes _cancelled_ on [cancel] immediately.
 * Otherwise, job becomes _cancelled_  when it finishes executing its code and
 * when all its children [complete][isCompleted].
 *
 * ```
 *                                                      wait children
 *    +-----+       start      +--------+   complete   +-------------+  finish  +-----------+
 *    | New | ---------------> | Active | -----------> | Completing  | -------> | Completed |
 *    +-----+                  +--------+              +-------------+          | normally  |
 *       |                         |                         |                  +-----------+
 *       | cancel                  | cancel                  | cancel
 *       V                         V                         |
 *  +-----------+   finish   +------------+                  |
 *  | Cancelled | <--------- | Cancelling | <----------------+
 *  |(completed)|            +------------+
 *  +-----------+
 * ```
 *
 * A job in the [coroutineContext][CoroutineScope.coroutineContext] represents the coroutine itself.
 * A job is active while the coroutine is working and job's cancellation aborts the coroutine when
 * the coroutine is suspended on a _cancellable_ suspension point by throwing [CancellationException].
 *
 * A job can have a _parent_ job. A job with a parent is cancelled when its parent is cancelled or completes.
 * Parent job waits for all its [children][attachChild] to complete in _completing_ or _cancelling_ state.
 * _Completing_ state is purely internal to the job. For an outside observer a _completing_ job is still active,
 * while internally it is waiting for its children.
 *
 * All functions on this interface and on all interfaces derived from it are **thread-safe** and can
 * be safely invoked from concurrent coroutines without external synchronization.
 */
public interface Job : CoroutineContext.Element {
    /**
     * Key for [Job] instance in the coroutine context.
     */
    public companion object Key : CoroutineContext.Key<Job> {
        /**
         * Creates a new job object in _active_ state.
         * It is optionally a child of a [parent] job.
         * @suppress **Deprecated**
         */
        @Deprecated("Replaced with top-level function", level = DeprecationLevel.HIDDEN)
        public operator fun invoke(parent: Job? = null): Job = Job(parent)

        init {
            /*
             * Here we make sure that CoroutineExceptionHandler is always initialized in advance, so
             * that if a coroutine fails due to StackOverflowError we don't fail to report this error
             * trying to initialize CoroutineExceptionHandler
             */
            CoroutineExceptionHandler
        }
    }

    // ------------ state query ------------

    /**
     * Returns `true` when this job is active -- it was already started and has not completed or cancelled yet.
     * The job that is waiting for its [children][attachChild] to complete is still considered to be active if it
     * was not cancelled.
     */
    public val isActive: Boolean

    /**
     * Returns `true` when this job has completed for any reason. A job that was cancelled and has
     * finished its execution is also considered complete. Job becomes complete only after
     * all its [children][attachChild] complete.
     */
    public val isCompleted: Boolean

    /**
     * Returns `true` if this job was [cancelled][cancel]. In the general case, it does not imply that the
     * job has already [completed][isCompleted] (it may still be cancelling whatever it was doing).
     */
    public val isCancelled: Boolean

    /**
     * Returns [CancellationException] that signals the completion of this job.
     *
     * It returns the original [cancel] cause if it is an instance of [CancellationException] or
     * an instance of [JobCancellationException] if this job was cancelled with a cause of
     * different type, was cancelled without a cause or had completed normally.
     *
     * This function throws [IllegalStateException] when invoked for an job that has not
     * [completed][isCompleted] nor [cancelled][isCancelled] yet.
     *
     * The [cancellable][suspendCancellableCoroutine] suspending functions throw this exception
     * when trying to suspend in the context of this job.
     */
    public fun getCancellationException(): CancellationException

    /**
     * @suppress **Deprecated**: Renamed to [getCancellationException]
     */
    @Deprecated("Renamed to getCancellationException", replaceWith = ReplaceWith("getCancellationException()"))
    public fun getCompletionException(): Throwable =
        getCancellationException()

    // ------------ state update ------------

    /**
     * Starts coroutine related to this job (if any) if it was not started yet.
     * The result `true` if this invocation actually started coroutine or `false`
     * if it was already started or completed.
     */
    public fun start(): Boolean

    /**
     * Cancels this job with an optional cancellation [cause]. The result is `true` if this job was
     * cancelled as a result of this invocation and `false` otherwise
     * (if it was already _completed_ or if it is [NonCancellable]).
     * Repeated invocations of this function have no effect and always produce `false`.
     *
     * When cancellation has a clear reason in the code, an instance of [CancellationException] should be created
     * at the corresponding original cancellation site and passed into this method to aid in debugging by providing
     * both the context of cancellation and text description of the reason.
     */
    public fun cancel(cause: Throwable? = null): Boolean

    // ------------ parent-child ------------

    /**
     * Attaches child job so that this job becomes its parent and
     * returns a handle that should be used to detach it.
     *
     * A parent-child relation has the following effect:
     * * Cancellation of parent with [cancel] immediately cancels all its children with the same cause.
     * * Parent cannot complete until all its children are complete. Parent waits for all its children to
     *   complete first in _completing_ or _cancelling_ state.
     *
     * A child must store the resulting [DisposableHandle] and [dispose][DisposableHandle.dispose] the attachment
     * to its parent on its own completion.
     *
     * Coroutine builders and job factory functions that accept `parent` [CoroutineContext] parameter
     * lookup a [Job] instance in the parent context and use this function to attach themselves as a child.
     * They also store a reference to the resulting [DisposableHandle] and dispose a handle when they complete.
     */
    public fun attachChild(child: Job): DisposableHandle

    /**
     * Cancels all [children][attachChild] jobs of this coroutine with the given [cause]. Unlike [cancel],
     * the state of this job itself is not affected.
     */
    public fun cancelChildren(cause: Throwable? = null)

    // ------------ state waiting ------------

    /**
     * Suspends coroutine until this job is complete. This invocation resumes normally (without exception)
     * when the job is complete for any reason and the [Job] of the invoking coroutine is still [active][isActive].
     * This function also [starts][Job.start] the corresponding coroutine if the [Job] was still in _new_ state.
     *
     * Note, that the job becomes complete only when all its [children][attachChild] are complete.
     *
     * This suspending function is cancellable and **always** checks for the cancellation of invoking coroutine's Job.
     * If the [Job] of the invoking coroutine is cancelled or completed when this
     * suspending function is invoked or while it is suspended, this function
     * throws [CancellationException].
     *
     * In particular, it means that a parent coroutine invoking `join` on a child coroutine that was started using
     * `launch(coroutineContext) { ... }` builder throws [CancellationException] if the child
     * had crashed, unless a non-standard [CoroutineExceptionHandler] if installed in the context.
     *
     * This function can be used in [select] invocation with [onJoin] clause.
     * Use [isCompleted] to check for completion of this job without waiting.
     *
     * There is [cancelAndJoin] function that combines an invocation of [cancel] and `join`.
     */
    public suspend fun join()

    /**
     * Clause for [select] expression of [join] suspending function that selects when the job is complete.
     * This clause never fails, even if the job completes exceptionally.
     */
    public val onJoin: SelectClause0

    // ------------ low-level state-notification ------------

    @Deprecated(message = "For binary compatibility", level = DeprecationLevel.HIDDEN)
    public fun invokeOnCompletion(handler: CompletionHandler): DisposableHandle

    @Deprecated(message = "For binary compatibility", level = DeprecationLevel.HIDDEN)
    public fun invokeOnCompletion(handler: CompletionHandler, onCancelling: Boolean): DisposableHandle

    /**
     * Registers handler that is **synchronously** invoked once on cancellation or completion of this job.
     * When job is already complete, then the handler is immediately invoked
     * with a job's cancellation cause or `null`. Otherwise, handler will be invoked once when this
     * job is cancelled or complete.
     *
     * Invocation of this handler on a transition to a transient _cancelling_ state
     * is controlled by [onCancelling] boolean parameter.
     * The handler is invoked on invocation of [cancel] when
     * job becomes _cancelling_ if [onCancelling] parameter is set to `true`. However,
     * when this [Job] is not backed by a coroutine, like [CompletableDeferred] or [CancellableContinuation]
     * (both of which do not posses a _cancelling_ state), then the value of [onCancelling] parameter is ignored.
     *
     * The resulting [DisposableHandle] can be used to [dispose][DisposableHandle.dispose] the
     * registration of this handler and release its memory if its invocation is no longer needed.
     * There is no need to dispose the handler after completion of this job. The references to
     * all the handlers are released when this job completes.
     *
     * Installed [handler] should not throw any exceptions. If it does, they will get caught,
     * wrapped into [CompletionHandlerException], and rethrown, potentially causing crash of unrelated code.
     *
     * **Note**: This function is a part of internal machinery that supports parent-child hierarchies
     * and allows for implementation of suspending functions that wait on the Job's state.
     * This function should not be used in general application code.
     * Implementations of `CompletionHandler` must be fast and _lock-free_.
     */
    public fun invokeOnCompletion(onCancelling: Boolean = false, handler: CompletionHandler): DisposableHandle

    // ------------ unstable internal API ------------

    /**
     * @suppress **Error**: Operator '+' on two Job objects is meaningless.
     * Job is a coroutine context element and `+` is a set-sum operator for coroutine contexts.
     * The job to the right of `+` just replaces the job the left of `+`.
     */
    @Suppress("DeprecatedCallableAddReplaceWith")
    @Deprecated(message = "Operator '+' on two Job objects is meaningless. " +
                    "Job is a coroutine context element and `+` is a set-sum operator for coroutine contexts. " +
                    "The job to the right of `+` just replaces the job the left of `+`.",
            level = DeprecationLevel.ERROR)
    public operator fun plus(other: Job) = other

    /**
     * Registration object for [invokeOnCompletion]. It can be used to [unregister] if needed.
     * There is no need to unregister after completion.
     * @suppress **Deprecated**: Replace with `DisposableHandle`
     */
    @Deprecated(message = "Replace with `DisposableHandle`",
        replaceWith = ReplaceWith("DisposableHandle"))
    public interface Registration {
        /**
         * Unregisters completion handler.
         * @suppress **Deprecated**: Replace with `dispose`
         */
        @Deprecated(message = "Replace with `dispose`",
            replaceWith = ReplaceWith("dispose()"))
        public fun unregister()
    }
}

/**
 * Creates a new job object in an _active_ state.
 * It is optionally a child of a [parent] job.
 */
@Suppress("FunctionName")
public fun Job(parent: Job? = null): Job = JobImpl(parent)

/**
 * A handle to an allocated object that can be disposed to make it eligible for garbage collection.
 */
@Suppress("DEPRECATION") // todo: remove when Job.Registration is removed
public interface DisposableHandle : Job.Registration {
    /**
     * Disposes the corresponding object, making it eligible for garbage collection.
     * Repeated invocation of this function has no effect.
     */
    public fun dispose()

    /**
     * Unregisters completion handler.
     * @suppress **Deprecated**: Replace with `dispose`
     */
    @Deprecated(message = "Replace with `dispose`",
        replaceWith = ReplaceWith("dispose()"))
    public override fun unregister() = dispose()
}

/**
 * Handler for [Job.invokeOnCompletion].
 *
 * Installed handler should not throw any exceptions. If it does, they will get caught,
 * wrapped into [CompletionHandlerException], and rethrown, potentially causing crash of unrelated code.
 *
 * **Note**: This type is a part of internal machinery that supports parent-child hierarchies
 * and allows for implementation of suspending functions that wait on the Job's state.
 * This type should not be used in general application code.
 * Implementations of `CompletionHandler` must be fast and _lock-free_.
 */
public typealias CompletionHandler = (cause: Throwable?) -> Unit

/**
 * This exception gets thrown if an exception is caught while processing [CompletionHandler] invocation for [Job].
 */
public class CompletionHandlerException(message: String, cause: Throwable) : RuntimeException(message, cause)

/**
 * Thrown by cancellable suspending functions if the [Job] of the coroutine is cancelled while it is suspending.
 */
public typealias CancellationException = java.util.concurrent.CancellationException

/**
 * Thrown by cancellable suspending functions if the [Job] of the coroutine is cancelled or completed
 * without cause, or with a cause or exception that is not [CancellationException]
 * (see [Job.getCancellationException]).
 */
public class JobCancellationException(
    message: String,
    cause: Throwable?,
    /**
     * The job that was cancelled.
     */
    public val job: Job
) : CancellationException(message) {
    init { if (cause != null) initCause(cause) }
    override fun toString(): String = "${super.toString()}; job=$job"
    override fun equals(other: Any?): Boolean =
        other === this ||
            other is JobCancellationException && other.message == message && other.job == job && other.cause == cause
    override fun hashCode(): Int =
        (message!!.hashCode() * 31 + job.hashCode()) * 31 + (cause?.hashCode() ?: 0)
}

/**
 * Represents an exception in the coroutine that was not caught by it and was not expected to be thrown.
 * This happens when coroutine is cancelled, but it completes with the different exception than its cancellation
 * cause was.
 */
public class UnexpectedCoroutineException(message: String, cause: Throwable) : IllegalStateException(message, cause)

/**
 * Unregisters a specified [registration] when this job is complete.
 *
 * This is a shortcut for the following code with slightly more efficient implementation (one fewer object created).
 * ```
 * invokeOnCompletion { registration.unregister() }
 * ```
 * @suppress: **Deprecated**: Renamed to `disposeOnCompletion`.
 */
@Deprecated(message = "Renamed to `disposeOnCompletion`",
    replaceWith = ReplaceWith("disposeOnCompletion(registration)"))
public fun Job.unregisterOnCompletion(registration: DisposableHandle): DisposableHandle =
    invokeOnCompletion(handler = DisposeOnCompletion(this, registration))

/**
 * Disposes a specified [handle] when this job is complete.
 *
 * This is a shortcut for the following code with slightly more efficient implementation (one fewer object created).
 * ```
 * invokeOnCompletion { handle.dispose() }
 * ```
 */
public fun Job.disposeOnCompletion(handle: DisposableHandle): DisposableHandle =
    invokeOnCompletion(handler = DisposeOnCompletion(this, handle))

/**
 * Cancels a specified [future] when this job is complete.
 *
 * This is a shortcut for the following code with slightly more efficient implementation (one fewer object created).
 * ```
 * invokeOnCompletion { future.cancel(false) }
 * ```
 */
public fun Job.cancelFutureOnCompletion(future: Future<*>): DisposableHandle =
    invokeOnCompletion(handler = CancelFutureOnCompletion(this, future))

/**
 * Cancels the job and suspends invoking coroutine until the cancelled job is complete.
 *
 * This suspending function is cancellable and **always** checks for the cancellation of invoking coroutine's Job.
 * If the [Job] of the invoking coroutine is cancelled or completed when this
 * suspending function is invoked or while it is suspended, this function
 * throws [CancellationException].
 *
 * In particular, it means that a parent coroutine invoking `cancelAndJoin` on a child coroutine that was started using
 * `launch(coroutineContext) { ... }` builder throws [CancellationException] if the child
 * had crashed, unless a non-standard [CoroutineExceptionHandler] if installed in the context.
 *
 * This is a shortcut for the invocation of [cancel][Job.cancel] followed by [join][Job.join].
 */
public suspend fun Job.cancelAndJoin() {
    cancel()
    return join()
}

/**
 * Cancels [Job] of this context with an optional cancellation [cause]. The result is `true` if the job was
 * cancelled as a result of this invocation and `false` if there is no job in the context or if it was already
 * cancelled or completed. See [Job.cancel] for details.
 */
public fun CoroutineContext.cancel(cause: Throwable? = null): Boolean =
    this[Job]?.cancel(cause) ?: false

/**
 * Cancels all children of the [Job] in this context with an optional cancellation [cause].
 * It does not do anything if there is no job in the context or it has no children.
 * See [Job.cancelChildren] for details.
 */
public fun CoroutineContext.cancelChildren(cause: Throwable? = null) {
   this[Job]?.cancelChildren(cause)
}

/**
 * @suppress **Deprecated**: `join` is now a member function of `Job`.
 */
@Suppress("EXTENSION_SHADOWED_BY_MEMBER", "DeprecatedCallableAddReplaceWith")
@Deprecated(message = "`join` is now a member function of `Job`")
public suspend fun Job.join() = this.join()

/**
 * No-op implementation of [Job.Registration].
 */
@Deprecated(message = "Replace with `NonDisposableHandle`",
    replaceWith = ReplaceWith("NonDisposableHandle"))
@Suppress("unused")
typealias EmptyRegistration = NonDisposableHandle

/**
 * No-op implementation of [DisposableHandle].
 */
public object NonDisposableHandle : DisposableHandle {
    /** Does not do anything. */
    override fun dispose() {}

    /** Returns "NonDisposableHandle" string. */
    override fun toString(): String = "NonDisposableHandle"
}

// --------------- helper classes to simplify job implementation

/**
 * A concrete implementation of [Job]. It is optionally a child to a parent job.
 * This job is cancelled when the parent is complete, but not vise-versa.
 *
 * This is an open class designed for extension by more specific classes that might augment the
 * state and mare store addition state information for completed jobs, like their result values.
 *
 * @param active when `true` the job is created in _active_ state, when `false` in _new_ state. See [Job] for details.
 * @suppress **This is unstable API and it is subject to change.**
 */
public open class JobSupport(active: Boolean) : Job, SelectClause0, SelectClause1<Any?> {
    override val key: CoroutineContext.Key<*> get() = Job

    /*
       === Internal states ===

       name       state class    public state  description
       ------     ------------   ------------  -----------
       EMPTY_N    EmptyNew     : New           no listeners
       EMPTY_A    EmptyActive  : Active        no listeners
       SINGLE     JobNode      : Active        a single listener
       SINGLE+    JobNode      : Active        a single listener + NodeList added as its next
       LIST_N     NodeList     : New           a list of listeners (promoted once, does not got back to EmptyNew)
       LIST_A     NodeList     : Active        a list of listeners (promoted once, does not got back to JobNode/EmptyActive)
       COMPLETING Finishing    : Completing    has a list of listeners (promoted once from LIST_*)
       CANCELLING Finishing    : Cancelling    has a list of listeners (promoted once from LIST_*)
       FINAL_C    Cancelled    : Cancelled     cancelled (final state)
       FINAL_F    Failed       : Completed     failed for other reason (final state)
       FINAL_R    <any>        : Completed     produced some result

       === Transitions ===

           New states      Active states       Inactive states
           
          +---------+       +---------+                          }
          | EMPTY_N | --+-> | EMPTY_A | ----+                    } Empty states
          +---------+   |   +---------+     |                    }
               |        |     |     ^       |    +----------+
               |        |     |     |       +--> |  FINAL_* |
               |        |     V     |       |    +----------+
               |        |   +---------+     |                    }
               |        |   | SINGLE  | ----+                    } JobNode states
               |        |   +---------+     |                    }
               |        |        |          |                    }
               |        |        V          |                    }
               |        |   +---------+     |                    }
               |        +-- | SINGLE+ | ----+                    }
               |            +---------+     |                    }
               |                 |          |
               V                 V          |
          +---------+       +---------+     |                    }
          | LIST_N  | ----> | LIST_A  | ----+                    } NodeList states
          +---------+       +---------+     |                    }
             |   |             |   |        |
             |   |    +--------+   |        |
             |   |    |            V        |
             |   |    |    +------------+   |   +------------+   }
             |   +-------> | COMPLETING | --+-- | CANCELLING |   } Finishing states
             |        |    +------------+       +------------+   }
             |        |         |                    ^
             |        |         |                    |
             +--------+---------+--------------------+


       This state machine and its transition matrix are optimized for the common case when job is created in active
       state (EMPTY_A) and at most one completion listener is added to it during its life-time.

       Note, that the actual `_state` variable can also be a reference to atomic operation descriptor `OpDescriptor`
     */

    // Note: use shared objects while we have no listeners
    private val _state = atomic<Any?>(if (active) EmptyActive else EmptyNew)

    @Volatile
    private var parentHandle: DisposableHandle? = null

    // ------------ initialization ------------

    /**
     * Initializes parent job.
     * It shall be invoked at most once after construction after all other initialization.
     */
    public fun initParentJob(parent: Job?) {
        check(parentHandle == null)
        if (parent == null) {
            parentHandle = NonDisposableHandle
            return
        }
        parent.start() // make sure the parent is started
        val handle = parent.attachChild(this)
        parentHandle = handle
        // now check our state _after_ registering (see updateState order of actions)
        if (isCompleted) handle.dispose()
    }

    // ------------ state query ------------

    /**
     * Returns current state of this job.
     */
    protected val state: Any? get() {
        _state.loop { state -> // helper loop on state (complete in-progress atomic operations)
            if (state !is OpDescriptor) return state
            state.perform(this)
        }
    }

    /**
     * @suppress **This is unstable API and it is subject to change.**
     */
    protected inline fun loopOnState(block: (Any?) -> Unit): Nothing {
        while (true) {
            block(state)
        }
    }

    public final override val isActive: Boolean get() {
        val state = this.state
        return state is Incomplete && state.isActive
    }

    public final override val isCompleted: Boolean get() = state !is Incomplete

    public final override val isCancelled: Boolean get() {
        val state = this.state
        return state is Cancelled || (state is Finishing && state.cancelled != null)
    }

    // ------------ state update ------------

    /**
     * Updates current [state] of this job. Returns `false` if current state is not equal to expected.
     * @suppress **This is unstable API and it is subject to change.**
     */
    internal fun updateState(expect: Incomplete, proposedUpdate: Any?, mode: Int): Boolean {
        val update = coerceProposedUpdate(expect, proposedUpdate)
        if (!tryUpdateState(expect, update)) return false
        completeUpdateState(expect, update, mode)
        // if an exceptional completion was suppressed (because cancellation was in progress), then report it separately
        if (proposedUpdate is CompletedExceptionally && proposedUpdate.cause != null && !incorporatedCause(update, proposedUpdate.cause)) {
            handleException(UnexpectedCoroutineException("Unexpected exception while cancellation is in progress; job=$this", proposedUpdate.cause))
        }
        return true
    }

    /**
     * Checks if the cause that was proposed for state update is consistent with the resulting updated state
     * and not exception information was lost. The key observation here is that [getCancellationException] wraps
     * exceptions that are not [CancellationException] into an instance of [JobCancellationException] and we allow
     * that [JobCancellationException] to be unwrapped again when it reaches the coroutine that was cancelled.
     *
     * NOTE: equality comparison of exceptions is performed here by design, see equals of JobCancellationException
     */
    private fun incorporatedCause(update: Any?, proposedCause: Throwable) =
        update is CompletedExceptionally && update.exception.let { ex ->
            ex == proposedCause || proposedCause is JobCancellationException && ex == proposedCause.cause
        }

    // when Job is in Cancelling state, it can only be promoted to Cancelled state with the same cause
    // however, null cause can be replaced with more specific JobCancellationException (that contains better stack trace)
    private fun coerceProposedUpdate(expect: Incomplete, proposedUpdate: Any?): Any? =
        if (expect is Finishing && expect.cancelled != null && !correspondinglyCancelled(expect.cancelled, proposedUpdate))
            expect.cancelled else proposedUpdate

    private fun correspondinglyCancelled(cancelled: Cancelled, proposedUpdate: Any?): Boolean {
        if (proposedUpdate !is Cancelled) return false
        return proposedUpdate.cause === cancelled.cause ||
            proposedUpdate.cause is JobCancellationException && cancelled.cause == null
    }

    /**
     * Tries to initiate update of the current [state] of this job.
     * @suppress **This is unstable API and it is subject to change.**
     */
    internal fun tryUpdateState(expect: Incomplete, update: Any?): Boolean  {
        require(update !is Incomplete) // only incomplete -> completed transition is allowed
        if (!_state.compareAndSet(expect, update)) return false
        // Unregister from parent job
        parentHandle?.dispose() // volatile read parentHandle _after_ state was updated
        return true // continues in completeUpdateState
    }

    /**
     * Completes update of the current [state] of this job.
     * @suppress **This is unstable API and it is subject to change.**
     */
    internal fun completeUpdateState(expect: Incomplete, update: Any?, mode: Int) {
        // Invoke completion handlers
        val exceptionally = update as? CompletedExceptionally
        val cause = exceptionally?.cause
        if (expect is JobNode<*>) { // SINGLE/SINGLE+ state -- one completion handler (common case)
            try {
                expect.invoke(cause)
            } catch (ex: Throwable) {
                handleException(CompletionHandlerException("Exception in completion handler $expect for $this", ex))
            }
        } else {
            expect.list?.notifyCompletion(cause)
        }
        // Do overridable processing after completion handlers
        if (!expect.isCancelling) onCancellation(exceptionally) // only notify when was not cancelling before
        afterCompletion(update, mode)
    }

    private inline fun <reified T: JobNode<*>> notifyHandlers(list: NodeList, cause: Throwable?) {
        var exception: Throwable? = null
        list.forEach<T> { node ->
            try {
                node.invoke(cause)
            } catch (ex: Throwable) {
                exception?.apply { addSuppressed(ex) } ?: run {
                    exception =  CompletionHandlerException("Exception in completion handler $node for $this", ex)
                }
            }
        }
        exception?.let { handleException(it) }
    }

    private fun NodeList.notifyCompletion(cause: Throwable?) =
        notifyHandlers<JobNode<*>>(this, cause)

    private fun notifyCancellation(list: NodeList, cause: Throwable?) =
        notifyHandlers<JobCancellationNode<*>>(list, cause)

    public final override fun start(): Boolean {
        loopOnState { state ->
            when (startInternal(state)) {
                FALSE -> return false
                TRUE -> return true
            }
        }
    }

    // returns: RETRY/FALSE/TRUE:
    //   FALSE when not new,
    //   TRUE  when started
    //   RETRY when need to retry
    private fun startInternal(state: Any?): Int {
        when (state) {
            is Empty -> { // EMPTY_X state -- no completion handlers
                if (state.isActive) return FALSE // already active
                if (!_state.compareAndSet(state, EmptyActive)) return RETRY
                onStart()
                return TRUE
            }
            is NodeList -> { // LIST -- a list of completion handlers (either new or active)
                return state.tryMakeActive().also { result ->
                    if (result == TRUE) onStart()
                }
            }
            else -> return FALSE // not a new state
        }
    }

    /**
     * Override to provide the actual [start] action.
     */
    protected open fun onStart() {}

    public final override fun getCancellationException(): CancellationException {
        val state = this.state
        return when {
            state is Finishing && state.cancelled != null ->
                state.cancelled.exception.toCancellationException("Job is being cancelled")
            state is Incomplete ->
                error("Job was not completed or cancelled yet: $this")
            state is CompletedExceptionally ->
                state.exception.toCancellationException("Job has failed")
            else -> JobCancellationException("Job has completed normally", null, this)
        }
    }

    private fun Throwable.toCancellationException(message: String): CancellationException =
        this as? CancellationException ?: JobCancellationException(message, this, this@JobSupport)

    /**
     * Returns the cause that signals the completion of this job -- it returns the original
     * [cancel] cause or **`null` if this job had completed
     * normally or was cancelled without a cause**. This function throws
     * [IllegalStateException] when invoked for an job that has not [completed][isCompleted] nor
     * [isCancelled] yet.
     */
    protected fun getCompletionCause(): Throwable? {
        val state = this.state
        return when {
            state is Finishing && state.cancelled != null -> state.cancelled.cause
            state is Incomplete -> error("Job was not completed or cancelled yet")
            state is CompletedExceptionally -> state.cause
            else -> null
        }
    }

    @Suppress("OverridingDeprecatedMember")
    public final override fun invokeOnCompletion(handler: CompletionHandler): DisposableHandle =
        installHandler(handler, onCancelling = false)

    @Suppress("OverridingDeprecatedMember")
    public final override fun invokeOnCompletion(handler: CompletionHandler, onCancelling: Boolean): DisposableHandle =
        installHandler(handler, onCancelling = onCancelling && hasCancellingState)

    public final override fun invokeOnCompletion(onCancelling: Boolean, handler: CompletionHandler): DisposableHandle =
        installHandler(handler, onCancelling = onCancelling && hasCancellingState)

    private fun installHandler(handler: CompletionHandler, onCancelling: Boolean): DisposableHandle {
        var nodeCache: JobNode<*>? = null
        loopOnState { state ->
            when (state) {
                is Empty -> { // EMPTY_X state -- no completion handlers
                    if (state.isActive) {
                        // try move to SINGLE state
                        val node = nodeCache ?: makeNode(handler, onCancelling).also { nodeCache = it }
                        if (_state.compareAndSet(state, node)) return node
                    } else
                        promoteEmptyToNodeList(state) // that way we can add listener for non-active coroutine
                }
                is Incomplete -> {
                    val list = state.list
                    if (list == null) { // SINGLE/SINGLE+
                        promoteSingleToNodeList(state as JobNode<*>)
                    } else {
                        if (state is Finishing && state.cancelled != null && onCancelling) {
                            // installing cancellation handler on job that is being cancelled
                            handler((state as? CompletedExceptionally)?.exception)
                            return NonDisposableHandle
                        }
                        val node = nodeCache ?: makeNode(handler, onCancelling).also { nodeCache = it }
                        if (addLastAtomic(state, list, node)) return node
                    }
                }
                else -> { // is complete
                    handler((state as? CompletedExceptionally)?.exception)
                    return NonDisposableHandle
                }
            }
        }
    }

    private fun makeNode(handler: CompletionHandler, onCancelling: Boolean): JobNode<*> =
        if (onCancelling)
            (handler as? JobCancellationNode<*>)?.also { require(it.job === this) }
                ?: InvokeOnCancellation(this, handler)
        else
            (handler as? JobNode<*>)?.also { require(it.job === this && (!hasCancellingState || it !is JobCancellationNode)) }
                ?: InvokeOnCompletion(this, handler)


    private fun addLastAtomic(expect: Any, list: NodeList, node: JobNode<*>) =
        list.addLastIf(node) { this.state === expect }

    private fun promoteEmptyToNodeList(state: Empty) {
        // try to promote it to list in new state
        _state.compareAndSet(state, NodeList(state.isActive))
    }

    private fun promoteSingleToNodeList(state: JobNode<*>) {
        // try to promote it to list (SINGLE+ state)
        state.addOneIfEmpty(NodeList(active = true))
        // it must be in SINGLE+ state or state has changed (node could have need removed from state)
        val list = state.next // either NodeList or somebody else won the race, updated state
        // just attempt converting it to list if state is still the same, then we'll continue lock-free loop
        _state.compareAndSet(state, list)
    }

    final override suspend fun join() {
        if (!joinInternal()) { // fast-path no wait
            return suspendCoroutineOrReturn { cont ->
                cont.context.checkCompletion()
                Unit // do not suspend
            }
        }
        return joinSuspend() // slow-path wait
    }

    private fun joinInternal(): Boolean {
        loopOnState { state ->
            if (state !is Incomplete) return false // not active anymore (complete) -- no need to wait
            if (startInternal(state) >= 0) return true // wait unless need to retry
        }
    }

    private suspend fun joinSuspend() = suspendCancellableCoroutine<Unit> { cont ->
        cont.disposeOnCompletion(invokeOnCompletion(handler = ResumeOnCompletion(this, cont)))
    }

    final override val onJoin: SelectClause0
        get() = this

    // registerSelectJoin
    final override fun <R> registerSelectClause0(select: SelectInstance<R>, block: suspend () -> R) {
        // fast-path -- check state and select/return if needed
        loopOnState { state ->
            if (select.isSelected) return
            if (state !is Incomplete) {
                // already complete -- select result
                if (select.trySelect(null)) {
                    select.completion.context.checkCompletion() // always check for our completion
                    block.startCoroutineUndispatched(select.completion)
                }
                return
            }
            if (startInternal(state) == 0) {
                // slow-path -- register waiter for completion
                select.disposeOnSelect(invokeOnCompletion(handler = SelectJoinOnCompletion(this, select, block)))
                return
            }
        }
    }

    internal fun removeNode(node: JobNode<*>) {
        // remove logic depends on the state of the job
        loopOnState { state ->
            when (state) {
                is JobNode<*> -> { // SINGE/SINGLE+ state -- one completion handler
                    if (state !== node) return // a different job node --> we were already removed
                    // try remove and revert back to empty state
                    if (_state.compareAndSet(state, EmptyActive)) return
                }
                is Incomplete -> { // may have a list of completion handlers
                    // remove node from the list if there is a list
                    if (state.list != null) node.remove()
                    return
                }
                else -> return // it is complete and does not have any completion handlers
            }
        }
    }

    protected open val hasCancellingState: Boolean get() = false

    public final override fun cancel(cause: Throwable?): Boolean =
        if (hasCancellingState)
            makeCancelling(cause) else
            makeCancelled(cause)

    // we will be dispatching coroutine to process its cancellation exception, so there is no need for
    // an extra check for Job status in MODE_CANCELLABLE
    private fun updateStateCancelled(state: Incomplete, cause: Throwable?) =
        updateState(state, Cancelled(this, cause), mode = MODE_ATOMIC_DEFAULT)

    // transitions to Cancelled state
    private fun makeCancelled(cause: Throwable?): Boolean {
        loopOnState { state ->
            if (state !is Incomplete) return false // quit if already complete
            if (updateStateCancelled(state, cause)) return true
        }
    }

    // transitions to Cancelling state
    private fun makeCancelling(cause: Throwable?): Boolean {
        loopOnState { state ->
            when (state) {
                is Empty -> { // EMPTY_X state -- no completion handlers
                    if (state.isActive) {
                        promoteEmptyToNodeList(state) // this way can wrap it into Cancelling on next pass
                    } else {
                        // cancelling a non-started coroutine makes it immediately cancelled
                        // (and we have no listeners to notify which makes it very simple)
                        if (updateStateCancelled(state, cause)) return true
                    }
                }
                is JobNode<*> -> { // SINGLE/SINGLE+ state -- one completion handler
                    promoteSingleToNodeList(state)
                }
                is NodeList -> { // LIST -- a list of completion handlers (either new or active)
                    if (state.isActive) {
                        if (tryMakeCancelling(state, state.list, cause)) return true
                    } else {
                        // cancelling a non-started coroutine makes it immediately cancelled
                        if (updateStateCancelled(state, cause))
                            return true
                    }
                }
                is Finishing -> { // Completing/Cancelling the job, may cancel
                    if (state.cancelled != null) return false // already cancelling
                    if (tryMakeCancelling(state, state.list, cause)) return true
                }
                else -> { // is inactive
                    return false
                }
            }
        }
    }

    // try make expected state in cancelling on the condition that we're still in this state
    private fun tryMakeCancelling(expect: Incomplete, list: NodeList, cause: Throwable?): Boolean {
        val cancelled = Cancelled(this, cause)
        if (!_state.compareAndSet(expect, Finishing(list, cancelled, false))) return false
        notifyCancellation(list, cause)
        onCancellation(cancelled)
        return true
    }

    /**
     * Returns:
     * * `true` if state was updated to completed/cancelled;
     * * `false` if made completing or it is cancelling and is waiting for children.
     *
     * @throws IllegalStateException if job is already complete or completing
     * @suppress **This is unstable API and it is subject to change.**
     */
    internal fun makeCompleting(proposedUpdate: Any?, mode: Int): Boolean {
        loopOnState { state ->
            if (state !is Incomplete)
                throw IllegalStateException("Job $this is already complete, but is being completed with $proposedUpdate", proposedUpdate.exceptionOrNull)
            if (state is Finishing && state.completing)
                throw IllegalStateException("Job $this is already completing, but is being completed with $proposedUpdate", proposedUpdate.exceptionOrNull)
            val waitChild: Child = firstChild(state) ?: // or else complete immediately
                if (updateState(state, proposedUpdate, mode)) return true else return@loopOnState
            // switch to completing state
            if (state is JobNode<*>) {
                // must promote to list to make completing & retry
                promoteSingleToNodeList(state)
            } else {
                val completing = Finishing(state.list!!, (state as? Finishing)?.cancelled, true)
                if (_state.compareAndSet(state, completing)) {
                    waitForChild(waitChild, proposedUpdate)
                    return false
                }
            }
        }
    }

    private val Any?.exceptionOrNull: Throwable?
        get() = (this as? CompletedExceptionally)?.exception

    private fun firstChild(state: Incomplete) =
        state as? Child ?: state.list?.nextChild()

    private fun waitForChild(waitChild: Child, proposedUpdate: Any?) {
        waitChild.child.invokeOnCompletion(handler = ChildCompletion(this, waitChild, proposedUpdate))
    }

    internal fun continueCompleting(lastChild: Child, proposedUpdate: Any?) {
        loopOnState { state ->
            if (state !is Finishing)
                throw IllegalStateException("Job $this is found in expected state while completing with $proposedUpdate", proposedUpdate.exceptionOrNull)
            // figure out if we need to wait for next child
            val waitChild = lastChild.nextChild() ?: // or else no more children
                if (updateState(state, proposedUpdate, MODE_ATOMIC_DEFAULT)) return else return@loopOnState
            // wait for next child
            waitForChild(waitChild, proposedUpdate)
            return
        }
    }

    private fun LockFreeLinkedListNode.nextChild(): Child? {
        var cur = this
        while (cur.isRemoved) cur = cur.prev.unwrap() // rollback to prev non-removed (or list head)
        while (true) {
            cur = cur.next.unwrap()
            if (cur.isRemoved) continue
            if (cur is Child) return cur
            if (cur is NodeList) return null // checked all -- no more children
        }
    }

    override fun attachChild(child: Job): DisposableHandle =
        invokeOnCompletion(onCancelling = true, handler = Child(this, child))

    public override fun cancelChildren(cause: Throwable?) {
        val state = this.state
        when (state) {
            is Child -> state.child.cancel(cause)
            is Incomplete -> state.list?.cancelChildrenList(cause)
        }
    }

    private fun NodeList.cancelChildrenList(cause: Throwable?) {
        forEach<Child> { it.child.cancel(cause) }
    }

    /**
     * Override to process any exceptions that were encountered while invoking completion handlers
     * installed via [invokeOnCompletion].
     */
    protected open fun handleException(exception: Throwable) {
        throw exception
    }

    /**
     * It is invoked once when job is cancelled or is completed, similarly to [invokeOnCompletion] with
     * `onCancelling` set to `true`.
     * @param exceptionally not null when the the job was cancelled or completed exceptionally,
     *               null when it has completed normally.
     * @suppress **This is unstable API and it is subject to change.**
     */
    protected open fun onCancellation(exceptionally: CompletedExceptionally?) {}

    /**
     * Override for post-completion actions that need to do something with the state.
     * @param mode completion mode.
     * @suppress **This is unstable API and it is subject to change.**
     */
    protected open fun afterCompletion(state: Any?, mode: Int) {}

    // for nicer debugging
    override final fun toString(): String =
        "${nameString()}{${stateString()}}@${Integer.toHexString(System.identityHashCode(this))}"

    /**
     * @suppress **This is unstable API and it is subject to change.**
     */
    protected open fun nameString(): String = this::class.java.simpleName

    private fun stateString(): String {
        val state = this.state
        return when (state) {
            is Finishing -> buildString {
                if (state.cancelled != null) append("Cancelling")
                if (state.completing) append("Completing")
            }
            is Incomplete -> if (state.isActive) "Active" else "New"
            is Cancelled -> "Cancelled"
            is CompletedExceptionally -> "CompletedExceptionally"
            else -> "Completed"
        }
    }

    /**
     * @suppress **This is unstable API and it is subject to change.**
     */
    internal interface Incomplete {
        val isActive: Boolean
        val list: NodeList? // is null only for Empty and JobNode incomplete state objects
    }

    // Cancelling or Completing
    private class Finishing(
        override val list: NodeList,
        @JvmField val cancelled: Cancelled?, /* != null when cancelling */
        @JvmField val completing: Boolean /* true when completing */
    ) : Incomplete {
        override val isActive: Boolean get() = cancelled == null
    }

    private val Incomplete.isCancelling: Boolean
        get() = this is Finishing && cancelled != null

    /**
     * @suppress **This is unstable API and it is subject to change.**
     */
    internal class NodeList(
        active: Boolean
    ) : LockFreeLinkedListHead(), Incomplete {
        private val _active = atomic(if (active) 1 else 0)

        override val isActive: Boolean get() = _active.value != 0
        override val list: NodeList get() = this

        fun tryMakeActive(): Int {
            if (_active.value != 0) return FALSE
            if (_active.compareAndSet(0, 1)) return RETRY
            return TRUE
        }

        override fun toString(): String = buildString {
            append("List")
            append(if (isActive) "{Active}" else "{New}")
            append("[")
            var first = true
            this@NodeList.forEach<JobNode<*>> { node ->
                if (first) first = false else append(", ")
                append(node)
            }
            append("]")
        }
    }

    /**
     * Class for a [state] of a job that had completed exceptionally, including cancellation.
     *
     * @param cause the exceptional completion cause. If `cause` is null, then an exception is
     *        if created via [createException] on first get from [exception] property.
     * @param allowNullCause if `null` cause is allowed.
     */
    public open class CompletedExceptionally protected constructor(
        public @JvmField val cause: Throwable?,
        allowNullCause: Boolean
    ) {
        /**
         * Creates exceptionally completed state.
         * @param cause the exceptional completion cause.
         */
        public constructor(cause: Throwable) : this(cause, false)

        @Volatile
        private var _exception: Throwable? = cause // will materialize JobCancellationException on first need

        init {
            require(allowNullCause || cause != null) { "Null cause is not allowed" }
        }

        /**
         * Returns completion exception.
         */
        public val exception: Throwable get() =
            _exception ?: // atomic read volatile var or else create new
                createException().also { _exception = it }

        protected open fun createException(): Throwable = error("Completion exception was not specified")

        override fun toString(): String = "${this::class.java.simpleName}[$exception]"
    }

    /**
     * A specific subclass of [CompletedExceptionally] for cancelled jobs.
     *
     * @param job the job that was cancelled.
     * @param cause the exceptional completion cause. If `cause` is null, then a [JobCancellationException]
     *        if created on first get from [exception] property.
     */
    public class Cancelled(
        private val job: Job,
        cause: Throwable?
    ) : CompletedExceptionally(cause, true) {
        override fun createException(): Throwable = JobCancellationException("Job was cancelled normally", null, job)
    }

    /*
     * =================================================================================================
     * This is ready-to-use implementation for Deferred interface.
     * However, it is not type-safe. Conceptually it just exposes the value of the underlying
     * completed state as `Any?`
     * =================================================================================================
     */

    public val isCompletedExceptionally: Boolean get() = state is CompletedExceptionally

    public fun getCompletionExceptionOrNull(): Throwable? {
        val state = this.state
        check(state !is Incomplete) { "This job has not completed yet" }
        return state.exceptionOrNull
    }

    protected fun getCompletedInternal(): Any? {
        val state = this.state
        check(state !is Incomplete) { "This job has not completed yet" }
        if (state is CompletedExceptionally) throw state.exception
        return state
    }

    protected suspend fun awaitInternal(): Any? {
        // fast-path -- check state (avoid extra object creation)
        while(true) { // lock-free loop on state
            val state = this.state
            if (state !is Incomplete) {
                // already complete -- just return result
                if (state is CompletedExceptionally) throw state.exception
                return state

            }
            if (startInternal(state) >= 0) break // break unless needs to retry
        }
        return awaitSuspend() // slow-path
    }

    private suspend fun awaitSuspend(): Any? = suspendCancellableCoroutine { cont ->
        cont.disposeOnCompletion(invokeOnCompletion {
            val state = this.state
            check(state !is Incomplete)
            if (state is CompletedExceptionally)
                cont.resumeWithException(state.exception)
            else
                cont.resume(state)
        })
    }

    // registerSelectAwaitInternal
    override fun <R> registerSelectClause1(select: SelectInstance<R>, block: suspend (Any?) -> R) {
        // fast-path -- check state and select/return if needed
        loopOnState { state ->
            if (select.isSelected) return
            if (state !is Incomplete) {
                // already complete -- select result
                if (select.trySelect(null)) {
                    if (state is CompletedExceptionally)
                        select.resumeSelectCancellableWithException(state.exception)
                    else
                        block.startCoroutineUndispatched(state, select.completion)
                }
                return
            }
            if (startInternal(state) == 0) {
                // slow-path -- register waiter for completion
                select.disposeOnSelect(invokeOnCompletion(handler = SelectAwaitOnCompletion(this, select, block)))
                return
            }
        }
    }

    internal fun <R> selectAwaitCompletion(select: SelectInstance<R>, block: suspend (Any?) -> R) {
        val state = this.state
        // Note: await is non-atomic (can be cancelled while dispatched)
        if (state is CompletedExceptionally)
            select.resumeSelectCancellableWithException(state.exception)
        else
            block.startCoroutineCancellable(state, select.completion)
    }
}

private const val RETRY = -1
private const val FALSE = 0
private const val TRUE = 1

@Suppress("PrivatePropertyName")
private val EmptyNew = Empty(false)
@Suppress("PrivatePropertyName")
private val EmptyActive = Empty(true)

private class Empty(override val isActive: Boolean) : JobSupport.Incomplete {
    override val list: JobSupport.NodeList? get() = null
    override fun toString(): String = "Empty{${if (isActive) "Active" else "New" }}"
}

private class JobImpl(parent: Job? = null) : JobSupport(true) {
    init { initParentJob(parent) }
}

// -------- invokeOnCompletion nodes

internal abstract class JobNode<out J : Job>(
    @JvmField val job: J
) : LockFreeLinkedListNode(), DisposableHandle, CompletionHandler, JobSupport.Incomplete {
    final override val isActive: Boolean get() = true
    final override val list: JobSupport.NodeList? get() = null
    final override fun dispose() = (job as JobSupport).removeNode(this)
    override abstract fun invoke(reason: Throwable?)
}

private class InvokeOnCompletion(
    job: Job,
    private val handler: CompletionHandler
) : JobNode<Job>(job)  {
    override fun invoke(reason: Throwable?) = handler.invoke(reason)
    override fun toString() = "InvokeOnCompletion[${handler::class.java.name}@${Integer.toHexString(System.identityHashCode(handler))}]"
}

private class ResumeOnCompletion(
    job: Job,
    private val continuation: Continuation<Unit>
) : JobNode<Job>(job)  {
    override fun invoke(reason: Throwable?) = continuation.resume(Unit)
    override fun toString() = "ResumeOnCompletion[$continuation]"
}

internal class DisposeOnCompletion(
    job: Job,
    private val handle: DisposableHandle
) : JobNode<Job>(job) {
    override fun invoke(reason: Throwable?) = handle.dispose()
    override fun toString(): String = "DisposeOnCompletion[$handle]"
}

private class CancelFutureOnCompletion(
    job: Job,
    private val future: Future<*>
) : JobNode<Job>(job)  {
    override fun invoke(reason: Throwable?) {
        // Don't interrupt when cancelling future on completion, because no one is going to reset this
        // interruption flag and it will cause spurious failures elsewhere
        future.cancel(false)
    }
    override fun toString() = "CancelFutureOnCompletion[$future]"
}

private class SelectJoinOnCompletion<R>(
    job: JobSupport,
    private val select: SelectInstance<R>,
    private val block: suspend () -> R
) : JobNode<JobSupport>(job) {
    override fun invoke(reason: Throwable?) {
        if (select.trySelect(null))
            block.startCoroutineCancellable(select.completion)
    }
    override fun toString(): String = "SelectJoinOnCompletion[$select]"
}

private class SelectAwaitOnCompletion<R>(
    job: JobSupport,
    private val select: SelectInstance<R>,
    private val block: suspend (Any?) -> R
) : JobNode<JobSupport>(job) {
    override fun invoke(reason: Throwable?) {
        if (select.trySelect(null))
            job.selectAwaitCompletion(select, block)
    }
    override fun toString(): String = "SelectAwaitOnCompletion[$select]"
}

// -------- invokeOnCancellation nodes

/**
 * Marker for node that shall be invoked on cancellation (in _cancelling_ state).
 * **Note: may be invoked multiple times during cancellation.**
 */
internal abstract class JobCancellationNode<out J : Job>(job: J) : JobNode<J>(job)

private class InvokeOnCancellation(
    job: Job,
    private val handler: CompletionHandler
) : JobCancellationNode<Job>(job)  {
    // delegate handler shall be invoked at most once, so here is an additional flag
    private val _invoked = atomic(0)
    override fun invoke(reason: Throwable?) {
        if (_invoked.compareAndSet(0, 1)) handler.invoke(reason)
    }
    override fun toString() = "InvokeOnCancellation[${handler::class.java.name}@${Integer.toHexString(System.identityHashCode(handler))}]"
}

internal class Child(
    parent: JobSupport,
    val child: Job
) : JobCancellationNode<JobSupport>(parent) {
    override fun invoke(reason: Throwable?) {
        // Always materialize the actual instance of parent's completion exception and cancel child with it
        child.cancel(job.getCancellationException())
    }
    override fun toString(): String = "Child[$child]"
}

private class ChildCompletion(
    private val parent: JobSupport,
    private val waitChild: Child,
    private val proposedUpdate: Any?
) : JobNode<Job>(waitChild.child) {
    override fun invoke(reason: Throwable?) {
        parent.continueCompleting(waitChild, proposedUpdate)
    }
}

