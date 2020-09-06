package esw.agent.service

import java.nio.file.Paths

import csw.location.api.models.ComponentType.{SequenceComponent, Service}
import csw.location.api.models.Connection.AkkaConnection
import csw.location.api.models.{ComponentId, ComponentType}
import csw.prefix.models.Prefix
import csw.prefix.models.Subsystem.ESW
import esw.agent.akka.app.AgentSettings
import esw.agent.service.api.AgentServiceApi
import esw.agent.service.api.client.AgentServiceClientFactory
import esw.agent.service.api.models.{Killed, Spawned}
import esw.agent.service.app.{AgentServiceApp, AgentServiceWiring}
import esw.ocs.testkit.EswTestKit
import esw.ocs.testkit.Service.AAS

import scala.concurrent.duration.DurationInt

class AgentServiceIntegrationTest extends EswTestKit(AAS) {

  implicit val patience: PatienceConfig = PatienceConfig(1.minute)

  private var agentService: AgentServiceApi          = _
  private var agentServiceWiring: AgentServiceWiring = _

  //start agent
  private lazy val eswVersion      = Some("0.1.0-SNAPSHOT")
  private lazy val channel: String = "file://" + getClass.getResource("/apps.json").getPath
  private lazy val eswAgentPrefix  = Prefix(ESW, "machine_A1")

  override def beforeAll(): Unit = {
    super.beforeAll()
    //start agent service
    spawnAgent(AgentSettings(eswAgentPrefix, 1.minute, channel))
    agentServiceWiring = AgentServiceApp.start()
    val httpLocation = resolveHTTPLocation(agentServiceWiring.prefix, ComponentType.Service)
    agentService = AgentServiceClientFactory(httpLocation, () => tokenWithEswUserRole())
  }

  override def afterAll(): Unit = {
    agentServiceWiring.stop().futureValue
    super.afterAll()
  }

  "AgentService" must {
    "start and kill sequence component on the given agent | ESW-361, ESW-367" in {
      val seqCompName   = "ESW_1"
      val seqCompPrefix = Prefix(eswAgentPrefix.subsystem, seqCompName)

      // spawn seq comp
      agentService.spawnSequenceComponent(eswAgentPrefix, seqCompName, eswVersion).futureValue shouldBe Spawned

      //verify component is started
      resolveSequenceComponent(seqCompPrefix)

      // stop spawned component
      agentService.killComponent(AkkaConnection(ComponentId(seqCompPrefix, SequenceComponent))).futureValue shouldBe Killed

      //verify that component is killed
      intercept[RuntimeException](resolveSequenceComponent(seqCompPrefix))
    }

    "start and kill sequence manager on the given agent | ESW-361, ESW-367" in {
      val smPrefix = Prefix(ESW, "sequence_manager")

      // spawn sequence manager
      val obsModeConfigPath = Paths.get(ClassLoader.getSystemResource("smObsModeConfig.conf").toURI)
      agentService
        .spawnSequenceManager(eswAgentPrefix, obsModeConfigPath, isConfigLocal = true, eswVersion)
        .futureValue shouldBe Spawned

      //verify sequence manager is started
      resolveAkkaLocation(smPrefix, Service)

      // stop sequence manager
      val smConnection = AkkaConnection(ComponentId(smPrefix, Service))
      agentService.killComponent(smConnection).futureValue shouldBe Killed

      //verify that component is killed
      intercept[RuntimeException](resolveAkkaLocation(smPrefix, Service))
    }
  }
}