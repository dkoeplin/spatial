package spatial

import argon.core.Reporting
import com.typesafe.config.ConfigFactory
import pureconfig._

object SpatialConfig extends Reporting {
  import argon.Config._

  case class SpatialConf(
    fpga: String,
    sim: Boolean,
    synth: Boolean,
    pir: Boolean,
    dse: Boolean,
    dot: Boolean,
    retiming: Boolean,
    splitting: Boolean,
    archDSE: Boolean,
    naming: Boolean,
    tree: Boolean
  )
  case class PlasticineConf(
    sinUcu: Int,
    stagesUcu: Int,
    sinPcu: Int,
    soutPcu:Int,
    vinPcu: Int,
    voutPcu: Int,
    regsPcu: Int,
    comp: Int,
    sinPmu: Int,
    soutPmu:Int,
    vinPmu: Int,
    voutPmu: Int,
    regsPmu: Int,
    rw: Int,
    lanes: Int
  )

  var targetName: String = _

  var enableDSE: Boolean = _
  var enableDot: Boolean = _

  var enableSim: Boolean = _
  var enableSynth: Boolean = _
  var enablePIR: Boolean = _
  var enablePIRSim: Boolean = false

  var enableRetiming: Boolean = _

  var enableSplitting: Boolean = _
  var enableArchDSE: Boolean = _
  var enableNaming: Boolean = _
  var enableTree: Boolean = _

  var sIn_UCU: Int = _
  var stages_UCU: Int = _

  var sIn_PCU: Int = _
  var sOut_PCU: Int = _
  var vIn_PCU: Int = _
  var vOut_PCU: Int = _
  var stages: Int = _
  var regs_PCU: Int = _
  var sIn_PMU: Int = _
  var sOut_PMU: Int = _
  var vIn_PMU: Int = _
  var vOut_PMU: Int = _
  var readWrite: Int = _
  var regs_PMU: Int = _
  var lanes: Int = _

  var threads: Int = 8

  def init(): Unit = {
    val defaultSpatial = ConfigFactory.parseString("""
spatial {
  fpga = "Default"
  sim = true
  synth = false
  pir = false
  dse = false
  dot = false
  retiming = false
  splitting = false
  arch-dse = false
  naming = false
  tree = false
}
""")

    val mergedSpatialConf = ConfigFactory.load().withFallback(defaultSpatial).resolve()
    loadConfig[SpatialConf](mergedSpatialConf, "spatial") match {
      case Right(spatialConf) =>
        targetName = spatialConf.fpga

        enableDSE = spatialConf.dse
        enableDot = spatialConf.dot

        enableSim = spatialConf.sim
        enableSynth = spatialConf.synth
        enablePIR = spatialConf.pir

        enableRetiming = spatialConf.retiming

        enableSplitting = spatialConf.splitting
        enableArchDSE = spatialConf.archDSE
        enableNaming = spatialConf.naming
        enableTree = spatialConf.tree

      case Left(failures) =>
        error("Unable to read spatial configuration")
        error(failures.head.description)
        failures.tail.foreach{x => error(x.description) }
        sys.exit(-1)
    }

    val defaultPlasticine =  ConfigFactory.parseString("""
plasticine {
  sin_ucu = 10
  stages-ucu = 10
  sin-pcu = 10
  sout-pcu = 10
  vin-pcu = 4
  vout-pcu = 1
  regs-pcu = 16
  comp = 10
  sin-pmu = 10
  sout-pmu = 10
  vin-pmu = 4
  vout-pmu = 1
  regs-pmu = 16
  rw = 10
  lanes = 16
}
  """)

    val mergedPlasticineConf = ConfigFactory.load().withFallback(defaultPlasticine).resolve()

    loadConfig[PlasticineConf](mergedPlasticineConf, "plasticine") match {
      case Right(plasticineConf) =>
        sIn_UCU = plasticineConf.sinUcu
        stages_UCU = plasticineConf.stagesUcu
        sIn_PCU = plasticineConf.sinPcu
        sOut_PCU = plasticineConf.soutPcu
        vIn_PCU = plasticineConf.vinPcu
        vOut_PCU = plasticineConf.voutPcu
        stages = plasticineConf.comp
        regs_PCU = plasticineConf.regsPcu
        sIn_PMU = plasticineConf.sinPmu
        sOut_PMU = plasticineConf.soutPmu
        vIn_PMU = plasticineConf.vinPmu
        vOut_PMU = plasticineConf.voutPmu
        readWrite = plasticineConf.rw
        regs_PMU = plasticineConf.regsPmu
        lanes = plasticineConf.lanes

      case Left(failures) =>
//        error("Unable to read Plasticine configuration")
//        error(failures.head.description)
//        failures.tail.foreach{x => error(x.description) }
//        sys.exit(-1)
    }
  }

}
