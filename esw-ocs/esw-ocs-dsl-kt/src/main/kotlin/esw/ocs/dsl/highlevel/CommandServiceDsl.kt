package esw.ocs.dsl.highlevel

import akka.actor.typed.ActorSystem
import akka.util.Timeout
import csw.command.api.javadsl.ICommandService
import csw.command.client.CommandServiceFactory
import csw.location.api.javadsl.ILocationService
import csw.location.api.javadsl.JComponentType.Assembly
import csw.location.api.javadsl.JComponentType.HCD
import csw.location.models.AkkaLocation
import csw.location.models.ComponentId
import csw.location.models.ComponentType
import csw.location.models.Connection
import csw.params.commands.*
import csw.params.commands.CommandResponse.*
import csw.params.core.models.ObsId
import csw.params.core.models.Prefix
import esw.ocs.dsl.nullable
import kotlinx.coroutines.future.await
import scala.concurrent.duration.Duration.create
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

interface CommandServiceDsl {
    val locationService: ILocationService
    val actorSystem: ActorSystem<*>

    companion object {
        private val duration: Duration = Duration.ofSeconds(10)
        private val timeout: Timeout = Timeout(create(10, TimeUnit.SECONDS))
    }

    fun setup(prefix: String, commandName: String, obsId: String? = null) =
            Setup(Prefix(prefix), CommandName(commandName), obsId.toOptionalObsId())

    fun observe(prefix: String, commandName: String, obsId: String? = null) =
            Observe(Prefix(prefix), CommandName(commandName), obsId.toOptionalObsId())

    fun sequenceOf(vararg sequenceCommand: SequenceCommand): Sequence = Sequence.create(sequenceCommand.toList())

    suspend fun Assembly(name: String) = InternalCommandServiceHandler(commandService(name, Assembly()), timeout)
    suspend fun HCD(name: String) = InternalCommandServiceHandler(commandService(name, HCD()), timeout)

    private suspend fun commandService(
            name: String,
            compType: ComponentType
    ): ICommandService = resolve(name, compType)
            .orElseThrow { IllegalArgumentException("Could not find component - $name of type - $compType") }
            .let { CommandServiceFactory.jMake(it, actorSystem) }


    private suspend fun resolve(name: String, compType: ComponentType): Optional<AkkaLocation> =
            locationService.resolve(Connection.AkkaConnection(ComponentId(name, compType)), duration).await()

    /** ========== Extensions ============ **/
    val Command.obsId: String? get() = jMaybeObsId().map { it.obsId() }.nullable()

    private fun String?.toOptionalObsId() = Optional.ofNullable(this?.let { ObsId(it) })
}
