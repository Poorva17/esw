package esw.ocs.testData

import csw.params.commands.CommandResponse.Completed
import csw.params.commands.{CommandName, Sequence, Setup}
import csw.params.core.models.{Id, Prefix}
import esw.ocs.dsl.{CswServices, Script}

class TestScript(csw: CswServices) extends Script(csw) {

  handleSetupCommand("command-1") { command =>
    spawn {
      // To avoid sequencer to finish immediately so that other Add, Append command gets time
      Thread.sleep(100)
      csw.crm.addOrUpdateCommand(Completed(command.runId))
    }
  }

  handleSetupCommand("command-3") { command =>
    spawn {
      csw.crm.addOrUpdateCommand(Completed(command.runId))
    }
  }

  handleSetupCommand("command-2") { command =>
    spawn {
      csw.crm.addOrUpdateCommand(Completed(command.runId))
    }
  }

  handleSetupCommand("command-4") { command =>
    //try sending concrete sequence
    val command4       = Setup(Id("testCommandIdString123"), Prefix("esw.test"), CommandName("command-to-assert-on"), None, Set.empty)
    val sequence       = Sequence(Id("testSequenceIdString123"), Seq(command4))
    val otherSequencer = "TCS.test.sequencer1"

    spawn {
      csw.sequencerCommandService.submitSequence(otherSequencer, sequence).await
      csw.crm.addOrUpdateCommand(Completed(command.runId))
    }
  }

}
