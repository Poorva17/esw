package esw.ocs.dsl.highlevel

import akka.Done
import akka.actor.Cancellable
import csw.event.api.javadsl.IEventPublisher
import csw.event.api.javadsl.IEventSubscriber
import csw.event.api.scaladsl.SubscriptionModes
import csw.params.core.generics.Key
import csw.params.core.generics.Parameter
import csw.params.events.*
import esw.ocs.dsl.SuspendableConsumer
import esw.ocs.dsl.SuspendableSupplier
import esw.ocs.dsl.epics.EventVariable
import esw.ocs.dsl.epics.EventVariableImpl
import esw.ocs.dsl.epics.ParamVariable
import esw.ocs.dsl.highlevel.models.EventSubscription
import esw.ocs.dsl.highlevel.models.Prefix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import java.util.*
import kotlin.time.Duration
import kotlin.time.toJavaDuration

interface EventServiceDsl {
    val coroutineScope: CoroutineScope
    val eventPublisher: IEventPublisher
    val eventSubscriber: IEventSubscriber

    /**
     * Method to create an instance of [[csw.params.events.EventKey]]
     *
     * @param prefix of the component which publishes this event
     * @param eventName represents the name of the event
     * @return an instance of [[csw.params.events.EventKey]]
     */
    fun EventKey(prefix: String, eventName: String): EventKey = EventKey(Prefix(prefix), EventName(eventName))

    /**
     * Method to create an instance of [[csw.params.events.EventKey]]
     *
     * @param eventKeyStr string representation of event key
     * @return an instance of [[csw.params.events.EventKey]]
     */
    fun EventKey(eventKeyStr: String): EventKey = EventKey.apply(eventKeyStr)

    /**
     * Method to create an instance of [[csw.params.events.SystemEvent]]
     *
     * @param sourcePrefix of the component which publishes this event
     * @param eventName represents the name of the event
     * @param parameters to be added in the event
     * @return an instance of [[csw.params.events.SystemEvent]]
     */
    fun SystemEvent(sourcePrefix: String, eventName: String, vararg parameters: Parameter<*>): SystemEvent =
            SystemEvent(Prefix(sourcePrefix), EventName(eventName)).jMadd(parameters.toSet())

    /**
     * Method to create an instance of [[csw.params.events.ObserveEvent]]
     *
     * @param sourcePrefix of the component which publishes this event
     * @param eventName represents the name of the event
     * @param parameters to be added to the event
     * @return an instance of [[csw.params.events.ObserveEvent]]
     */
    fun ObserveEvent(sourcePrefix: String, eventName: String, vararg parameters: Parameter<*>): ObserveEvent =
            ObserveEvent(Prefix(sourcePrefix), EventName(eventName)).jMadd(parameters.toSet())

    /**
     * Publishes the given `event`. Throws [[csw.event.api.exceptions.EventServerNotAvailable]] when event server is not available or
     * [[csw.event.api.exceptions.PublishFailure]] containing the cause for other failures.
     *
     * @param event to publish
     * @return [[akka.Done]] when event is published
     */
    suspend fun publishEvent(event: Event): Done = eventPublisher.publish(event).await()

    /**
     * Publishes the event generated by `eventGenerator` at `every` frequency. Cancellable can used to
     * stop the publishing. Throws [[csw.event.api.exceptions.EventServerNotAvailable]] when event server is not available or
     * [[csw.event.api.exceptions.PublishFailure]] containing the cause for other failures.
     *
     * @param every frequency with which the events are to be published
     * @param eventGenerator function which will be called at given frequency to generate an event to be published
     * @return handle of [[akka.actor.Cancellable]] which can be used to stop event publishing
     */
    fun publishEvent(every: Duration, eventGenerator: SuspendableSupplier<Event?>): Cancellable =
            eventPublisher.publishAsync({
                coroutineScope.future { Optional.ofNullable(eventGenerator()) }
            }, every.toJavaDuration())

    /**
     * Subscribes to the `eventKeys` which will execute the given `callback` whenever an event is published on any one of the event keys.
     * Throws [[csw.event.api.exceptions.EventServerNotAvailable]] when event server is not available.
     *
     * @param eventKeys collection of strings representing [[csw.params.events.EventKey]]
     * @param callback to be executed whenever event is published on provided keys
     * @return handle of [[esw.ocs.dsl.highlevel.models.EventSubscription]] which can be used to cancel the subscription
     */
    suspend fun onEvent(vararg eventKeys: String, callback: SuspendableConsumer<Event>): EventSubscription {
        val subscription = eventSubscriber.subscribeAsync(eventKeys.toEventKeys()) { coroutineScope.future { callback(it) } }
        subscription.ready().await()
        return EventSubscription { subscription.unsubscribe().await() }
    }

    /**
     * Subscribes to the given `eventKeys` and will execute the given `callback` on tick of specified `duration` with the latest event available.
     * Throws [[csw.event.api.exceptions.EventServerNotAvailable]] when event server is not available.
     *
     * @param eventKeys collection of strings representing [[csw.params.events.EventKey]]
     * @param duration which determines the frequency with which events are received
     * @param callback to be executed whenever event is published on provided keys
     * @return handle of [[esw.ocs.dsl.highlevel.models.EventSubscription]] which can be used to cancel the subscription
     */
    suspend fun onEvent(vararg eventKeys: String, duration: Duration, callback: SuspendableConsumer<Event>): EventSubscription {
        val cb = { event: Event -> coroutineScope.future { callback(event) } }
        val subscription = eventSubscriber
                .subscribeAsync(eventKeys.toEventKeys(), cb, duration.toJavaDuration(), SubscriptionModes.jRateAdapterMode())
        subscription.ready().await()
        return EventSubscription { subscription.unsubscribe().await() }
    }

    /**
     * Method to get the latest event of all the provided `eventKeys`. Invalid event will be given if no event is published on one or more keys.
     * Throws [[csw.event.api.exceptions.EventServerNotAvailable]] when event server is not available.
     *
     * @param eventKeys collection of strings representing [[csw.params.events.EventKey]].
     * @return a [[kotlin.collections.Set]] of [[csw.params.events.Event]]
     */
    suspend fun getEvent(vararg eventKeys: String): Set<Event> =
            eventSubscriber.get(eventKeys.toEventKeys()).await().toSet()

    /**
     * Method to create an instance of [[esw.ocs.dsl.epics.ParamVariable]] tied to the particular param `key` of an [[csw.params.events.Event]]
     * being published on specific `event key`.
     *
     * [[esw.ocs.dsl.epics.ParamVariable]] is [[esw.ocs.dsl.epics.EventVariable]] with methods to get and set a specific parameter in the [[csw.params.events.Event]]
     * It behaves differently depending on the presence of `duration` parameter while creating its instance.
     * - When provided with `duration`, it will **poll** at an interval of given `duration` to refresh its own value
     * - Otherwise it will **subscribe** to the given event key and will refresh its own value whenever events are published
     *
     * @param initial value to set to the parameter key of the event
     * @param eventKeyStr string representation of event key
     * @param key represents parameter key of the event to tie [[esw.ocs.dsl.epics.ParamVariable]] to
     * @param duration represents the interval of polling.
     * @return instance of [[esw.ocs.dsl.epics.ParamVariable]]
     */
    suspend fun <T> ParamVariable(initial: T, eventKeyStr: String, key: Key<T>, duration: Duration? = null): ParamVariable<T> =
            EventVariableImpl.createParamVariable(initial, key, eventKeyStr, this, duration)

    /**
     * Method to create an instance of [[esw.ocs.dsl.epics.EventVariable]] tied to an [[csw.params.events.Event]] being published on specified `event key`.
     *
     * [[esw.ocs.dsl.epics.EventVariable]] behaves differently depending on the presence of `duration` parameter while creating its instance.
     * - When provided with `duration`, it will **poll** at an interval of given `duration` to refresh its own value
     * - Otherwise it will **subscribe** to the given event key and will refresh its own value whenever events are published
     *
     * @param eventKeyStr string representation of event key
     * @param duration represents the interval of polling.
     * @ return instance of [[esw.ocs.dsl.epics.EventVariable]]
     */
    suspend fun EventVariable(eventKeyStr: String, duration: Duration? = null): EventVariable =
            EventVariableImpl.createEventVariable(eventKeyStr, this, duration)

    private fun (Array<out String>).toEventKeys(): Set<EventKey> = map { EventKey.apply(it) }.toSet()
}
