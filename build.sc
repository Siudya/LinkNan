import mill._
import scalalib._
import scalafmt._
import $file.dependencies.`rocket-chip`.common

val defaultVersions = Map(
  "chisel" -> "6.1.0",
  "chisel-plugin" -> "6.1.0",
  "chiseltest" -> "5.0.0",
  "scala" -> "2.13.10",
  "scalatest" -> "3.2.7"
)

def getVersion(dep: String, org: String = "org.chipsalliance", cross: Boolean = false) = {
  val version = sys.env.getOrElse(dep + "Version", defaultVersions(dep))
  if(cross)
    ivy"$org:::$dep:$version"
  else
    ivy"$org::$dep:$version"
}

trait CommonModule extends ScalaModule {
  override def scalaVersion = defaultVersions("scala")
  override def scalacPluginIvyDeps = Agg(getVersion("chisel-plugin", cross = true))
  override def scalacOptions = super.scalacOptions() ++ Agg("-Ymacro-annotations", "-Ytasty-reader")
  override def ivyDeps = super.ivyDeps() ++ Agg(
    getVersion("chisel"),
    getVersion("chiseltest", "edu.berkeley.cs"),
    ivy"org.chipsalliance:llvm-firtool:1.62.1"
  )
}

object cde extends CommonModule {
  override def millSourcePath = os.pwd / "dependencies" / "cde" / "cde"
}

object hardfloat extends CommonModule {
  override def millSourcePath = os.pwd / "dependencies" / "hardfloat" / "hardfloat"
}

object rocketchip extends millbuild.dependencies.`rocket-chip`.common.RocketChipModule {
  override def millSourcePath = os.pwd / "dependencies" / "rocket-chip"
  def scalaVersion: T[String] = T(defaultVersions("scala"))
  def chiselModule = None
  def chiselPluginJar = None
  def chiselIvy = Some(getVersion("chisel"))
  def chiselPluginIvy = Some(getVersion("chisel-plugin", cross = true))
  def macrosModule = macros
  def hardfloatModule = hardfloat
  def cdeModule = cde
  def mainargsIvy = ivy"com.lihaoyi::mainargs:0.5.0"
  def json4sJacksonIvy = ivy"org.json4s::json4s-jackson:4.0.5"

  object macros extends millbuild.dependencies.`rocket-chip`.common.MacrosModule {
    def scalaVersion: T[String] = T(defaultVersions("scala"))
    def scalaReflectIvy = ivy"org.scala-lang:scala-reflect:${defaultVersions("scala")}"
  }
}

object xsutils extends SbtModule with CommonModule {
  override def millSourcePath = os.pwd / "dependencies" / "xs-utils"
  override def moduleDeps = super.moduleDeps ++ Seq(rocketchip, cde)
}

object difftest extends SbtModule with CommonModule {
  override def millSourcePath = os.pwd / "dependencies" / "difftest"
}

object fudian extends SbtModule with CommonModule {
  override def millSourcePath = os.pwd / "dependencies" / "fudian"
}

object zhujiang extends SbtModule with CommonModule {
  override def millSourcePath = os.pwd / "dependencies" / "zhujiang"
  override def moduleDeps = super.moduleDeps ++ Seq(rocketchip, xsutils)
}

object axi2tl extends SbtModule with CommonModule {
  override def millSourcePath = os.pwd / "dependencies" / "nanhu" / "coupledL2" / "AXItoTL"
  override def moduleDeps = super.moduleDeps ++ Seq(rocketchip, xsutils)
}

object huancun extends SbtModule with CommonModule {
  override def millSourcePath = os.pwd / "dependencies" / "nanhu" / "huancun"
  override def moduleDeps = super.moduleDeps ++ Seq(rocketchip, xsutils)
}

object cpl2 extends SbtModule with CommonModule {
  override def millSourcePath = os.pwd / "dependencies" / "nanhu" / "coupledL2"
  override def moduleDeps = super.moduleDeps ++ Seq(rocketchip, xsutils, huancun, axi2tl)
}

object nhl2 extends SbtModule with CommonModule {
  override def millSourcePath = os.pwd / "dependencies" / "nhl2"
  override def moduleDeps = super.moduleDeps ++ Seq(rocketchip, xsutils, huancun, cpl2)
}

object nanhu extends SbtModule with CommonModule {
  override def millSourcePath = os.pwd / "dependencies" / "nanhu"
  override def moduleDeps = super.moduleDeps ++ Seq(rocketchip, xsutils, cpl2, huancun, axi2tl, fudian, difftest)
}

object nansha extends SbtModule with CommonModule {
  override def millSourcePath = os.pwd
  override def moduleDeps = super.moduleDeps ++ Seq(rocketchip, xsutils, nhl2, zhujiang, nanhu)

  object test extends SbtModuleTests with TestModule.ScalaTest {
    override def ivyDeps = super.ivyDeps() ++ Agg(
      getVersion("scalatest", "org.scalatest")
    )
    def testFramework = "org.scalatest.tools.Framework"
  }
}