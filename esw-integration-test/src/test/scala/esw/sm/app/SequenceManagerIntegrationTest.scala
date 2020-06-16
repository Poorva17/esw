package esw.sm.app

import java.io.File
import java.nio.file.{Files, Path, Paths}

import akka.Done
import akka.actor.CoordinatedShutdown
import csw.location.api.models.ComponentId
import csw.location.api.models.ComponentType.{Sequencer, Service}
import csw.location.api.models.Connection.{AkkaConnection, HttpConnection}
import csw.prefix.models.Prefix
import csw.prefix.models.Subsystem._
import esw.ocs.api.actor.client.{SequenceComponentImpl, SequencerImpl}
import esw.ocs.app.SequencerApp
import esw.ocs.app.SequencerAppCommand.SequenceComponent
import esw.ocs.app.wiring.SequenceComponentWiring
import esw.ocs.testkit.EswTestKit
import esw.sm.api.SequenceManagerApi
import esw.sm.api.actor.client.SequenceManagerApiFactory
import esw.sm.api.protocol.CommonFailure.ConfigurationMissing
import esw.sm.api.protocol.ConfigureResponse.ConflictingResourcesWithRunningObsMode
import esw.sm.api.protocol.StartSequencerResponse.LoadScriptError
import esw.sm.api.protocol._
import esw.sm.app.SequenceManagerAppCommand.StartCommand

import scala.collection.mutable.ArrayBuffer

class SequenceManagerIntegrationTest extends EswTestKit {
  private val WFOS_CAL              = "WFOS_Cal"
  private val IRIS_CAL              = "IRIS_Cal"
  private val IRIS_DARKNIGHT        = "IRIS_DarkNight"
  private val sequenceManagerPrefix = Prefix(ESW, "sequence_manager")

  override protected def beforeEach(): Unit = locationService.unregisterAll()
  override protected def afterEach(): Unit  = TestSetup.cleanup()

  "start sequence manager and register akka + http locations| ESW-171, ESW-172" in {
    // resolving sequence manager fails for Akka and Http
    intercept[Exception](resolveAkkaLocation(sequenceManagerPrefix, Service))
    intercept[Exception](resolveHTTPLocation(sequenceManagerPrefix, Service))

    TestSetup.startSequenceManager()

    // verify sequence manager is started and AkkaLocation & HttpLocation are registered with location service
    resolveAkkaLocation(sequenceManagerPrefix, Service).prefix shouldBe sequenceManagerPrefix
    resolveHTTPLocation(sequenceManagerPrefix, Service).prefix shouldBe sequenceManagerPrefix
  }

  "configure and cleanup for provided observation mode | ESW-162, ESW-166, ESW-164, ESW-171" in {
    val eswSeqCompPrefix   = Prefix(ESW, "primary")
    val irisSeqCompPrefix  = Prefix(IRIS, "primary")
    val aoeswSeqCompPrefix = Prefix(AOESW, "primary")

    TestSetup.startSequenceComponents(eswSeqCompPrefix, irisSeqCompPrefix, aoeswSeqCompPrefix)

    // ESW-171: Starts SM and returns SM Http client.
    val sequenceManagerClient = TestSetup.startSequenceManager()

    val eswIrisCalPrefix   = Prefix(ESW, IRIS_CAL)
    val irisCalPrefix      = Prefix(IRIS, IRIS_CAL)
    val aoeswIrisCalPrefix = Prefix(AOESW, IRIS_CAL)

    // ************ Configure for observing mode ************************
    val configureResponse = sequenceManagerClient.configure(IRIS_CAL).futureValue

    // assert for Successful Configuration
    configureResponse shouldBe a[ConfigureResponse.Success]

    // ESW-162 verify configure response returns master sequencer ComponentId
    val masterSequencerLocation = resolveHTTPLocation(eswIrisCalPrefix, Sequencer)
    configureResponse should ===(ConfigureResponse.Success(masterSequencerLocation.connection.componentId))

    // ESW-162 (verify all appropriate Sequencers are started based on observing mode)
    resolveSequencerLocation(eswIrisCalPrefix).connection should ===(sequencerConnection(eswIrisCalPrefix))
    resolveSequencerLocation(irisCalPrefix).connection should ===(sequencerConnection(irisCalPrefix))
    resolveSequencerLocation(aoeswIrisCalPrefix).connection should ===(sequencerConnection(aoeswIrisCalPrefix))

    // ESW-164 assert that sequence components have loaded sequencer scripts
    assertThatSeqCompIsLoadedWithScript(eswSeqCompPrefix)
    assertThatSeqCompIsLoadedWithScript(irisSeqCompPrefix)
    assertThatSeqCompIsLoadedWithScript(aoeswSeqCompPrefix)

    // *************** Cleanup for observing mode ********************
    val cleanupResponse = sequenceManagerClient.cleanup(IRIS_CAL).futureValue

    // assert for Successful Cleanup
    cleanupResponse should ===(CleanupResponse.Success)

    // ESW-166 verify all sequencers are stopped for the observing mode and seq comps are available
    assertThatSeqCompIsAvailable(eswSeqCompPrefix)
    assertThatSeqCompIsAvailable(irisSeqCompPrefix)
    assertThatSeqCompIsAvailable(aoeswSeqCompPrefix)
  }

  "configure should run multiple obs modes in parallel if resources are not conflicting | ESW-168, ESW-169, ESW-170, ESW-171" in {
    TestSetup.startSequenceComponents(
      Prefix(ESW, "primary"),
      Prefix(ESW, "secondary"),
      Prefix(IRIS, "primary"),
      Prefix(AOESW, "primary"),
      Prefix(WFOS, "primary"),
      Prefix(TCS, "primary")
    )
    val sequenceManagerClient = TestSetup.startSequenceManager()

    // Configure for "IRIS_Cal" observing mode should be successful as the resources are available
    sequenceManagerClient.configure(IRIS_CAL).futureValue shouldBe a[ConfigureResponse.Success]

    // *************** Avoid conflicting sequence execution | ESW-169 ********************
    // Configure for "IRIS_Darknight" observing mode should return error because resource IRIS and NFIRAOS are busy
    sequenceManagerClient.configure(IRIS_DARKNIGHT).futureValue should ===(ConflictingResourcesWithRunningObsMode(Set(IRIS_CAL)))

    // *************** Should run observation concurrently if no conflict in resources | ESW-168, ESW-170 ********************
    // Configure for "WFOS_Cal" observing mode should be successful as the resources are available
    sequenceManagerClient.configure(WFOS_CAL).futureValue shouldBe a[ConfigureResponse.Success]

    // Test cleanup
    sequenceManagerClient.cleanup(IRIS_CAL).futureValue
    sequenceManagerClient.cleanup(WFOS_CAL).futureValue
  }

  "start sequencer on esw sequence component as fallback if subsystem sequence component is not available | ESW-164, ESW-171" in {
    TestSetup.startSequenceComponents(Prefix(ESW, "primary"), Prefix(ESW, "secondary"), Prefix(IRIS, "primary"))
    val sequenceManagerClient = TestSetup.startSequenceManager()

    // ************ Configure for observing mode: sequencers required: [IRIS, ESW, AOESW] ************************
    sequenceManagerClient.configure(IRIS_CAL).futureValue shouldBe a[ConfigureResponse.Success]

    val aoeswSequencer          = resolveSequencer(AOESW, IRIS_CAL)
    val seqCompRunningSequencer = new SequencerImpl(aoeswSequencer).getSequenceComponent.futureValue

    // ESW-164 verify AOESW.IRIS_CAL sequencer is running on fallback ESW sequence component as AOESW sequence component
    // is not available
    seqCompRunningSequencer.prefix.subsystem shouldBe ESW

    //test cleanup
    sequenceManagerClient.cleanup(IRIS_CAL)
  }

  "throw exception if config file is missing | ESW-162, ESW-160, ESW-171" in {
    val exception = intercept[RuntimeException](SequenceManagerApp.main(Array("start", "-p", "sm-config.conf")))
    exception.getMessage shouldBe "File does not exist on local disk at path sm-config.conf"
  }

  "start and shut down sequencer for given subsystem and observation mode | ESW-176, ESW-326, ESW-171" in {
    TestSetup.startSequenceComponents(Prefix(ESW, "primary"))

    val sequenceManagerClient = TestSetup.startSequenceManager()

    // verify that sequencer is not present
    intercept[Exception](resolveHTTPLocation(Prefix(ESW, IRIS_DARKNIGHT), Sequencer))

    val response = sequenceManagerClient.startSequencer(ESW, IRIS_DARKNIGHT).futureValue

    // ESW-176 Verify that start sequencer return Started response with component id for master sequencer
    response should ===(StartSequencerResponse.Started(ComponentId(Prefix(ESW, IRIS_DARKNIGHT), Sequencer)))

    // verify that sequencer is started
    resolveHTTPLocation(Prefix(ESW, IRIS_DARKNIGHT), Sequencer)

    // ESW-326 Verify that shutdown sequencer returns Success
    val shutdownResponse = sequenceManagerClient.shutdownSequencer(ESW, IRIS_DARKNIGHT).futureValue
    shutdownResponse should ===(ShutdownSequencerResponse.Success)

    // verify that sequencer are shut down
    intercept[Exception](resolveHTTPLocation(Prefix(ESW, IRIS_DARKNIGHT), Sequencer))
  }

  "restart a running sequencer for given subsystem and obsMode | ESW-327, ESW-171" in {
    TestSetup.startSequenceComponents(Prefix(ESW, "primary"))
    val componentId = ComponentId(Prefix(ESW, IRIS_DARKNIGHT), Sequencer)

    val sequenceManagerClient = TestSetup.startSequenceManager()

    sequenceManagerClient.startSequencer(ESW, IRIS_DARKNIGHT)

    // verify that sequencer is started
    resolveHTTPLocation(Prefix(ESW, IRIS_DARKNIGHT), Sequencer)

    // restart sequencer that is already running
    val secondRestartResponse = sequenceManagerClient.restartSequencer(ESW, IRIS_DARKNIGHT).futureValue
    // verify that restart sequencer return Success response with component id
    secondRestartResponse should ===(RestartSequencerResponse.Success(componentId))
  }

  "shutdown all the running sequencers | ESW-324, ESW-171" in {
    val irisDarkNightPrefix = Prefix(ESW, IRIS_DARKNIGHT)
    val irisCalPrefix       = Prefix(ESW, IRIS_CAL)

    val sequenceManagerClient = TestSetup.startSequenceManager()

    val darkNightSequencerL = spawnSequencer(ESW, IRIS_DARKNIGHT)
    val calSequencerL       = spawnSequencer(ESW, IRIS_CAL)

    // verify that darkNight sequencer has started
    resolveAkkaLocation(irisDarkNightPrefix, Sequencer) should ===(darkNightSequencerL)

    // verify that cal sequencer has started
    resolveAkkaLocation(irisCalPrefix, Sequencer) should ===(calSequencerL)

    // shut down all the sequencers that are running
    sequenceManagerClient.shutdownAllSequencers().futureValue should ===(ShutdownAllSequencersResponse.Success)

    // verify that darkNight sequencer is not present
    intercept[Exception](resolveAkkaLocation(irisDarkNightPrefix, Sequencer))

    // verify that cal sequencer is not present
    intercept[Exception](resolveAkkaLocation(irisCalPrefix, Sequencer))
  }

  "should return loadScript error if configuration is missing for subsystem observation mode | ESW-176, ESW-171" in {
    TestSetup.startSequenceComponents(Prefix(ESW, "primary"))

    val sequenceManagerClient = TestSetup.startSequenceManager()

    // verify that sequencer is not present
    intercept[Exception](resolveHTTPLocation(Prefix(ESW, "invalid_obs_mode"), Sequencer))

    val response: StartSequencerResponse = sequenceManagerClient.startSequencer(ESW, "invalid_obs_mode").futureValue

    response shouldBe a[LoadScriptError]
    val loadScriptError: LoadScriptError = response.asInstanceOf[LoadScriptError]
    loadScriptError.msg should ===("Script configuration missing for [ESW] with [invalid_obs_mode]")
  }

  "should support all observation modes in configuration file | ESW-160" in {
    val tmpPath = File.createTempFile("temp-config", ".conf").toPath
    File.createTempFile("temp-config", ".conf").deleteOnExit()
    Files.write(tmpPath, "esw-sm {\n  obsModes: {}}".getBytes)

    TestSetup.startSequenceComponents(Prefix(ESW, "primary"))
    val obsMode = "APS_Cal"

    // try to configure obsMode which is not present in script
    val sequenceManagerClient = TestSetup.startSequenceManager(tmpPath)
    sequenceManagerClient.configure(obsMode).futureValue shouldBe ConfigurationMissing(obsMode)

    // Add obs mode in config file
    Files.write(
      tmpPath,
      "esw-sm {\n  obsModes: {\n    APS_Cal: {\n      resources: [ESW, APS]\n      sequencers: [ESW]\n    } } }".getBytes
    )

    // unregister SM and start SM so configuration for obsMode can be picked up
    TestSetup.unregisterSequenceManager()
    val restartedSequenceManager    = TestSetup.startSequenceManager(tmpPath)
    val response: ConfigureResponse = restartedSequenceManager.configure(obsMode).futureValue

    // verify that configuration is successful
    response should ===(ConfigureResponse.Success(ComponentId(Prefix(ESW, obsMode), Sequencer)))
  }

  private def sequencerConnection(prefix: Prefix) = AkkaConnection(ComponentId(prefix, Sequencer))

  private def assertThatSeqCompIsAvailable(prefix: Prefix): Unit = assertSeqCompAvailability(isSeqCompAvailable = true, prefix)
  private def assertThatSeqCompIsLoadedWithScript(prefix: Prefix): Unit =
    assertSeqCompAvailability(isSeqCompAvailable = false, prefix)

  private def assertSeqCompAvailability(isSeqCompAvailable: Boolean, prefix: Prefix): Unit = {
    val seqCompStatus = new SequenceComponentImpl(resolveSequenceComponentLocation(prefix)).status.futureValue
    if (isSeqCompAvailable) seqCompStatus.response shouldBe None // assert sequence component is available
    else seqCompStatus.response.isDefined shouldBe true          // assert sequence components is busy
  }

  object TestSetup {
    private val seqCompWirings    = ArrayBuffer.empty[SequenceComponentWiring]
    private val seqManagerWirings = ArrayBuffer.empty[SequenceManagerWiring]

    // Setup Sequence components for subsystems
    def startSequenceComponents(prefixes: Prefix*): Unit =
      prefixes.foreach { prefix =>
        seqCompWirings += SequencerApp.run(SequenceComponent(prefix.subsystem, Some(prefix.componentName)))
      }

    val path: Path = Paths.get(ClassLoader.getSystemResource("smResources.conf").toURI)

    def startSequenceManager(configFilePath: Path = path): SequenceManagerApi = {
      val wiring = SequenceManagerApp.run(StartCommand(configFilePath))
      seqManagerWirings += wiring
      val smLocation = resolveHTTPLocation(sequenceManagerPrefix, Service)
      SequenceManagerApiFactory.make(smLocation)
    }

    def unregisterSequenceManager(): Done = {
      seqManagerWirings.clear()
      locationService.unregister(AkkaConnection(ComponentId(sequenceManagerPrefix, Service))).futureValue
      locationService.unregister(HttpConnection(ComponentId(sequenceManagerPrefix, Service))).futureValue
    }

    def cleanup(): Unit = {
      seqCompWirings.foreach(_.cswWiring.actorRuntime.shutdown(CoordinatedShutdown.JvmExitReason).futureValue)
      seqManagerWirings.foreach(_.shutdown(CoordinatedShutdown.JvmExitReason).futureValue)
      seqCompWirings.clear()
      seqManagerWirings.clear()
    }
  }
}
